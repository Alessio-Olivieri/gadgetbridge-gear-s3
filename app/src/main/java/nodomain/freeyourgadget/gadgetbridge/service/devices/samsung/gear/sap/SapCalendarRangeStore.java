package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Gear-requested windows, kept in the owning device's preferences across support instances. */
public final class SapCalendarRangeStore {
    public static final String RANGES_KEY = "gear_s3_calendar_gear_ranges_v1";
    static final String SEQUENCE_KEY = "gear_s3_calendar_sequence_v1";
    static final String SEQUENCE_SEEN_KEY = "gear_s3_calendar_sequence_seen_v1";
    private static final String REFRESH_PENDING_KEY = "gear_s3_calendar_refresh_pending_v1";
    private static final String REPLACE_ON_NEXT_RANGE_KEY = "gear_s3_calendar_replace_on_next_range_v1";
    private static final int MAX_STORED_RANGES = 64;
    private final SharedPreferences preferences;

    public SapCalendarRangeStore(final SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public State load() {
        final List<SapCalendarCodec.RequestRange> ranges = new ArrayList<>();
        final List<String> zones = new ArrayList<>();
        String raw;
        try {
            raw = preferences.getString(RANGES_KEY, "[]");
        } catch (final ClassCastException ignored) {
            raw = "[]";
        }
        try {
            final JSONArray values = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < Math.min(values.length(), MAX_STORED_RANGES); i++) {
                try {
                    final JSONObject value = values.getJSONObject(i);
                    final SapCalendarCodec.RequestRange range = SapCalendarCodec.parseRange(value);
                    final String zone = value.getString("zone");
                    final String reminder = value.getString("reminder");
                    if (range.maxTextLength < 0 || !isKnownZone(zone)
                            || !("on".equals(reminder) || "off".equals(reminder))) continue;
                    ranges.add(range);
                    zones.add(zone);
                } catch (final JSONException | RuntimeException ignored) {
                    // A damaged entry must not prevent restoration of other stored windows.
                }
            }
        } catch (final JSONException | RuntimeException ignored) {
            // Malformed preference is not a Gear request; never synthesize a replacement range.
        }
        return new State(ranges, zones, Math.max(0, safeLong(SEQUENCE_KEY)),
                safeBoolean(SEQUENCE_SEEN_KEY), safeBoolean(REFRESH_PENDING_KEY),
                safeBoolean(REPLACE_ON_NEXT_RANGE_KEY));
    }

    private long safeLong(final String key) {
        try { return preferences.getLong(key, 0); }
        catch (final ClassCastException ignored) { return 0; }
    }

    private boolean safeBoolean(final String key) {
        try { return preferences.getBoolean(key, false); }
        catch (final ClassCastException ignored) { return false; }
    }

    private static boolean isKnownZone(final String zone) {
        return zone != null && !zone.isEmpty() && zone.length() <= 128
                && TimeZone.getTimeZone(zone).getID().equals(zone);
    }

    /** Caller supplies only windows parsed from actual Gear range-bearing requests. */
    public boolean saveRanges(final List<SapCalendarCodec.RequestRange> ranges, final List<String> zones) {
        final String encoded = encodeRanges(ranges, zones);
        if (encoded == null) return false;
        return preferences.edit().putString(RANGES_KEY, encoded)
                .putBoolean(REPLACE_ON_NEXT_RANGE_KEY, false).commit();
    }

    /** Explicit debug migration of Gear request windows after in-memory state was lost. */
    boolean importObservedRanges(final List<SapCalendarCodec.RequestRange> ranges, final List<String> zones) {
        final State previous = load();
        if (!previous.ranges.isEmpty() || previous.replaceOnNextRange) return false;
        final String encoded = encodeRanges(ranges, zones);
        if (encoded == null || ranges.isEmpty()) return false;
        // Initialize the calendar request/response sequence at zero for imported windows.
        // Commit all state together so a failed import cannot leave a partial sync marker.
        return preferences.edit().putString(RANGES_KEY, encoded)
                .putLong(SEQUENCE_KEY, 0).putBoolean(SEQUENCE_SEEN_KEY, true)
                .putBoolean(REFRESH_PENDING_KEY, true)
                .putBoolean(REPLACE_ON_NEXT_RANGE_KEY, false).commit();
    }

    private static String encodeRanges(final List<SapCalendarCodec.RequestRange> ranges,
                                       final List<String> zones) {
        if (ranges == null || zones == null || ranges.size() != zones.size()
                || ranges.size() > MAX_STORED_RANGES) return null;
        final JSONArray values = new JSONArray();
        try {
            for (int i = 0; i < ranges.size(); i++) {
                final SapCalendarCodec.RequestRange range = ranges.get(i);
                final String zone = zones.get(i);
                if (range == null || range.start < 0 || range.end <= range.start
                        || range.maxTextLength < 0 || range.maxEvents < 1
                        || range.maxAllDay < 0 || range.maxAllDay > range.maxEvents
                        || !isKnownZone(zone)) return null;
                values.put(new JSONObject().put("startTime", range.start).put("endTime", range.end)
                        .put("maxTextLength", range.maxTextLength).put("maxEventCnt", range.maxEvents)
                        .put("maxAlldayEventCnt", range.maxAllDay)
                        .put("reminder", range.reminders ? "on" : "off").put("zone", zone));
            }
        } catch (final JSONException e) {
            return null;
        }
        return values.toString();
    }

    public boolean saveProgress(final long sequence, final boolean sequenceSeen) {
        return preferences.edit().putLong(SEQUENCE_KEY, sequence)
                .putBoolean(SEQUENCE_SEEN_KEY, sequenceSeen).commit();
    }

    public boolean setRefreshPending(final boolean pending) {
        return preferences.edit().putBoolean(REFRESH_PENDING_KEY, pending).commit();
    }

    public boolean markFullSyncRequested() {
        return preferences.edit().putBoolean(REPLACE_ON_NEXT_RANGE_KEY, true)
                .putLong(SEQUENCE_KEY, 0).putBoolean(SEQUENCE_SEEN_KEY, false).commit();
    }

    public static final class State {
        public final List<SapCalendarCodec.RequestRange> ranges;
        public final List<String> zones;
        public final long sequence;
        public final boolean sequenceSeen;
        public final boolean refreshPending;
        public final boolean replaceOnNextRange;

        private State(final List<SapCalendarCodec.RequestRange> ranges, final List<String> zones,
                      final long sequence, final boolean sequenceSeen, final boolean refreshPending,
                      final boolean replaceOnNextRange) {
            this.ranges = ranges;
            this.zones = zones;
            this.sequence = sequence;
            this.sequenceSeen = sequenceSeen;
            this.refreshPending = refreshPending;
            this.replaceOnNextRange = replaceOnNextRange;
        }

        /** A saved Gear window permits reconciliation even if the process missed an observer callback. */
        public boolean shouldReconcileOnConnect() {
            return sequenceSeen && !ranges.isEmpty();
        }
    }
}
