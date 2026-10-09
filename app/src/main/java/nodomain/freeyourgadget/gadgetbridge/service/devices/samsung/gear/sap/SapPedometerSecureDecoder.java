package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearSessionSecrets;

/** Receive-only SDK/AppCipher boundary for {@code /system/watch_pedometer}. */
public final class SapPedometerSecureDecoder {
    public static final int SDK_ENCRYPTED = 0x04;
    public static final int SDK_COMPRESSED = 0x10;
    public static final int SDK_FRAGMENT_MASK = 0x03;
    public static final int SDK_FRAGMENT_FIRST_OR_INTERMEDIATE = 0x01;
    public static final int SDK_FRAGMENT_LAST = 0x03;
    private static final int SDK_KNOWN_MASK = SDK_ENCRYPTED | SDK_COMPRESSED | SDK_FRAGMENT_MASK;
    private static final int MAX_CLEAR_LENGTH = 65527;
    private static final int MAX_REASSEMBLED_LENGTH = 4 * 1024 * 1024;

    private final SapHostManagerAppCipher cipher;
    private final ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();
    private boolean fragmentActive;
    private int fragmentOrdinal;
    private int fragmentDecryptedLength;
    private int fragmentClearLength;

    public SapPedometerSecureDecoder(final SapHostManagerAppCipher cipher) {
        if (cipher == null) throw new IllegalArgumentException("Missing health AppCipher");
        this.cipher = cipher;
    }

