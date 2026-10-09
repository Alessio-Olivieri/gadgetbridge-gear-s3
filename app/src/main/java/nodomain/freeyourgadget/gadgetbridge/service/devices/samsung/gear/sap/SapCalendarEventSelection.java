package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

/** Bounded selection for one Gear-requested list window. */
public final class SapCalendarEventSelection {
    private static final int MAX_CLEAR_FRAME_BYTES = 48 * 1024;

    private SapCalendarEventSelection() { }

    public static List<CalendarEvent> select(final List<CalendarEvent> candidates,
                                              final SapCalendarCodec.RequestRange range,
                                              final TimeZone phoneZone) throws JSONException {
        final List<CalendarEvent> selected = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int allDay = 0;
        for (final CalendarEvent event : candidates) {
            if (selected.size() >= range.maxEvents || allDay >= range.maxAllDay) break;
            if (!event.isAllDay()) continue;
            if (event.getEventId() < 0 || event.getEnd() <= event.getBegin()
                    || !seen.add(SapCalendarSnapshot.key(event))) continue;
            selected.add(event);
            allDay++;
        }
        for (final CalendarEvent event : candidates) {
            if (selected.size() >= range.maxEvents) break;
            if (event.isAllDay() || event.getEventId() < 0 || event.getEnd() <= event.getBegin()
                    || !seen.add(SapCalendarSnapshot.key(event))) continue;
            selected.add(event);
        }
        String json = SapCalendarCodec.listResponse(selected, range.maxTextLength, phoneZone, range.reminders);
        while (json.getBytes(StandardCharsets.UTF_8).length > MAX_CLEAR_FRAME_BYTES && !selected.isEmpty()) {
            selected.remove(selected.size() - 1);
            json = SapCalendarCodec.listResponse(selected, range.maxTextLength, phoneZone, range.reminders);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CLEAR_FRAME_BYTES) {
            throw new IllegalArgumentException("Calendar list exceeds frame bound");
        }
        return selected;
    }
}
