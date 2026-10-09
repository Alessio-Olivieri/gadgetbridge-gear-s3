package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** Locally-owned provider metadata for the bounded Gear weather service. */
public final class SapWeatherServiceRegistration {
    public static final String PROFILE = "/system/weather2";
    public static final int API_CHANNEL = 109;
    public static final int ASP_VERSION = 0x0100;
    public static final int REMOTE_ASP_VERSION = 0x0100;
    public static final int ROLE_FLAGS = 0x04; // provider + socket
    public static final int REMOTE_ROLE_FLAGS = 0x05; // consumer + socket
    /** Gear-initiated weather connections use QoS type 4; provider connections use type 5. */
    public static final int OBSERVED_QOS_TYPE = 0x04;
    public static final int DECLARED_QOS_TYPE = 0x05;
    public static final int QOS_DATA_RATE = 0x00;
    public static final int QOS_CLASS = 0x00;
    public static final int PAYLOAD_TYPE = 0x00;
    public static final int SERVICE_TIMEOUT = 10;
    public static final String COMPONENT_KEY = "samsung_gear_weather_component_id";

    private final int agentUuid;
    private final int componentId;
    private volatile RemoteDescriptor remote;

    public SapWeatherServiceRegistration(final int agentUuid, final int componentId) {
        if (!SapHostManagerRegistration.validAgent(agentUuid)
                || !SapHostManagerRegistration.validComponent(componentId)) {
            throw new IllegalArgumentException("Invalid owned weather identity");
        }
        this.agentUuid = agentUuid;
        this.componentId = componentId;
    }

    public static SapWeatherServiceRegistration getOrCreate(final SharedPreferences preferences,
                                                              final SapHostManagerRegistration host,
                                                              final SapBcmServiceRegistration bcm,
                                                              final SapNotificationServiceRegistration notifications,
                                                              final SapMusicServiceRegistration music) {
        if (preferences == null || host == null || bcm == null || notifications == null || music == null) {
            throw new IllegalArgumentException("Missing Gear SAP registration state");
        }
        synchronized (SapWeatherServiceRegistration.class) {
            final int current = preferences.getInt(COMPONENT_KEY, -1);
            if (validDistinct(current, host, bcm, notifications, music)) {
                return new SapWeatherServiceRegistration(host.getAgentUuid(), current);
            }
            final SecureRandom random = new SecureRandom();
            int component;
            do {
                component = SapHostManagerRegistration.generateComponent(random);
            } while (!validDistinct(component, host, bcm, notifications, music));
            if (!preferences.edit().putInt(COMPONENT_KEY, component).commit()) {
                throw new IllegalStateException("Could not persist Gear weather component ID");
            }
            return new SapWeatherServiceRegistration(host.getAgentUuid(), component);
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
                ASP_VERSION, "1.0", 0, ROLE_FLAGS, SERVICE_TIMEOUT, 2, 0);
    }

    public SapAgentDescriptor toCapabilityDescriptor() {
        return new SapAgentDescriptor(agentUuid, SapHostManagerRegistration.FRIENDLY_NAME,
                Collections.singletonList(new SapServiceDescriptor(componentId, PROFILE, ASP_VERSION,
                        ROLE_FLAGS, SERVICE_TIMEOUT)));
    }

    public void setRemoteConsumer(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !PROFILE.equals(service.getProfileId())
                || service.getAspVersion() != REMOTE_ASP_VERSION
                || service.getRoleFlags() != REMOTE_ROLE_FLAGS || service.getTimeout() != 0) {
            throw new IllegalArgumentException("Incompatible remote weather consumer descriptor");
        }
        remote = new RemoteDescriptor(agent.getUuid(), service.getComponentId());
    }

    public int getAgentUuid() { return agentUuid; }
    public int getComponentId() { return componentId; }
    public RemoteDescriptor getRemoteConsumer() { return remote; }

    public static final class RemoteDescriptor {
        private final int agentUuid;
        private final int componentId;

        private RemoteDescriptor(final int agentUuid, final int componentId) {
            this.agentUuid = agentUuid;
            this.componentId = componentId;
        }

        public int getAgentUuid() { return agentUuid; }
        public int getComponentId() { return componentId; }
    }
}
