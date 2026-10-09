package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Phone-initiated, receive-only API-999 service connection state. */
public final class SapPedometerServiceConnector {
    public enum State { IDLE, PENDING, ESTABLISHED, TERMINATING, FAILED }
    private static final int MAX_DYNAMIC_SESSION = 992;
    private final SapPedometerServiceRegistration local;
    private final SecureRandom random;
    private final Set<Integer> reservedSessions = new HashSet<>();
    private State state = State.IDLE;
    private int pendingSessionId = -1;
    private int applicationSessionId = -1;

    public SapPedometerServiceConnector(final SapPedometerServiceRegistration local) {
        this(local, new SecureRandom());
    }

    SapPedometerServiceConnector(final SapPedometerServiceRegistration local, final SecureRandom random) {
        if (local == null || random == null) throw new IllegalArgumentException("Missing pedometer connection state");
        this.local = local;
        this.random = random;
    }

    public SapServiceConnectionRequest createRequest(final Set<Integer> externallyUsedSessions) {
        if (state != State.IDLE) throw new IllegalStateException("watch pedometer connection was already attempted");
        final SapPedometerServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        if (remote == null) throw new IllegalStateException("Missing Gear watch pedometer provider descriptor");
        pendingSessionId = allocateSessionId(externallyUsedSessions == null ? Collections.<Integer>emptySet() : externallyUsedSessions);
        reservedSessions.add(pendingSessionId);
        state = State.PENDING;
        return new SapServiceConnectionRequest(remote.getComponentId(), local.getComponentId(),
                SapPedometerServiceRegistration.PROFILE, Collections.singletonList(new SapServiceConnectionRequest.Session(
                pendingSessionId, SapPedometerServiceRegistration.API_CHANNEL,
                new SapQosDescriptor(SapPedometerServiceRegistration.QOS_TYPE,
                        SapPedometerServiceRegistration.QOS_DATA_RATE,
                        SapPedometerServiceRegistration.QOS_CLASS),
                SapPedometerServiceRegistration.PAYLOAD_TYPE)));
    }

    public void handleResponse(final SapServiceConnectionResponse response) {
        if (state != State.PENDING || response == null) throw new IllegalStateException("Unexpected watch pedometer connection response");
        final SapPedometerServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        if (remote == null || response.getAcceptorSlot() != remote.getComponentId()
                || response.getInitiatorSlot() != local.getComponentId()
                || !SapPedometerServiceRegistration.PROFILE.equals(response.getProfile())
                || response.getSessionIds().size() != 1 || response.getSessionIds().get(0) != pendingSessionId) {
            fail();
            throw new IllegalArgumentException("Invalid Gear watch pedometer connection response");
        }
        if (response.getStatus() != SapServiceConnectionResponse.STATUS_SUCCESS) {
            fail();
            throw new IllegalStateException("Gear rejected watch pedometer connection, status=" + response.getStatus());
        }
        applicationSessionId = pendingSessionId;
        pendingSessionId = -1;
        state = State.ESTABLISHED;
    }

    public SapServiceConnectionTermination createTermination() {
        if (state != State.ESTABLISHED) throw new IllegalStateException("watch pedometer service is not established");
        final SapPedometerServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        if (remote == null) throw new IllegalStateException("Missing Gear watch pedometer provider descriptor");
        state = State.TERMINATING;
        return new SapServiceConnectionTermination(remote.getComponentId(), local.getComponentId(),
                SapPedometerServiceRegistration.PROFILE);
    }

    public void handleTerminationResponse(final SapServiceConnectionTerminationResponse response) {
        if (state != State.TERMINATING || response == null) {
            throw new IllegalStateException("Unexpected watch pedometer termination response");
        }
        final SapPedometerServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        if (remote == null || response.getAcceptorComponentId() != local.getComponentId()
                || response.getInitiatorComponentId() != remote.getComponentId()
                || !SapPedometerServiceRegistration.PROFILE.equals(response.getProfile())) {
            fail();
            throw new IllegalArgumentException("Invalid Gear watch pedometer termination response");
        }
        if (response.getStatus() != SapServiceConnectionTerminationResponse.STATUS_SUCCESS) {
            fail();
            throw new IllegalStateException("Gear rejected watch pedometer termination, status=" + response.getStatus());
        }
        clear();
    }

    public boolean matchesPeerTermination(final SapServiceConnectionTermination termination) {
        final SapPedometerServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        return state == State.ESTABLISHED && termination != null && remote != null
                && termination.getAcceptorComponentId() == local.getComponentId()
                && termination.getInitiatorComponentId() == remote.getComponentId()
                && SapPedometerServiceRegistration.PROFILE.equals(termination.getProfile());
    }

    public void clear() {
        reservedSessions.clear();
        pendingSessionId = -1;
        applicationSessionId = -1;
        state = State.IDLE;
    }

    private int allocateSessionId(final Set<Integer> externallyUsed) {
        for (int attempts = 0; attempts < MAX_DYNAMIC_SESSION * 2; attempts++) {
            final int candidate = random.nextInt(MAX_DYNAMIC_SESSION) + 1;
            if (!reservedSessions.contains(candidate) && !externallyUsed.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("No free dynamic SAP watch pedometer session ID");
    }

    private void fail() {
        if (pendingSessionId >= 1) reservedSessions.remove(pendingSessionId);
        pendingSessionId = -1;
        state = State.FAILED;
    }

    public State getState() { return state; }
    public int getApplicationSessionId() { return applicationSessionId; }
    public boolean isApplicationSession(final int sessionId) { return state == State.ESTABLISHED && sessionId == applicationSessionId; }
}
