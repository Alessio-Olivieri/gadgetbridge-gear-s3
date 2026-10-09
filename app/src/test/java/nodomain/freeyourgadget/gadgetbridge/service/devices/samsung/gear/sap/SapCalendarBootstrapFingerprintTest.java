package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class SapCalendarBootstrapFingerprintTest {
    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");

    private static CalendarEvent event(final long id, final long start,
                                       final String title, final String description) {
        return new CalendarEvent(start, start + 3_600_000, start, id, title, description, "",
                "Synthetic", "local", 0, 0, false, "organizer@example.invalid", "LOCAL",
                "1", null, 0, 0, "Europe/Rome");
    }

    private static String hash(final CalendarEvent... values) {
        return SapCalendarBootstrapFingerprint.calculate(Arrays.asList(values), 101, ROME, true);
    }

    @Test
    public void timedAndAllDayGoldenHashesMatchTheDocumentedHostByteFormat() {
        final CalendarEvent timed = event(33, 1_790_935_200_000L, "Synthetic", null);
        final long start = 1_798_243_200_000L;
        final CalendarEvent allDay = new CalendarEvent(start, start + 86_400_000L, start, 9,
                "Synthetic", null, null, "Synthetic", "local", 0, 0, true,
                "organizer@example.invalid", "LOCAL", "1", null, 0, 0, "UTC");
        assertEquals("aae1f46cf0ffa0ac384cc5b784d0fe7f29fafd18eaa3b7f20b7f2609e3d73c9d", hash(timed));
        assertEquals("049a0a9ada9202263b99eef18d81bf24bd81922a1933dea176048b62c6fc227c", hash(allDay));
    }

    @Test
    public void deterministicOrderAndPrivateTextNeverAppearInDiagnosticValue() {
        final CalendarEvent a = event(33, 1_790_935_200_000L, "private test title", "private test description");
        final CalendarEvent b = event(34, 1_791_021_600_000L, "different", null);
        final String value = hash(a, b);
        assertEquals(value, hash(b, a));
        assertEquals(64, value.length());
        assertFalse(value.contains("private"));
    }

    @Test
    public void changedWatchVisibleTextIdentityAndTimeChangeFingerprint() {
        final CalendarEvent original = event(33, 1_790_935_200_000L, "Original", null);
        final String value = hash(original);
        assertFalse(value.equals(hash(event(33, original.getBegin(), "Renamed", null))));
        assertFalse(value.equals(hash(event(35, original.getBegin(), "Original", null))));
        assertFalse(value.equals(hash(event(33, original.getBegin() + 3_600_000, "Original", null))));
    }

    @Test
    public void truncatedDescriptionSuffixDoesNotChangeWireButFullMirrorRetainsIt() {
        final String prefix = String.join("", Collections.nCopies(101, "x"));
        assertEquals(hash(event(9, 1_798_243_200_000L, "Test", prefix + "old")),
                hash(event(9, 1_798_243_200_000L, "Test", prefix + "new")));
    }

    @Test
    public void allDayAndEventTimezoneArePartOfTheWireFingerprint() {
        final long start = 1_798_243_200_000L;
        final CalendarEvent allDay = new CalendarEvent(start, start + 86_400_000L, start, 9,
                "Synthetic", null, null, "Synthetic", "local", 0, 0, true,
                "organizer@example.invalid", "LOCAL", "1", null, 0, 0, "UTC");
        final CalendarEvent timed = new CalendarEvent(start, start + 86_400_000L, start, 9,
                "Synthetic", null, null, "Synthetic", "local", 0, 0, false,
                "organizer@example.invalid", "LOCAL", "1", null, 0, 0, "UTC");
        final CalendarEvent otherZone = new CalendarEvent(start, start + 86_400_000L, start, 9,
                "Synthetic", null, null, "Synthetic", "local", 0, 0, true,
                "organizer@example.invalid", "LOCAL", "1", null, 0, 0, "Europe/Rome");
        assertFalse(hash(allDay).equals(hash(timed)));
        assertFalse(hash(allDay).equals(hash(otherZone)));
    }

    @Test
    public void unmappableReminderColorStatusAndDuplicateIdentityAreRefused() {
        final CalendarEvent alert = event(33, 1_790_935_200_000L, "Test", null);
        alert.setRemindersAbsoluteTs(Collections.singletonList(alert.getBegin() - 600_000));
        assertEquals("Unverifiable Calendar property: BEGIN",
                assertThrows(IllegalArgumentException.class, () -> hash(alert)).getMessage());
        final CalendarEvent colored = new CalendarEvent(alert.getBegin(), alert.getEnd(), 1, 33,
                "Test", null, "", "Synthetic", "local", 0, 0xff445566, false,
                null, "LOCAL", "1", null, 0, 0, "Europe/Rome");
        assertThrows(IllegalArgumentException.class, () -> hash(colored));
        final CalendarEvent plain = event(33, alert.getBegin(), "Test", null);
        assertThrows(IllegalArgumentException.class, () -> hash(plain, plain));
    }
}
