package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SapAccessoryAuthenticationCodecTest {
    @Test
    public void encodesCompleteCapturedWsmInitRecordShape() throws Exception {
        final byte[] commitment = hex("3362dd7541034b7d4bdce62b37b5accf9f882119ac18e38b37c8d21568b78d2c"
                + "5fc7fe40aa4e306a67819e3a7723c9cd8dba9198a16782cbfe8ed8f7d3cdb786");
        final byte[] inner = new WsmV2AuthPacket(0x06, commitment).encode();
        assertEquals(67, inner.length);
        assertArrayEquals(commitment, WsmV2Crypto.commitment(hex("0401156b1948f0b962d5ac44534ebdf6b1e32e7804f5a66bcc57e87b0ebdebbc8771a3f6a0887387807ab2d6b705946e8b91f09f2f0aab9768b02725d96fc0fbb5ceff011759e51f30fe8e3fc85c9d4c48a5dd2961f94b1c2a0d9107e1000cdc10daf9d12deeecc4441540b9f805d2febcb2a5cd8e85b3b9277fb98030480bc4297546b387")));

        final byte[] payload = SapAccessoryAuthenticationCodec.encode(0x20, inner);
        final byte[] record = SapRecordCodec.encode(new SapRecord(payload));
        assertArrayEquals(concat(hex("00452000020643"), commitment), record);
        assertEquals(69, payload.length);
        assertArrayEquals(inner, SapAccessoryAuthenticationCodec.decode(payload, 0x20, 67));
    }

    @Test
    public void rejectsMissingOrUnexpectedAuthenticationEnvelope() {
        assertThrows(IllegalArgumentException.class,
                () -> SapAccessoryAuthenticationCodec.decode(new byte[68], 0x20, 67));
        assertThrows(IllegalArgumentException.class,
                () -> SapAccessoryAuthenticationCodec.encode(0x20, new byte[0]));
    }

    @Test
    public void decodesCompleteWsmServerConfirmRecordShape() {
        final byte[] record = hex("0045120002024396442D6FEDAAF3D0B7E0C654965744534F266CA3DF222E4FF74F84D3D64AF77294EDBF1CC679D5F491189AACBD35E6F043D09F6A430E2282DBAE38B2B022F83B");
        final byte[] payload = new byte[69];
        System.arraycopy(record, 2, payload, 0, payload.length);

        final byte[] inner = SapAccessoryAuthenticationCodec.decode(payload, 0x12, 67);
        final WsmV2AuthPacket packet = WsmV2AuthPacket.parse(inner);
        assertEquals(2, packet.getType());
        assertEquals(64, packet.getPayload().length);
        assertArrayEquals(record, SapRecordCodec.encode(new SapRecord(payload)));
    }

    private static byte[] concat(final byte[]... parts) {
        int length = 0;
        for (final byte[] part : parts) length += part.length;
        final byte[] result = new byte[length];
        int offset = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] hex(final String value) {
        final byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
