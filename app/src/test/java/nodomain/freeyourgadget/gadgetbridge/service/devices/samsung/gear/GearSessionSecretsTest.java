package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class GearSessionSecretsTest {
    @Test
    public void derivesTheExactOrderedHostManagerApplicationKey() {
        final GearSessionSecrets secrets = new GearSessionSecrets();
        secrets.setPrf(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
        assertArrayEquals(hex("8572bc810257ed522b4e5fe80135ff5f3a88d19c3c273dff51170a060617fa26"),
                secrets.deriveApplicationKey(0xa323, 0x1625));
        assertFalse(java.util.Arrays.equals(secrets.deriveApplicationKey(0xa323, 0x1625),
                secrets.deriveApplicationKey(0x1625, 0xa323)));
        assertThrows(IllegalStateException.class, () -> { secrets.clear(); secrets.deriveApplicationKey(0xa323, 0x1625); });
    }

    private static byte[] hex(final String value) {
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
