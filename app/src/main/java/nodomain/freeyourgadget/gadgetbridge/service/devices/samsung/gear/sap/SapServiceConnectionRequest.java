package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed generic SAP service-connection request (default session 1023). */
public final class SapServiceConnectionRequest {
    public static final int TYPE = 0x01;
    private final int acceptorComponentId;
    private final int initiatorComponentId;
    private final String profile;
    private final List<Session> sessions;

    public SapServiceConnectionRequest(final int acceptorComponentId, final int initiatorComponentId,
                                       final String profile, final List<Session> sessions) {
        if (!isU16(acceptorComponentId) || !isU16(initiatorComponentId) || profile == null || profile.length() == 0
                || sessions == null || sessions.isEmpty()) throw new IllegalArgumentException("Invalid SAP service request");
        this.acceptorComponentId = acceptorComponentId;
        this.initiatorComponentId = initiatorComponentId;
        this.profile = profile;
        this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
    }
    public int getAcceptorComponentId() { return acceptorComponentId; }
    public int getInitiatorComponentId() { return initiatorComponentId; }
    public String getProfile() { return profile; }
    public List<Session> getSessions() { return sessions; }

    public static final class Session {
        private final int sessionId;
        private final int channelId;
        private final SapQosDescriptor qos;
        private final int payloadType;
        public Session(final int sessionId, final int channelId, final SapQosDescriptor qos, final int payloadType) {
            if (!isU16(sessionId) || !isU16(channelId) || qos == null || payloadType < 0 || payloadType > 0xff) {
                throw new IllegalArgumentException("Invalid SAP requested session");
            }
            this.sessionId = sessionId; this.channelId = channelId; this.qos = qos; this.payloadType = payloadType;
        }
        public int getSessionId() { return sessionId; }
        public int getChannelId() { return channelId; }
        public SapQosDescriptor getQos() { return qos; }
        public int getPayloadType() { return payloadType; }
    }
    static boolean isU16(final int value) { return value >= 0 && value <= 0xffff; }
}
