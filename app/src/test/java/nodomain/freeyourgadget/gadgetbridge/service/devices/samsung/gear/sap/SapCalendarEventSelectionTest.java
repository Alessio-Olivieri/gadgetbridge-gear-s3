package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SapCalendarEventSelectionTest {
    private static CalendarEvent timedEvent(final long start, final long eventId) {
        return new CalendarEvent(start, start + 3_600_000L, eventId, eventId,
                "Test", null, null, "Personal", "local", 0, 0, false,
                null, "LOCAL", "1", null, 0, 0, "Europe/Rome");
    }

    @Test
    public void timedEventSurvivesAllDayZeroLimitAndDuplicateRows() throws Exception {
        final long start = 1_780_319_600_000L;
        final CalendarEvent event = timedEvent(start, 42);
        final SapCalendarCodec.RequestRange range = SapCalendarCodec.parseRange(new JSONObject()
                .put("startTime", start - 3_600_000L).put("endTime", start + 86_400_000L)
                .put("maxEventCnt", 1500).put("maxAlldayEventCnt", 0)
                .put("maxTextLength", 101).put("reminder", "off"));
        final List<CalendarEvent> selected = SapCalendarEventSelection.select(Arrays.asList(event, event),
                range, TimeZone.getTimeZone("Europe/Rome"));
        assertEquals(1, selected.size());
        final JSONObject response = new JSONObject(SapCalendarCodec.listResponse(selected, range.maxTextLength,
                TimeZone.getTimeZone("Europe/Rome"), range.reminders));
        assertEquals(1, response.getInt("count"));
        assertEquals(1, response.getJSONArray("list").length());
        assertTrue(response.getJSONArray("list").getJSONObject(0).getString("vcs").contains("UID:42\r\n"));
        assertEquals(0, SapCalendarEventSelection.select(Collections.emptyList(), range,
                TimeZone.getTimeZone("Europe/Rome")).size());
    }
}
