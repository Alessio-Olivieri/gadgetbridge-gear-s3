package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WsmV2CryptoTest {
    @Test
    public void serializesAndParsesFixedWidthP521Point() throws Exception {
        final KeyPair pair = WsmV2Crypto.generateKeyPair();
        final byte[] point = WsmV2Crypto.serializePublicPoint(pair.getPublic());
        assertEquals(133, point.length);
        assertEquals(4, point[0]);
        assertArrayEquals(point, WsmV2Crypto.serializePublicPoint(WsmV2Crypto.deserializePublicPoint(point)));
        point[132] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> WsmV2Crypto.deserializePublicPoint(point));
    }

    @Test
    public void padsUnsignedCoordinatesWithoutSignByte() {
        final byte[] fixed = WsmV2Crypto.toFixed(new BigInteger("80", 16), 66);
        assertEquals(0, fixed[0]);
        assertEquals((byte) 0x80, fixed[65]);
    }

    @Test
    public void reproducesCapturedCommitmentAndAuthPacket() throws Exception {
        final byte[] publicPoint = hex("0401156b1948f0b962d5ac44534ebdf6b1e32e7804f5a66bcc57e87b0ebdebbc8771a3f6a0887387807ab2d6b705946e8b91f09f2f0aab9768b02725d96fc0fbb5ceff011759e51f30fe8e3fc85c9d4c48a5dd2961f94b1c2a0d9107e1000cdc10daf9d12deeecc4441540b9f805d2febcb2a5cd8e85b3b9277fb98030480bc4297546b387");
        final byte[] expected = hex("3362dd7541034b7d4bdce62b37b5accf9f882119ac18e38b37c8d21568b78d2c5fc7fe40aa4e306a67819e3a7723c9cd8dba9198a16782cbfe8ed8f7d3cdb786");
        assertArrayEquals(expected, WsmV2Crypto.commitment(publicPoint));
        final WsmV2AuthPacket packet = new WsmV2AuthPacket(6, expected);
        assertArrayEquals(packet.encode(), WsmV2AuthPacket.parse(packet.encode()).encode());
    }

    @Test
    public void clientAndServerAgreeOnEcdhAndDerivedValues() throws Exception {
        final KeyPair client = WsmV2Crypto.generateKeyPair();
        final KeyPair server = WsmV2Crypto.generateKeyPair();
        final byte[] cp = WsmV2Crypto.serializePublicPoint(client.getPublic());
        final byte[] sp = WsmV2Crypto.serializePublicPoint(server.getPublic());
        final byte[] cz = WsmV2Crypto.ecdhX(client, sp);
        final byte[] sz = WsmV2Crypto.ecdhX(server, cp);
        assertEquals(66, cz.length);
        assertArrayEquals(cz, sz);
        assertArrayEquals(WsmV2Crypto.clientAuthenticator(cz, "watch", "phone"), WsmV2Crypto.clientAuthenticator(sz, "watch", "phone"));
        assertArrayEquals(WsmV2Crypto.serverConfirmation(cz, "watch", "phone"), WsmV2Crypto.serverConfirmation(sz, "watch", "phone"));
        assertArrayEquals(WsmV2Crypto.prf(cz, cp, sp, "watch", "phone"), WsmV2Crypto.prf(sz, cp, sp, "watch", "phone"));
    }

    @Test
    public void checksSourceDerivedClientAuthenticatorWithSyntheticPeerId() throws Exception {
        final byte[] z = hex("015C231729FCC61CAD8108A7A0535CA07A9633F452FD03B3CCE74722E9ACBC13F556513E98B25387E3643D92912534697993B772C97AD2DC6FA5DD58229AAB74F514");
        final String serverId = "SAMSUNG_ACCESSARY__02:00:00:00:00:01";
        final String clientId = "SAP_B1E0D0B61448BDD2DE2486B6DB21D428";
        final byte[] expected = hex("F4EC8427D32D7FA3F99F5D282AA10505BE56EA5D3185D7DD05E0E9A9F8EE0B654FF46B3BB28AC07573F4A26EA278519CAC8A8A5FF939B47664F45B5415EE2308");
        assertArrayEquals(expected, WsmV2Crypto.clientAuthenticator(z, serverId, clientId));
    }

    @Test
    public void checksSourceDerivedServerConfirmationWithSyntheticPeerId() throws Exception {
        final byte[] z = hex("00433C219024277E7E682FCB288148C282747403279B1CCC06352C6E5505D769BE97B3B204DA6EF55507AA104A3A35C5AF41CF2FA364D60FD967F43E3933BA6D783D");
        final String serverId = "SAMSUNG_ACCESSARY__02:00:00:00:00:01";
        final String clientId = "SAP_BC1D40FC7691D1B1B21EACBBA235D3D3";
        final byte[] expected = hex("4E89CF0E829A001704A5BF08407B4261BE9F2C8BBE64598BC69EF787F1FE92460E2FDBE548E09915AAC607F24A9223EBE1F1FDC550D3B0D4A7F8AFF2F236E91C");
        assertArrayEquals(expected, WsmV2Crypto.serverConfirmation(z, serverId, clientId));
    }

    private static byte[] hex(final String hex) {
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
