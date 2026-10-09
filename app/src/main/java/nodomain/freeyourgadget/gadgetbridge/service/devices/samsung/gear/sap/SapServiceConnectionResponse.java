package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed generic SAP service-connection response (default session 1023). */
public final class SapServiceConnectionResponse {
    public static final int TYPE = 0x02;
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_REJECTED = 1;
    public static final int STATUS_SESSION_COLLISION = 3;
    private final int acceptorSlot;
    private final int initiatorSlot;
    private final String profile;
    private final int status;
    private final List<Integer> sessionIds;
    public SapServiceConnectionResponse(final int acceptorSlot, final int initiatorSlot, final String profile,
                                        final int status, final List<Integer> sessionIds) {
        if (!SapServiceConnectionRequest.isU16(acceptorSlot) || !SapServiceConnectionRequest.isU16(initiatorSlot)
                || profile == null || profile.length() == 0 || status < 0 || status > 0xff || sessionIds == null || sessionIds.isEmpty()) {
            throw new IllegalArgumentException("Invalid SAP service response");
        }
        for (final Integer id : sessionIds) if (id == null || !SapServiceConnectionRequest.isU16(id)) throw new IllegalArgumentException("Invalid SAP response session");
        this.acceptorSlot = acceptorSlot; this.initiatorSlot = initiatorSlot; this.profile = profile; this.status = status;
        this.sessionIds = Collections.unmodifiableList(new ArrayList<>(sessionIds));
    }
    public int getAcceptorSlot() { return acceptorSlot; }
    public int getInitiatorSlot() { return initiatorSlot; }
    public String getProfile() { return profile; }
    public int getStatus() { return status; }
    public List<Integer> getSessionIds() { return sessionIds; }
}
