package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** M2 fixtures and bounded connection-state validation for API channel 222. */
public class SapBcmServiceConnectorTest {
    private static final byte[] CAPTURED_REQUEST = hex("00241b000ffc01de39ecbb2f73797374656d2f62636d736572766963653b0001004300de04000200c56e");
    private static final byte[] CAPTURED_RESPONSE = hex("001fc8410ffc02de39ecbb2f73797374656d2f62636d736572766963653b0000010043f383");

    @Test
    public void decodesAndReencodesCapturedBcmserviceControlRecords() {
        final SapPostAuthRecord requestRecord = decode(CAPTURED_REQUEST);
        requestRecord.getHeader().requireServiceControlDataFrame();
        final SapServiceConnectionRequest request = SapServiceConnectionCodec.parseRequest(requestRecord.getBody());
        assertEquals(SapBcmServiceRegistration.PROFILE, request.getProfile());
        assertEquals(0x43, request.getSessions().get(0).getSessionId());
        assertEquals(SapBcmServiceRegistration.API_CHANNEL, request.getSessions().get(0).getChannelId());
        assertEquals(SapBcmServiceRegistration.QOS_TYPE, request.getSessions().get(0).getQos().getType());
        assertEquals(SapBcmServiceRegistration.QOS_DATA_RATE, request.getSessions().get(0).getQos().getDataRate());
        assertEquals(SapBcmServiceRegistration.QOS_CLASS, request.getSessions().get(0).getQos().getClassType());
        assertEquals(SapBcmServiceRegistration.PAYLOAD_TYPE, request.getSessions().get(0).getPayloadType());
        assertArrayEquals(CAPTURED_REQUEST, encode(request));

        final SapPostAuthRecord responseRecord = decode(CAPTURED_RESPONSE);
        responseRecord.getHeader().requireServiceControlDataFrame();
        final SapServiceConnectionResponse response = SapServiceConnectionCodec.parseResponse(responseRecord.getBody());
        assertEquals(0xde39, response.getAcceptorSlot());
        assertEquals(0xecbb, response.getInitiatorSlot());
        assertEquals(0x43, response.getSessionIds().get(0).intValue());
        assertArrayEquals(CAPTURED_RESPONSE, SapPostAuthRecordCodec.encode(new SapPostAuthRecord(
                responseRecord.getHeader(), SapServiceConnectionCodec.encode(response))));
    }

    @Test
    public void allocatesDynamicSessionAndUsesDynamicComponents() {
        final SapBcmServiceConnector connector = configuredConnector(314);
        final SapServiceConnectionRequest request = connector.createRequest();
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);

        assertEquals(0x5678, request.getAcceptorComponentId());
        assertEquals(0x4567, request.getInitiatorComponentId());
        assertEquals(SapBcmServiceRegistration.PROFILE, request.getProfile());
        assertTrue(session.getSessionId() >= 1 && session.getSessionId() <= 992);
        assertEquals(314, session.getSessionId());
        assertEquals(222, session.getChannelId());
        assertEquals(4, session.getQos().getType());
        assertEquals(0, session.getQos().getDataRate());
        assertEquals(2, session.getQos().getClassType());
        assertEquals(0, session.getPayloadType());
        assertEquals(SapBcmServiceConnector.State.PENDING, connector.getState());
        assertTrue(connector.isReservedSession(314));
    }

    @Test
    public void acceptsGearResponseWithUnswappedRequestComponentSlotsAndMapsApi222() {
        final SapBcmServiceConnector connector = configuredConnector(314);
        connector.createRequest();
        connector.handleResponse(response(0x5678, 0x4567, SapBcmServiceRegistration.PROFILE, 0, 314));

        assertEquals(SapBcmServiceConnector.State.ESTABLISHED, connector.getState());
        assertEquals(314, connector.getApplicationSessionId());
        assertTrue(connector.isApplicationSession(314));
        assertFalse(connector.isApplicationSession(315));
    }

    @Test
    public void rejectsWrongComponentProfileStatusAndSessionAndReleasesPendingSession() {
        assertInvalid(response(0x5679, 0x4567, SapBcmServiceRegistration.PROFILE, 0, 314));
        assertInvalid(response(0x5678, 0x4567, "/system/not-bcmservice", 0, 314));
        assertInvalid(response(0x5678, 0x4567, SapBcmServiceRegistration.PROFILE, 1, 314));
        assertInvalid(response(0x5678, 0x4567, SapBcmServiceRegistration.PROFILE, 0, 315));
    }

    @Test
    public void preventsDuplicateInitiationAndReleasesConnectionLocalStateOnDisconnect() {
        final SapBcmServiceConnector connector = configuredConnector(314);
        connector.createRequest();
        assertThrows(IllegalStateException.class, connector::createRequest);
        connector.clear();
        assertEquals(SapBcmServiceConnector.State.IDLE, connector.getState());
        assertFalse(connector.isReservedSession(314));
        assertEquals(314, connector.createRequest().getSessions().get(0).getSessionId());
    }

    @Test
    public void m2ProducesOnlyAServiceControlRequestAndNoApplicationPdu() {
        final SapServiceConnectionRequest request = configuredConnector(314).createRequest();
        final SapPostAuthRecord record = new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                SapServiceConnectionCodec.encode(request));
        final SapPostAuthRecord decoded = decode(SapPostAuthRecordCodec.encode(record));
        decoded.getHeader().requireServiceControlDataFrame();
        assertEquals(SapServiceConnectionRequest.TYPE, decoded.getBody()[0] & 0xff);
    }

    private static void assertInvalid(final SapServiceConnectionResponse response) {
        final SapBcmServiceConnector connector = configuredConnector(314);
        connector.createRequest();
        assertThrows(RuntimeException.class, () -> connector.handleResponse(response));
        assertEquals(SapBcmServiceConnector.State.FAILED, connector.getState());
        assertFalse(connector.isReservedSession(314));
    }

    private static SapBcmServiceConnector configuredConnector(final int session) {
        final SapBcmServiceRegistration registration = new SapBcmServiceRegistration(0x1234, 0x4567);
        final SapServiceDescriptor remote = new SapServiceDescriptor(0x5678, SapBcmServiceRegistration.PROFILE, 0x0200, 0x04, 0);
        registration.setRemoteProvider(new SapAgentDescriptor(0x2345, "Gear", Collections.singletonList(remote)), remote);
        return new SapBcmServiceConnector(registration, new FixedSecureRandom(session - 1));
    }

    private static SapServiceConnectionResponse response(final int acceptor, final int initiator, final String profile,
                                                         final int status, final int session) {
        return new SapServiceConnectionResponse(acceptor, initiator, profile, status, Collections.singletonList(session));
    }

    private static SapPostAuthRecord decode(final byte[] wire) {
        return new SapPostAuthRecordCodec().accept(wire).get(0);
    }

    private static byte[] encode(final SapServiceConnectionRequest request) {
        return SapPostAuthRecordCodec.encode(new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                SapServiceConnectionCodec.encode(request)));
    }

    private static byte[] hex(final String string) {
        final byte[] bytes = new byte[string.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(string.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final int value;
        private FixedSecureRandom(final int value) { this.value = value; }
        @Override public int nextInt(final int bound) { return value % bound; }
    }
}
