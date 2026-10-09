package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the existing file generator and completion handlers without a phone address book or watch. */
@RunWith(RobolectricTestRunner.class)
public class GearContactsDebugDeleteTest {
    private Context context;
    private SharedPreferences preferences, devicePreferences;
    private GearContactsService service;
    private final List<SapPostAuthRecord> emitted = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("contacts-debug-test", Context.MODE_PRIVATE);
        devicePreferences = context.getSharedPreferences("contacts-debug-test-device", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        assertTrue(devicePreferences.edit().clear().commit());
    }

    @After public void tearDown() {
        if (service != null) service.dispose();
    }

    private static GearContactsCodec.Contact contact(final long id, final String number) {
        return new GearContactsCodec.Contact(id, id + 1000, id, "Synthetic Person", "Synthetic", "Person", false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }

    private GearContactsSnapshot.Delta seedMixedScan(final int deleteCount) {
        final GearContactsSnapshot baseline = new GearContactsSnapshot(new GearContactsSnapshotStore(devicePreferences));
        final List<GearContactsCodec.Contact> original = new ArrayList<>();
        for (int id = deleteCount + 2; id >= 1; id--) original.add(contact(id, "+15550001000"));
        assertTrue(baseline.recordTransportDeliveredSnapshot(baseline.compare(original, true)));
        assertTrue(baseline.markPendingVerification());
        final GearContactsSnapshot.Delta scan = baseline.compare(Arrays.asList(
                contact(1, "+15550001001"), contact(2, "+15550001002"),
                contact(deleteCount + 3, "+15550001003"), contact(deleteCount + 4, "+15550001004"),
                contact(deleteCount + 5, "+15550001005")), false);
        assertEquals(3, scan.insert.size());
        assertEquals(2, scan.update.size());
        assertEquals(deleteCount, scan.delete.size());
        return scan;
    }

    private void createService() {
        service = new GearContactsService(context, preferences, devicePreferences, "00:11:22:33:44:55",
                new GearSessionSecrets(), new SapHostManagerRegistration(0x1100, 0x1101),
                new HashSet<>(Arrays.asList(0x1100, 0x1101)), new GearContactsService.Emitter() {
            @Override public void send(final SapPostAuthRecord record) { emitted.add(record); }
            @Override public Set<Integer> usedSessions() { return new HashSet<>(); }
        });
    }

    private void prepareDebugFiles(final GearContactsSnapshot.Delta scan) throws Exception {
        final GearContactsOneShotGate gate = (GearContactsOneShotGate) field(service, "debugOneShot");
        assertTrue(gate.arm());
        assertTrue(gate.consume());
        assertTrue(GearContactsOneShotGate.retainDeletes(scan) > 0);
        // Same projection and generator as the authorized scan callback; production transport is unchanged.
        invoke("prepareFiles", GearContactsSnapshot.Delta.class, scan);
        setField(service, "pendingDelta", scan);
        setField(service, "pendingDebugOneShot", true);
        setField(service, "pendingInitial", false);
        setField(service, "contactsSession", 9); // The authorized trial has a live, ready control SAP.
        setField(service, "readyControlGeneration", field(service, "controlGeneration"));
        setField(service, "ftCommandSession", 10);
    }

    @Test public void mixedScanGeneratesOnlyFiveDeletesAndCompletionKeepsAutomaticSyncPaused() throws Exception {
        final GearContactsSnapshot.Delta scan = seedMixedScan(5);
        final Map<String, ?> before = new HashMap<>(devicePreferences.getAll());
        createService();
        assertAutomaticSyncPaused();
        prepareDebugFiles(scan);
        assertDeleteFiles(5, 1);
        invoke("sendNextFile");
        assertEquals(1, emitted.size()); // one file setup
        completeCurrentFile();
        assertTrue(files().isEmpty());
        assertFalse((Boolean) field(service, "pendingDebugOneShot"));
        assertEquals(before, devicePreferences.getAll());
        assertAutomaticSyncPaused();
        service.dispose();
        createService();
        assertAutomaticSyncPaused();
        assertEquals(before, devicePreferences.getAll());
    }

    @Test public void allDeletesUseExistingBatchesAndInterruptionCannotAdvanceOrRearm() throws Exception {
        final GearContactsSnapshot.Delta scan = seedMixedScan(401);
        final Map<String, ?> before = new HashMap<>(devicePreferences.getAll());
        createService();
        prepareDebugFiles(scan);
        assertDeleteFiles(401, 3); // existing 200 + 200 + 1 records per file
        invoke("sendNextFile");
        completeCurrentFile();
        assertEquals(2, files().size());
        assertTrue((Boolean) field(service, "pendingDebugOneShot"));
        assertEquals(before, devicePreferences.getAll());
        service.dispose();
        assertTrue(files().isEmpty());
        assertEquals(before, devicePreferences.getAll());
        createService();
        assertAutomaticSyncPaused();
        assertEquals(before, devicePreferences.getAll());
    }

    @Test public void replacementTrialSendsTargetDeleteBeforeCurrentInsertAndRetainsBaseline() throws Exception {
        final GearContactsSnapshot.Delta scan = seedMixedScan(1);
        final Map<String, ?> before = new HashMap<>(devicePreferences.getAll());
        assertTrue(GearContactsOneShotGate.retainReplacement(scan, 4,
                GearContactsSnapshot.fingerprint(contact(4, "+15550009999"))));
        createService();
        invoke("prepareFiles", GearContactsSnapshot.Delta.class, scan);
        setField(service, "pendingDelta", scan);
        setField(service, "pendingDebugOneShot", true);
        setField(service, "contactsSession", 9); // The authorized trial has a live, ready control SAP.
        setField(service, "readyControlGeneration", field(service, "controlGeneration"));
        setField(service, "ftCommandSession", 10);
        assertEquals(2, files().size());
        final Object[] queued = files().toArray();
        final JSONObject deletion = decode((byte[]) field(queued[0], "bytes"), (byte[]) field(service, "contactKey"));
        assertEquals(4L, deletion.getJSONObject("delete").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        final JSONObject insertion = decode((byte[]) field(queued[1], "bytes"), (byte[]) field(service, "contactKey"));
        assertEquals(4L, insertion.getJSONObject("insert").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertEquals(1, deletion.getJSONObject("delete").getInt("number_of_raw_contacts"));
        assertEquals(1, insertion.getJSONObject("insert").getInt("number_of_raw_contacts"));
        invoke("sendNextFile");
        completeCurrentFile();
        assertEquals(1, files().size());
        assertEquals(before, devicePreferences.getAll());
        completeCurrentFile();
        assertTrue(files().isEmpty());
        assertEquals(before, devicePreferences.getAll());
    }

    @Test public void repeatedObserverCallbacksWaitForOneQuietPeriod() throws Exception {
        seedMixedScan(1);
        createService();
        final ContentObserver observer = (ContentObserver) field(service, "observer");
        observer.onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(200, TimeUnit.MILLISECONDS);
        observer.onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(200, TimeUnit.MILLISECONDS);
        assertFalse((Boolean) field(service, "pendingVerificationLogged"));
        ShadowLooper.shadowMainLooper().idleFor(150, TimeUnit.MILLISECONDS);
        assertTrue((Boolean) field(service, "pendingVerificationLogged"));
    }

    @Test public void observerChangeDuringTransferQueuesFollowUpDespitePendingMarker() throws Exception {
        seedMixedScan(1);
        createService();
        setField(service, "scanInProgress", true);
        invoke("scheduleScan", boolean.class, false);
        assertTrue((Boolean) field(service, "scanAgain"));
        assertFalse((Boolean) field(service, "pendingVerificationLogged"));
    }

    private void completeCurrentFile() throws Exception {
        final Object file = files().peek();
        // Simulate byte delivery, then exercise the real generic completion handler.
        setField(file, "offset", ((byte[]) field(file, "bytes")).length);
        invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", "filetransfer-complete-req")
                .put("fileName", ((File) field(file, "path")).getName()).toString().getBytes(StandardCharsets.UTF_8));
    }

    private void assertAutomaticSyncPaused() throws Exception {
        assertTrue(((GearContactsSnapshot) field(service, "snapshot")).hasPendingVerification());
        assertFalse(((GearContactsOneShotGate) field(service, "debugOneShot")).isArmed());
        final int previouslyEmitted = emitted.size();
        setField(service, "contactsSession", 9);
        invoke("scheduleScan", boolean.class, false);
        assertTrue((Boolean) field(service, "pendingVerificationLogged"));
        assertFalse((Boolean) field(service, "scanInProgress"));
        assertTrue(files().isEmpty());
        assertEquals(previouslyEmitted, emitted.size());
    }

    private void assertDeleteFiles(final int expectedDeletes, final int expectedFiles) throws Exception {
        assertEquals(expectedFiles, files().size());
        long expectedId = 3;
        for (final Object file : files()) {
            final JSONObject payload = decode((byte[]) field(file, "bytes"), (byte[]) field(service, "contactKey"));
            assertFalse(payload.has("insert"));
            assertFalse(payload.has("update"));
            assertEquals(1, payload.length());
            final JSONObject deletion = payload.getJSONObject("delete");
            final JSONArray rows = deletion.getJSONArray("raw_contact_list");
            assertEquals(Math.min(200, expectedDeletes - (int) expectedId + 3), rows.length());
            assertEquals(rows.length(), deletion.getInt("number_of_raw_contacts"));
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject row = rows.getJSONObject(i);
                assertEquals(1, row.length());
                assertEquals(1, row.getJSONObject("raw_contact").length());
                assertEquals(expectedId++, row.getJSONObject("raw_contact").getLong("id"));
            }
        }
        assertEquals(expectedDeletes + 3L, expectedId);
    }

    private static JSONObject decode(final byte[] zip, final byte[] key) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertNotNull(input.getNextEntry());
            final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            final byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) encoded.write(buffer, 0, length);
            final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return new JSONObject(new String(cipher.doFinal(Base64.getMimeDecoder().decode(encoded.toByteArray())), StandardCharsets.UTF_8));
        }
    }

    private ArrayDeque<?> files() throws Exception { return (ArrayDeque<?>) field(service, "files"); }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void invoke(final String name) throws Exception {
        final Method method = GearContactsService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(service);
    }

    private void invoke(final String name, final Class<?> type, final Object argument) throws Exception {
        final Method method = GearContactsService.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(service, argument);
    }
}
