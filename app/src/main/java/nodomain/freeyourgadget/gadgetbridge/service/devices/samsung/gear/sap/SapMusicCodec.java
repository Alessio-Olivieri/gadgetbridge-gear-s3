package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;

/** JSON grammar carried after the clear SDK header in /system/music API100 frames. */
public final class SapMusicCodec {
    private static final int MAX_JSON_BYTES = 48 * 1024;
    private static final int MAX_STRING_CODE_UNITS = 256;
    private static final int MAX_ARTWORK_BYTES = 32 * 1024;
    private SapMusicCodec() { }
    public enum RequestType { MEDIA_CHANGED, GET_ATTRIBUTE, SET_ATTRIBUTE, CAPABILITY_FEATURE, REMOTE_CONTROL, UNKNOWN, MALFORMED }

    public static final class Request {
        private final RequestType type;
        private final JSONObject json;

        private Request(final RequestType type, final JSONObject json) { this.type = type; this.json = json; }
        public RequestType getType() { return type; }
        public String getMessageId() { return json == null ? "" : json.optString("msgId"); }
        public boolean isMediaChangedEnabled() { return json != null && "true".equalsIgnoreCase(json.optString("value")); }
        public GBDeviceEventMusicControl.Event getControlEvent() { return json == null ? null : decodeControl(json); }
        public GBDeviceEventMusicControl.Event getVolumeEvent() {
            if (json == null || !"music-setattribute-req".equals(json.optString("msgId"))) return null;
            switch (json.optString("volume")) {
                case "up": return GBDeviceEventMusicControl.Event.VOLUMEUP;
                case "down": return GBDeviceEventMusicControl.Event.VOLUMEDOWN;
                default: return null;
            }
        }
    }

    /** API100 body is a clear service header (0) followed directly by UTF-8 JSON. */
    public static Request decodeRequest(final byte[] data) {
        if (data == null || data.length < 2 || data.length > MAX_JSON_BYTES + 1 || data[0] != 0) return new Request(RequestType.MALFORMED, null);
        try {
            final JSONObject json = new JSONObject(new String(data, 1, data.length - 1, StandardCharsets.UTF_8));
            switch (json.optString("msgId")) {
                case "music-mediachanged-req": return new Request(RequestType.MEDIA_CHANGED, json);
                case "music-getattribute-req": return new Request(RequestType.GET_ATTRIBUTE, json);
                case "music-setattribute-req": return new Request(RequestType.SET_ATTRIBUTE, json);
                case "capability-feature-ind": return new Request(RequestType.CAPABILITY_FEATURE, json);
                case "music-remotecontrol-req": return new Request(RequestType.REMOTE_CONTROL, json);
                default: return new Request(RequestType.UNKNOWN, json);
            }
        } catch (final JSONException e) { return new Request(RequestType.MALFORMED, null); }
    }

