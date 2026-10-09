package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONObject;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Bounded Host Manager startup framing, decoding, ordering, and lifecycle vectors. */
public class SapHostManagerApplicationTest {
    private static final String WATCH_INFO = "{\"msgId\":\"mgr_watch_info_res\",\"model_number\":\"SM-R760\",\"software_version\":\"R760XXU2FVG1\",\"battery_level\":57,\"is_initialed_watch\":true}";
    private static final String RECORD_925_JSON = "{\"msgId\":\"mgr_watch_info_req\",\"deviceid\":\"02:00:00:00:00:01\",\"btMac\":\"02:00:00:00:00:00\",\"hmVer\":\"2.2.03.23121541N\",\"dp_lastapp\":1,\"locale\":\"en_US\",\"fbe_mode\":\"disable\",\"watchResourceTransferDone\":false}";
    private static final String RECORD_928_JSON = "{\"msgId\":\"mgr_wearable_status_req\",\"deviceid\":\"02:00:00:00:00:01\",\"type\":\"connect\",\"timestamp\":\"1790074847338:<:::::::::;\"}";
    private static final String RECORD_941_JSON = "{\"msgId\":\"mgr_status_exchange_done\",\"result\":\"connect\",\"reason\":0}";
    private static final String RECORD_944_JSON = "{\"msgId\":\"mgr_wearable_ready_for_restore_req\",\"deviceid\":\"02:00:00:00:00:01\"}";
    private static final String RECORD_961_JSON = "{\"msgId\":\"mgr_wearable_ready_for_restore_res\"}";

    @Test
    public void clearFrameEncodesAndDecodesRecord925Shape() {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        final byte[] frame = SapHostManagerApplication.encodeClear(WATCH_INFO);
        assertArrayEquals(new byte[]{0, 0, 0}, new byte[]{frame[0], frame[1], frame[2]});
        application.createWatchInfoRequest("AA:BB:CC:DD:EE:FF", "02:00:00:00:00:00", "en_US");
        assertEquals("mgr_watch_info_res", application.decode(frame).getMessageId());
        assertEquals("mgr_watch_info_req", application.decode(SapHostManagerApplication.encodeClear(RECORD_925_JSON)).getMessageId());
    }

