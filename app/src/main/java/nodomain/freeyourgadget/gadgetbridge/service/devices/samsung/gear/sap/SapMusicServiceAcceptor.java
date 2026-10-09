package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/** Strict acceptor for the Gear-initiated reliable API-100 remote-music session. */
public final class SapMusicServiceAcceptor {
    private final SapMusicServiceRegistration local;
    private int sessionId = -1;
    public SapMusicServiceAcceptor(final SapMusicServiceRegistration local) { this.local = local; }
    public SapServiceConnectionResponse handle(final SapServiceConnectionRequest request, final Set<Integer> used) {
        int status = SapServiceConnectionResponse.STATUS_REJECTED;
        final SapMusicServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote != null && sessionId < 0 && request != null && request.getAcceptorComponentId() == local.getComponentId() && request.getInitiatorComponentId() == remote.getComponentId() && SapMusicServiceRegistration.PROFILE.equals(request.getProfile()) && request.getSessions().size() == 1) {
            final SapServiceConnectionRequest.Session s = request.getSessions().get(0);
            if (s.getSessionId() >= 1 && s.getSessionId() <= 992 && s.getChannelId() == SapMusicServiceRegistration.API_CHANNEL && s.getQos().getType() == SapMusicServiceRegistration.QOS_TYPE && s.getQos().getDataRate() == SapMusicServiceRegistration.QOS_DATA_RATE && s.getQos().getClassType() == SapMusicServiceRegistration.QOS_CLASS && s.getPayloadType() == SapMusicServiceRegistration.PAYLOAD_TYPE) {
                status = used != null && used.contains(s.getSessionId()) ? SapServiceConnectionResponse.STATUS_SESSION_COLLISION : SapServiceConnectionResponse.STATUS_SUCCESS;
                if (status == SapServiceConnectionResponse.STATUS_SUCCESS) sessionId = s.getSessionId();
            }
        }
        // A type-2 response always carries the proposed session list, including rejection.
        // Reusing the request's slots in reverse is the same generic responder grammar as
        // Host Manager and NotificationService.
        return new SapServiceConnectionResponse(request.getInitiatorComponentId(), request.getAcceptorComponentId(), request.getProfile(), status,
                status == SapServiceConnectionResponse.STATUS_SUCCESS ? Collections.singletonList(sessionId) : requestedSessionIds(request));
    }
    private static java.util.List<Integer> requestedSessionIds(final SapServiceConnectionRequest request) {
        final ArrayList<Integer> ids = new ArrayList<>();
        if (request != null) for (final SapServiceConnectionRequest.Session session : request.getSessions()) ids.add(session.getSessionId());
        return ids;
    }
    public boolean isApplicationSession(final int id) { return id == sessionId && id >= 1; }
    public int getApplicationSessionId() { return sessionId; }
}
