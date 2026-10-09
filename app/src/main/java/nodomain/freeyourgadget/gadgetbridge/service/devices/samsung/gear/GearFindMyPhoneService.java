package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.*;

/** Watch-initiated FMP service on the existing SAP transport. No location or reverse finding. */
public final class GearFindMyPhoneService {
    private static final Logger LOG = LoggerFactory.getLogger(GearFindMyPhoneService.class);
    public interface Emitter {
        void send(SapPostAuthRecord record, long generation);
        boolean connected();
    }
    public interface Alert {
        /** Return value means callback dispatch was queued; accepted means Android launch was scheduled. */
        boolean start(Runnable accepted, Runnable started, Consumer<Boolean> finished);
        void stop();
    }
    public interface Scheduler { Runnable later(Runnable task, long delayMillis); }
    private final SapFindMyPhoneRegistration registration;
    private final Emitter emitter;
    private final Alert alert;
    private final Scheduler scheduler;
    private final SapReliableSession rx = new SapReliableSession();
    private final Map<Integer, byte[]> waiting = new LinkedHashMap<>();
    private final Set<Integer> delivered = new LinkedHashSet<>();
    private final Map<Integer, byte[]> unacked = new LinkedHashMap<>();
    private volatile long generation;
    private volatile int session = -1;
    private boolean reliable, pending, startAccepted, ringing, disposed;
    private long operation;
    private int txSequence = 1, retries;
    private Runnable cancelDeadline, cancelLaunch, cancelLinkCheck, cancelRetry;

    public GearFindMyPhoneService(final SapFindMyPhoneRegistration registration, final Emitter emitter,
                                 final Alert alert, final Scheduler scheduler) {
        this.registration = registration;
        this.emitter = emitter;
        this.alert = alert;
        this.scheduler = scheduler;
    }
    public SapFindMyPhoneRegistration registration() { return registration; }
    public int session() { return session; }
    public long generation() { return generation; }
    public boolean hasSession(final int id) { return id >= 1 && id == session && !disposed; }

