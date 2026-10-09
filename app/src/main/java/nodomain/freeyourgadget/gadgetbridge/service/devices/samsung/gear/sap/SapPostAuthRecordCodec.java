package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental CRC-protected connectivity framing used by SAP post-auth sessions. */
public final class SapPostAuthRecordCodec {
    /** SM-R760 SSDU limit is 0xfff5; reserve one SDK-header byte. */
    public static final int MAX_PAYLOAD_LENGTH = 0xfff4;
    private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();

    public synchronized List<SapPostAuthRecord> accept(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new ArrayList<>();
        buffered.write(bytes, 0, bytes.length);
        final byte[] all = buffered.toByteArray();
        final List<SapPostAuthRecord> result = new ArrayList<>();
        int offset = 0;
        while (all.length - offset >= 4) {
            final int length = unsigned16(all, offset);
            if (length < 2 || length > MAX_PAYLOAD_LENGTH) {
                reset();
                throw new IllegalArgumentException("Invalid post-auth SAP payload length " + length);
            }
            if (crc16(all, offset, 2) != unsigned16(all, offset + 2)) {
                reset();
                throw new IllegalArgumentException("Post-auth SAP length CRC mismatch");
            }
            final int total = length + 6;
            if (all.length - offset < total) break;
            if (crc16(all, offset + 4, length) != unsigned16(all, offset + 4 + length)) {
                reset();
                throw new IllegalArgumentException("Post-auth SAP payload CRC mismatch");
            }
            final SapTransportHeader header = SapTransportHeader.parse(all, offset + 4);
            final byte[] body = Arrays.copyOfRange(all, offset + 6, offset + 4 + length);
            result.add(new SapPostAuthRecord(header, body));
            offset += total;
        }
        buffered.reset();
        if (offset < all.length) buffered.write(all, offset, all.length - offset);
        return result;
    }

    public static byte[] encode(final SapPostAuthRecord record) {
        final byte[] header = record.getHeader().encode();
        final byte[] body = record.getBody();
        final int length = header.length + body.length;
        if (length < 2 || length > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException("Invalid post-auth SAP payload length");
        final byte[] encoded = new byte[length + 6];
        write16(encoded, 0, length);
        write16(encoded, 2, crc16(encoded, 0, 2));
        System.arraycopy(header, 0, encoded, 4, 2);
        System.arraycopy(body, 0, encoded, 6, body.length);
        write16(encoded, 4 + length, crc16(encoded, 4, length));
        return encoded;
    }

    public static int crc16(final byte[] bytes, final int offset, final int length) {
        if (bytes == null || offset < 0 || length < 0 || bytes.length - offset < length) {
            throw new IllegalArgumentException("Invalid CRC range");
        }
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= bytes[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        return crc & 0xffff;
    }

    private void reset() { buffered.reset(); }
    private static int unsigned16(final byte[] bytes, final int offset) { return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff); }
    private static void write16(final byte[] bytes, final int offset, final int value) { bytes[offset] = (byte) (value >>> 8); bytes[offset + 1] = (byte) value; }
}
