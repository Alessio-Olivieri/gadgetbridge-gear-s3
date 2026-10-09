package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SapReconnectCodecTest {
    @Test
    public void parsesCapturedGearReconnectProbeAndRequestsConfigurationFallback() {
        final byte[] probe = new byte[39];
        probe[0] = 0x13;
        probe[1] = 0x04;
        probe[2] = 0x01;
        System.arraycopy("SAMSUNG_ACCESSARY__02:00:00:00:00:01".getBytes(StandardCharsets.UTF_8), 0, probe, 3, 36);

        final SapReconnectCodec.Probe parsed = SapReconnectCodec.parseProbe(probe);
        assertEquals(0x0401, parsed.getPdVersion());
        assertEquals("SAMSUNG_ACCESSARY__02:00:00:00:00:01", parsed.getPeerId());
        assertArrayEquals(new byte[]{0x16, 0x04, 0x01, 0x00, (byte) 0xff, 0x05},
                SapReconnectCodec.createConfigurationChangedResponse(parsed));
    }

    @Test
    public void rejectsMalformedReconnectProbe() {
        assertThrows(IllegalArgumentException.class,
                () -> SapReconnectCodec.parseProbe(new byte[]{0x13, 0x04, 0x01}));
        final byte[] invalidPeerId = new byte[39];
        invalidPeerId[0] = 0x13;
        invalidPeerId[1] = 0x04;
        invalidPeerId[2] = 0x01;
        assertThrows(IllegalArgumentException.class, () -> SapReconnectCodec.parseProbe(invalidPeerId));
    }
}
