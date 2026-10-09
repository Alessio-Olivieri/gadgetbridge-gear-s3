package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded WSM v2 AppCipher envelope for one live Host Manager application
 * connection. The GCM tag is deliberately not part of this wire format; the
 * complete HMAC-SHA512 authenticates the envelope instead.
 */
public final class SapHostManagerAppCipher {
    public static final int KEY_LENGTH = 32;
    public static final int IV_LENGTH = 16;
    public static final int MAC_LENGTH = 64;
    public static final int OVERHEAD = IV_LENGTH + 4 + MAC_LENGTH;
    private static final long UINT32_MAX = 0xffffffffL;
    private static final int MAX_PAYLOAD_LENGTH = 65527;

    private byte[] key;
    private final SecureRandom random;
    private long txSequence;
    private long rxExpected;

    public SapHostManagerAppCipher(final byte[] derivedKey) {
        this(derivedKey, new SecureRandom());
    }

    SapHostManagerAppCipher(final byte[] derivedKey, final SecureRandom random) {
        if (derivedKey == null || derivedKey.length != KEY_LENGTH || random == null) {
            throw new IllegalArgumentException("Invalid Host Manager application cipher material");
        }
        key = Arrays.copyOf(derivedKey, KEY_LENGTH);
        this.random = random;
    }

    public synchronized byte[] protect(final byte[] plaintext) {
        requireActive();
        requirePayload(plaintext);
        final byte[] iv = new byte[IV_LENGTH];
        do {
            random.nextBytes(iv);
        } while (gcmCounterWouldWrap(iv, plaintext.length));
        return protectChecked(plaintext, iv);
    }

    synchronized byte[] protect(final byte[] plaintext, final byte[] iv) {
        requireActive();
        requirePayload(plaintext);
        if (iv == null || iv.length != IV_LENGTH) throw new IllegalArgumentException("Invalid AppCipher IV");
        if (txSequence > UINT32_MAX) throw new IllegalStateException("Host Manager AppCipher TX sequence exhausted");
        if (gcmCounterWouldWrap(iv, plaintext.length)) {
            throw new IllegalArgumentException("Host Manager AppCipher GCM low-32 counter wrap");
        }
        return protectChecked(plaintext, iv);
    }

    private byte[] protectChecked(final byte[] plaintext, final byte[] iv) {
        if (txSequence > UINT32_MAX) throw new IllegalStateException("Host Manager AppCipher TX sequence exhausted");
        final byte[] ciphertext = gcmPayloadStream(key, iv, plaintext);
        final byte[] body = new byte[IV_LENGTH + ciphertext.length + 4];
        System.arraycopy(iv, 0, body, 0, IV_LENGTH);
        System.arraycopy(ciphertext, 0, body, IV_LENGTH, ciphertext.length);
        writeLe32(body, IV_LENGTH + ciphertext.length, txSequence);
        final byte[] mac = hmac("HmacSHA512", key, body);
        final byte[] result = new byte[body.length + MAC_LENGTH];
        System.arraycopy(body, 0, result, 0, body.length);
        System.arraycopy(mac, 0, result, body.length, MAC_LENGTH);
        Arrays.fill(ciphertext, (byte) 0);
        Arrays.fill(body, (byte) 0);
        Arrays.fill(mac, (byte) 0);
        txSequence++;
        return result;
    }

