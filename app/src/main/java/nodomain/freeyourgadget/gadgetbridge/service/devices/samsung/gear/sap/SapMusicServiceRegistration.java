package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;

/** Locally owned provider descriptor for the Gear remote-music SAP service. */
public final class SapMusicServiceRegistration {
    public static final String PROFILE = "/system/music";
    public static final int API_CHANNEL = 100;
    public static final int ASP_VERSION = 0x0200;
    public static final int REMOTE_ASP_VERSION = 0x0100;
    public static final int ROLE_FLAGS = 0x04;
    public static final int REMOTE_ROLE_FLAGS = 0x05;
    public static final int QOS_TYPE = 0x04;
    public static final int QOS_DATA_RATE = 0;
    public static final int QOS_CLASS = 0x02;
    public static final int PAYLOAD_TYPE = 0;
    private static final String COMPONENT_KEY = "samsung_gear_music_component_id";
    private final int agentUuid, componentId;
    private RemoteDescriptor remote;

    private SapMusicServiceRegistration(final int agentUuid, final int componentId) { this.agentUuid = agentUuid; this.componentId = componentId; }
    public static SapMusicServiceRegistration getOrCreate(final SharedPreferences preferences, final SapHostManagerRegistration host, final SapBcmServiceRegistration bcm, final SapNotificationServiceRegistration notifications) {
        int id = preferences.getInt(COMPONENT_KEY, -1);
        if (id < 0x100 || id > 0xfeff || id == host.getComponentId() || id == bcm.getComponentId() || id == notifications.getComponentId()) {
            final SecureRandom random = new SecureRandom();
            do { id = SapHostManagerRegistration.generateComponent(random); }
            while (id == host.getComponentId() || id == bcm.getComponentId() || id == notifications.getComponentId());
            if (!preferences.edit().putInt(COMPONENT_KEY, id).commit()) throw new IllegalStateException("Could not persist Gear music component ID");
        }
        return new SapMusicServiceRegistration(host.getAgentUuid(), id);
    }
    public SapCapabilityExchange.LocalServiceMetadata toCapabilityMetadata() { return new SapCapabilityExchange.LocalServiceMetadata(agentUuid, Integer.toString(agentUuid), SapHostManagerRegistration.FRIENDLY_NAME, componentId, Integer.toString(componentId), PROFILE, ASP_VERSION, "2.0", 0, ROLE_FLAGS, 0, 2, 0); }
    public SapAgentDescriptor toCapabilityDescriptor() { return new SapAgentDescriptor(agentUuid, SapHostManagerRegistration.FRIENDLY_NAME, Collections.singletonList(new SapServiceDescriptor(componentId, PROFILE, ASP_VERSION, ROLE_FLAGS, 0))); }
    public void setRemoteConsumer(final SapAgentDescriptor agent, final SapServiceDescriptor service) {
        if (agent == null || service == null || !PROFILE.equals(service.getProfileId()) || service.getAspVersion() != REMOTE_ASP_VERSION || service.getRoleFlags() != REMOTE_ROLE_FLAGS || service.getTimeout() != 0) throw new IllegalArgumentException("Incompatible remote music consumer descriptor");
        remote = new RemoteDescriptor(service.getComponentId());
    }
    public int getComponentId() { return componentId; }
    public RemoteDescriptor getRemoteConsumer() { return remote; }
    public static final class RemoteDescriptor { private final int componentId; private RemoteDescriptor(final int componentId) { this.componentId = componentId; } public int getComponentId() { return componentId; } }
}
