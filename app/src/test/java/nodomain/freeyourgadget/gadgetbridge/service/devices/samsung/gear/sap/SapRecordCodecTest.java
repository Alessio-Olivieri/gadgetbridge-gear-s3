package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SapRecordCodecTest {
    @Test
    public void decodesFragmentedAndMultipleRecords() {
        final byte[] first = SapRecordCodec.encode(new SapRecord(new byte[]{0x20, 1, 2}));
        final byte[] second = SapRecordCodec.encode(new SapRecord(new byte[]{0x09}));
        final SapRecordCodec codec = new SapRecordCodec();
        assertEquals(0, codec.accept(new byte[]{first[0]}).size());
        final byte[] remaining = new byte[first.length - 1 + second.length];
        System.arraycopy(first, 1, remaining, 0, first.length - 1);
        System.arraycopy(second, 0, remaining, first.length - 1, second.length);
        final List<SapRecord> records = codec.accept(remaining);
        assertEquals(2, records.size());
        assertArrayEquals(new byte[]{0x20, 1, 2}, records.get(0).getPayload());
        assertArrayEquals(new byte[]{0x09}, records.get(1).getPayload());
    }

    @Test
    public void rejectsMalformedZeroLength() {
        assertThrows(IllegalArgumentException.class, () -> new SapRecordCodec().accept(new byte[]{0, 0}));
    }
}
