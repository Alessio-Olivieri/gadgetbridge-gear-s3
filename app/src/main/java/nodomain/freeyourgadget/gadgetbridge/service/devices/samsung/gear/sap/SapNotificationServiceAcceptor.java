package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Strict, connection-local acceptor for the Gear-initiated API-104 service session. */
public final class SapNotificationServiceAcceptor {
    public enum State { DISCONNECTED, SESSION_ESTABLISHED, INITIALIZING, READY }
    private static final int MAX_DYNAMIC_SESSION = 992;
    private final SapNotificationServiceRegistration local;
    private final Set<Integer> usedSessions = new HashSet<>();
    private int applicationSessionId = -1;
    private State state = State.DISCONNECTED;

    public SapNotificationServiceAcceptor(final SapNotificationServiceRegistration local) {
        if (local == null) throw new IllegalArgumentException("Missing NotificationService registration");
        this.local = local;
    }

    public SapServiceConnectionResponse handle(final SapServiceConnectionRequest request, final Set<Integer> externallyUsedSessions) {
        final int status = validate(request, externallyUsedSessions == null ? Collections.<Integer>emptySet() : externallyUsedSessions);
        if (status == SapServiceConnectionResponse.STATUS_SUCCESS) {
            applicationSessionId = request.getSessions().get(0).getSessionId();
            usedSessions.add(applicationSessionId);
            state = State.SESSION_ESTABLISHED;
        }
        return new SapServiceConnectionResponse(request.getInitiatorComponentId(), request.getAcceptorComponentId(), request.getProfile(), status,
                status == SapServiceConnectionResponse.STATUS_SUCCESS ? Collections.singletonList(applicationSessionId) : requestedIds(request));
    }

    private int validate(final SapServiceConnectionRequest request, final Set<Integer> externallyUsed) {
        final SapNotificationServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote == null || request == null || applicationSessionId != -1
                || request.getAcceptorComponentId() != local.getComponentId()
                || request.getInitiatorComponentId() != remote.getComponentId()
                || !SapNotificationServiceRegistration.PROFILE.equals(request.getProfile()) || request.getSessions().size() != 1) return SapServiceConnectionResponse.STATUS_REJECTED;
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        if (session.getSessionId() < 1 || session.getSessionId() > MAX_DYNAMIC_SESSION
                || session.getChannelId() != SapNotificationServiceRegistration.API_CHANNEL
                || session.getQos().getType() != SapNotificationServiceRegistration.QOS_TYPE
                || session.getQos().getDataRate() != SapNotificationServiceRegistration.QOS_DATA_RATE
                || session.getQos().getClassType() != SapNotificationServiceRegistration.QOS_CLASS
                || session.getPayloadType() != SapNotificationServiceRegistration.PAYLOAD_TYPE) return SapServiceConnectionResponse.STATUS_REJECTED;
        if (usedSessions.contains(session.getSessionId()) || externallyUsed.contains(session.getSessionId())) return SapServiceConnectionResponse.STATUS_SESSION_COLLISION;
        return SapServiceConnectionResponse.STATUS_SUCCESS;
    }

    private static java.util.List<Integer> requestedIds(final SapServiceConnectionRequest request) {
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        if (request != null) for (final SapServiceConnectionRequest.Session session : request.getSessions()) ids.add(session.getSessionId());
        return ids;
    }

    public void beginInitialization() { if (state == State.SESSION_ESTABLISHED) state = State.INITIALIZING; }
    /** A retransmitted initialization may legitimately arrive after the service is ready. */
    public void markReady() {
        if (state == State.READY) return;
        if (state == State.SESSION_ESTABLISHED || state == State.INITIALIZING) {
            state = State.READY;
            return;
        }
        throw new IllegalStateException("NotificationService ready before session");
    }
    public void clear() { usedSessions.clear(); applicationSessionId = -1; state = State.DISCONNECTED; }
    public boolean isApplicationSession(final int session) { return applicationSessionId == session && session >= 1; }
    public int getApplicationSessionId() { return applicationSessionId; }
    public State getState() { return state; }
}
