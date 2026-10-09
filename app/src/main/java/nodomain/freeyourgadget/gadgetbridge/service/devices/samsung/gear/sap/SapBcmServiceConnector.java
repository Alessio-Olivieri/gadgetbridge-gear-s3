package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded phone-initiated bcmservice connection setup. This class deliberately
 * owns the API-222 transport-session mapping; application exchange is separate.
 */
public final class SapBcmServiceConnector {
    public enum State { IDLE, PENDING, ESTABLISHED, FAILED }

    private static final int MAX_DYNAMIC_SESSION = 992;

    private final SapBcmServiceRegistration local;
    private final SecureRandom random;
    private final Set<Integer> reservedSessions = new HashSet<>();
    private State state = State.IDLE;
    private int pendingSessionId = -1;
    private int applicationSessionId = -1;

    public SapBcmServiceConnector(final SapBcmServiceRegistration local) {
        this(local, new SecureRandom());
    }

    SapBcmServiceConnector(final SapBcmServiceRegistration local, final SecureRandom random) {
        if (local == null || random == null) throw new IllegalArgumentException("Missing bcmservice connection state");
        this.local = local;
        this.random = random;
    }

    public SapServiceConnectionRequest createRequest() {
        if (state != State.IDLE) throw new IllegalStateException("bcmservice connection was already attempted");
        final SapBcmServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        if (remote == null) throw new IllegalStateException("Missing Gear bcmservice provider descriptor");
        pendingSessionId = allocateSessionId();
        reservedSessions.add(pendingSessionId);
        state = State.PENDING;
        return new SapServiceConnectionRequest(remote.getComponentId(), local.getComponentId(),
                SapBcmServiceRegistration.PROFILE, Collections.singletonList(new SapServiceConnectionRequest.Session(
                pendingSessionId, SapBcmServiceRegistration.API_CHANNEL,
                new SapQosDescriptor(SapBcmServiceRegistration.QOS_TYPE, SapBcmServiceRegistration.QOS_DATA_RATE,
                        SapBcmServiceRegistration.QOS_CLASS), SapBcmServiceRegistration.PAYLOAD_TYPE)));
    }

    /** Validates the Gear's response, which retains the request component-slot order. */
    public void handleResponse(final SapServiceConnectionResponse response) {
        if (state != State.PENDING || response == null) throw new IllegalStateException("Unexpected bcmservice connection response");
        final SapBcmServiceRegistration.RemoteDescriptor remote = local.getRemoteProvider();
        if (remote == null || response.getAcceptorSlot() != remote.getComponentId()
                || response.getInitiatorSlot() != local.getComponentId()
                || !SapBcmServiceRegistration.PROFILE.equals(response.getProfile())
                || response.getSessionIds().size() != 1
                || response.getSessionIds().get(0) != pendingSessionId) {
            fail();
            throw new IllegalArgumentException("Invalid Gear bcmservice connection response");
        }
        if (response.getStatus() != SapServiceConnectionResponse.STATUS_SUCCESS) {
            fail();
            throw new IllegalStateException("Gear rejected bcmservice connection, status=" + response.getStatus());
        }
        applicationSessionId = pendingSessionId;
        pendingSessionId = -1;
        state = State.ESTABLISHED;
    }

    /** Releases all connection-local allocation; call on transport disconnect. */
    public void clear() {
        reservedSessions.clear();
        pendingSessionId = -1;
        applicationSessionId = -1;
        state = State.IDLE;
    }

    private int allocateSessionId() {
        for (int attempts = 0; attempts < MAX_DYNAMIC_SESSION * 2; attempts++) {
            final int candidate = random.nextInt(MAX_DYNAMIC_SESSION) + 1;
            if (!reservedSessions.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("No free dynamic SAP bcmservice session ID");
    }

    private void fail() {
        if (pendingSessionId >= 1) reservedSessions.remove(pendingSessionId);
        pendingSessionId = -1;
        state = State.FAILED;
    }

    public State getState() { return state; }
    public int getPendingSessionId() { return pendingSessionId; }
    public int getApplicationSessionId() { return applicationSessionId; }
    public boolean isApplicationSession(final int sessionId) { return state == State.ESTABLISHED && sessionId == applicationSessionId; }
    boolean isReservedSession(final int sessionId) { return reservedSessions.contains(sessionId); }
}
