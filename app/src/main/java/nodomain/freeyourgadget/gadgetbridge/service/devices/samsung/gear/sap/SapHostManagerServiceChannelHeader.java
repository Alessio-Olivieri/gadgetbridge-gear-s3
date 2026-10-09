package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/** Typed SDK service-channel headers used by bounded Host Manager messages. */
public final class SapHostManagerServiceChannelHeader {
    public static final int SIZE = 1;
    private static final int CLEAR_SINGLE_MESSAGE = 0;
    private static final int SECURE_SINGLE_MESSAGE = 0x04;
    private static final int COMPRESSED_SECURE_SINGLE_MESSAGE = 0x14;

    private SapHostManagerServiceChannelHeader() {
    }

    public static byte[] encodeClearSingleMessage() { return new byte[]{CLEAR_SINGLE_MESSAGE}; }

    public static int requireClearSingleMessage(final byte[] bytes) {
        if (bytes == null || bytes.length < SIZE || (bytes[0] & 0xff) != CLEAR_SINGLE_MESSAGE) {
            throw new IllegalArgumentException("Invalid clear Host Manager SDK service-channel header");
        }
        return SIZE;
    }

    public static int requireSecureSingleMessage(final byte[] bytes) {
        if (bytes == null || bytes.length <= SIZE || (bytes[0] & 0xff) != SECURE_SINGLE_MESSAGE) {
            throw new IllegalArgumentException("Invalid secure Host Manager SDK service-channel header");
        }
        return SIZE;
    }

    public static int requireCompressedSecureSingleMessage(final byte[] bytes) {
        if (bytes == null || bytes.length <= SIZE || (bytes[0] & 0xff) != COMPRESSED_SECURE_SINGLE_MESSAGE) {
            throw new IllegalArgumentException("Invalid compressed secure Host Manager SDK service-channel header");
        }
        return SIZE;
    }

    public static boolean isSecure(final int header) {
        return header == SECURE_SINGLE_MESSAGE || header == COMPRESSED_SECURE_SINGLE_MESSAGE;
    }

    public static boolean isCompressedSecure(final int header) { return header == COMPRESSED_SECURE_SINGLE_MESSAGE; }

    public static byte[] encodeSecureSingleMessage(final boolean compressed, final byte[] protectedPayload) {
        if (protectedPayload == null || protectedPayload.length == 0) {
            throw new IllegalArgumentException("Missing secure Host Manager payload");
        }
        final byte[] result = new byte[SIZE + protectedPayload.length];
        result[0] = (byte) (compressed ? COMPRESSED_SECURE_SINGLE_MESSAGE : SECURE_SINGLE_MESSAGE);
        System.arraycopy(protectedPayload, 0, result, SIZE, protectedPayload.length);
        return result;
    }
}
