package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Arrays;

/** The three-byte WSM v2 inner header: version, message type, total length. */
public final class WsmV2AuthPacket {
    public static final int VERSION = 2;
    private final int type;
    private final byte[] payload;

    public WsmV2AuthPacket(final int type, final byte[] payload) {
        if (payload.length + 3 > 255) throw new IllegalArgumentException("WSM packet too large");
        this.type = type;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public int getType() { return type; }
    public byte[] getPayload() { return Arrays.copyOf(payload, payload.length); }
    public byte[] encode() {
        final byte[] result = new byte[payload.length + 3];
        result[0] = VERSION; result[1] = (byte) type; result[2] = (byte) result.length;
        System.arraycopy(payload, 0, result, 3, payload.length);
        return result;
    }
    public static WsmV2AuthPacket parse(final byte[] bytes) {
        if (bytes == null || bytes.length < 3 || bytes[0] != VERSION || (bytes[2] & 0xff) != bytes.length) {
            throw new IllegalArgumentException("Invalid WSM v2 AuthPacket");
        }
        return new WsmV2AuthPacket(bytes[1] & 0xff, Arrays.copyOfRange(bytes, 3, bytes.length));
    }
}
