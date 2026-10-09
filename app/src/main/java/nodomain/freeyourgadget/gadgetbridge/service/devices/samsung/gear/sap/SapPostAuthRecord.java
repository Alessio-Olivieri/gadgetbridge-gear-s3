package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.Arrays;

/** A complete CRC-protected SAP connectivity record received after authentication. */
public final class SapPostAuthRecord {
    private final SapTransportHeader header;
    private final byte[] body;

    public SapPostAuthRecord(final SapTransportHeader header, final byte[] body) {
        if (header == null || body == null) throw new IllegalArgumentException("Missing SAP post-auth record data");
        this.header = header;
        this.body = Arrays.copyOf(body, body.length);
    }

    public SapTransportHeader getHeader() { return header; }
    public byte[] getBody() { return Arrays.copyOf(body, body.length); }
}
