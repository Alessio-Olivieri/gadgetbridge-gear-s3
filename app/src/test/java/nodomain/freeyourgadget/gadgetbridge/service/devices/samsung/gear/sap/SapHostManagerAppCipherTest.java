package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class SapHostManagerAppCipherTest {
    private static final byte[] KEY = hex("8572bc810257ed522b4e5fe80135ff5f3a88d19c3c273dff51170a060617fa26");
    private static final byte[] IV = hex("000102030405060708090a0b0c0d0e0f");
    private static final byte[] PLAIN = hex("00007b226d73674964223a2273796e7468657469635f6170706369706865725f766563746f72227d");
    private static final byte[] CIPHER = hex("1179e68a29e5baa3eef5df608d3b5b5f317cb44cd2ea44fd7ed55365b48215f6834746905930e0c0");
    private static final byte[] MAC = hex("08e3a824ee331dde9ed3c9603c82dc370239a0d942b0619b995ac8904259b15a3e8edba545c17d9736c4e99a53e3b45695e4ecf89e27445111bb39942e3e8546");

    @Test
    public void exactUncompressedVectorAndTxSequence() {
        final SapHostManagerAppCipher cipher = new SapHostManagerAppCipher(KEY);
        final byte[] wire = cipher.protect(PLAIN, IV);
        assertArrayEquals(CIPHER, Arrays.copyOfRange(wire, 16, wire.length - 68));
        assertArrayEquals(MAC, Arrays.copyOfRange(wire, wire.length - 64, wire.length));
        assertEquals(1, cipher.getTxSequence());
        assertArrayEquals(PLAIN, new SapHostManagerAppCipher(KEY).unprotect(wire));
    }

    @Test
    public void vectorSizesMacCoverageAndTamperingAreStrict() {
        for (final int size : new int[]{1, 15, 16, 17, 31, 32, 33, 721}) {
            final byte[] value = new byte[size];
            Arrays.fill(value, (byte) 0x5a);
            final byte[] wire = new SapHostManagerAppCipher(KEY).protect(value, IV);
            assertArrayEquals(value, new SapHostManagerAppCipher(KEY).unprotect(wire));
        }
        final byte[] valid = new SapHostManagerAppCipher(KEY).protect(PLAIN, IV);
        for (final int index : new int[]{0, 16, valid.length - 65, valid.length - 1}) {
            final byte[] altered = Arrays.copyOf(valid, valid.length);
            altered[index] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> new SapHostManagerAppCipher(KEY).unprotect(altered));
        }
    }

    @Test
    public void replayIsRejectedGapAdvancesThresholdOnceAndClearDisablesCipher() {
        final SapHostManagerAppCipher sender = new SapHostManagerAppCipher(KEY);
        final byte[] first = sender.protect(new byte[]{1}, IV);
        final SapHostManagerAppCipher receiver = new SapHostManagerAppCipher(KEY);
        receiver.unprotect(first);
        assertThrows(IllegalArgumentException.class, () -> receiver.unprotect(first));
        final byte[] gap = resignWithSequence(first, 7);
        final SapHostManagerAppCipher gapReceiver = new SapHostManagerAppCipher(KEY);
        gapReceiver.unprotect(gap);
        assertEquals(1, gapReceiver.getRxExpected());
        receiver.clear();
        assertTrue(receiver.isCleared());
        assertThrows(IllegalStateException.class, () -> receiver.unprotect(first));
    }

    @Test
    public void compressedVectorHasTheDocumentedFrameDigest() throws Exception {
        final byte[] compressed = hex("789c6360a856ca2d4ef74c51b2522aaecc2bc9482dc94c8e4f2c2848ce2cc8482d8a2f4b4d2ec92f52d2514a492c4904aaa91805a360148c58a0540b009e0df12e");
        final byte[] protectedPayload = new SapHostManagerAppCipher(KEY).protect(compressed, IV);
        final byte[] frame = SapHostManagerServiceChannelHeader.encodeSecureSingleMessage(true, protectedPayload);
        assertEquals("aea97bdfefd15cebd0b041ef4369e13b996ad5e09151343b9e1a4e04c3d7108c", sha256(frame));
    }

    private static String sha256(final byte[] input) throws Exception {
        final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(input);
        final StringBuilder result = new StringBuilder();
        for (final byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
    private static byte[] resignWithSequence(final byte[] wire, final int sequence) {
        final byte[] result = Arrays.copyOf(wire, wire.length);
        final int sequenceOffset = result.length - 68;
        for (int i = 0; i < 4; i++) result[sequenceOffset + i] = (byte) (sequence >>> (8 * i));
        try {
            final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec(KEY, "HmacSHA512"));
            final byte[] tag = mac.doFinal(Arrays.copyOf(result, result.length - 64));
            System.arraycopy(tag, 0, result, result.length - 64, tag.length);
            return result;
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
    private static byte[] hex(final String value) {
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
