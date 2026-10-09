package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SapPedometerJsonProbeTest {
    @Test
    public void decodesUtf8AndIdentifiesGearSyncCommand() {
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(("{\"requestMessage\":\"Gear_Sync_Cmd\",\"device\":\"Gear S\",\"version\":3.01,\"requestCode\":7} ").getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isValid());
        assertEquals("Gear_Sync_Cmd", result.getRequestMessage());
        assertEquals("Gear S", result.getDevice());
        assertEquals(3.01d, ((Number) result.getVersion()).doubleValue(), 0.0d);
        assertEquals(7, ((Number) result.getRequestCode()).intValue());
    }

    @Test
    public void parsesHealthResponseAndEveryPedometerFieldPresence() {
        final String json = "{\"responseMessage\":\"Gear_SHealth_Sync\",\"responseCode\":8,\"version\":3.01,\"device\":\"Gear S\",\"LastReceiveTime\":9,\"LastSendTime\":10,\"pedometerResults\":{\"SPedoResult\":[{\"mtimeCreated\":1,\"mWalkStepCount\":2,\"mRunStepCount\":3,\"mTotalStepCount\":4,\"mDistance\":5.5,\"mCalory\":6.5,\"mSpeed\":7.5,\"mHealthyStep\":8,\"mActiveTime\":9,\"devicePKId\":\"pk\",\"mCRUD\":10,\"mUpStep\":11,\"mDownStep\":12,\"mVersionCode\":13}]}}";
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(json.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isValid());
        assertEquals("Gear_SHealth_Sync", result.getResponseMessage());
        assertEquals(1, result.getRecords().size());
        for (SapPedometerJsonProbe.Field field : result.getRecords().get(0).getFields().values()) assertTrue(field.isPresent());
        assertEquals(4, ((Number) result.getRecords().get(0).getFields().get("mTotalStepCount").getValue()).intValue());
    }

    @Test
    public void malformedJsonIsIgnoredAsProbeInput() {
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(new byte[]{(byte) 0xc3, 0x28});
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("UTF-8"));
    }

    @Test
    public void malformedPedometerListDoesNotThrow() {
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse("{\"responseMessage\":\"Gear_SHealth_Sync\",\"pedometerResults\":{\"SPedoResult\":{}}}".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isValid());
    }

    @Test
    public void inspectsV451CommonSyncAndBuildsExactSuccessEnvelope() throws org.json.JSONException {
        final String body = "{\"SYNC_REQUEST\":[{\"device_info\":[{\"device\":\"Gear S3\",\"version\":4.51}]},{\"message_info\":[{\"action\":\"SYNC_DATA\",\"start_time\":1,\"end_time\":2,\"last_sync_time\":0,\"is_last_chunk\":true}]}]}";
        final String outer = "{\"message\":\"REQUEST\",\"sender\":\"com.samsung.tizengear.app.shealth.wearable.syncmanager\",\"receiver\":\"com.samsung.android.app.shealth.wearable.syncmanager\",\"version\":4.51,\"device\":10038,\"sequence_num\":8,\"type\":\"DATA\",\"body\":" + org.json.JSONObject.quote(body) + "}";
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(outer.getBytes(StandardCharsets.UTF_8));
        final SapPedometerJsonProbe.CommonSync sync = SapPedometerJsonProbe.inspectCommonSync(result);
        assertTrue(result.isValid());
        assertEquals("SYNC_DATA", sync.getAction());
        assertEquals(2, sync.getRequestCount());
        assertEquals(8, ((Number) sync.getSequence()).intValue());
        assertEquals(1, ((Number) sync.getStartTime()).intValue());

        final String response = new String(SapPedometerJsonProbe.commonSyncSuccessResponse(8), StandardCharsets.UTF_8);
        final org.json.JSONObject responseObject = new org.json.JSONObject(response);
        assertEquals("RESPONSE", responseObject.getString("message"));
        assertEquals(8, responseObject.getInt("sequence_num"));
        assertEquals("DATA", responseObject.getString("type"));
        final org.json.JSONArray responseArray = new org.json.JSONArray(responseObject.getString("body"));
        assertEquals("SYNC_DATA", responseArray.getJSONObject(1).getJSONArray("message_info").getJSONObject(0).getString("action"));
        assertEquals("SUCCESS", responseArray.getJSONObject(1).getJSONArray("message_info").getJSONObject(0).getString("result"));
    }

    @Test
    public void buildsRequestDataWithTheSameDirectArrayBodyAndSequence() throws org.json.JSONException {
        final String response = new String(SapPedometerJsonProbe.commonSyncSuccessResponse(21, "REQUEST_DATA"), StandardCharsets.UTF_8);
        final org.json.JSONObject responseObject = new org.json.JSONObject(response);
        assertEquals("RESPONSE", responseObject.getString("message"));
        assertEquals(21, responseObject.getInt("sequence_num"));
        assertEquals("DATA", responseObject.getString("type"));

        final String body = responseObject.getString("body");
        final org.json.JSONArray responseArray = new org.json.JSONArray(body);
        assertEquals(2, responseArray.length());
        assertEquals("REQUEST_DATA", responseArray.getJSONObject(1).getJSONArray("message_info")
                .getJSONObject(0).getString("action"));
        assertEquals("SUCCESS", responseArray.getJSONObject(1).getJSONArray("message_info")
                .getJSONObject(0).getString("result"));
        assertFalse(body.trim().startsWith("{"));
        assertFalse(body.contains("SYNC_RESPONSE"));
    }

    @Test
    public void mixedV451HealthTablesKeepFunctionalSelectionAndNumericValues() throws org.json.JSONException {
        final JSONObject firstStep = new JSONObject()
                .put("datauuid", "step-001")
                .put("start_time", 1000L)
                .put("end_time", 2000L)
                .put("count", 12)
                .put("walk_step", 9)
                .put("run_step", 3)
                .put("distance", 4.5)
                .put("calorie", 1.25)
                .put("duration", 1000)
                .put("speed", 2.5)
                .put("update_time", 2100L)
                .put("create_time", 900L);
        final JSONObject secondStep = new JSONObject()
                .put("datauuid", "step-002")
                .put("start_time", 3000L)
                .put("end_time", 4000L)
                .put("count", 8);
        final JSONArray body = new JSONArray()
                .put(new JSONObject().put("com.samsung.shealth.tracker.pedometer_step_count",
                        new JSONArray().put(firstStep).put(secondStep)))
                .put(new JSONObject().put("com.samsung.shealth.tracker.heart_rate",
                        new JSONArray().put(new JSONObject().put("heart_rate", 70))))
                .put(new JSONObject().put("sleep_data",
                        new JSONArray().put(new JSONObject().put("sleep_status", "compressed"))));
        final String outer = new JSONObject()
                .put("message", "REQUEST")
                .put("body", body.toString())
                .toString();

        final SapPedometerJsonProbe.Result parsed = SapPedometerJsonProbe.parse(outer.getBytes(StandardCharsets.UTF_8));
        // Master removed the verbose table-summary API. Assert the surviving functional
        // mixed-table selection and original numeric values instead of restoring that dump.
        final SapPedometerJsonProbe.PedometerStepRecords steps = SapPedometerJsonProbe.parsePedometerStepRecords(parsed);
        assertEquals(1, steps.getRecords().size());
        assertEquals(1, steps.getMalformedCount());
        assertEquals(0, steps.getDuplicateCount());
        assertEquals("step-001", steps.getRecords().get(0).getDatauuid());
        assertEquals(1000L, steps.getRecords().get(0).getStartTimeMs());
        assertEquals(2000L, steps.getRecords().get(0).getEndTimeMs());
        assertEquals(12, steps.getRecords().get(0).getCount());
        assertEquals(4.5f, steps.getRecords().get(0).getDistance(), 0);
        final SapPedometerJsonProbe.HeartRateRecords heart = SapPedometerJsonProbe.parseHeartRateRecords(parsed);
        assertTrue(heart.getRecords().isEmpty());
        assertEquals(1, heart.getMalformedCount());
    }

    @Test
    public void parsesMinuteStepIntervalsAndSuppressesRepeatedUuidRows() throws org.json.JSONException {
        final JSONObject row = new JSONObject()
                .put("datauuid", "same-row")
                .put("start_time", 1790176980000L)
                .put("end_time", 1790177039999L)
                .put("count", 13)
                .put("walk_step", 13)
                .put("run_step", 0)
                .put("distance", 9.09)
                .put("calorie", 0.4)
                .put("duration", 8844)
                .put("speed", 1.027777777777778);
        final JSONArray body = new JSONArray()
                .put(new JSONObject().put("com.samsung.shealth.tracker.pedometer_step_count",
                        new JSONArray().put(row).put(new JSONObject(row.toString()))))
                .put(new JSONObject().put("message_info", new JSONArray()));
        final JSONObject outer = new JSONObject()
                .put("message", "REQUEST")
                .put("body", body.toString());

        final SapPedometerJsonProbe.PedometerStepRecords parsed = SapPedometerJsonProbe.parsePedometerStepRecords(
                SapPedometerJsonProbe.parse(outer.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, parsed.getRecords().size());
        assertEquals(0, parsed.getMalformedCount());
        assertEquals(1, parsed.getDuplicateCount());
        final SapPedometerJsonProbe.PedometerStepRecord record = parsed.getRecords().get(0);
        assertEquals(1790176980000L, record.getStartTimeMs());
        assertEquals(59999L, record.getEndTimeMs() - record.getStartTimeMs());
        assertEquals(13, record.getCount());
        assertEquals(13, record.getWalkStep());
        assertEquals(0, record.getRunStep());
    }

    @Test
    public void rejectsMalformedOrPartialStepRows() throws org.json.JSONException {
        final JSONArray body = new JSONArray().put(new JSONObject().put("pedometer_step_count", new JSONArray()
                .put(new JSONObject().put("datauuid", "missing-count").put("start_time", 1).put("end_time", 2))
                .put(new JSONObject().put("datauuid", "bad-time").put("start_time", 3).put("end_time", 2).put("count", 1))));
        final JSONObject outer = new JSONObject().put("message", "REQUEST").put("body", body.toString());

        final SapPedometerJsonProbe.PedometerStepRecords parsed = SapPedometerJsonProbe.parsePedometerStepRecords(
                SapPedometerJsonProbe.parse(outer.toString().getBytes(StandardCharsets.UTF_8)));
        assertTrue(parsed.getRecords().isEmpty());
        assertEquals(2, parsed.getMalformedCount());
    }

    @Test
    public void parsesSanitizedHeartRateRowsAndRejectsInvalidMeasurements() throws org.json.JSONException {
        final JSONObject valid = new JSONObject()
                .put("datauuid", "synthetic-heart-0001")
                .put("start_time", 1790176980123L)
                .put("end_time", 1790176981123L)
                .put("heart_rate", 72.5);
        final JSONObject missingRate = new JSONObject(valid.toString());
        missingRate.remove("heart_rate");
        final JSONArray rows = new JSONArray()
                .put(valid)
                .put(new JSONObject(valid.toString()))
                .put(new JSONObject(valid.toString()).put("datauuid", "zero").put("heart_rate", 0))
                .put(new JSONObject(valid.toString()).put("datauuid", "too-high").put("heart_rate", 251))
                .put(new JSONObject(valid.toString()).put("datauuid", "bad-time").put("start_time", 1790176980123.5))
                .put(missingRate.put("datauuid", "missing-rate"));
        final String body = new JSONArray().put(new JSONObject()
                .put("com.samsung.shealth.tracker.heart_rate", rows)).toString();
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(new JSONObject()
                .put("message", "REQUEST").put("body", body).toString().getBytes(StandardCharsets.UTF_8));

        final SapPedometerJsonProbe.HeartRateRecords parsed = SapPedometerJsonProbe.parseHeartRateRecords(result);
        assertEquals(1, parsed.getRecords().size());
        assertEquals(4, parsed.getMalformedCount());
        assertEquals(1, parsed.getDuplicateCount());
        assertEquals("synthetic-heart-0001", parsed.getRecords().get(0).getDatauuid());
        assertEquals(1790176980123L, parsed.getRecords().get(0).getStartTimeMs());
        assertEquals(1790176981123L, parsed.getRecords().get(0).getEndTimeMs());
        assertEquals(73, parsed.getRecords().get(0).getHeartRate());
    }
    @Test
    public void tableDiscoveryNeverStringifiesJsonContainers() throws Exception {
        final JSONArray records = new JSONArray() {
            @Override public String toString() { throw new AssertionError("serialized records during traversal"); }
        };
        records.put(new JSONObject().put("count", 3));
        final JSONObject wrapper = new JSONObject() {
            @Override public String toString() { throw new AssertionError("serialized object during traversal"); }
        };
        wrapper.put("table_name", "pedometer_step_count").put("records", records);
        final java.util.List<JSONArray> found = findTables(wrapper);
        assertEquals(1, found.size()); assertTrue(found.get(0) == records);
    }

    @Test
    public void tableDiscoveryDoesNotConvertUnrelatedScalarValues() throws Exception {
        final Number expensive = new Number() {
            @Override public int intValue() { return 1; }
            @Override public long longValue() { return 1; }
            @Override public float floatValue() { return 1; }
            @Override public double doubleValue() { return 1; }
            @Override public String toString() { throw new AssertionError("converted unrelated scalar"); }
        };
        final JSONObject wrapper = new JSONObject().put("timestamp", expensive).put("type", expensive)
                .put("pedometer_step_count", new JSONArray().put(new JSONObject().put("count", expensive)));
        assertEquals(1, findTables(wrapper).size());
    }

    @Test
    public void largeSyntheticTableStillProvidesEveryFunctionalPedometerRow() throws Exception {
        final JSONArray rows = new JSONArray();
        for (int i = 0; i < 1000; i++) rows.put(new JSONObject().put("datauuid", "synthetic-" + i)
                .put("start_time", 1700000000000L + i * 60000L).put("end_time", 1700000060000L + i * 60000L)
                .put("count", 3).put("walk_step", 2).put("run_step", 1)
                .put("distance", 1).put("calorie", 1).put("duration", 60000).put("speed", 1));
        final String body = new JSONArray().put(new JSONObject().put("table_name", "pedometer_step_count")
                .put("records", rows)).toString();
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(new JSONObject()
                .put("message", "REQUEST").put("body", body).toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(1000, SapPedometerJsonProbe.parsePedometerStepRecords(result).getRecords().size());
    }

    private static java.util.List<JSONArray> findTables(Object body) throws Exception {
        final java.lang.reflect.Method method = SapPedometerJsonProbe.class.getDeclaredMethod("findTableArrays",
                Object.class, String.class, java.util.List.class, int.class);
        method.setAccessible(true);
        final java.util.List<JSONArray> found = new java.util.ArrayList<>();
        method.invoke(null, body, "pedometer_step_count", found, 0);
        return found;
    }

}
