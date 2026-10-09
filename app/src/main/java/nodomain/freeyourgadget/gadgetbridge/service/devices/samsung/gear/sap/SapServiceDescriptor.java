package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/** Raw SAP service metadata as exchanged by CapEx. */
public final class SapServiceDescriptor {
    private final int componentId;
    private final String profileId;
    private final int aspVersion;
    private final int roleFlags;
    private final int timeout;

    public SapServiceDescriptor(final int componentId, final String profileId, final int aspVersion,
                                final int roleFlags, final int timeout) {
        if (componentId < 0 || componentId > 0xffff || profileId == null || aspVersion < 0 || aspVersion > 0xffff
                || roleFlags < 0 || roleFlags > 0xff || timeout < 0 || timeout > 0xffff) {
            throw new IllegalArgumentException("Invalid SAP service descriptor");
        }
        this.componentId = componentId;
        this.profileId = profileId;
        this.aspVersion = aspVersion;
        this.roleFlags = roleFlags;
        this.timeout = timeout;
    }
    public int getComponentId() { return componentId; }
    public String getProfileId() { return profileId; }
    public int getAspVersion() { return aspVersion; }
    public int getRoleFlags() { return roleFlags; }
    public int getTimeout() { return timeout; }
}
