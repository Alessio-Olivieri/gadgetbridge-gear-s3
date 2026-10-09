package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SapCalendarBootstrapTest {
    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    private static final long FIRST = 1_790_892_000_000L;

    private static SharedPreferences preferences(final Map<String, Object> values) {
        final SharedPreferences prefs = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.getString(anyString(), anyString())).thenAnswer(i ->
                (String) values.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(prefs.getLong(anyString(), anyLong())).thenAnswer(i ->
                (Long) values.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(i ->
                (Boolean) values.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenAnswer(i -> {
            values.put(i.getArgument(0), i.getArgument(1)); return editor;
        });
        when(editor.putLong(anyString(), anyLong())).thenAnswer(i -> {
            values.put(i.getArgument(0), i.getArgument(1)); return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            values.put(i.getArgument(0), i.getArgument(1)); return editor;
        });
        when(editor.commit()).thenReturn(true);
        return prefs;
    }

    private static SharedPreferences sevenRanges(final Map<String, Object> values) throws Exception {
        final SharedPreferences prefs = preferences(values);
        final List<SapCalendarCodec.RequestRange> ranges = new ArrayList<>();
        final List<String> zones = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            final long start = FIRST + i * 86_400_000L;
            ranges.add(SapCalendarCodec.parseRange(new JSONObject().put("startTime", start)
                    .put("endTime", start + 86_399_000L).put("maxEventCnt", 1500)
                    .put("maxAlldayEventCnt", 750).put("maxTextLength", 101)
                    .put("reminder", "on")));
            zones.add(ROME.getID());
        }
        final SapCalendarRangeStore store = new SapCalendarRangeStore(prefs);
        org.junit.Assert.assertTrue(store.saveRanges(ranges, zones));
        org.junit.Assert.assertTrue(store.saveProgress(0, true));
        return prefs;
    }

    private static CalendarEvent event(final String title) {
        return new CalendarEvent(FIRST + 3_600_000L, FIRST + 7_200_000L, 1, 33,
                title, null, null, "Synthetic", "local", 0, 0, false,
                "organizer@example.invalid", "LOCAL", "1", null, 0, 0, ROME.getID());
    }

    @Test
    public void inspectUsesAllSevenProductionWindowsAndDetectsChangedAndroidContent() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences prefs = sevenRanges(values);
        final AtomicReference<CalendarEvent> current = new AtomicReference<>(event("Before"));
        final int[] queries = {0};
        final SapCalendarBootstrap.EventSource source = (start, end) -> {
            queries[0]++;
            return start == FIRST ? java.util.Collections.singletonList(current.get())
                    : java.util.Collections.emptyList();
        };
        final SapCalendarBootstrap.Plan before = SapCalendarBootstrap.inspect(prefs, ROME, source, 7);
        assertEquals(1, before.count());
        assertEquals(7, queries[0]);
        assertEquals(64, before.fingerprint.length());
        assertFalse(before.fingerprint.contains("Before"));
        current.set(event("After"));
        final SapCalendarBootstrap.Plan after = SapCalendarBootstrap.inspect(prefs, ROME, source, 7);
        assertEquals(14, queries[0]);
        assertFalse(before.fingerprint.equals(after.fingerprint));
        assertEquals(before.ranges, after.ranges);
        assertEquals(0L, values.get(SapCalendarRangeStore.SEQUENCE_KEY));
    }

    @Test
    public void inspectRefusesReplacementAndNonzeroSequenceBeforeAnyAndroidQuery() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences prefs = sevenRanges(values);
        final int[] queries = {0};
        final SapCalendarBootstrap.EventSource source = (start, end) -> {
            queries[0]++; return java.util.Collections.singletonList(event("Synthetic"));
        };
        values.put(SapCalendarRangeStore.SEQUENCE_KEY, 1L);
        assertThrows(IllegalStateException.class, () -> SapCalendarBootstrap.inspect(prefs, ROME, source, 7));
        values.put(SapCalendarRangeStore.SEQUENCE_KEY, 0L);
        org.junit.Assert.assertTrue(new SapCalendarRangeStore(prefs).markFullSyncRequested());
        assertThrows(IllegalStateException.class, () -> SapCalendarBootstrap.inspect(prefs, ROME, source, 7));
        assertEquals(0, queries[0]);
    }
}
