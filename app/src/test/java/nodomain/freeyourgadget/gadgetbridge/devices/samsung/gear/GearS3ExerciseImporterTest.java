package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapExerciseRecords;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GearS3ExerciseImporterTest extends TestBase {
    private static final long START = 1790841184910L;
    private static final long END = 1790841531440L;
    private Device dbDevice;
    private User dbUser;
    private GBDevice gbDevice;

    @Before
    public void setUpDevice() {
        gbDevice = createDummyGDevice("00:00:00:00:00:39");
        dbDevice = DBHelper.getDevice(gbDevice, daoSession);
        dbUser = DBHelper.getUser(daoSession);
    }

    private static JSONObject running(final JSONArray live) throws Exception {
        return new JSONObject().put("datauuid", "00000000-0000-0000-0000-000000000001")
                .put("start_time", START).put("end_time", END).put("duration", 337879)
                .put("exercise_type", 1002).put("source_type", 1).put("completion_status", 1)
                .put("calorie", 42.5).put("total_calorie", 43.0).put("distance", 850.0)
                .put("count", 674).put("count_type", 30001).put("mean_speed", 2.5)
                .put("max_speed", 4.0).put("heart_rate_sample_count", 310)
                .put("mean_heart_rate", 174).put("min_heart_rate", 117)
                .put("max_heart_rate", 194).put("live_data", live.toString());
    }

    private static JSONArray sixPoints() throws Exception {
        final JSONArray points = new JSONArray();
        for (int i = 0; i < 6; i++) {
            points.put(new JSONObject().put("start_time", START + i * 60000L)
                    .put("heart_rate", 117 + i * 12));
        }
        return points;
    }

    private static SapExerciseRecords.Result parse(final JSONObject... rows) throws Exception {
        final JSONArray array = new JSONArray();
        for (final JSONObject row : rows) array.put(row);
        final String body = new JSONArray().put(new JSONObject()
                .put("com.samsung.shealth.exercise", array)).toString();
        final SapPedometerJsonProbe.Result decoded = SapPedometerJsonProbe.parse(new JSONObject()
                .put("message", "REQUEST").put("body", body).toString()
                .getBytes(StandardCharsets.UTF_8));
        return SapExerciseRecords.parse(decoded);
    }

    @Test
    public void runningSummaryAndSixMeasuredPointsKeepExplicitDuration() throws Exception {
        final SapExerciseRecords.Result parsed = parse(running(sixPoints()));
        assertEquals(1, parsed.records.size());
        final SapExerciseRecords.Record row = parsed.records.get(0);
        assertEquals(ActivityKind.RUNNING.getCode(), GearS3ExerciseImporter.activityKind(row.exerciseType));
        assertEquals(337879L, row.durationMs);
        assertEquals(6, row.heartRatePoints.size());
        assertEquals(START, row.heartRatePoints.get(0).timeMs);
        final ActivitySummaryData data = GearS3ExerciseImporter.summaryData(row);
        assertEquals(337.879, data.getNumber(ActivitySummaryEntries.ACTIVE_SECONDS, 0).doubleValue(), 0.001);
        assertEquals(174.0, data.getNumber(ActivitySummaryEntries.HR_AVG, 0).doubleValue(), 0.001);
        assertEquals(117.0, data.getNumber(ActivitySummaryEntries.HR_MIN, 0).doubleValue(), 0.001);
        assertEquals(194.0, data.getNumber(ActivitySummaryEntries.HR_MAX, 0).doubleValue(), 0.001);
        assertEquals(310.0, data.getNumber("gear_s3_hr_sensor_sample_count", 0).doubleValue(), 0.001);
        assertEquals(674.0, data.getNumber(ActivitySummaryEntries.STRIDE_TOTAL, 0).doubleValue(), 0.001);
        assertEquals(42.5, data.getNumber(ActivitySummaryEntries.CALORIES_BURNT, 0).doubleValue(), 0.001);
        assertEquals(43.0, data.getNumber(ActivitySummaryEntries.CALORIES_TOTAL, 0).doubleValue(), 0.001);
    }

    @Test
    public void replayIsIdempotentAndRevisionUpdatesSameWorkout() throws Exception {
        final SapExerciseRecords.Record first = parse(running(sixPoints())).records.get(0);
        final GearS3ExerciseImporter.Outcome inserted = GearS3ExerciseImporter.persist(
                daoSession, dbDevice, dbUser, List.of(first));
        assertTrue(inserted.changed());
        assertEquals(1, inserted.inserted);
        final BaseActivitySummary summary = daoSession.getBaseActivitySummaryDao().loadAll().get(0);
        final long id = summary.getId();
        assertEquals(END, summary.getEndTime().getTime());
        assertEquals(6, new GearS3ExerciseTrackProvider().getActivityTrack(summary).getAllPoints().size());
        final GearS3ExerciseImporter.Outcome replay = GearS3ExerciseImporter.persist(
                daoSession, dbDevice, dbUser, List.of(first));
        assertFalse(replay.changed());
        assertEquals(1, replay.unchanged);
        final JSONObject changed = running(sixPoints()).put("duration", 330000).put("mean_heart_rate", 170);
        final GearS3ExerciseImporter.Outcome updated = GearS3ExerciseImporter.persist(
                daoSession, dbDevice, dbUser, parse(changed).records);
        assertEquals(1, updated.updated);
        assertTrue(updated.changed()); // The CommonSync completion flag includes this result.
        assertEquals(1, daoSession.getBaseActivitySummaryDao().count());
        final BaseActivitySummary revised = daoSession.getBaseActivitySummaryDao().load(id);
        assertEquals(id, revised.getId().longValue());
        assertEquals(330.0, ActivitySummaryData.fromJson(revised.getSummaryData())
                .getNumber(ActivitySummaryEntries.ACTIVE_SECONDS, 0).doubleValue(), 0.001);
    }

    @Test
    public void emptyNonHrAndMalformedOptionalLiveDataDoNotCreateHr() throws Exception {
        final JSONObject empty = running(new JSONArray());
        final JSONObject nonHr = running(new JSONArray().put(new JSONObject()
                .put("start_time", START).put("speed", 2.2)));
        final JSONObject malformed = running(new JSONArray().put(new JSONObject()
                .put("heart_rate", "private").put("start_time", START)));
        for (final JSONObject row : new JSONObject[]{empty, nonHr, malformed}) {
            assertTrue(parse(row).records.get(0).heartRatePoints.isEmpty());
        }
        assertEquals(0, parse(running(new JSONArray()).put("live_data", "not-json"))
                .records.get(0).heartRatePoints.size());
    }

    @Test
    public void malformedIdentityIsRejectedAndRepeatedSourceUuidIsCounted() throws Exception {
        final JSONObject valid = running(new JSONArray());
        final JSONObject nullUuid = running(new JSONArray()).put("datauuid", JSONObject.NULL);
        final JSONObject missingDuration = running(new JSONArray());
        missingDuration.remove("duration");
        final SapExerciseRecords.Result parsed = parse(valid, nullUuid, missingDuration,
                new JSONObject(valid.toString()));
        assertEquals(1, parsed.records.size());
        assertEquals(2, parsed.malformedCount);
        assertEquals(1, parsed.duplicateCount);
    }

    @Test
    public void unknownTypeAndMissingHeartRateFieldsStayGeneric() throws Exception {
        final JSONObject row = new JSONObject().put("datauuid", "00000000-0000-0000-0000-000000000002")
                .put("start_time", START).put("end_time", END).put("duration", 337879)
                .put("exercise_type", 9999).put("live_data", "[]");
        final SapExerciseRecords.Record parsed = parse(row).records.get(0);
        assertEquals(ActivityKind.UNKNOWN.getCode(), GearS3ExerciseImporter.activityKind(parsed.exerciseType));
        assertFalse(GearS3ExerciseImporter.summaryData(parsed).has(ActivitySummaryEntries.HR_AVG));
        GearS3ExerciseImporter.persist(daoSession, dbDevice, dbUser, List.of(parsed));
        assertNull(new GearS3ExerciseTrackProvider().getActivityTrack(
                daoSession.getBaseActivitySummaryDao().loadAll().get(0)));
    }

    @Test
    public void fractionalSourceBpmIsRetainedWhileChartUsesIntegerBpm() throws Exception {
        final JSONArray points = new JSONArray().put(new JSONObject()
                .put("start_time", START).put("heart_rate", 117.5));
        final SapExerciseRecords.Record row = parse(running(points)).records.get(0);
        assertEquals(117.5, row.heartRatePoints.get(0).bpm, 0.001);
        GearS3ExerciseImporter.persist(daoSession, dbDevice, dbUser, List.of(row));
        final BaseActivitySummary summary = daoSession.getBaseActivitySummaryDao().loadAll().get(0);
        assertEquals(117.5, GearS3ExerciseImporter.storedData(summary)
                .getJSONArray("heart_rate").getJSONArray(0).getDouble(1), 0.001);
        assertEquals(118, new GearS3ExerciseTrackProvider().getActivityTrack(summary)
                .getAllPoints().get(0).getHeartRate());
    }

    @Test
    public void unrelatedNormalHrRowDoesNotEnterWorkoutTrack() throws Exception {
        final SapExerciseRecords.Record row = parse(running(sixPoints())).records.get(0);
        GearS3ExerciseImporter.persist(daoSession, dbDevice, dbUser, List.of(row));
        final GearS3HeartRateSample ordinary = new GearS3HeartRateSample();
        ordinary.setDatauuid("00000000-0000-0000-0000-000000000099");
        ordinary.setTimestamp(1790841629287L);
        ordinary.setEndTimeMs(1790841629287L);
        ordinary.setHeartRate(65);
        assertTrue(new GearS3HeartRateSampleProvider(gbDevice, daoSession)
                .persistSamples(List.of(ordinary), getContext()));
        final BaseActivitySummary summary = daoSession.getBaseActivitySummaryDao().loadAll().get(0);
        assertNotNull(new GearS3ExerciseTrackProvider().getActivityTrack(summary));
        assertEquals(6, new GearS3ExerciseTrackProvider().getActivityTrack(summary).getAllPoints().size());
        assertTrue(1790841629287L > summary.getEndTime().getTime());
        assertEquals(1, daoSession.getGearS3HeartRateSampleDao().count());
    }
}
