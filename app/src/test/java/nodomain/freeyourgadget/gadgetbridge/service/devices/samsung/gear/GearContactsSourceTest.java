package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.ContactsContract;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Executes the real adapter's SQL predicates against synthetic ContactsContract tables. */
@RunWith(RobolectricTestRunner.class)
public class GearContactsSourceTest {
    private static final long RAW_ID = 700001;
    private static final String ENTERED = "202 555 0101", NORMALIZED = "+12025550101";
    private Context context;
    private Provider provider;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS);
        provider = new Provider();
        assertTrue(provider.onCreate());
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider);
        provider.raw(RAW_ID, false);
        provider.data(RAW_ID * 10, RAW_ID, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                "Synthetic Person", "Synthetic", "Person", null);
    }

    @After public void cleanup() { provider.db.close(); }

    private GearContactsCodec.Contact read() {
        final List<GearContactsCodec.Contact> contacts = GearContactsSource.read(context);
        assertEquals(1, contacts.size());
        return contacts.get(0);
    }

    private void phone(String normalized) {
        provider.data(RAW_ID * 10 + 1, RAW_ID, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ENTERED, "2", null, normalized);
    }

    @Test public void populatedNormalizedNumberIsIncludedWithoutChangingEnteredNumber() throws Exception {
        phone(NORMALIZED);
        final GearContactsCodec.Contact contact = read();
        final GearContactsCodec.Number number = contact.numbers.get(0);
        assertEquals(ENTERED, number.value);
        assertEquals(NORMALIZED, number.normalized);
        assertEquals(2, number.type);
        final JSONObject data = GearContactsCodec.record(contact).getJSONArray("data_list").getJSONObject(1).getJSONObject("data");
        assertEquals(ENTERED, data.getString("number"));
        assertEquals(NORMALIZED, data.getString("normalized_number"));
    }

    @Test public void nullNormalizedNumberRetainsThePhoneRow() {
        phone(null);
        final GearContactsCodec.Contact contact = read();
        assertEquals(1, contact.numbers.size());
        assertEquals(ENTERED, contact.numbers.get(0).value);
        assertNull(contact.numbers.get(0).normalized);
        assertEquals(2, contact.numbers.get(0).type);
    }

    @Test public void nullNormalizedNumberRetainsTheWholeNamedRawContact() {
        phone(null);
        final GearContactsCodec.Contact contact = read();
        assertEquals(RAW_ID, contact.rawId);
        assertEquals(RAW_ID + 100000, contact.contactId);
        assertEquals("Synthetic Person", contact.displayName);
        assertEquals("Synthetic", contact.first);
        assertEquals("Person", contact.last);
    }

    @Test public void linkedRawContactsShareActualAggregateNameSourceWithoutDroppingEitherRow() {
        phone(NORMALIZED);
        final long linkedId = RAW_ID + 1;
        provider.raw(linkedId, false);
        provider.data(linkedId * 10, linkedId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                "202 555 0102", "2", null, null);
        provider.db.execSQL("UPDATE raw_contacts SET contact_id=? WHERE _id=?",
                new Object[]{RAW_ID + 100000, linkedId});
        final List<GearContactsCodec.Contact> contacts = GearContactsSource.read(context);
        assertEquals(2, contacts.size());
        assertEquals(RAW_ID, contacts.get(0).rawId);
        assertEquals(linkedId, contacts.get(1).rawId);
        assertEquals(contacts.get(0).contactId, contacts.get(1).contactId);
        assertEquals(RAW_ID, contacts.get(0).displayRawId);
        assertEquals(RAW_ID, contacts.get(1).displayRawId);
        assertEquals(RAW_ID, GearContactsSource.readRaw(context, linkedId).displayRawId);
    }

    @Test public void aggregateNameSourceMayBeAnIneligibleRawContact() {
        phone(NORMALIZED);
        provider.raw(RAW_ID + 1, false); // No phone: still a valid aggregate name source.
        provider.db.execSQL("UPDATE contacts SET name_raw_contact_id=? WHERE _id=?",
                new Object[]{RAW_ID + 1, RAW_ID + 100000});
        assertEquals(RAW_ID + 1, read().displayRawId);
    }

    @Test public void nameSourceChangeProducesReplacementInsteadOfNewRawContact() {
        phone(NORMALIZED);
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        assertTrue(snapshot.recordTransportDeliveredSnapshot(snapshot.compare(GearContactsSource.read(context), true)));
        provider.db.execSQL("UPDATE contacts SET name_raw_contact_id=?", new Object[]{RAW_ID + 1});
        final GearContactsSnapshot.Delta delta = snapshot.compare(GearContactsSource.read(context), false);
        assertEquals(1, delta.update.size());
        assertTrue(delta.insert.isEmpty());
        assertTrue(delta.delete.isEmpty());
    }

    @Test(expected = IllegalStateException.class)
    public void missingAggregateIdentityFailsInsteadOfInventingIdentityOrSelectingDeletes() {
        phone(NORMALIZED);
        provider.db.execSQL("DELETE FROM contacts");
        GearContactsSource.read(context);
    }

    @Test public void mixedNullAndPopulatedPhoneRowsAreBothIncluded() {
        phone(null);
        provider.data(RAW_ID * 10 + 2, RAW_ID, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                "202 555 0102", "1", null, "+12025550102");
        final GearContactsCodec.Contact contact = read();
        assertEquals(2, contact.numbers.size());
        assertNull(contact.numbers.get(0).normalized);
        assertEquals("+12025550102", contact.numbers.get(1).normalized);
    }

    @Test public void nullNormalizedContactCanBeEncodedAsInsert() throws Exception {
        phone(null);
        final JSONObject insert = GearContactsCodec.change("insert", Collections.singletonList(read()), Collections.emptyList())
                .getJSONObject("insert");
        assertEquals(1, insert.getInt("number_of_raw_contacts"));
        final JSONObject record = insert.getJSONArray("raw_contact_list").getJSONObject(0);
        assertEquals(RAW_ID, record.getJSONObject("raw_contact").getLong("id"));
        assertEquals(2, record.getJSONArray("data_list").length());
        final JSONObject phone = record.getJSONArray("data_list").getJSONObject(1).getJSONObject("data");
        assertEquals(ENTERED, phone.getString("number"));
        assertEquals(2, phone.getInt("type"));
        // JSONObject.put(key, Java null) omits the optional property.
        assertFalse(phone.has("normalized_number"));
    }

    @Test public void nullNormalizedFingerprintIsStableAndUnchangedScanSendsNothing() {
        phone(null);
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        final GearContactsCodec.Contact first = read();
        final String hash = GearContactsSnapshot.fingerprint(first);
        assertTrue(snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Collections.singletonList(first), true)));
        assertEquals(hash, GearContactsSnapshot.fingerprint(read()));
        assertTrue(snapshot.compare(Collections.singletonList(read()), false).isEmpty());
    }

    @Test public void androidNormalizedValueTransitionProducesDeterministicModification() {
        phone(null);
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        final GearContactsCodec.Contact first = read();
        assertTrue(snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Collections.singletonList(first), true)));
        final ContentValues values = new ContentValues();
        values.put(ContactsContract.Data.DATA4, NORMALIZED);
        provider.db.update("data", values, "_id=?", new String[]{Long.toString(RAW_ID * 10 + 1)});
        final GearContactsCodec.Contact current = read();
        assertEquals(ENTERED, current.numbers.get(0).value);
        assertEquals(NORMALIZED, current.numbers.get(0).normalized);
        assertNotEquals(GearContactsSnapshot.fingerprint(first), GearContactsSnapshot.fingerprint(current));
        assertEquals(GearContactsSnapshot.fingerprint(current), GearContactsSnapshot.fingerprint(read()));
        final GearContactsSnapshot.Delta delta = snapshot.compare(Collections.singletonList(current), false);
        assertEquals(1, delta.update.size());
        assertEquals(RAW_ID, delta.update.get(0).rawId);
        assertTrue(delta.insert.isEmpty());
        assertTrue(delta.delete.isEmpty());
    }

    @Test public void emptyNormalizedValueIsPreservedRatherThanInvented() throws Exception {
        phone("");
        final GearContactsCodec.Contact contact = read();
        assertEquals("", contact.numbers.get(0).normalized);
        final JSONObject data = GearContactsCodec.record(contact).getJSONArray("data_list").getJSONObject(1).getJSONObject("data");
        assertTrue(data.has("normalized_number"));
        assertEquals("", data.getString("normalized_number"));
        assertEquals(ENTERED, data.getString("number"));
    }

    @Test public void nullNormalizedNumberIsOmittedFromWireJson() throws Exception {
        phone(null);
        final JSONObject expected = new JSONObject().put("number", ENTERED)
                .put("normalized_number", (String) null).put("type", 2);
        final JSONObject actual = GearContactsCodec.record(read()).getJSONArray("data_list").getJSONObject(1).getJSONObject("data");
        assertFalse(expected.has("normalized_number"));
        assertEquals(expected.toString(), actual.toString());
    }

    @Test public void nullAndEmptyAndroidNormalizedValuesHaveDistinctStableFingerprints() {
        phone(null);
        final String nullHash = GearContactsSnapshot.fingerprint(read());
        final ContentValues values = new ContentValues(); values.put(ContactsContract.Data.DATA4, "");
        provider.db.update("data", values, "_id=?", new String[]{Long.toString(RAW_ID * 10 + 1)});
        final String emptyHash = GearContactsSnapshot.fingerprint(read());
        assertNotEquals(nullHash, emptyHash);
        assertEquals(emptyHash, GearContactsSnapshot.fingerprint(read()));
    }

    @Test public void queryDoesNotRequireData4OrAggregatePhoneFlags() {
        phone(null);
        read();
        assertEquals("deleted=0", provider.rawSelection);
        assertEquals("(mimetype=? OR mimetype=?)", provider.dataSelection);
        assertFalse(provider.dataSelection.contains(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER));
    }

    @Test public void targetedReadAlsoAcceptsNullNormalizedNumberAndKeepsExactId() {
        phone(null);
        provider.raw(RAW_ID + 1, false);
        provider.data(RAW_ID * 10 + 2, RAW_ID + 1, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                "202 555 0102", "2", null, NORMALIZED);
        final GearContactsCodec.Contact contact = GearContactsSource.readRaw(context, RAW_ID);
        assertNotNull(contact);
        assertEquals(RAW_ID, contact.rawId);
        assertNull(contact.numbers.get(0).normalized);
        assertEquals("deleted=0 AND _id=?", provider.rawSelection);
        assertEquals("(mimetype=? OR mimetype=?) AND raw_contact_id=?", provider.dataSelection);
    }

    @Test public void deletedRawContactRemainsExcluded() {
        phone(null);
        final ContentValues values = new ContentValues(); values.put(ContactsContract.RawContacts.DELETED, 1);
        provider.db.update("raw_contacts", values, "_id=?", new String[]{Long.toString(RAW_ID)});
        assertTrue(GearContactsSource.read(context).isEmpty());
    }

    @Test public void missingEnteredNumberRemainsExcludedRegardlessOfData4() {
        provider.data(RAW_ID * 10 + 1, RAW_ID, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                null, "2", null, NORMALIZED);
        provider.data(RAW_ID * 10 + 2, RAW_ID, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                "", "2", null, null);
        assertTrue(GearContactsSource.read(context).isEmpty());
    }

    /** Unlike a projection-only MatrixCursor fixture, this provider executes selections and ordering. */
    private static final class Provider extends ContentProvider {
        SQLiteDatabase db;
        String rawSelection, dataSelection;
        @Override public boolean onCreate() {
            db = SQLiteDatabase.create(null);
            db.execSQL("CREATE TABLE contacts (_id INTEGER PRIMARY KEY, name_raw_contact_id INTEGER)");
            db.execSQL("CREATE TABLE raw_contacts (_id INTEGER PRIMARY KEY, contact_id INTEGER, display_name TEXT, starred INTEGER, deleted INTEGER)");
            db.execSQL("CREATE TABLE data (_id INTEGER PRIMARY KEY, raw_contact_id INTEGER, mimetype TEXT, data1 TEXT, data2 TEXT, data3 TEXT, data4 TEXT, is_super_primary INTEGER)");
            return true;
        }
        void raw(long id, boolean deleted) {
            final ContentValues row = new ContentValues();
            row.put("_id", id); row.put("contact_id", id + 100000); row.put("display_name", "Synthetic Person");
            row.put("starred", 0); row.put("deleted", deleted ? 1 : 0);
            db.insertOrThrow("raw_contacts", null, row);
            db.execSQL("INSERT INTO contacts VALUES (?, ?)", new Object[]{id + 100000, id});
        }
        void data(long id, long rawId, String mime, String data1, String data2, String data3, String data4) {
            final ContentValues row = new ContentValues();
            row.put("_id", id); row.put("raw_contact_id", rawId); row.put("mimetype", mime);
            row.put("data1", data1); row.put("data2", data2); row.put("data3", data3); row.put("data4", data4);
            row.put("is_super_primary", 0);
            db.insertOrThrow("data", null, row);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (ContactsContract.Contacts.CONTENT_URI.equals(uri))
                return db.query("contacts", projection, selection, args, null, null, order);
            final boolean raw = ContactsContract.RawContacts.CONTENT_URI.equals(uri);
            if (!raw && !ContactsContract.Data.CONTENT_URI.equals(uri)) throw new IllegalArgumentException("Unexpected test URI");
            if (raw) rawSelection = selection; else dataSelection = selection;
            return db.query(raw ? "raw_contacts" : "data", projection, selection, args, null, null, order);
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
