package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/** Two-byte SAP transport header used by the post-auth reserved sessions. */
public final class SapTransportHeader {
    public static final int CAPABILITY_SESSION_ID = 1020;
    public static final int SERVICE_CONTROL_SESSION_ID = 1023;

    private final int version;
    private final int frameType;
    private final int sessionId;
    private final int fragment;

    public SapTransportHeader(final int version, final int frameType, final int sessionId, final int fragment) {
        if (version < 0 || version > 7 || frameType < 0 || frameType > 1
                || sessionId < 0 || sessionId > 1023 || fragment < 0 || fragment > 3) {
            throw new IllegalArgumentException("Invalid SAP transport header fields");
        }
        this.version = version;
        this.frameType = frameType;
        this.sessionId = sessionId;
        this.fragment = fragment;
    }

    public static SapTransportHeader parse(final byte[] payload, final int offset) {
        if (payload == null || offset < 0 || payload.length - offset < 2) {
            throw new IllegalArgumentException("Truncated SAP transport header");
        }
        final int first = payload[offset] & 0xff;
        final int second = payload[offset + 1] & 0xff;
        return new SapTransportHeader((first >>> 5) & 0x07, (first >>> 4) & 0x01,
                ((first & 0x0f) << 6) | ((second & 0xfc) >>> 2), second & 0x03);
    }

    public byte[] encode() {
        return new byte[]{
                (byte) ((version << 5) | (frameType << 4) | ((sessionId >>> 6) & 0x0f)),
                (byte) (((sessionId & 0x3f) << 2) | fragment)
        };
    }

    public int getVersion() { return version; }
    public int getFrameType() { return frameType; }
    public int getSessionId() { return sessionId; }
    public int getFragment() { return fragment; }

    public void requireCapexDataFrame() {
        if (version != 0 || frameType != 0 || sessionId != CAPABILITY_SESSION_ID || fragment != 0) {
            throw new IllegalArgumentException("Unsupported post-auth SAP transport frame");
        }
    }

    public void requireServiceControlDataFrame() {
        if (version != 0 || frameType != 0 || sessionId != SERVICE_CONTROL_SESSION_ID || fragment != 0) {
            throw new IllegalArgumentException("Unsupported SAP service-control transport frame");
        }
    }

    public void requireUnfragmentedDataFrame() {
        if (version != 0 || frameType != 0 || fragment != 0) {
            throw new IllegalArgumentException("Unsupported SAP application transport frame");
        }
    }
}
