package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/**
 * SDK service-channel envelope preceding bcmservice feature entries. This decoder
 * accepts only the normal, unencrypted, single-fragment form.
 */
public final class SapBcmServiceChannelHeader {
    public static final int SIZE = 1;
    private static final int NORMAL_UNENCRYPTED_SINGLE_FRAGMENT = 0x00;

    private SapBcmServiceChannelHeader() {
    }

    public static byte[] encodeNormalUnencryptedSingleFragment() {
        return new byte[]{NORMAL_UNENCRYPTED_SINGLE_FRAGMENT};
    }

    public static int decodeNormalUnencryptedSingleFragment(final byte[] bytes) {
        if (bytes == null || bytes.length < SIZE) {
            throw new IllegalArgumentException("Missing bcmservice SDK channel header");
        }
        if ((bytes[0] & 0xff) != NORMAL_UNENCRYPTED_SINGLE_FRAGMENT) {
            throw new IllegalArgumentException("Unsupported bcmservice SDK channel header 0x"
                    + Integer.toHexString(bytes[0] & 0xff));
        }
        return SIZE;
    }
}
