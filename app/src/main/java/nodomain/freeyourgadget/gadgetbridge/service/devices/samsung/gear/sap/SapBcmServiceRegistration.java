package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** Stable local CapEx metadata for the bounded bcmservice consumer. */
public final class SapBcmServiceRegistration {
    public static final String PROFILE = "/system/bcmservice";
    public static final int API_CHANNEL = 222;
    public static final int ASP_VERSION = 0x0200;
    public static final int ROLE_FLAGS = 0x05; // consumer + socket
    public static final int QOS_TYPE = 0x04;
    public static final int QOS_DATA_RATE = 0x00;
    public static final int QOS_CLASS = 0x02;
    public static final int PAYLOAD_TYPE = 0x00;
    public static final String COMPONENT_KEY = "samsung_gear_bcmservice_component_id";

    private final int agentUuid;
    private final int componentId;
    private RemoteDescriptor remote;

    public SapBcmServiceRegistration(final int agentUuid, final int componentId) {
        if (!SapHostManagerRegistration.validAgent(agentUuid) || !SapHostManagerRegistration.validComponent(componentId)) {
            throw new IllegalArgumentException("Invalid owned bcmservice identity");
        }
        this.agentUuid = agentUuid;
        this.componentId = componentId;
    }

    /**
     * Services from one Gadgetbridge SAP agent share its persistent agent UUID,
     * but each profile receives a distinct persistent component ID.
     */
    public static SapBcmServiceRegistration getOrCreate(final SharedPreferences preferences,
                                                        final SapHostManagerRegistration hostManager) {
        if (preferences == null || hostManager == null) throw new IllegalArgumentException("Missing Gear SAP registration state");
        synchronized (SapBcmServiceRegistration.class) {
            final int component = preferences.getInt(COMPONENT_KEY, -1);
            if (SapHostManagerRegistration.validComponent(component) && component != hostManager.getComponentId()) {
                return new SapBcmServiceRegistration(hostManager.getAgentUuid(), component);
            }
            final SecureRandom random = new SecureRandom();
            int componentId;
            do {
                componentId = SapHostManagerRegistration.generateComponent(random);
            } while (!SapHostManagerRegistration.validComponent(componentId) || componentId == hostManager.getComponentId());
            if (!preferences.edit().putInt(COMPONENT_KEY, componentId).commit()) {
                throw new IllegalStateException("Could not persist Gear bcmservice component ID");
            }
            return new SapBcmServiceRegistration(hostManager.getAgentUuid(), componentId);
        }
    }

    public int getAgentUuid() {
        return agentUuid;
    }

    public int getComponentId() {
        return componentId;
    }

    public SapCapabilityExchange.LocalServiceMetadata toCapabilityMetadata() {
        return new SapCapabilityExchange.LocalServiceMetadata(agentUuid, Integer.toString(agentUuid),
                SapHostManagerRegistration.FRIENDLY_NAME, componentId, Integer.toString(componentId), PROFILE,
                ASP_VERSION, "2.0", 1, ROLE_FLAGS, 0, 2, 0);
    }

    public SapAgentDescriptor toCapabilityDescriptor() {
        return new SapAgentDescriptor(agentUuid, SapHostManagerRegistration.FRIENDLY_NAME,
                Collections.singletonList(new SapServiceDescriptor(componentId, PROFILE, ASP_VERSION, ROLE_FLAGS, 0)));
    }

    public void setRemoteProvider(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !PROFILE.equals(service.getProfileId())
                || service.getAspVersion() != ASP_VERSION
                || (service.getRoleFlags() & 0x03) != 0x00
                || (service.getRoleFlags() & 0x04) == 0) {
            throw new IllegalArgumentException("Incompatible remote bcmservice provider descriptor");
        }
        remote = new RemoteDescriptor(agent.getUuid(), service.getComponentId(), service.getAspVersion(),
                service.getRoleFlags(), service.getTimeout());
    }

    public RemoteDescriptor getRemoteProvider() {
        return remote;
    }

    public static final class RemoteDescriptor {
        private final int agentUuid;
        private final int componentId;
        private final int aspVersion;
        private final int roleFlags;
        private final int timeout;

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
