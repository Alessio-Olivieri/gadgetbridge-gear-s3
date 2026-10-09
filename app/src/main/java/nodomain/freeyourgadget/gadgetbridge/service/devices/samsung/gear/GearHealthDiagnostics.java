package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;

/** Optional diagnostics only: no device, socket, cipher, database or protocol callbacks. */
final class GearHealthDiagnostics implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GearHealthDiagnostics.class);
    private final Function<byte[], Report> inspector;
    private final BiConsumer<Report, BooleanSupplier> reporter;
    private ThreadPoolExecutor executor;
    private volatile long generation;
    private volatile boolean closed;

    GearHealthDiagnostics(final BiConsumer<Report, BooleanSupplier> reporter) {
        this(GearHealthDiagnostics::inspect, reporter);
    }

    // Test seam permits blocking/failing inspection without delaying protocol handling.
    GearHealthDiagnostics(final Function<byte[], Report> inspector,
                          final BiConsumer<Report, BooleanSupplier> reporter) {
        this.inspector = inspector;
        this.reporter = reporter;
        restart();
    }

    synchronized void restart() {
        close();
        closed = false;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    final Thread thread = new Thread(runnable, "GearS3-health-diagnostics");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    synchronized void submit(final byte[] applicationBytes) {
        if (closed || applicationBytes == null) return;
        final long submittedGeneration = generation;
        // Never retain protocol buffers or mutable JSON. The worker exclusively owns this copy.
        final byte[] snapshot = Arrays.copyOf(applicationBytes, applicationBytes.length);
        final BooleanSupplier current = () -> !closed && generation == submittedGeneration;
        executor.execute(() -> {
            try {
                if (!current.getAsBoolean()) return;
                final Report report = inspector.apply(snapshot);
                if (current.getAsBoolean()) reporter.accept(report, current);
            } catch (final RuntimeException e) {
                // Do not expose payloads through parser exception messages.
                if (current.getAsBoolean()) LOG.warn("Gear S3 health diagnostic failed; protocol handling unaffected");
            } finally {
                Arrays.fill(snapshot, (byte) 0);
            }
        });
    }

    synchronized void invalidate() {
        generation++;
        if (executor != null) executor.getQueue().clear();
    }

    @Override public synchronized void close() {
        closed = true;
        invalidate();
        // Never join/await inspection from a Bluetooth lifecycle or UI callback.
        if (executor != null) executor.shutdownNow();
    }

    static Report inspect(final byte[] snapshot) {
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(snapshot);
        final SapPedometerJsonProbe.CommonSync commonSync = SapPedometerJsonProbe.inspectCommonSync(result);
        return new Report(result, commonSync);
    }

    static final class Report {
        final SapPedometerJsonProbe.Result result;
        final SapPedometerJsonProbe.CommonSync commonSync;

        Report(final SapPedometerJsonProbe.Result result, final SapPedometerJsonProbe.CommonSync commonSync) {
            this.result = result;
            this.commonSync = commonSync;
        }
    }
}
