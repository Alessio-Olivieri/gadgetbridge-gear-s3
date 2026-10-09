package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Receive-only parser for the Gear S health JSON schema. */
public final class SapPedometerJsonProbe {
    public static final String GEAR_SYNC_COMMAND = "Gear_Sync_Cmd";
    public static final String GEAR_SHEALTH_SYNC = "Gear_SHealth_Sync";
    private static final String[] PEDOMETER_FIELDS = {
            "mtimeCreated", "mWalkStepCount", "mRunStepCount", "mTotalStepCount", "mDistance", "mCalory",
            "mSpeed", "mHealthyStep", "mActiveTime", "devicePKId", "mCRUD", "mUpStep", "mDownStep", "mVersionCode"
    };

    private SapPedometerJsonProbe() { }

    public static Result parse(final byte[] payload) {
        if (payload == null) return Result.malformed("null payload");
        final String json;
        try {
            final CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload));
            json = decoded.toString();
        } catch (final CharacterCodingException e) {
            return Result.malformed("invalid UTF-8: " + e.getMessage());
        }
        try {
            final JSONObject object = new JSONObject(json);
            final Map<String, Object> topLevel = new LinkedHashMap<>();
            final JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    final String name = names.getString(i);
                    final Object value = object.opt(name);
                    topLevel.put(name, value == null ? JSONObject.NULL : value);
                }
            }
            final String requestMessage = nullableString(object, "requestMessage");
            final String responseMessage = nullableString(object, "responseMessage");
            final List<Record> records = new ArrayList<>();
            if (GEAR_SHEALTH_SYNC.equals(responseMessage)) {
                final JSONObject results = object.optJSONObject("pedometerResults");
                if (results != null) {
                    final JSONArray array = results.optJSONArray("SPedoResult");
                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            final JSONObject record = array.optJSONObject(i);
                            if (record == null) return Result.malformed("SPedoResult[" + i + "] is not an object");
                            final Map<String, Field> fields = new LinkedHashMap<>();
                            for (final String field : PEDOMETER_FIELDS) {
                                fields.put(field, new Field(record.has(field), record.opt(field)));
                            }
                            records.add(new Record(fields));
                        }
                    } else if (results.has("SPedoResult")) {
                        return Result.malformed("pedometerResults.SPedoResult is not an array");
                    }
                }
            }
            return Result.valid(payload.length, topLevel, requestMessage, responseMessage,
                    number(object, "requestCode"), number(object, "responseCode"), number(object, "version"),
                    number(object, "device"), number(object, "LastReceiveTime"), number(object, "LastSendTime"), records);
        } catch (final JSONException | RuntimeException e) {
            return Result.malformed("invalid health JSON: " + e.getMessage());
        }
    }

    /** Bounded inspection of Samsung V4.51 CommonSync without retaining data records. */
    public static CommonSync inspectCommonSync(final Result result) {
        if (result == null || !result.isValid()) return null;
        final Object message = result.getTopLevel().get("message");
        final Object bodyValue = result.getTopLevel().get("body");
        if (!(bodyValue instanceof String) || !"REQUEST".equals(String.valueOf(message))) return null;
        try {
            JSONArray request;
            List<String> bodyKeys;
            try {
                final JSONObject body = new JSONObject((String) bodyValue);
                request = body.optJSONArray("SYNC_REQUEST");
                if (request == null) return null;
                bodyKeys = jsonKeys(body);
            } catch (final JSONException notWrappedObject) {
                // The V4.51 Gear request contains the SYNC_REQUEST array directly.
                request = new JSONArray((String) bodyValue);
                bodyKeys = Collections.emptyList();
            }
            final List<String> elementKeys = new ArrayList<>();
            final List<String> representativeFields = new ArrayList<>();
            final List<String> arraySummaries = new ArrayList<>();
            String action = null;
            Object start = JSONObject.NULL, end = JSONObject.NULL, last = JSONObject.NULL, lastChunk = JSONObject.NULL;
            for (int i = 0; i < request.length(); i++) {
                final JSONObject element = request.optJSONObject(i);
                if (element == null) continue;
                elementKeys.addAll(jsonKeys(element));
                collectArrays(element, "SYNC_REQUEST[" + i + "]", arraySummaries, representativeFields, 0);
                final JSONArray messageInfo = element.optJSONArray("message_info");
                if (messageInfo == null || messageInfo.length() == 0) continue;
                final JSONObject info = messageInfo.optJSONObject(0);
                if (info == null) continue;
                final List<String> infoFields = jsonKeys(info);
                if (representativeFields.isEmpty() || !representativeFields.contains("action")) {
                    representativeFields.addAll(infoFields);
                }
                if (action == null) action = nullableString(info, "action");
                if (info.has("start_time")) start = info.opt("start_time");
                if (info.has("end_time")) end = info.opt("end_time");
                if (info.has("last_sync_time")) last = info.opt("last_sync_time");
                if (info.has("is_last_chunk")) lastChunk = info.opt("is_last_chunk");
            }
            return new CommonSync(result.getTopLevel(), bodyKeys, request.length(), elementKeys,
                    representativeFields, arraySummaries, action, start, end, last, lastChunk,
                    result.getTopLevel().get("sequence_num"));
        } catch (final JSONException | RuntimeException ignored) {
            return null;
        }
    }

    /** Parse complete Samsung pedometer rows while suppressing duplicate source UUIDs in one chunk. */
    public static PedometerStepRecords parsePedometerStepRecords(final Result result) {
        if (result == null || !result.isValid()) return new PedometerStepRecords(Collections.<PedometerStepRecord>emptyList(), 0, 0);
        final Object bodyValue = result.getTopLevel().get("body");
        if (!(bodyValue instanceof String)) return new PedometerStepRecords(Collections.<PedometerStepRecord>emptyList(), 0, 0);
        try {
            final Object body = parseCommonSyncBody((String) bodyValue);
            final List<JSONArray> arrays = new ArrayList<>();
            findTableArrays(body, "pedometer_step_count", arrays, 0);
            findTableArrays(body, "com.samsung.shealth.tracker.pedometer_step_count", arrays, 0);
            final Set<String> uuids = new LinkedHashSet<>();
            final List<PedometerStepRecord> records = new ArrayList<>();
            int malformed = 0;
            int duplicates = 0;
            for (final JSONArray array : arrays) {
                for (int i = 0; i < array.length(); i++) {
                    final Object value = array.opt(i);
                    if (!(value instanceof JSONObject)) {
                        malformed++;
                        continue;
                    }
                    final PedometerStepRecord record = PedometerStepRecord.parse((JSONObject) value);
                    if (record == null) {
                        malformed++;
                    } else if (!uuids.add(record.getDatauuid())) {
                        duplicates++;
                    } else {
                        records.add(record);
                    }
                }
            }
            return new PedometerStepRecords(records, malformed, duplicates);
        } catch (final JSONException | RuntimeException ignored) {
            return new PedometerStepRecords(Collections.<PedometerStepRecord>emptyList(), 1, 0);
        }
    }

    /** Parse Gear S3 V4.51 CommonSync heart-rate rows. */
    public static HeartRateRecords parseHeartRateRecords(final Result result) {
        if (result == null || !result.isValid()) {
            return new HeartRateRecords(Collections.<HeartRateRecord>emptyList(), 0, 0);
        }
        final Object bodyValue = result.getTopLevel().get("body");
        if (!(bodyValue instanceof String)) {
            return new HeartRateRecords(Collections.<HeartRateRecord>emptyList(), 0, 0);
        }
        try {
            final Object body = parseCommonSyncBody((String) bodyValue);
            final List<JSONArray> arrays = new ArrayList<>();
            findTableArrays(body, "com.samsung.shealth.tracker.heart_rate", arrays, 0);
            final Set<String> uuids = new LinkedHashSet<>();
            final List<HeartRateRecord> records = new ArrayList<>();
            int malformed = 0;
            int duplicates = 0;
            for (final JSONArray array : arrays) {
                for (int i = 0; i < array.length(); i++) {
                    final JSONObject object = array.optJSONObject(i);
                    final HeartRateRecord record = object == null ? null : HeartRateRecord.parse(object);
                    if (record == null) {
                        malformed++;
                    } else if (!uuids.add(record.getDatauuid())) {
                        duplicates++;
                    } else {
                        records.add(record);
                    }
                }
            }
            return new HeartRateRecords(records, malformed, duplicates);
        } catch (final JSONException | RuntimeException ignored) {
            return new HeartRateRecords(Collections.<HeartRateRecord>emptyList(), 1, 0);
        }
    }

    private static Object parseCommonSyncBody(final String bodyValue) throws JSONException {
        try {
            final JSONObject body = new JSONObject(bodyValue);
            final JSONArray request = body.optJSONArray("SYNC_REQUEST");
            if (request == null) throw new JSONException("SYNC_REQUEST is not an array");
            return request;
        } catch (final JSONException notWrappedObject) {
            return new JSONArray(bodyValue);
        }
    }

    private static void findTableArrays(final Object value, final String tableName,
                                        final List<JSONArray> arrays, final int depth) {
        if (depth > 8 || value == null || value == JSONObject.NULL) return;
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                findTableArrays(array.opt(i), tableName, arrays, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject)) return;
        final JSONObject object = (JSONObject) value;
        final JSONArray names = object.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            final String name = names.optString(i);
            final Object child = object.opt(name);
            if (tableName.equals(name)) {
                if (child instanceof JSONArray && !arrays.contains(child)) {
                    arrays.add((JSONArray) child);
                } else {
                    collectArrays(child, arrays, depth + 1);
                }
            }
            // Table names are strings. Never stringify containers (or numbers)
            // while recursively visiting every field: that repeatedly serializes
            // whole subtrees, including all records, for each table being sought.
            if (("table".equals(name) || "table_name".equals(name) || "data_type".equals(name)
                    || "dataType".equals(name) || "tableName".equals(name) || "type".equals(name)
                    || "name".equals(name)) && tableName.equals(child)) {
                collectNamedRecordArrays(object, arrays, depth + 1);
            }
            findTableArrays(child, tableName, arrays, depth + 1);
        }
    }

    private static void collectNamedRecordArrays(final JSONObject object, final List<JSONArray> arrays,
                                                 final int depth) {
        for (final String key : new String[]{"records", "data", "values", "items", "rows", "result"}) {
            final Object value = object.opt(key);
            if (value instanceof JSONArray) collectArrays(value, arrays, depth);
        }
    }

    private static void collectArrays(final Object value, final List<JSONArray> arrays, final int depth) {
        if (depth > 8 || value == null || value == JSONObject.NULL) return;
        if (value instanceof JSONArray) {
            if (!arrays.contains(value)) arrays.add((JSONArray) value);
        } else if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            final JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                collectArrays(object.opt(names.optString(i)), arrays, depth + 1);
            }
        }
    }

    private static List<String> jsonKeys(final JSONObject object) {
        final List<String> keys = new ArrayList<>();
        final JSONArray names = object.names();
        if (names != null) for (int i = 0; i < names.length(); i++) keys.add(names.optString(i));
        return keys;
    }

    private static void collectArrays(final Object value, final String path, final List<String> summaries,
                                      final List<String> representativeFields, final int depth) {
        if (depth > 3 || value == null || value == JSONObject.NULL) return;
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            summaries.add(path + "[count=" + array.length() + "]");
            if (array.length() > 0) {
                final Object first = array.opt(0);
                if (first instanceof JSONObject && representativeFields.isEmpty()) {
                    representativeFields.addAll(jsonKeys((JSONObject) first));
                }
                collectArrays(first, path + "[0]", summaries, representativeFields, depth + 1);
            }
        } else if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            final JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                final String name = names.optString(i);
                collectArrays(object.opt(name), path + "." + name, summaries, representativeFields, depth + 1);
            }
        }
    }

    /** V4.51 CommonSync response envelope. */
    public static byte[] commonSyncSuccessResponse(final Object sequence) {
        return commonSyncSuccessResponse(sequence, "SYNC_DATA");
    }

    /** Exact V4.51 response envelope for a supported CommonSync action. */
    public static byte[] commonSyncSuccessResponse(final Object sequence, final String action) {
        try {
            final JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("device", "Gadgetbridge");
            deviceInfo.put("device_type", 0);
            deviceInfo.put("version", 4.51d);
            deviceInfo.put("last_sync_time", 0L);
            final JSONArray deviceInfoArray = new JSONArray().put(deviceInfo);

            final JSONObject messageInfo = new JSONObject();
            messageInfo.put("action", action);
            messageInfo.put("result", "SUCCESS");
            final JSONArray messageInfoArray = new JSONArray().put(messageInfo);

            final JSONArray responseArray = new JSONArray();
            responseArray.put(new JSONObject().put("device_info", deviceInfoArray));
            responseArray.put(new JSONObject().put("message_info", messageInfoArray));

            final JSONObject outer = new JSONObject();
            outer.put("message", "RESPONSE");
            outer.put("sender", "com.samsung.android.app.shealth.wearable.syncmanager");
            outer.put("receiver", "com.samsung.tizengear.app.shealth.wearable.syncmanager");
            outer.put("version", 4.51d);
            outer.put("device", "10038");
            if (sequence != null && sequence != JSONObject.NULL) outer.put("sequence_num", sequence);
            outer.put("type", "DATA");
            outer.put("body", responseArray.toString());
            return outer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final JSONException e) {
            throw new IllegalStateException("Unable to build CommonSync response", e);
        }
    }

    private static String nullableString(final JSONObject object, final String key) {
        final Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
    }

    private static Object number(final JSONObject object, final String key) {
        final Object value = object.opt(key);
        return value == null ? JSONObject.NULL : value;
    }

    public static final class Result {
        private final boolean valid;
        private final String error;
        private final int byteLength;
        private final Map<String, Object> topLevel;
        private final String requestMessage, responseMessage;
        private final Object requestCode, responseCode, version, device, lastReceiveTime, lastSendTime;
        private final List<Record> records;

        private Result(final boolean valid, final String error, final int byteLength, final Map<String, Object> topLevel,
                       final String requestMessage, final String responseMessage, final Object requestCode,
                       final Object responseCode, final Object version, final Object device, final Object lastReceiveTime,
                       final Object lastSendTime, final List<Record> records) {
            this.valid = valid; this.error = error; this.byteLength = byteLength;
            this.topLevel = Collections.unmodifiableMap(new LinkedHashMap<>(topLevel));
            this.requestMessage = requestMessage; this.responseMessage = responseMessage;
            this.requestCode = requestCode; this.responseCode = responseCode; this.version = version;
            this.device = device; this.lastReceiveTime = lastReceiveTime; this.lastSendTime = lastSendTime;
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
        }

        private static Result valid(final int length, final Map<String, Object> topLevel, final String requestMessage,
                                    final String responseMessage, final Object requestCode, final Object responseCode,
                                    final Object version, final Object device, final Object lastReceiveTime,
                                    final Object lastSendTime, final List<Record> records) {
            return new Result(true, null, length, topLevel, requestMessage, responseMessage, requestCode,
                    responseCode, version, device, lastReceiveTime, lastSendTime, records);
        }

        private static Result malformed(final String error) {
            return new Result(false, error, 0, Collections.<String, Object>emptyMap(), null, null,
                    JSONObject.NULL, JSONObject.NULL, JSONObject.NULL, JSONObject.NULL, JSONObject.NULL, JSONObject.NULL,
                    Collections.<Record>emptyList());
        }

        public boolean isValid() { return valid; }
        public String getError() { return error; }
        public int getByteLength() { return byteLength; }
        public Map<String, Object> getTopLevel() { return topLevel; }
        public String getRequestMessage() { return requestMessage; }
        public String getResponseMessage() { return responseMessage; }
        public Object getRequestCode() { return requestCode; }
        public Object getResponseCode() { return responseCode; }
        public Object getVersion() { return version; }
        public Object getDevice() { return device; }
        public Object getLastReceiveTime() { return lastReceiveTime; }
        public Object getLastSendTime() { return lastSendTime; }
        public List<Record> getRecords() { return records; }
    }

    public static final class CommonSync {
        private final Map<String, Object> topLevel;
        private final List<String> bodyKeys, elementKeys, representativeFields, arraySummaries;
        private final int requestCount;
        private final String action;
        private final Object startTime, endTime, lastSyncTime, lastChunk, sequence;

        private CommonSync(final Map<String, Object> topLevel, final List<String> bodyKeys, final int requestCount,
                           final List<String> elementKeys, final List<String> representativeFields,
                           final List<String> arraySummaries, final String action,
                           final Object startTime, final Object endTime, final Object lastSyncTime,
                           final Object lastChunk, final Object sequence) {
            this.topLevel = Collections.unmodifiableMap(new LinkedHashMap<>(topLevel));
            this.bodyKeys = Collections.unmodifiableList(new ArrayList<>(bodyKeys));
            this.requestCount = requestCount;
            this.elementKeys = Collections.unmodifiableList(new ArrayList<>(elementKeys));
            this.representativeFields = Collections.unmodifiableList(new ArrayList<>(representativeFields));
            this.arraySummaries = Collections.unmodifiableList(new ArrayList<>(arraySummaries));
            this.action = action;
            this.startTime = startTime; this.endTime = endTime; this.lastSyncTime = lastSyncTime;
            this.lastChunk = lastChunk; this.sequence = sequence;
        }

        public Map<String, Object> getTopLevel() { return topLevel; }
        public List<String> getBodyKeys() { return bodyKeys; }
        public int getRequestCount() { return requestCount; }
        public List<String> getElementKeys() { return elementKeys; }
        public List<String> getRepresentativeFields() { return representativeFields; }
        public List<String> getArraySummaries() { return arraySummaries; }
        public String getAction() { return action; }
        public Object getStartTime() { return startTime; }
        public Object getEndTime() { return endTime; }
        public Object getLastSyncTime() { return lastSyncTime; }
        public Object getLastChunk() { return lastChunk; }
        public Object getSequence() { return sequence; }
    }

    public static final class Record {
        private final Map<String, Field> fields;
        private Record(final Map<String, Field> fields) { this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
        public Map<String, Field> getFields() { return fields; }
    }

    public static final class Field {
        private final boolean present;
        private final Object value;
        private Field(final boolean present, final Object value) { this.present = present; this.value = value; }
        public boolean isPresent() { return present; }
        public Object getValue() { return value == null ? JSONObject.NULL : value; }
    }

    public static final class PedometerStepRecords {
        private final List<PedometerStepRecord> records;
        private final int malformedCount;
        private final int duplicateCount;

        private PedometerStepRecords(final List<PedometerStepRecord> records, final int malformedCount,
                                     final int duplicateCount) {
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.malformedCount = malformedCount;
            this.duplicateCount = duplicateCount;
        }

        public List<PedometerStepRecord> getRecords() { return records; }
        public int getMalformedCount() { return malformedCount; }
        public int getDuplicateCount() { return duplicateCount; }
    }

    public static final class HeartRateRecords {
        private final List<HeartRateRecord> records;
        private final int malformedCount;
        private final int duplicateCount;

        private HeartRateRecords(final List<HeartRateRecord> records, final int malformedCount,
                                 final int duplicateCount) {
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.malformedCount = malformedCount;
            this.duplicateCount = duplicateCount;
        }

        public List<HeartRateRecord> getRecords() { return records; }
        public int getMalformedCount() { return malformedCount; }
        public int getDuplicateCount() { return duplicateCount; }
    }

    public static final class HeartRateRecord {
        private final String datauuid;
        private final long startTimeMs;
        private final long endTimeMs;
        private final int heartRate;

        private HeartRateRecord(final String datauuid, final long startTimeMs,
                                final long endTimeMs, final int heartRate) {
            this.datauuid = datauuid;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.heartRate = heartRate;
        }

        private static HeartRateRecord parse(final JSONObject object) {
            final Object uuid = object.opt("datauuid");
            final Long start = exactLong(object.opt("start_time"));
            final Long end = exactLong(object.opt("end_time"));
            final Object value = object.opt("heart_rate");
            if (!(uuid instanceof String) || ((String) uuid).trim().isEmpty()
                    || start == null || end == null || start <= 0 || end < start
                    || start / 1000L > Integer.MAX_VALUE || !(value instanceof Number)) {
                return null;
            }
            final double bpm = ((Number) value).doubleValue();
            // ActivitySample and the default chart cannot represent a valid value above 250 bpm.
            if (!Double.isFinite(bpm) || bpm < 1.0d || bpm > 250.0d) return null;
            return new HeartRateRecord((String) uuid, start, end, (int) Math.round(bpm));
        }

        private static Long exactLong(final Object value) {
            if (!(value instanceof Number)) return null;
            final double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || number < Long.MIN_VALUE || number > Long.MAX_VALUE
                    || number != Math.rint(number)) return null;
            return ((Number) value).longValue();
        }

        public String getDatauuid() { return datauuid; }
        public long getStartTimeMs() { return startTimeMs; }
        public long getEndTimeMs() { return endTimeMs; }
        public int getHeartRate() { return heartRate; }
    }

    public static final class PedometerStepRecord {
        private final String datauuid;
        private final long startTimeMs, endTimeMs;
        private final int count, walkStep, runStep;
        private final float distance, calorie, speed;
        private final long duration;

        private PedometerStepRecord(final String datauuid, final long startTimeMs, final long endTimeMs,
                                    final int count, final int walkStep, final int runStep, final float distance,
                                    final float calorie, final long duration, final float speed) {
            this.datauuid = datauuid;
            this.startTimeMs = startTimeMs; this.endTimeMs = endTimeMs;
            this.count = count; this.walkStep = walkStep; this.runStep = runStep;
            this.distance = distance; this.calorie = calorie; this.duration = duration; this.speed = speed;
        }

        private static PedometerStepRecord parse(final JSONObject object) {
            final Object uuidValue = object.opt("datauuid");
            final Long start = requiredLong(object.opt("start_time"));
            final Long end = requiredLong(object.opt("end_time"));
            final Integer count = requiredInt(object.opt("count"));
            if (!(uuidValue instanceof String) || ((String) uuidValue).trim().isEmpty()
                    || start == null || end == null || count == null || start < 0 || end < start || count < 0) {
                return null;
            }
            // These fields are persisted verbatim; silently substituting zero for a partial row
            // would create a plausible-looking but incorrect activity sample.
            final Integer walk = requiredInt(object.opt("walk_step"));
            final Integer run = requiredInt(object.opt("run_step"));
            final Float distance = requiredFloat(object.opt("distance"));
            final Float calorie = requiredFloat(object.opt("calorie"));
            final Long duration = requiredLong(object.opt("duration"));
            final Float speed = requiredFloat(object.opt("speed"));
            if (walk == null || run == null || distance == null || calorie == null || duration == null || speed == null) {
                return null;
            }
            return new PedometerStepRecord((String) uuidValue, start, end, count, walk, run,
                    distance, calorie, duration, speed);
        }

        private static Long requiredLong(final Object value) {
            return value instanceof Number ? ((Number) value).longValue() : null;
        }

        private static Integer requiredInt(final Object value) {
            if (!(value instanceof Number)) return null;
            final long number = ((Number) value).longValue();
            return number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? null : (int) number;
        }

        private static Float requiredFloat(final Object value) {
            if (!(value instanceof Number)) return null;
            final float number = ((Number) value).floatValue();
            return Float.isFinite(number) ? number : null;
        }

        public String getDatauuid() { return datauuid; }
        public long getStartTimeMs() { return startTimeMs; }
        public long getEndTimeMs() { return endTimeMs; }
        public int getCount() { return count; }
        public int getWalkStep() { return walkStep; }
        public int getRunStep() { return runStep; }
        public float getDistance() { return distance; }
        public float getCalorie() { return calorie; }
        public long getDuration() { return duration; }
        public float getSpeed() { return speed; }
    }
}
