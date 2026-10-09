package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3CommonSyncState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Synthetic encrypted RX and deliberately stalled diagnostics; no Bluetooth or private data. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearHealthDiagnosticsTest {
    private final List<GearHealthDiagnostics> workers = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    private GearS3DeviceSupport support;
    private SapHostManagerAppCipher sender;
    private SapHostManagerAppCipher receiver;

    @After public void cleanup() {
        release.countDown();
        if (support != null) support.dispose();
        for (GearHealthDiagnostics worker : workers) worker.close();
        lifecycle.shutdownNow();
    }

    private GearHealthDiagnostics worker(java.util.function.Function<byte[], GearHealthDiagnostics.Report> inspector,
            java.util.function.BiConsumer<GearHealthDiagnostics.Report, java.util.function.BooleanSupplier> reporter) {
        final GearHealthDiagnostics worker = new GearHealthDiagnostics(inspector, reporter);
        workers.add(worker); return worker;
    }

    private void configure(GearHealthDiagnostics worker) throws Exception {
        support = new GearS3DeviceSupport(worker);
        final SapPedometerServiceConnector connector = mock(SapPedometerServiceConnector.class);
        when(connector.isApplicationSession(77)).thenReturn(true);
        when(connector.getApplicationSessionId()).thenReturn(77);
        set("pedometerServiceConnector", connector);
        set("postAuthRecords", new SapPostAuthRecordCodec());
        sender = new SapHostManagerAppCipher(new byte[32]);
        receiver = new SapHostManagerAppCipher(new byte[32]);
        set("pedometerSecureDecoder", new SapPedometerSecureDecoder(receiver));
    }

    private void set(String name, Object value) throws Exception {
        final Field field = GearS3DeviceSupport.class.getDeclaredField(name); field.setAccessible(true); field.set(support, value);
    }

    private Object get(String name) throws Exception {
        final Field field = GearS3DeviceSupport.class.getDeclaredField(name); field.setAccessible(true); return field.get(support);
    }

    private static byte[] request(int sequence) throws Exception {
        final JSONArray body = new JSONArray().put(new JSONObject().put("message_info", new JSONArray()
                .put(new JSONObject().put("action", "SYNC_DATA"))));
        return new JSONObject().put("message", "REQUEST").put("type", "DATA").put("sequence_num", sequence)
                .put("sender", "com.samsung.tizengear.app.shealth.wearable.syncmanager")
                .put("receiver", "com.samsung.android.app.shealth.wearable.syncmanager")
                .put("body", body.toString()).toString().getBytes(StandardCharsets.UTF_8);
    }

    private void receive(int sequence) throws Exception {
        final byte[] protectedBody = sender.protect(request(sequence));
        final byte[] body = new byte[protectedBody.length + 1]; body[0] = 4;
        System.arraycopy(protectedBody, 0, body, 1, protectedBody.length);
        support.onSocketRead(SapPostAuthRecordCodec.encode(new SapPostAuthRecord(new SapTransportHeader(0, 0, 77, 0), body)));
        // Master dispatches application records asynchronously. Wait only for that ordered
        // protocol queue, never for the independently stalled diagnostic worker.
        ((ExecutorService) get("sapApplicationExecutor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static ThreadPoolExecutor executor(GearHealthDiagnostics worker) throws Exception {
        final Field field = GearHealthDiagnostics.class.getDeclaredField("executor");
        field.setAccessible(true); return (ThreadPoolExecutor) field.get(worker);
    }

    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(5, TimeUnit.SECONDS)); }

    // Simulate a parser that ignores interruption, so tests prove disposal never joins it.
    private void blockInspection() {
        boolean interrupted = false;
        for (;;) {
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test failed to release diagnostic"); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test public void encryptedSocketReadRunsInspectionAndReportingOutsideSupportMonitor() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean locked = new AtomicBoolean();
        final AtomicReference<String> thread = new AtomicReference<>();
        configure(worker(bytes -> {
            locked.set(Thread.holdsLock(support)); thread.set(Thread.currentThread().getName());
            return GearHealthDiagnostics.inspect(bytes);
        }, (report, current) -> {
            if (Thread.holdsLock(support)) locked.set(true);
            done.countDown();
        }));
        receive(1); await(done);
        assertFalse(locked.get()); assertEquals("GearS3-health-diagnostics", thread.get());
        assertEquals(1, receiver.getRxExpected());
        assertTrue(((GearS3CommonSyncState) get("commonSyncState")).isActive());
    }

    @Test public void mainThreadDisposeReturnsWhileDiagnosticInspectionIsBlocked() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        final AtomicInteger reports = new AtomicInteger();
        configure(worker(bytes -> {
            entered.countDown(); blockInspection(); exited.countDown(); return GearHealthDiagnostics.inspect(bytes);
        }, (report, current) -> reports.incrementAndGet()));
        receive(1); await(entered);
        final ThreadPoolExecutor running = executor(workers.get(0));
        lifecycle.submit(() -> support.dispose()).get(1, TimeUnit.SECONDS);
        assertEquals(1, release.getCount()); // Inspector is still blocked; disposal did not wait.
        release.countDown(); await(exited); assertTrue(running.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, reports.get());
    }

    @Test public void nextEncryptedRecordStillDecodesWhileDiagnosticsAreBlocked() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        configure(worker(bytes -> { entered.countDown(); blockInspection(); return GearHealthDiagnostics.inspect(bytes); }, (r, c) -> {}));
        receive(1); await(entered); receive(2);
        assertEquals(2, receiver.getRxExpected());
        assertTrue(((GearS3CommonSyncState) get("commonSyncState")).isActive());
    }

    @Test public void diagnosticExceptionCannotFailReaderOrFollowingHealthMessage() throws Exception {
        final CountDownLatch failed = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        configure(worker(bytes -> {
            if (calls.getAndIncrement() == 0) { failed.countDown(); throw new IllegalArgumentException("synthetic diagnostic failure"); }
            return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> done.countDown()));
        receive(1); await(failed); receive(2); await(done);
        assertEquals(2, receiver.getRxExpected()); assertNotNull(get("pedometerSecureDecoder"));
        assertTrue(((GearS3CommonSyncState) get("commonSyncState")).isActive());
    }

    @Test public void snapshotIsIndependentOfInputMutation() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger seen = new AtomicInteger();
        final GearHealthDiagnostics worker = worker(bytes -> {
            entered.countDown(); blockInspection(); return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> { seen.set(((Number) r.commonSync.getSequence()).intValue()); done.countDown(); });
        final byte[] bytes = request(12); worker.submit(bytes); await(entered);
        Arrays.fill(bytes, (byte) 0); release.countDown(); await(done); assertEquals(12, seen.get());
    }

    @Test public void diagnosticBacklogRetainsOnlyLatestPendingSnapshot() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(2);
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        final GearHealthDiagnostics worker = worker(bytes -> {
            if (calls.getAndIncrement() == 0) { entered.countDown(); blockInspection(); }
            return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> { seen.add(((Number) r.commonSync.getSequence()).intValue()); done.countDown(); });
        worker.submit(request(1)); await(entered);
        for (int i = 2; i <= 30; i++) worker.submit(request(i));
        release.countDown(); await(done); assertEquals(Arrays.asList(1, 30), seen);
    }

    @Test public void reconnectGenerationSuppressesOldReportAndRunsNewSnapshot() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), oldExited = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        final GearHealthDiagnostics worker = worker(bytes -> {
            if (calls.getAndIncrement() == 0) { entered.countDown(); blockInspection(); oldExited.countDown(); }
            return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> { seen.add(((Number) r.commonSync.getSequence()).intValue()); done.countDown(); });
        worker.submit(request(1)); await(entered); worker.submit(request(2));
        final ThreadPoolExecutor retired = executor(worker);
        worker.restart(); worker.submit(request(3)); await(done);
        release.countDown(); await(oldExited); assertTrue(retired.awaitTermination(5, TimeUnit.SECONDS)); assertEquals(Collections.singletonList(3), seen);
    }

    @Test public void disposedSupportCannotPublishIntoNewSupportInstance() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), oldExited = new CountDownLatch(1), fresh = new CountDownLatch(1);
        final AtomicInteger oldReports = new AtomicInteger();
        configure(worker(bytes -> {
            entered.countDown(); blockInspection(); oldExited.countDown(); return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> oldReports.incrementAndGet()));
        receive(1); await(entered); final ThreadPoolExecutor retired = executor(workers.get(0)); support.dispose();
        configure(worker(GearHealthDiagnostics::inspect, (r, c) -> fresh.countDown()));
        receive(2); await(fresh); release.countDown(); await(oldExited); assertTrue(retired.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, oldReports.get()); assertEquals(1, receiver.getRxExpected());
    }

    @Test public void pedometerCipherResetInvalidatesInFlightDiagnosticWithoutClosingProtocol() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        final GearHealthDiagnostics worker = worker(bytes -> {
            if (calls.getAndIncrement() == 0) { entered.countDown(); blockInspection(); }
            return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> { seen.add(((Number) r.commonSync.getSequence()).intValue()); done.countDown(); });
        configure(worker); receive(1); await(entered);
        final Method clear = GearS3DeviceSupport.class.getDeclaredMethod("clearPedometerAppCipher"); clear.setAccessible(true);
        synchronized (support) { clear.invoke(support); }
        worker.submit(request(2)); release.countDown(); await(done); assertEquals(Collections.singletonList(2), seen);
    }

    @Test public void disconnectInvalidatesRunningAndQueuedDiagnosticsWithoutWaiting() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        final AtomicInteger reports = new AtomicInteger();
        configure(worker(bytes -> {
            entered.countDown(); blockInspection(); exited.countDown(); return GearHealthDiagnostics.inspect(bytes);
        }, (r, c) -> reports.incrementAndGet()));
        receive(1); await(entered); receive(2);
        final ThreadPoolExecutor retired = executor(workers.get(0));
        lifecycle.submit(() -> support.disconnect()).get(1, TimeUnit.SECONDS);
        release.countDown(); await(exited); assertTrue(retired.awaitTermination(5, TimeUnit.SECONDS)); assertEquals(0, reports.get());
    }

    @Test public void reporterFailureIsIsolatedFromSubsequentReports() throws Exception {
        final CountDownLatch failed = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final GearHealthDiagnostics worker = worker(GearHealthDiagnostics::inspect, (r, c) -> {
            if (calls.getAndIncrement() == 0) { failed.countDown(); throw new IllegalStateException("synthetic sink failure"); }
            done.countDown();
        });
        worker.submit(request(1)); await(failed); worker.submit(request(2)); await(done); assertEquals(2, calls.get());
    }

    @Test public void lateSubmitAfterCloseCannotRestartWorker() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final GearHealthDiagnostics worker = worker(bytes -> { calls.incrementAndGet(); return GearHealthDiagnostics.inspect(bytes); }, (r, c) -> {});
        worker.close(); worker.submit(request(1)); assertEquals(0, calls.get());
    }
}
