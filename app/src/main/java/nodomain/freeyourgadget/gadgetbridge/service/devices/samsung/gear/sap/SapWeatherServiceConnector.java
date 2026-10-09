package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;

/** Phone-initiated weather provider connection; independent of Gear-initiated acceptance. */
public final class SapWeatherServiceConnector {
    public enum State { IDLE, PENDING, OVERDUE, ESTABLISHED, TERMINATING }
    private static final int MAX_DYNAMIC_SESSION = 992;
    private final SapWeatherServiceRegistration local;
    private final SecureRandom random;
    private State state = State.IDLE;
    private int sessionId = -1;

    public SapWeatherServiceConnector(final SapWeatherServiceRegistration local) {
        this(local, new SecureRandom());
    }

    SapWeatherServiceConnector(final SapWeatherServiceRegistration local, final SecureRandom random) {
        if (local == null || random == null) throw new IllegalArgumentException("Missing weather connector state");
        this.local = local;
        this.random = random;
    }

    public synchronized SapServiceConnectionRequest createRequest(final Set<Integer> used) {
        if (state != State.IDLE) throw new IllegalStateException("Weather connection already active");
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote == null) throw new IllegalStateException("Missing Gear weather consumer descriptor");
        for (int attempt = 0; attempt < MAX_DYNAMIC_SESSION * 2; attempt++) {
            final int candidate = random.nextInt(MAX_DYNAMIC_SESSION) + 1;
            if (used == null || !used.contains(candidate)) {
                sessionId = candidate;
                state = State.PENDING;
                return new SapServiceConnectionRequest(remote.getComponentId(), local.getComponentId(),
                        SapWeatherServiceRegistration.PROFILE, Collections.singletonList(
                        new SapServiceConnectionRequest.Session(candidate, SapWeatherServiceRegistration.API_CHANNEL,
                                new SapQosDescriptor(SapWeatherServiceRegistration.DECLARED_QOS_TYPE,
                                        SapWeatherServiceRegistration.QOS_DATA_RATE,
                                        SapWeatherServiceRegistration.QOS_CLASS), SapWeatherServiceRegistration.PAYLOAD_TYPE)));
            }
        }
        throw new IllegalStateException("No free dynamic SAP weather session");
    }

    public synchronized void handleResponse(final SapServiceConnectionResponse response) {
        if (!isAwaitingResponse() || response == null) throw new IllegalStateException("Unexpected weather connection response");
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote == null || response.getAcceptorSlot() != remote.getComponentId()
                || response.getInitiatorSlot() != local.getComponentId()
                || !SapWeatherServiceRegistration.PROFILE.equals(response.getProfile())
                || response.getSessionIds().size() != 1 || response.getSessionIds().get(0) != sessionId) {
            throw new IllegalArgumentException("Mismatched weather connection response");
        }
        if (response.getStatus() != SapServiceConnectionResponse.STATUS_SUCCESS) {
            clear();
            throw new IllegalStateException("Gear rejected weather connection, status=" + response.getStatus());
        }
        state = State.ESTABLISHED;
    }

    /** A slow response is not a rejection: the Gear may already own this session. */
    public synchronized boolean markOverdue(final int expectedSession) {
        if (state != State.PENDING || sessionId != expectedSession) return false;
        state = State.OVERDUE;
        return true;
    }

    public synchronized boolean isAwaitingResponse() {
        return state == State.PENDING || state == State.OVERDUE;
    }

    public synchronized boolean isReservedSession(final int id) {
        return sessionId == id && id >= 1 && state != State.IDLE;
    }

    public synchronized SapServiceConnectionTermination createTermination() {
        if (state != State.ESTABLISHED) throw new IllegalStateException("Weather connection is not established");
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote == null) throw new IllegalStateException("Missing Gear weather consumer descriptor");
        state = State.TERMINATING;
        return new SapServiceConnectionTermination(remote.getComponentId(), local.getComponentId(), SapWeatherServiceRegistration.PROFILE);
    }

    public synchronized void handleTerminationResponse(final SapServiceConnectionTerminationResponse response) {
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (state != State.TERMINATING || response == null || remote == null
                || response.getAcceptorComponentId() != local.getComponentId()
                || response.getInitiatorComponentId() != remote.getComponentId()
                || !SapWeatherServiceRegistration.PROFILE.equals(response.getProfile())) {
            throw new IllegalArgumentException("Mismatched weather termination response");
        }
        if (response.getStatus() != SapServiceConnectionTerminationResponse.STATUS_SUCCESS) {
            state = State.ESTABLISHED;
            throw new IllegalStateException("Gear rejected weather termination, status=" + response.getStatus());
        }
        clear();
    }

    public synchronized boolean matchesPeerTermination(final SapServiceConnectionTermination termination) {
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        return state == State.ESTABLISHED && termination != null && remote != null
                && termination.getAcceptorComponentId() == local.getComponentId()
                && termination.getInitiatorComponentId() == remote.getComponentId()
                && SapWeatherServiceRegistration.PROFILE.equals(termination.getProfile());
    }

    public synchronized void clear() { state = State.IDLE; sessionId = -1; }
    public synchronized State getState() { return state; }
    public synchronized int getSessionId() { return sessionId; }
    public synchronized boolean isApplicationSession(final int id) {
        return (state == State.ESTABLISHED || state == State.TERMINATING) && sessionId == id;
    }
}
