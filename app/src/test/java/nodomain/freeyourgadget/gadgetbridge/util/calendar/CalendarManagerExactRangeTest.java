package nodomain.freeyourgadget.gadgetbridge.util.calendar;

import org.junit.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalendarManagerExactRangeTest {
    @Test
    public void ordinaryTimedEventInsideRequestedWindowIsRetained() {
        final TimeZone utc = TimeZone.getTimeZone("UTC");
        final long start = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
        final long end = Instant.parse("2026-10-02T00:00:00Z").toEpochMilli();
        final long eventStart = Instant.parse("2026-10-01T14:00:00Z").toEpochMilli();
        assertTrue(CalendarManager.isInstanceInExactRange(eventStart, eventStart + 3_600_000L,
                false, start, end, utc));
        assertFalse(CalendarManager.isInstanceInExactRange(end, end + 3_600_000L,
                false, start, end, utc));
        assertFalse(CalendarManager.isInstanceInExactRange(start - 3_600_000L, start,
                false, start, end, utc));
    }

    @Test
    public void europeRomeTimedEventUsesEpochRangeWithoutTimezoneShift() {
        final ZoneId rome = ZoneId.of("Europe/Rome");
        final TimeZone phoneZone = TimeZone.getTimeZone(rome);
        final long start = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, rome).toInstant().toEpochMilli();
        final long end = ZonedDateTime.of(2026, 10, 2, 0, 0, 0, 0, rome).toInstant().toEpochMilli();
        final long eventStart = ZonedDateTime.of(2026, 10, 1, 16, 0, 0, 0, rome).toInstant().toEpochMilli();
        assertTrue(CalendarManager.isInstanceInExactRange(eventStart, eventStart + 3_600_000L,
                false, start, end, phoneZone));
        assertFalse(CalendarManager.isInstanceInExactRange(end, end + 3_600_000L,
                false, start, end, phoneZone));
    }
}
