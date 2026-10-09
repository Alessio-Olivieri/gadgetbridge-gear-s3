package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

/**
 * Private per-device Android-side mirror, not an import of the watch's lossy DB.
 * The write-ahead state must be committed BEFORE sending anything that changes watch rows.
 * A trusted checkpoint and its application revision are published in one preference edit.
 * "Trusted" means protocol-completed or explicitly initialized through the debug bootstrap;
 * an application ACK alone is not independent proof of a watch SQLite commit.
 */
public final class SapCalendarMirrorStore {
    static final String MIRROR_KEY = "gear_s3_calendar_mirror_v1";
    static final String STATE_KEY = "gear_s3_calendar_mirror_state_v1";
    private static final String TRUSTED = "trusted";
    // Local resource bounds. Reject oversize mirrors; never truncate event comparison data.
    private static final int MAX_EVENTS = 100_000;
    private static final int MAX_JSON_CHARS = 16 * 1024 * 1024;
    private final SharedPreferences preferences;
    private boolean writeFailed;

    public SapCalendarMirrorStore(final SharedPreferences preferences) {
        this.preferences = preferences;
    }

    /** Retains the old checkpoint for diagnosis, but makes it ineligible for restoration. */
    public boolean invalidate(final String reason) {
        if (reason == null || TRUSTED.equals(reason)) throw new IllegalArgumentException("Invalid mirror phase");
        final boolean saved = preferences.edit().putString(STATE_KEY, reason).commit();
        if (!saved) writeFailed = true;
        return saved && !writeFailed;
    }

    /** Diagnostic only; no preference write or private mirror text is exposed. */
    public String debugBootstrapRefusal() {
        if (!BuildConfig.DEBUG || writeFailed) return "support-unavailable";
        try {
            final String state = preferences.getString(STATE_KEY, "");
            if (TRUSTED.equals(state)) return "mirror-not-unknown";
            if (!"".equals(state)) return "interrupted-operation";
            if (!"".equals(preferences.getString(MIRROR_KEY, ""))) return "mirror-not-unknown";
            return null;
        } catch (final RuntimeException ignored) {
            return "mirror-state-invalid";
        }
    }

