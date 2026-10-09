package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/** Acceptor for the bounded Gear-initiated weather service session. */
public final class SapWeatherServiceAcceptor {
    private static final int MAX_DYNAMIC_SESSION = 992;
    private final SapWeatherServiceRegistration local;
    private int applicationSessionId = -1;
    private boolean terminating;

    public SapWeatherServiceAcceptor(final SapWeatherServiceRegistration local) {
        if (local == null) throw new IllegalArgumentException("Missing weather registration");
        this.local = local;
    }

    public synchronized SapServiceConnectionResponse handle(final SapServiceConnectionRequest request,
                                               final Set<Integer> externallyUsedSessions) {
        final int status = validate(request, externallyUsedSessions == null
                ? Collections.<Integer>emptySet() : externallyUsedSessions);
        if (status == SapServiceConnectionResponse.STATUS_SUCCESS) {
            applicationSessionId = request.getSessions().get(0).getSessionId();
        }
        return new SapServiceConnectionResponse(request.getInitiatorComponentId(), request.getAcceptorComponentId(),
                request.getProfile(), status,
                status == SapServiceConnectionResponse.STATUS_SUCCESS
                        ? Collections.singletonList(applicationSessionId) : requestedIds(request));
    }

    private int validate(final SapServiceConnectionRequest request, final Set<Integer> externallyUsedSessions) {
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (remote == null || request == null || applicationSessionId != -1
                || request.getAcceptorComponentId() != local.getComponentId()
                || request.getInitiatorComponentId() != remote.getComponentId()
                || !SapWeatherServiceRegistration.PROFILE.equals(request.getProfile())
                || request.getSessions().size() != 1) {
            return SapServiceConnectionResponse.STATUS_REJECTED;
        }
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        if (session.getSessionId() < 1 || session.getSessionId() > MAX_DYNAMIC_SESSION
                || session.getChannelId() != SapWeatherServiceRegistration.API_CHANNEL
                || session.getQos().getType() != SapWeatherServiceRegistration.OBSERVED_QOS_TYPE
                || session.getQos().getDataRate() != SapWeatherServiceRegistration.QOS_DATA_RATE
                || session.getQos().getClassType() != SapWeatherServiceRegistration.QOS_CLASS
                || session.getPayloadType() != SapWeatherServiceRegistration.PAYLOAD_TYPE) {
            return SapServiceConnectionResponse.STATUS_REJECTED;
        }
        return externallyUsedSessions.contains(session.getSessionId())
                ? SapServiceConnectionResponse.STATUS_SESSION_COLLISION
                : SapServiceConnectionResponse.STATUS_SUCCESS;
    }

    private static ArrayList<Integer> requestedIds(final SapServiceConnectionRequest request) {
        final ArrayList<Integer> ids = new ArrayList<>();
        if (request != null) {
            for (final SapServiceConnectionRequest.Session session : request.getSessions()) {
                ids.add(session.getSessionId());
            }
        }
        return ids;
    }

    public synchronized boolean isApplicationSession(final int sessionId) {
        return applicationSessionId == sessionId && sessionId >= 1;
    }

    public synchronized boolean matchesPeerTermination(final SapServiceConnectionTermination termination) {
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        return applicationSessionId >= 1 && termination != null && remote != null
                && termination.getAcceptorComponentId() == local.getComponentId()
                && termination.getInitiatorComponentId() == remote.getComponentId()
                && SapWeatherServiceRegistration.PROFILE.equals(termination.getProfile());
    }

    public synchronized SapServiceConnectionTermination createTermination() {
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (applicationSessionId < 1 || terminating || remote == null) {
            throw new IllegalStateException("Weather service is not ready to terminate");
        }
        terminating = true;
        // On a Gear-initiated connection the phone was the acceptor, so its
        // locally initiated close reverses the original component direction.
        return new SapServiceConnectionTermination(remote.getComponentId(), local.getComponentId(),
                SapWeatherServiceRegistration.PROFILE);
    }

    public synchronized void handleTerminationResponse(final SapServiceConnectionTerminationResponse response) {
        final SapWeatherServiceRegistration.RemoteDescriptor remote = local.getRemoteConsumer();
        if (!terminating || response == null || remote == null
                || response.getAcceptorComponentId() != local.getComponentId()
                || response.getInitiatorComponentId() != remote.getComponentId()
                || !SapWeatherServiceRegistration.PROFILE.equals(response.getProfile())) {
            throw new IllegalArgumentException("Unexpected weather termination response");
        }
        if (response.getStatus() != SapServiceConnectionTerminationResponse.STATUS_SUCCESS) {
            terminating = false;
            throw new IllegalStateException("Gear rejected weather termination, status=" + response.getStatus());
        }
        clear();
    }

    public synchronized void clear() {
        applicationSessionId = -1;
        terminating = false;
    }

    public synchronized int getApplicationSessionId() { return applicationSessionId; }
}
