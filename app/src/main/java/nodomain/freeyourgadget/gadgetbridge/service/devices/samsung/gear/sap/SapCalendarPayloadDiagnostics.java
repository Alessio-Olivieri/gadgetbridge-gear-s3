package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** Describes the actual serialized payload without exposing calendar text or account data. */
public final class SapCalendarPayloadDiagnostics {
    private static final Set<String> ENVELOPE_KEYS = new HashSet<>(Arrays.asList(
            "msgId", "specVersion", "reason", "result", "count", "deleted_count", "sequence", "list"));
    private static final Set<String> EVENT_KEYS = new HashSet<>(Arrays.asList(
            "BEGIN", "END", "UID", "WATCHUID", "DESCRIPTION", "DTEND", "DTSTART", "LOCATION",
            "SUMMARY", "COLOR", "ALLDAY", "TIMEZONE", "STATUS", "ORIGINALSTART", "ACTION",
            "TRIGGER", "GEO", "REPEAT", "EDITABLE", "DELETABLE", "ORGANIZER"));
    private static final Set<String> NUMERIC_KEYS = new HashSet<>(Arrays.asList(
            "UID", "COLOR", "ALLDAY", "ORIGINALSTART", "ACTION", "REPEAT", "EDITABLE", "DELETABLE"));

    private SapCalendarPayloadDiagnostics() { }

    public static JSONObject describe(final String json) throws JSONException {
        final JSONObject payload = new JSONObject(json);
        final String msgId = payload.optString("msgId");
        if (!"calendar-listwithoption-rsp".equals(msgId) && !"calendar-update-ind".equals(msgId)) {
            throw new IllegalArgumentException("Not a calendar event payload");
        }
        final TreeMap<String, String> shape = new TreeMap<>();
        int unknownKeys = 0;
        final java.util.Iterator<String> keys = payload.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (ENVELOPE_KEYS.contains(key)) shape.put(key, type(payload.get(key)));
            else unknownKeys++;
        }
        final JSONArray entries = payload.getJSONArray("list");
        final JSONArray events = new JSONArray();
        int vcsCount = 0;
        int deletedVcsCount = 0;
        for (int i = 0; i < entries.length(); i++) {
            final JSONObject entry = entries.getJSONObject(i);
            for (final String key : Arrays.asList("vcs", "deleted_vcs")) {
                if (!entry.has(key)) continue;
                if ("vcs".equals(key)) vcsCount++; else deletedVcsCount++;
                final String vcs = entry.getString(key);
                events.put(new JSONObject().put("path", "list[" + i + "]." + key)
                        .put("entryKeyCount", entry.length())
                        .put("utf16Chars", vcs.length()).put("utf8Bytes", vcs.getBytes(StandardCharsets.UTF_8).length)
                        .put("crlfOnly", vcs.replace("\r\n", "").indexOf('\n') < 0
                                && vcs.replace("\r\n", "").indexOf('\r') < 0)
                        .put("trailingDoubleCrlf", vcs.endsWith("\r\n\r\n"))
                        .put("structure", structure(vcs)));
            }
        }
        return new JSONObject().put("msgId", msgId).put("shape", new JSONObject(shape))
                .put("unknownOuterKeys", unknownKeys)
                .put("specVersion", payload.getInt("specVersion")).put("reason", payload.getInt("reason"))
                .put("resultSuccess", "success".equals(payload.optString("result")))
                .put("declaredCount", payload.getInt("count")).put("listLength", entries.length())
                .put("vcsCount", vcsCount).put("deletedVcsCount", deletedVcsCount)
                .put("bytes", json.getBytes(StandardCharsets.UTF_8).length).put("events", events);
    }

    private static String type(final Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof JSONArray) return "array";
        if (value instanceof JSONObject) return "object";
        if (value instanceof Boolean) return "boolean";
        return "null";
    }

    private static JSONArray structure(final String vcs) {
        final JSONArray result = new JSONArray();
        for (final String line : vcs.split("\r\n", -1)) {
            if (line.isEmpty()) { result.put("<empty>"); continue; }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                result.put("<folded text chars=" + line.length() + ">");
                continue;
            }
            final int colon = line.indexOf(':');
            final String key = colon < 0 ? "" : line.substring(0, colon);
            if (!EVENT_KEYS.contains(key)) { result.put("<unknown line chars=" + line.length() + ">"); continue; }
            final String value = line.substring(colon + 1);
            final boolean safe = (NUMERIC_KEYS.contains(key) && value.matches("-?[0-9]{1,20}"))
                    || (("DTSTART".equals(key) || "DTEND".equals(key)) && value.matches("[0-9]{8}T[0-9]{6}Z"))
                    || (("BEGIN".equals(key) || "END".equals(key))
                        && Arrays.asList("VCALENDAR", "VEVENT", "VALARM").contains(value));
            result.put(key + ":" + (safe ? value : "<redacted chars=" + value.length() + ">"));
        }
        return result;
    }
}
