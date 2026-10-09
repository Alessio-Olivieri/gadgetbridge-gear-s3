package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Bounded acceptor for a Gear-initiated Host Manager service connection. */
public final class SapHostManagerAcceptor {
    private static final int MAX_DYNAMIC_SESSION = 992;
    private final SapHostManagerRegistration local;
    private RemoteDescriptor remote;
    private final Set<Integer> usedSessions = new HashSet<>();
    private int applicationSessionId = -1;

    public SapHostManagerAcceptor(final SapHostManagerRegistration local) {
        if (local == null) throw new IllegalArgumentException("Missing local Host Manager registration");
        this.local = local;
    }
    public void setRemoteDescriptor(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !SapHostManagerRegistration.PROFILE.equals(service.getProfileId())
                || service.getAspVersion() != SapHostManagerRegistration.ASP_VERSION
                || (service.getRoleFlags() & 0x03) != 0x01 || (service.getRoleFlags() & 0x04) == 0) {
            throw new IllegalArgumentException("Incompatible remote Host Manager descriptor");
        }
        remote = new RemoteDescriptor(agent.getUuid(), service.getComponentId(), service.getAspVersion(), service.getRoleFlags(), service.getTimeout());
    }
    public SapServiceConnectionResponse handle(final SapServiceConnectionRequest request) {
        final int status = validate(request);
        if (status == SapServiceConnectionResponse.STATUS_SUCCESS) {
            applicationSessionId = request.getSessions().get(0).getSessionId();
            usedSessions.add(applicationSessionId);
        }
        return new SapServiceConnectionResponse(request.getInitiatorComponentId(), request.getAcceptorComponentId(), request.getProfile(),
                status, status == SapServiceConnectionResponse.STATUS_SUCCESS
                        ? Collections.singletonList(applicationSessionId) : requestedSessionIds(request));
    }
    private int validate(final SapServiceConnectionRequest request) {
        if (remote == null || request.getAcceptorComponentId() != local.getComponentId()
                || request.getInitiatorComponentId() != remote.componentId || !SapHostManagerRegistration.PROFILE.equals(request.getProfile())
                || request.getSessions().size() != 1 || applicationSessionId != -1) return SapServiceConnectionResponse.STATUS_REJECTED;
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        if (session.getChannelId() != SapHostManagerRegistration.API_CHANNEL
                || session.getQos().getType() != SapHostManagerRegistration.QOS_TYPE
                || session.getQos().getDataRate() != SapHostManagerRegistration.QOS_DATA_RATE
                || session.getQos().getClassType() != SapHostManagerRegistration.QOS_CLASS
                || session.getPayloadType() != SapHostManagerRegistration.PAYLOAD_TYPE
                || session.getSessionId() < 1 || session.getSessionId() > MAX_DYNAMIC_SESSION) return SapServiceConnectionResponse.STATUS_REJECTED;
        return usedSessions.contains(session.getSessionId()) ? SapServiceConnectionResponse.STATUS_SESSION_COLLISION : SapServiceConnectionResponse.STATUS_SUCCESS;
    }
    private static java.util.List<Integer> requestedSessionIds(final SapServiceConnectionRequest request) {
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        for (final SapServiceConnectionRequest.Session session : request.getSessions()) ids.add(session.getSessionId());
        return ids;
    }
    public int getApplicationSessionId() { return applicationSessionId; }
    public boolean isApplicationSession(final int sessionId) { return sessionId == applicationSessionId && sessionId >= 1; }
    /** Reserve a dynamic transport ID owned by another negotiated service. */
    public void reserveTransportSession(final int sessionId) {
        if (sessionId < 1 || sessionId > MAX_DYNAMIC_SESSION) throw new IllegalArgumentException("Invalid dynamic SAP session ID");
        usedSessions.add(sessionId);
    }
    public RemoteDescriptor getRemoteDescriptor() { return remote; }
    public static final class RemoteDescriptor {
        private final int agentUuid, componentId, aspVersion, roleFlags, timeout;
        private RemoteDescriptor(final int agentUuid, final int componentId, final int aspVersion, final int roleFlags, final int timeout) { this.agentUuid = agentUuid; this.componentId = componentId; this.aspVersion = aspVersion; this.roleFlags = roleFlags; this.timeout = timeout; }
        public int getAgentUuid() { return agentUuid; }
        public int getComponentId() { return componentId; }
        public int getAspVersion() { return aspVersion; }
        public int getRoleFlags() { return roleFlags; }
        public int getTimeout() { return timeout; }
    }
}
