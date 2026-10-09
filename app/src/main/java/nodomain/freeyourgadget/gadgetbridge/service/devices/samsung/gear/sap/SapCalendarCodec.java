package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

/** Clear, reliable channel-101 calendar JSON and Samsung vCalendar grammar. */
public final class SapCalendarCodec {
    private SapCalendarCodec() { }

    public static byte[] encodeFrame(final int sequence, final String json) {
        if (sequence < 1 || sequence > 0xffff || json == null) throw new IllegalArgumentException("Invalid calendar frame");
        final byte[] body = json.getBytes(StandardCharsets.UTF_8);
        final byte[] frame = new byte[body.length + 3];
        frame[0] = (byte) (sequence >>> 8);
        frame[1] = (byte) sequence;
        frame[2] = 0; // clear, uncompressed, unfragmented SDK service channel
        System.arraycopy(body, 0, frame, 3, body.length);
        return frame;
    }

    public static Frame decodeFrame(final byte[] frame) throws JSONException {
        if (frame == null || frame.length < 5 || frame[2] != 0) throw new IllegalArgumentException("Unsupported calendar SDK frame");
        final int sequence = ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
        if (sequence < 1) throw new IllegalArgumentException("Invalid calendar reliable sequence");
        final JSONObject payload = new JSONObject(new String(frame, 3, frame.length - 3, StandardCharsets.UTF_8));
        return new Frame(sequence, payload, payload.getString("msgId"));
    }

    public static RequestRange parseRange(final JSONObject request) throws JSONException {
        final long start = request.getLong("startTime");
        final long end = request.getLong("endTime");
        final int maxEvents = request.optInt("maxEventCnt", 0);
        final int maxAllDay = request.optInt("maxAlldayEventCnt", 0);
        if (start < 0 || end <= start || maxEvents < 1 || maxAllDay < 0 || maxAllDay > maxEvents) {
            throw new IllegalArgumentException("Invalid calendar list range or limits");
        }
        return new RequestRange(start, end, request.optInt("maxTextLength", 0), maxEvents, maxAllDay,
                "on".equals(request.optString("reminder", "off")));
    }

    /** Samsung's date-change request supplies a new window, not list limits. */
    public static RequestRange parseDateChangedRange(final JSONObject request, final RequestRange previous)
            throws JSONException {
        final long start = request.getLong("startTime");
        final long end = request.getLong("endTime");
        // Use default limits only if this process lacks a prior list request.
        final int maxTextLength = previous == null ? 101 : previous.maxTextLength;
        final int maxEvents = previous == null ? 1500 : previous.maxEvents;
        final int maxAllDay = previous == null ? 750 : previous.maxAllDay;
        final boolean reminders = previous == null || previous.reminders;
        if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid calendar date-change range");
        return new RequestRange(start, end, maxTextLength, maxEvents, maxAllDay, reminders);
    }

    public static String dateChangedResponse() throws JSONException {
        return new JSONObject().put("msgId", "calendar-datechanged-rsp")
                .put("result", "success").put("reason", 0).toString();
    }

    public static String firstDayResponse(final int firstDay) throws JSONException {
        return new JSONObject().put("msgId", "calendar-firstdayofweek-rsp")
                .put("data", Integer.toString(firstDay)).toString();
    }

    public static String homeTimezoneResponse() throws JSONException {
        return new JSONObject().put("msgId", "calendar-home-timezone-rsp")
                .put("preferences_home_tz_enabled", false).toString();
    }

    public static String changeResponse() throws JSONException {
        return new JSONObject().put("msgId", "calendar-change-rsp")
                .put("result", "success").put("reason", 0).toString();
    }

    public static String sequenceResponse(final long sequence) throws JSONException {
        return new JSONObject().put("msgId", "calendar-sequence-rsp")
                .put("sequence", sequence).toString();
    }

    public static String listResponse(final List<CalendarEvent> events, final int maxTextLength,
                                      final TimeZone phoneZone, final boolean reminders) throws JSONException {
        final JSONArray list = new JSONArray();
        for (final CalendarEvent event : events) list.put(new JSONObject().put("vcs", vcalendar(event, maxTextLength, phoneZone, reminders)));
        return new JSONObject().put("msgId", "calendar-listwithoption-rsp")
                .put("specVersion", 1).put("reason", 0).put("result", "success")
                .put("count", events.size()).put("list", list).toString();
    }

