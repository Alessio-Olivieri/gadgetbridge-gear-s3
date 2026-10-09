package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** SAP service-connection codec fixtures. */
public class SapServiceConnectionCodecTest {
    private static final byte[] BCM_REQUEST = hex("00241b000ffc01de39ecbb2f73797374656d2f62636d736572766963653b0001004300de04000200c56e");
    private static final byte[] WEBPROXY_REQUEST = hex("002adf810ffc014d6bde312f73797374656d2f77656270726f78793b00020377037801f501f60500000500000000a137");
    private static final byte[] WEBPROXY_SUCCESS = hex("001fc8410ffc02de314d6b2f73797374656d2f77656270726f78793b0000020377037864f1");
    private static final byte[] HOST_REQUEST = hex("0025dbc10ffc01a32316252f73797374656d2f686f73746d616e616765723b0001013c006704000200eca9");
    private static final byte[] HOST_SUCCESS = hex("0020d8010ffc021625a3232f73797374656d2f686f73746d616e616765723b000001013c349b");
    private static final byte[] PEDOMETER_TERMINATION = hex("032a3900392f73797374656d2f77617463685f7065646f6d657465723b");
    private static final byte[] PEDOMETER_TERMINATION_SUCCESS = hex("0400392a392f73797374656d2f77617463685f7065646f6d657465723b00");

    @Test
    public void decodesAndReencodesAllCapturedServiceControlRecords() {
        assertRequest(BCM_REQUEST, 1);
        assertRequest(WEBPROXY_REQUEST, 2);
        assertResponse(WEBPROXY_SUCCESS);
        assertRequest(HOST_REQUEST, 1);
        assertResponse(HOST_SUCCESS);
    }

    @Test
    public void twoChannelRequestUsesGroupedArrays() {
        final SapServiceConnectionRequest request = request(WEBPROXY_REQUEST);
        assertEquals(0x377, request.getSessions().get(0).getSessionId());
        assertEquals(0x378, request.getSessions().get(1).getSessionId());
        assertEquals(0x1f5, request.getSessions().get(0).getChannelId());
        assertEquals(0x1f6, request.getSessions().get(1).getChannelId());
        assertEquals(5, request.getSessions().get(0).getQos().getType());
        assertEquals(5, request.getSessions().get(1).getQos().getType());
        assertEquals(0, request.getSessions().get(0).getPayloadType());
        assertEquals(0, request.getSessions().get(1).getPayloadType());
    }

    @Test
    public void acceptsHostManagerWithDynamicPeerIdsAndRoutesDynamicSession() {
        final SapHostManagerRegistration local = new SapHostManagerRegistration(0x1234, 0x4567);
        final SapHostManagerAcceptor acceptor = new SapHostManagerAcceptor(local);
        acceptor.setRemoteDescriptor(new SapAgentDescriptor(0x9876, "Gear", Collections.singletonList(
                new SapServiceDescriptor(0x2345, SapHostManagerRegistration.PROFILE, 0x0100, 0x05, 0)) ),
                new SapServiceDescriptor(0x2345, SapHostManagerRegistration.PROFILE, 0x0100, 0x05, 0));
        final SapServiceConnectionRequest request = hostRequest(0x4567, 0x2345, 777, 103, 4, 0, 2, 0);
        final SapServiceConnectionResponse response = acceptor.handle(request);
        assertEquals(0, response.getStatus());
        assertEquals(0x2345, response.getAcceptorSlot());
        assertEquals(0x4567, response.getInitiatorSlot());
        assertEquals(777, response.getSessionIds().get(0).intValue());
        assertTrue(acceptor.isApplicationSession(777));
    }

