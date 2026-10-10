package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Synthetic fixtures only. Models the same write-ahead/commit order as Calendar dispatch. */
public class SapCalendarMirrorStoreTest {
    private static final String ZONE = "Europe/Rome";
    private static final long START = 1_790_892_000_000L;

    /** Staged edits: nothing reaches the durable map until a successful commit. */
    private static final class DevicePreferences {
        final Map<String, Object> durable = new HashMap<>();
        final SharedPreferences prefs = mock(SharedPreferences.class);
        boolean failCommit;

        DevicePreferences() {
            when(prefs.getString(anyString(), anyString())).thenAnswer(i ->
                    (String) durable.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(prefs.getLong(anyString(), anyLong())).thenAnswer(i ->
                    (Long) durable.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(i ->
                    (Boolean) durable.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(prefs.edit()).thenAnswer(i -> {
                final Map<String, Object> staged = new HashMap<>();
                final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
                when(editor.putString(anyString(), anyString())).thenAnswer(p -> {
                    staged.put(p.getArgument(0), p.getArgument(1)); return editor;
                });
                when(editor.putLong(anyString(), anyLong())).thenAnswer(p -> {
                    staged.put(p.getArgument(0), p.getArgument(1)); return editor;
                });
                when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(p -> {
                    staged.put(p.getArgument(0), p.getArgument(1)); return editor;
                });
                when(editor.commit()).thenAnswer(p -> {
                    if (failCommit) return false;
                    durable.putAll(staged);
                    return true;
                });
                return editor;
            });
        }
    }

    private static CalendarEvent event(final String title, final String description) {
        final CalendarEvent event = new CalendarEvent(START + 3_600_000, START + 7_200_000,
                100, 42, title, description, "", "Synthetic", "local", 0, 0, false,
                null, "LOCAL", "1", null, 0, 0, ZONE);
        event.setRemindersAbsoluteTs(Arrays.asList(START + 3_000_000, START + 3_300_000));
        return event;
    }

    /** Debug fingerprint compares this wire shape with the watch DB; no VALARM. */
    private static CalendarEvent bootstrapEvent(final String title, final String description) {
        return new CalendarEvent(START + 3_600_000, START + 7_200_000,
                100, 42, title, description, "", "Synthetic", "local", 0, 0, false,
                null, "LOCAL", "1", null, 0, 0, ZONE);
    }

    private static void saveRange(final DevicePreferences device) throws Exception {
        final SapCalendarCodec.RequestRange range = SapCalendarCodec.parseRange(new JSONObject()
                .put("startTime", START).put("endTime", START + 86_399_000)
                .put("maxTextLength", 101).put("maxEventCnt", 1500)
                .put("maxAlldayEventCnt", 750).put("reminder", "on"));
        assertTrue(new SapCalendarRangeStore(device.prefs).saveRanges(
                Collections.singletonList(range), Collections.singletonList(ZONE)));
    }

    private static SapCalendarSnapshot fullSync(final DevicePreferences device, final CalendarEvent event) throws Exception {
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        assertTrue(store.invalidate("full-sync"));
        assertTrue(new SapCalendarRangeStore(device.prefs).markFullSyncRequested());
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        saveRange(device);
        snapshot.recordList(Collections.singletonList(event));
        assertFalse(store.commit(snapshot, 0, ZONE)); // Sending a list is not completion.
        snapshot.completeFullSync(); // Genuine post-list Gear sequence request.
        assertTrue(store.commit(snapshot, 0, ZONE));
        return snapshot;
    }

    private static SapCalendarSnapshot restored(final DevicePreferences device) {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        assertEquals("trusted", new SapCalendarMirrorStore(device.prefs).restore(snapshot, ZONE));
        assertTrue(snapshot.hasBaseline());
        return snapshot;
    }

    private static void assertSuppressed(final DevicePreferences device) {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        new SapCalendarMirrorStore(device.prefs).restore(snapshot, ZONE);
        assertFalse(snapshot.hasBaseline());
        assertTrue(snapshot.getEvents().isEmpty());
        assertThrows(IllegalStateException.class, () -> snapshot.diff(Collections.emptyList()));
    }

    @Test
    public void completedFullSyncSurvivesSessionCloseAndNewStoreInstance() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final CalendarEvent event = event("Original", null);
        fullSync(device, event).sessionClosed();
        assertTrue(restored(device).diff(Collections.singletonList(event)).isEmpty());
        assertEquals(0L, device.durable.get(SapCalendarRangeStore.SEQUENCE_KEY));
        assertEquals(true, device.durable.get(SapCalendarRangeStore.SEQUENCE_SEEN_KEY));
    }

    @Test
    public void titleEditAfterRestartIsOneModificationNotAnAddition() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        fullSync(device, event("Original", null));
        final SapCalendarSnapshot.Difference diff = restored(device).diff(
                Collections.singletonList(event("Renamed", null)));
        assertEquals(0, diff.addedCount);
        assertEquals(1, diff.modifiedCount);
        assertEquals(0, diff.removedCount());
        assertEquals(1, diff.deleted.size());
        assertEquals(1, diff.changed.size());
        assertEquals("Original", diff.deleted.get(0).getTitle());
        assertEquals("Renamed", diff.changed.get(0).getTitle());
    }

