package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Clear-text WSM v2 fresh-authentication primitives. */
public final class WsmV2Crypto {
    public static final int COORDINATE_LENGTH = 66;
    public static final int PUBLIC_POINT_LENGTH = 133;
    public static final int AUTHENTICATOR_LENGTH = 64;
    private static final ECParameterSpec P521 = parameters();

    private WsmV2Crypto() { }

    public static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp521r1"));
        return generator.generateKeyPair();
    }

    public static byte[] serializePublicPoint(final PublicKey key) {
        if (!(key instanceof ECPublicKey)) throw new IllegalArgumentException("Expected EC public key");
        final ECPoint point = ((ECPublicKey) key).getW();
        final byte[] result = new byte[PUBLIC_POINT_LENGTH];
        result[0] = 0x04;
        System.arraycopy(toFixed(point.getAffineX(), COORDINATE_LENGTH), 0, result, 1, COORDINATE_LENGTH);
        System.arraycopy(toFixed(point.getAffineY(), COORDINATE_LENGTH), 0, result, 1 + COORDINATE_LENGTH, COORDINATE_LENGTH);
        return result;
    }

    public static PublicKey deserializePublicPoint(final byte[] encoded) throws Exception {
        if (encoded == null || encoded.length != PUBLIC_POINT_LENGTH || encoded[0] != 0x04) {
            throw new IllegalArgumentException("Invalid P-521 uncompressed point");
        }
        final BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 67));
        final BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 67, 133));
        validatePoint(x, y);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P521));
    }

    public static byte[] ecdhX(final KeyPair local, final byte[] peerPoint) throws Exception {
        final KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(local.getPrivate());
        agreement.doPhase(deserializePublicPoint(peerPoint), true);
        final byte[] sharedSecret = agreement.generateSecret();
        if (sharedSecret.length != COORDINATE_LENGTH) {
            throw new IllegalStateException("P-521 ECDH provider returned " + sharedSecret.length + " bytes, expected 66");
        }
        return sharedSecret;
    }

    public static byte[] commitment(final byte[] clientPoint) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(clientPoint);
    }

    public static byte[] clientAuthenticator(final byte[] z, final String serverId, final String clientId) throws Exception {
        final byte[] key = clientMacKey(z, serverId, clientId);
        try {
            return hmac("HmacSHA512", key, join(utf8(serverId), utf8(clientId)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] clientMacKey(final byte[] z, final String serverId, final String clientId) throws Exception {
        final byte[] digest = sha512(join(z, new byte[16], utf8(serverId), utf8(clientId)));
        try {
            // Use the first 32 digest bytes as the HMAC key.
            // For SHA-512 HMAC that is equivalent to using this 32-byte prefix directly.
            return Arrays.copyOf(digest, 32);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public static byte[] serverConfirmation(final byte[] z, final String serverId, final String clientId) throws Exception {
        final byte[] key = serverMacKey(z, serverId, clientId);
        try {
            return hmac("HmacSHA512", key, join(utf8(clientId), utf8(serverId)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] serverMacKey(final byte[] z, final String serverId, final String clientId) throws Exception {
        final byte[] digest = sha512(join(z, utf8(serverId), utf8(clientId)));
        try {
            // Server confirmation uses the first 32 digest bytes
            // as the HMAC key.
            return Arrays.copyOf(digest, 32);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public static byte[] prf(final byte[] z, final byte[] clientPoint, final byte[] serverPoint,
                             final String serverId, final String clientId) throws Exception {
        return hmac("HmacSHA256", Arrays.copyOf(z, 32),
                join(z, clientPoint, serverPoint, utf8(serverId), utf8(clientId)));
    }

    public static byte[] hmac(final String algorithm, final byte[] key, final byte[] data) throws Exception {
        final Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac.doFinal(data);
    }

    public static byte[] toFixed(final BigInteger value, final int length) {
        if (value.signum() < 0) throw new IllegalArgumentException("Negative unsigned integer");
        final byte[] source = value.toByteArray();
        final int start = source.length > 1 && source[0] == 0 ? 1 : 0;
        final int size = source.length - start;
        if (size > length) throw new IllegalArgumentException("Integer does not fit fixed width");
        final byte[] result = new byte[length];
        System.arraycopy(source, start, result, length - size, size);
        return result;
    }

    private static ECParameterSpec parameters() {
        try {
            final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp521r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (final Exception e) {
            throw new IllegalStateException("P-521 unavailable", e);
        }
    }

    private static void validatePoint(final BigInteger x, final BigInteger y) {
        final BigInteger p = ((ECFieldFp) P521.getCurve().getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            throw new IllegalArgumentException("P-521 point coordinate out of range");
        }
        final BigInteger left = y.multiply(y).mod(p);
        final BigInteger right = x.multiply(x).multiply(x).add(P521.getCurve().getA().multiply(x))
                .add(P521.getCurve().getB()).mod(p);
        if (!left.equals(right)) throw new IllegalArgumentException("Point is not on P-521");
    }

    private static byte[] sha512(final byte[] input) throws Exception { return MessageDigest.getInstance("SHA-512").digest(input); }
    private static byte[] utf8(final String input) { return input.getBytes(StandardCharsets.UTF_8); }
    private static byte[] join(final byte[]... values) {
        int length = 0;
        for (byte[] value : values) length += value.length;
        final byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, result, offset, value.length); offset += value.length; }
        return result;
    }
}
