package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Permission-gated Android ContactsContract adapter; never logs or persists PII. */
public final class GearContactsSource {
    private static final Logger LOG = LoggerFactory.getLogger(GearContactsSource.class);
    private GearContactsSource() { }

    public static List<GearContactsCodec.Contact> read(final Context context) {
        return read(context, -1);
    }

    /** Production mapping restricted to exactly one raw ID for debug operations. */
    static GearContactsCodec.Contact readRaw(final Context context, final long rawId) {
        if (rawId <= 0) throw new IllegalArgumentException("Invalid raw ID");
        final List<GearContactsCodec.Contact> contacts = read(context, rawId);
        return contacts.isEmpty() ? null : contacts.get(0);
    }

    private static List<GearContactsCodec.Contact> read(final Context context, final long rawId) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Gear contacts permission missing");
        }
        final Map<Long, Row> rows = new LinkedHashMap<>();
        final String[] rawColumns = {ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY, ContactsContract.RawContacts.STARRED};
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI, rawColumns,
                ContactsContract.RawContacts.DELETED + "=0" + (rawId > 0 ? " AND " + ContactsContract.RawContacts._ID + "=?" : ""),
                rawId > 0 ? new String[]{Long.toString(rawId)} : null, ContactsContract.RawContacts._ID + " ASC")) {
            if (cursor == null) throw new IllegalStateException("Gear contacts provider unavailable");
            while (cursor.moveToNext()) {
                final Row row = new Row();
                row.id = cursor.getLong(0); row.contactId = cursor.getLong(1);
                if (rawId > 0 && row.id != rawId) continue;
                row.display = cursor.getString(2); row.favorite = cursor.getInt(3) != 0;
                rows.put(row.id, row);
            }
        }
        final String[] dataColumns = {ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4, ContactsContract.Data.IS_SUPER_PRIMARY};
        final String where = "(" + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=?)"
                + (rawId > 0 ? " AND " + ContactsContract.Data.RAW_CONTACT_ID + "=?" : "");
        final String[] args = rawId > 0 ? new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, Long.toString(rawId)}
                : new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE};
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI, dataColumns, where,
                args, ContactsContract.Data._ID + " ASC")) {
            if (cursor == null) throw new IllegalStateException("Gear contact data provider unavailable");
            while (cursor.moveToNext()) {
                final Row row = rows.get(cursor.getLong(0));
                if (row == null) continue;
                final String mime = cursor.getString(1);
                if (ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE.equals(mime)) {
                    row.first = cursor.getString(3); row.last = cursor.getString(4);
                } else if (ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mime)) {
                    final String value = cursor.getString(2);
                    if (value != null && !value.isEmpty()) {
                        // Android DATA4 is optional. Preserve it, including null, without normalizing DATA1.
                        row.numbers.add(new GearContactsCodec.Number(value, cursor.getString(5), cursor.getInt(3),
                                cursor.getString(4), cursor.getInt(6) != 0));
                    }
                }
            }
        }
        final List<GearContactsCodec.Contact> result = new ArrayList<>();
        for (final Row row : rows.values()) {
            if (row.numbers.isEmpty()) {
                LOG.debug("Gear S3 contacts source omitted: rawId={}; no nonempty phone DATA1 returned", row.id);
                continue;
            }
            result.add(new GearContactsCodec.Contact(row.id, row.contactId, row.id,
                    row.display == null ? "" : row.display, row.first, row.last, row.favorite, row.numbers));
            if (LOG.isDebugEnabled() && row.numbers.stream().anyMatch(number -> number.normalized == null))
                LOG.debug("Gear S3 contacts source included: rawId={}; phoneRows={}; contains null DATA4", row.id, row.numbers.size());
        }
        LOG.debug("Gear S3 contacts source snapshot: rawRows={} included={} omittedNoPhone={}",
                rows.size(), result.size(), rows.size() - result.size());
        return result;
    }

    private static final class Row {
        long id, contactId;
        String display, first, last;
        boolean favorite;
        final List<GearContactsCodec.Number> numbers = new ArrayList<>();
    }
}
