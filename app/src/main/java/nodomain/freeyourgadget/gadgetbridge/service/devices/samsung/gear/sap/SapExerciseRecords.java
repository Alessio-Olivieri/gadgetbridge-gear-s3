package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Only the established CommonSync exercise fields; no raw record is retained or logged. */
public final class SapExerciseRecords {
    private static final String TABLE = "com.samsung.shealth.exercise";
    private static final int MAX_LIVE_DATA_CHARS = 1_048_576;

    private SapExerciseRecords() { }

    public static Result parse(final SapPedometerJsonProbe.Result decoded) {
        if (decoded == null || !decoded.isValid()) return new Result(Collections.emptyList(), 0, 0);
        final Object body = decoded.getTopLevel().get("body");
        if (!(body instanceof String)) return new Result(Collections.emptyList(), 0, 0);
        try {
            JSONArray request;
            try {
                request = new JSONArray((String) body);
            } catch (final JSONException directArrayFailed) {
                request = new JSONObject((String) body).optJSONArray("SYNC_REQUEST");
            }
            if (request == null) return new Result(Collections.emptyList(), 0, 0);
            final Map<String, Record> byUuid = new LinkedHashMap<>();
            int malformed = 0;
            int duplicate = 0;
            for (int i = 0; i < request.length(); i++) {
                final JSONObject element = request.optJSONObject(i);
                if (element == null) continue;
                final JSONArray rows = element.optJSONArray(TABLE);
                if (rows == null) continue;
                for (int j = 0; j < rows.length(); j++) {
                    final Record record = parseRow(rows.optJSONObject(j));
                    if (record == null) {
                        malformed++;
                    } else if (byUuid.putIfAbsent(record.uuid, record) != null) {
                        duplicate++;
                    }
                }
            }
            return new Result(new ArrayList<>(byUuid.values()), malformed, duplicate);
        } catch (final JSONException | RuntimeException ignored) {
            return new Result(Collections.emptyList(), 1, 0);
        }
    }

    private static Record parseRow(final JSONObject row) {
        if (row == null) return null;
        final Object uuidValue = row.opt("datauuid");
        if (!(uuidValue instanceof String)) return null;
        final String uuid = (String) uuidValue;
        final Long start = integer(row.opt("start_time"));
        final Long end = integer(row.opt("end_time"));
        final Long duration = integer(row.opt("duration"));
        if (uuid.isBlank() || uuid.length() > 128 || start == null || end == null || duration == null
                || start <= 0 || end < start || duration < 0) return null;
        final List<Point> points = new ArrayList<>();
        final Object live = row.opt("live_data");
        if (live instanceof String && ((String) live).length() <= MAX_LIVE_DATA_CHARS) {
            try {
                final JSONArray entries = new JSONArray((String) live);
                for (int i = 0; i < entries.length(); i++) {
                    final JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) continue;
                    final Long time = integer(entry.opt("start_time"));
                    final Double bpm = finite(entry.opt("heart_rate"));
                    if (time != null && bpm != null && time >= start && time <= end
                            && bpm >= 1 && bpm <= 250) {
                        points.add(new Point(time, bpm));
                    }
                }
            } catch (final JSONException ignored) {
                // A malformed optional series must not discard an otherwise valid workout.
            }
        }
        points.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return new Record(uuid, start, end, duration, integer(row.opt("exercise_type")),
                numbers(row), points);
    }

    private static Map<String, Double> numbers(final JSONObject row) {
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final String key : new String[]{"calorie", "total_calorie", "distance", "count", "count_type",
                "max_speed", "mean_speed", "mean_heart_rate", "min_heart_rate", "max_heart_rate",
                "heart_rate_sample_count", "completion_status", "source_type"}) {
            final Double value = finite(row.opt(key));
            if (value != null) out.put(key, value);
        }
        return out;
    }

    private static Double finite(final Object value) {
        if (!(value instanceof Number)) return null;
        final double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : null;
    }

    private static Long integer(final Object value) {
        final Double number = finite(value);
        if (number == null || number < Long.MIN_VALUE || number > Long.MAX_VALUE
                || number != Math.rint(number)) return null;
        return number.longValue();
    }

    public static final class Result {
        public final List<Record> records;
        public final int malformedCount;
        public final int duplicateCount;

        private Result(final List<Record> records, final int malformedCount, final int duplicateCount) {
            this.records = records;
            this.malformedCount = malformedCount;
            this.duplicateCount = duplicateCount;
        }
    }

    public static final class Record {
        public final String uuid;
        public final long startMs;
        public final long endMs;
        public final long durationMs;
        public final Long exerciseType;
        public final Map<String, Double> numbers;
        public final List<Point> heartRatePoints;

        private Record(final String uuid, final long startMs, final long endMs, final long durationMs,
                       final Long exerciseType, final Map<String, Double> numbers, final List<Point> heartRatePoints) {
            this.uuid = uuid;
            this.startMs = startMs;
            this.endMs = endMs;
            this.durationMs = durationMs;
            this.exerciseType = exerciseType;
            this.numbers = Collections.unmodifiableMap(numbers);
            this.heartRatePoints = Collections.unmodifiableList(heartRatePoints);
        }

        public Double number(final String key) { return numbers.get(key); }
    }

    public static final class Point {
        public final long timeMs;
        public final double bpm;

        private Point(final long timeMs, final double bpm) {
            this.timeMs = timeMs;
            this.bpm = bpm;
        }
    }
}
