package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** Locally-owned CapEx metadata for the clear Gear NotificationService provider. */
public final class SapNotificationServiceRegistration {
    public static final String PROFILE = "/system/NotificationService";
    public static final int API_CHANNEL = 104;
    public static final int ASP_VERSION = 0x0200;
    public static final int REMOTE_ASP_VERSION = 0x0100;
    public static final int ROLE_FLAGS = 0x04; // provider + socket
    public static final int REMOTE_ROLE_FLAGS = 0x05; // consumer + socket
    public static final int QOS_TYPE = 0x05; // reliable
    public static final int QOS_DATA_RATE = 0x00; // low
    public static final int QOS_CLASS = 0x00; // low priority
    public static final int PAYLOAD_TYPE = 0x00;
    private static final String COMPONENT_KEY = "samsung_gear_notificationservice_component_id";

    private final int agentUuid;
    private final int componentId;
    private RemoteDescriptor remote;

    public SapNotificationServiceRegistration(final int agentUuid, final int componentId) {
        if (!SapHostManagerRegistration.validAgent(agentUuid) || !SapHostManagerRegistration.validComponent(componentId)) {
            throw new IllegalArgumentException("Invalid owned NotificationService identity");
        }
        this.agentUuid = agentUuid;
        this.componentId = componentId;
    }

    public static SapNotificationServiceRegistration getOrCreate(final SharedPreferences preferences,
                                                                  final SapHostManagerRegistration hostManager,
                                                                  final SapBcmServiceRegistration bcmService) {
        if (preferences == null || hostManager == null || bcmService == null) throw new IllegalArgumentException("Missing Gear SAP registration state");
        synchronized (SapNotificationServiceRegistration.class) {
            final int current = preferences.getInt(COMPONENT_KEY, -1);
            if (validDistinct(current, hostManager, bcmService)) return new SapNotificationServiceRegistration(hostManager.getAgentUuid(), current);
            final SecureRandom random = new SecureRandom();
            int component;
            do { component = SapHostManagerRegistration.generateComponent(random); }
            while (!validDistinct(component, hostManager, bcmService));
            if (!preferences.edit().putInt(COMPONENT_KEY, component).commit()) throw new IllegalStateException("Could not persist Gear NotificationService component ID");
            return new SapNotificationServiceRegistration(hostManager.getAgentUuid(), component);
        }
    }

    private static boolean validDistinct(final int candidate, final SapHostManagerRegistration hostManager, final SapBcmServiceRegistration bcmService) {
        return SapHostManagerRegistration.validComponent(candidate)
                && candidate != hostManager.getComponentId() && candidate != bcmService.getComponentId();
    }

    public SapCapabilityExchange.LocalServiceMetadata toCapabilityMetadata() {
        return new SapCapabilityExchange.LocalServiceMetadata(agentUuid, Integer.toString(agentUuid),
                SapHostManagerRegistration.FRIENDLY_NAME, componentId, Integer.toString(componentId), PROFILE,
                ASP_VERSION, "2.0", 0, ROLE_FLAGS, 0, 2, 0);
    }

    public SapAgentDescriptor toCapabilityDescriptor() {
        return new SapAgentDescriptor(agentUuid, SapHostManagerRegistration.FRIENDLY_NAME,
                Collections.singletonList(new SapServiceDescriptor(componentId, PROFILE, ASP_VERSION, ROLE_FLAGS, 0)));
    }

    public void setRemoteConsumer(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !PROFILE.equals(service.getProfileId())
                || service.getAspVersion() != REMOTE_ASP_VERSION || service.getRoleFlags() != REMOTE_ROLE_FLAGS
                || service.getTimeout() != 0) throw new IllegalArgumentException("Incompatible remote NotificationService consumer descriptor");
        remote = new RemoteDescriptor(agent.getUuid(), service.getComponentId(), service.getAspVersion(), service.getRoleFlags(), service.getTimeout());
    }

    public int getAgentUuid() { return agentUuid; }
    public int getComponentId() { return componentId; }
    public RemoteDescriptor getRemoteConsumer() { return remote; }

    public static final class RemoteDescriptor {
        private final int agentUuid, componentId, aspVersion, roleFlags, timeout;
        private RemoteDescriptor(final int agentUuid, final int componentId, final int aspVersion, final int roleFlags, final int timeout) {
            this.agentUuid = agentUuid; this.componentId = componentId; this.aspVersion = aspVersion; this.roleFlags = roleFlags; this.timeout = timeout;
        }
        public int getAgentUuid() { return agentUuid; }
        public int getComponentId() { return componentId; }
        public int getAspVersion() { return aspVersion; }
        public int getRoleFlags() { return roleFlags; }
        public int getTimeout() { return timeout; }
    }
}