    @Test
    public void rejectsWrongChannelQosRangeCollisionAndDuplicate() {
        final SapHostManagerAcceptor acceptor = configuredAcceptor();
        assertEquals(1, acceptor.handle(hostRequest(0x4568, 0x2345, 100, 103, 4, 0, 2, 0)).getStatus());
        assertEquals(1, acceptor.handle(hostRequest(0x4567, 0x2346, 100, 103, 4, 0, 2, 0)).getStatus());
        assertEquals(1, acceptor.handle(hostRequest(0x4567, 0x2345, 100, 104, 4, 0, 2, 0)).getStatus());
        assertEquals(1, acceptor.handle(hostRequest(0x4567, 0x2345, 100, 103, 5, 0, 2, 0)).getStatus());
        assertEquals(1, acceptor.handle(hostRequest(0x4567, 0x2345, 100, 103, 4, 0, 2, 1)).getStatus());
        assertEquals(1, acceptor.handle(hostRequest(0x4567, 0x2345, 993, 103, 4, 0, 2, 0)).getStatus());
        final SapHostManagerAcceptor collision = configuredAcceptor();
        collision.reserveTransportSession(99);
        assertEquals(3, collision.handle(hostRequest(0x4567, 0x2345, 99, 103, 4, 0, 2, 0)).getStatus());
        assertEquals(0, acceptor.handle(hostRequest(0x4567, 0x2345, 100, 103, 4, 0, 2, 0)).getStatus());
        assertEquals(1, acceptor.handle(hostRequest(0x4567, 0x2345, 101, 103, 4, 0, 2, 0)).getStatus());
    }

    @Test
    public void registrationNeverUsesSamsungCapturedIdsAndProvidesTruthfulDescriptor() {
        final SapHostManagerRegistration registration = new SapHostManagerRegistration(0x1234, 0x4567);
        assertFalse(registration.getAgentUuid() == 0x7413);
        assertFalse(registration.getComponentId() == 0xa323);
        final SapServiceDescriptor service = registration.toCapabilityDescriptor().getServices().get(0);
        assertEquals(SapHostManagerRegistration.PROFILE, service.getProfileId());
        assertEquals(0x0100, service.getAspVersion());
        assertEquals(0x04, service.getRoleFlags());
    }

    @Test
    public void hostManagerRegistrationBuildsTheSourceDerivedOneProfileInitialQuery() {
        final SapHostManagerRegistration registration = new SapHostManagerRegistration(0x1234, 0x4567);
        final SapCapabilityExchange exchange = new SapCapabilityExchange(1, 31, 0,
                Collections.singletonList(registration.toCapabilityMetadata()));
        assertArrayEquals(hex("001d09c00ff00103ffffffff012f73797374656d2f686f73746d616e616765723bf21f"),
                SapPostAuthRecordCodec.encode(exchange.createInitialQuery(0xffffffffL)));
    }

    @Test
    public void rejectsWrongTransportAndMalformedControlBodies() {
        assertThrows(IllegalArgumentException.class, () -> new SapTransportHeader(0, 0, 1020, 0).requireServiceControlDataFrame());
        assertThrows(IllegalArgumentException.class, () -> SapServiceConnectionCodec.parseRequest(new byte[]{1, 0, 1}));
        final byte[] trailing = SapServiceConnectionCodec.encode(request(HOST_REQUEST));
        assertThrows(IllegalArgumentException.class, () -> SapServiceConnectionCodec.parseRequest(Arrays.copyOf(trailing, trailing.length - 1)));
    }

