package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** Locally-owned phone consumer registration for health data reception. */
public final class SapPedometerServiceRegistration {
    public static final String PROFILE = "/system/watch_pedometer";
    public static final int API_CHANNEL = 999;
    public static final int ASP_VERSION = 0x0200;
    public static final int REMOTE_ASP_VERSION = 0x0100;
    public static final int ROLE_FLAGS = 0x05; // consumer + socket
    public static final int REMOTE_ROLE_FLAGS = 0x04; // provider + socket
    public static final int QOS_TYPE = 0x04;
    public static final int QOS_DATA_RATE = 0x00;
    public static final int QOS_CLASS = 0x00;
    public static final int PAYLOAD_TYPE = 0x00;
    public static final String COMPONENT_KEY = "samsung_gear_watch_pedometer_component_id";

    private final int agentUuid;
    private final int componentId;
    private RemoteDescriptor remote;

    public SapPedometerServiceRegistration(final int agentUuid, final int componentId) {
        if (!SapHostManagerRegistration.validAgent(agentUuid) || !SapHostManagerRegistration.validComponent(componentId)) {
            throw new IllegalArgumentException("Invalid owned watch pedometer identity");
        }
        this.agentUuid = agentUuid;
        this.componentId = componentId;
    }

    public static SapPedometerServiceRegistration getOrCreate(final SharedPreferences preferences,
                                                               final SapHostManagerRegistration host,
                                                               final SapBcmServiceRegistration bcm,
                                                               final SapNotificationServiceRegistration notifications,
                                                               final SapMusicServiceRegistration music) {
        if (preferences == null || host == null || bcm == null || notifications == null || music == null) {
            throw new IllegalArgumentException("Missing Gear SAP registration state");
        }
        synchronized (SapPedometerServiceRegistration.class) {
            final int current = preferences.getInt(COMPONENT_KEY, -1);
            if (validDistinct(current, host, bcm, notifications, music)) {
                return new SapPedometerServiceRegistration(host.getAgentUuid(), current);
            }
            final SecureRandom random = new SecureRandom();
            int component;
            do {
                component = SapHostManagerRegistration.generateComponent(random);
            } while (!validDistinct(component, host, bcm, notifications, music));
            if (!preferences.edit().putInt(COMPONENT_KEY, component).commit()) {
                throw new IllegalStateException("Could not persist Gear watch pedometer component ID");
            }
            return new SapPedometerServiceRegistration(host.getAgentUuid(), component);
        }
    }

    private static boolean validDistinct(final int candidate,
                                         final SapHostManagerRegistration host,
                                         final SapBcmServiceRegistration bcm,
                                         final SapNotificationServiceRegistration notifications,
                                         final SapMusicServiceRegistration music) {
        return SapHostManagerRegistration.validComponent(candidate)
                && candidate != host.getComponentId()
                && candidate != bcm.getComponentId()
                && candidate != notifications.getComponentId()
                && candidate != music.getComponentId();
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

    public void setRemoteProvider(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !PROFILE.equals(service.getProfileId())
                || service.getAspVersion() != REMOTE_ASP_VERSION
                || service.getRoleFlags() != REMOTE_ROLE_FLAGS || service.getTimeout() != 0) {
            throw new IllegalArgumentException("Incompatible remote watch pedometer provider descriptor");
        }
        remote = new RemoteDescriptor(agent.getUuid(), service.getComponentId(), service.getAspVersion(),
                service.getRoleFlags(), service.getTimeout());
    }

    public int getAgentUuid() { return agentUuid; }
    public int getComponentId() { return componentId; }
    public RemoteDescriptor getRemoteProvider() { return remote; }

    public static final class RemoteDescriptor {
        private final int agentUuid, componentId, aspVersion, roleFlags, timeout;
        private RemoteDescriptor(final int agentUuid, final int componentId, final int aspVersion,
                                 final int roleFlags, final int timeout) {
            this.agentUuid = agentUuid;
            this.componentId = componentId;
            this.aspVersion = aspVersion;
            this.roleFlags = roleFlags;
            this.timeout = timeout;
        }
        public int getAgentUuid() { return agentUuid; }
        public int getComponentId() { return componentId; }
        public int getAspVersion() { return aspVersion; }
        public int getRoleFlags() { return roleFlags; }
        public int getTimeout() { return timeout; }
    }
}
