package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Incremental parser for the two-byte SAP record length prefix. */
public final class SapRecordCodec {
    public static final int MAX_RECORD_LENGTH = 0xffff;
    private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();

    public synchronized List<SapRecord> accept(final byte[] data) {
        return accept(data, Integer.MAX_VALUE);
    }

    /**
     * Parses at most {@code maximumRecords}. This permits a protocol switch at
     * a record boundary when an RFCOMM read coalesces pre- and post-auth data.
     */
    public synchronized List<SapRecord> accept(final byte[] data, final int maximumRecords) {
        if (data != null && data.length > 0) buffered.write(data, 0, data.length);
        if (maximumRecords < 1) throw new IllegalArgumentException("maximumRecords must be positive");
        final byte[] all = buffered.toByteArray();
        final List<SapRecord> records = new ArrayList<>();
        int offset = 0;
        while (records.size() < maximumRecords && all.length - offset >= 2) {
            final int length = ((all[offset] & 0xff) << 8) | (all[offset + 1] & 0xff);
            if (length == 0) throw new IllegalArgumentException("Malformed SAP record: zero length");
            if (all.length - offset - 2 < length) break;
            final byte[] payload = new byte[length];
            System.arraycopy(all, offset + 2, payload, 0, length);
            records.add(new SapRecord(payload));
            offset += length + 2;
        }
        buffered.reset();
        if (offset < all.length) buffered.write(all, offset, all.length - offset);
        return records;
    }

    /** Returns unparsed bytes and clears this pre-auth decoder's buffer. */
    public synchronized byte[] takeBuffered() {
        final byte[] result = buffered.toByteArray();
        buffered.reset();
        return result;
    }

    public static byte[] encode(final SapRecord record) {
        final byte[] payload = record.getPayload();
        final byte[] encoded = new byte[payload.length + 2];
        encoded[0] = (byte) (payload.length >>> 8);
        encoded[1] = (byte) payload.length;
        System.arraycopy(payload, 0, encoded, 2, payload.length);
        return encoded;
    }
}