    @Test
    public void malformedControlDiagnosticIdentifiesBoundaryAndDoesNotPoisonNextParse() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SapServiceConnectionCodec.parse(new byte[]{1, 0, 1}));
        assertEquals("Truncated SAP service-control body: offset=3 required=2 bodyBytes=3", error.getMessage());
        assertEquals("/system/hostmanager", SapServiceConnectionCodec.parseRequest(
                SapServiceConnectionCodec.encode(request(HOST_REQUEST))).getProfile());
    }

    @Test
    public void invalidCountDiagnosticReportsNumericFieldWithoutCopyingProfile() {
        final byte[] encoded = SapServiceConnectionCodec.encode(request(HOST_REQUEST));
        final int countOffset = 5 + "/system/hostmanager".length() + 1;
        encoded[countOffset] = 0; encoded[countOffset + 1] = 0;
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SapServiceConnectionCodec.parse(encoded));
        assertEquals("Invalid SAP service session count 0 at offset " + countOffset, error.getMessage());
        assertFalse(error.getMessage().contains("/system/hostmanager"));
    }

    @Test
    public void decodesAndReencodesGenericPedometerTermination() {
        final SapServiceConnectionTermination termination = SapServiceConnectionCodec.parseTermination(PEDOMETER_TERMINATION);
        assertEquals(0x2a39, termination.getAcceptorComponentId());
        assertEquals(0x0039, termination.getInitiatorComponentId());
        assertEquals("/system/watch_pedometer", termination.getProfile());
        assertArrayEquals(PEDOMETER_TERMINATION, SapServiceConnectionCodec.encode(termination));

        final SapServiceConnectionTerminationResponse response =
                SapServiceConnectionCodec.parseTerminationResponse(PEDOMETER_TERMINATION_SUCCESS);
        assertEquals(0x0039, response.getAcceptorComponentId());
        assertEquals(0x2a39, response.getInitiatorComponentId());
        assertEquals(SapServiceConnectionTerminationResponse.STATUS_SUCCESS, response.getStatus());
        assertArrayEquals(PEDOMETER_TERMINATION_SUCCESS, SapServiceConnectionCodec.encode(response));
        assertArrayEquals(PEDOMETER_TERMINATION_SUCCESS, SapServiceConnectionCodec.encode(
                SapServiceConnectionCodec.terminationResponse(termination, SapServiceConnectionTerminationResponse.STATUS_SUCCESS)));
    }

    private static SapHostManagerAcceptor configuredAcceptor() {
        final SapHostManagerAcceptor acceptor = new SapHostManagerAcceptor(new SapHostManagerRegistration(0x1234, 0x4567));
        final SapServiceDescriptor remote = new SapServiceDescriptor(0x2345, SapHostManagerRegistration.PROFILE, 0x0100, 0x05, 0);
        acceptor.setRemoteDescriptor(new SapAgentDescriptor(0x9876, "Gear", Collections.singletonList(remote)), remote);
        return acceptor;
    }
    private static SapServiceConnectionRequest hostRequest(final int acceptor, final int initiator, final int session, final int channel, final int qosType, final int rate, final int classType, final int payload) {
        return new SapServiceConnectionRequest(acceptor, initiator, SapHostManagerRegistration.PROFILE,
                Collections.singletonList(new SapServiceConnectionRequest.Session(session, channel, new SapQosDescriptor(qosType, rate, classType), payload)));
    }
    private static void assertRequest(final byte[] wire, final int count) {
        final SapServiceConnectionRequest request = request(wire);
        assertEquals(count, request.getSessions().size());
        assertArrayEquals(wire, encode(request));
    }
    private static void assertResponse(final byte[] wire) {
        final SapPostAuthRecord record = decode(wire);
        record.getHeader().requireServiceControlDataFrame();
        final SapServiceConnectionResponse response = SapServiceConnectionCodec.parseResponse(record.getBody());
        assertArrayEquals(wire, SapPostAuthRecordCodec.encode(new SapPostAuthRecord(record.getHeader(), SapServiceConnectionCodec.encode(response))));
    }
    private static SapServiceConnectionRequest request(final byte[] wire) {
        final SapPostAuthRecord record = decode(wire);
        record.getHeader().requireServiceControlDataFrame();
        return SapServiceConnectionCodec.parseRequest(record.getBody());
    }
    private static byte[] encode(final SapServiceConnectionRequest request) {
        return SapPostAuthRecordCodec.encode(new SapPostAuthRecord(new SapTransportHeader(0, 0, 1023, 0), SapServiceConnectionCodec.encode(request)));
    }
    private static SapPostAuthRecord decode(final byte[] wire) { return new SapPostAuthRecordCodec().accept(wire).get(0); }
    private static byte[] hex(final String string) { final byte[] bytes = new byte[string.length() / 2]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(string.substring(i * 2, i * 2 + 2), 16); return bytes; }
}
