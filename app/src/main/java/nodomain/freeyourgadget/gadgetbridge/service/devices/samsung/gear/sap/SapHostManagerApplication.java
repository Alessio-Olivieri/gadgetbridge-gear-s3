package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Bounded Host Manager startup messages on an already accepted API-103 session. */
public final class SapHostManagerApplication {
    public static final String WATCH_INFO_REQUEST = "mgr_watch_info_req";
    public static final String WATCH_INFO_RESPONSE = "mgr_watch_info_res";
    public static final String WEARABLE_STATUS_REQUEST = "mgr_wearable_status_req";
    public static final String WEARABLE_STATUS_RESPONSE = "mgr_wearable_status_res";
    public static final String HOST_STATUS_REQUEST = "mgr_host_status_req";
    public static final String HOST_STATUS_RESPONSE = "mgr_host_status_res";
    public static final String STATUS_EXCHANGE_DONE = "mgr_status_exchange_done";
    public static final String READY_FOR_RESTORE_REQUEST = "mgr_wearable_ready_for_restore_req";
    public static final String READY_FOR_RESTORE_RESPONSE = "mgr_wearable_ready_for_restore_res";
    public static final String DATA_NETWORK_REQUEST = "mgr_datanetwork_req";
    public static final String DATA_NETWORK_RESPONSE = "mgr_datanetwork_res";
    private static final String CONNECTION_TYPE = "connect";
    private static final int MAX_WIRE_BYTES = 65527;
    private static final int MAX_INFLATED_BYTES = 65527;
    private static final int MAX_STRING_LENGTH = 1024;
    private static final int MAX_STATUS_DATA_LENGTH = 60000;
    private static final int TRANSFORMED_PREFIX = 0x10;
    private static final String[] WATCH_INFO_FIELDS = new String[]{
            "model_number", "android_version", "software_version", "bt_mac_address", "wifi_mac_address",
            "model_name", "sales_code", "serial", "is_initialed_watch", "watchface", "default_pd_classname",
            "default_clock_pkgname", "peer_id", "isReactivationlocked", "sync_required", "payment_ese",
            "device_contact_count", "standalone_mode", "battery_level", "one_ui_version", "knox_version",
            "knox_sdk_version", "knox_tima_version", "knox_setizen_version", "battery_capacity",
            "battery_rated_current", "wms_version"
    };

    public enum State {
        HOST_MANAGER_CONNECTED,
        WATCH_INFO_COMPLETE,
        WEARABLE_STATUS_COMPLETE,
        HOST_STATUS_REQUESTED,
        HOST_STATUS_RESPONSE_SENT,
        HOST_STATUS_COMPLETE,
        READY_FOR_RESTORE_REQUEST_SENT,
        HOST_MANAGER_READY
    }

    private State state = State.HOST_MANAGER_CONNECTED;
    private boolean watchInfoRequestSent;
    private boolean wearableStatusRequestSent;
    private boolean hostStatusResponseSent;
    private boolean readyForRestoreRequestSent;
    private WatchInfoResponse watchInfoResponse;
    private WearableStatusResponse wearableStatusResponse;
    private HostStatusRequest hostStatusRequest;

    public byte[] createWatchInfoRequest(final String deviceId, final String bluetoothAddress,
                                         final String locale) {
        if (watchInfoRequestSent) return null;
        requireState(State.HOST_MANAGER_CONNECTED, "watch-info request");
        requireString(deviceId, "deviceid");
        requireString(bluetoothAddress, "btMac");
        requireString(locale, "locale");
        // Gadgetbridge has not performed Samsung watch-resource transfer.
        final String json = "{\"msgId\":" + quote(WATCH_INFO_REQUEST)
                + ",\"deviceid\":" + quote(deviceId)
                + ",\"btMac\":" + quote(bluetoothAddress)
                + ",\"locale\":" + quote(locale)
                + ",\"watchResourceTransferDone\":false}";
        watchInfoRequestSent = true;
        return encodeClear(json);
    }

