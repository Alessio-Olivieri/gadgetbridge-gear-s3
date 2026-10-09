package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapExerciseRecords;

/** Standard workout summaries, keyed by the Samsung source UUID in compact raw summary data. */
public final class GearS3ExerciseImporter {
    private static final String SOURCE = "gear-s3-exercise-v1";

    private GearS3ExerciseImporter() { }

    public static int activityKind(final Long samsungType) {
        return samsungType != null && samsungType == 1002L
                ? ActivityKind.RUNNING.getCode() : ActivityKind.UNKNOWN.getCode();
    }

    @NonNull
    public static ActivitySummaryData summaryData(final SapExerciseRecords.Record record) {
        final ActivitySummaryData data = new ActivitySummaryData();
        data.add(ActivitySummaryEntries.ACTIVE_SECONDS, record.durationMs / 1000.0,
                ActivitySummaryEntries.UNIT_SECONDS, true);
        add(data, ActivitySummaryEntries.CALORIES_BURNT, record.number("calorie"), ActivitySummaryEntries.UNIT_KCAL);
        add(data, ActivitySummaryEntries.CALORIES_TOTAL, record.number("total_calorie"), ActivitySummaryEntries.UNIT_KCAL);
        add(data, ActivitySummaryEntries.DISTANCE_METERS, record.number("distance"), ActivitySummaryEntries.UNIT_METERS);
        add(data, ActivitySummaryEntries.SPEED_AVG, record.number("mean_speed"), ActivitySummaryEntries.UNIT_METERS_PER_SECOND);
        add(data, ActivitySummaryEntries.SPEED_MAX, record.number("max_speed"), ActivitySummaryEntries.UNIT_METERS_PER_SECOND);
        add(data, ActivitySummaryEntries.HR_AVG, record.number("mean_heart_rate"), ActivitySummaryEntries.UNIT_BPM);
        add(data, ActivitySummaryEntries.HR_MIN, record.number("min_heart_rate"), ActivitySummaryEntries.UNIT_BPM);
        add(data, ActivitySummaryEntries.HR_MAX, record.number("max_heart_rate"), ActivitySummaryEntries.UNIT_BPM);

        final Double count = record.number("count");
        final Double countType = record.number("count_type");
        if (count != null && countType != null) {
            // Field 30001 contains stride, not step count.
            if (countType == 30001) add(data, ActivitySummaryEntries.STRIDE_TOTAL, count, ActivitySummaryEntries.UNIT_NONE);
            else if (countType == 30002) add(data, ActivitySummaryEntries.STROKES, count, ActivitySummaryEntries.UNIT_STROKES);
            else if (countType == 30004) add(data, ActivitySummaryEntries.REPETITIONS, count, ActivitySummaryEntries.UNIT_REPS);
            else add(data, "gear_s3_count", count, ActivitySummaryEntries.UNIT_NONE);
        } else if (count != null) add(data, "gear_s3_count", count, ActivitySummaryEntries.UNIT_NONE);
        add(data, "gear_s3_count_type", countType, ActivitySummaryEntries.UNIT_NONE);
        add(data, "gear_s3_source_type", record.number("source_type"), ActivitySummaryEntries.UNIT_NONE);
        add(data, "gear_s3_completion_status", record.number("completion_status"), ActivitySummaryEntries.UNIT_NONE);
        add(data, "gear_s3_hr_sensor_sample_count", record.number("heart_rate_sample_count"), ActivitySummaryEntries.UNIT_NONE);
        return data;
    }

    private static void add(final ActivitySummaryData data, final String key, final Double value, final String unit) {
        if (value != null) data.add(key, value, unit, true);
    }

    /** Store only identity and measured HR points, never the full private CommonSync row. */
    @NonNull
    public static byte[] rawSummary(final SapExerciseRecords.Record record) throws JSONException {
        final JSONObject object = new JSONObject();
        object.put("source", SOURCE);
        object.put("datauuid", record.uuid);
        final JSONArray points = new JSONArray();
        for (final SapExerciseRecords.Point point : record.heartRatePoints) {
            points.put(new JSONArray().put(point.timeMs).put(point.bpm));
        }
        object.put("heart_rate", points);
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static JSONObject storedData(final BaseActivitySummary summary) {
        final byte[] raw = summary.getRawSummaryData();
        if (raw == null || raw.length == 0 || raw.length > 1_048_576) return null;
        try {
            final JSONObject data = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            return SOURCE.equals(data.optString("source")) ? data : null;
        } catch (final JSONException ignored) {
            return null;
        }
    }

    public static final class Outcome {
        public final int inserted;
        public final int updated;
        public final int unchanged;
        public final boolean success;

        private Outcome(final int inserted, final int updated, final int unchanged, final boolean success) {
            this.inserted = inserted;
            this.updated = updated;
            this.unchanged = unchanged;
            this.success = success;
        }

        public boolean changed() { return inserted + updated > 0; }
    }

    public static Outcome persist(@NonNull final GBDevice device, @NonNull final List<SapExerciseRecords.Record> records) {
        if (records.isEmpty()) return new Outcome(0, 0, 0, true);
        try (DBHandler db = GBApplication.acquireDB()) {
            return persist(db.getDaoSession(), DBHelper.getDevice(device, db.getDaoSession()),
                    DBHelper.getUser(db.getDaoSession()), records);
        } catch (final Exception ignored) {
            return new Outcome(0, 0, 0, false);
        }
    }

    /** Package-visible for database-backed regression tests. */
    static Outcome persist(final DaoSession session, final Device device, final User user,
                           final List<SapExerciseRecords.Record> records) throws JSONException {
        final BaseActivitySummaryDao dao = session.getBaseActivitySummaryDao();
        final Map<String, BaseActivitySummary> existing = new HashMap<>();
        final List<BaseActivitySummary> summaries = dao.queryBuilder()
                .where(BaseActivitySummaryDao.Properties.DeviceId.eq(device.getId()),
                        BaseActivitySummaryDao.Properties.UserId.eq(user.getId())).list();
        for (final BaseActivitySummary summary : summaries) {
            final JSONObject stored = storedData(summary);
            if (stored != null) existing.put(stored.optString("datauuid"), summary);
        }
        int inserted = 0, updated = 0, unchanged = 0;
        for (final SapExerciseRecords.Record record : records) {
            BaseActivitySummary summary = existing.get(record.uuid);
            final boolean isNew = summary == null;
            if (isNew) {
                summary = new BaseActivitySummary();
                summary.setDevice(device);
                summary.setUser(user);
            }
            final byte[] raw = rawSummary(record);
            final String details = summaryData(record).toJson();
            final int kind = activityKind(record.exerciseType);
            if (!isNew && summary.getStartTime().getTime() == record.startMs
                    && summary.getEndTime().getTime() == record.endMs
                    && summary.getActivityKind() == kind
                    && Objects.equals(summary.getSummaryData(), details)
                    && Arrays.equals(summary.getRawSummaryData(), raw)) {
                unchanged++;
                continue;
            }
            summary.setStartTime(new Date(record.startMs));
            summary.setEndTime(new Date(record.endMs));
            summary.setActivityKind(kind);
            summary.setSummaryData(details);
            summary.setRawSummaryData(raw);
            dao.insertOrReplace(summary);
            existing.put(record.uuid, summary);
            if (isNew) inserted++; else updated++;
        }
        return new Outcome(inserted, updated, unchanged, true);
    }
}
