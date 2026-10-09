package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WsmV2ClientTest {
    @Test
    public void acceptsVerifiedMessageShapesAndRejectsWrongOrder() throws Exception {
        assertThrows(IllegalStateException.class, () -> new WsmV2Client("watch", "phone").handleRequest(new byte[136]));
        final WsmV2Client client = new WsmV2Client("watch", "phone");
        final byte[] init = client.createInit();
        assertEquals(67, init.length);
        assertEquals(2, init[0]);
        assertEquals(6, init[1]);

        final KeyPair server = WsmV2Crypto.generateKeyPair();
        final byte[] sp = WsmV2Crypto.serializePublicPoint(server.getPublic());
        final byte[] request = new byte[136]; request[0] = 2; request[2] = (byte) 0x88; System.arraycopy(sp, 0, request, 3, 133);
        final byte[] response = client.handleRequest(request);
        assertEquals(200, response.length);
        final byte[] cp = Arrays.copyOfRange(response, 3, 136);
        assertArrayEquals(Arrays.copyOfRange(init, 3, 67), WsmV2Crypto.commitment(cp));
        final byte[] z = WsmV2Crypto.ecdhX(server, cp);
        assertArrayEquals(WsmV2Crypto.clientAuthenticator(z, "watch", "phone"), Arrays.copyOfRange(response, 136, 200));
        final byte[] confirm = new byte[67]; confirm[0] = 2; confirm[1] = 2; confirm[2] = 0x43;
        System.arraycopy(WsmV2Crypto.serverConfirmation(z, "watch", "phone"), 0, confirm, 3, 64);
        assertArrayEquals(WsmV2Crypto.prf(z, cp, sp, "watch", "phone"), client.handleConfirm(confirm, sp));
        assertEquals(WsmV2Client.State.AUTHENTICATED, client.getState());
    }

    @Test
    public void reproducesPythonReferenceClientResponseWithFixedP521Scalars() throws Exception {
        final String serverId = "SAMSUNG_ACCESSARY__02:00:00:00:00:01";
        final String clientId = "SAP_BC1D40FC7691D1B1B21EACBBA235D3D3";
        final byte[] clientPoint = hex("0400c6858e06b70404e9cd9e3ecb662395b4429c648139053fb521f828af606b4d3dbaa14b5e77efe75928fe1dc127a2ffa8de3348b3c1856a429bf97e7e31c2e5bd66011839296a789a3bc0045c8a5fb42c7d1bd998f54449579b446817afbd17273e662c97ee72995ef42640c550b9013fad0761353c7086a272c24088be94769fd16650");
        final byte[] serverPoint = hex("0400433c219024277e7e682fcb288148c282747403279b1ccc06352c6e5505d769be97b3b204da6ef55507aa104a3a35c5af41cf2fa364d60fd967f43e3933ba6d783d00f4bb8cc7f86db26700a7f3eceeeed3f0b5c6b5107c4da97740ab21a29906c42dbbb3e377de9f251f6b93937fa99a3248f4eafcbe95edc0f4f71be356d661f41b02");
        final byte[] expectedCommitment = hex("27b9676ac0e98cf4baba737f74f65b37d42f22661cc2aaefee5afc12005e8680ac3698e53dd1f8fcbd238e72dbdbf8ceaab33e5f7e3977e86bdd151e8525f2ad");
        final byte[] expectedSharedSecret = hex("00433c219024277e7e682fcb288148c282747403279b1ccc06352c6e5505d769be97b3b204da6ef55507aa104a3a35c5af41cf2fa364d60fd967f43e3933ba6d783d");
        final byte[] expectedAuthenticator = hex("b7833e215320c9d18bb3eeef30ea5f5c2a5fe18d8a195991b7b4b7c77c8daf41735b84a3a3a25f061e0397c5c5a79f41c4d8dd8276c350695514ad32f20fa34b");
        final byte[] expectedWireResponse = hex("00ca11000201c80400c6858e06b70404e9cd9e3ecb662395b4429c648139053fb521f828af606b4d3dbaa14b5e77efe75928fe1dc127a2ffa8de3348b3c1856a429bf97e7e31c2e5bd66011839296a789a3bc0045c8a5fb42c7d1bd998f54449579b446817afbd17273e662c97ee72995ef42640c550b9013fad0761353c7086a272c24088be94769fd16650b7833e215320c9d18bb3eeef30ea5f5c2a5fe18d8a195991b7b4b7c77c8daf41735b84a3a3a25f061e0397c5c5a79f41c4d8dd8276c350695514ad32f20fa34b");

        final WsmV2Client client = new WsmV2Client(serverId, clientId, fixedKeyPair(BigInteger.ONE, clientPoint));
        final byte[] init = client.createInit();
        assertArrayEquals(expectedCommitment, Arrays.copyOfRange(init, 3, 67));

        final byte[] request = new WsmV2AuthPacket(0, serverPoint).encode();
        final byte[] response = client.handleRequest(request);
        assertArrayEquals(clientPoint, Arrays.copyOfRange(response, 3, 136));
        assertArrayEquals(expectedSharedSecret, WsmV2Crypto.ecdhX(fixedKeyPair(BigInteger.ONE, clientPoint), serverPoint));
        assertArrayEquals(expectedAuthenticator, Arrays.copyOfRange(response, 136, 200));
        assertArrayEquals(expectedWireResponse, SapRecordCodec.encode(new SapRecord(SapAccessoryAuthenticationCodec.encode(0x11, response))));
    }

    private static KeyPair fixedKeyPair(final BigInteger privateScalar, final byte[] publicPoint) throws Exception {
        final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp521r1"));
        final ECParameterSpec specification = parameters.getParameterSpec(ECParameterSpec.class);
        final PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(privateScalar, specification));
        return new KeyPair(WsmV2Crypto.deserializePublicPoint(publicPoint), privateKey);
    }

    private static byte[] hex(final String value) {
        final byte[] output = new byte[value.length() / 2];
        for (int i = 0; i < output.length; i++) {
            output[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return output;
    }
}