    public String restore(final SapCalendarSnapshot snapshot, final String phoneZone) {
        snapshot.clear();
        try {
            if (writeFailed) return "storage-failure";
            if (!TRUSTED.equals(preferences.getString(STATE_KEY, "missing"))) return "not-committed";
            final SapCalendarRangeStore.State state = new SapCalendarRangeStore(preferences).load();
            if (!state.sequenceSeen || state.replaceOnNextRange || state.ranges.isEmpty()) return "incomplete-progress";
            // Range restoration tolerates bad individual preferences; mirror trust must not.
            final long persistedSequence = preferences.getLong(SapCalendarRangeStore.SEQUENCE_KEY, -1);
            if (persistedSequence < 0 || persistedSequence != state.sequence
                    || !preferences.getBoolean(SapCalendarRangeStore.SEQUENCE_SEEN_KEY, false)
                    || preferences.getBoolean("gear_s3_calendar_replace_on_next_range_v1", true)) {
                return "inconsistent-progress";
            }
            final String raw = preferences.getString(MIRROR_KEY, "");
            if (raw == null || raw.length() > MAX_JSON_CHARS) return "invalid-size";
            final JSONObject root = new JSONObject(raw);
            if (root.getInt("version") != 1 || root.getLong("sequence") != state.sequence
                    || !phoneZone.equals(root.getString("phoneZone"))
                    || !root.getString("ranges").equals(preferences.getString(SapCalendarRangeStore.RANGES_KEY, ""))) {
                return "inconsistent-progress";
            }
            if (new JSONArray(root.getString("ranges")).length() != state.ranges.size()) return "inconsistent-progress";
            final JSONArray rows = root.getJSONArray("events");
            if (rows.length() > MAX_EVENTS) return "invalid-size";
            final List<CalendarEvent> events = new ArrayList<>();
            final Set<String> identities = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                final CalendarEvent event = decode(rows.getJSONObject(i));
                if (!identities.add(SapCalendarSnapshot.key(event))) return "duplicate-identity";
                events.add(event);
            }
            snapshot.restoreTrusted(events);
            return "debug-bootstrap-watch-db-verified-v1".equals(root.optString("provenance"))
                    ? "trusted-debug-bootstrap" : "trusted";
        } catch (final JSONException | RuntimeException ignored) {
            // Never log the private serialized mirror or exception text containing its contents.
            return "corrupt";
        }
    }

    /** Publish completed exchanges, or rebind an already trusted mirror after an empty diff. */
    public boolean commit(final SapCalendarSnapshot snapshot, final long sequence, final String phoneZone) {
        if (writeFailed || !snapshot.hasBaseline() || sequence < 0) return false;
        return writeCheckpoint(snapshot.getEvents(), sequence, phoneZone, null, false);
    }

    /** Debug-only, explicit import of matching current watch/Android state. */
    public boolean debugBootstrap(final List<CalendarEvent> events, final long sequence, final String phoneZone,
                                  final String expectedFingerprint, final String computedFingerprint,
                                  final int expectedCount, final String expectedRanges) {
        if (!BuildConfig.DEBUG || writeFailed || events == null || sequence != 0 || expectedCount != events.size()
                || expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !expectedFingerprint.equals(computedFingerprint)) return false;
        try {
            // A pre-existing marker represents either trusted history or an uncertain operation.
            if (!"".equals(preferences.getString(STATE_KEY, ""))
                    || !"".equals(preferences.getString(MIRROR_KEY, ""))
                    || expectedRanges == null
                    || !expectedRanges.equals(preferences.getString(SapCalendarRangeStore.RANGES_KEY, ""))) return false;
            final SapCalendarRangeStore.State state = new SapCalendarRangeStore(preferences).load();
            if (state.sequence != 0 || !state.sequenceSeen || state.replaceOnNextRange
                    || state.ranges.isEmpty() || preferences.getLong(SapCalendarRangeStore.SEQUENCE_KEY, -1) != 0
                    || !preferences.getBoolean(SapCalendarRangeStore.SEQUENCE_SEEN_KEY, false)
                    || preferences.getBoolean("gear_s3_calendar_replace_on_next_range_v1", true)) return false;
            return writeCheckpoint(events, 0, phoneZone, expectedFingerprint, true);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private boolean writeCheckpoint(final List<CalendarEvent> events, final long sequence, final String phoneZone,
                                    final String verifiedFingerprint, final boolean clearPendingRefresh) {
        try {
            final SapCalendarRangeStore.State state = new SapCalendarRangeStore(preferences).load();
            if (state.replaceOnNextRange || state.ranges.isEmpty()) return false;
            if (events.size() > MAX_EVENTS) return false;
            final JSONArray rows = new JSONArray();
            for (final CalendarEvent event : events) rows.put(encode(event));
            final JSONObject checkpoint = new JSONObject().put("version", 1).put("sequence", sequence)
                    .put("phoneZone", phoneZone)
                    .put("ranges", preferences.getString(SapCalendarRangeStore.RANGES_KEY, ""))
                    .put("events", rows);
            if (verifiedFingerprint != null) checkpoint.put("provenance", "debug-bootstrap-watch-db-verified-v1")
                    .put("verifiedFingerprint", verifiedFingerprint);
            final String value = checkpoint.toString();
            if (value.length() > MAX_JSON_CHARS) return false;
            final SharedPreferences.Editor edit = preferences.edit().putString(MIRROR_KEY, value).putString(STATE_KEY, TRUSTED)
                    .putLong(SapCalendarRangeStore.SEQUENCE_KEY, sequence)
                    .putBoolean(SapCalendarRangeStore.SEQUENCE_SEEN_KEY, true);
            if (clearPendingRefresh) edit.putBoolean("gear_s3_calendar_refresh_pending_v1", false);
            final boolean saved = edit.commit();
            if (!saved) writeFailed = true;
            return saved;
        } catch (final JSONException | RuntimeException ignored) {
            return false;
        }
    }

    private static JSONObject encode(final CalendarEvent e) throws JSONException {
        final JSONObject row = new JSONObject().put("begin", e.getBegin()).put("end", e.getEnd())
                .put("instanceId", e.getId()).put("eventId", e.getEventId())
                .put("calendarColor", e.getCalendarColor()).put("color", e.getColor())
                .put("allDay", e.isAllDay()).put("status", e.getStatus()).put("attendingStatus", e.getAttendingStatus());
        putNullable(row, "title", e.getTitle());
        putNullable(row, "description", e.getDescription());
        putNullable(row, "location", e.getLocation());
        putNullable(row, "calendarName", e.getCalName());
        putNullable(row, "accountName", e.getCalAccountName());
        putNullable(row, "accountType", e.getCalAccountType());
        putNullable(row, "calendarId", e.getCalendarId());
        putNullable(row, "organizer", e.getOrganizer());
        putNullable(row, "timezone", e.getTimezone());
        putNullable(row, "rrule", e.getRrule());
        final JSONArray reminders = new JSONArray();
        for (final long time : e.getRemindersAbsoluteTs()) reminders.put(time);
        return row.put("reminders", reminders);
    }

    private static void putNullable(final JSONObject row, final String key, final String value) throws JSONException {
        row.put(key, value == null ? JSONObject.NULL : value);
    }

    private static String nullable(final JSONObject row, final String key) throws JSONException {
        if (!row.has(key)) throw new JSONException("Missing mirror field");
        if (row.isNull(key)) return null;
        if (!(row.get(key) instanceof String)) throw new JSONException("Invalid mirror string");
        return row.getString(key);
    }

    private static CalendarEvent decode(final JSONObject row) throws JSONException {
        final long begin = row.getLong("begin"), end = row.getLong("end"), eventId = row.getLong("eventId");
        if (begin < 0 || end <= begin || eventId < 0) throw new JSONException("Invalid mirror identity/time");
        final CalendarEvent event = new CalendarEvent(begin, end, row.getLong("instanceId"), eventId,
                nullable(row, "title"), nullable(row, "description"), nullable(row, "location"),
                nullable(row, "calendarName"), nullable(row, "accountName"), row.getInt("calendarColor"),
                row.getInt("color"), row.getBoolean("allDay"), nullable(row, "organizer"),
                nullable(row, "accountType"), nullable(row, "calendarId"), nullable(row, "rrule"),
                row.getInt("status"), row.getInt("attendingStatus"), nullable(row, "timezone"));
        final JSONArray values = row.getJSONArray("reminders");
        final List<Long> reminders = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) reminders.add(values.getLong(i));
        event.setRemindersAbsoluteTs(reminders);
        return event;
    }
}