    @Test
    public void fullTextRemindersNullsAndDeletionTupleSurviveRestart() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final String text = String.join("", Collections.nCopies(120, "x")) + "old suffix";
        final CalendarEvent original = event("Synthetic", text);
        fullSync(device, original);
        final SapCalendarSnapshot mirror = restored(device);
        final CalendarEvent saved = mirror.getEvents().get(0);
        assertEquals(text, saved.getDescription());
        assertEquals(null, saved.getOrganizer());
        assertEquals("", saved.getLocation());
        assertEquals(original.getRemindersAbsoluteTs(), saved.getRemindersAbsoluteTs());
        assertEquals(SapCalendarCodec.vcalendar(original, 0, TimeZone.getTimeZone(ZONE), true),
                SapCalendarCodec.vcalendar(saved, 0, TimeZone.getTimeZone(ZONE), true));
        assertEquals(1, mirror.diff(Collections.singletonList(event("Synthetic", text + "edit"))).modifiedCount);
        final SapCalendarSnapshot.Difference removal = mirror.diff(Collections.emptyList());
        assertEquals(1, removal.removedCount());
        assertEquals(original.getBegin(), removal.deleted.get(0).getBegin());
        assertEquals(original.getEnd(), removal.deleted.get(0).getEnd());
        assertEquals(original.getEventId(), removal.deleted.get(0).getEventId());
    }

