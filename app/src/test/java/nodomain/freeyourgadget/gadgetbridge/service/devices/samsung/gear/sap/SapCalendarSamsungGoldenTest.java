package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.*;

/**
 * Calendar serialization fixtures for read-only events with no watch UID, geo,
 * recurrence or alarm. These fixtures test serialization, not watch acceptance.
 */
public class SapCalendarSamsungGoldenTest {
    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    private static final long TIMED_START = 1790935200000L; // 2026-10-02 10:00 UTC / 12:00 Rome
    private static final long ALL_DAY_START = 1793491200000L; // 2026-11-01 00:00 UTC
    private static final String TIMED = "BEGIN:VCALENDAR\r\n"
            + "BEGIN:VEVENT\r\nUID:42\r\n"
            + "DTEND:20261002T130000Z\r\nDTSTART:20261002T120000Z\r\n"
            + "SUMMARY:Calendar fixture\r\nALLDAY:0\r\nTIMEZONE:Europe/Rome\r\n"
            + "ORIGINALSTART:1790935200000\r\nREPEAT:0\r\nEDITABLE:0\r\nDELETABLE:0\r\n"
            + "ORGANIZER:fixture@example.invalid\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n\r\n";
    private static final String ALL_DAY = "BEGIN:VCALENDAR\r\n"
            + "BEGIN:VEVENT\r\nUID:43\r\n"
            + "DTEND:20261102T010000Z\r\nDTSTART:20261101T010000Z\r\n"
            + "SUMMARY:All-day fixture\r\nALLDAY:1\r\nTIMEZONE:UTC\r\n"
            + "ORIGINALSTART:1793491200000\r\nREPEAT:0\r\nEDITABLE:0\r\nDELETABLE:0\r\n"
            + "ORGANIZER:fixture@example.invalid\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n\r\n";

    private static CalendarEvent event(final long begin, final long end, final long instanceId,
                                       final long eventId, final boolean allDay, final String zone,
                                       final String title, final String description, final String location) {
        return new CalendarEvent(begin, end, instanceId, eventId, title, description, location,
                "Fixture", "fixture@example.invalid", 0, 0, allDay, "fixture@example.invalid",
                "LOCAL", "7", null, 0, 0, zone);
    }

    private static CalendarEvent timed() {
        return event(TIMED_START, TIMED_START + 3_600_000L, 900, 42, false, "Europe/Rome",
                "Calendar fixture", null, null);
    }

    @Test
    public void timedRomeEventMatchesExpectedVcalendarSerialization() {
        assertEquals(TIMED, SapCalendarCodec.vcalendar(timed(), 101, ROME, false));
    }

    @Test
    public void utcAllDayEventStillUsesPhoneFormatterAndExplicitAllDayProperty() {
        final CalendarEvent event = event(ALL_DAY_START, ALL_DAY_START + 86_400_000L, 901, 43,
                true, "UTC", "All-day fixture", null, null);
        assertEquals(ALL_DAY, SapCalendarCodec.vcalendar(event, 101, ROME, false));
        // Samsung formats Instances timestamps in the phone zone, even for all-day UTC events.
        final String utcPhone = ALL_DAY.replace("20261102T010000Z", "20261102T000000Z")
                .replace("20261101T010000Z", "20261101T000000Z");
        assertEquals(utcPhone, SapCalendarCodec.vcalendar(event, 101, TimeZone.getTimeZone("UTC"), false));
    }

    @Test
    public void completeNonemptyListMatchesSamsungEnvelopeAndVcsCollection() throws Exception {
        final String expected = "{\"msgId\":\"calendar-listwithoption-rsp\",\"specVersion\":1,"
                + "\"reason\":0,\"result\":\"success\",\"count\":1,\"list\":[{\"vcs\":"
                + JSONObject.quote(TIMED) + "}]}";
        final String actual = SapCalendarCodec.listResponse(Collections.singletonList(timed()), 101, ROME, false);
        final JSONObject wanted = new JSONObject(expected);
        final JSONObject got = new JSONObject(actual);
        assertEquals(wanted.length(), got.length()); // No type, sequence, or extra wrapper for list responses.
        for (final String key : new String[]{"msgId", "specVersion", "reason", "result", "count"}) {
            assertEquals(wanted.get(key), got.get(key));
        }
        assertEquals(1, got.getJSONArray("list").length());
        assertEquals(1, got.getJSONArray("list").getJSONObject(0).length());
        assertArrayEquals(TIMED.getBytes(StandardCharsets.UTF_8),
                got.getJSONArray("list").getJSONObject(0).getString("vcs").getBytes(StandardCharsets.UTF_8));
        final byte[] wire = SapCalendarCodec.encodeFrame(1, actual);
        assertEquals(TIMED, SapCalendarCodec.decodeFrame(wire).payload.getJSONArray("list")
                .getJSONObject(0).getString("vcs"));
    }

