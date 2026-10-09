package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/** Calendar service establishment in either direction, with one dynamic channel-101 session. */
public final class SapCalendarServiceConnection {
    public enum State { IDLE, PENDING, ESTABLISHED }
    public enum Decision {
        NONE, ACCEPTED, ACCEPTED_SIMULTANEOUS, REJECTED_STATE, REJECTED_DESCRIPTOR,
        REJECTED_PARAMETERS, SESSION_COLLISION
    }
    private final SapCalendarServiceRegistration local;
    private final SecureRandom random;
    private volatile State state = State.IDLE; // Debug quiescence observes creates on the SAP executor.
    private int sessionId = -1;
    private int pendingPhoneSessionId = -1;
    private Decision lastDecision = Decision.NONE;

    public SapCalendarServiceConnection(final SapCalendarServiceRegistration local) {
        this(local, new SecureRandom());
    }

    SapCalendarServiceConnection(final SapCalendarServiceRegistration local, final SecureRandom random) {
        if (local == null || random == null) throw new IllegalArgumentException("Missing calendar connection state");
        this.local = local;
        this.random = random;
    }

    public SapServiceConnectionRequest createRequest(final Set<Integer> used) {
        if (state != State.IDLE || local.getRemoteConsumer() == null) throw new IllegalStateException("Calendar connection unavailable");
        for (int i = 0; i < 1984; i++) {
            final int candidate = random.nextInt(992) + 1;
            if (used == null || !used.contains(candidate)) {
                sessionId = candidate;
                pendingPhoneSessionId = candidate;
                state = State.PENDING;
                return new SapServiceConnectionRequest(local.getRemoteConsumer().getComponentId(), local.getComponentId(),
                        SapCalendarServiceRegistration.PROFILE, Collections.singletonList(new SapServiceConnectionRequest.Session(
                        candidate, SapCalendarServiceRegistration.API_CHANNEL, new SapQosDescriptor(
                        SapCalendarServiceRegistration.QOS_TYPE, SapCalendarServiceRegistration.QOS_DATA_RATE,
                        SapCalendarServiceRegistration.QOS_CLASS), SapCalendarServiceRegistration.PAYLOAD_TYPE)));
            }
        }
        throw new IllegalStateException("No free calendar session ID");
    }

    /** Returns true only when this response newly establishes the API101 session. */
    public boolean handleResponse(final SapServiceConnectionResponse response) {
        if (state == State.IDLE) return false; // Late confirmation after pending request timeout/teardown.
        if (response == null || local.getRemoteConsumer() == null
                || !SapCalendarServiceRegistration.PROFILE.equals(response.getProfile())
                || response.getAcceptorSlot() != local.getRemoteConsumer().getComponentId()
                || response.getInitiatorSlot() != local.getComponentId()) {
            if (state == State.PENDING) clear();
            throw new IllegalArgumentException("Invalid Gear calendar service response");
        }
        if (state == State.ESTABLISHED) {
            // The Gear's incoming request may have won a simultaneous create. Its response
            // to our older request (or a duplicate confirmation) must not erase that session.
            pendingPhoneSessionId = -1;
            return false;
        }
        if (state != State.PENDING || response.getStatus() != SapServiceConnectionResponse.STATUS_SUCCESS
                || !response.getSessionIds().equals(Collections.singletonList(pendingPhoneSessionId))) {
            clear();
            throw new IllegalArgumentException("Gear rejected calendar service request, status=" + response.getStatus());
        }
        pendingPhoneSessionId = -1;
        state = State.ESTABLISHED;
        return true;
    }

    public SapServiceConnectionResponse handleRequest(final SapServiceConnectionRequest request, final Set<Integer> used) {
        if (request == null) throw new IllegalArgumentException("Missing calendar service request");
        int status = SapServiceConnectionResponse.STATUS_REJECTED;
        int acceptedSession = -1;
        final SapCalendarServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote == null || request.getAcceptorComponentId() != local.getComponentId()
                || request.getInitiatorComponentId() != remote.getComponentId()
                || !SapCalendarServiceRegistration.PROFILE.equals(request.getProfile())
                || request.getSessions().size() != 1) {
            lastDecision = Decision.REJECTED_DESCRIPTOR;
        } else if (state == State.ESTABLISHED) {
            lastDecision = Decision.REJECTED_STATE;
        } else {
            final SapServiceConnectionRequest.Session proposed = request.getSessions().get(0);
            if (proposed.getSessionId() >= 1 && proposed.getSessionId() <= 992
                    && proposed.getChannelId() == SapCalendarServiceRegistration.API_CHANNEL
                    && proposed.getQos().getType() == SapCalendarServiceRegistration.QOS_TYPE
                    && proposed.getQos().getDataRate() == SapCalendarServiceRegistration.QOS_DATA_RATE
                    && proposed.getQos().getClassType() == SapCalendarServiceRegistration.QOS_CLASS
                    && proposed.getPayloadType() == SapCalendarServiceRegistration.PAYLOAD_TYPE) {
                // The pending phone proposal is our own reservation, not another service.
                status = used != null && used.contains(proposed.getSessionId())
                        && proposed.getSessionId() != pendingPhoneSessionId
                        ? SapServiceConnectionResponse.STATUS_SESSION_COLLISION : SapServiceConnectionResponse.STATUS_SUCCESS;
                if (status == SapServiceConnectionResponse.STATUS_SUCCESS) {
                    final boolean simultaneous = state == State.PENDING;
                    acceptedSession = simultaneous
                            ? Math.min(pendingPhoneSessionId, proposed.getSessionId()) : proposed.getSessionId();
                    sessionId = acceptedSession;
                    state = State.ESTABLISHED;
                    lastDecision = simultaneous ? Decision.ACCEPTED_SIMULTANEOUS : Decision.ACCEPTED;
                } else {
                    lastDecision = Decision.SESSION_COLLISION;
                }
            } else {
                lastDecision = Decision.REJECTED_PARAMETERS;
            }
        }
        final ArrayList<Integer> ids = new ArrayList<>();
        if (status == SapServiceConnectionResponse.STATUS_SUCCESS) ids.add(acceptedSession);
        else for (final SapServiceConnectionRequest.Session proposed : request.getSessions()) ids.add(proposed.getSessionId());
        return new SapServiceConnectionResponse(request.getInitiatorComponentId(), request.getAcceptorComponentId(),
                request.getProfile(), status, ids);
    }

    public void clear() {
        state = State.IDLE;
        sessionId = -1;
        pendingPhoneSessionId = -1;
        lastDecision = Decision.NONE;
    }
    /** Retire only the outgoing proposal; a simultaneous Gear-owned session stays live. */
    public void expirePendingPhoneRequest() {
        if (state == State.PENDING) clear();
        else pendingPhoneSessionId = -1;
    }
    public State getState() { return state; }
    public int getSessionId() { return sessionId; }
    public int getPendingPhoneSessionId() { return pendingPhoneSessionId; }
    public Decision getLastDecision() { return lastDecision; }
    public boolean isApplicationSession(final int session) { return state == State.ESTABLISHED && sessionId == session; }
}
