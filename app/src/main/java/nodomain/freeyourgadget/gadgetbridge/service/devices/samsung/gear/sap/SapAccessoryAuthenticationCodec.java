package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Arrays;

/** Samsung Accessory Authenticate envelope: outer message type, auth type, WSM AuthPacket. */
public final class SapAccessoryAuthenticationCodec {
    public static final int AUTH_TYPE_WSM_V2 = 0;

    private SapAccessoryAuthenticationCodec() {
    }

    public static byte[] encode(final int messageType, final byte[] securityPacket) {
        validateMessageType(messageType);
        if (securityPacket == null || securityPacket.length == 0) {
            throw new IllegalArgumentException("Missing WSM security packet");
        }
        final byte[] payload = new byte[securityPacket.length + 2];
        payload[0] = (byte) messageType;
        payload[1] = AUTH_TYPE_WSM_V2;
        System.arraycopy(securityPacket, 0, payload, 2, securityPacket.length);
        return payload;
    }

    public static byte[] decode(final byte[] payload, final int expectedMessageType,
                                final int expectedSecurityPacketLength) {
        validateMessageType(expectedMessageType);
        if (payload == null || payload.length != expectedSecurityPacketLength + 2
                || (payload[0] & 0xff) != expectedMessageType
                || (payload[1] & 0xff) != AUTH_TYPE_WSM_V2) {
            throw new IllegalArgumentException("Invalid Samsung Accessory Authenticate envelope");
        }
        return Arrays.copyOfRange(payload, 2, payload.length);
    }

    private static void validateMessageType(final int messageType) {
        if (messageType != 0x20 && messageType != 0x10 && messageType != 0x11 && messageType != 0x12) {
            throw new IllegalArgumentException("Invalid Samsung Accessory Authenticate message type");
        }
    }
}
