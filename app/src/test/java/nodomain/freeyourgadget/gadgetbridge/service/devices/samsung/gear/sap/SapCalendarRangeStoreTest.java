package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SapCalendarRangeStoreTest {
    private static SapCalendarCodec.RequestRange range(final long start) throws Exception {
        return SapCalendarCodec.parseRange(new JSONObject().put("startTime", start)
                .put("endTime", start + 86_400_000L).put("maxTextLength", 101)
                .put("maxEventCnt", 1500).put("maxAlldayEventCnt", 750).put("reminder", "on"));
    }

    private static SharedPreferences preferences(final Map<String, Object> values) {
        final SharedPreferences prefs = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.getString(anyString(), anyString())).thenAnswer(invocation ->
                (String) values.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        when(prefs.getLong(anyString(), anyLong())).thenAnswer(invocation ->
                (Long) values.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(invocation ->
                (Boolean) values.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1)); return editor;
        });
        when(editor.putLong(anyString(), anyLong())).thenAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1)); return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1)); return editor;
        });
        when(editor.commit()).thenReturn(true);
        return prefs;
    }

    @Test
    public void gearRangeSurvivesSessionCloseAndNewServiceInstance() throws Exception {
        final SharedPreferences device = preferences(new HashMap<>());
        final SapCalendarRangeStore beforeClose = new SapCalendarRangeStore(device);
        final List<SapCalendarCodec.RequestRange> ranges = new ArrayList<>();
        ranges.add(range(1_700_000_000_000L));
        assertTrue(beforeClose.saveRanges(ranges, java.util.Collections.singletonList("Europe/Rome")));
        assertTrue(beforeClose.saveProgress(0, true));
        // Closing API101 writes no range removal. A newly constructed store sees the exact request.
        final SapCalendarRangeStore.State reopened = new SapCalendarRangeStore(device).load();
        assertEquals(1, reopened.ranges.size());
        assertEquals(ranges.get(0).start, reopened.ranges.get(0).start);
        assertEquals(ranges.get(0).end, reopened.ranges.get(0).end);
        assertEquals(1500, reopened.ranges.get(0).maxEvents);
        assertEquals(750, reopened.ranges.get(0).maxAllDay);
        assertEquals(101, reopened.ranges.get(0).maxTextLength);
        assertTrue(reopened.ranges.get(0).reminders);
        assertEquals("Europe/Rome", reopened.zones.get(0));
        assertTrue(reopened.sequenceSeen);
        assertTrue(reopened.shouldReconcileOnConnect());
    }

    @Test
    public void disablingSyncDoesNotEraseObservedWindow() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SapCalendarRangeStore store = new SapCalendarRangeStore(preferences(values));
        assertTrue(store.saveRanges(java.util.Collections.singletonList(range(1_700_000_000_000L)),
                java.util.Collections.singletonList("UTC")));
        values.put(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, false);
        assertEquals(1, store.load().ranges.size());
        values.put(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, true);
        assertEquals(1, store.load().ranges.size());
    }

    @Test
    public void rangesAndSequenceNumberAloneDoNotFabricateCompletedExchange() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences device = preferences(values);
        final SapCalendarRangeStore store = new SapCalendarRangeStore(device);
        store.saveRanges(java.util.Collections.singletonList(range(1_700_000_000_000L)),
                java.util.Collections.singletonList("Europe/Rome"));
        values.put("gear_s3_calendar_sequence_v1", 4L);
        final SapCalendarRangeStore.State restored = new SapCalendarRangeStore(device).load();
        assertEquals(4, restored.sequence);
        assertFalse(restored.sequenceSeen);
        assertFalse(restored.shouldReconcileOnConnect());
        assertTrue(store.saveProgress(4, true));
        assertTrue(new SapCalendarRangeStore(device).load().sequenceSeen);
        store.markFullSyncRequested();
        // Replacing the ranges is not itself a sequence request/completed baseline.
        store.saveRanges(java.util.Collections.singletonList(range(1_800_000_000_000L)),
                java.util.Collections.singletonList("Europe/Rome"));
        final SapCalendarRangeStore.State interrupted = new SapCalendarRangeStore(device).load();
        assertEquals(0, interrupted.sequence);
        assertFalse(interrupted.sequenceSeen);
        assertFalse(interrupted.shouldReconcileOnConnect());
    }

    @Test
    public void disconnectedObserverRefreshRemainsPendingForReconnect() throws Exception {
        final SharedPreferences device = preferences(new HashMap<>());
        final SapCalendarRangeStore active = new SapCalendarRangeStore(device);
        assertTrue(active.saveRanges(java.util.Collections.singletonList(range(1_700_000_000_000L)),
                java.util.Collections.singletonList("UTC")));
        assertTrue(active.saveProgress(0, true));
        assertTrue(active.setRefreshPending(true));
        final SapCalendarRangeStore.State reconnected = new SapCalendarRangeStore(device).load();
        assertTrue(reconnected.refreshPending);
        assertTrue(reconnected.sequenceSeen);
        assertEquals(1, reconnected.ranges.size());
        assertTrue(reconnected.shouldReconcileOnConnect());
        assertTrue(new SapCalendarRangeStore(device).setRefreshPending(false));
        assertFalse(new SapCalendarRangeStore(device).load().refreshPending);
    }

    @Test
    public void gearDevicePreferencesAreIsolated() throws Exception {
        final SapCalendarRangeStore deviceA = new SapCalendarRangeStore(preferences(new HashMap<>()));
        final SapCalendarRangeStore deviceB = new SapCalendarRangeStore(preferences(new HashMap<>()));
        assertTrue(deviceA.saveRanges(java.util.Collections.singletonList(range(1_700_000_000_000L)),
                java.util.Collections.singletonList("UTC")));
        assertEquals(1, deviceA.load().ranges.size());
        assertEquals(0, deviceB.load().ranges.size());
    }

    @Test
    public void capturedRequestImportIsAtomicAndDoesNotReplaceObservedState() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SapCalendarRangeStore store = new SapCalendarRangeStore(preferences(values));
        final List<SapCalendarCodec.RequestRange> captured = java.util.Collections.singletonList(
                range(1_700_000_000_000L));
        final List<String> zones = java.util.Collections.singletonList("Europe/Rome");
        assertTrue(store.importObservedRanges(captured, zones));
        final SapCalendarRangeStore.State restored = new SapCalendarRangeStore(preferences(values)).load();
        assertEquals(1, restored.ranges.size());
        assertEquals("Europe/Rome", restored.zones.get(0));
        assertEquals(0, restored.sequence);
        assertTrue(restored.sequenceSeen);
        assertTrue(restored.refreshPending);
        assertTrue(restored.shouldReconcileOnConnect());
        assertFalse(store.importObservedRanges(java.util.Collections.singletonList(range(1_800_000_000_000L)), zones));
        assertEquals(captured.get(0).start, store.load().ranges.get(0).start);
    }

    @Test
    public void fullSyncRestartRetainsOldRangeUntilNewGearRangeReplacesIt() throws Exception {
        final SharedPreferences device = preferences(new HashMap<>());
        final SapCalendarRangeStore beforeRestart = new SapCalendarRangeStore(device);
        assertTrue(beforeRestart.saveRanges(java.util.Collections.singletonList(range(1_700_000_000_000L)),
                java.util.Collections.singletonList("UTC")));
        assertTrue(beforeRestart.saveProgress(3, true));
        assertTrue(beforeRestart.markFullSyncRequested());
        final SapCalendarRangeStore afterRestart = new SapCalendarRangeStore(device);
        assertEquals(1, afterRestart.load().ranges.size());
        assertTrue(afterRestart.load().replaceOnNextRange);
        assertFalse(afterRestart.load().sequenceSeen);
        assertEquals(0, afterRestart.load().sequence);
        assertTrue(afterRestart.saveRanges(java.util.Collections.singletonList(range(1_800_000_000_000L)),
                java.util.Collections.singletonList("Europe/Rome")));
        assertFalse(afterRestart.load().replaceOnNextRange);
        assertEquals(1_800_000_000_000L, afterRestart.load().ranges.get(0).start);
    }

    @Test
    public void corruptAndInvalidStoredRangesAreIgnored() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SapCalendarRangeStore store = new SapCalendarRangeStore(preferences(values));
        values.put(SapCalendarRangeStore.RANGES_KEY, "not JSON");
        assertEquals(0, store.load().ranges.size());
        assertFalse(store.load().shouldReconcileOnConnect());
        values.put(SapCalendarRangeStore.RANGES_KEY,
                "[{\"startTime\":20,\"endTime\":10,\"maxEventCnt\":1,\"maxAlldayEventCnt\":0,\"zone\":\"UTC\"},"
                        + "{\"startTime\":10,\"endTime\":20,\"maxEventCnt\":1,\"maxAlldayEventCnt\":0,\"zone\":\"Bad/Zone\"}]");
        assertEquals(0, store.load().ranges.size());
        assertFalse(store.load().shouldReconcileOnConnect());
    }

    @Test
    public void activeWindowsSurviveResetAndInterruptedReplacementUntilRealRangeArrives() throws Exception {
        final SapCalendarRangeStore store = new SapCalendarRangeStore(preferences(new HashMap<>()));
        final SapCalendarCodec.RequestRange old = range(1_700_000_000_000L);
        assertTrue(store.saveRanges(java.util.Collections.singletonList(old), java.util.Collections.singletonList("UTC")));
        final SapCalendarRangeSet active = new SapCalendarRangeSet();
        active.restore(store.load());
        active.requestReplacement();
        store.markFullSyncRequested();
        assertEquals(old.start, active.getRanges().get(0).start);
        assertTrue(active.isReplacementPending());
        // No list arrived before disconnect/process loss. Both active and durable knowledge survive.
        final SapCalendarRangeSet recreated = new SapCalendarRangeSet();
        recreated.restore(store.load());
        assertEquals(old.start, recreated.getRanges().get(0).start);
        assertTrue(recreated.isReplacementPending());
        final SapCalendarCodec.RequestRange replacement = range(1_800_000_000_000L);
        recreated.observe(replacement, "Europe/Rome");
        assertEquals(1, recreated.getRanges().size());
        assertEquals(replacement.start, recreated.getRanges().get(0).start);
        assertFalse(recreated.isReplacementPending());
        assertTrue(store.saveRanges(recreated.getRanges(), recreated.getZones()));
        assertEquals(replacement.start, store.load().ranges.get(0).start);
    }
}
