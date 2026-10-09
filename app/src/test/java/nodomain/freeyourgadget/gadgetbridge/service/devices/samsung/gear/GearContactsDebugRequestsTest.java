package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLog;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearContactsDebugRequests.Mode;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTermination;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTerminationResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the actual scan, encoder and completion path with synthetic provider rows. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearContactsDebugRequestsTest {
    private static final String ADDRESS = "00:11:22:33:44:55";
    private GearContactsDebugRequests requests = new GearContactsDebugRequests();
    private GearS3DeviceSupport support;
    private final List<SapPostAuthRecord> emitted = new ArrayList<>();
    private Context context;
    private SharedPreferences prefs, devicePrefs;
    private GearContactsService service;
    private SyntheticProvider provider;
    private Map<String, ?> retained;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS, "android.permission.DUMP");
        prefs = context.getSharedPreferences("contacts-queue-identity", Context.MODE_PRIVATE);
        devicePrefs = context.getSharedPreferences("contacts-queue-device", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        devicePrefs.edit().clear().commit();
        final GearContactsSnapshot snapshot = new GearContactsSnapshot(new GearContactsSnapshotStore(devicePrefs));
        snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Arrays.asList(contact(1), contact(2)), true));
        snapshot.markPendingVerification();
        retained = new HashMap<>(devicePrefs.getAll());
        provider = new SyntheticProvider();
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider);
        requests.register(context, ADDRESS);
    }

    @After public void cleanup() {
        if (support != null) support.dispose();
        else if (service != null) service.dispose();
        requests.close();
    }

    private static GearContactsCodec.Contact contact(final long id) {
        return new GearContactsCodec.Contact(id, id + 1000, id, "Synthetic Person", null, null, false,
                Collections.singletonList(new GearContactsCodec.Number("+15550001001", null, 2, null, true)));
    }

    private void createService() throws Exception {
        service = new GearContactsService(context, prefs, devicePrefs, ADDRESS, new GearSessionSecrets(),
                new SapHostManagerRegistration(0x1100, 0x1101), new HashSet<>(Arrays.asList(0x1100, 0x1101)),
                new GearContactsService.Emitter() {
                    @Override public void send(final SapPostAuthRecord record) { emitted.add(record); }
                    @Override public Set<Integer> usedSessions() { return new HashSet<>(); }
                }, requests);
        provider.controller = requests;
        if (support != null) {
            final Field contacts = GearS3DeviceSupport.class.getDeclaredField("contactsService");
            contacts.setAccessible(true);
            contacts.set(support, service);
        }
        set("remoteContacts", 20);
        set("remoteFt", 21);
        set("ftCommandSession", 10);
        set("ftDataSession", 11);
    }

    private boolean request(Mode mode, long id) {
        final boolean accepted = requests.request(mode, id);
        if (accepted && mode == Mode.INSERT) provider.trialIds.add(id);
        return accepted;
    }

    private void sap() throws Exception { set("contactsSession", 9); requests.wake(); idle(); }
    private void resume() throws Exception {
        if (requests.hasPending()) {
            final long id = (Long) field(requests, "rawId");
            if (id > 0) provider.trialIds.add(id);
        }
        invoke("onContactMessage", byte[].class, "crypt_Request_Resume_Sync_Contact".getBytes(StandardCharsets.UTF_8));
        idle();
        drainScan();
    }
    private void idle() { ShadowLooper.shadowMainLooper().idle(); }
    private void drainScan() throws Exception {
        ((ExecutorService) field(service, "loader")).submit(() -> { }).get(5, TimeUnit.SECONDS);
        idle();
    }

    @Test public void broadcastBeforeServiceExistsQueuesAndExecutesExactlyOnceAfterResume() throws Exception {
        context.sendBroadcast(new Intent(GearContactsService.DEBUG_INSERT_RAW_ACTION)
                .setPackage(context.getPackageName()).putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, ADDRESS)
                .putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, 9119L));
        idle();
        assertTrue(requests.hasPending());
        assertLog("queued: contacts service absent");
        createService(); idle();
        assertLog("queued: SAP not established");
        sap();
        assertLog("queued: waiting for Resume boundary");
        assertEquals(0, provider.debugScans);
        resume();
        assertSingleInsert();
        assertLog("delivered pending request to Contacts service");
        assertLog("selected raw-contact ID=9119");
        complete();
        resume();
        assertEquals(1, provider.debugScans);
        assertTrue(files().isEmpty());
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void requestAfterSapWaitsForResume() throws Exception {
        createService(); sap();
        assertTrue(request(Mode.INSERT, 9119)); idle();
        assertEquals(0, provider.debugScans);
        assertLog("queued: waiting for Resume boundary");
        resume();
        assertSingleInsert();
    }

    @Test public void requestAfterResumeStartsImmediatelyAndRetainsOnlyDebugTrialState() throws Exception {
        createService(); sap(); resume();
        assertEquals(0, provider.debugScans); // Ordinary unchanged scan runs; no debug scan yet.
        assertTrue(request(Mode.INSERT, 9119)); idle(); drainScan();
        assertSingleInsert();
        complete();
        invoke("scheduleScan", boolean.class, false); idle();
        assertEquals(1, provider.debugScans);
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void duplicatePendingAndActiveRequestsAreExplicitlyRejected() throws Exception {
        assertTrue(request(Mode.INSERT, 9119));
        assertFalse(request(Mode.INSERT, 9119));
        assertLog("rejected: another debug request already pending");
        createService(); sap(); resume();
        assertFalse(request(Mode.INSERT, 9999));
        assertSingleInsert();
    }

    @Test public void serviceRecreationPreservesPendingInsertAndReceiver() throws Exception {
        createService(); sap();
        assertTrue(request(Mode.INSERT, 9119)); idle();
        service.dispose(); idle();
        assertTrue(requests.hasPending());
        createService(); sap(); resume();
        assertSingleInsert();
        complete();
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void sapTeardownBeforeResumePreservesPendingInsert() throws Exception {
        createService(); sap();
        assertTrue(request(Mode.INSERT, 9119)); idle();
        final int local = (Integer) field(service, "contactsComponent");
        assertTrue(service.onTermination(new SapServiceConnectionTermination(20, local, GearContactsService.CONTACTS)));
        idle();
        assertTrue(requests.hasPending());
        assertEquals(0, provider.debugScans);
        sap(); resume();
        assertSingleInsert();
    }

    @Test public void preparedInsertBeforeFileSetupSurvivesServiceRecreation() throws Exception {
        createService(); sap(); resume();
        set("ftCommandSession", -1);
        assertTrue(request(Mode.INSERT, 9119)); idle(); drainScan();
        assertTrue(requests.hasPending());
        assertFalse(requests.isActive());
        assertEquals(1, files().size());
        service.dispose(); idle();
        assertTrue(requests.hasPending());
        createService(); sap(); resume();
        assertEquals(2, provider.debugScans);
        assertEquals(1, files().size());
        assertEquals(9119, decoded(files().peek()).getJSONObject("insert").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete();
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void interruptedTransmittedInsertDoesNotAutomaticallyReplay() throws Exception {
        createService(); sap(); resume();
        request(Mode.INSERT, 9119); idle(); drainScan();
        assertSingleInsert();
        assertTrue(requests.isActive());
        service.dispose(); idle();
        createService(); sap(); resume();
        assertFalse(requests.hasPending());
        assertFalse(requests.isActive());
        assertTrue(files().isEmpty());
        assertEquals(1, provider.debugScans);
        assertFalse(request(Mode.INSERT, 9119));
        assertLog("INSERT already attempted; replay unsafe");
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void replaceNeedsSameDeliveredSeedAndChangedFingerprint() throws Exception {
        assertFalse(request(Mode.REPLACE, 9119));
        createService(); sap(); resume();
        request(Mode.INSERT, 9119); idle(); drainScan();
        assertSingleInsert(); complete();
        assertFalse(request(Mode.REPLACE, 9120));
        assertTrue(request(Mode.REPLACE, 9119)); idle(); drainScan();
        assertTrue(files().isEmpty()); // unchanged seed cannot be replaced
        provider.number = "+15550002002";
        assertTrue(request(Mode.REPLACE, 9119)); idle(); drainScan();
        assertEquals(2, files().size());
        final Object[] queued = files().toArray();
        assertEquals(9119, decoded(queued[0]).getJSONObject("delete").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertEquals(9119, decoded(queued[1]).getJSONObject("insert").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); finishReplacementBoundary(); complete();
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void explicitInitialWhileRequestQueuedCannotResetOrPromoteBaseline() throws Exception {
        createService(); sap(); request(Mode.INSERT, 9119); idle();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8));
        idle();
        assertTrue(requests.hasPending());
        assertEquals(0, provider.debugScans);
        assertEquals(retained, devicePrefs.getAll());
    }

    private void createSupport() throws Exception {
        support = new GearS3DeviceSupport();
        support.setContext(new GBDevice(ADDRESS, "Synthetic Gear", null, null, DeviceType.SAMSUNG_GEAR_S3),
                BluetoothAdapter.getDefaultAdapter(), context);
        requests = (GearContactsDebugRequests) field(support, "contactsDebugRequests");
    }

    @Test public void pendingInsertSurvivesActualDeviceSupportDisposalAndRecreation() throws Exception {
        requests.close();
        createSupport(); createService(); sap();
        assertTrue(request(Mode.INSERT, 9120)); idle();
        support.dispose(); service = null;
        final GearContactsDebugRequests previous = requests;
        createSupport();
        assertSame(previous, requests);
        assertTrue(requests.hasPending());
        assertLog("pending debug request restored into newly-created GearS3DeviceSupport: mode=INSERT rawId=9120");
        assertFalse(request(Mode.INSERT, 9120));
        assertLog("rejected: another debug request already pending");
        createService(); sap();
        assertEquals(0, provider.debugScans); // SAP alone must not deliver the request.
        resume(); assertSingleInsert(9120); complete();
        resume();
        assertEquals(1, provider.debugScans);
        assertTrue(files().isEmpty());
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void receiverAcceptsRequestBetweenDeviceSupportInstances() throws Exception {
        requests.close(); createSupport();
        support.dispose(); support = null;
        context.sendBroadcast(new Intent(GearContactsService.DEBUG_INSERT_RAW_ACTION)
                .setPackage(context.getPackageName()).putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, ADDRESS)
                .putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, 9120L));
        idle();
        assertTrue(requests.hasPending());
        createSupport(); createService(); sap();
        assertEquals(0, provider.debugScans);
        resume(); assertSingleInsert(9120); complete();
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void deliveredSeedSurvivesDeviceSupportRecreationAndValidatesReplacement() throws Exception {
        requests.close(); createSupport(); createService(); sap(); resume();
        assertTrue(request(Mode.INSERT, 9120)); idle(); drainScan();
        assertSingleInsert(9120); complete();
        assertLog("seeded INSERT retained: rawId=9120");
        support.dispose(); service = null;
        createSupport(); createService(); sap(); resume();
        assertLog("seeded INSERT state restored into newly-created GearS3DeviceSupport: rawId=9120");
        assertEquals(1, provider.debugScans); // Only one debug scan; normal Resume scans do not replay the trial.
        assertFalse(request(Mode.REPLACE, 9119));
        assertLog("REPLACE raw ID differs from seeded INSERT");
        assertTrue(request(Mode.REPLACE, 9120)); idle(); drainScan();
        assertTrue(files().isEmpty()); // Expected previous fingerprint is still the original seed.
        provider.number = "+15550002002";
        assertTrue(request(Mode.REPLACE, 9120)); idle(); drainScan();
        assertLog("debug REPLACE accepted: rawId=9120");
        final Object[] queued = files().toArray();
        assertEquals(2, queued.length);
        assertEquals(9120, decoded(queued[0]).getJSONObject("delete").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertEquals(9120, decoded(queued[1]).getJSONObject("insert").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); finishReplacementBoundary(); complete();
        assertEquals(retained, devicePrefs.getAll());
        invoke("scheduleScan", boolean.class, false); idle();
        assertEquals(3, provider.debugScans); // INSERT, unchanged debug REPLACE, changed debug REPLACE only.
        assertTrue(files().isEmpty());
    }

    @Test public void processLocalQueueAndSeedAreIsolatedByDeviceAddress() throws Exception {
        requests.close(); createSupport(); createService(); sap(); resume();
        assertTrue(request(Mode.INSERT, 9120)); idle(); drainScan(); complete();
        final GearContactsDebugRequests other = GearContactsDebugRequests.forDevice("AA:11:22:33:44:66");
        try {
            assertSame(other, GearContactsDebugRequests.forDevice("aa:11:22:33:44:66"));
            assertNotSame(requests, other);
            other.register(context, "AA:11:22:33:44:66");
            assertFalse(other.request(Mode.REPLACE, 9120)); // Another Gear cannot use this seed.
            context.sendBroadcast(new Intent(GearContactsService.DEBUG_INSERT_RAW_ACTION)
                    .setPackage(context.getPackageName()).putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, "aa:11:22:33:44:66")
                    .putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, 9119L));
            idle();
            assertTrue(other.hasPending());
            assertFalse(requests.hasPending());
            assertFalse(other.request(Mode.INSERT, 9120));
            assertEquals(1, provider.debugScans);
            assertEquals(retained, devicePrefs.getAll());
        } finally { other.close(); }
    }

    @Test public void interruptedInsertRemainsUnsafeAcrossDeviceSupportRecreation() throws Exception {
        requests.close(); createSupport(); createService(); sap(); resume();
        assertTrue(request(Mode.INSERT, 9120)); idle(); drainScan();
        assertSingleInsert(9120);
        support.dispose(); service = null;
        createSupport(); createService(); sap(); resume();
        assertFalse(request(Mode.REPLACE, 9120));
        assertFalse(request(Mode.INSERT, 9120));
        assertEquals(1, provider.debugScans);
        assertTrue(files().isEmpty());
        assertEquals(retained, devicePrefs.getAll());
    }

    private void prepareReplacement() throws Exception {
        createService(); sap(); resume();
        assertTrue(request(Mode.INSERT, 9120)); idle(); drainScan(); complete();
        provider.number = "+15550002002";
        assertTrue(request(Mode.REPLACE, 9120)); idle(); drainScan();
        assertEquals(2, files().size());
    }

    private SapServiceConnectionTerminationResponse terminationResponse(final int status) throws Exception {
        return new SapServiceConnectionTerminationResponse((Integer) field(service, "ftComponent"), 21,
                GearContactsService.FILE_TRANSFER, status);
    }

    private SapServiceConnectionResponse freshFtResponse(final int status) throws Exception {
        return new SapServiceConnectionResponse(21, (Integer) field(service, "ftComponent"),
                GearContactsService.FILE_TRANSFER, status,
                Arrays.asList((Integer) field(service, "pendingFtData"), (Integer) field(service, "pendingFtCommand")));
    }

    private void finishReplacementBoundary() throws Exception {
        assertTrue(service.onTerminationResponse(terminationResponse(0)));
        assertTrue(service.acceptResponse(freshFtResponse(0)));
    }

    private int setupCount() throws Exception {
        int count = 0;
        for (final SapPostAuthRecord record : emitted) {
            if (record.getHeader().getSessionId() == SapTransportHeader.SERVICE_CONTROL_SESSION_ID) continue;
            final byte[] body = record.getBody();
            if (body.length > 3 && body[2] == 0 && body[3] == '{') {
                final JSONObject command = new JSONObject(new String(GearContactsCodec.payload(body), StandardCharsets.UTF_8));
                if ("filetransfer-setup-req".equals(command.optString("msgId"))) count++;
            }
        }
        return count;
    }

    @Test public void replacementWaitsForTerminationThenFreshSapBeforeInsertSetupAndPayload() throws Exception {
        prepareReplacement();
        assertEquals(2, setupCount()); // Seed I, replacement D.
        final String deletedName = ((File) field(files().peek(), "path")).getName();
        complete();
        assertEquals("CLOSING_FT", field(service, "replaceBoundary").toString());
        assertNull(field(service, "sending"));
        assertEquals(1, files().size());
        final SapServiceConnectionTermination closing = (SapServiceConnectionTermination)
                SapServiceConnectionCodec.parse(emitted.get(emitted.size() - 1).getBody());
        assertEquals(GearContactsService.FILE_TRANSFER, closing.getProfile());
        assertEquals(21, closing.getAcceptorComponentId());
        assertEquals(field(service, "ftComponent"), closing.getInitiatorComponentId());
        assertLog("REPLACE D transport completed; waiting before I");
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
        invoke("sendNextFile", null, null);
        assertEquals(2, setupCount()); // Elapsed time alone never starts I.
        invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", "filetransfer-complete-req")
                .put("fileName", deletedName).toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(2, setupCount()); // Duplicate D completion cannot start I.
        assertFalse(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(22, 21,
                GearContactsService.FILE_TRANSFER, 0)));
        assertTrue(service.onTerminationResponse(terminationResponse(0)));
        assertEquals("OPENING_FT", field(service, "replaceBoundary").toString());
        final SapServiceConnectionRequest opening = (SapServiceConnectionRequest)
                SapServiceConnectionCodec.parse(emitted.get(emitted.size() - 1).getBody());
        assertEquals(GearContactsService.FILE_TRANSFER, opening.getProfile());
        for (final SapServiceConnectionRequest.Session session : opening.getSessions()) {
            assertNotEquals(10, session.getSessionId()); assertNotEquals(11, session.getSessionId());
        }
        invoke("sendNextFile", null, null);
        assertEquals(2, setupCount());
        assertTrue(service.acceptResponse(freshFtResponse(0)));
        assertEquals(3, setupCount());
        assertEquals(1, field(service, "ftDataTx"));
        final Object insertion = files().peek();
        assertEquals(0, field(insertion, "offset")); // Setup is not a payload send.
        invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", "filetransfer-setup-rsp")
                .put("fileName", ((File) field(insertion, "path")).getName()).put("result", 0)
                .toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(((byte[]) field(insertion, "bytes")).length, field(insertion, "offset"));
        assertEquals(9120, decoded(insertion).getJSONObject("insert").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete();
        assertFalse(requests.isActive());
        assertTrue(files().isEmpty());
        assertLog("REPLACE starting I after FT termination response and fresh SAP establishment");
        assertEquals(retained, devicePrefs.getAll());
        invoke("scheduleScan", boolean.class, false); idle();
        assertEquals(2, provider.debugScans); // No automatic delta bypass.
    }

    @Test public void replacementBoundaryTimeoutDoesNotSendInsertOrPermitReplay() throws Exception {
        prepareReplacement(); complete();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        assertEquals(2, setupCount());
        assertTrue(files().isEmpty());
        assertFalse(requests.isActive());
        assertFalse(service.onTerminationResponse(terminationResponse(0)));
        assertFalse(request(Mode.INSERT, 9120));
        assertFalse(request(Mode.REPLACE, 9120));
        assertLog("REPLACE stopped: file-transfer boundary timed out; no automatic replay");
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void replacementRejectsFailedCloseWithoutSendingInsert() throws Exception {
        prepareReplacement(); complete();
        assertTrue(service.onTerminationResponse(terminationResponse(1)));
        assertTrue(files().isEmpty());
        assertEquals(2, setupCount());
        assertFalse(request(Mode.REPLACE, 9120));
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void replacementRejectsFailedFreshSapWithoutSendingInsert() throws Exception {
        prepareReplacement(); complete();
        assertTrue(service.onTerminationResponse(terminationResponse(0)));
        assertTrue(service.acceptResponse(freshFtResponse(1)));
        assertTrue(files().isEmpty());
        assertEquals(2, setupCount());
        assertFalse(request(Mode.REPLACE, 9120));
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void replacementOpeningTimeoutDoesNotSendInsert() throws Exception {
        prepareReplacement(); complete();
        assertTrue(service.onTerminationResponse(terminationResponse(0)));
        final SapServiceConnectionResponse lateResponse = freshFtResponse(0);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        assertFalse(service.acceptResponse(lateResponse));
        assertTrue(files().isEmpty());
        assertEquals(2, setupCount());
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void replacementInterruptedBetweenFilesDoesNotResumeAfterSupportRecreation() throws Exception {
        requests.close(); createSupport();
        prepareReplacement(); complete();
        support.dispose(); service = null;
        createSupport(); createService(); sap(); resume();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        assertEquals(2, setupCount());
        assertTrue(files().isEmpty());
        assertFalse(request(Mode.REPLACE, 9120));
        assertFalse(request(Mode.INSERT, 9120));
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void nonDebugInitialInsertFilesStillAdvanceOnTransportCompletion() throws Exception {
        createService(); sap();
        set("readyControlGeneration", field(service, "controlGeneration")); // Simulate the initial handshake for this isolated queue fixture.
        final GearContactsSnapshot.Delta delta = new GearContactsSnapshot().compare(Arrays.asList(contact(10), contact(11)), true);
        final Method prepare = GearContactsService.class.getDeclaredMethod("prepareFiles", GearContactsSnapshot.Delta.class);
        prepare.setAccessible(true);
        prepare.invoke(service, delta);
        prepare.invoke(service, delta); // Two synthetic I files, same normal queue path as full sync.
        set("pendingDebugOneShot", false);
        invoke("sendNextFile", null, null);
        assertEquals(1, setupCount());
        complete();
        assertEquals(2, setupCount());
        assertEquals("NONE", field(service, "replaceBoundary").toString());
        assertNotNull(field(service, "sending"));
        assertEquals(retained, devicePrefs.getAll());
    }

    @Test public void completedExplicitDebugInsertIsNotReplayedButUnrelatedAutomaticInsertStillRuns() throws Exception {
        createService(); sap(); assertTrue(request(Mode.INSERT, 9119)); resume(); complete();
        provider.trialIds.add(9121L); resume();
        assertEquals(1, files().size()); assertFalse((Boolean) field(service, "pendingDebugOneShot"));
        assertEquals(9121, decoded(files().peek()).getJSONObject("insert").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete();
        final GearContactsSnapshot saved = new GearContactsSnapshot(new GearContactsSnapshotStore(devicePrefs));
        assertTrue(saved.isTransportUncertain(9121)); assertFalse(saved.isTransportUncertain(9119));
        assertEquals(retained.get("samsung_gear_contacts_acknowledged_snapshot_v1"), devicePrefs.getString("samsung_gear_contacts_acknowledged_snapshot_v1", null));
        resume(); assertTrue(files().isEmpty()); assertEquals(1, provider.debugScans);
    }

    @Test public void interruptedExplicitDebugInsertCannotReplayWhileOtherIdsStillConverge() throws Exception {
        createService(); sap(); assertTrue(request(Mode.INSERT, 9119)); resume(); service.dispose();
        createService(); sap(); provider.trialIds.add(9121L); resume();
        assertEquals(1, files().size()); assertFalse((Boolean) field(service, "pendingDebugOneShot"));
        assertEquals(9121, decoded(files().peek()).getJSONObject("insert").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertFalse(request(Mode.INSERT, 9119)); complete(); resume(); assertTrue(files().isEmpty());
        assertEquals(1, provider.debugScans);
    }

    private void assertSingleInsert() throws Exception {
        assertSingleInsert(9119);
    }
    private void assertSingleInsert(final long rawId) throws Exception {
        assertEquals(1, provider.debugScans);
        assertEquals(1, files().size());
        final JSONObject payload = decoded(files().peek());
        assertEquals(1, payload.length());
        final JSONObject insert = payload.getJSONObject("insert");
        assertEquals(1, insert.getInt("number_of_raw_contacts"));
        assertEquals(rawId, insert.getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertFalse((Boolean) field(service, "pendingInitial"));
        // Debug INSERT owns only process-local trial state, never a production ledger checkpoint.
        assertTrue(requests.isActive());
        final GearContactsSnapshot durable = new GearContactsSnapshot(new GearContactsSnapshotStore(devicePrefs));
        assertFalse(durable.isTransportUncertain(rawId));
        assertFalse(durable.isBlocked(rawId));
        assertTrue((Boolean) field(service, "pendingDebugOneShot"));
        assertTrue(((GearContactsSnapshot) field(service, "snapshot")).hasPendingVerification());
        assertEquals(retained, devicePrefs.getAll());
        assertLog("file transfer setup");
    }
    private void complete() throws Exception {
        final boolean debugTrial = requests.isActive();
        final Map<String, ?> before = new HashMap<>(devicePrefs.getAll());
        final Object file = files().peek();
        if (field(service, "pendingTransportSnapshot") != null && !(Boolean) field(file, "setupAccepted"))
            invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", "filetransfer-setup-rsp").put("result", 0)
                    .put("fileName", ((File) field(file, "path")).getName()).toString().getBytes(StandardCharsets.UTF_8));
        final Field offset = file.getClass().getDeclaredField("offset"); offset.setAccessible(true);
        offset.set(file, ((byte[]) field(file, "bytes")).length);
        final Field accepted = file.getClass().getDeclaredField("setupAccepted"); accepted.setAccessible(true); accepted.set(file, true);
        invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", "filetransfer-complete-req")
                .put("fileName", ((File) field(file, "path")).getName()).toString().getBytes(StandardCharsets.UTF_8));
        idle();
        // Even delivered debug I/D files must not promote or overwrite persistent production state.
        if (debugTrial) assertEquals(before, devicePrefs.getAll());
    }
    private JSONObject decoded(final Object file) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream((byte[]) field(file, "bytes")))) {
            assertNotNull(zip.getNextEntry());
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec((byte[]) field(service, "contactKey"), "AES"), new IvParameterSpec(new byte[16]));
            return new JSONObject(new String(cipher.doFinal(Base64.getMimeDecoder().decode(out.toByteArray())), StandardCharsets.UTF_8));
        }
    }
    private void assertLog(final String text) {
        assertTrue("Missing diagnostic: " + text, ShadowLog.getLogs().stream().anyMatch(log -> log.msg.contains(text)));
    }
    private ArrayDeque<?> files() throws Exception { return (ArrayDeque<?>) field(service, "files"); }
    private static Object field(final Object object, final String name) throws Exception {
        final Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private void set(final String name, final Object value) throws Exception {
        final Field f = service.getClass().getDeclaredField(name); f.setAccessible(true); f.set(service, value);
    }
    private void invoke(final String name, final Class<?> type, final Object value) throws Exception {
        final Method method = type == null ? service.getClass().getDeclaredMethod(name)
                : service.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        if (type == null) method.invoke(service); else method.invoke(service, value);
    }

    public static final class SyntheticProvider extends ContentProvider {
        int scans, debugScans;
        GearContactsDebugRequests controller;
        final Set<Long> trialIds = new HashSet<>();
        private Set<Long> ids() {
            final Set<Long> ids = new HashSet<>(Arrays.asList(1L, 2L));
            ids.addAll(trialIds); return ids;
        }
        String number = "+15550001001";
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            final MatrixCursor cursor = new MatrixCursor(projection);
            if (uri.equals(ContactsContract.RawContacts.CONTENT_URI)) {
                scans++;
                try { if (controller != null && field(controller, "deliveredTo") != null) debugScans++; }
                catch (Exception e) { throw new AssertionError(e); }
                for (long id : ids()) cursor.addRow(new Object[]{id, id + 1000, "Synthetic Person", 0});
            } else if (uri.equals(ContactsContract.Data.CONTENT_URI)) {
                for (long id : ids()) cursor.addRow(new Object[]{id,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, id >= 9119 ? number : "+15550001001", "2", null, null, 1});
            }
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
