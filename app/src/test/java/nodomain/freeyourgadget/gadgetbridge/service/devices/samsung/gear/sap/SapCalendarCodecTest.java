package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

import static org.junit.Assert.*;

public class SapCalendarCodecTest {
    private static CalendarEvent event(final long start, final String title) {
        final CalendarEvent event = new CalendarEvent(start, start + 3_600_000L, 91, 42,
                title, "first\nsecond", "Room", "Personal", "local", 0, 0, false,
                "owner@example.invalid", "LOCAL", "5", null, 0, 1, "Europe/Rome");
        event.setRemindersAbsoluteTs(Collections.singletonList(start - 900_000L));
        return event;
    }

    @Test
    public void capturedRequestAndResponseGrammar() throws Exception {
        final JSONObject request = new JSONObject("{\"msgId\":\"calendar-listwithoption-req\",\"startTime\":1790028000000,\"endTime\":1790114399000,\"maxTextLength\":101,\"maxEventCnt\":1500,\"maxAlldayEventCnt\":750,\"reminder\":\"on\"}");
        final SapCalendarCodec.RequestRange range = SapCalendarCodec.parseRange(request);
        assertEquals(1790028000000L, range.start);
        assertEquals(1500, range.maxEvents);
        assertTrue(range.reminders);
        final JSONObject empty = new JSONObject(SapCalendarCodec.listResponse(Collections.emptyList(), 101,
                TimeZone.getTimeZone("Europe/Rome"), true));
        assertEquals("calendar-listwithoption-rsp", empty.getString("msgId"));
        assertEquals(1, empty.getInt("specVersion"));
        assertEquals(0, empty.getInt("count"));
        assertEquals(0, empty.getJSONArray("list").length());
    }

    @Test
    public void eventUsesSamsungInstanceIdentityLocalTimeAndReminder() throws Exception {
        final long start = 1_797_158_400_000L;
        final TimeZone rome = TimeZone.getTimeZone("Europe/Rome");
        final JSONObject response = new JSONObject(SapCalendarCodec.listResponse(
                Collections.singletonList(event(start, "Meeting")), 101, rome, true));
        assertEquals("calendar-listwithoption-rsp", response.getString("msgId"));
        assertEquals(1, response.getInt("count"));
        assertEquals(1, response.getJSONArray("list").length());
        final String vcs = response.getJSONArray("list").getJSONObject(0).getString("vcs");
        assertTrue(vcs.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n\r\n"));
        assertTrue(vcs.startsWith("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:42\r\n"));
        assertTrue(vcs.contains("ORIGINALSTART:" + start + "\r\n"));
        assertTrue(vcs.contains("TIMEZONE:Europe/Rome\r\n"));
        assertTrue(vcs.contains("SUMMARY:Meeting\r\n"));
        assertTrue(vcs.contains("DTSTART:20261213T114000Z\r\n"));
        assertTrue(vcs.contains("DTEND:20261213T124000Z\r\n"));
        assertTrue(vcs.contains("DESCRIPTION:first\r\n second\r\n"));
        assertTrue(vcs.contains("TRIGGER:-PT15M\r\n"));
        assertTrue(vcs.contains("EDITABLE:0\r\nDELETABLE:0\r\n"));
        assertFalse(vcs.contains("WATCHUID:"));
        assertFalse(vcs.contains("RRULE:"));
        assertFalse(vcs.contains("VERSION:"));
        final String utc = SapCalendarCodec.vcalendar(event(start, "Meeting"), 101,
                TimeZone.getTimeZone("UTC"), false);
        assertTrue(utc.contains("DTSTART:20261213T104000Z\r\n"));
        assertFalse(utc.contains("BEGIN:VALARM"));
        assertFalse(vcs.equals(utc));
    }

    @Test
    public void zeroAndFiveMinuteRemindersUseSamsungIntegerActionAndMinuteTrigger() {
        final CalendarEvent current = event(1_797_158_400_000L, "Synthetic");
        current.setRemindersAbsoluteTs(Arrays.asList(current.getBegin(), current.getBegin() - 300_000L));
        final String vcs = SapCalendarCodec.vcalendar(current, 101, TimeZone.getTimeZone("Europe/Rome"), true);
        assertTrue(vcs.contains("BEGIN:VALARM\r\nACTION:1\r\nTRIGGER:-PT0M\r\n"
                + "ACTION:1\r\nTRIGGER:-PT5M\r\nEND:VALARM\r\n"));
        assertFalse(SapCalendarCodec.vcalendar(current, 101, TimeZone.getTimeZone("Europe/Rome"), false)
                .contains("BEGIN:VALARM"));
    }

    @Test
    public void updateDeletionAndReliableFrame() throws Exception {
        final CalendarEvent previous = event(1_797_158_400_000L, "Old");
        final JSONObject update = new JSONObject(SapCalendarCodec.updateIndication(
                Collections.singletonList(event(1_797_162_000_000L, "New")),
                Collections.singletonList(previous), 7, 101, TimeZone.getTimeZone("UTC"), false));
        assertEquals(1, update.getInt("count"));
        assertEquals(1, update.getInt("deleted_count"));
        assertEquals(7, update.getLong("sequence"));
        assertTrue(update.getJSONArray("list").getJSONObject(1).getString("deleted_vcs").contains("SUMMARY:Old"));
        final byte[] frame = SapCalendarCodec.encodeFrame(0x1234, update.toString());
        assertArrayEquals(new byte[]{0x12, 0x34, 0}, Arrays.copyOf(frame, 3));
        assertEquals("calendar-update-ind", SapCalendarCodec.decodeFrame(frame).messageId);
        assertEquals(0x1234, SapCalendarCodec.decodeFrame(frame).sequence);
        assertThrows(IllegalArgumentException.class, () -> SapCalendarCodec.decodeFrame(
                "\u0000\u0001\u0004{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void dateChangeUsesSourceDefaultLimitsWhenProcessHasNoPriorRange() throws Exception {
        final JSONObject request = new JSONObject().put("msgId", "calendar-datechanged-req")
                .put("startTime", 1_780_000_000_000L).put("endTime", 1_780_086_400_000L);
        final SapCalendarCodec.RequestRange range = SapCalendarCodec.parseDateChangedRange(request, null);
        assertEquals(1500, range.maxEvents);
        assertEquals(750, range.maxAllDay);
        assertEquals(101, range.maxTextLength);
        assertTrue(range.reminders);
        final JSONObject response = new JSONObject(SapCalendarCodec.dateChangedResponse());
        assertEquals("calendar-datechanged-rsp", response.getString("msgId"));
        assertEquals("success", response.getString("result"));
        assertEquals(0, response.getInt("reason"));
    }
}