    public synchronized byte[] unprotect(final byte[] protectedBytes) {
        requireActive();
        if (protectedBytes == null || protectedBytes.length < OVERHEAD + 1
                || protectedBytes.length > MAX_PAYLOAD_LENGTH + OVERHEAD) {
            throw new IllegalArgumentException("Invalid Host Manager AppCipher protected length");
        }
        final int bodyLength = protectedBytes.length - MAC_LENGTH;
        final byte[] body = Arrays.copyOf(protectedBytes, bodyLength);
        final byte[] expectedMac = hmac("HmacSHA512", key, body);
        try {
            if (!MessageDigest.isEqual(expectedMac, Arrays.copyOfRange(protectedBytes, bodyLength, protectedBytes.length))) {
                throw new IllegalArgumentException("Host Manager AppCipher MAC mismatch");
            }
        } finally {
            Arrays.fill(expectedMac, (byte) 0);
        }
        final long sequence = readLe32(body, body.length - 4);
        if (sequence < rxExpected) {
            Arrays.fill(body, (byte) 0);
            throw new IllegalArgumentException("Host Manager AppCipher replayed sequence");
        }
        if (rxExpected > UINT32_MAX) {
            Arrays.fill(body, (byte) 0);
            throw new IllegalStateException("Host Manager AppCipher RX sequence exhausted");
        }
        final byte[] iv = Arrays.copyOf(body, IV_LENGTH);
        final byte[] ciphertext = Arrays.copyOfRange(body, IV_LENGTH, body.length - 4);
        rxExpected++; // Increment the threshold by one, even after a gap.
        try {
            return ctrPayloadStream(key, iv, ciphertext);
        } finally {
            Arrays.fill(body, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    public synchronized long getTxSequence() { return txSequence; }
    public synchronized long getRxExpected() { return rxExpected; }

    public synchronized void clear() {
        if (key != null) Arrays.fill(key, (byte) 0);
        key = null;
        txSequence = 0;
        rxExpected = 0;
    }

    synchronized boolean isCleared() { return key == null; }

    private void requireActive() {
        if (key == null) throw new IllegalStateException("Host Manager AppCipher is cleared");
    }

    private static void requirePayload(final byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Invalid Host Manager AppCipher payload length");
        }
    }

    private static byte[] hmac(final String algorithm, final byte[] key, final byte[] data) {
        try {
            final Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (final Exception e) {
            throw new IllegalStateException("Host Manager AppCipher HMAC unavailable", e);
        }
    }

    /** GCM's IV-derived counter stream, with no transmitted GCM tag. */
    private static byte[] gcmPayloadStream(final byte[] key, final byte[] iv, final byte[] input) {
        return crypt(key, initialCounter(key, iv), input, true);
    }

    /** Use AES-CTR's full-width counter increment for received data. */
    private static byte[] ctrPayloadStream(final byte[] key, final byte[] iv, final byte[] input) {
        return crypt(key, initialCounter(key, iv), input, false);
    }

    private static byte[] crypt(final byte[] key, final byte[] initialCounter, final byte[] input,
                                final boolean incrementLow32) {
        try {
            final Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            final byte[] counter = Arrays.copyOf(initialCounter, IV_LENGTH);
            final byte[] output = new byte[input.length];
            for (int offset = 0; offset < input.length; offset += IV_LENGTH) {
                final byte[] stream = aes.doFinal(counter);
                final int count = Math.min(IV_LENGTH, input.length - offset);
                for (int i = 0; i < count; i++) output[offset + i] = (byte) (input[offset + i] ^ stream[i]);
                Arrays.fill(stream, (byte) 0);
                if (incrementLow32) incrementLow32(counter); else incrementFull(counter);
            }
            Arrays.fill(counter, (byte) 0);
            return output;
        } catch (final Exception e) {
            throw new IllegalStateException("Host Manager AppCipher AES unavailable", e);
        }
    }

    private static byte[] initialCounter(final byte[] key, final byte[] iv) {
        try {
            final Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            final byte[] h = aes.doFinal(new byte[IV_LENGTH]);
            final byte[] first = multiply(iv, h);
            first[IV_LENGTH - 1] ^= (byte) 0x80; // GHASH's BE64(128) low byte.
            final byte[] j0 = multiply(first, h);
            incrementLow32(j0);
            Arrays.fill(h, (byte) 0);
            Arrays.fill(first, (byte) 0);
            return j0;
        } catch (final Exception e) {
            throw new IllegalStateException("Host Manager AppCipher counter unavailable", e);
        }
    }

    private static byte[] multiply(final byte[] x, final byte[] y) {
        final byte[] z = new byte[IV_LENGTH];
        final byte[] v = Arrays.copyOf(y, IV_LENGTH);
        for (int bit = 0; bit < 128; bit++) {
            if ((x[bit / 8] & (1 << (7 - (bit % 8)))) != 0) xor(z, v);
            final boolean low = (v[IV_LENGTH - 1] & 1) != 0;
            shiftRight(v);
            if (low) v[0] ^= (byte) 0xe1;
        }
        Arrays.fill(v, (byte) 0);
        return z;
    }

    private boolean gcmCounterWouldWrap(final byte[] iv, final int payloadLength) {
        final byte[] counter = initialCounter(key, iv);
        try {
            final long low = readBe32(counter, IV_LENGTH - 4);
            final long blocks = (payloadLength + IV_LENGTH - 1L) / IV_LENGTH;
            return low + blocks > 0x100000000L;
        } finally {
            Arrays.fill(counter, (byte) 0);
        }
    }

    private static void xor(final byte[] destination, final byte[] source) {
        for (int i = 0; i < IV_LENGTH; i++) destination[i] ^= source[i];
    }
    private static void shiftRight(final byte[] bytes) {
        int carry = 0;
        for (int i = 0; i < bytes.length; i++) {
            final int current = bytes[i] & 0xff;
            bytes[i] = (byte) ((current >>> 1) | carry);
            carry = (current & 1) << 7;
        }
    }
    private static void incrementLow32(final byte[] value) {
        for (int i = value.length - 1; i >= value.length - 4; i--) {
            value[i]++;
            if (value[i] != 0) return;
        }
    }
    private static void incrementFull(final byte[] value) {
        for (int i = value.length - 1; i >= 0; i--) {
            value[i]++;
            if (value[i] != 0) return;
        }
    }
    private static void writeLe32(final byte[] value, final int offset, final long number) {
        for (int i = 0; i < 4; i++) value[offset + i] = (byte) (number >>> (8 * i));
    }
    private static long readLe32(final byte[] value, final int offset) {
        long result = 0;
        for (int i = 0; i < 4; i++) result |= (long) (value[offset + i] & 0xff) << (8 * i);
        return result;
    }
    private static long readBe32(final byte[] value, final int offset) {
        long result = 0;
        for (int i = 0; i < 4; i++) result = (result << 8) | (value[offset + i] & 0xffL);
        return result;
    }
}
