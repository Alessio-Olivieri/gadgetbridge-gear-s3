package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class SapCalendarSnapshotTest {
    private static CalendarEvent event(final long start, final String title) {
        return new CalendarEvent(start, start + 3_600_000, start, 42, title, null, null,
                "Personal", "local", 0, 0, false, null, "LOCAL", "1", null, 0, 0, "UTC");
    }

    @Test
    public void recurringInstancesStayDistinctAndChangesNeedCommit() {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        final CalendarEvent first = event(1_000_000L, "First");
        final CalendarEvent second = event(2_000_000L, "Second");
        snapshot.recordList(Arrays.asList(first, second, first));
        snapshot.completeFullSync();
        final SapCalendarSnapshot.Difference diff = snapshot.diff(Arrays.asList(event(1_000_000L, "Edited"),
                event(1_000_000L, "Edited")));
        assertEquals(1, diff.changed.size());
        assertEquals(0, diff.addedCount);
        assertEquals(1, diff.modifiedCount);
        assertEquals(2, diff.deleted.size()); // Old modified version plus the removed instance.
        assertEquals(1, diff.removedCount());
        assertEquals(2_000_000L, diff.deleted.get(1).getBegin());
        assertEquals(2, snapshot.diff(Collections.singletonList(event(1_000_000L, "Edited"))).deleted.size());
        snapshot.commit(diff);
        assertTrue(snapshot.diff(Collections.singletonList(event(1_000_000L, "Edited"))).isEmpty());
    }

    @Test
    public void eventAddedAfterAnEmptyInitialListBecomesAnUpdate() throws Exception {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        snapshot.recordList(Collections.emptyList());
        snapshot.completeFullSync();
        final CalendarEvent added = event(1_000_000L, "New");
        final SapCalendarSnapshot.Difference difference = snapshot.diff(Collections.singletonList(added));
        assertEquals(1, difference.changed.size());
        assertEquals(1, difference.addedCount);
        assertEquals(0, difference.modifiedCount);
        assertEquals(0, difference.deleted.size());
        final org.json.JSONObject indication = new org.json.JSONObject(SapCalendarCodec.updateIndication(
                difference.changed, difference.deleted, 1, 101, java.util.TimeZone.getTimeZone("UTC"), false));
        assertEquals("calendar-update-ind", indication.getString("msgId"));
        assertEquals(1, indication.getInt("count"));
        assertEquals(0, indication.getInt("deleted_count"));
        assertTrue(indication.getJSONArray("list").getJSONObject(0).getString("vcs").contains("UID:42\r\n"));
        snapshot.commit(difference);
        assertTrue(snapshot.diff(Collections.singletonList(added)).isEmpty());
    }

    @Test
    public void restartDoesNotInventAnEmptyWatchBaseline() {
        final CalendarEvent existing = event(1_000_000L, "Existing");
        final SapCalendarSnapshot original = new SapCalendarSnapshot();
        original.beginFullSync();
        original.recordList(Collections.singletonList(existing));
        original.completeFullSync();
        original.commit(original.diff(Collections.singletonList(existing)));
        final SapCalendarSnapshot restarted = new SapCalendarSnapshot();
        assertFalse(restarted.hasBaseline());
        // Production checks hasBaseline before any incremental query/diff/TX.
        assertThrows(IllegalStateException.class, () -> restarted.diff(Collections.singletonList(existing)));
        restarted.completeFullSync(); // A sequence-only reconnect proves no event baseline.
        assertFalse(restarted.hasBaseline());
    }

    @Test
    public void interruptedFullSyncCannotAuthorizeIncrementalUpdates() {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        snapshot.recordList(Collections.singletonList(event(1_000_000L, "Existing")));
        snapshot.sessionClosed();
        snapshot.completeFullSync();
        assertFalse(snapshot.hasBaseline());
    }

    @Test
    public void renameIsOneModificationWithSamsungDeleteOldAndAddNew() throws Exception {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        final CalendarEvent oldEvent = event(1_000_000L, "Before");
        final CalendarEvent renamed = event(1_000_000L, "After");
        snapshot.beginFullSync();
        snapshot.recordList(Collections.singletonList(oldEvent));
        snapshot.completeFullSync();
        snapshot.sessionClosed(); // A completed same-instance baseline survives disconnect.
        assertTrue(snapshot.hasBaseline());
        final SapCalendarSnapshot.Difference diff = snapshot.diff(Collections.singletonList(renamed));
        assertEquals(0, diff.addedCount);
        assertEquals(1, diff.modifiedCount);
        assertEquals(0, diff.removedCount());
        assertEquals(Collections.singletonList(oldEvent), diff.deleted);
        assertEquals(Collections.singletonList(renamed), diff.changed);
        final org.json.JSONObject wire = new org.json.JSONObject(SapCalendarCodec.updateIndication(
                diff.changed, diff.deleted, 1, 101, java.util.TimeZone.getTimeZone("UTC"), false));
        assertEquals(1, wire.getInt("count"));
        assertEquals(1, wire.getInt("deleted_count"));
        assertTrue(wire.getJSONArray("list").getJSONObject(0).getString("vcs").contains("SUMMARY:After\r\n"));
        assertTrue(wire.getJSONArray("list").getJSONObject(1).getString("deleted_vcs").contains("SUMMARY:Before\r\n"));
        // Pending transmission does not commit; only the application ACK does.
        assertEquals(1, snapshot.diff(Collections.singletonList(renamed)).modifiedCount);
        snapshot.commit(diff);
        assertTrue(snapshot.diff(Collections.singletonList(renamed)).isEmpty());
    }

    @Test
    public void androidInstanceRowRenumberingIsNotAnEventModification() {
        final CalendarEvent before = event(1_000_000L, "Same");
        final CalendarEvent renumbered = new CalendarEvent(before.getBegin(), before.getEnd(), 9999, 42,
                "Same", null, null, "Personal", "local", 0, 0, false,
                null, "LOCAL", "1", null, 0, 0, "UTC");
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        snapshot.recordList(Collections.singletonList(before));
        snapshot.completeFullSync();
        assertTrue(snapshot.diff(Collections.singletonList(renumbered)).isEmpty());
    }

    @Test
    public void identicalLogicalRowsDoNotBecomeTwoModifications() throws Exception {
        // Synthetic DB-shaped fixture: start/end seconds, originalStart milliseconds,
        // identical content and distinct watch-local row IDs. No private DB is a test asset.
        final long startSeconds = 1_700_000_000L;
        final long originalStart = startSeconds * 1000;
        final CalendarEvent first = new CalendarEvent(originalStart, (startSeconds + 3600) * 1000,
                7, 42, "Before", "", "", "", "", 0, 0, false,
                "", "", "", null, 0, 0, "Europe/Rome");
        final CalendarEvent duplicate = new CalendarEvent(originalStart, first.getEnd(),
                8, 42, "Before", "", "", "", "", 0, 0, false,
                "", "", "", null, 0, 0, "Europe/Rome");
        final CalendarEvent current = new CalendarEvent(originalStart, first.getEnd(),
                900, 42, "After", "", "", "", "", 0, 0, false,
                "", "", "", null, 0, 0, "Europe/Rome");
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        // Exercise the existing logical mirror only. This is not a DB-import authorization path.
        snapshot.beginFullSync();
        snapshot.recordList(Arrays.asList(first, duplicate));
        snapshot.completeFullSync();
        assertTrue(snapshot.diff(Collections.singletonList(first)).isEmpty());
        final SapCalendarSnapshot.Difference diff = snapshot.diff(Collections.singletonList(current));
        assertEquals(0, diff.addedCount);
        assertEquals(1, diff.modifiedCount);
        assertEquals(1, diff.deleted.size());
        final org.json.JSONObject wire = new org.json.JSONObject(SapCalendarCodec.updateIndication(
                diff.changed, diff.deleted, 1, 101, java.util.TimeZone.getTimeZone("Europe/Rome"), true));
        assertEquals(1, wire.getInt("count"));
        assertEquals(1, wire.getInt("deleted_count"));
        assertEquals(2, wire.getJSONArray("list").length());
        assertFalse(wire.has("deleted_list"));
        final String deleted = wire.getJSONArray("list").getJSONObject(1).getString("deleted_vcs");
        assertEquals(SapCalendarCodec.vcalendar(duplicate, 101,
                java.util.TimeZone.getTimeZone("Europe/Rome"), true), deleted);
        assertTrue(deleted.contains("UID:42\r\n"));
        assertTrue(deleted.contains("ORIGINALSTART:" + originalStart + "\r\n"));
        assertFalse(deleted.contains("WATCHUID:"));
    }

    @Test
    public void truncatedWatchTextIsNotAnExactUnlimitedPhoneMirror() {
        final String prefix = "x".repeat(101);
        final CalendarEvent dbProjection = new CalendarEvent(1_000_000L, 4_600_000L, 1, 42,
                "Title", prefix, "", "", "", 0, 0, false, "", "", "", null, 0, 0, "UTC");
        final CalendarEvent originalPhoneContent = new CalendarEvent(1_000_000L, 4_600_000L, 2, 42,
                "Title", prefix + " unavailable suffix", "", "", "", 0, 0, false,
                "", "", "", null, 0, 0, "UTC");
        final java.util.TimeZone zone = java.util.TimeZone.getTimeZone("UTC");
        assertEquals(SapCalendarCodec.vcalendar(dbProjection, 101, zone, true),
                SapCalendarCodec.vcalendar(originalPhoneContent, 101, zone, true));
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        snapshot.recordList(Collections.singletonList(dbProjection));
        snapshot.completeFullSync();
        // The current mirror intentionally compares untruncated content. A DB prefix
        // cannot be silently substituted for the missing original Android description.
        assertEquals(1, snapshot.diff(Collections.singletonList(originalPhoneContent)).modifiedCount);
    }
}
