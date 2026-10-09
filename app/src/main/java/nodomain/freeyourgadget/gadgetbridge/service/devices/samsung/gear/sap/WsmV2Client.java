package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;

/** Client half of the WSM v2 0x20/0x10/0x11/0x12 exchange. */
public final class WsmV2Client {
    public enum State { NEW, WAIT_REQUEST, WAIT_CONFIRM, AUTHENTICATED, FAILED }
    private final String serverId;
    private final String clientId;
    private final KeyPair suppliedKeyPair;
    private KeyPair keyPair;
    private byte[] clientPoint;
    private byte[] initCommitment;
    private byte[] sharedSecret;
    private State state = State.NEW;

    public WsmV2Client(final String serverId, final String clientId) {
        this(serverId, clientId, null);
    }

    WsmV2Client(final String serverId, final String clientId, final KeyPair suppliedKeyPair) {
        this.serverId = serverId;
        this.clientId = clientId;
        this.suppliedKeyPair = suppliedKeyPair;
    }

    public State getState() { return state; }

    public byte[] createInit() throws Exception {
        require(State.NEW);
        keyPair = suppliedKeyPair == null ? WsmV2Crypto.generateKeyPair() : suppliedKeyPair;
        clientPoint = WsmV2Crypto.serializePublicPoint(keyPair.getPublic());
        initCommitment = WsmV2Crypto.commitment(clientPoint);
        state = State.WAIT_REQUEST;
        return new WsmV2AuthPacket(0x06, initCommitment).encode();
    }

    public byte[] handleRequest(final byte[] request) throws Exception {
        require(State.WAIT_REQUEST);
        if (!MessageDigest.isEqual(initCommitment, WsmV2Crypto.commitment(clientPoint))) fail();
        final WsmV2AuthPacket packet = WsmV2AuthPacket.parse(request);
        if (packet.getType() != 0 || packet.getPayload().length != 133) fail();
        final byte[] serverPoint = packet.getPayload();
        sharedSecret = WsmV2Crypto.ecdhX(keyPair, serverPoint);
        if (sharedSecret.length != WsmV2Crypto.COORDINATE_LENGTH) fail();
        final byte[] clientAuthenticator = WsmV2Crypto.clientAuthenticator(sharedSecret, serverId, clientId);
        state = State.WAIT_CONFIRM;
        return new WsmV2AuthPacket(1, join(clientPoint, clientAuthenticator)).encode();
    }

    public byte[] handleConfirm(final byte[] confirm, final byte[] serverPoint) throws Exception {
        require(State.WAIT_CONFIRM);
        final WsmV2AuthPacket packet = WsmV2AuthPacket.parse(confirm);
        if (packet.getType() != 2 || packet.getPayload().length != 64) fail();
        final byte[] expected = WsmV2Crypto.serverConfirmation(sharedSecret, serverId, clientId);
        if (!MessageDigest.isEqual(expected, packet.getPayload())) fail();
        final byte[] prf = WsmV2Crypto.prf(sharedSecret, clientPoint, serverPoint, serverId, clientId);
        Arrays.fill(sharedSecret, (byte) 0);
        sharedSecret = null;
        state = State.AUTHENTICATED;
        return prf;
    }

    private void require(final State expected) { if (state != expected) fail(); }
    private void fail() { state = State.FAILED; throw new IllegalStateException("Unexpected or invalid WSM v2 message"); }
    private static byte[] join(final byte[] first, final byte[] second) {
        final byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
