package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class SapMusicCodecTest {
    private static GBDeviceEventMusicControl.Event decode(final String action) {
        return SapMusicCodec.decodeControl(("{\"msgId\":\"music-remotecontrol-req\",\"action\":\"" + action + "\",\"status\":\"pressed\"}").getBytes(StandardCharsets.UTF_8));
    }
    @Test public void decodesPlay() { assertEquals(GBDeviceEventMusicControl.Event.PLAY, decode("play")); }
    @Test public void decodesPause() { assertEquals(GBDeviceEventMusicControl.Event.PAUSE, decode("pause")); }
    @Test public void decodesNext() { assertEquals(GBDeviceEventMusicControl.Event.NEXT, decode("forward")); }
    @Test public void decodesPrevious() { assertEquals(GBDeviceEventMusicControl.Event.PREVIOUS, decode("backward")); }
    @Test public void encodesPlaybackState() throws Exception {
        final MusicStateSpec state = new MusicStateSpec((byte) MusicStateSpec.STATE_PLAYING, 12, 100, (byte) -1, (byte) -1);
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodePlaybackState(state), StandardCharsets.UTF_8));
        assertEquals("music-playstatechange-rsp", json.getString("msgId")); assertEquals(3, json.getInt("playstate")); assertEquals(12000L, json.getLong("position"));
    }
    @Test public void encodesTitleAndArtist() throws Exception {
        final MusicSpec spec = new MusicSpec("Artist", null, "Title", -1, -1, -1);
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodeMetadata(spec), StandardCharsets.UTF_8));
        assertEquals("music-mediachanged-ind", json.getString("msgId")); assertEquals("Title", json.getString("title")); assertEquals("Artist", json.getString("artist"));
    }
    @Test public void ignoresMalformedPacketsSafely() {
        assertNull(SapMusicCodec.decodeControl(new byte[]{'{'}));
        assertNull(SapMusicCodec.decodeControl("{\"msgId\":\"music-remotecontrol-req\",\"action\":\"play\",\"status\":\"released\"}".getBytes(StandardCharsets.UTF_8)));
        assertNull(SapMusicCodec.decodeControl("{\"msgId\":\"other\"}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void decodesPhysicalApi100JsonFrameWithoutNotificationServiceEnvelope() {
        final byte[] raw = new byte[] {0, '{', '"', 'm', 's', 'g', 'I', 'd', '"', ':', '"', 'm', 'u', 's', 'i', 'c', '-', 'g', 'e', 't', 'a', 't', 't', 'r', 'i', 'b', 'u', 't', 'e', '-', 'r', 'e', 'q', '"', '}'};
        assertEquals(SapMusicCodec.RequestType.GET_ATTRIBUTE, SapMusicCodec.decodeRequest(raw).getType());
        assertArrayEquals(raw, SapMusicCodec.encodeFrame(new String(raw, 1, raw.length - 1, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void parsesMediaChangedAndGetAttributeRequests() {
        assertEquals(SapMusicCodec.RequestType.MEDIA_CHANGED, SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-mediachanged-req\",\"value\":\"true\",\"timeout\":31000}")).getType());
        assertEquals(SapMusicCodec.RequestType.GET_ATTRIBUTE, SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-getattribute-req\"}")).getType());
    }
    @Test public void playpauseReleaseDoesNotToggleAndPressedDoes() {
        assertNull(SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-remotecontrol-req\",\"action\":\"playpause\",\"status\":\"released\"}")).getControlEvent());
        assertEquals(GBDeviceEventMusicControl.Event.PLAYPAUSE, SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-remotecontrol-req\",\"action\":\"playpause\",\"status\":\"pressed\"}")).getControlEvent());
    }
    @Test public void getAttributeUsesSamsungResponseGrammar() throws Exception {
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodeGetAttributeResponse(null, 7), StandardCharsets.UTF_8));
        assertEquals("music-getattribute-rsp", json.getString("msgId")); assertEquals("repeatoff", json.getString("repeat")); assertEquals("off", json.getString("shuffle"));
    }
    @Test public void malformedAndUnknownFramesAreSafe() {
        assertEquals(SapMusicCodec.RequestType.MALFORMED, SapMusicCodec.decodeRequest(new byte[] {0, '{'}).getType());
        assertEquals(SapMusicCodec.RequestType.UNKNOWN, SapMusicCodec.decodeRequest(frame("{\"msgId\":\"other\"}")).getType());
    }
    @Test public void cachesMusicPublishedBeforeTheSapSession() throws Exception {
        final SapMusicStateCache cache = new SapMusicStateCache();
        cache.setMusicSpec(new MusicSpec("Artist", null, "Title", -1, -1, -1));
        cache.setMusicStateSpec(new MusicStateSpec((byte) MusicStateSpec.STATE_PLAYING, 0, 100, (byte) -1, (byte) -1));
        cache.setMediaChangedSubscribed(true);
        assertEquals("Title", new JSONObject(new String(cache.getMusicMetadata(), StandardCharsets.UTF_8)).getString("title"));
        assertEquals(MusicStateSpec.STATE_PLAYING, cache.getMusicStateSpec().getState());
        assertEquals(true, cache.isMediaChangedSubscribed());
    }
    @Test public void parsesVolumeUpAndDownRequests() {
        assertEquals(GBDeviceEventMusicControl.Event.VOLUMEUP, SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-setattribute-req\",\"volume\":\"up\"}")).getVolumeEvent());
        assertEquals(GBDeviceEventMusicControl.Event.VOLUMEDOWN, SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-setattribute-req\",\"volume\":\"down\"}")).getVolumeEvent());
    }
    @Test public void encodesCurrentVolumeAndVolumeChange() throws Exception {
        JSONObject json = new JSONObject(new String(SapMusicCodec.encodeGetAttributeResponse(null, 9), StandardCharsets.UTF_8));
        assertEquals(9, json.getInt("volume"));
        json = new JSONObject(new String(SapMusicCodec.encodeVolumeChanged(11), StandardCharsets.UTF_8));
        assertEquals("music-volumechange-rsp", json.getString("msgId")); assertEquals(11, json.getInt("volume"));
    }
    @Test public void advertisesRawAndroidVolumeMaximum() throws Exception {
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodeCapabilityFeature(25), StandardCharsets.UTF_8));
        assertEquals("capability-feature-rsp", json.getString("msgId")); assertEquals(25, json.getInt("volumemax-state"));
    }
    @Test public void encodesSourceArtworkAndDuration() throws Exception {
        final MusicSpec spec = new MusicSpec("Artist", "Album", "Title", 123, -1, -1);
        spec.setAlbumArt(new byte[] {'a', 'b', 'c'});
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodeMetadata(spec), StandardCharsets.UTF_8));
        assertEquals("YWJj", json.getString("image")); assertEquals(123000L, json.getLong("duration"));
    }
    @Test public void noArtworkUsesSourceEmptyImage() throws Exception {
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodeMetadata(new MusicSpec(), ""), StandardCharsets.UTF_8));
        assertEquals("", json.getString("image")); assertEquals(JSONObject.NULL, json.get("albumArt"));
    }
    @Test public void oversizedArtworkIsOmittedSafely() throws Exception {
        final MusicSpec spec = new MusicSpec();
        spec.setAlbumArt(new byte[32 * 1024 + 1]);
        final JSONObject json = new JSONObject(new String(SapMusicCodec.encodeMetadata(spec), StandardCharsets.UTF_8));
        assertEquals("", json.getString("image"));
    }
    @Test public void malformedSetAttributeIsIgnored() {
        assertNull(SapMusicCodec.decodeRequest(frame("{\"msgId\":\"music-setattribute-req\",\"volume\":\"9\"}")).getVolumeEvent());
    }
    private static byte[] frame(final String json) { return SapMusicCodec.encodeFrame(json.getBytes(StandardCharsets.UTF_8)); }
}
