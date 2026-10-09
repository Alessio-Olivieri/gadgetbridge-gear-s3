package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

/** Logical Android mirror. Only completed exchanges or a validated durable mirror establish trust. */
public final class SapCalendarSnapshot {
    private final Map<String, CalendarEvent> sent = new LinkedHashMap<>();
    private boolean baselineValid;
    private boolean collectingFullSync;
    private boolean receivedList;

    public void clear() {
        sent.clear();
        baselineValid = false;
        collectingFullSync = false;
        receivedList = false;
    }

    public boolean hasBaseline() { return baselineValid; }

    public boolean isCollectingFullSync() { return collectingFullSync; }

    public List<CalendarEvent> getEvents() { return new ArrayList<>(sent.values()); }

    // Only the validated per-device store may restore trust without a new wire exchange.
    void restoreTrusted(final List<CalendarEvent> events) {
        clear();
        for (final CalendarEvent event : events) sent.put(key(event), event);
        baselineValid = true;
    }

    /** Start a fresh genuine full exchange; a partial exchange is never an empty trusted watch. */
    public void beginFullSync() {
        clear();
        collectingFullSync = true;
    }

    public void completeFullSync() {
        if (collectingFullSync && receivedList) {
            baselineValid = true;
            collectingFullSync = false;
        }
    }

    public void sessionClosed() {
        if (collectingFullSync) clear(); // A partial list exchange cannot authorize a delta.
    }

    public void recordList(final List<CalendarEvent> events) {
        if (!collectingFullSync && !baselineValid) return;
        if (collectingFullSync) receivedList = true;
        for (final CalendarEvent event : events) sent.put(key(event), event);
    }

    public Difference diff(final List<CalendarEvent> events) {
        if (!baselineValid) throw new IllegalStateException("Calendar watch baseline unknown");
        final Map<String, CalendarEvent> current = new LinkedHashMap<>();
        for (final CalendarEvent event : events) current.put(key(event), event);
        final List<CalendarEvent> changed = new ArrayList<>();
        final List<CalendarEvent> deleted = new ArrayList<>();
        int addedCount = 0;
        int modifiedCount = 0;
        for (final Map.Entry<String, CalendarEvent> entry : current.entrySet()) {
            final CalendarEvent previous = sent.get(entry.getKey());
            if (previous == null) {
                changed.add(entry.getValue());
                addedCount++;
            } else if (!sameContent(previous, entry.getValue())) {
                changed.add(entry.getValue());
                // Changed content replaces the previous event version.
                // The consumer deletes deleted_vcs before inserting vcs (no WATCHUID upsert here).
                deleted.add(previous);
                modifiedCount++;
            }
        }
        for (final Map.Entry<String, CalendarEvent> entry : sent.entrySet()) {
            if (!current.containsKey(entry.getKey())) deleted.add(entry.getValue());
        }
        return new Difference(changed, deleted, current, addedCount, modifiedCount);
    }

    public void commit(final Difference difference) {
        sent.clear();
        sent.putAll(difference.current);
    }

    private static boolean sameContent(final CalendarEvent a, final CalendarEvent b) {
        // Instances._ID and phone-only account metadata are not Gear event identity/content.
        // Compare supported fields through the unchanged encoder in one fixed comparison zone.
        final java.util.TimeZone zone = java.util.TimeZone.getTimeZone("UTC");
        return SapCalendarCodec.vcalendar(a, 0, zone, true).equals(SapCalendarCodec.vcalendar(b, 0, zone, true));
    }

    // Samsung's UID is the Android event ID; ORIGINALSTART distinguishes instances.
    public static String key(final CalendarEvent event) { return event.getEventId() + ":" + event.getBegin(); }

    public static final class Difference {
        public final List<CalendarEvent> changed;
        public final List<CalendarEvent> deleted;
        public final int addedCount;
        public final int modifiedCount;
        private final Map<String, CalendarEvent> current;
        private Difference(final List<CalendarEvent> changed, final List<CalendarEvent> deleted,
                           final Map<String, CalendarEvent> current, final int addedCount, final int modifiedCount) {
            this.changed = changed; this.deleted = deleted; this.current = current;
            this.addedCount = addedCount; this.modifiedCount = modifiedCount;
        }
        public boolean isEmpty() { return changed.isEmpty() && deleted.isEmpty(); }
        public int removedCount() { return deleted.size() - modifiedCount; }
    }
}
