package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Arrays;

/** two-byte marker for one clear Host Manager message. */
public final class SapHostManagerMarker {
    public static final int SIZE = 2;
    private static final byte[] SINGLE_MESSAGE = new byte[]{0, 0};

    private SapHostManagerMarker() {
    }

    public static byte[] singleMessage() {
        return Arrays.copyOf(SINGLE_MESSAGE, SIZE);
    }

    public static int requireSingleMessage(final byte[] bytes, final int offset) {
        if (bytes == null || offset < 0 || bytes.length - offset < SIZE
                || bytes[offset] != 0 || bytes[offset + 1] != 0) {
            throw new IllegalArgumentException("Invalid Host Manager single-message marker");
        }
        return offset + SIZE;
    }
}