    public static String updateIndication(final List<CalendarEvent> changed, final List<CalendarEvent> deleted,
                                          final long sequence, final int maxTextLength, final TimeZone phoneZone,
                                          final boolean reminders) throws JSONException {
        final JSONArray list = new JSONArray();
        for (final CalendarEvent event : changed) list.put(new JSONObject().put("vcs", vcalendar(event, maxTextLength, phoneZone, reminders)));
        for (final CalendarEvent event : deleted) list.put(new JSONObject().put("deleted_vcs", vcalendar(event, maxTextLength, phoneZone, reminders)));
        return new JSONObject().put("msgId", "calendar-update-ind")
                .put("specVersion", 1).put("reason", 0).put("result", "success")
                .put("count", changed.size()).put("deleted_count", deleted.size())
                .put("sequence", sequence).put("list", list).toString();
    }

    public static String vcalendar(final CalendarEvent event, final int maxTextLength, final TimeZone phoneZone,
                                   final boolean reminders) {
        if (event == null || phoneZone == null || event.getEventId() < 0 || event.getBegin() < 0
                || event.getEnd() <= event.getBegin()) throw new IllegalArgumentException("Invalid calendar event");
        final StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n");
        line(out, "UID", Long.toString(event.getEventId()));
        if (event.getDescription() != null && !event.getDescription().isEmpty()) {
            line(out, "DESCRIPTION", foldDescription(truncate(event.getDescription(), maxTextLength)));
        }
        line(out, "DTEND", date(event.getEnd(), phoneZone));
        line(out, "DTSTART", date(event.getBegin(), phoneZone));
        if (event.getLocation() != null && !event.getLocation().isEmpty()) line(out, "LOCATION", clean(truncate(event.getLocation(), maxTextLength)));
        if (event.getTitle() != null && !event.getTitle().isEmpty()) line(out, "SUMMARY", clean(truncate(event.getTitle(), maxTextLength)));
        if (event.getColor() != 0) line(out, "COLOR", Integer.toString(event.getColor()));
        line(out, "ALLDAY", event.isAllDay() ? "1" : "0");
        if (event.getTimezone() != null && !event.getTimezone().isEmpty()) line(out, "TIMEZONE", clean(event.getTimezone()));
        if (event.getAttendingStatus() != 0) {
            line(out, "STATUS", event.getAttendingStatus() == 1 ? "CONFIRMED"
                    : event.getAttendingStatus() == 2 ? "CANCELLED" : "TENTATIVE");
        }
        // Set instanceStart for all events, including nonrecurring events.
        line(out, "ORIGINALSTART", Long.toString(event.getBegin()));
        if (reminders && !event.getRemindersAbsoluteTs().isEmpty()) {
            out.append("BEGIN:VALARM\r\n");
            for (final long reminder : event.getRemindersAbsoluteTs()) {
                final long minutes = (event.getBegin() - reminder) / 60_000L;
                if (minutes < 0) continue;
                line(out, "ACTION", "1");
                line(out, "TRIGGER", "-PT" + minutes + "M");
            }
            out.append("END:VALARM\r\n");
        }
        line(out, "REPEAT", event.getRrule() != null && !event.getRrule().isEmpty() ? "1" : "0");
        line(out, "EDITABLE", "0");
        line(out, "DELETABLE", "0");
        line(out, "ORGANIZER", clean(event.getOrganizer() == null ? "null" : event.getOrganizer()));
        out.append("END:VEVENT\r\nEND:VCALENDAR\r\n\r\n");
        return out.toString();
    }

    private static String date(final long millis, final TimeZone zone) {
        final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ENGLISH);
        format.setTimeZone(zone); // Use the default time zone with a literal Z suffix.
        return format.format(new Date(millis));
    }

    private static void line(final StringBuilder out, final String key, final String value) {
        out.append(key).append(':').append(value).append("\r\n");
    }

    private static String clean(final String value) { return value.replace('\r', ' ').replace('\n', ' '); }
    private static String foldDescription(final String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n ");
    }
    private static String truncate(final String text, final int max) {
        if (max <= 0 || text.length() <= max) return text;
        final int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    public static final class Frame {
        public final int sequence;
        public final JSONObject payload;
        public final String messageId;
        private Frame(final int sequence, final JSONObject payload, final String messageId) {
            this.sequence = sequence; this.payload = payload; this.messageId = messageId;
        }
    }

    public static final class RequestRange {
        public final long start, end;
        public final int maxTextLength, maxEvents, maxAllDay;
        public final boolean reminders;
        private RequestRange(final long start, final long end, final int maxTextLength, final int maxEvents,
                             final int maxAllDay, final boolean reminders) {
            this.start = start; this.end = end; this.maxTextLength = maxTextLength;
            this.maxEvents = maxEvents; this.maxAllDay = maxAllDay; this.reminders = reminders;
        }
    }
}
