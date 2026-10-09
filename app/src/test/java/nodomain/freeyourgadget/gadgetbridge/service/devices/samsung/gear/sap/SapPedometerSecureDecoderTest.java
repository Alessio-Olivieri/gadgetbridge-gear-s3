package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearSessionSecrets;

public class SapPedometerSecureDecoderTest {
    private static final byte[] PRF = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] IV0 = hex("000102030405060708090a0b0c0d0e0f");
    private static final byte[] JSON = "{\"capability_type\":\"request\",\"sender\":\"wearable\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    public void derivesHealthKeyInDynamicGearProviderPhoneConsumerOrder() {
        final GearSessionSecrets secrets = secrets();
        final int provider = 0x3900;
        final int consumer = 0x222b;
        final byte[] expectedKey = secrets.deriveApplicationKey(provider, consumer);
        final byte[] protectedBytes = new SapHostManagerAppCipher(expectedKey).protect(JSON, IV0);
        assertArrayEquals(JSON, SapPedometerSecureDecoder.forDynamicComponents(secrets, provider, consumer)
                .decode(sdk(0x04, protectedBytes)).getApplicationBytes());
        assertFalse(SapPedometerSecureDecoder.forDynamicComponents(secrets, consumer, provider)
                .decode(sdk(0x04, protectedBytes)).isValid());
    }

    @Test
    public void healthCipherStateIsIndependentFromHostManagerCipher() {
        final byte[] key = secrets().deriveApplicationKey(0x3900, 0x222b);
        final SapHostManagerAppCipher watchHealth = new SapHostManagerAppCipher(key);
        final SapHostManagerAppCipher hostManager = new SapHostManagerAppCipher(key);
        hostManager.unprotect(hostManager.protect(new byte[]{9}, IV0));
        final SapPedometerSecureDecoder health = new SapPedometerSecureDecoder(new SapHostManagerAppCipher(key));
        assertArrayEquals(JSON, health.decode(sdk(0x04, watchHealth.protect(JSON, IV0))).getApplicationBytes());
        assertEquals(1, hostManager.getRxExpected());
    }

    @Test
    public void decodesEncryptedSingleSdkMessage() {
        final Pair pair = pair();
        final SapPedometerSecureDecoder.Result result = pair.decoder.decode(sdk(0x04, pair.sender.protect(JSON, IV0)));
        assertTrue(result.isValid());
        assertTrue(result.isHmacVerified());
        assertFalse(result.isCompressed());
        assertEquals(0, result.getSequence());
        assertArrayEquals(JSON, result.getApplicationBytes());
    }

    @Test
    public void decodesEncryptedZlibSingleSdkMessage() throws Exception {
        final Pair pair = pair();
        final byte[] compressed = zlib(JSON);
        final SapPedometerSecureDecoder.Result result = pair.decoder.decode(sdk(0x14, pair.sender.protect(compressed, IV0)));
        assertTrue(result.isValid());
        assertTrue(result.isCompressed());
        assertEquals(compressed.length, result.getDecryptedLength());
        assertArrayEquals(JSON, result.getApplicationBytes());
    }

    @Test
    public void badHmacIsIgnoredWithoutPoisoningFollowingMessage() {
        final Pair pair = pair();
        final byte[] wire = sdk(0x04, pair.sender.protect(JSON, IV0));
        final byte[] bad = Arrays.copyOf(wire, wire.length);
        bad[bad.length - 1] ^= 1;
        assertFalse(pair.decoder.decode(bad).isValid());
        assertArrayEquals(JSON, pair.decoder.decode(wire).getApplicationBytes());
    }

    @Test
    public void replayedSequenceIsRejectedAfterSuccessfulAuthentication() {
        final Pair pair = pair();
        final byte[] wire = sdk(0x04, pair.sender.protect(JSON, IV0));
        assertTrue(pair.decoder.decode(wire).isValid());
        final SapPedometerSecureDecoder.Result replay = pair.decoder.decode(wire);
        assertFalse(replay.isValid());
        assertTrue(replay.isHmacVerified());
    }

    @Test
    public void malformedZlibIsIgnoredSafely() {
        final Pair pair = pair();
        final SapPedometerSecureDecoder.Result result = pair.decoder.decode(
                sdk(0x14, pair.sender.protect(new byte[]{1, 2, 3}, IV0)));
        assertFalse(result.isValid());
        assertTrue(result.isHmacVerified());
    }

    @Test
    public void fragmentedSdkPayloadAuthenticatesAndReassemblesAfterPerFragmentInflate() throws Exception {
        final Pair pair = pair();
        final byte[] first = "{\"capability_type\":\"com.samsung.shealth.REQUEST_CAPABILITY\",".getBytes(StandardCharsets.UTF_8);
        final byte[] second = "\"message_type\":\"com.samsung.wearable.app.shealth.REQUEST_CAPABILITY\"}".getBytes(StandardCharsets.UTF_8);
        final SapPedometerSecureDecoder.Result firstResult = pair.decoder.decode(
                sdk(0x15, pair.sender.protect(zlib(first), IV0)));
        assertTrue(firstResult.isValid());
        assertTrue(firstResult.isHmacVerified());
        assertTrue(firstResult.isDecryptSucceeded());
        assertFalse(firstResult.isReassemblyComplete());
        assertEquals(1, firstResult.getFragmentOrdinal());
        assertEquals(first.length, firstResult.getAccumulatedLength());

        final byte[] iv1 = Arrays.copyOf(IV0, IV0.length);
        iv1[15] = 1;
        final SapPedometerSecureDecoder.Result lastResult = pair.decoder.decode(
                sdk(0x17, pair.sender.protect(zlib(second), iv1)));
        assertTrue(lastResult.isValid());
        assertTrue(lastResult.isReassemblyComplete());
        assertEquals(2, lastResult.getFragmentOrdinal());
        assertEquals(first.length + second.length, lastResult.getReassembledLength());
        assertArrayEquals(concat(first, second), lastResult.getApplicationBytes());
    }

    @Test
    public void nonFragmentDuringReassemblyIsRejectedAndClearsAccumulator() throws Exception {
        final Pair pair = pair();
        final byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        final byte[] wire = sdk(0x15, pair.sender.protect(zlib(first), IV0));
        assertTrue(pair.decoder.decode(wire).isValid());
        final byte[] iv1 = Arrays.copyOf(IV0, IV0.length);
        iv1[15] = 1;
        final SapPedometerSecureDecoder.Result result = pair.decoder.decode(
                sdk(0x04, pair.sender.protect(JSON, iv1)));
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("non-fragment"));
    }

    @Test
    public void lastFragmentWithoutFirstIsRejectedAndNextMessageCanAuthenticate() {
        final Pair pair = pair();
        final byte[] wire = sdk(0x04, pair.sender.protect(JSON, IV0));
        wire[0] = 0x07;
        final SapPedometerSecureDecoder.Result result = pair.decoder.decode(wire);
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("last fragment"));
        final byte[] iv1 = Arrays.copyOf(IV0, IV0.length);
        iv1[15] = 1;
        assertArrayEquals(JSON, pair.decoder.decode(sdk(0x04, pair.sender.protect(JSON, iv1))).getApplicationBytes());
    }

    @Test
    public void messageModeIntermediateSdkStateIsNotAcceptedOnApi999() {
        final Pair pair = pair();
        final byte[] wire = sdk(0x04, pair.sender.protect(JSON, IV0));
        wire[0] = 0x06;
        final SapPedometerSecureDecoder.Result result = pair.decoder.decode(wire);
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("unsupported SDK fragment state 2"));
    }

    @Test
    public void clearJsonStillFeedsExistingProbe() {
        final Pair pair = pair();
        final byte[] clear = pair.decoder.decode(sdk(0x04, pair.sender.protect(JSON, IV0))).getApplicationBytes();
        assertTrue(SapPedometerJsonProbe.parse(clear).isValid());
    }

    private static Pair pair() {
        final byte[] key = secrets().deriveApplicationKey(0x3900, 0x222b);
        return new Pair(new SapHostManagerAppCipher(key),
                new SapPedometerSecureDecoder(new SapHostManagerAppCipher(key)));
    }
    private static GearSessionSecrets secrets() {
        final GearSessionSecrets secrets = new GearSessionSecrets();
        secrets.setPrf(PRF);
        return secrets;
    }
    private static byte[] sdk(final int header, final byte[] protectedBytes) {
        final byte[] result = new byte[protectedBytes.length + 1];
        result[0] = (byte) header;
        System.arraycopy(protectedBytes, 0, result, 1, protectedBytes.length);
        return result;
    }
    private static byte[] zlib(final byte[] value) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) { deflater.write(value); }
        return output.toByteArray();
    }
    private static byte[] concat(final byte[] first, final byte[] second) {
        final byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
    private static byte[] hex(final String value) {
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static final class Pair {
        private final SapHostManagerAppCipher sender;
        private final SapPedometerSecureDecoder decoder;
        private Pair(final SapHostManagerAppCipher sender, final SapPedometerSecureDecoder decoder) {
            this.sender = sender; this.decoder = decoder;
        }
    }
}
