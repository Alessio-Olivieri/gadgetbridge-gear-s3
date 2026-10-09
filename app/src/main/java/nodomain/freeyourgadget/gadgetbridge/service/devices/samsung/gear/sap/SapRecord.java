package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Arrays;

/** One SAP RFCOMM record: unsigned big-endian length followed by payload. */
public final class SapRecord {
    private final byte[] payload;

    public SapRecord(final byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 0xffff) {
            throw new IllegalArgumentException("Invalid SAP record payload length");
        }
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public byte getType() {
        return payload[0];
    }

    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
