package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

/** Samsung Accessory SDK framing plus AppCipher for health TX. */
public final class SapPedometerSecureEncoder {
    private static final int AUTO_COMPRESSION_THRESHOLD = 512; // fragment length including SDK byte
    private final SapHostManagerAppCipher cipher;

    SapPedometerSecureEncoder(final SapHostManagerAppCipher cipher) {
        if (cipher == null) throw new IllegalArgumentException("Missing health AppCipher");
        this.cipher = cipher;
    }

    public EncodedFrame encode(final byte[] applicationBytes) {
        if (applicationBytes == null || applicationBytes.length == 0) {
            throw new IllegalArgumentException("Missing health application payload");
        }
        byte[] protectedInput = Arrays.copyOf(applicationBytes, applicationBytes.length);
        boolean compressed = false;
        if (applicationBytes.length + 1 > AUTO_COMPRESSION_THRESHOLD) {
            final byte[] candidate = zlib(applicationBytes);
            if (candidate.length <= applicationBytes.length) {
                Arrays.fill(protectedInput, (byte) 0);
                protectedInput = candidate;
                compressed = true;
            } else {
                Arrays.fill(candidate, (byte) 0);
            }
        }
        try {
            final byte sdk = (byte) (SapPedometerSecureDecoder.SDK_ENCRYPTED
                    | (compressed ? SapPedometerSecureDecoder.SDK_COMPRESSED : 0));
            final byte[] protectedBytes = cipher.protect(protectedInput);
            final byte[] body = new byte[protectedBytes.length + 1];
            body[0] = sdk;
            System.arraycopy(protectedBytes, 0, body, 1, protectedBytes.length);
            final long sequence = cipher.getTxSequence() - 1;
            Arrays.fill(protectedBytes, (byte) 0);
            return new EncodedFrame(body, sdk & 0xff, compressed, sequence, body.length - 1,
                    applicationBytes.length);
        } finally {
            Arrays.fill(protectedInput, (byte) 0);
        }
    }

    private static byte[] zlib(final byte[] input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(input);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("Unable to zlib-compress health payload", e);
        }
        return output.toByteArray();
    }

    public static final class EncodedFrame {
        private final byte[] body;
        private final int sdk;
        private final boolean compressed;
        private final long sequence;
        private final int protectedLength;
        private final int clearLength;

        private EncodedFrame(final byte[] body, final int sdk, final boolean compressed, final long sequence,
                             final int protectedLength, final int clearLength) {
            this.body = body;
            this.sdk = sdk;
            this.compressed = compressed;
            this.sequence = sequence;
            this.protectedLength = protectedLength;
            this.clearLength = clearLength;
        }

        public byte[] getBody() { return Arrays.copyOf(body, body.length); }
        public int getSdk() { return sdk; }
        public boolean isCompressed() { return compressed; }
        public long getSequence() { return sequence; }
        public int getProtectedLength() { return protectedLength; }
        public int getClearLength() { return clearLength; }
    }
}
