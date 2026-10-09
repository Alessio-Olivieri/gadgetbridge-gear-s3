package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SapPdCodecTest {
    @Test
    public void serializesVerifiedType6Shape() {
        final String clientPeerId = "SAP_BC1D40FC7691D1B1B21EACBBA235D3D3";
        final byte[] response = SapPdCodec.createResponse(clientPeerId, "Pixel 8 Pro", "Google", "phone");
        assertEquals(6, response[0]);
        assertEquals(0x04, response[1]);
        assertEquals(0x01, response[2]);
        assertEquals(0, response[5]);
        assertEquals(2, response[6]);
        assertTrue(new String(response).contains("SWatch;"));
        assertTrue(new String(response).contains(clientPeerId));
    }

    @Test
    public void parsesGearPeerIdFromLiveType5Request() {
        final byte[] request = concat(hex("05040104010000200000fff503e0ffff02000a01"),
                "SM-R760;SAMSUNG;GEAR_SERIES;/Sband+;SAMSUNG_ACCESSARY__02:00:00:00:00:01"
                        .getBytes(), hex("01011f"));
        assertEquals("SM-R760", SapPdCodec.parseWatchModel(request));
        assertEquals("SAMSUNG_ACCESSARY__02:00:00:00:00:01", SapPdCodec.parseWatchPeerId(request));
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