    public synchronized void learnRemote(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) for (final SapServiceDescriptor service : agent.getServices()) {
            if (!SapFindMyPhoneRegistration.PROFILE.equals(service.getProfileId())) continue;
            if (service.getAspVersion() != 0x0100 || service.getRoleFlags() != 0x05 || service.getTimeout() != 0) {
                LOG.warn("Gear S3 FMP incompatible consumer descriptor");
                continue;
            }
            if (registration.getRemoteConsumer() != null
                    && registration.getRemoteConsumer().getComponentId() != service.getComponentId()) close();
            registration.setRemoteConsumer(agent, service);
            LOG.info("Gear S3 FMP consumer learned: agent={} component={}", agent.getUuid(), service.getComponentId());
        }
    }

    public synchronized SapServiceConnectionResponse accept(final SapServiceConnectionRequest request,
                                                             final Set<Integer> used) {
        final List<Integer> ids = new ArrayList<>();
        for (final SapServiceConnectionRequest.Session s : request.getSessions()) ids.add(s.getSessionId());
        int status = SapServiceConnectionResponse.STATUS_REJECTED;
        final SapFindMyPhoneRegistration.RemoteDescriptor remote = registration.getRemoteConsumer();
        if (!disposed && session < 1 && remote != null && request.getSessions().size() == 1
                && SapFindMyPhoneRegistration.PROFILE.equals(request.getProfile())
                && request.getAcceptorComponentId() == registration.getComponentId()
                && request.getInitiatorComponentId() == remote.getComponentId()) {
            final SapServiceConnectionRequest.Session s = request.getSessions().get(0);
            final SapQosDescriptor qos = s.getQos();
            LOG.info("Gear S3 FMP create: session={} channel={} qos={}/{}/{} payloadType={}",
                    s.getSessionId(), s.getChannelId(), qos.getType(), qos.getDataRate(), qos.getClassType(), s.getPayloadType());
            // Validate generic SAP enum ranges and honor the peer's QoS proposal.
            if (s.getSessionId() >= 1 && s.getSessionId() <= 992 && s.getChannelId() == 103
                    && s.getPayloadType() == 0 && (qos.getType() == 4 || qos.getType() == 5)
                    && qos.getDataRate() <= 1 && qos.getClassType() <= 2) {
                status = used.contains(s.getSessionId()) ? SapServiceConnectionResponse.STATUS_SESSION_COLLISION
                        : SapServiceConnectionResponse.STATUS_SUCCESS;
                if (status == SapServiceConnectionResponse.STATUS_SUCCESS) {
                    generation++;
                    session = s.getSessionId();
                    reliable = qos.getType() == 5;
                    LOG.info("Gear S3 FMP established: session={} generation={} reliable={}", session, generation, reliable);
                    monitorConnection(generation);
                }
            }
        }
        LOG.info("Gear S3 FMP create response: status={}", status);
        return new SapServiceConnectionResponse(request.getInitiatorComponentId(), request.getAcceptorComponentId(),
                request.getProfile(), status, ids);
    }

    public synchronized int terminate(final SapServiceConnectionTermination termination) {
        final SapFindMyPhoneRegistration.RemoteDescriptor remote = registration.getRemoteConsumer();
        if (session < 1 || remote == null || !SapFindMyPhoneRegistration.PROFILE.equals(termination.getProfile())
                || termination.getAcceptorComponentId() != registration.getComponentId()
                || termination.getInitiatorComponentId() != remote.getComponentId()) return 1;
        close();
        return SapServiceConnectionTerminationResponse.STATUS_SUCCESS;
    }

    public synchronized void onRecord(final SapPostAuthRecord record, final long expectedGeneration) {
        if (disposed || generation != expectedGeneration || !hasSession(record.getHeader().getSessionId())) return;
        try {
            final SapTransportHeader header = record.getHeader();
            if (header.getVersion() != 0 || header.getFragment() != 0) throw new IllegalArgumentException("Unsupported FMP transport header");
            byte[] body = record.getBody();
            LOG.debug("Gear S3 FMP RX: session={} generation={} frameType={} bytes={} reliable={}",
                    session, generation, header.getFrameType(), body.length, reliable);
            if (header.getFrameType() == 1) {
                if (!reliable) throw new IllegalArgumentException("Control on unreliable FMP session");
                reliableControl(body);
                return;
            }
            if (!reliable) { command(body); return; }
            if (body.length < 3 || body.length > 1026) throw new IllegalArgumentException("Invalid FMP reliable length");
            final int sequence = u16(body, 0);
            LOG.debug("Gear S3 FMP reliable RX: sequence={} sdkHeader={}", sequence, body[2] & 255);
            if (sequence == 0) throw new IllegalArgumentException("Invalid FMP sequence");
            if (delivered.contains(sequence)) { ack(); return; }
            if (sequence != rx.getExpectedSequence()) {
                if (waiting.size() >= 16) throw new IllegalArgumentException("FMP receive window full");
                waiting.put(sequence, Arrays.copyOfRange(body, 2, body.length));
                emit(1, SapNotificationServiceCodec.encodeReliableNak(rx.getExpectedSequence(),
                        sequence == 1 ? 0xffff : sequence - 1));
                return;
            }
            receive(sequence, Arrays.copyOfRange(body, 2, body.length));
            while (session >= 1 && waiting.containsKey(rx.getExpectedSequence())) {
                final int next = rx.getExpectedSequence();
                receive(next, waiting.remove(next));
            }
            if (session >= 1) ack();
        } catch (final IllegalArgumentException e) {
            LOG.warn("Gear S3 FMP rejected frame: {}", e.getMessage());
        }
    }

    private void receive(final int sequence, final byte[] body) {
        rx.receive(sequence);
        delivered.add(sequence);
        if (delivered.size() > 64) delivered.remove(delivered.iterator().next());
        // A malformed application command is still a delivered transport packet.
        try { command(body); }
        catch (final IllegalArgumentException e) { LOG.warn("Gear S3 FMP rejected command: {}", e.getMessage()); }
    }
    private void ack() {
        if (rx.getLastValidSequence() > 0) emit(1, SapNotificationServiceCodec.encodeReliableBlockAck(rx.getLastValidSequence()));
        rx.consumePendingBlockAck();
    }
    private void command(final byte[] body) {
        final SapFindMyPhoneCodec.Command command = SapFindMyPhoneCodec.decode(body);
        LOG.info("Gear S3 FMP command: {} session={} generation={}", command, session, generation);
        if (command == SapFindMyPhoneCodec.Command.START) LOG.info("Gear S3 FMP Start fields: time=180 timeType=string sdkHeader=0");
        switch (command) {
            case START:
                if (startAccepted) {
                    LOG.info("Gear S3 FMP START accepted: duplicate; existing alert retained");
                    sendStartResponse(true);
                    return;
                }
                if (pending) {
                    LOG.info("Gear S3 FMP START pending: dispatch has not accepted yet");
                    return;
                }
                pending = true;
                final long op = ++operation;
                final long gen = generation;
                cancelLaunch = scheduler.later(() -> failedLaunch(gen, op, "activity/playback watchdog expired"), 10_000);
                cancelDeadline = scheduler.later(() -> timeout(gen, op), 180_000);
                final boolean queued = alert.start(() -> accepted(gen, op), () -> started(gen, op),
                        failed -> finished(gen, op, failed));
                LOG.info("Gear S3 FMP alert-controller result: dispatchQueued={}", queued);
                if (!queued) failedLaunch(gen, op, "alert-controller rejected dispatch");
                break;
            case STOP:
                stopAlert();
                send(SapFindMyPhoneCodec.stopResponse());
                break;
            case FOUND_ACK:
                // Acknowledges the already completed local operation. Never touches a subsequent ring.
                break;
            case UNKNOWN: break;
        }
    }
    private synchronized void accepted(final long gen, final long op) {
        if (gen != generation || op != operation || (!pending && !ringing) || startAccepted) return;
        startAccepted = true;
        LOG.info("Gear S3 FMP START accepted: Android activity launch scheduled; playback confirmation pending");
        // Acknowledge acceptance before launching the player Activity.
        sendStartResponse(true);
    }
    private void sendStartResponse(final boolean success) {
        final byte[] response = SapFindMyPhoneCodec.startResponse(success);
        // Locally generated fixed envelope only; no incoming/private fields.
        LOG.info("Gear S3 FMP response JSON: {}", new String(response, 1, response.length - 1,
                java.nio.charset.StandardCharsets.UTF_8));
        send(response);
    }
    private synchronized void started(final long gen, final long op) {
        if (gen != generation || op != operation || !pending) return;
        pending = false;
        ringing = true;
        cancel(cancelLaunch); cancelLaunch = null;
        LOG.info("Gear S3 FMP Android playback started: generation={}", generation);
        accepted(gen, op);
    }
    private synchronized void finished(final long gen, final long op, final boolean failed) {
        if (gen != generation || op != operation || (!pending && !ringing)) return;
        final boolean wasAccepted = startAccepted;
        LOG.info("Gear S3 FMP alert completion: accepted={} playbackStarted={} failed={}",
                wasAccepted, ringing, failed);
        stopAlert();
        if (!wasAccepted) sendStartResponse(false);
        else send(failed ? SapFindMyPhoneCodec.stopResponse() : SapFindMyPhoneCodec.foundRequest());
    }
    private synchronized void failedLaunch(final long gen, final long op, final String reason) {
        if (gen != generation || op != operation || !pending) return;
        final boolean wasAccepted = startAccepted;
        stopAlert();
        LOG.warn("Gear S3 FMP START unavailable: reason={} previouslyAccepted={}", reason, wasAccepted);
        if (wasAccepted) send(SapFindMyPhoneCodec.stopResponse());
        else sendStartResponse(false);
    }
    private synchronized void timeout(final long gen, final long op) {
        if (gen != generation || op != operation || (!pending && !ringing)) return;
        stopAlert();
        send(SapFindMyPhoneCodec.stopResponse());
    }
    private void stopAlert() {
        operation++;
        pending = startAccepted = ringing = false;
        cancel(cancelLaunch); cancelLaunch = null;
        cancel(cancelDeadline); cancelDeadline = null;
        alert.stop();
    }
    private void monitorConnection(final long gen) {
        cancelLinkCheck = scheduler.later(() -> {
            synchronized (GearFindMyPhoneService.this) {
                if (generation != gen || session < 1) return;
                if (!emitter.connected()) { close(); return; }
                monitorConnection(gen);
            }
        }, 1000);
    }
    private void send(final byte[] sdkBody) {
        if (session < 1 || disposed) return;
        if (!reliable) { emit(0, sdkBody); return; }
        if (unacked.size() >= 16) { close(); return; }
        final int seq = txSequence;
        txSequence = seq == 0xffff ? 1 : seq + 1;
        final byte[] body = new byte[sdkBody.length + 2];
        body[0] = (byte) (seq >>> 8); body[1] = (byte) seq;
        System.arraycopy(sdkBody, 0, body, 2, sdkBody.length);
        unacked.put(seq, body);
        emit(0, body);
        if (session >= 1 && cancelRetry == null) scheduleRetry();
    }
    private void scheduleRetry() {
        final long gen = generation;
        cancelRetry = scheduler.later(() -> {
            synchronized (GearFindMyPhoneService.this) {
                if (gen != generation || session < 1) return;
                cancelRetry = null;
                if (unacked.isEmpty()) return;
                if (++retries > 3) { LOG.warn("Gear S3 FMP reliable response timed out; stopping alert"); close(); return; }
                for (final byte[] body : new ArrayList<>(unacked.values())) emit(0, body);
                if (session >= 1) scheduleRetry();
            }
        }, 2000); // Bounded local retry policy.
    }
    private void reliableControl(final byte[] body) {
        if (body.length == 3 && body[0] == 1) {
            final int through = u16(body, 1);
            if (!unacked.containsKey(through)) return;
            for (final int seq : new ArrayList<>(unacked.keySet())) {
                unacked.remove(seq);
                if (seq == through) break;
            }
            retries = 0;
            if (unacked.isEmpty()) { cancel(cancelRetry); cancelRetry = null; }
        } else if (body.length >= 6 && body[0] == 2 && body.length == 2 + (body[1] & 0xff) * 4) {
            for (int offset = 2; offset < body.length; offset += 4) {
                final int first = u16(body, offset), last = u16(body, offset + 2);
                for (final Map.Entry<Integer, byte[]> entry : new ArrayList<>(unacked.entrySet())) {
                    final int seq = entry.getKey();
                    if (first <= last ? seq >= first && seq <= last : seq >= first || seq <= last) emit(0, entry.getValue());
                }
            }
        } else throw new IllegalArgumentException("Invalid FMP reliable control");
    }
    private void emit(final int type, final byte[] body) {
        if (session < 1 || disposed) return;
        try {
            LOG.debug("Gear S3 FMP TX: session={} generation={} frameType={} bytes={} reliable={}",
                    session, generation, type, body.length, reliable);
            emitter.send(new SapPostAuthRecord(new SapTransportHeader(0, type, session, 0), body), generation);
        } catch (final RuntimeException e) {
            LOG.warn("Gear S3 FMP send unavailable; stopping alert", e);
            close();
        }
    }
    private static int u16(final byte[] b, final int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    private static void cancel(final Runnable cancellation) { if (cancellation != null) cancellation.run(); }
    public synchronized void close() {
        generation++;
        session = -1;
        stopAlert();
        cancel(cancelLinkCheck); cancelLinkCheck = null;
        cancel(cancelRetry); cancelRetry = null;
        rx.reset(); waiting.clear(); delivered.clear(); unacked.clear();
        txSequence = 1; retries = 0;
        LOG.info("Gear S3 FMP closed: generation={}; alert canceled", generation);
    }
    public synchronized void dispose() { close(); disposed = true; }
}
