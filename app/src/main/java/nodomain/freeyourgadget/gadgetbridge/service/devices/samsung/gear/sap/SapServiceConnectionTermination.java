package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/** SAP service-connection termination request on session 1023. */
public final class SapServiceConnectionTermination {
    public static final int TYPE = 0x03;

    private final int acceptorComponentId;
    private final int initiatorComponentId;
    private final String profile;

    public SapServiceConnectionTermination(final int acceptorComponentId, final int initiatorComponentId,
                                           final String profile) {
        if (!SapServiceConnectionRequest.isU16(acceptorComponentId)
                || !SapServiceConnectionRequest.isU16(initiatorComponentId)
                || profile == null || profile.isEmpty()) {
            throw new IllegalArgumentException("Invalid SAP service termination");
        }
        this.acceptorComponentId = acceptorComponentId;
        this.initiatorComponentId = initiatorComponentId;
        this.profile = profile;
    }

    public int getAcceptorComponentId() { return acceptorComponentId; }
    public int getInitiatorComponentId() { return initiatorComponentId; }
    public String getProfile() { return profile; }
}
