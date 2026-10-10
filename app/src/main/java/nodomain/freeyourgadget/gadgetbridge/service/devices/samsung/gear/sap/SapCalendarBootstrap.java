package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

/** Read-only debug snapshot plan. The caller owns the live SAP lock and performs the guarded commit. */
public final class SapCalendarBootstrap {
    /** Fixed, privacy-safe refusal codes; never forward provider exception messages. */
    public static final class Refusal extends IllegalStateException {
        public final String reason;
        public Refusal(final String reason) { super(reason); this.reason = reason; }
        public String diagnosticLine() { return "REFUSED " + reason; }
    }

    /** Pure predicate shared by INSPECT and BOOTSTRAP under the Calendar lock. */
    public static final class Quiescence {
        public final SapCalendarServiceConnection.State connection;
        public final boolean supportReady, mirrorUnknown, updatePending, fullSyncActive;
        public final boolean routePresent, routeMatches, ingressPending, reliableRxPending;
        public final boolean refreshRunning, sequenceReady;

        public Quiescence(final boolean supportReady, final SapCalendarServiceConnection.State connection,
                          final boolean routePresent, final boolean routeMatches,
                          final boolean mirrorUnknown,
                          final boolean updatePending, final boolean fullSyncActive,
                          final boolean ingressPending, final boolean reliableRxPending,
                          final boolean refreshRunning, final boolean sequenceReady) {
            this.supportReady = supportReady;
            this.connection = connection;
            this.routePresent = routePresent;
            this.routeMatches = routeMatches;
            this.mirrorUnknown = mirrorUnknown;
            this.updatePending = updatePending;
            this.fullSyncActive = fullSyncActive;
            this.ingressPending = ingressPending;
            this.reliableRxPending = reliableRxPending;
            this.refreshRunning = refreshRunning;
            this.sequenceReady = sequenceReady;
        }

        public String refusalReason() {
            if (!supportReady) return "support-unavailable";
            if (!mirrorUnknown) return "mirror-not-unknown";
            if (connection == SapCalendarServiceConnection.State.PENDING) return "active-service-create";
            if (!(connection == SapCalendarServiceConnection.State.IDLE && !routePresent
                    || connection == SapCalendarServiceConnection.State.ESTABLISHED
                    && routePresent && routeMatches)) return "session-state-inconsistent";
            if (fullSyncActive) return "full-sync-active";
            if (updatePending) return "update-pending";
            if (ingressPending) return "application-rx-pending";
            if (reliableRxPending) return "reliable-rx-pending";
            if (refreshRunning) return "refresh-running";
            if (!sequenceReady) return "sequence-mismatch";
            return null;
        }

        public void requireIdle() {
            final String reason = refusalReason();
            if (reason != null) throw new Refusal(reason);
        }
    }

    /** Calendar application TX increments this sequence before queuing a frame. */
    public static void requireNoSapTx(final int before, final int after) {
        if (before != after) throw new Refusal("sap-tx-during-bootstrap");
    }

    public interface EventSource {
        List<CalendarEvent> read(long start, long end);
    }

    public static final class Plan {
        public final List<CalendarEvent> events;
        public final String fingerprint;
        public final String ranges;
        public final String phoneZone;

        private Plan(final List<CalendarEvent> events, final String fingerprint,
                     final String ranges, final String phoneZone) {
            this.events = events;
            this.fingerprint = fingerprint;
            this.ranges = ranges;
            this.phoneZone = phoneZone;
        }

        public int count() { return events.size(); }
    }

    private SapCalendarBootstrap() { }