    public byte[] createWearableStatusRequest(final String deviceId, final long currentTimeMillis) {
        if (wearableStatusRequestSent) return null;
        requireState(State.WATCH_INFO_COMPLETE, "wearable-status request");
        requireString(deviceId, "deviceid");
        if (currentTimeMillis < 0) throw new IllegalArgumentException("Invalid Host Manager timestamp");
        final String json = "{\"msgId\":" + quote(WEARABLE_STATUS_REQUEST)
                + ",\"deviceid\":" + quote(deviceId)
                + ",\"type\":" + quote(CONNECTION_TYPE)
                + ",\"timestamp\":" + quote(wearableStatusTimestamp(currentTimeMillis, deviceId)) + "}";
        wearableStatusRequestSent = true;
        return encodeClear(json);
    }

    public DecodedMessage decode(final byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("Invalid Host Manager application body length");
        }
        final byte[] jsonBytes;
        if ((body[0] & 0xff) == TRANSFORMED_PREFIX) {
            final byte[] inflated = inflateBounded(Arrays.copyOfRange(body, 1, body.length));
            final int offset = SapHostManagerMarker.requireSingleMessage(inflated, 0);
            jsonBytes = Arrays.copyOfRange(inflated, offset, inflated.length);
        } else {
            final int offset = SapHostManagerServiceChannelHeader.requireClearSingleMessage(body);
            final int jsonOffset = SapHostManagerMarker.requireSingleMessage(body, offset);
            jsonBytes = Arrays.copyOfRange(body, jsonOffset, body.length);
        }
        return decodeJson(jsonBytes);
    }

    /** Decode a bounded AppCipher payload after the typed SDK header. */
    public DecodedMessage decodeSecure(final byte[] body, final SapHostManagerAppCipher cipher) {
        if (body == null || body.length <= SapHostManagerServiceChannelHeader.SIZE || body.length > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("Invalid secure Host Manager application body length");
        }
        if (cipher == null || !SapHostManagerServiceChannelHeader.isSecure(body[0] & 0xff)) {
            throw new IllegalArgumentException("Unsupported secure Host Manager frame");
        }
        final byte[] protectedPayload = Arrays.copyOfRange(body, SapHostManagerServiceChannelHeader.SIZE, body.length);
        final byte[] decrypted = cipher.unprotect(protectedPayload);
        try {
            final byte[] logical = SapHostManagerServiceChannelHeader.isCompressedSecure(body[0] & 0xff)
                    ? inflateBounded(decrypted) : decrypted;
            final int jsonOffset = SapHostManagerMarker.requireSingleMessage(logical, 0);
            return decodeJson(Arrays.copyOfRange(logical, jsonOffset, logical.length));
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    private DecodedMessage decodeJson(final byte[] jsonBytes) {
        final JSONObject json = parseObject(decodeUtf8(jsonBytes));
        final String messageId = requiredString(json, "msgId");
        if (WATCH_INFO_RESPONSE.equals(messageId)) {
            return decodeWatchInfo(messageId, json);
        }
        if (WEARABLE_STATUS_RESPONSE.equals(messageId)) {
            return decodeWearableStatus(messageId, json);
        }
        if (HOST_STATUS_REQUEST.equals(messageId)) {
            return decodeHostStatusRequest(messageId, json);
        }
        if (STATUS_EXCHANGE_DONE.equals(messageId)) {
            return decodeStatusExchangeDone(messageId, json);
        }
        if (READY_FOR_RESTORE_RESPONSE.equals(messageId)) {
            return decodeReadyForRestoreResponse(messageId, json);
        }
        return new DecodedMessage(messageId, json, null, null, null);
    }

    /** Creates the logical (marker plus UTF-8 JSON) content protected by AppCipher. */
    public byte[] createHostStatusResponse(final HostStatusValues values) {
        if (hostStatusResponseSent) return null;
        requireState(State.HOST_STATUS_REQUESTED, "host-status response");
        if (values == null) throw new IllegalArgumentException("Missing Host Manager host-status values");
        final String json = "{\"msgId\":" + quote(HOST_STATUS_RESPONSE)
                + ",\"deviceid\":" + quote(values.gearAddress)
                + ",\"data\":" + quote(values.toXml())
                + ",\"type\":" + quote(values.type)
                + ",\"preinstalled\":\"false\"}";
        final byte[] jsonBytes = utf8(json);
        if (jsonBytes.length == 0 || jsonBytes.length + SapHostManagerMarker.SIZE > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("Invalid Host Manager host-status JSON size");
        }
        final byte[] logical = new byte[SapHostManagerMarker.SIZE + jsonBytes.length];
        System.arraycopy(SapHostManagerMarker.singleMessage(), 0, logical, 0, SapHostManagerMarker.SIZE);
        System.arraycopy(jsonBytes, 0, logical, SapHostManagerMarker.SIZE, jsonBytes.length);
        hostStatusResponseSent = true;
        state = State.HOST_STATUS_RESPONSE_SENT;
        return logical;
    }

    /** Applies automatic compression, then AppCipher protection. */
    public static SecureFrame protectHostStatus(final SapHostManagerAppCipher cipher, final byte[] logical) {
        if (cipher == null || logical == null || logical.length == 0 || logical.length > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("Invalid secure Host Manager logical payload");
        }
        byte[] payload = logical;
        boolean compressed = false;
        if (logical.length + SapHostManagerServiceChannelHeader.SIZE > 512) {
            final byte[] candidate = deflate(logical);
            if (candidate.length <= logical.length) {
                payload = candidate;
                compressed = true;
            } else {
                Arrays.fill(candidate, (byte) 0);
            }
        }
        final byte[] protectedPayload = cipher.protect(payload);
        final byte[] body = SapHostManagerServiceChannelHeader.encodeSecureSingleMessage(compressed, protectedPayload);
        if (payload != logical) Arrays.fill(payload, (byte) 0);
        Arrays.fill(protectedPayload, (byte) 0);
        return new SecureFrame(body, compressed, logical.length, body.length - SapHostManagerServiceChannelHeader.SIZE,
                cipher.getTxSequence() - 1);
    }

    public byte[] createReadyForRestoreRequest(final String deviceId) {
        if (readyForRestoreRequestSent) return null;
        requireState(State.HOST_STATUS_COMPLETE, "ready-for-restore request");
        requireString(deviceId, "deviceid");
        final String json = "{\"msgId\":" + quote(READY_FOR_RESTORE_REQUEST)
                + ",\"deviceid\":" + quote(deviceId) + "}";
        readyForRestoreRequestSent = true;
        state = State.READY_FOR_RESTORE_REQUEST_SENT;
        return encodeClear(json);
    }

    /** Settings responses contain deviceid and network type 0/1/2/3. */
    public static byte[] createDataNetworkResponse(final String deviceId, final int networkType) {
        requireString(deviceId, "deviceid");
        if (networkType < 0 || networkType > 3) throw new IllegalArgumentException("Invalid network type");
        return encodeClear("{\"msgId\":" + quote(DATA_NETWORK_RESPONSE)
                + ",\"deviceid\":" + quote(deviceId) + ",\"data\":" + networkType + "}");
    }

    private DecodedMessage decodeWatchInfo(final String messageId, final JSONObject json) {
        if (!watchInfoRequestSent) throw new IllegalStateException("Watch-info response arrived before request");
        final Map<String, Object> fields = new LinkedHashMap<>();
        try {
            for (final String field : WATCH_INFO_FIELDS) {
                if (!json.has(field) || json.isNull(field)) continue;
                final Object value = json.get(field);
                if (value instanceof String && ((String) value).length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("Oversized Host Manager field " + field);
                }
                if (!(value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Double)) {
                    throw new IllegalArgumentException("Unsupported Host Manager field type " + field);
                }
                fields.put(field, value);
            }
        } catch (final JSONException e) {
            throw new IllegalArgumentException("Invalid Host Manager watch-info response", e);
        }
        watchInfoResponse = new WatchInfoResponse(fields);
        if (state == State.HOST_MANAGER_CONNECTED) state = State.WATCH_INFO_COMPLETE;
        return new DecodedMessage(messageId, json, watchInfoResponse, null, null);
    }

    private DecodedMessage decodeWearableStatus(final String messageId, final JSONObject json) {
        if (!wearableStatusRequestSent || state.ordinal() < State.WATCH_INFO_COMPLETE.ordinal()) {
            throw new IllegalStateException("Wearable-status response arrived before request");
        }
        final String type = requiredString(json, "type");
        if (!CONNECTION_TYPE.equals(type)) throw new IllegalArgumentException("Unexpected wearable-status type " + type);
        final String data = requiredString(json, "data", MAX_STATUS_DATA_LENGTH);
        wearableStatusResponse = new WearableStatusResponse(type, data);
        if (state == State.WATCH_INFO_COMPLETE) state = State.WEARABLE_STATUS_COMPLETE;
        return new DecodedMessage(messageId, json, null, wearableStatusResponse, null);
    }

    private DecodedMessage decodeHostStatusRequest(final String messageId, final JSONObject json) {
        if (state.ordinal() < State.WEARABLE_STATUS_COMPLETE.ordinal()) {
            throw new IllegalStateException("Host-status request arrived before wearable-status completion");
        }
        final String type = requiredString(json, "type");
        if (!CONNECTION_TYPE.equals(type)) throw new IllegalArgumentException("Unexpected host-status type " + type);
        hostStatusRequest = new HostStatusRequest(type);
        if (state == State.WEARABLE_STATUS_COMPLETE) state = State.HOST_STATUS_REQUESTED;
        return new DecodedMessage(messageId, json, null, null, hostStatusRequest);
    }

    private DecodedMessage decodeStatusExchangeDone(final String messageId, final JSONObject json) {
        if (state.ordinal() < State.HOST_STATUS_RESPONSE_SENT.ordinal()) {
            throw new IllegalStateException("Status-exchange completion arrived before host-status response");
        }
        final String result = requiredString(json, "result");
        if (!CONNECTION_TYPE.equals(result)) throw new IllegalArgumentException("Unexpected status-exchange result " + result);
        final Object reason = json.opt("reason");
        if (!(reason instanceof Number) || ((Number) reason).longValue() != 0) {
            throw new IllegalArgumentException("Unsuccessful Host Manager status-exchange reason");
        }
        if (state == State.HOST_STATUS_RESPONSE_SENT) state = State.HOST_STATUS_COMPLETE;
        return new DecodedMessage(messageId, json, null, null, null);
    }

    private DecodedMessage decodeReadyForRestoreResponse(final String messageId, final JSONObject json) {
        if (!readyForRestoreRequestSent || state.ordinal() < State.READY_FOR_RESTORE_REQUEST_SENT.ordinal()) {
            throw new IllegalStateException("Ready-for-restore response arrived before request");
        }
        if (json.has("deviceid") && !json.isNull("deviceid")) requiredString(json, "deviceid");
        state = State.HOST_MANAGER_READY;
        return new DecodedMessage(messageId, json, null, null, null);
    }

    public void clear() {
        watchInfoRequestSent = false;
        wearableStatusRequestSent = false;
        hostStatusResponseSent = false;
        readyForRestoreRequestSent = false;
        watchInfoResponse = null;
        wearableStatusResponse = null;
        hostStatusRequest = null;
        state = State.HOST_MANAGER_CONNECTED;
    }

    public State getState() { return state; }
    public boolean isWatchInfoRequestSent() { return watchInfoRequestSent; }
    public boolean isWearableStatusRequestSent() { return wearableStatusRequestSent; }
    public boolean isHostStatusResponseSent() { return hostStatusResponseSent; }
    public boolean isReadyForRestoreRequestSent() { return readyForRestoreRequestSent; }
    public WatchInfoResponse getWatchInfoResponse() { return watchInfoResponse; }
    public WearableStatusResponse getWearableStatusResponse() { return wearableStatusResponse; }
    public HostStatusRequest getHostStatusRequest() { return hostStatusRequest; }

    public static byte[] encodeClear(final String json) {
        final byte[] jsonBytes = utf8(json);
        if (jsonBytes.length == 0 || jsonBytes.length + SapHostManagerServiceChannelHeader.SIZE + SapHostManagerMarker.SIZE > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("Invalid Host Manager clear JSON size");
        }
        parseObject(json);
        final byte[] body = new byte[SapHostManagerServiceChannelHeader.SIZE + SapHostManagerMarker.SIZE + jsonBytes.length];
        System.arraycopy(SapHostManagerServiceChannelHeader.encodeClearSingleMessage(), 0, body, 0, SapHostManagerServiceChannelHeader.SIZE);
        System.arraycopy(SapHostManagerMarker.singleMessage(), 0, body, SapHostManagerServiceChannelHeader.SIZE, SapHostManagerMarker.SIZE);
        System.arraycopy(jsonBytes, 0, body, SapHostManagerServiceChannelHeader.SIZE + SapHostManagerMarker.SIZE, jsonBytes.length);
        return body;
    }

    private static byte[] inflateBounded(final byte[] compressed) {
        if (compressed.length == 0) throw new IllegalArgumentException("Empty transformed Host Manager frame");
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[512];
            int read;
            while ((read = inflater.read(buffer)) != -1) {
                if (output.size() + read > MAX_INFLATED_BYTES) throw new IllegalArgumentException("Host Manager transformed frame exceeds limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (final IOException e) {
            throw new IllegalArgumentException("Invalid Host Manager transformed frame", e);
        }
    }

    private static byte[] deflate(final byte[] input) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(input);
            deflater.finish();
            return output.toByteArray();
        } catch (final IOException e) {
            throw new IllegalStateException("Could not compress Host Manager payload", e);
        }
    }

    private static JSONObject parseObject(final String json) {
        if (json == null || json.length() == 0 || json.length() > MAX_INFLATED_BYTES || json.charAt(0) != '{') {
            throw new IllegalArgumentException("Host Manager payload is not a bounded JSON object");
        }
        try {
            return new JSONObject(json);
        } catch (final JSONException e) {
            throw new IllegalArgumentException("Invalid Host Manager JSON", e);
        }
    }

    private static String requiredString(final JSONObject json, final String name) {
        return requiredString(json, name, MAX_STRING_LENGTH);
    }

    private static String requiredString(final JSONObject json, final String name, final int maximumLength) {
        final Object value = json.opt(name);
        if (!(value instanceof String)) throw new IllegalArgumentException("Missing or non-string Host Manager " + name);
        final String string = (String) value;
        if (string.isEmpty() || string.length() > maximumLength) {
            throw new IllegalArgumentException("Invalid Host Manager " + name);
        }
        return string;
    }

    private static void requireString(final String value, final String name) {
        if (value == null || value.isEmpty() || value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Invalid Host Manager " + name);
        }
    }

    private static byte[] utf8(final String value) {
        if (value == null) throw new IllegalArgumentException("Missing Host Manager JSON");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(final String value) {
        return JSONObject.quote(value);
    }

    static String wearableStatusTimestamp(final long currentTimeMillis, final String deviceId) {
        requireString(deviceId, "deviceid");
        final String compactAddress = deviceId.replace(":", "");
        final StringBuilder result = new StringBuilder(Long.toString(currentTimeMillis));
        for (int i = 0; i < compactAddress.length(); i++) {
            result.append((char) (compactAddress.charAt(i) + 10));
        }
        return result.toString();
    }

    private void requireState(final State required, final String action) {
        if (state != required) throw new IllegalStateException("Cannot send Host Manager " + action + " in state " + state);
    }

    private static String decodeUtf8(final byte[] value) {
        try {
            final CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value));
            return decoded.toString();
        } catch (final CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid Host Manager UTF-8", e);
        }
    }

    public static final class DecodedMessage {
        private final String messageId;
        private final JSONObject json;
        private final WatchInfoResponse watchInfo;
        private final WearableStatusResponse wearableStatus;
        private final HostStatusRequest hostStatus;
        private DecodedMessage(final String messageId, final JSONObject json, final WatchInfoResponse watchInfo,
                               final WearableStatusResponse wearableStatus, final HostStatusRequest hostStatus) {
            this.messageId = messageId;
            this.json = json;
            this.watchInfo = watchInfo;
            this.wearableStatus = wearableStatus;
            this.hostStatus = hostStatus;
        }
        public String getMessageId() { return messageId; }
        public JSONObject getJson() { return json; }
        public WatchInfoResponse getWatchInfo() { return watchInfo; }
        public WearableStatusResponse getWearableStatus() { return wearableStatus; }
        public HostStatusRequest getHostStatus() { return hostStatus; }
    }

    public static final class WatchInfoResponse {
        private final Map<String, Object> fields;
        private WatchInfoResponse(final Map<String, Object> fields) { this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
        public Map<String, Object> getFields() { return fields; }
    }

    public static final class WearableStatusResponse {
        private final String type;
        private final String data;
        private WearableStatusResponse(final String type, final String data) { this.type = type; this.data = data; }
        public String getType() { return type; }
        public String getData() { return data; }
    }

    public static final class HostStatusRequest {
        private final String type;
        private HostStatusRequest(final String type) { this.type = type; }
        public String getType() { return type; }
    }

    /** Bounded host facts serialized as DeviceStatus. */
    public static final class HostStatusValues {
        private final String gearAddress, type, deviceId, deviceName, platformVersion, modelNumber, softwareVersion, sdkVersion;
        public HostStatusValues(final String gearAddress, final String type, final String deviceId, final String deviceName,
                                final String platformVersion, final String modelNumber, final String softwareVersion,
                                final String sdkVersion) {
            requireString(gearAddress, "deviceid");
            requireString(type, "type");
            this.gearAddress = gearAddress;
            this.type = type;
            this.deviceId = optional(deviceId);
            this.deviceName = optional(deviceName);
            this.platformVersion = optional(platformVersion);
            this.modelNumber = optional(modelNumber);
            this.softwareVersion = optional(softwareVersion);
            this.sdkVersion = optional(sdkVersion);
        }
        public String toXml() {
            final StringBuilder xml = new StringBuilder("<DeviceStatus><device>");
            optionalElement(xml, "deviceID", deviceId);
            optionalElement(xml, "deviceName", deviceName);
            element(xml, "devicePlatform", "android");
            optionalElement(xml, "devicePlatformVersion", platformVersion);
            element(xml, "deviceType", "Host");
            optionalElement(xml, "modelNumber", modelNumber);
            optionalElement(xml, "swVersion", softwareVersion);
            element(xml, "MCC", "");
            element(xml, "MNC", "");
            optionalElement(xml, "SDKVersion", sdkVersion);
            xml.append("<connectivity/><deviceFeature/><apps/></device></DeviceStatus>");
            return xml.toString();
        }
        private static String optional(final String value) {
            if (value == null || value.isEmpty()) return null;
            if (value.length() > MAX_STRING_LENGTH) throw new IllegalArgumentException("Oversized Host Manager host fact");
            return value;
        }
        private static void optionalElement(final StringBuilder xml, final String name, final String value) {
            if (value != null) element(xml, name, value);
        }
        private static void element(final StringBuilder xml, final String name, final String value) {
            xml.append('<').append(name).append('>').append(xmlEscape(value)).append("</").append(name).append('>');
        }
        private static String xmlEscape(final String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;");
        }
    }

    public static final class SecureFrame {
        private final byte[] body;
        private final boolean compressed;
        private final int logicalLength, protectedLength;
        private final long sequence;
        private SecureFrame(final byte[] body, final boolean compressed, final int logicalLength,
                            final int protectedLength, final long sequence) {
            this.body = Arrays.copyOf(body, body.length); this.compressed = compressed;
            this.logicalLength = logicalLength; this.protectedLength = protectedLength; this.sequence = sequence;
        }
        public byte[] getBody() { return Arrays.copyOf(body, body.length); }
        public boolean isCompressed() { return compressed; }
        public int getLogicalLength() { return logicalLength; }
        public int getProtectedLength() { return protectedLength; }
        public long getSequence() { return sequence; }
    }
}