    @Test
    public void descriptionFoldingPunctuationAndJsonEscapingMatchSamsung() throws Exception {
        final CalendarEvent event = event(TIMED_START, TIMED_START + 3_600_000L, 900, 42,
                false, "Europe/Rome", "Café, \"fixture\"; test", "first\r\nsecond\nthird,;\\",
                "Room, A; B\\C");
        final String expected = TIMED.replace("DTEND:", "DESCRIPTION:first\r\n second\r\n third,;\\\r\nDTEND:")
                .replace("SUMMARY:Calendar fixture", "LOCATION:Room, A; B\\C\r\nSUMMARY:Café, \"fixture\"; test");
        final String json = SapCalendarCodec.listResponse(Collections.singletonList(event), 101, ROME, false);
        assertEquals(expected, new JSONObject(json).getJSONArray("list").getJSONObject(0).getString("vcs"));
        assertFalse(json.contains("\r"));
        assertFalse(json.contains("\n")); // CRLF is escaped by the JSON layer, restored by parsing.
        assertTrue(json.contains("\\r\\n"));
    }

    @Test
    public void uidIsAndroidEventIdAndOriginalStartDistinguishesInstances() {
        final CalendarEvent next = event(TIMED_START + 86_400_000L, TIMED_START + 90_000_000L,
                123456, 42, false, "Europe/Rome", "Calendar fixture", null, null);
        final String expected = TIMED.replace("20261002", "20261003")
                .replace("ORIGINALSTART:1790935200000", "ORIGINALSTART:1791021600000");
        assertEquals(expected, SapCalendarCodec.vcalendar(next, 101, ROME, false));
        assertFalse(expected.contains("UID:123456"));
    }

    @Test
    public void dstOverlapUsesPhoneWallClockWhileOriginalStartRemainsDistinct() {
        final long before = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli();
        final long after = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli();
        for (final long start : new long[]{before, after}) {
            final String vcs = SapCalendarCodec.vcalendar(event(start, start + 60_000L, 1, 42,
                    false, "UTC", "fixture", null, null), 101, ROME, false);
            assertTrue(vcs.contains("DTSTART:20261025T023000Z\r\n"));
            assertTrue(vcs.contains("TIMEZONE:UTC\r\n"));
            assertTrue(vcs.contains("ORIGINALSTART:" + start + "\r\n"));
        }
    }

    @Test
    public void diagnosticsDescribeActualCollectionAndNeverExposePrivateText() throws Exception {
        final CalendarEvent event = event(TIMED_START, TIMED_START + 3_600_000L, 1, 42, false,
                "Europe/Rome", "PRIVATE_TITLE", "PRIVATE_DESCRIPTION\nUID:PRIVATE_FOLDED_TEXT", "PRIVATE_LOCATION");
        final String json = SapCalendarCodec.listResponse(Collections.singletonList(event), 101, ROME, false);
        final JSONObject diagnostic = SapCalendarPayloadDiagnostics.describe(json);
        assertEquals(1, diagnostic.getInt("declaredCount"));
        assertEquals(1, diagnostic.getInt("listLength"));
        assertEquals(1, diagnostic.getInt("vcsCount"));
        assertEquals("array", diagnostic.getJSONObject("shape").getString("list"));
        final JSONObject entry = diagnostic.getJSONArray("events").getJSONObject(0);
        assertEquals("list[0].vcs", entry.getString("path"));
        assertTrue(entry.getBoolean("crlfOnly"));
        assertTrue(entry.getBoolean("trailingDoubleCrlf"));
        assertEquals(new JSONObject(json).getJSONArray("list").getJSONObject(0).getString("vcs")
                .getBytes(StandardCharsets.UTF_8).length, entry.getInt("utf8Bytes"));
        assertFalse(diagnostic.toString().contains("PRIVATE"));
        assertFalse(diagnostic.toString().contains("fixture@example.invalid"));
        assertTrue(diagnostic.toString().contains("UID:42"));
        final JSONObject update = SapCalendarPayloadDiagnostics.describe(SapCalendarCodec.updateIndication(
                Collections.singletonList(event), Collections.singletonList(timed()), 3, 101, ROME, false));
        assertEquals(2, update.getInt("listLength"));
        assertEquals(1, update.getInt("vcsCount"));
        assertEquals(1, update.getInt("deletedVcsCount"));
    }
}