    /** Manual debug recovery of an independently inspected EMPTY watch, not current Android data. */
    public static Plan inspectEmpty(final SharedPreferences preferences, final TimeZone phoneZone) throws JSONException {
        final SapCalendarRangeStore.State state = new SapCalendarRangeStore(preferences).load();
        final String raw = preferences.getString(SapCalendarRangeStore.RANGES_KEY, "");
        if (state.ranges.isEmpty() || new JSONArray(raw).length() != state.ranges.size()
                || state.replaceOnNextRange || !state.sequenceSeen || state.sequence != 0
                || preferences.getLong(SapCalendarRangeStore.SEQUENCE_KEY, -1) != 0
                || !preferences.getBoolean(SapCalendarRangeStore.SEQUENCE_SEEN_KEY, false)
                || preferences.getBoolean("gear_s3_calendar_replace_on_next_range_v1", true)) {
            throw new Refusal("range-or-sequence-incomplete");
        }
        for (final String zone : state.zones) {
            if (!phoneZone.getID().equals(zone)) throw new Refusal("range-options-mismatch");
        }
        return new Plan(java.util.Collections.emptyList(), "", raw, phoneZone.getID());
    }

    /** The explicit caller attests to the helper's stable, read-only DB inspection. */
    public static void requireEmptyWatchProof(final String databaseSha256, final int count,
                                              final long watchSequence, final long verifiedAt,
                                              final long now) {
        if (count != 0) throw new Refusal("watch-not-empty");
        if (watchSequence != 0) throw new Refusal("watch-sequence-mismatch");
        if (databaseSha256 == null || !databaseSha256.matches("[0-9a-f]{64}")) {
            throw new Refusal("watch-proof-missing");
        }
        if (verifiedAt <= 0 || verifiedAt > now || now - verifiedAt > 300_000L) {
            throw new Refusal("watch-proof-expired");
        }
    }

    public static Plan inspect(final SharedPreferences preferences, final TimeZone phoneZone,
                               final EventSource source, final int expectedRangeCount) throws JSONException {
        if (preferences == null || phoneZone == null || source == null) throw new IllegalArgumentException("Missing bootstrap input");
        if (expectedRangeCount < 1) throw new Refusal("range-count-mismatch");
        final SapCalendarRangeStore.State state = new SapCalendarRangeStore(preferences).load();
        final String raw = preferences.getString(SapCalendarRangeStore.RANGES_KEY, "");
        if (raw == null || new JSONArray(raw).length() != state.ranges.size() || state.ranges.size() != expectedRangeCount
                || state.replaceOnNextRange || !state.sequenceSeen || state.sequence != 0
                || preferences.getLong(SapCalendarRangeStore.SEQUENCE_KEY, -1) != 0
                || !preferences.getBoolean(SapCalendarRangeStore.SEQUENCE_SEEN_KEY, false)
                || preferences.getBoolean("gear_s3_calendar_replace_on_next_range_v1", true)) {
            throw new Refusal("range-or-sequence-incomplete");
        }
        final SapCalendarCodec.RequestRange first = state.ranges.get(0);
        long priorEnd = -1;
        final Map<String, CalendarEvent> unique = new LinkedHashMap<>();
        for (int i = 0; i < state.ranges.size(); i++) {
            final SapCalendarCodec.RequestRange range = state.ranges.get(i);
            if (range.start <= priorEnd || !phoneZone.getID().equals(state.zones.get(i))
                    || range.maxTextLength != first.maxTextLength || range.reminders != first.reminders
                    || range.maxEvents != first.maxEvents || range.maxAllDay != first.maxAllDay) {
                throw new Refusal("range-options-mismatch");
            }
            final List<CalendarEvent> selected = SapCalendarEventSelection.select(
                    source.read(range.start, range.end), range, phoneZone);
            for (final CalendarEvent event : selected) {
                final CalendarEvent previous = unique.putIfAbsent(SapCalendarSnapshot.key(event), event);
                if (previous != null && !SapCalendarBootstrapFingerprint.calculate(
                        java.util.Collections.singletonList(previous), first.maxTextLength, phoneZone, first.reminders)
                        .equals(SapCalendarBootstrapFingerprint.calculate(
                                java.util.Collections.singletonList(event), first.maxTextLength, phoneZone, first.reminders))) {
                    throw new Refusal("android-event-changed-during-query");
                }
            }
            priorEnd = range.end;
        }
        final List<CalendarEvent> events = new ArrayList<>(unique.values());
        return new Plan(events, SapCalendarBootstrapFingerprint.calculate(events,
                first.maxTextLength, phoneZone, first.reminders), raw, phoneZone.getID());
    }
}