    public static SapPedometerSecureDecoder forDynamicComponents(final GearSessionSecrets secrets,
                                                                  final int gearProviderComponent,
                                                                  final int phoneConsumerComponent) {
        if (secrets == null) throw new IllegalArgumentException("Missing authenticated WSM secrets");
        final byte[] key = secrets.deriveApplicationKey(gearProviderComponent, phoneConsumerComponent);
        try {
            return new SapPedometerSecureDecoder(new SapHostManagerAppCipher(key));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public Result decode(final byte[] body) {
        if (body == null || body.length < 2) {
            clearFragments();
            return Result.failure(-1, -1, "missing SDK/protected body", false, false);
        }
        final int sdk = body[0] & 0xff;
        final int fragment = sdk & SDK_FRAGMENT_MASK;
        final boolean compressed = (sdk & SDK_COMPRESSED) != 0;
        if ((sdk & ~SDK_KNOWN_MASK) != 0 || (sdk & SDK_ENCRYPTED) == 0) {
            clearFragments();
            return Result.failure(sdk, sequence(body), "unsupported SDK flags", false, compressed);
        }
        final byte[] protectedBytes = Arrays.copyOfRange(body, 1, body.length);
        final long sequence = sequence(body);
        final byte[] decrypted;
        try {
            decrypted = cipher.unprotect(protectedBytes);
        } catch (final IllegalArgumentException e) {
            final boolean authenticated = e.getMessage() != null && e.getMessage().contains("sequence");
            clearFragments();
            return Result.failure(sdk, sequence, e.getMessage(), authenticated, compressed);
        } catch (final RuntimeException e) {
            clearFragments();
            return Result.failure(sdk, sequence, e.getMessage(), false, compressed);
        }
        final byte[] frameworkClear;
        try {
            frameworkClear = compressed ? inflateZlib(decrypted, MAX_CLEAR_LENGTH) : decrypted;
        } catch (final IllegalArgumentException e) {
            Arrays.fill(decrypted, (byte) 0);
            clearFragments();
            return Result.failureAfterDecrypt(sdk, sequence, e.getMessage(), compressed, decrypted.length, false, -1);
        }
        if (fragment == 0) {
            if (fragmentActive) {
                clearFragments();
                if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
                Arrays.fill(decrypted, (byte) 0);
                return Result.failureAfterDecrypt(sdk, sequence, "non-fragment received while fragment reassembly is active",
                        compressed, decrypted.length, false, frameworkClear.length);
            }
            return finishMessage(sdk, sequence, compressed, decrypted, frameworkClear, 0, decrypted.length, frameworkClear.length);
        }
        if (fragment == SDK_FRAGMENT_FIRST_OR_INTERMEDIATE) {
            if (!fragmentActive) {
                fragmentBuffer.reset();
                fragmentOrdinal = 0;
                fragmentDecryptedLength = 0;
                fragmentClearLength = 0;
                fragmentActive = true;
            }
        } else if (fragment == SDK_FRAGMENT_LAST) {
            if (!fragmentActive) {
                if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
                Arrays.fill(decrypted, (byte) 0);
                return Result.failureAfterDecrypt(sdk, sequence, "last fragment received without a first fragment",
                        compressed, decrypted.length, false, frameworkClear.length);
            }
        } else {
            clearFragments();
            if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
            Arrays.fill(decrypted, (byte) 0);
            return Result.failureAfterDecrypt(sdk, sequence, "unsupported SDK fragment state " + fragment,
                    compressed, decrypted.length, false, frameworkClear.length);
        }
        if (fragmentBuffer.size() + frameworkClear.length > MAX_REASSEMBLED_LENGTH) {
            clearFragments();
            if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
            Arrays.fill(decrypted, (byte) 0);
            return Result.failureAfterDecrypt(sdk, sequence, "health fragment reassembly exceeds bound",
                    compressed, decrypted.length, false, frameworkClear.length);
        }
        fragmentBuffer.write(frameworkClear, 0, frameworkClear.length);
        fragmentOrdinal++;
        fragmentDecryptedLength += decrypted.length;
        fragmentClearLength += frameworkClear.length;
        if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
        Arrays.fill(decrypted, (byte) 0);
        if (fragment == SDK_FRAGMENT_FIRST_OR_INTERMEDIATE) {
            return Result.fragmentInProgress(sdk, sequence, compressed, fragmentOrdinal,
                    fragmentDecryptedLength, fragmentClearLength, fragmentBuffer.size());
        }
        final byte[] reassembled = fragmentBuffer.toByteArray();
        final int totalDecrypted = fragmentDecryptedLength;
        final int totalClear = fragmentClearLength;
        final int ordinal = fragmentOrdinal;
        clearFragments();
        return finishMessage(sdk, sequence, compressed, null, reassembled, ordinal, totalDecrypted, totalClear);
    }

    private Result finishMessage(final int sdk, final long sequence, final boolean compressed,
                                 final byte[] decrypted, final byte[] frameworkClear,
                                 final int ordinal, final int decryptedLength, final int clearLength) {
        final boolean startsWithComp = startsWithComp(frameworkClear);
        final byte[] application;
        try {
            application = startsWithComp ? decodeComp(frameworkClear, MAX_CLEAR_LENGTH) : frameworkClear;
        } catch (final IllegalArgumentException e) {
            if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
            return Result.failureAfterDecrypt(sdk, sequence, e.getMessage(), compressed, decryptedLength,
                    startsWithComp, clearLength);
        }
        final Result result = Result.success(sdk, sequence, compressed, decryptedLength, clearLength,
                startsWithComp, application, ordinal, clearLength, clearLength);
        if (frameworkClear != decrypted) Arrays.fill(frameworkClear, (byte) 0);
        if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
        if (application != frameworkClear) Arrays.fill(application, (byte) 0);
        return result;
    }

    private void clearFragments() {
        fragmentBuffer.reset();
        fragmentActive = false;
        fragmentOrdinal = 0;
        fragmentDecryptedLength = 0;
        fragmentClearLength = 0;
    }

    public void clear() {
        clearFragments();
        cipher.clear();
    }

    public SapPedometerSecureEncoder encoder() { return new SapPedometerSecureEncoder(cipher); }

    private static long sequence(final byte[] sdkBody) {
        if (sdkBody == null || sdkBody.length < 1 + SapHostManagerAppCipher.OVERHEAD) return -1;
        final int offset = sdkBody.length - SapHostManagerAppCipher.MAC_LENGTH - 4;
        long value = 0;
        for (int i = 0; i < 4; i++) value |= (long) (sdkBody[offset + i] & 0xff) << (8 * i);
        return value;
    }

    private static byte[] inflateZlib(final byte[] compressed, final int limit) {
        final Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, compressed.length * 4));
        final byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                final int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (output.size() + count > limit) throw new IllegalArgumentException("health zlib output exceeds bound");
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IllegalArgumentException("incomplete health zlib stream");
                } else {
                    throw new IllegalArgumentException("stalled health zlib stream");
                }
            }
            return output.toByteArray();
        } catch (final DataFormatException e) {
            throw new IllegalArgumentException("malformed health zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    private static boolean startsWithComp(final byte[] value) {
        return value.length >= 4 && value[0] == 'C' && value[1] == 'O' && value[2] == 'M' && value[3] == 'P';
    }

    private static byte[] decodeComp(final byte[] value, final int limit) {
        if (value.length < 8) throw new IllegalArgumentException("truncated health COMP header");
        final int headerLength = value[5] * 10 + value[6];
        if (headerLength < 8 || headerLength >= value.length) throw new IllegalArgumentException("invalid health COMP header length");
        final String coding = new String(value, 7, headerLength - 7, java.nio.charset.StandardCharsets.US_ASCII);
        final byte[] encoded = Arrays.copyOfRange(value, headerLength, value.length);
        if ("zlib".equals(coding)) return inflateZlib(encoded, limit);
        if (!"gzip".equals(coding)) throw new IllegalArgumentException("unsupported health COMP coding " + coding);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded));
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, encoded.length * 4))) {
            final byte[] buffer = new byte[1024];
            int count;
            while ((count = gzip.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IllegalArgumentException("health COMP gzip output exceeds bound");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (final IOException e) {
            throw new IllegalArgumentException("malformed health COMP gzip stream", e);
        }
    }

    public static final class Result {
        private final boolean valid, complete, hmacVerified, decryptSucceeded, compressed, startsWithComp;
        private final int sdk, decryptedLength, frameworkClearLength, fragmentOrdinal, accumulatedLength, reassembledLength, applicationLength;
        private final long sequence;
        private final byte[] applicationBytes;
        private final String error;

        private Result(final boolean valid, final boolean complete, final int sdk, final long sequence, final boolean hmacVerified,
                       final boolean decryptSucceeded, final boolean compressed, final int decryptedLength, final int frameworkClearLength,
                       final boolean startsWithComp, final byte[] applicationBytes, final String error,
                       final int fragmentOrdinal, final int accumulatedLength, final int reassembledLength, final int applicationLength) {
            this.valid = valid; this.complete = complete; this.sdk = sdk; this.sequence = sequence; this.hmacVerified = hmacVerified;
            this.decryptSucceeded = decryptSucceeded;
            this.compressed = compressed; this.decryptedLength = decryptedLength;
            this.frameworkClearLength = frameworkClearLength; this.startsWithComp = startsWithComp;
            this.applicationBytes = applicationBytes == null ? null : Arrays.copyOf(applicationBytes, applicationBytes.length);
            this.error = error;
            this.fragmentOrdinal = fragmentOrdinal; this.accumulatedLength = accumulatedLength;
            this.reassembledLength = reassembledLength; this.applicationLength = applicationLength;
        }
        private static Result success(final int sdk, final long sequence, final boolean compressed,
                                      final int decryptedLength, final int clearLength, final boolean comp,
                                      final byte[] application, final int ordinal, final int accumulatedLength,
                                      final int reassembledLength) {
            return new Result(true, true, sdk, sequence, true, true, compressed, decryptedLength, clearLength,
                    comp, application, null, ordinal, accumulatedLength, reassembledLength,
                    application == null ? -1 : application.length);
        }
        private static Result fragmentInProgress(final int sdk, final long sequence, final boolean compressed,
                                                 final int ordinal, final int decryptedLength, final int clearLength,
                                                 final int accumulatedLength) {
            return new Result(true, false, sdk, sequence, true, true, compressed, decryptedLength, clearLength,
                    false, null, null, ordinal, accumulatedLength, -1, -1);
        }
        private static Result failure(final int sdk, final long sequence, final String error,
                                      final boolean authenticated, final boolean compressed) {
            return new Result(false, false, sdk, sequence, authenticated, false, compressed, -1, -1,
                    false, null, error, -1, -1, -1, -1);
        }
        private static Result failureAfterDecrypt(final int sdk, final long sequence, final String error,
                                                  final boolean compressed, final int decryptedLength,
                                                  final boolean comp, final int clearLength) {
            return new Result(false, false, sdk, sequence, true, true, compressed, decryptedLength, clearLength,
                    comp, null, error, -1, -1, -1, -1);
        }
        public boolean isValid() { return valid; }
        public boolean isReassemblyComplete() { return complete; }
        public int getSdk() { return sdk; }
        public int getFragmentState() { return sdk < 0 ? -1 : sdk & SDK_FRAGMENT_MASK; }
        public long getSequence() { return sequence; }
        public boolean isHmacVerified() { return hmacVerified; }
        public boolean isDecryptSucceeded() { return decryptSucceeded; }
        public boolean isCompressed() { return compressed; }
        public int getDecryptedLength() { return decryptedLength; }
        public int getFrameworkClearLength() { return frameworkClearLength; }
        public int getFragmentOrdinal() { return fragmentOrdinal; }
        public int getAccumulatedLength() { return accumulatedLength; }
        public int getReassembledLength() { return reassembledLength; }
        public int getApplicationLength() { return applicationLength; }
        public boolean startsWithComp() { return startsWithComp; }
        public byte[] getApplicationBytes() { return applicationBytes == null ? null : Arrays.copyOf(applicationBytes, applicationBytes.length); }
        public String getError() { return error; }
    }
}
