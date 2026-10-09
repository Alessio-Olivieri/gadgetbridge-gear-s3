package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** The phone's dynamically allocated /system/fmp provider descriptor. */
public final class SapFindMyPhoneRegistration {
    public static final String PROFILE = "/system/fmp";
    public static final int API_CHANNEL = 103;
    public static final int ASP_VERSION = 0x0200;
    public static final int REMOTE_ASP_VERSION = 0x0100;
    public static final int ROLE_FLAGS = 0x04;
    public static final int REMOTE_ROLE_FLAGS = 0x05;
    // Phone XML declaration; incoming QoS is negotiated separately (watch XML differs).
    public static final int QOS_TYPE = 5;
    public static final int QOS_DATA_RATE = 0;
    public static final int QOS_CLASS = 0;
    public static final int PAYLOAD_TYPE = 0;
    private static final String COMPONENT_KEY = "samsung_gear_find_my_phone_component_id";

    private final int agentUuid;
    private final int componentId;
    private RemoteDescriptor remote;

    public SapFindMyPhoneRegistration(final int agentUuid, final int componentId) {
        if (!SapHostManagerRegistration.validAgent(agentUuid) || !SapHostManagerRegistration.validComponent(componentId)) {
            throw new IllegalArgumentException("Invalid find_my_phone provider identity");
        }
        this.agentUuid = agentUuid;
        this.componentId = componentId;
    }

    public static SapFindMyPhoneRegistration getOrCreate(final SharedPreferences prefs,
            final SapHostManagerRegistration host, final java.util.Set<Integer> usedComponents) {
        synchronized (SapFindMyPhoneRegistration.class) {
            int id = prefs.getInt(COMPONENT_KEY, -1);
            if (!SapHostManagerRegistration.validComponent(id) || usedComponents.contains(id)) {
                final SecureRandom random = new SecureRandom();
                do { id = SapHostManagerRegistration.generateComponent(random); }
                while (usedComponents.contains(id));
                if (!prefs.edit().putInt(COMPONENT_KEY, id).commit()) {
                    throw new IllegalStateException("Could not persist Gear FMP component");
                }
            }
            return new SapFindMyPhoneRegistration(host.getAgentUuid(), id);
        }
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

    public boolean matchesLocalDescriptor(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        return agent != null && service != null && agent.getUuid() == agentUuid
                && service.getComponentId() == componentId && PROFILE.equals(service.getProfileId())
                && service.getAspVersion() == ASP_VERSION && service.getRoleFlags() == ROLE_FLAGS
                && service.getTimeout() == 0;
    }

    public void setRemoteConsumer(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !PROFILE.equals(service.getProfileId())
                || service.getAspVersion() != REMOTE_ASP_VERSION || service.getRoleFlags() != REMOTE_ROLE_FLAGS
                || service.getTimeout() != 0) throw new IllegalArgumentException("Incompatible Gear find_my_phone consumer");
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
