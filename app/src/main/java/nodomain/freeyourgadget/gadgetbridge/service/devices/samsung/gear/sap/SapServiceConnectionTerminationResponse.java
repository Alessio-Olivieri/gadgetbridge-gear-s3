package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/** SAP service-connection termination response on session 1023. */
public final class SapServiceConnectionTerminationResponse {
    public static final int TYPE = 0x04;
    public static final int STATUS_SUCCESS = 0;

    private final int acceptorComponentId;
    private final int initiatorComponentId;
    private final String profile;
    private final int status;

    public SapServiceConnectionTerminationResponse(final int acceptorComponentId, final int initiatorComponentId,
                                                   final String profile, final int status) {
        if (!SapServiceConnectionRequest.isU16(acceptorComponentId)
                || !SapServiceConnectionRequest.isU16(initiatorComponentId)
                || profile == null || profile.isEmpty() || status < 0 || status > 0xff) {
            throw new IllegalArgumentException("Invalid SAP service termination response");
        }
        this.acceptorComponentId = acceptorComponentId;
        this.initiatorComponentId = initiatorComponentId;
        this.profile = profile;
        this.status = status;
    }

    public int getAcceptorComponentId() { return acceptorComponentId; }
    public int getInitiatorComponentId() { return initiatorComponentId; }
    public String getProfile() { return profile; }
    public int getStatus() { return status; }
}
