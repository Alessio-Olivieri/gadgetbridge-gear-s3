package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SapPd33CodecTest {
    // SM-R760 type-0x14 record shape after the 0x16 fallback, with a synthetic peer address.
    private static final byte[] SYNTHETIC_GEAR_OFFER = hex(
            "140401040100"
                    + "53414d53554e475f4143434553534152595f5f30323a30303a30303a30303a30303a3031"
                    + "2d0c"
                    + "010020000002fff50303e004ffff05000a"
                    + "0607534d2d52373630"
                    + "070753414d53554e47"
                    + "080b474541525f534552494553"
                    + "09072f5362616e642b"
                    + "0a010b010c1f");

    @Test
    public void parsesSyntheticGearOfferAndBuildsSourceCorrelatedAnswer() {
        assertEquals(107, SYNTHETIC_GEAR_OFFER.length);
        final SapPd33Codec.Offer offer = SapPd33Codec.parseOffer(SYNTHETIC_GEAR_OFFER);
        assertEquals("SAMSUNG_ACCESSARY__02:00:00:00:00:01", offer.getPeerId());
        assertEquals(0x0401, offer.getSoftwareVersion());
        assertEquals(0x00200000, offer.getApduSize());
        assertEquals(0xfff5, offer.getSsduSize());
        assertEquals(0x03e0, offer.getMaxSessions());
        assertEquals(0xffff, offer.getTimeout());
        assertEquals("SM-R760", offer.getModel());
        assertEquals("SAMSUNG", offer.getManufacturer());
        assertEquals("GEAR_SERIES", offer.getFriendlyName());
        assertEquals("/Sband+", offer.getProfile());

        final String peerId = "SAP_BC1D40FC7691D1B1B21EACBBA235D3D3";
        final byte[] answer = SapPd33Codec.createAnswer(offer, new SapPd33Codec.ClientInfo(
                peerId, "Pixel 8 Pro", "Google", "Pixel 8 Pro", 0, 0));

        final byte[] expectedPayload = concat(
                hex("15040100ff00"), peerId.getBytes(StandardCharsets.UTF_8),
                hex("2d07042710080b"), "Pixel 8 Pro".getBytes(StandardCharsets.UTF_8),
                hex("0706"), "Google".getBytes(StandardCharsets.UTF_8),
                hex("060b"), "Pixel 8 Pro".getBytes(StandardCharsets.UTF_8),
                hex("0a000906"), "SWatch".getBytes(StandardCharsets.UTF_8), hex("0c00"));
        assertEquals(93, answer.length);
        assertArrayEquals(expectedPayload, answer);
        assertArrayEquals(concat(hex("005d"), expectedPayload),
                SapRecordCodec.encode(new SapRecord(answer)));
    }

    @Test
    public void rejectsMalformedOfferBeforeNegotiation() {
        assertThrows(IllegalArgumentException.class,
                () -> SapPd33Codec.parseOffer(new byte[]{0x14, 0x04, 0x01}));

        final byte[] wrongMode = SYNTHETIC_GEAR_OFFER.clone();
        wrongMode[42] = 0x21;
        assertThrows(IllegalArgumentException.class, () -> SapPd33Codec.parseOffer(wrongMode));
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
