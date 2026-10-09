package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** Stable, locally owned metadata for the one bounded Host Manager provider. */
public final class SapHostManagerRegistration {
    public static final String PROFILE = "/system/hostmanager";
    public static final String FRIENDLY_NAME = "Gadgetbridge";
    public static final int API_CHANNEL = 103;
    public static final int ASP_VERSION = 0x0100;
    public static final int ROLE_FLAGS = 0x04; // provider + socket
    public static final int QOS_TYPE = 0x04;
    public static final int QOS_DATA_RATE = 0x00;
    public static final int QOS_CLASS = 0x02;
    public static final int PAYLOAD_TYPE = 0x00;
    public static final String AGENT_KEY = "samsung_gear_host_manager_agent_uuid";
    public static final String COMPONENT_KEY = "samsung_gear_host_manager_component_id";

    private final int agentUuid;
    private final int componentId;

    public SapHostManagerRegistration(final int agentUuid, final int componentId) {
        if (!validAgent(agentUuid) || !validComponent(componentId)) throw new IllegalArgumentException("Invalid owned Host Manager identity");
        this.agentUuid = agentUuid;
        this.componentId = componentId;
    }

    public static SapHostManagerRegistration getOrCreate(final SharedPreferences preferences) {
        synchronized (SapHostManagerRegistration.class) {
            final int agent = preferences.getInt(AGENT_KEY, -1);
            final int component = preferences.getInt(COMPONENT_KEY, -1);
            if (validAgent(agent) && validComponent(component)) return new SapHostManagerRegistration(agent, component);
            final SecureRandom random = new SecureRandom();
            final int newAgent = generateAgent(random);
            final int newComponent = generateComponent(random);
            if (!preferences.edit().putInt(AGENT_KEY, newAgent).putInt(COMPONENT_KEY, newComponent).commit()) {
                throw new IllegalStateException("Could not persist Gear Host Manager identity");
            }
            return new SapHostManagerRegistration(newAgent, newComponent);
        }
    }

    static int generateAgent(final SecureRandom random) { int id; do { id = random.nextInt(0x10000); } while (!validAgent(id)); return id; }
    static int generateComponent(final SecureRandom random) { return 0x0100 + random.nextInt(0xfe00); }
    static boolean validAgent(final int id) { return id >= 0 && id <= 0xfffe && id != 0x7413; }
    static boolean validComponent(final int id) { return id >= 0x0100 && id <= 0xfeff && id != 0xa323; }

    public int getAgentUuid() { return agentUuid; }
    public int getComponentId() { return componentId; }
    public SapCapabilityExchange.LocalServiceMetadata toCapabilityMetadata() {
        return new SapCapabilityExchange.LocalServiceMetadata(agentUuid, Integer.toString(agentUuid), FRIENDLY_NAME,
                componentId, Integer.toString(componentId), PROFILE, ASP_VERSION, "1.0", 0, ROLE_FLAGS,
                0, 2, 0);
    }
    public SapAgentDescriptor toCapabilityDescriptor() {
        return new SapAgentDescriptor(agentUuid, FRIENDLY_NAME,
                Collections.singletonList(new SapServiceDescriptor(componentId, PROFILE, ASP_VERSION, ROLE_FLAGS, 0)));
    }
}