    @Test
    public void dynamicApi103SessionIsOutsideApplicationBody() {
        final byte[] body = SapHostManagerApplication.encodeClear(WATCH_INFO);
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0, 860, 0), body);
        assertEquals(860, new SapPostAuthRecordCodec().accept(SapPostAuthRecordCodec.encode(record)).get(0).getHeader().getSessionId());
    }

    @Test
    public void dataNetworkResponseUsesSamsungClearHostManagerShape() {
        final byte[] response = SapHostManagerApplication.createDataNetworkResponse("AA:BB:CC:DD:EE:FF", 2);
        assertArrayEquals(SapHostManagerApplication.encodeClear(
                "{\"msgId\":\"mgr_datanetwork_res\",\"deviceid\":\"AA:BB:CC:DD:EE:FF\",\"data\":2}"), response);
        assertThrows(IllegalArgumentException.class,
                () -> SapHostManagerApplication.createDataNetworkResponse("AA:BB:CC:DD:EE:FF", 4));
    }

    @Test
    public void transformedRecord927FormInflatesAndParsesWatchInfo() throws Exception {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        application.createWatchInfoRequest("AA:BB:CC:DD:EE:FF", "02:00:00:00:00:00", "en_US");
        final SapHostManagerApplication.DecodedMessage decoded = application.decode(transformed(WATCH_INFO));
        assertEquals("SM-R760", decoded.getWatchInfo().getFields().get("model_number"));
        assertEquals(57, decoded.getWatchInfo().getFields().get("battery_level"));
        assertEquals(SapHostManagerApplication.State.WATCH_INFO_COMPLETE, application.getState());
    }

    @Test
    public void rejectsMalformedFramesAndOversizedInflation() throws Exception {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        assertThrows(IllegalArgumentException.class, () -> application.decode(new byte[]{0, 1, 0, '{', '}'}));
        assertThrows(IllegalArgumentException.class, () -> application.decode(new byte[]{0, 0, 0, '{'}));
        assertThrows(IllegalArgumentException.class, () -> application.decode(new byte[]{0, 0, 0, (byte) 0xc3}));
        assertThrows(IllegalArgumentException.class, () -> application.decode(new byte[]{0x10, 1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> application.decode(new byte[]{0x14, 1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> application.decode(transformed(oversizedJson())));
    }

    @Test
    public void requestIsTruthfulBoundedAndSentOnceUntilDisconnect() {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        final byte[] request = application.createWatchInfoRequest("AA:BB:CC:DD:EE:FF", "02:00:00:00:00:00", "en_US");
        final String json = new String(request, 3, request.length - 3, StandardCharsets.UTF_8);
        assertTrue(json.contains("mgr_watch_info_req"));
        assertFalse(json.contains("hmVer"));
        assertFalse(json.contains("2.2.03.23121541N"));
        assertFalse(json.contains("dp_lastapp"));
        assertFalse(json.contains("fbe_mode"));
        assertTrue(json.contains("\"watchResourceTransferDone\":false"));
        assertFalse(json.contains("mgr_wearable_status_req"));
        assertNull(application.createWatchInfoRequest("AA:BB:CC:DD:EE:FF", "02:00:00:00:00:00", "en_US"));
        application.clear();
        assertTrue(application.createWatchInfoRequest("AA:BB:CC:DD:EE:FF", "02:00:00:00:00:00", "en_US") != null);
    }

    @Test
    public void optionalFieldsAndMessageIdAreHandledStrictly() {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        application.createWatchInfoRequest("AA:BB:CC:DD:EE:FF", "02:00:00:00:00:00", "en_US");
        assertTrue(application.decode(SapHostManagerApplication.encodeClear("{\"msgId\":\"mgr_watch_info_res\"}")).getWatchInfo().getFields().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> application.decode(SapHostManagerApplication.encodeClear("{}")));
        assertThrows(IllegalArgumentException.class, () -> application.decode(SapHostManagerApplication.encodeClear("[]")));
    }

    @Test
    public void record928RequestHasExactSourceDerivedTimestampAndIsSentOnce() {
        final SapHostManagerApplication application = watchInfoComplete();
        final byte[] request = application.createWearableStatusRequest("02:00:00:00:00:01", 1790074847338L);
        assertArrayEquals(SapHostManagerApplication.encodeClear(RECORD_928_JSON), request);
        assertEquals("1790074847338:<:::::::::;",
                SapHostManagerApplication.wearableStatusTimestamp(1790074847338L, "02:00:00:00:00:01"));
        application.decode(SapHostManagerApplication.encodeClear(WATCH_INFO));
        assertNull(application.createWearableStatusRequest("02:00:00:00:00:01", 1790074847339L));
    }

    @Test
    public void transformedWearableStatusResponseAllowsCapturedSizeAndAdvancesOnce() throws Exception {
        final SapHostManagerApplication application = watchInfoComplete();
        application.createWearableStatusRequest("02:00:00:00:00:01", 1790074847338L);
        final StringBuilder xml = new StringBuilder("<DeviceStatus>");
        while (xml.length() < 12000) xml.append("<deviceFeature/>");
        xml.append("</DeviceStatus>");
        final String json = "{\"msgId\":\"mgr_wearable_status_res\",\"type\":\"connect\",\"data\":\"" + xml + "\"}";
        final SapHostManagerApplication.DecodedMessage decoded = application.decode(transformed(json));
        assertEquals("connect", decoded.getWearableStatus().getType());
        assertEquals(xml.toString(), decoded.getWearableStatus().getData());
        assertEquals(SapHostManagerApplication.State.WEARABLE_STATUS_COMPLETE, application.getState());
        application.decode(transformed(json));
        assertEquals(SapHostManagerApplication.State.WEARABLE_STATUS_COMPLETE, application.getState());
    }

    @Test
    public void hostStatusRequestIsParsedOnlyAfterWearableStatusCompletion() throws Exception {
        final String hostRequest = "{\"msgId\":\"mgr_host_status_req\",\"type\":\"connect\"}";
        final SapHostManagerApplication early = watchInfoComplete();
        assertThrows(IllegalStateException.class,
                () -> early.decode(SapHostManagerApplication.encodeClear(hostRequest)));

        final SapHostManagerApplication application = wearableStatusComplete();
        final SapHostManagerApplication.DecodedMessage decoded =
                application.decode(SapHostManagerApplication.encodeClear(hostRequest));
        assertEquals("connect", decoded.getHostStatus().getType());
        assertEquals(SapHostManagerApplication.State.HOST_STATUS_REQUESTED, application.getState());
        assertThrows(IllegalStateException.class,
                () -> application.decode(SapHostManagerApplication.encodeClear(RECORD_941_JSON)));
        assertThrows(IllegalStateException.class,
                () -> application.decode(SapHostManagerApplication.encodeClear(RECORD_961_JSON)));
        assertEquals(SapHostManagerApplication.State.HOST_STATUS_REQUESTED, application.getState());
        assertFalse(application.getState() == SapHostManagerApplication.State.HOST_MANAGER_READY);
    }

    @Test
    public void capturedRestoreSequenceIsOrderedIdempotentAndReadyOnlyAtTheEnd() throws Exception {
        final SapHostManagerApplication application = hostStatusResponseSent();
        application.decode(SapHostManagerApplication.encodeClear(RECORD_941_JSON));
        assertEquals(SapHostManagerApplication.State.HOST_STATUS_COMPLETE, application.getState());
        final byte[] request = application.createReadyForRestoreRequest("02:00:00:00:00:01");
        assertArrayEquals(SapHostManagerApplication.encodeClear(RECORD_944_JSON), request);
        assertEquals(SapHostManagerApplication.State.READY_FOR_RESTORE_REQUEST_SENT, application.getState());
        application.decode(SapHostManagerApplication.encodeClear(RECORD_941_JSON));
        assertNull(application.createReadyForRestoreRequest("02:00:00:00:00:01"));
        assertEquals(SapHostManagerApplication.State.READY_FOR_RESTORE_REQUEST_SENT, application.getState());
        application.decode(SapHostManagerApplication.encodeClear(RECORD_961_JSON));
        assertEquals(SapHostManagerApplication.State.HOST_MANAGER_READY, application.getState());
        application.decode(SapHostManagerApplication.encodeClear(RECORD_961_JSON));
        assertEquals(SapHostManagerApplication.State.HOST_MANAGER_READY, application.getState());
    }

    @Test
    public void secureStatusDoneDoesNotSendReadyForRestoreRequest() throws Exception {
        final SapHostManagerApplication application = hostStatusResponseSent();
        final byte[] clear = SapHostManagerApplication.encodeClear(RECORD_941_JSON);
        final byte[] logical = Arrays.copyOfRange(clear, 1, clear.length);
        final SapHostManagerAppCipher peer = new SapHostManagerAppCipher(hex("8572bc810257ed522b4e5fe80135ff5f3a88d19c3c273dff51170a060617fa26"));
        final byte[] secure = SapHostManagerServiceChannelHeader.encodeSecureSingleMessage(false, peer.protect(logical));
        final SapHostManagerApplication.DecodedMessage decoded = application.decodeSecure(secure,
                new SapHostManagerAppCipher(hex("8572bc810257ed522b4e5fe80135ff5f3a88d19c3c273dff51170a060617fa26")));
        assertEquals(SapHostManagerApplication.STATUS_EXCHANGE_DONE, decoded.getMessageId());
        assertEquals(SapHostManagerApplication.State.HOST_STATUS_COMPLETE, application.getState());
        assertFalse(application.isReadyForRestoreRequestSent());
    }

    @Test
    public void hostStatusResponseIsBoundedEscapedSecureAndSentOnce() throws Exception {
        final SapHostManagerApplication application = wearableStatusComplete();
        application.decode(SapHostManagerApplication.encodeClear("{\"msgId\":\"mgr_host_status_req\",\"type\":\"connect\"}"));
        final SapHostManagerApplication.HostStatusValues values = new SapHostManagerApplication.HostStatusValues(
                "AA:BB:CC:DD:EE:FF", "connect", "02:00:00:00:00:00", "A&B", "14<test>",
                "Pixel", "build\"name", "34");
        final byte[] logical = application.createHostStatusResponse(values);
        final String json = new String(logical, 2, logical.length - 2, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"msgId\":\"mgr_host_status_res\""));
        assertTrue(json.contains("\"preinstalled\":\"false\""));
        assertTrue(json.contains("&amp;"));
        assertTrue(json.contains("&lt;test&gt;"));
        assertTrue(json.contains("<connectivity/><deviceFeature/><apps/>"));
        final String xml = new JSONObject(json).getString("data");
        assertEquals("DeviceStatus", DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement().getTagName());
        assertEquals(SapHostManagerApplication.State.HOST_STATUS_RESPONSE_SENT, application.getState());
        assertTrue(application.isHostStatusResponseSent());
        assertNull(application.createHostStatusResponse(values));
        final SapHostManagerApplication.SecureFrame frame = SapHostManagerApplication.protectHostStatus(
                new SapHostManagerAppCipher(hex("8572bc810257ed522b4e5fe80135ff5f3a88d19c3c273dff51170a060617fa26")), logical);
        assertEquals(0x14, frame.getBody()[0] & 0xff);
        assertTrue(frame.isCompressed());
    }

    @Test
    public void orderingAndDisconnectResetAllStartupState() throws Exception {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        assertThrows(IllegalStateException.class,
                () -> application.createWearableStatusRequest("02:00:00:00:00:01", 1L));
        final SapHostManagerApplication active = wearableStatusComplete();
        active.decode(SapHostManagerApplication.encodeClear("{\"msgId\":\"mgr_host_status_req\",\"type\":\"connect\"}"));
        active.clear();
        assertEquals(SapHostManagerApplication.State.HOST_MANAGER_CONNECTED, active.getState());
        assertFalse(active.isWatchInfoRequestSent());
        assertFalse(active.isWearableStatusRequestSent());
        assertFalse(active.isHostStatusResponseSent());
        assertFalse(active.isReadyForRestoreRequestSent());
        assertNull(active.getWatchInfoResponse());
        assertNull(active.getWearableStatusResponse());
        assertNull(active.getHostStatusRequest());
    }

    private static SapHostManagerApplication watchInfoComplete() {
        final SapHostManagerApplication application = new SapHostManagerApplication();
        application.createWatchInfoRequest("02:00:00:00:00:01", "02:00:00:00:00:00", "en_US");
        application.decode(SapHostManagerApplication.encodeClear(WATCH_INFO));
        return application;
    }

    private static SapHostManagerApplication wearableStatusComplete() throws Exception {
        final SapHostManagerApplication application = watchInfoComplete();
        application.createWearableStatusRequest("02:00:00:00:00:01", 1790074847338L);
        application.decode(transformed("{\"msgId\":\"mgr_wearable_status_res\",\"type\":\"connect\",\"data\":\"<DeviceStatus/>\"}"));
        return application;
    }

    private static SapHostManagerApplication hostStatusResponseSent() throws Exception {
        final SapHostManagerApplication application = wearableStatusComplete();
        application.decode(SapHostManagerApplication.encodeClear("{\"msgId\":\"mgr_host_status_req\",\"type\":\"connect\"}"));
        application.createHostStatusResponse(new SapHostManagerApplication.HostStatusValues(
                "02:00:00:00:00:01", "connect", "02:00:00:00:00:00", null, "14", "Pixel", "build", "34"));
        return application;
    }

    private static byte[] transformed(final String json) throws Exception {
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(new byte[]{0, 0});
            deflater.write(json.getBytes(StandardCharsets.UTF_8));
        }
        final byte[] result = new byte[compressed.size() + 1];
        result[0] = 0x10;
        System.arraycopy(compressed.toByteArray(), 0, result, 1, compressed.size());
        return result;
    }

    private static String oversizedJson() {
        final StringBuilder result = new StringBuilder("{\"msgId\":\"mgr_watch_info_res\",\"model_name\":\"");
        for (int i = 0; i < 66000; i++) result.append('x');
        return result.append("\"}").toString();
    }

    private static byte[] hex(final String value) {
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