    public static byte[] encodeFrame(final byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_JSON_BYTES) throw new IllegalArgumentException("Invalid music JSON payload");
        final byte[] frame = new byte[json.length + 1];
        System.arraycopy(json, 0, frame, 1, json.length);
        return frame;
    }

    public static GBDeviceEventMusicControl.Event decodeControl(final byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_JSON_BYTES) return null;
        try {
            return decodeControl(new JSONObject(new String(data, StandardCharsets.UTF_8)));
        } catch (final JSONException e) { return null; }
    }
    private static GBDeviceEventMusicControl.Event decodeControl(final JSONObject json) {
            if (!"music-remotecontrol-req".equals(json.optString("msgId")) || !"pressed".equals(json.optString("status"))) return null;
            switch (json.optString("action")) {
                case "play": return GBDeviceEventMusicControl.Event.PLAY;
                case "pause": return GBDeviceEventMusicControl.Event.PAUSE;
                case "playpause": return GBDeviceEventMusicControl.Event.PLAYPAUSE;
                case "forward": return GBDeviceEventMusicControl.Event.NEXT;
                case "backward": return GBDeviceEventMusicControl.Event.PREVIOUS;
                default: return null;
            }
    }
    public static byte[] encodeMetadata(final MusicSpec spec) {
        return encodeMetadata(spec, albumArtForJson(spec.getAlbumArt()));
    }
    static byte[] encodeMetadata(final MusicSpec spec, final String image) {
        final JSONObject json = header("music-mediachanged-ind");
        try {
            json.put("index", "-1"); json.put("audioId", "-1"); json.put("title", bounded(spec.getTrack())); json.put("artist", bounded(spec.getArtist()));
            json.put("album", bounded(spec.getAlbum())); json.put("duration", Math.max(0, spec.getDuration()) * 1000L); json.put("image", image); json.put("albumArt", JSONObject.NULL); json.put("artistId", JSONObject.NULL); json.put("albumId", JSONObject.NULL); json.put("favoriteStatus", JSONObject.NULL); json.put("playstatus", JSONObject.NULL);
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final JSONException e) { throw new IllegalStateException(e); }
    }
    public static byte[] encodePlaybackState(final MusicStateSpec spec) {
        final JSONObject json = header("music-playstatechange-rsp");
        try { json.put("playstate", spec.getState() == MusicStateSpec.STATE_PLAYING ? 3 : 2); json.put("position", Math.max(0, spec.getPosition()) * 1000L); return json.toString().getBytes(StandardCharsets.UTF_8); }
        catch (final JSONException e) { throw new IllegalStateException(e); }
    }
    /** Samsung reports raw STREAM_MUSIC indices here, with the raw maximum in capability-feature-rsp. */
    public static byte[] encodeGetAttributeResponse(final MusicStateSpec spec, final int volume) {
        final JSONObject json = header("music-getattribute-rsp");
        try {
            final int repeat = spec == null ? MusicStateSpec.STATE_UNKNOWN : spec.getRepeat();
            json.put("repeat", repeat == 1 ? "repeatone" : repeat == 2 ? "repeatall" : "repeatoff");
            json.put("shuffle", spec != null && spec.getShuffle() == MusicStateSpec.STATE_SHUFFLE_ENABLED ? "on" : "off");
            json.put("volume", Math.max(0, volume));
            json.put("earjack", false);
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final JSONException e) { throw new IllegalStateException(e); }
    }
    public static byte[] encodeSetAttributeResponse() { return header("music-setattribute-rsp").toString().getBytes(StandardCharsets.UTF_8); }
    public static byte[] encodeVolumeChanged(final int volume) {
        final JSONObject json = header("music-volumechange-rsp");
        try { json.put("volume", Math.max(0, volume)); json.put("earjack", false); return json.toString().getBytes(StandardCharsets.UTF_8); }
        catch (final JSONException e) { throw new IllegalStateException(e); }
    }
    public static byte[] encodeCapabilityFeature(final int maxVolume) {
        final JSONObject json = header("capability-feature-rsp");
        try { json.put("distinct-state", "true"); json.put("warning-volumemax-state", Math.max(0, (maxVolume * 2) / 3)); json.put("volumemax-state", Math.max(0, maxVolume)); return json.toString().getBytes(StandardCharsets.UTF_8); }
        catch (final JSONException e) { throw new IllegalStateException(e); }
    }
    private static String albumArtForJson(final byte[] artwork) {
        return artwork == null || artwork.length > MAX_ARTWORK_BYTES ? "" : Base64.getEncoder().encodeToString(artwork);
    }
    private static JSONObject header(final String id) { final JSONObject json = new JSONObject(); try { json.put("msgId", id); json.put("result", "success"); json.put("reason", 0); json.put("appId", ""); } catch (final JSONException e) { throw new IllegalStateException(e); } return json; }
    private static String bounded(final String value) { if (value == null) return ""; return value.length() <= MAX_STRING_CODE_UNITS ? value : value.substring(0, MAX_STRING_CODE_UNITS); }
}
