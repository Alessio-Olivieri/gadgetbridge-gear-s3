package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;

/**
 * Deliberately narrow DB-comparable projection of Samsung's serialized vCalendar.
 * Unsupported DB mappings refuse the debug import; normal Calendar encoding is untouched.
 */
public final class SapCalendarBootstrapFingerprint {
    private static final byte[] DOMAIN = "GearS3CalendarWatchDbWireV1\n".getBytes(StandardCharsets.US_ASCII);
    private static final List<String> FIELDS = Arrays.asList("UID", "ORIGINALSTART", "DTSTART", "DTEND",
            "ALLDAY", "TIMEZONE", "SUMMARY", "DESCRIPTION", "LOCATION", "COLOR", "STATUS",
            "REPEAT", "ORGANIZER", "EDITABLE", "DELETABLE");
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList("UID", "ORIGINALSTART", "DTSTART",
            "DTEND", "ALLDAY", "REPEAT", "ORGANIZER", "EDITABLE", "DELETABLE"));

    private SapCalendarBootstrapFingerprint() { }

    public static String calculate(final List<CalendarEvent> events, final int maxTextLength,
                                   final TimeZone phoneZone, final boolean reminders) {
        if (events == null || phoneZone == null || maxTextLength < 0) throw new IllegalArgumentException("Invalid fingerprint input");
        final List<CalendarEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(CalendarEvent::getEventId).thenComparingLong(CalendarEvent::getBegin));
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (final NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
        digest.update(DOMAIN);
        digest.update(ByteBuffer.allocate(4).putInt(ordered.size()).array());
        String previous = null;
        for (final CalendarEvent event : ordered) {
            final String identity = SapCalendarSnapshot.key(event);
            if (identity.equals(previous)) throw new IllegalArgumentException("Duplicate logical Calendar event");
            previous = identity;
            final Map<String, String> fields = parseWire(SapCalendarCodec.vcalendar(event,
                    maxTextLength, phoneZone, reminders));
            if (!Long.toString(event.getEventId()).equals(fields.get("UID"))
                    || !Long.toString(event.getBegin()).equals(fields.get("ORIGINALSTART"))) {
                throw new IllegalArgumentException("Calendar identity changed during encoding");
            }
            for (final String key : FIELDS) {
                final byte[] value = fields.getOrDefault(key, "").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
                digest.update(value);
            }
        }
        final byte[] result = digest.digest();
        final char[] hex = "0123456789abcdef".toCharArray();
        final StringBuilder output = new StringBuilder(result.length * 2);
        for (final byte b : result) {
            output.append(hex[(b >>> 4) & 15]).append(hex[b & 15]);
        }
        return output.toString();
    }

    private static Map<String, String> parseWire(final String wire) {
        final Map<String, String> values = new HashMap<>();
        String previous = null;
        for (final String line : wire.split("\r\n", -1)) {
            if (line.isEmpty() || line.equals("BEGIN:VCALENDAR") || line.equals("BEGIN:VEVENT")
                    || line.equals("END:VEVENT") || line.equals("END:VCALENDAR")) continue;
            if (line.startsWith(" ")) {
                if (!"DESCRIPTION".equals(previous)) throw new IllegalArgumentException("Unsupported Calendar folding");
                values.put(previous, values.get(previous) + "\n" + line.substring(1));
                continue;
            }
            final int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("Invalid Calendar property");
            final String key = line.substring(0, colon);
            if (!FIELDS.contains(key) || values.containsKey(key)) {
                throw new IllegalArgumentException("Unverifiable Calendar property: " + key);
            }
            values.put(key, line.substring(colon + 1));
            previous = key;
        }
        if (!values.keySet().containsAll(REQUIRED)
                || !"0".equals(values.get("REPEAT"))
                || !"0".equals(values.get("EDITABLE"))
                || !"0".equals(values.get("DELETABLE"))
                || !"".equals(values.getOrDefault("COLOR", ""))
                || !"".equals(values.getOrDefault("STATUS", ""))) {
            throw new IllegalArgumentException("Unverifiable Calendar content");
        }
        return values;
    }
}
