package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** b2contacts records and contact-file encryption. */
public final class GearContactsCodec {
    private GearContactsCodec() { }

    public enum Control { INITIAL_SYNC, RESUME_SYNC, ENCRYPTION_KEY, ALL_LOG_IDS, UNKNOWN }

    /** Control strings use these tokens; the crypt_ prefix selects encrypted files. */
    public static Control control(final String message) {
        if (message == null) return Control.UNKNOWN;
        final String command = message.startsWith("crypt_") ? message.substring(6) : message;
        switch (command) {
            case "Request_Initial_Sync_Contact": return Control.INITIAL_SYNC;
            case "Request_Resume_Sync_Contact": return Control.RESUME_SYNC;
            case "Request_Encryption_Key": return Control.ENCRYPTION_KEY;
            case "Request_get_all_log_ids": return Control.ALL_LOG_IDS;
            default: return Control.UNKNOWN;
        }
    }

    /** Control responses use a string requestId and a success result. */
    public static JSONObject logActivationResponse(final JSONObject request) throws JSONException {
        if (!"logs-activatesync-req".equals(request.getString("msgId"))) {
            throw new IllegalArgumentException("Not a log activation request");
        }
        request.getBoolean("value");
        return new JSONObject().put("msgId", "logs-activatesync-rsp")
                .put("requestId", request.getString("requestId")).put("result", "success");
    }

    public static String resumeResponse(final boolean hasTransportBaseline) {
        return hasTransportBaseline ? "Response_Resume_Sync_Contact" : "Response_Initial_Sync_Required_Contact";
    }

    public static final class Number {
        public final String value, normalized, label;
        public final int type;
        public final boolean primary;
        public Number(final String value, final String normalized, final int type, final String label, final boolean primary) {
            this.value = value; this.normalized = normalized; this.type = type; this.label = label; this.primary = primary;
        }
    }

    public static final class Contact {
        public final long rawId, contactId, displayRawId;
        public final String displayName, first, last;
        public final boolean favorite;
        public final List<Number> numbers;
        public Contact(final long rawId, final long contactId, final long displayRawId, final String displayName,
                       final String first, final String last, final boolean favorite, final List<Number> numbers) {
            this.rawId = rawId; this.contactId = contactId; this.displayRawId = displayRawId;
            this.displayName = displayName; this.first = first; this.last = last; this.favorite = favorite;
            this.numbers = Collections.unmodifiableList(new ArrayList<>(numbers));
        }
    }

    public static JSONObject change(final String kind, final List<Contact> contacts, final List<Long> deleted) throws JSONException {
        if (!"insert".equals(kind) && !"update".equals(kind) && !"delete".equals(kind)) throw new IllegalArgumentException("Invalid contact change kind");
        final JSONArray list = new JSONArray();
        if ("delete".equals(kind)) {
            // Wrap the raw-row delete ID like insert/update raw rows.
            for (final long id : deleted) list.put(new JSONObject().put("raw_contact", new JSONObject().put("id", id)));
        } else {
            for (final Contact contact : contacts) list.put(record(contact));
        }
        return new JSONObject().put(kind, new JSONObject().put("number_of_raw_contacts", list.length()).put("raw_contact_list", list));
    }

    public static JSONObject record(final Contact contact) throws JSONException {
        final JSONObject raw = new JSONObject().put("id", contact.rawId).put("contact_id", contact.contactId)
                .put("display_name_raw_contact_id", contact.displayRawId).put("is_favorite", contact.favorite)
                .put("display_name", contact.displayName).put("is_emergency", false).put("has_photo", false);
        final JSONArray data = new JSONArray();
        data.put(new JSONObject().put("raw_contact_id", contact.rawId).put("type", "name")
                .put("data", new JSONObject().put("first", nullable(contact.first)).put("last", nullable(contact.last))
                        .put("prefix", JSONObject.NULL).put("middle", JSONObject.NULL).put("suffix", JSONObject.NULL)
                        .put("phonetic_first", JSONObject.NULL).put("phonetic_middle", JSONObject.NULL).put("phonetic_last", JSONObject.NULL)));
        for (final Number number : contact.numbers) {
            // Pass Android DATA4 directly to put():
            // Java null omits the optional property, retaining DATA1 and the whole phone row.
            final JSONObject value = new JSONObject().put("number", number.value).put("normalized_number", number.normalized)
                    .put("type", number.type);
            if (number.type == 0) value.put("label", nullable(number.label));
            data.put(new JSONObject().put("raw_contact_id", contact.rawId).put("type", "number")
                    .put("is_default", number.primary).put("data", value));
        }
        return new JSONObject().put("raw_contact", raw).put("data_list", data);
    }

    private static Object nullable(final String value) { return value == null ? JSONObject.NULL : value; }

    /** Reliable SDK frame: BE16 sequence, clear service header, then application bytes. */
    public static byte[] frame(final int sequence, final byte[] payload) {
        if (sequence < 1 || sequence > 65535 || payload == null) throw new IllegalArgumentException("Invalid contact reliable frame");
        final byte[] result = new byte[payload.length + 3];
        result[0] = (byte) (sequence >>> 8); result[1] = (byte) sequence;
        System.arraycopy(payload, 0, result, 3, payload.length);
        return result;
    }

    public static int sequence(final byte[] frame) {
        if (frame == null || frame.length < 4 || frame[2] != 0) throw new IllegalArgumentException("Invalid clear contact reliable frame");
        return ((frame[0] & 255) << 8) | (frame[1] & 255);
    }

    public static byte[] payload(final byte[] frame) {
        sequence(frame);
        return Arrays.copyOfRange(frame, 3, frame.length);
    }

    public static byte[] contactFile(final JSONObject change, final byte[] key, final String basename) throws GeneralSecurityException, IOException {
        if (key == null || key.length != 32 || basename == null || !basename.matches("SAContactJSONFile[0-9]+")) throw new IllegalArgumentException("Invalid contact file inputs");
        final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        final byte[] ciphertext = cipher.doFinal(change.toString().getBytes(StandardCharsets.UTF_8));
        final byte[] encoded = Base64.encode(ciphertext, Base64.DEFAULT);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(basename + ".dat"));
            zip.write(encoded);
            zip.closeEntry();
        } finally {
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
        }
        return out.toByteArray();
    }

    public static JSONObject featureResponse(final JSONObject request) throws JSONException {
        if (!"contacts-getfeature-req".equals(request.optString("msgId"))) throw new IllegalArgumentException("Not a contact feature request");
        return new JSONObject().put("msgId", "contacts-getfeature-rsp")
                .put("requestId", String.valueOf(request.get("requestId"))).put("version", "1.35")
                .put("feature", new JSONArray().put(new JSONObject().put("supportLog", false))).put("result", "success");
    }
}
