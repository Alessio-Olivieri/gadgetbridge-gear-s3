package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Protocol fixtures for the ASP 2.0 bcmservice exchange. */
public class SapBcmFeatureExchangeTest {
    private static final byte[] RECORD_914_BODY = hex("00040201010402050104010501040206010406020104020701");
    private static final byte[] RECORD_915_BODY = hex("0004020201040205010402060104020101040103010402230404060201");
    private static final byte[] RECORD_917_BODY = hex("001502020230323a30303a30303a30303a30303a3030050205020705020102060a010302476f6f676c651506020230323a30303a30303a30303a38303a3030");
    private static final byte[] RECORD_918_BODY = hex("000502010204050205020f16010502626c7565746f6f7468206e6663207769666905020602001506020262323a36663a65303a32643a62623a61340b0207022d36202d362030");

    @Test
    public void sdkHeaderEncodesAndRejectsOtherChannelForms() {
        assertArrayEquals(new byte[]{0}, SapBcmServiceChannelHeader.encodeNormalUnencryptedSingleFragment());
        assertEquals(1, SapBcmServiceChannelHeader.decodeNormalUnencryptedSingleFragment(new byte[]{0, 4}));
        assertThrows(IllegalArgumentException.class, () -> SapBcmServiceChannelHeader.decodeNormalUnencryptedSingleFragment(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> SapBcmServiceChannelHeader.decodeNormalUnencryptedSingleFragment(new byte[]{4}));
    }

    @Test
    public void featureEntryParsingIsStrictAndConsumesTheCompletePayload() {
        assertEquals(6, SapBcmFeatureExchange.parse(Arrays.copyOfRange(RECORD_914_BODY, 1, RECORD_914_BODY.length)).size());
        assertThrows(IllegalArgumentException.class, () -> SapBcmFeatureExchange.parse(new byte[]{3, 2, 1}));
        assertThrows(IllegalArgumentException.class, () -> SapBcmFeatureExchange.parse(new byte[]{5, 2, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> SapBcmFeatureExchange.parse(new byte[]{4, 2, 1, 1, 1}));
    }

    @Test
    public void record914RequestSetEncodesExactlyThroughTheModelAndOnlyOnce() {
        final SapBcmFeatureExchange exchange = new SapBcmFeatureExchange();
        assertArrayEquals(RECORD_914_BODY, exchange.createLocalRequestFrame());
        assertNull(exchange.createLocalRequestFrame());
    }

    @Test
    public void record915LiveM2RequestsDecodeAndProduceOnlyTruthfulReplies() {
        final SapBcmFeatureExchange.Result result = new SapBcmFeatureExchange().handleFrame(RECORD_915_BODY, values("02:00:00:00:00:00", new byte[]{6}, "Google", null, null));
        assertEquals(7, result.getReceived().size());
        assertArrayEquals(hex("001502020230323a30303a30303a30303a30303a303005020102060a010302476f6f676c65"), result.getReplyFrame());
        assertTrue(result.isPeerRequestHandlingComplete());
    }

    @Test
    public void records917And918DecodeAsFeatureResponses() {
        final SapBcmFeatureExchange exchange = new SapBcmFeatureExchange();
        exchange.createLocalRequestFrame();
        assertEquals(5, exchange.handleFrame(RECORD_917_BODY, values(null, null, null, null, null)).getReceived().size());
        final SapBcmFeatureExchange.Result result = exchange.handleFrame(RECORD_918_BODY, values(null, null, null, null, null));
        assertEquals(6, result.getReceived().size());
        assertTrue(result.isPeerResponsesComplete());
    }

    @Test
    public void featureFrameUsesTheAcceptedDynamicSessionAndSupportsImmediateApplicationTraffic() {
        final SapBcmFeatureExchange exchange = new SapBcmFeatureExchange();
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0, 564, 0), exchange.createLocalRequestFrame());
        assertEquals(564, new SapPostAuthRecordCodec().accept(SapPostAuthRecordCodec.encode(record)).get(0).getHeader().getSessionId());
        assertEquals(7, exchange.handleFrame(RECORD_915_BODY, values(null, new byte[]{6}, "Google", null, null)).getReceived().size());
    }

    @Test
    public void manufacturerResponseIsPlatformDerivedAndHfpNotificationHasNoAction() {
        final SapBcmFeatureExchange exchange = new SapBcmFeatureExchange();
        final SapBcmFeatureExchange.Result manufacturer = exchange.handleFrame(hex("0004010301"), values(null, null, "Google", null, null));
        assertArrayEquals(hex("000a010302476f6f676c65"), manufacturer.getReplyFrame());
        final SapBcmFeatureExchange.Result hfp = exchange.handleFrame(hex("0004022304"), values(null, null, "Google", null, null));
        assertNull(hfp.getReplyFrame());
        assertTrue(hfp.getActions().get(0).getReason().contains("no HFP action"));
    }

    @Test
    public void unavailableAndUnsupportedRequestsAreOmittedWithoutSamsungFeatureBits() {
        final SapBcmFeatureExchange exchange = new SapBcmFeatureExchange();
        final SapBcmFeatureExchange.Result unavailable = exchange.handleFrame(hex("000402060104060201"), values(null, null, null, null, null));
        assertNull(unavailable.getReplyFrame());
        final SapBcmFeatureExchange.Result services = exchange.handleFrame(hex("0004020501"), values(null, null, null, null, null));
        assertNull(services.getReplyFrame());
        assertTrue(services.getActions().get(0).getReason().contains("no Gadgetbridge capability bits"));
        assertNull(exchange.handleFrame(hex("0004090901"), values(null, null, null, null, null)).getReplyFrame());
    }

    @Test
    public void disconnectClearResetsFeatureExchangeState() {
        final SapBcmFeatureExchange exchange = new SapBcmFeatureExchange();
        exchange.createLocalRequestFrame();
        exchange.handleFrame(RECORD_918_BODY, values(null, null, null, null, null));
        exchange.clear();
        assertArrayEquals(RECORD_914_BODY, exchange.createLocalRequestFrame());
        assertFalse(exchange.handleFrame(hex("0004022304"), values(null, null, null, null, null)).isPeerResponsesComplete());
    }

    private static SapBcmFeatureExchange.Values values(final String address, final byte[] version, final String manufacturer, final String le, final String p2p) {
        return new SapBcmFeatureExchange.Values() {
            @Override public byte[] bluetoothAddress() { return SapBcmFeatureExchange.utf8(address); }
            @Override public byte[] bluetoothVersion() { return version; }
            @Override public byte[] manufacturer() { return SapBcmFeatureExchange.utf8(manufacturer); }
            @Override public byte[] leAddress() { return SapBcmFeatureExchange.utf8(le); }
            @Override public byte[] wifiP2pAddress() { return SapBcmFeatureExchange.utf8(p2p); }
        };
    }
    private static byte[] hex(final String string) { final byte[] bytes = new byte[string.length() / 2]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(string.substring(i * 2, i * 2 + 2), 16); return bytes; }
}