    @Test
    public void interruptedFullSyncCannotRestoreOldOrPromotePartialMirror() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final SapCalendarSnapshot active = fullSync(device, event("Old", null));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        assertTrue(store.invalidate("full-sync"));
        assertTrue(new SapCalendarRangeStore(device.prefs).markFullSyncRequested());
        active.beginFullSync();
        assertSuppressed(device); // Before the first replacement range.
        saveRange(device); // Even identical ranges must not resurrect the old mirror.
        active.recordList(Collections.singletonList(event("Partial", null)));
        assertFalse(store.commit(active, 0, ZONE));
        active.sessionClosed();
        active.completeFullSync(); // A sequence-only reconnect cannot complete that lost exchange.
        assertFalse(store.commit(active, 0, ZONE));
        assertSuppressed(device);
    }

    @Test
    public void unacknowledgedUpdateKeepsOldCheckpointButPreventsRestoration() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final SapCalendarSnapshot active = fullSync(device, event("Old", null));
        final Object old = device.durable.get(SapCalendarMirrorStore.MIRROR_KEY);
        final SapCalendarSnapshot.Difference pending = active.diff(Collections.singletonList(event("New", null)));
        assertEquals(1, pending.modifiedCount);
        assertTrue(new SapCalendarMirrorStore(device.prefs).invalidate("update-pending"));
        // TX or a reliable ACK alone never calls snapshot.commit / store.commit.
        assertEquals(old, device.durable.get(SapCalendarMirrorStore.MIRROR_KEY));
        assertEquals(0L, device.durable.get(SapCalendarRangeStore.SEQUENCE_KEY));
        assertSuppressed(device);
    }

    @Test
    public void applicationAckAdvancesContentAndRevisionTogether() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final SapCalendarSnapshot active = fullSync(device, event("Old", null));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final CalendarEvent current = event("New", null);
        final SapCalendarSnapshot.Difference pending = active.diff(Collections.singletonList(current));
        assertTrue(store.invalidate("update-pending"));
        active.commit(pending); // Only the pending update's application ACK does this.
        assertTrue(store.commit(active, 1, ZONE));
        assertTrue(restored(device).diff(Collections.singletonList(current)).isEmpty());
        assertEquals(1L, device.durable.get(SapCalendarRangeStore.SEQUENCE_KEY));
        assertEquals(1, new JSONObject((String) device.durable.get(SapCalendarMirrorStore.MIRROR_KEY)).getLong("sequence"));
    }

    @Test
    public void unknownMirrorIsNotBootstrappedByRangesOrSequenceAlone() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        assertSuppressed(device);
        final SapCalendarSnapshot unknown = new SapCalendarSnapshot();
        unknown.completeFullSync();
        assertFalse(new SapCalendarMirrorStore(device.prefs).commit(unknown, 0, ZONE));
    }

    @Test
    public void genuineEmptyFullSyncIsDifferentFromUnknown() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.beginFullSync();
        snapshot.recordList(Collections.emptyList());
        snapshot.completeFullSync();
        assertTrue(new SapCalendarMirrorStore(device.prefs).commit(snapshot, 0, ZONE));
        assertTrue(restored(device).diff(Collections.emptyList()).isEmpty());
    }

    @Test
    public void syncPreferenceToggleRetainsMirrorAndDevicesAreIsolated() throws Exception {
        final DevicePreferences a = new DevicePreferences();
        final DevicePreferences b = new DevicePreferences();
        final CalendarEvent event = event("Old", null);
        fullSync(a, event);
        a.durable.put(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, false);
        assertTrue(restored(a).diff(Collections.singletonList(event)).isEmpty());
        a.durable.put(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, true);
        assertTrue(restored(a).diff(Collections.singletonList(event)).isEmpty());
        saveRange(b);
        new SapCalendarRangeStore(b.prefs).saveProgress(0, true);
        assertSuppressed(b);
    }

    @Test
    public void corruptInconsistentOrIncompleteProgressIsNeverTrusted() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        fullSync(device, event("Old", null));
        final Map<String, Object> good = new HashMap<>(device.durable);
        final String[] keys = {SapCalendarMirrorStore.MIRROR_KEY,
                SapCalendarRangeStore.SEQUENCE_KEY, SapCalendarRangeStore.SEQUENCE_KEY,
                SapCalendarRangeStore.SEQUENCE_SEEN_KEY, "gear_s3_calendar_replace_on_next_range_v1",
                SapCalendarRangeStore.RANGES_KEY};
        final Object[] values = {"broken JSON", 7L, "wrong type", false, true, "[]"};
        for (int i = 0; i < keys.length; i++) {
            device.durable.clear();
            device.durable.putAll(good);
            device.durable.put(keys[i], values[i]);
            assertSuppressed(device);
        }
        device.durable.clear();
        device.durable.putAll(good);
        device.durable.remove(SapCalendarRangeStore.SEQUENCE_KEY);
        assertSuppressed(device);
        device.durable.putAll(good);
        final SapCalendarSnapshot otherZone = new SapCalendarSnapshot();
        assertEquals("inconsistent-progress", new SapCalendarMirrorStore(device.prefs).restore(otherZone, "UTC"));
        assertFalse(otherZone.hasBaseline());
    }

    @Test
    public void failedSafetyMarkerPreventsPublishingOrSendingNewState() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final SapCalendarSnapshot active = fullSync(device, event("Old", null));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final Object old = device.durable.get(SapCalendarMirrorStore.MIRROR_KEY);
        device.failCommit = true;
        assertFalse(store.invalidate("update-pending")); // Dispatch refuses TX here.
        device.failCommit = false;
        assertFalse(store.commit(active, 1, ZONE)); // Sticky failure in this instance.
        assertEquals(old, device.durable.get(SapCalendarMirrorStore.MIRROR_KEY));
        // Since dispatch sent nothing, the old durable state is still valid after restart.
        assertTrue(restored(device).hasBaseline());
    }

    @Test
    public void failedAckCheckpointLeavesDurableUncertaintyNotPartialRevision() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        final SapCalendarSnapshot active = fullSync(device, event("Old", null));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final Object old = device.durable.get(SapCalendarMirrorStore.MIRROR_KEY);
        assertTrue(store.invalidate("update-pending"));
        active.commit(active.diff(Collections.singletonList(event("New", null))));
        device.failCommit = true;
        assertFalse(store.commit(active, 1, ZONE));
        assertEquals(old, device.durable.get(SapCalendarMirrorStore.MIRROR_KEY));
        assertEquals(0L, device.durable.get(SapCalendarRangeStore.SEQUENCE_KEY));
        assertSuppressed(device);
    }

    @Test
    public void explicitDebugBootstrapRestoresFullMirrorWithoutAdvancingSequence() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        final String fullDescription = String.join("", Collections.nCopies(101, "x")) + "private source suffix";
        final CalendarEvent current = bootstrapEvent("Verified", fullDescription);
        final String fingerprint = SapCalendarBootstrapFingerprint.calculate(Collections.singletonList(current),
                101, TimeZone.getTimeZone(ZONE), true);
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final String ranges = (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY);
        final java.util.Set<String> priorKeys = new java.util.HashSet<>(device.durable.keySet());
        assertTrue(store.debugBootstrap(Collections.singletonList(current), 0, ZONE,
                fingerprint, fingerprint, 1, ranges));
        final java.util.Set<String> newKeys = new java.util.HashSet<>(device.durable.keySet());
        newKeys.removeAll(priorKeys);
        assertEquals(new java.util.HashSet<>(Arrays.asList(SapCalendarMirrorStore.MIRROR_KEY,
                SapCalendarMirrorStore.STATE_KEY, "gear_s3_calendar_refresh_pending_v1")), newKeys);
        assertEquals(0L, device.durable.get(SapCalendarRangeStore.SEQUENCE_KEY));
        assertEquals(false, device.durable.get("gear_s3_calendar_refresh_pending_v1"));
        assertEquals("debug-bootstrap-watch-db-verified-v1", new JSONObject(
                (String) device.durable.get(SapCalendarMirrorStore.MIRROR_KEY)).getString("provenance"));
        final SapCalendarSnapshot restarted = new SapCalendarSnapshot();
        assertEquals("trusted-debug-bootstrap", new SapCalendarMirrorStore(device.prefs).restore(restarted, ZONE));
        assertEquals(fullDescription, restarted.getEvents().get(0).getDescription());
        assertTrue(restarted.diff(Collections.singletonList(current)).isEmpty());
        assertEquals(1, restarted.diff(Collections.singletonList(
                bootstrapEvent("Renamed", current.getDescription()))).modifiedCount);
        // This store has no SAP route/sender; the source path creates no SAP transaction.
    }

    @Test
    public void debugBootstrapRefusesChangedFingerprintWrongCountAndTrustedOverwrite() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        final CalendarEvent before = bootstrapEvent("Before", null);
        final CalendarEvent after = bootstrapEvent("After", null);
        final String expected = SapCalendarBootstrapFingerprint.calculate(Collections.singletonList(before),
                101, TimeZone.getTimeZone(ZONE), true);
        final String changed = SapCalendarBootstrapFingerprint.calculate(Collections.singletonList(after),
                101, TimeZone.getTimeZone(ZONE), true);
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final String ranges = (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY);
        assertFalse(store.debugBootstrap(Collections.singletonList(after), 0, ZONE, expected, changed, 1, ranges));
        assertFalse(store.debugBootstrap(Collections.singletonList(before), 0, ZONE, expected, expected, 2, ranges));
        assertSuppressed(device);
        assertTrue(store.debugBootstrap(Collections.singletonList(before), 0, ZONE, expected, expected, 1, ranges));
        assertFalse(store.debugBootstrap(Collections.singletonList(after), 0, ZONE, changed, changed, 1, ranges));
        assertTrue(restoredForDebug(device).diff(Collections.singletonList(before)).isEmpty());
    }

    @Test
    public void debugBootstrapRefusesUncertainOperationOrSequenceMismatch() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        final CalendarEvent current = bootstrapEvent("Current", null);
        final String fingerprint = SapCalendarBootstrapFingerprint.calculate(Collections.singletonList(current),
                101, TimeZone.getTimeZone(ZONE), true);
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final String ranges = (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY);
        assertFalse(store.debugBootstrap(Collections.singletonList(current), 0, ZONE,
                fingerprint, fingerprint, 1, ranges)); // No completed sequence.
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(1, true));
        assertFalse(store.debugBootstrap(Collections.singletonList(current), 0, ZONE,
                fingerprint, fingerprint, 1, ranges)); // Phone/watch zero precondition fails.
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        assertTrue(store.invalidate("update-pending"));
        assertFalse(store.debugBootstrap(Collections.singletonList(current), 0, ZONE,
                fingerprint, fingerprint, 1, ranges));
        device.durable.remove(SapCalendarMirrorStore.STATE_KEY);
        assertTrue(new SapCalendarRangeStore(device.prefs).markFullSyncRequested());
        assertFalse(store.debugBootstrap(Collections.singletonList(current), 0, ZONE,
                fingerprint, fingerprint, 1, ranges));
        assertSuppressed(device);
    }

    private static SapCalendarSnapshot restoredForDebug(final DevicePreferences device) {
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        assertEquals("trusted-debug-bootstrap", new SapCalendarMirrorStore(device.prefs).restore(snapshot, ZONE));
        return snapshot;
    }

    @Test
    public void verifiedEmptyWatchRestoresEmptyMirrorThenNormalDiffAddsCurrentEvents() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        assertTrue(store.invalidate("range-change")); // A genuine date request, no event transfer.
        final String ranges = (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY);
        final String hash = String.join("", Collections.nCopies(64, "a"));
        assertTrue(store.debugBootstrapEmpty(hash, ranges, ZONE));
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        assertEquals("trusted-debug-empty-bootstrap",
                new SapCalendarMirrorStore(device.prefs).restore(snapshot, ZONE));
        assertTrue(snapshot.hasBaseline());
        assertTrue(snapshot.getEvents().isEmpty());
        assertEquals(0L, device.durable.get(SapCalendarRangeStore.SEQUENCE_KEY));
        final SapCalendarSnapshot.Difference diff = snapshot.diff(Collections.singletonList(event("Synthetic", null)));
        assertEquals(1, diff.addedCount);
        assertEquals(0, diff.modifiedCount);
        assertTrue(diff.deleted.isEmpty());
        assertFalse(store.debugBootstrapEmpty(hash, ranges, ZONE)); // No trusted overwrite.
    }

    @Test
    public void emptyRecoveryAcceptsOnlyObsoleteEmptyRangeChangeCheckpoint() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final String ranges = (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY);
        final String hash = String.join("", Collections.nCopies(64, "a"));
        final SapCalendarSnapshot empty = new SapCalendarSnapshot();
        empty.restoreTrusted(Collections.emptyList());
        assertTrue(store.commit(empty, 0, ZONE));
        assertEquals("mirror-not-unknown", store.debugEmptyBootstrapRefusal());
        assertTrue(store.invalidate("range-change"));
        assertNull(store.debugEmptyBootstrapRefusal());
        assertTrue(store.debugBootstrapEmpty(hash, ranges, ZONE));
        assertEquals("trusted-debug-empty-bootstrap", store.restore(new SapCalendarSnapshot(), ZONE));

        assertTrue(store.invalidate("update-pending"));
        assertEquals("interrupted-operation", store.debugEmptyBootstrapRefusal());
        assertTrue(store.invalidate("range-change"));
        final org.json.JSONObject checkpoint = new org.json.JSONObject(
                (String) device.durable.get(SapCalendarMirrorStore.MIRROR_KEY));
        device.durable.put(SapCalendarMirrorStore.MIRROR_KEY, checkpoint.put("sequence", 1).toString());
        assertEquals("mirror-not-unknown", store.debugEmptyBootstrapRefusal());
        device.durable.put(SapCalendarMirrorStore.MIRROR_KEY, "{corrupt");
        assertEquals("mirror-state-invalid", store.debugEmptyBootstrapRefusal());
    }

    @Test
    public void emptyRecoveryNeverOverwritesOldNonemptyCheckpoint() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        final SapCalendarSnapshot snapshot = new SapCalendarSnapshot();
        snapshot.restoreTrusted(Collections.singletonList(event("Synthetic", null)));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        assertTrue(store.commit(snapshot, 0, ZONE));
        assertTrue(store.invalidate("range-change"));
        assertEquals("mirror-not-unknown", store.debugEmptyBootstrapRefusal());
        assertFalse(store.debugBootstrapEmpty(String.join("", Collections.nCopies(64, "a")),
                (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY), ZONE));
    }

    @Test
    public void emptyRecoveryRefusesUncertainUpdateIncompleteSyncAndChangedRanges() throws Exception {
        final DevicePreferences device = new DevicePreferences();
        saveRange(device);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(0, true));
        final SapCalendarMirrorStore store = new SapCalendarMirrorStore(device.prefs);
        final String ranges = (String) device.durable.get(SapCalendarRangeStore.RANGES_KEY);
        final String hash = String.join("", Collections.nCopies(64, "a"));
        assertFalse(store.debugBootstrapEmpty(hash, "[]", ZONE));
        assertTrue(store.invalidate("update-pending"));
        assertFalse(store.debugBootstrapEmpty(hash, ranges, ZONE));
        assertTrue(store.invalidate("full-sync"));
        assertFalse(store.debugBootstrapEmpty(hash, ranges, ZONE));
        device.durable.remove(SapCalendarMirrorStore.STATE_KEY);
        assertTrue(new SapCalendarRangeStore(device.prefs).saveProgress(1, true));
        assertFalse(store.debugBootstrapEmpty(hash, ranges, ZONE));
        assertSuppressed(device);
    }
}
