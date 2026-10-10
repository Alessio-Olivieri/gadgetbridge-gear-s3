package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.Manifest;
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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTermination;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTerminationResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual production scan/transfer path; reserved synthetic numbers only. No debug authorization. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearContactsModifyTest {
    private static final String SNAPSHOT = "samsung_gear_contacts_acknowledged_snapshot_v1";
    private static final String OLD = "+15550001001", CURRENT = "+15550002002";
    private Context context;
    private SharedPreferences identity, prefs;
    private GearContactsService service;
    private GearContactsDebugRequests debug;
    private final List<SapPostAuthRecord> emitted = new ArrayList<>();
    private Provider provider;
    private String original;
    private String failSend;
    private boolean dropQueuedSetup, requireDurablePayloadMarker, requireConsentConsumed;
    private boolean emitterConnected = true;
    private int reconnectRequests;

    @Before public void setup() throws Exception {
        final Field budget = GearContactsService.class.getDeclaredField("CONTROL_RECONNECT_USED");
        budget.setAccessible(true);
        ((Set<?>) budget.get(null)).clear();
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS);
        identity = context.getSharedPreferences("modify-identity", Context.MODE_PRIVATE);
        prefs = context.getSharedPreferences("modify-device", Context.MODE_PRIVATE);
        identity.edit().clear().commit(); prefs.edit().clear().commit();
        provider = new Provider();
        provider.rows.put(1L, OLD); provider.rows.put(2L, OLD);
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider);
        final GearContactsSnapshot baseline = snapshot();
        assertTrue(baseline.recordTransportDeliveredSnapshot(baseline.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), true)));
        original = prefs.getString(SNAPSHOT, null);
    }

    @After public void cleanup() {
        if (service != null) service.dispose();
        if (debug != null) debug.close();
    }

    private GearContactsSnapshot snapshot() { return new GearContactsSnapshot(new GearContactsSnapshotStore(prefs)); }
    private static GearContactsCodec.Contact contact(long id, String number) {
        return new GearContactsCodec.Contact(id, id + 1000, id, "Synthetic Person", null, null, false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }

    private void create() throws Exception {
        create(new GearContactsDebugRequests());
    }

    private void create(GearContactsDebugRequests requests) throws Exception {
        create(requests, "00:11:22:33:44:55");
    }

    private void create(GearContactsDebugRequests requests, String address) throws Exception {
        debug = requests;
        service = new GearContactsService(context, identity, prefs, address, new GearSessionSecrets(),
                new SapHostManagerRegistration(0x1100, 0x1101), new HashSet<>(Arrays.asList(0x1100, 0x1101)),
                new GearContactsService.Emitter() {
                    @Override public Set<Integer> usedSessions() { return new HashSet<>(); }
                    @Override public boolean isConnected() { return emitterConnected; }
                    @Override public void reconnect(GearContactsService requester) { assertSame(service, requester); reconnectRequests++; }
                    @Override public void send(SapPostAuthRecord record) {
                        if (requireConsentConsumed) assertFalse(prefs.getBoolean(GearContactsService.ALLOW_FIRST_SYNC, false));
                        final int session = record.getHeader().getSessionId();
                        final byte[] body = record.getBody();
                        if (requireDurablePayloadMarker && session == integer("ftDataSession"))
                            assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
                        if (dropQueuedSetup && session != 1023 && body.length > 3 && body[3] == '{') return;
                        boolean fail = "payload".equals(failSend) && session == integer("ftDataSession");
                        fail |= "terminate".equals(failSend) && session == 1023 && body[0] == 3;
                        fail |= "open".equals(failSend) && session == 1023 && body[0] == 1;
                        fail |= "setup".equals(failSend) && session != 1023 && body.length > 3 && body[3] == '{';
                        if (fail) { failSend = null; throw new IllegalStateException("Synthetic transport failure"); }
                        emitted.add(record);
                    }
                }, debug);
        set("remoteContacts", 20); set("remoteFt", 21); set("contactsSession", 9);
        set("ftCommandSession", 10); set("ftDataSession", 11);
    }

    private void resume() throws Exception { invoke("onContactMessage", byte[].class, "Request_Resume_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain(); }
    private void idle() { ShadowLooper.shadowMainLooper().idle(); }
    private void drain() throws Exception {
        idle(); ((ExecutorService) get(service, "loader")).submit(() -> {}).get(5, TimeUnit.SECONDS); idle();
    }
    private String legacyUncertain() {
        assertTrue(snapshot().markPendingVerification());
        return prefs.getString(SNAPSHOT, null);
    }
    private void legacyRetained(String previous) {
        assertEquals(previous, prefs.getString(SNAPSHOT, null));
        assertTrue(snapshot().hasPendingVerification());
    }
    private void observedChange() throws Exception {
        ((android.database.ContentObserver) get(service, "observer")).onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(350)); drain();
    }

    private void stalledControl() throws Exception {
        ((Runnable) get(service, "controlWatchdog")).run();
    }

    private boolean controlClosed(int status) {
        final boolean result = service.onTerminationResponse(new SapServiceConnectionTerminationResponse(
                integer("contactsComponent"), 20, GearContactsService.CONTACTS, status));
        idle(); return result;
    }

    private void retryEstablished() throws Exception {
        assertTrue(controlClosed(0));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2)); drain();
        assertTrue(integer("pendingContacts") > 0);
        ((GearSessionSecrets) get(service, "secrets")).setPrf(new byte[32]);
        assertTrue(service.acceptResponse(new SapServiceConnectionResponse(20, integer("contactsComponent"),
                GearContactsService.CONTACTS, 0, Collections.singletonList(integer("pendingContacts")))));
    }

    private void pendingRecovery() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange(); controlBack();
        assertEquals(0, setups()); assertEquals("AWAITING_HANDSHAKE", get(service, "controlState").toString());
    }

    private void exhaustControlRecovery() throws Exception {
        for (int i = 0; i < 5; i++) { stalledControl(); retryEstablished(); }
        stalledControl(); assertTrue(controlClosed(0));
    }

    @Test public void handshakeDeadlineRetiresOnlyBrokenControlAndNeverGrantsReadiness() throws Exception {
        pendingRecovery(); final int ft = integer("ftCommandSession"), data = integer("ftDataSession");
        final long generation = (long) get(service, "controlGeneration");
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(59)); drain(); assertEquals("AWAITING_HANDSHAKE", get(service, "controlState").toString());
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1)); drain();
        assertEquals("RETIRING", get(service, "controlState").toString()); assertEquals(generation, get(service, "controlGeneration"));
        assertEquals(ft, integer("ftCommandSession")); assertEquals(data, integer("ftDataSession"));
        assertFalse((Boolean) get(service, "controlReady")); assertTrue((Boolean) get(service, "scanAgain")); assertEquals(0, setups());
        assertFalse(snapshot().isBlocked(3));
    }

    @Test public void retryHandshakeAutomaticallyScansLatestPhoneStateAndInsertsOnce() throws Exception {
        pendingRecovery(); final int scans = provider.scans; stalledControl(); retryEstablished();
        provider.rows.put(3L, OLD); for (int i = 0; i < 20; i++) observedChange();
        assertEquals(scans, provider.scans); assertEquals(0, setups());
        resume(); assertEquals(1, setups()); assertEquals("READY", get(service, "controlState").toString());
        assertNull(get(service, "controlWatchdog")); assertNull(get(service, "controlRetry"));
        complete(); assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
        resume(); assertEquals(1, setups());
    }

    @Test public void supersededHandshakeWatchdogCannotRetireNewReadyGeneration() throws Exception {
        pendingRecovery(); final Runnable stale = (Runnable) get(service, "controlWatchdog");
        stalledControl(); retryEstablished(); resume(); final long generation = (long) get(service, "controlGeneration");
        final int session = integer("contactsSession"); stale.run();
        assertEquals(generation, get(service, "controlGeneration")); assertEquals(session, integer("contactsSession"));
        assertEquals("READY", get(service, "controlState").toString()); assertEquals(1, setups()); assertEquals(0, reconnectRequests);
    }

    @Test public void staleCallbackCannotCancelNewGenerationEvenWhenTransportJustDisconnected() throws Exception {
        pendingRecovery(); final Runnable stale = (Runnable) get(service, "controlWatchdog");
        stalledControl(); retryEstablished(); final Runnable current = (Runnable) get(service, "controlWatchdog");
        emitterConnected = false; stale.run(); assertSame(current, get(service, "controlWatchdog"));
        assertEquals("AWAITING_HANDSHAKE", get(service, "controlState").toString());
        current.run(); assertNull(get(service, "controlWatchdog")); assertEquals("DISCONNECTED", get(service, "controlState").toString());
    }

    @Test public void supersededRetirementDeadlineCannotTerminateRetryGeneration() throws Exception {
        pendingRecovery(); stalledControl(); final Runnable stale = (Runnable) get(service, "controlWatchdog");
        retryEstablished(); final int session = integer("contactsSession"); stale.run();
        assertEquals(session, integer("contactsSession")); assertEquals("AWAITING_HANDSHAKE", get(service, "controlState").toString());
        resume(); assertEquals(1, setups());
    }

    @Test public void supersededRetryCallbackCannotRequestAnotherControlSession() throws Exception {
        pendingRecovery(); stalledControl(); assertTrue(controlClosed(0)); final Runnable stale = (Runnable) get(service, "controlRetry");
        controlBack(); final int records = emitted.size(); stale.run();
        assertEquals(records, emitted.size()); assertEquals("AWAITING_HANDSHAKE", get(service, "controlState").toString());
        resume(); assertEquals(1, setups());
    }

    @Test public void lateResumeDuringRetirementCannotAuthorizeNewOffers() throws Exception {
        pendingRecovery(); stalledControl(); resume();
        assertEquals("RETIRING", get(service, "controlState").toString()); assertEquals(0, setups()); assertFalse((Boolean) get(service, "controlReady"));
        retryEstablished(); resume(); assertEquals(1, setups());
    }

    @Test public void unrelatedControlTerminationResponseDoesNotReleaseRetirement() throws Exception {
        pendingRecovery(); stalledControl();
        assertFalse(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(22, 20, GearContactsService.CONTACTS, 0)));
        assertEquals("RETIRING", get(service, "controlState").toString()); assertNull(get(service, "controlRetry"));
        retryEstablished(); resume(); assertEquals(1, setups());
    }

    @Test public void missingControlCloseResponseHasBoundedLocalRetirement() throws Exception {
        pendingRecovery(); stalledControl(); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        assertEquals(-1, integer("contactsSession")); assertEquals("DISCONNECTED", get(service, "controlState").toString());
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2)); assertTrue(integer("pendingContacts") > 0); assertEquals(0, setups());
    }

    @Test public void rejectedControlCloseStillRetriesWithoutTouchingFt() throws Exception {
        pendingRecovery(); stalledControl(); assertTrue(controlClosed(1));
        assertEquals(10, integer("ftCommandSession")); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
        assertTrue(integer("pendingContacts") > 0); assertEquals(0, setups());
    }

    @Test public void unresponsiveControlConnectionRequestRetriesWithoutManufacturingReadiness() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange();
        assertEquals("CONNECTING", get(service, "controlState").toString()); stalledControl();
        assertEquals("DISCONNECTED", get(service, "controlState").toString());
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2)); assertEquals("CONNECTING", get(service, "controlState").toString());
        assertEquals(0, setups()); assertFalse(snapshot().isBlocked(3));
    }

    @Test public void rejectedControlConnectionResponseAutomaticallyRetries() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange();
        assertTrue(service.acceptResponse(new SapServiceConnectionResponse(20, integer("contactsComponent"), GearContactsService.CONTACTS, 1,
                Collections.singletonList(integer("pendingContacts")))));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2)); assertTrue(integer("pendingContacts") > 0); assertEquals(0, setups());
    }

    @Test public void invalidSessionRejectionRetriesWithoutWaitingForHandshakeOrChangingLedger() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange();
        final int old = integer("pendingContacts");
        assertTrue(service.acceptResponse(new SapServiceConnectionResponse(20, integer("contactsComponent"),
                GearContactsService.CONTACTS, 1, Collections.singletonList(0xffff))));
        assertEquals(-1, integer("pendingContacts"));
        assertEquals("DISCONNECTED", get(service, "controlState").toString());
        assertFalse((Boolean) get(service, "controlReady"));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
        assertTrue(integer("pendingContacts") > 0);
        assertNotEquals(old, integer("pendingContacts"));
        assertEquals(0, setups());
        assertEquals(original, prefs.getString(SNAPSHOT, null));
        assertFalse(snapshot().isBlocked(3));
    }

    @Test public void unrelatedInvalidSessionRejectionCannotRetirePendingContactsRequest() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange();
        final int pending = integer("pendingContacts");
        assertFalse(service.acceptResponse(new SapServiceConnectionResponse(21, integer("contactsComponent"),
                GearContactsService.CONTACTS, 1, Collections.singletonList(0xffff))));
        assertFalse(service.acceptResponse(new SapServiceConnectionResponse(20, integer("contactsComponent"),
                GearContactsService.CONTACTS, 0, Collections.singletonList(0xffff))));
        assertEquals(pending, integer("pendingContacts"));
    }

    @Test public void lateOldConnectionResponseCannotPoisonRetryRequest() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange(); final int old = integer("pendingContacts");
        stalledControl(); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2)); final int current = integer("pendingContacts");
        assertNotEquals(old, current);
        assertFalse(service.acceptResponse(new SapServiceConnectionResponse(20, integer("contactsComponent"), GearContactsService.CONTACTS, 0, Collections.singletonList(old))));
        assertEquals(current, integer("pendingContacts")); assertEquals(0, setups());
    }

    @Test public void fiveNarrowRetriesPrecedeSingleAutomaticRfcommFallback() throws Exception {
        pendingRecovery(); for (int i = 0; i < 5; i++) { stalledControl(); assertEquals(0, reconnectRequests); retryEstablished(); }
        stalledControl(); assertTrue(controlClosed(0)); assertEquals(1, reconnectRequests);
        assertEquals("EXHAUSTED", get(service, "controlState").toString()); assertNull(get(service, "controlWatchdog")); assertNull(get(service, "controlRetry"));
        for (int i = 0; i < 20; i++) observedChange(); assertEquals(1, reconnectRequests); assertEquals(0, setups());
    }

    @Test public void rfcommFallbackBudgetSurvivesServiceRecreationUntilHealthyHandshake() throws Exception {
        pendingRecovery(); exhaustControlRecovery(); assertEquals(1, reconnectRequests);
        service.dispose(); debug.close(); create(); controlBack(); exhaustControlRecovery(); assertEquals(1, reconnectRequests);
        controlBack(); resume(); complete(); assertEquals(1, reconnectRequests);
        controlLost(); provider.rows.put(4L, CURRENT); observedChange(); controlBack(); exhaustControlRecovery(); assertEquals(2, reconnectRequests);
    }

    @Test public void queuedRfcommEscalationCannotDisposeSupersedingGeneration() throws Exception {
        pendingRecovery(); set("controlRetries", 5); stalledControl();
        assertTrue(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(
                integer("contactsComponent"), 20, GearContactsService.CONTACTS, 0)));
        final Runnable stale = (Runnable) get(service, "controlEscalation"); assertNotNull(stale); assertEquals(0, reconnectRequests);
        controlBack(); resume(); stale.run(); assertEquals(0, reconnectRequests);
        assertEquals("READY", get(service, "controlState").toString()); assertEquals(1, setups());
    }

    @Test public void fallbackBudgetIsDeviceAddressScoped() throws Exception {
        pendingRecovery(); exhaustControlRecovery(); assertEquals(1, reconnectRequests);
        service.dispose(); debug.close(); create(new GearContactsDebugRequests(), "00:11:22:33:44:66"); controlBack();
        exhaustControlRecovery(); assertEquals(2, reconnectRequests);
    }

    @Test public void activeInsertNeverCanceledByHandshakeRecovery() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); final Object active = get(service, "sending"); controlLost(); controlBack();
        stalledControl(); assertSame(active, get(service, "sending")); assertEquals(10, integer("ftCommandSession")); assertEquals(1, setups());
        retryEstablished(); assertSame(active, get(service, "sending")); complete(); drain(); assertEquals(1, setups());
        assertTrue(snapshot().isTransportUncertain(3)); resume(); assertEquals(1, setups()); assertEquals(0, reconnectRequests);
    }

    @Test public void activeDeleteCompletesAcrossHandshakeRetirementAndCheckpointsTombstone() throws Exception {
        provider.rows.remove(2L); create(); resume(); controlLost(); controlBack(); stalledControl(); retryEstablished();
        complete(); assertTrue(snapshot().isTransportDeleted(2)); resume(); assertEquals(1, setups());
    }

    @Test public void activeModifyDAndRetainedFreshIWaitForRetryResume() throws Exception {
        begin(); controlLost(); controlBack(); stalledControl(); retryEstablished(); complete(); fresh();
        assertEquals(1, setups()); assertNull(get(service, "sending")); assertTrue(snapshot().isBlocked(1));
        resume(); assertEquals(2, setups()); requireDurablePayloadMarker = true; complete(); assertFalse(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void activeModifyISurvivesHandshakeRecoveryWithoutDuplicateOffer() throws Exception {
        begin(); complete(); fresh(); final Object active = get(service, "sending"); controlLost(); controlBack(); stalledControl(); retryEstablished();
        assertSame(active, get(service, "sending")); complete(); resume(); assertEquals(2, setups()); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void exhaustedRecoveryDoesNotRecycleRfcommWhileDContinuationRetained() throws Exception {
        begin(); complete(); controlLost(); fresh(); controlBack(); exhaustControlRecovery();
        assertEquals(0, reconnectRequests); assertTrue((Boolean) get(service, "controlReconnectPending")); assertEquals(1, files().size());
        assertEquals(1, setups()); assertTrue(snapshot().isBlocked(1));
        controlBack(); resume(); complete(); assertFalse(snapshot().isBlocked(1)); assertEquals(0, reconnectRequests);
    }

    @Test public void exhaustedRecoveryDefersRfcommUntilAlreadyOfferedInsertCompletes() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); controlLost(); controlBack();
        // No elapsed payload timeout here: exercise recovery callbacks while the independent setup is pending.
        set("controlRetries", 5); stalledControl(); assertTrue(controlClosed(0)); assertEquals(0, reconnectRequests);
        assertNotNull(get(service, "sending")); complete(); drain(); assertEquals(1, reconnectRequests);
        assertTrue(snapshot().isTransportUncertain(3)); assertFalse(snapshot().isBlocked(3));
    }

    @Test public void disconnectedTransportCancelsWatchdogWithoutAffectingLedger() throws Exception {
        pendingRecovery(); final String ledger = prefs.getString("samsung_gear_contacts_transport_ledger_v1", null);
        emitterConnected = false; stalledControl(); assertNull(get(service, "controlWatchdog")); assertNull(get(service, "controlRetry"));
        assertEquals("DISCONNECTED", get(service, "controlState").toString()); assertEquals(0, reconnectRequests);
        assertEquals(ledger, prefs.getString("samsung_gear_contacts_transport_ledger_v1", null));
    }

    @Test public void disposalInvalidatesCapturedWatchdogAndRetryCallbacks() throws Exception {
        pendingRecovery(); final Runnable stalled = (Runnable) get(service, "controlWatchdog");
        stalledControl(); assertTrue(controlClosed(0)); final Runnable retry = (Runnable) get(service, "controlRetry");
        service.dispose(); final int records = emitted.size(); stalled.run(); retry.run();
        assertEquals(records, emitted.size()); assertNull(get(service, "controlWatchdog")); assertNull(get(service, "controlRetry")); assertEquals(0, reconnectRequests);
    }

    @Test public void actualDeviceDisconnectBroadcastDisposesServiceAndCancelsRecovery() throws Exception {
        pendingRecovery(); final Runnable stale = (Runnable) get(service, "controlWatchdog"); emitterConnected = false;
        final nodomain.freeyourgadget.gadgetbridge.impl.GBDevice device = new nodomain.freeyourgadget.gadgetbridge.impl.GBDevice(
                "00:11:22:33:44:55", "Synthetic Gear", null, null, nodomain.freeyourgadget.gadgetbridge.model.DeviceType.SAMSUNG_GEAR_S3);
        final Intent intent = new Intent(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.ACTION_DEVICE_CHANGED)
                .putExtra(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.EXTRA_DEVICE, device);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcastSync(intent);
        assertTrue((Boolean) get(service, "disposed")); assertNull(get(service, "controlWatchdog"));
        final int records = emitted.size(); stale.run(); assertEquals(records, emitted.size()); assertEquals(0, reconnectRequests);
    }

    @Test public void anotherDeviceDisconnectCannotDisposeThisService() throws Exception {
        pendingRecovery(); emitterConnected = false;
        final nodomain.freeyourgadget.gadgetbridge.impl.GBDevice device = new nodomain.freeyourgadget.gadgetbridge.impl.GBDevice(
                "00:11:22:33:44:66", "Synthetic Gear", null, null, nodomain.freeyourgadget.gadgetbridge.model.DeviceType.SAMSUNG_GEAR_S3);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcastSync(
                new Intent(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.ACTION_DEVICE_CHANGED)
                        .putExtra(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.EXTRA_DEVICE, device));
        assertFalse((Boolean) get(service, "disposed")); assertNotNull(get(service, "controlWatchdog"));
    }

    @Test public void delayedDisconnectBroadcastCannotDisposeReconnectedLiveTransport() throws Exception {
        pendingRecovery();
        final nodomain.freeyourgadget.gadgetbridge.impl.GBDevice device = new nodomain.freeyourgadget.gadgetbridge.impl.GBDevice(
                "00:11:22:33:44:55", "Synthetic Gear", null, null, nodomain.freeyourgadget.gadgetbridge.model.DeviceType.SAMSUNG_GEAR_S3);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcastSync(
                new Intent(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.ACTION_DEVICE_CHANGED)
                        .putExtra(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.EXTRA_DEVICE, device));
        assertFalse((Boolean) get(service, "disposed")); assertNotNull(get(service, "controlWatchdog"));
        resume(); assertEquals(1, setups());
    }

    @Test public void supportDisconnectCancelsOwnedContactsRecovery() throws Exception {
        pendingRecovery(); final GearS3DeviceSupport support = new GearS3DeviceSupport();
        final Field field = GearS3DeviceSupport.class.getDeclaredField("contactsService"); field.setAccessible(true); field.set(support, service);
        support.disconnect(); assertNull(field.get(support)); assertTrue((Boolean) get(service, "disposed"));
        assertNull(get(service, "controlWatchdog")); assertNull(get(service, "controlRetry")); assertEquals(0, reconnectRequests);
    }

    @Test public void realInitialCancelsHandshakeRecoveryWithoutChangingInitialImportGating() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); // Explicitly authorized synthetic first sync; no local baseline.
        create(); controlBack(); final Runnable old = (Runnable) get(service, "controlWatchdog");
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertNull(get(service, "controlWatchdog")); old.run(); assertEquals("READY", get(service, "controlState").toString());
        assertEquals(1, setups()); assertTrue((Boolean) get(service, "pendingInitial"));
    }

    @Test public void deleteDetectedDuringBrokenGenerationAutomaticallyRunsAfterRecoveryResume() throws Exception {
        create(); resume(); controlLost(); provider.rows.remove(2L); observedChange(); controlBack();
        stalledControl(); retryEstablished(); assertEquals(0, setups()); resume(); assertEquals(1, setups()); complete();
        assertTrue(snapshot().isTransportDeleted(2)); resume(); assertEquals(1, setups());
    }

    @Test public void modifyDetectedDuringBrokenGenerationAutomaticallyRunsAfterRecoveryResume() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(1L, CURRENT); observedChange(); controlBack();
        stalledControl(); retryEstablished(); assertEquals(0, setups()); resume(); assertEquals(1, setups());
        complete(); fresh(); assertEquals(2, setups()); complete(); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void multipleChangesWhileUnreadyCoalesceWithoutRepeatedQueries() throws Exception {
        pendingRecovery(); final int scans = provider.scans;
        for (int i = 0; i < 20; i++) { provider.rows.put(3L, i % 2 == 0 ? OLD : CURRENT); observedChange(); }
        provider.rows.put(1L, CURRENT); provider.rows.remove(2L); observedChange();
        assertEquals(scans, provider.scans); assertEquals(0, setups()); stalledControl(); retryEstablished(); resume();
        complete(); drain(); assertEquals(2, files().size());
        complete(); fresh(); complete(); drain();
        resume(); assertEquals(1, files().size()); complete();
        assertTrue(snapshot().isTransportDeleted(2)); assertTrue(snapshot().isTransportUncertain(1)); assertTrue(snapshot().isTransportUncertain(3));
        resume(); assertEquals(4, setups());
    }

    @Test public void uncertainLegacyNewIdOnResumeInsertsWithoutAnyDebugAuthorization() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.put(3L, CURRENT); create(); resume();
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert"));
        assertFalse((Boolean) get(service, "pendingDebugOneShot")); assertFalse((Boolean) get(service, "pendingPersistentTest"));
        assertFalse(debug.hasProductionTest()); assertFalse(debug.hasPersistentTest());
        complete(); assertEquals(GearContactsSnapshot.fingerprint(contact(3, CURRENT)), snapshot().lastTransportDeliveredFingerprint(3));
        assertTrue(snapshot().isTransportUncertain(3)); legacyRetained(legacy);
        recreateDurableService(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty()); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyUnchangedIdsSendNothingAndAreNotBulkSeeded() throws Exception {
        final String legacy = legacyUncertain(); create(); resume();
        assertEquals(0, setups()); assertTrue(files().isEmpty()); assertNull(prefs.getString("samsung_gear_contacts_transport_ledger_v1", null));
        assertFalse(snapshot().isTransportUncertain(1)); assertFalse(snapshot().isTransportUncertain(2)); legacyRetained(legacy);
        recreateDurableService(); resume(); assertEquals(0, setups()); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyModifiedIdMigratesOnlyAfterCompleteProductionReplacement() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.put(1L, CURRENT); create(); resume();
        assertEquals(2, files().size()); assertFalse((Boolean) get(service, "pendingDebugOneShot"));
        assertEquals(1, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); assertFalse(snapshot().isTransportUncertain(1)); assertTrue(snapshot().isBlocked(1));
        assertEquals(1, setups()); fresh(); assertTrue(decode(files().peek()).has("insert")); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
        assertTrue(snapshot().isTransportUncertain(1)); assertFalse(snapshot().isTransportUncertain(2)); legacyRetained(legacy);
        recreateDurableService(); resume(); assertEquals(2, setups()); assertTrue(files().isEmpty()); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyDeletedIdMigratesToDurableTombstoneWithoutPrepare() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.remove(2L); create(); resume();
        assertEquals(1, files().size()); assertFalse((Boolean) get(service, "pendingDebugOneShot"));
        assertEquals(2, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertFalse(snapshot().isTransportUncertain(2)); complete(); assertTrue(snapshot().isTransportDeleted(2));
        assertEquals(GearContactsSnapshot.fingerprint(contact(2, OLD)), snapshot().lastTransportDeliveredFingerprint(2));
        assertFalse(snapshot().isTransportUncertain(1)); legacyRetained(legacy);
        recreateDurableService(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty()); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyObserverNewIdInsertsPromptlyAndCoalesces() throws Exception {
        final String legacy = legacyUncertain(); create(); resume(); assertTrue((Boolean) get(service, "watching"));
        provider.rows.put(3L, CURRENT);
        final android.database.ContentObserver observer = (android.database.ContentObserver) get(service, "observer");
        for (int i = 0; i < 5; i++) observer.onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(349)); assertEquals(0, setups());
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(1)); drain(); assertEquals(1, setups());
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert")); complete(); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyObserverModifyUsesSameProductionReplacement() throws Exception {
        final String legacy = legacyUncertain(); create(); resume(); provider.rows.put(2L, CURRENT); observedChange();
        assertEquals(2, files().size()); assertFalse((Boolean) get(service, "pendingDebugOneShot"));
        complete(); fresh(); complete(); assertTrue(snapshot().isTransportUncertain(2)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyObserverDeletionIsDeferredToRealResume() throws Exception {
        final String legacy = legacyUncertain(); create(); resume(); provider.rows.remove(2L); observedChange();
        assertEquals(0, setups()); assertFalse(snapshot().isBlocked(2)); resume(); assertEquals(1, setups());
        complete(); assertTrue(snapshot().isTransportDeleted(2)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyBlockedIdDoesNotBlockNewInsert() throws Exception {
        final String legacy = legacyUncertain(); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        provider.rows.put(1L, CURRENT); provider.rows.put(3L, CURRENT); create(); resume();
        assertEquals(1, files().size()); complete(); assertTrue(snapshot().isTransportUncertain(3));
        assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().isTransportUncertain(1)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyBlockedIdDoesNotBlockOtherModify() throws Exception {
        final String legacy = legacyUncertain(); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        provider.rows.put(1L, CURRENT); provider.rows.put(2L, CURRENT); create(); resume();
        assertEquals(2, files().size()); assertEquals(2, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); fresh(); complete(); assertTrue(snapshot().isTransportUncertain(2)); assertTrue(snapshot().isBlocked(1)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyBlockedIdDoesNotBlockOtherDelete() throws Exception {
        final String legacy = legacyUncertain(); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        provider.rows.clear(); create(); resume(); assertEquals(1, files().size());
        final org.json.JSONArray deleted = decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list");
        assertEquals(1, deleted.length()); assertEquals(2, deleted.getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); assertTrue(snapshot().isTransportDeleted(2)); assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().isTransportDeleted(1)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyFailedDeleteKeepsOldHashAndBlocksOnlyFailedIdAfterRecreation() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.remove(2L); create(); resume(); failSend = "payload"; command("filetransfer-setup-rsp", 0);
        assertTrue(snapshot().isBlocked(2)); assertFalse(snapshot().isTransportDeleted(2)); assertFalse(snapshot().isTransportUncertain(2));
        recreateDurableService(); provider.rows.put(3L, CURRENT); resume(); complete();
        assertEquals(2, setups()); assertTrue(snapshot().isBlocked(2)); assertTrue(snapshot().isTransportUncertain(3)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyInterruptedModifyDoesNotMigrateOrBlindlyReplay() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.put(1L, CURRENT); create(); resume(); complete(); service.dispose();
        assertFalse(snapshot().isTransportUncertain(1)); assertTrue(snapshot().isBlocked(1));
        recreateDurableService(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, OLD)), snapshot().lastTransportDeliveredFingerprint(1)); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyInsertCheckpointUsesExactPackagedFingerprint() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.put(3L, OLD); create(); resume();
        assertTrue(decode(files().peek()).toString().contains(OLD)); provider.rows.put(3L, CURRENT); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
        resume(); assertEquals(2, files().size()); assertTrue((Boolean) get(service, "pendingReplacement")); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyModifyCheckpointUsesExactIAndDetectsNextPhoneEdit() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.put(1L, CURRENT); create(); resume(); complete(); fresh();
        provider.rows.put(1L, OLD); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
        recreateDurableService(); resume(); assertEquals(2, files().size()); assertTrue((Boolean) get(service, "pendingReplacement")); legacyRetained(legacy);
    }

    @Test public void uncertainLegacyDeletedIdReappearsAsCurrentInsertAfterRestart() throws Exception {
        final String legacy = legacyUncertain(); provider.rows.remove(2L); create(); resume(); complete();
        recreateDurableService(); provider.rows.put(2L, CURRENT); resume();
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert")); complete();
        assertFalse(snapshot().isTransportDeleted(2)); assertEquals(GearContactsSnapshot.fingerprint(contact(2, CURRENT)), snapshot().lastTransportDeliveredFingerprint(2)); legacyRetained(legacy);
    }

    @Test public void corruptLedgerStillStopsUncertainLegacyNewIdWithoutReimport() throws Exception {
        final String legacy = legacyUncertain(); prefs.edit().putString("samsung_gear_contacts_transport_ledger_v1", "invalid").commit();
        provider.rows.put(3L, CURRENT); create(); resume(); assertEquals(0, setups()); assertTrue(files().isEmpty());
        assertTrue(snapshot().hasInvalidLedger()); assertEquals(0, provider.scans); legacyRetained(legacy);
    }

    private void begin() throws Exception { provider.rows.put(1L, CURRENT); create(); resume(); assertEquals(2, files().size()); }
    private void command(String type, int result) throws Exception {
        invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", type).put("result", result)
                .put("reason", -1).put("fileName", ((File) get(files().peek(), "path")).getName())
                .toString().getBytes(StandardCharsets.UTF_8)); idle();
    }
    private void complete() throws Exception {
        // Exercise the actual accepted-setup -> durable payload boundary before completion.
        if (!(Boolean) get(files().peek(), "setupAccepted")) command("filetransfer-setup-rsp", 0);
        final Object file = files().peek();
        final Field offset = file.getClass().getDeclaredField("offset"); offset.setAccessible(true);
        offset.set(file, ((byte[]) get(file, "bytes")).length);
        final Field accepted = file.getClass().getDeclaredField("setupAccepted"); accepted.setAccessible(true); accepted.set(file, true);
        command("filetransfer-complete-req", 0);
    }
    private SapServiceConnectionTerminationResponse closed(int status) {
        return new SapServiceConnectionTerminationResponse(integer("ftComponent"), 21, GearContactsService.FILE_TRANSFER, status);
    }
    private SapServiceConnectionResponse opened(int status) {
        return new SapServiceConnectionResponse(21, integer("ftComponent"), GearContactsService.FILE_TRANSFER, status,
                Arrays.asList(integer("pendingFtData"), integer("pendingFtCommand")));
    }
    private void fresh() { assertTrue(service.onTerminationResponse(closed(0))); assertTrue(service.acceptResponse(opened(0))); }
    private void unchangedUncertain() throws Exception {
        assertEquals(original, prefs.getString(SNAPSHOT, null)); // Legacy rows never rewritten by deltas.
        final GearContactsSnapshot saved = snapshot();
        assertTrue(saved.isInitialized()); assertFalse(saved.hasPendingVerification());
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, saved.isBlocked(1) || !saved.isTransportUncertain(1) ? OLD : CURRENT)), saved.lastTransportDeliveredFingerprint(1));
    }
    private int setups() throws Exception {
        int count = 0;
        for (SapPostAuthRecord record : emitted) {
            byte[] body = record.getBody();
            if (record.getHeader().getSessionId() != 1023 && body.length > 3 && body[2] == 0 && body[3] == '{'
                    && "filetransfer-setup-req".equals(new JSONObject(new String(GearContactsCodec.payload(body), StandardCharsets.UTF_8)).optString("msgId"))) count++;
        }
        return count;
    }

    @Test public void sameBaselineIdUsesDeleteIdentityAndCurrentInsertWithoutU() throws Exception {
        begin();
        final Object[] pair = files().toArray();
        final JSONObject deletion = decode(pair[0]), insertion = decode(pair[1]);
        assertEquals(1, deletion.length()); assertEquals(1, insertion.length());
        final JSONObject raw = deletion.getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact");
        assertEquals(1, raw.length()); assertEquals(1, raw.getLong("id"));
        assertEquals(GearContactsCodec.change("insert", Collections.singletonList(contact(1, CURRENT)), Collections.emptyList()).toString(), insertion.toString());
        assertFalse(insertion.toString().contains(OLD));
        assertFalse((Boolean) get(service, "pendingDebugOneShot"));
        unchangedUncertain(); // Local D setup does not create a durable payload-stage block.
        assertFalse(snapshot().isBlocked(1));
        final String priorFingerprint = snapshot().lastTransportDeliveredFingerprint(1);
        complete(); // Delivered D alone must not checkpoint the new I representation.
        assertTrue(snapshot().isBlocked(1));
        assertEquals(priorFingerprint, snapshot().lastTransportDeliveredFingerprint(1));
        fresh(); complete();
        assertFalse(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void productionWaitsForMatchedCloseAndFreshSapThenActuallySendsI() throws Exception {
        begin(); complete();
        assertEquals(1, setups()); assertNull(get(service, "sending"));
        assertFalse(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(22, 21, GearContactsService.FILE_TRANSFER, 0)));
        assertFalse(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(integer("ftComponent"), 21, GearContactsService.CONTACTS, 0)));
        assertTrue(service.onTerminationResponse(closed(0)));
        assertEquals(1, setups()); assertNull(get(service, "sending"));
        assertNotEquals(10, integer("pendingFtCommand")); assertNotEquals(11, integer("pendingFtData"));
        assertTrue(service.acceptResponse(opened(0)));
        assertEquals(2, setups()); assertEquals(0, get(files().peek(), "offset"));
        command("filetransfer-setup-rsp", 0);
        assertEquals(((byte[]) get(files().peek(), "bytes")).length, get(files().peek(), "offset"));
        complete(); assertTrue(files().isEmpty()); unchangedUncertain();
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("transport-delivered checkpoint: rawId=1")));
    }

    @Test public void successfulReplacementCheckpointsWithoutResendAfterRecreation() throws Exception {
        begin(); complete(); fresh(); complete(); unchangedUncertain();
        service.dispose(); debug.close(); create(); resume();
        assertEquals(2, setups()); assertTrue(files().isEmpty()); assertEquals(2, provider.scans);
        unchangedUncertain();
    }

    private void controlLost() {
        assertTrue(service.onTermination(new SapServiceConnectionTermination(20, integer("contactsComponent"), GearContactsService.CONTACTS)));
    }

    private void controlBack() throws Exception {
        ((GearSessionSecrets) get(service, "secrets")).setPrf(new byte[32]);
        set("contactsSession", 12 + (int) (long) get(service, "controlGeneration"));
        service.establishedContacts();
        drain();
    }

    @Test public void deletePendingSetupSurvivesControlLossAndCompletesIndependently() throws Exception {
        provider.rows.remove(2L); create(); resume(); final Object offered = get(service, "sending");
        controlLost(); assertSame(offered, get(service, "sending")); assertEquals(10, integer("ftCommandSession"));
        assertEquals(11, integer("ftDataSession")); assertFalse(snapshot().isBlocked(2));
        complete(); assertTrue(snapshot().isTransportDeleted(2)); assertFalse(snapshot().isBlocked(2));
        controlBack(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }

    @Test public void deleteControlRestorationAndNewResumeDoNotDuplicatePendingOffer() throws Exception {
        provider.rows.remove(2L); create(); resume(); controlLost(); controlBack(); resume();
        assertEquals(1, setups()); assertFalse(snapshot().isBlocked(2)); complete(); drain();
        assertTrue(snapshot().isTransportDeleted(2)); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }

    @Test public void prePayloadDeviceDisposalAfterControlLossStillAllowsDeleteRetry() throws Exception {
        provider.rows.remove(2L); create(); resume(); controlLost(); service.dispose();
        assertFalse(snapshot().isBlocked(2)); assertFalse(snapshot().isTransportDeleted(2));
        recreateDurableService(); resume(); assertEquals(1, files().size()); assertEquals(2, setups());
    }

    @Test public void actualFtTerminationBeforePayloadReacquiresAndRetriesDeleteWithoutNewResume() throws Exception {
        provider.rows.remove(2L); create(); resume();
        assertTrue(service.onTermination(new SapServiceConnectionTermination(21, integer("ftComponent"), GearContactsService.FILE_TRANSFER)));
        assertTrue(files().isEmpty()); assertFalse(snapshot().isBlocked(2)); assertFalse(snapshot().isTransportDeleted(2));
        assertTrue(service.acceptResponse(opened(0))); drain(); complete(); assertTrue(snapshot().isTransportDeleted(2));
    }

    @Test public void insertSetupSurvivesControlChurnAndCheckpointsOnce() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); final Object offered = get(service, "sending");
        controlLost(); controlBack(); resume(); assertSame(offered, get(service, "sending")); assertEquals(1, setups());
        complete(); drain(); assertEquals(GearContactsSnapshot.fingerprint(contact(3, CURRENT)), snapshot().lastTransportDeliveredFingerprint(3));
        assertFalse(snapshot().isBlocked(3)); assertEquals(1, setups());
    }

    @Test public void modifyDSetupContinuesWhileControlAbsentThenIWaitsForRestoration() throws Exception {
        begin(); final Object offered = get(service, "sending"); controlLost();
        assertSame(offered, get(service, "sending")); assertFalse(snapshot().isBlocked(1)); complete();
        assertTrue(snapshot().isBlocked(1)); fresh(); assertNull(get(service, "sending")); assertEquals(1, setups());
        controlBack(); assertEquals(1, setups()); resume(); assertEquals(2, setups()); complete(); assertFalse(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void modifyClosingBoundarySurvivesControlLossWithMatchingTerminationStillRequired() throws Exception {
        begin(); complete(); controlLost(); assertEquals("CLOSING_FT", get(service, "replaceBoundary").toString());
        assertFalse(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(22, 21, GearContactsService.FILE_TRANSFER, 0)));
        assertEquals(1, setups()); fresh(); assertNull(get(service, "sending"));
        controlBack(); assertEquals(1, setups()); resume(); complete(); assertEquals(2, setups()); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void modifyFreshSapOpeningSurvivesControlLoss() throws Exception {
        begin(); complete(); assertTrue(service.onTerminationResponse(closed(0))); controlLost();
        assertEquals("OPENING_FT", get(service, "replaceBoundary").toString());
        assertTrue(service.acceptResponse(opened(0))); assertNull(get(service, "sending")); assertEquals(1, setups());
        controlBack(); assertEquals(1, setups()); resume(); complete(); assertEquals(2, setups()); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void modifyISetupContinuesIndependentlyAfterControlTermination() throws Exception {
        begin(); complete(); fresh(); final Object offered = get(service, "sending"); controlLost();
        assertSame(offered, get(service, "sending")); complete(); assertFalse(snapshot().isBlocked(1));
        controlBack(); resume(); assertEquals(2, setups()); assertTrue(files().isEmpty());
    }

    @Test public void modifyPayloadAfterControlLossStillArmsV3BeforeEnqueue() throws Exception {
        begin(); controlLost(); requireDurablePayloadMarker = true; command("filetransfer-setup-rsp", 0);
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
        service.dispose(); assertTrue(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, OLD)), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void actualFtTerminationAfterPayloadStillProtectsAmbiguousId() throws Exception {
        begin(); command("filetransfer-setup-rsp", 0); controlLost();
        assertTrue(service.onTermination(new SapServiceConnectionTermination(21, integer("ftComponent"), GearContactsService.FILE_TRANSFER)));
        assertTrue(files().isEmpty()); assertTrue(snapshot().isBlocked(1));
        controlBack(); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }

    @Test public void pendingRealResumeDeletionIntentSurvivesBusyInsertAndControlChurn() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); provider.rows.remove(2L); resume();
        controlLost(); controlBack(); complete(); drain(); assertTrue(files().isEmpty()); resume();
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("delete"));
        complete(); assertTrue(snapshot().isTransportDeleted(2)); assertEquals(2, setups());
    }

    @Test public void nextReplacementPairWaitsForControlAndDoesNotReplayCompletedPair() throws Exception {
        provider.rows.put(1L, CURRENT); provider.rows.put(2L, CURRENT); create(); resume(); complete(); fresh();
        controlLost(); complete(); fresh(); assertEquals(2, setups()); assertNull(get(service, "sending"));
        assertFalse(snapshot().isBlocked(1)); assertFalse(snapshot().isBlocked(2));
        controlBack(); assertEquals(2, setups()); resume(); complete(); fresh(); complete(); drain();
        assertEquals(4, setups()); assertFalse(snapshot().isBlocked(2));
    }

    @Test public void observerChangeWhileControlAbsentWaitsForCurrentGenerationResume() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT); observedChange();
        assertEquals(0, setups()); controlBack(); assertEquals(0, setups()); resume(); assertEquals(1, setups()); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, CURRENT)), snapshot().lastTransportDeliveredFingerprint(3));
    }

    @Test public void firstControlEstablishmentWithoutRealResumeStillSendsNothing() throws Exception {
        provider.rows.put(3L, CURRENT); create(); controlBack(); assertEquals(0, setups()); assertTrue(files().isEmpty());
        resume(); assertEquals(1, setups());
    }

    @Test public void repeatedControlChurnAndResumeDoNotLoopOrDuplicateDelete() throws Exception {
        provider.rows.remove(2L); create(); resume();
        for (int i = 0; i < 3; i++) { controlLost(); controlBack(); resume(); }
        assertEquals(1, setups()); complete(); drain(); final int scans = provider.scans;
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2)); drain();
        assertEquals(scans, provider.scans); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }

    @Test public void blockedIdDoesNotStopCurrentGenerationResumeFromInsertingUnrelatedId() throws Exception {
        begin(); command("filetransfer-setup-rsp", 0);
        assertTrue(service.onTermination(new SapServiceConnectionTermination(21, integer("ftComponent"), GearContactsService.FILE_TRANSFER)));
        controlLost(); provider.rows.put(3L, CURRENT); controlBack(); assertTrue(files().isEmpty()); resume();
        assertTrue(snapshot().isBlocked(1)); assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert"));
        assertTrue(service.acceptResponse(opened(0))); complete(); assertTrue(snapshot().isBlocked(1)); assertTrue(snapshot().isTransportUncertain(3));
    }

    @Test public void deleteCompletedDuringControlLossSurvivesProcessRecreationWithoutResend() throws Exception {
        provider.rows.remove(2L); create(); resume(); controlLost(); complete(); recreateDurableService(); resume();
        assertTrue(snapshot().isTransportDeleted(2)); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }

    @Test public void queuedDebugInsertStillWaitsForNewRealControlBoundary() throws Exception {
        create(); resume(); controlLost(); provider.rows.put(3L, CURRENT);
        assertTrue(debug.request(GearContactsDebugRequests.Mode.INSERT, 3)); controlBack();
        assertTrue(debug.hasPending()); assertEquals("waiting for Resume boundary", service.debugWaitingReason()); assertEquals(0, setups());
        // Production also sees the new ID, but developer quarantine must not run a debug trial at restoration.
        assertFalse((Boolean) get(service, "pendingDebugOneShot"));
    }

    @Test public void postDWaitForCurrentGenerationReadinessHasNoArbitraryDelayOrTimeout() throws Exception {
        begin(); complete(); controlLost(); fresh();
        assertEquals(1, setups()); assertNull(get(service, "sending"));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(60));
        assertTrue(snapshot().isBlocked(1)); assertEquals(1, files().size());
        controlBack(); assertEquals(1, setups()); resume(); assertEquals(2, setups()); complete();
        assertFalse(snapshot().isBlocked(1));
    }

    @Test public void prePayloadDeleteTimeoutDuringControlLossRetriesOnNewResumeWithoutAnotherDeviceReconnect() throws Exception {
        provider.rows.remove(2L); create(); resume(); controlLost();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        assertTrue(files().isEmpty()); assertFalse(snapshot().isBlocked(2)); assertFalse(snapshot().isTransportDeleted(2));
        assertTrue(service.onTerminationResponse(closed(0)));
        controlBack(); assertEquals(1, setups()); resume();
        assertTrue(service.acceptResponse(opened(0))); drain(); assertEquals(2, setups()); assertEquals(1, files().size());
        complete(); drain(); assertTrue(snapshot().isTransportDeleted(2)); assertEquals(2, setups());
    }

    private void watchResumeRecord(final int session) throws Exception {
        service.onRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0, session, 0),
                GearContactsCodec.frame(1, "Request_Resume_Sync_Contact".getBytes(StandardCharsets.UTF_8))));
        drain();
    }

    @Test public void keyOnlyQueuedCannotStartNewInsert() throws Exception {
        create(); resume(); controlLost(); controlBack(); provider.rows.put(9131L, CURRENT); observedChange();
        assertEquals(-1L, get(service, "readyControlGeneration")); assertEquals(0, setups()); assertTrue(files().isEmpty());
        assertFalse(snapshot().isTransportUncertain(9131)); resume(); assertEquals(1, setups());
        complete(); assertEquals(GearContactsSnapshot.fingerprint(contact(9131, CURRENT)), snapshot().lastTransportDeliveredFingerprint(9131));
    }

    @Test public void staleRetiredResumeCannotAuthorizeReplacementControlGeneration() throws Exception {
        create(); resume(); final int oldSession = integer("contactsSession"); controlLost(); controlBack(); provider.rows.put(3L, CURRENT);
        watchResumeRecord(oldSession); assertFalse((Boolean) get(service, "controlReady")); assertEquals(0, setups());
        watchResumeRecord(integer("contactsSession")); assertTrue((Boolean) get(service, "controlReady")); assertEquals(1, setups());
    }

    @Test public void controlSessionRetirementPreventsOldResumeSessionAliasing() throws Exception {
        create(); resume(); final int previous = integer("contactsSession"); controlLost();
        final HashSet<Integer> used = new HashSet<>(); service.addUsedSessions(used); assertTrue(used.contains(previous));
        controlBack(); assertNotEquals(previous, integer("contactsSession"));
    }

    @Test public void repeatedUnreadyControlGenerationsDoNotInheritAuthorization() throws Exception {
        create(); resume(); controlLost(); controlBack(); final long first = (Long) get(service, "controlGeneration");
        provider.rows.put(3L, CURRENT); observedChange(); controlLost(); controlBack(); observedChange();
        assertTrue((Long) get(service, "controlGeneration") > first); assertEquals(-1L, get(service, "readyControlGeneration"));
        assertEquals(0, setups()); resume(); assertEquals(1, setups());
    }

    @Test public void sourceChangesWhileKeyQueuedUseCurrentRepresentationAfterResume() throws Exception {
        create(); resume(); controlLost(); controlBack(); provider.rows.put(3L, OLD); observedChange();
        provider.rows.put(3L, CURRENT); observedChange(); observedChange(); assertEquals(0, setups());
        resume(); assertEquals(1, setups()); final String sent = decode(files().peek()).toString(); assertTrue(sent.contains(CURRENT)); assertFalse(sent.contains(OLD));
        complete(); resume(); assertEquals(1, setups());
    }

    @Test public void deleteNewOfferWaitsForCurrentGenerationReadiness() throws Exception {
        create(); resume(); controlLost(); controlBack(); provider.rows.remove(2L); observedChange();
        assertEquals(0, setups()); assertFalse(snapshot().isBlocked(2)); assertFalse(snapshot().isTransportDeleted(2));
        resume(); assertEquals(1, setups()); complete(); assertTrue(snapshot().isTransportDeleted(2));
    }

    @Test public void modifyNewDOfferWaitsForCurrentGenerationReadiness() throws Exception {
        create(); resume(); controlLost(); controlBack(); provider.rows.put(1L, CURRENT); observedChange();
        assertEquals(0, setups()); assertFalse(snapshot().isBlocked(1)); resume(); assertEquals(1, setups());
        complete(); fresh(); assertEquals(2, setups()); complete(); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void activeInsertCompletesBeforeNewResumeButNextPhoneEditWaits() throws Exception {
        provider.rows.put(3L, OLD); create(); resume(); controlLost(); controlBack(); provider.rows.put(3L, CURRENT);
        complete(); drain(); assertEquals(1, setups()); assertTrue(files().isEmpty());
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
        resume(); assertEquals(2, setups()); complete(); fresh(); complete(); assertEquals(3, setups());
    }

    @Test public void queuedReplacementIStillWaitsAfterRestorationAndLocalKeyRequest() throws Exception {
        begin(); complete(); controlLost(); fresh(); controlBack();
        invoke("onContactMessage", byte[].class, "Request_Encryption_Key".getBytes(StandardCharsets.UTF_8)); drain();
        assertEquals(1, setups()); assertNull(get(service, "sending")); assertTrue(snapshot().isBlocked(1));
        resume(); assertEquals(2, setups()); requireDurablePayloadMarker = true; complete(); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void multipleNewIdsCoalesceIntoOneInsertAfterGenerationReady() throws Exception {
        create(); resume(); controlLost(); controlBack(); provider.rows.put(3L, CURRENT); observedChange(); provider.rows.put(4L, CURRENT); observedChange();
        assertEquals(0, setups()); resume(); resume(); assertEquals(1, setups());
        complete(); drain(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
        assertTrue(snapshot().isTransportUncertain(3)); assertTrue(snapshot().isTransportUncertain(4));
    }

    @Test public void serviceDisposalResetsReadinessAndRecreationCannotManufactureIt() throws Exception {
        create(); resume(); assertEquals(get(service, "controlGeneration"), get(service, "readyControlGeneration"));
        service.dispose(); assertFalse((Boolean) get(service, "controlReady")); assertEquals(-1L, get(service, "readyControlGeneration"));
        recreateDurableService(); provider.rows.put(3L, CURRENT); controlBack(); observedChange(); assertEquals(0, setups());
        resume(); assertEquals(1, setups());
    }

    @Test public void initialControlAllowsFirstGenerationImportButOldCompletionCannotAuthorizeNewGeneration() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); // First import requires consent and no retained local state.
        create(); invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertTrue((Boolean) get(service, "pendingInitial")); assertEquals(1, setups());
        controlLost(); controlBack(); complete(); assertTrue(snapshot().isInitialized()); assertFalse((Boolean) get(service, "controlReady"));
        provider.rows.put(3L, CURRENT); observedChange(); assertEquals(1, setups());
        resume(); assertEquals(2, setups()); complete();
    }


    @Test public void closeRejectionStops() throws Exception { begin(); complete(); assertTrue(service.onTerminationResponse(closed(1))); stopped(1); }
    @Test public void closeTimeoutStops() throws Exception { begin(); complete(); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10)); stopped(1); assertFalse(service.onTerminationResponse(closed(0))); }
    @Test public void closeSendFailureStops() throws Exception { begin(); failSend = "terminate"; complete(); stopped(1); }
    @Test public void freshSapRejectionStops() throws Exception { begin(); complete(); assertTrue(service.onTerminationResponse(closed(0))); assertTrue(service.acceptResponse(opened(1))); stopped(1); }
    @Test public void freshSapTimeoutStops() throws Exception { begin(); complete(); assertTrue(service.onTerminationResponse(closed(0))); final SapServiceConnectionResponse late = opened(0); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10)); stopped(1); assertFalse(service.acceptResponse(late)); }
    @Test public void freshSapRequestFailureStops() throws Exception { begin(); complete(); failSend = "open"; assertTrue(service.onTerminationResponse(closed(0))); stopped(1); }
    @Test public void fileTransferTerminatedBetweenDAndIStopsAndRetainsMarker() throws Exception { begin(); complete(); service.onTermination(new SapServiceConnectionTermination(21, integer("ftComponent"), GearContactsService.FILE_TRANSFER)); stopped(1); }
    @Test public void disconnectedDuringDStopsAndRetainsMarker() throws Exception { begin(); command("filetransfer-setup-rsp", 0); service.dispose(); stopped(1); }
    @Test public void rejectedDSetupStops() throws Exception { begin(); command("filetransfer-setup-rsp", 1); stoppedBeforePayload(1); }
    @Test public void failedDPayloadStops() throws Exception { begin(); failSend = "payload"; command("filetransfer-setup-rsp", 0); stopped(1); }
    @Test public void failedISetupStops() throws Exception { begin(); complete(); fresh(); command("filetransfer-setup-rsp", 1); stopped(2); }
    @Test public void failedIPayloadStops() throws Exception { begin(); complete(); fresh(); failSend = "payload"; command("filetransfer-setup-rsp", 0); stopped(2); }
    @Test public void silentIPayloadTimeoutStops() throws Exception { begin(); complete(); fresh(); command("filetransfer-setup-rsp", 0); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10)); stopped(2); }
    @Test public void iSetupSendFailureStops() throws Exception { begin(); complete(); assertTrue(service.onTerminationResponse(closed(0))); failSend = "setup"; assertTrue(service.acceptResponse(opened(0))); stopped(1); }
    @Test public void receiverCancellationStops() throws Exception { begin(); command("filetransfer-cancel-req", 0); stoppedBeforePayload(1); }

    private void beginWithoutFt() throws Exception {
        provider.rows.put(1L, CURRENT); create();
        set("ftCommandSession", -1); set("ftDataSession", -1); resume();
    }

    @Test public void initialFtRejectionStopsBeforeD() throws Exception { beginWithoutFt(); assertTrue(service.acceptResponse(opened(1))); stoppedBeforePayload(0); }
    @Test public void initialFtTimeoutStopsBeforeD() throws Exception { beginWithoutFt(); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10)); stoppedBeforePayload(0); }
    @Test public void initialFtRequestFailureStopsBeforeD() throws Exception { failSend = "open"; beginWithoutFt(); stoppedBeforePayload(0); }
    @Test public void missingFtDescriptorStopsBeforeD() throws Exception {
        provider.rows.put(1L, CURRENT); create(); set("ftCommandSession", -1); set("remoteFt", -1); resume(); stoppedBeforePayload(0);
    }
    @Test public void disconnectDuringIPayloadRetainsOldBaselineAndStops() throws Exception {
        begin(); complete(); fresh(); command("filetransfer-setup-rsp", 0); service.dispose(); stopped(2);
    }

    private void stopped(int expectedSetups) throws Exception {
        assertTrue(files().isEmpty()); assertNull(get(service, "pendingDelta"));
        assertEquals(expectedSetups, setups()); unchangedUncertain();
        assertFalse((Boolean) get(service, "pendingReplacement"));
        assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().hasPendingVerification());
    }
    private void stoppedBeforePayload(int expectedSetups) throws Exception {
        assertTrue(files().isEmpty()); assertNull(get(service, "pendingDelta"));
        assertEquals(expectedSetups, setups()); unchangedUncertain();
        assertFalse((Boolean) get(service, "pendingReplacement"));
        assertFalse(snapshot().isBlocked(1)); assertFalse(snapshot().hasPendingVerification());
        assertEquals("NONE", snapshot().incompleteStage(1));
    }

    @Test public void sapAloneCannotStartProductionModify() throws Exception {
        provider.rows.put(1L, CURRENT); create(); invoke("scheduleScan", boolean.class, false); drain();
        assertEquals(0, provider.scans); assertEquals(0, setups()); assertEquals(original, prefs.getString(SNAPSHOT, null));
        resume(); assertEquals(1, setups()); unchangedUncertain();
    }

    @Test public void observerCallbacksDebounceToOnePromptReplacement() throws Exception {
        create(); resume(); assertEquals(0, setups());
        provider.rows.put(1L, CURRENT);
        final android.database.ContentObserver observer = (android.database.ContentObserver) get(service, "observer");
        for (int i = 0; i < 5; i++) observer.onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(349)); assertEquals(0, setups());
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(1)); drain();
        assertEquals(1, setups()); assertEquals(2, provider.scans);
    }

    @Test public void unchangedResumeSendsNothingAndKeepsUsableBaseline() throws Exception {
        create(); resume(); resume(); assertEquals(0, setups()); assertTrue(files().isEmpty());
        assertEquals(original, prefs.getString(SNAPSHOT, null)); assertFalse((Boolean) get(service, "initialPending"));
    }

    @Test public void legacyDivergentBaselinePermitsProductionModifyWithoutClearingUncertainty() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(1L, CURRENT); create(); resume();
        assertEquals(1, setups()); assertEquals(1, provider.scans); assertEquals(2, files().size());
        assertTrue(snapshot().hasPendingVerification()); assertFalse((Boolean) get(service, "pendingDebugOneShot"));
    }

    @Test public void genuineDeletionIsDeferredWhileOnlyChangedIdIsReplaced() throws Exception {
        provider.rows.remove(2L); begin();
        for (Object file : files()) {
            final JSONObject json = decode(file);
            final String kind = json.has("delete") ? "delete" : "insert";
            assertEquals(1, json.getJSONObject(kind).getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        }
    }

    @Test public void knownBaselineDeletionAtResumeMarksOnlyThatIdUntilTransportCompletes() throws Exception {
        provider.rows.remove(2L); create(); resume(); assertEquals(1, setups());
        assertFalse(snapshot().isBlocked(2)); command("filetransfer-setup-rsp", 0);
        assertTrue(snapshot().isBlocked(2)); assertFalse(snapshot().isTransportDeleted(2));
        complete(); assertTrue(snapshot().isTransportDeleted(2)); assertFalse(snapshot().isBlocked(2));
        assertEquals(original, prefs.getString(SNAPSHOT, null));
    }

    @Test public void connectedAddCheckpointsAndUnchangedReconnectDoesNotResend() throws Exception {
        create(); resume(); provider.rows.put(3L, OLD); invoke("scheduleScan", boolean.class, false); drain();
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert")); complete();
        assertEquals(3, snapshot().size()); assertFalse(snapshot().hasPendingVerification());
        service.dispose(); debug.close(); create(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }

    @Test public void offlineAddReconcilesOnResume() throws Exception {
        provider.rows.put(3L, OLD); create(); resume(); assertEquals(1, setups()); complete();
        assertEquals(3, snapshot().size()); assertFalse(snapshot().hasPendingVerification());
    }

    @Test public void mixedAddAndModifyCheckpointsAddBeforeReplacingExistingId() throws Exception {
        provider.rows.put(3L, OLD); provider.rows.put(1L, CURRENT); create(); resume();
        assertEquals(1, files().size());
        assertEquals(3, decode(files().peek()).getJSONObject("insert").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); drain();
        assertEquals(3, snapshot().size()); assertEquals(2, files().size());
        assertEquals(1, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertFalse(snapshot().hasPendingVerification());
    }

    @Test public void multipleModifiedIdsStayAsSingleIdPairsWithFreshBoundaries() throws Exception {
        provider.rows.put(1L, CURRENT); provider.rows.put(2L, CURRENT); create(); resume();
        assertEquals(4, files().size());
        final Object[] files = files().toArray();
        for (int i = 0; i < files.length; i++) {
            final String kind = i % 2 == 0 ? "delete" : "insert";
            assertEquals(i / 2 + 1, decode(files[i]).getJSONObject(kind).getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        }
        complete(); fresh(); complete(); assertEquals(2, setups()); // No same-session I -> next D either.
        fresh(); complete(); fresh(); complete();
        assertEquals(4, setups()); assertTrue(files().isEmpty());
        assertEquals(original, prefs.getString(SNAPSHOT, null));
        assertTrue(snapshot().isTransportUncertain(1)); assertTrue(snapshot().isTransportUncertain(2));
    }

    @Test public void missingBaselineResumeCannotTriggerWatchResetWithoutConsent() throws Exception {
        prefs.edit().clear().commit(); create();
        final Map<String, ?> before = prefs.getAll();
        resume(); resume();
        assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty());
        assertEquals(before, prefs.getAll()); assertFalse(snapshot().isInitialized());
    }

    @Test public void missingBaselineUnsolicitedInitialRequiresConsent() throws Exception {
        prefs.edit().clear().commit(); create();
        final Map<String, ?> before = prefs.getAll();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty()); assertEquals(0, setups());
        assertEquals(before, prefs.getAll()); assertFalse(snapshot().isInitialized());
    }

    @Test public void firstSyncConsentIsConsumedBeforeInitialRequiredAndNotRestoredByProcessRestart() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); requireConsentConsumed = true; create(); resume();
        assertFalse(prefs.getBoolean(GearContactsService.ALLOW_FIRST_SYNC, false));
        assertEquals("Response_Initial_Sync_Required_Contact", new String(GearContactsCodec.payload(emitted.get(emitted.size()-1).getBody()), StandardCharsets.UTF_8));
        service.dispose(); debug.close(); emitted.clear(); create(); resume();
        assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty());
    }

    @Test public void firstSyncConsentCannotResetRetainedBaseline() throws Exception {
        prefs.edit().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); create(); resume();
        emitted.clear();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty());
        assertEquals(original, prefs.getString(SNAPSHOT, null));
    }

    @Test public void firstSyncConsentDoesNotAuthorizeAnotherControlGeneration() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); create(); resume();
        set("controlGeneration", 1L); emitted.clear(); resume();
        assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty());
    }

    @Test public void repeatedResumeDuringAuthorizedInitialCannotRequestAnotherWatchReset() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); create(); resume();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        final int count = emitted.size(); final Object file = files().peek();
        resume(); assertEquals(count, emitted.size()); assertSame(file, files().peek());
        assertEquals(1, setups());
    }

    @Test public void explicitInitialStillUsesOnlyIAndCheckpointsAtFinalDelivery() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit(); // Fresh synthetic device: no retained transport state.
        provider.rows.put(1L, CURRENT); create();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertTrue((Boolean) get(service, "pendingInitial")); assertFalse((Boolean) get(service, "pendingReplacement"));
        assertTrue(decode(files().peek()).has("insert")); assertFalse(snapshot().isInitialized());
        complete(); assertTrue(snapshot().isInitialized()); assertFalse(snapshot().hasPendingVerification());
        assertTrue(snapshot().compare(GearContactsSource.read(context), false).isEmpty());
    }

    @Test public void initialRequestWithRetainedBaselineCannotReplayOrResetLedger() throws Exception {
        create();
        final GearContactsSnapshot retained = snapshot();
        assertTrue(retained.beginTransport(Collections.singleton(3L)));
        assertTrue(retained.recordTransportDelivered(Collections.singletonMap(3L, GearContactsSnapshot.fingerprint(contact(3, CURRENT)))));
        service.persistentTestStateChanged();
        final String ledger = prefs.getString("samsung_gear_contacts_transport_ledger_v1", null);
        invoke("onContactMessage", byte[].class, "crypt_Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertEquals(0, setups());
        assertTrue(files().isEmpty());
        assertFalse((Boolean) get(service, "initialPending"));
        assertEquals(original, prefs.getString(SNAPSHOT, null));
        assertEquals(ledger, prefs.getString("samsung_gear_contacts_transport_ledger_v1", null));
        assertTrue(snapshot().isInitialized());
    }

    @Test public void repeatedInitialCannotResetAnActiveFreshImportOrEnqueueAnotherFile() throws Exception {
        prefs.edit().clear().putBoolean(GearContactsService.ALLOW_FIRST_SYNC, true).commit();
        create();
        invoke("onContactMessage", byte[].class, "crypt_Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        final Object file = files().peek();
        final long readyGeneration = (long) get(service, "readyControlGeneration");
        invoke("onContactMessage", byte[].class, "crypt_Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertSame(file, files().peek());
        assertEquals(1, setups());
        assertEquals(readyGeneration, get(service, "readyControlGeneration"));
        complete();
        assertTrue(snapshot().isInitialized());
        invoke("onContactMessage", byte[].class, "crypt_Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertEquals(1, setups());
        assertTrue(snapshot().isInitialized());
    }

    @Test public void explicitInitialDuringReplacementCannotClearUncertaintyOrReimport() throws Exception {
        begin(); complete();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8));
        stopped(1); assertFalse(service.onTerminationResponse(closed(0)));
    }

    private static final long TEST_RAW = 9120;
    private static final String TEST_ADDRESS = "00:11:22:33:44:55";

    private Map<String, ?> prepareTest() throws Exception {
        assertTrue(snapshot().markPendingVerification());
        provider.rows.put(TEST_RAW, OLD);
        create(); debug.register(context, TEST_ADDRESS);
        final Map<String, ?> retained = new LinkedHashMap<>(prefs.getAll());
        assertTrue(debug.prepareProductionTest(TEST_RAW)); awaitPreparation();
        assertNotNull(debug.productionTestBaseline());
        assertEquals(retained, prefs.getAll());
        return retained;
    }

    private void awaitPreparation() throws Exception {
        ((ExecutorService) get(debug, "preparer")).submit(() -> {}).get(5, TimeUnit.SECONDS); idle();
    }

    @Test public void prepareCapturesExactProductionFingerprintWithoutFilesOrTransmission() throws Exception {
        prepareTest();
        final GearContactsDebugRequests.ProductionTestBaseline baseline = debug.productionTestBaseline();
        assertEquals(TEST_RAW, baseline.rawId); assertEquals(1, baseline.snapshot.size());
        assertTrue(baseline.snapshot.compare(Collections.singletonList(contact(TEST_RAW, OLD)), false).isEmpty());
        assertEquals(1, baseline.snapshot.compare(Collections.singletonList(contact(TEST_RAW, CURRENT)), false).update.size());
        assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty()); assertNull(get(service, "pendingDelta"));
        assertTrue(snapshot().hasPendingVerification());
    }

    @Test public void unchangedTestResumeSendsNothingAndRetainsOverride() throws Exception {
        final Map<String, ?> retained = prepareTest(); resume(); resume();
        assertEquals(0, setups()); assertTrue(files().isEmpty()); assertTrue(debug.hasProductionTest());
        assertEquals(retained, prefs.getAll());
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("production-test baseline unchanged: rawId=9120")));
    }

    @Test public void changedTestEntryUsesRealProductionModifyAndExcludesEveryUnrelatedDelta() throws Exception {
        final Map<String, ?> retained = prepareTest();
        provider.rows.put(TEST_RAW, CURRENT); provider.rows.put(1L, CURRENT);
        provider.rows.remove(2L); provider.rows.put(3L, OLD);
        resume();
        assertEquals(2, files().size()); final Object[] pair = files().toArray();
        assertEquals(TEST_RAW, decode(pair[0]).getJSONObject("delete").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertEquals(GearContactsCodec.change("insert", Collections.singletonList(contact(TEST_RAW, CURRENT)),
                Collections.emptyList()).toString(), decode(pair[1]).toString());
        assertTrue((Boolean) get(service, "pendingReplacement"));
        assertFalse((Boolean) get(service, "pendingDebugOneShot")); assertFalse((Boolean) get(service, "pendingInitial"));
        assertTrue(debug.hasProductionTest()); assertEquals(retained, prefs.getAll());
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("production MODIFY selected: rawId=9120")));
        assertFalse(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("debug one-shot selected")));
    }

    @Test public void testModifyRequiresSuccessfulTerminationAndFreshSapAndNeverChangesPersistedBytes() throws Exception {
        final Map<String, ?> retained = prepareTest(); provider.rows.put(TEST_RAW, CURRENT); resume();
        complete(); assertEquals(1, setups()); assertNull(get(service, "sending"));
        assertTrue(service.onTerminationResponse(closed(0)));
        assertEquals(1, setups()); assertNull(get(service, "sending"));
        assertTrue(service.acceptResponse(opened(0))); assertEquals(2, setups());
        command("filetransfer-setup-rsp", 0); complete();
        assertTrue(files().isEmpty()); assertTrue(debug.hasProductionTest());
        assertEquals(retained, prefs.getAll()); assertTrue(snapshot().hasPendingVerification());
        resume(); assertEquals(2, setups()); assertTrue(files().isEmpty());
    }

    @Test public void testObserverAndSapCannotAuthorizeModifyEvenAfterUnchangedResume() throws Exception {
        final Map<String, ?> retained = prepareTest(); resume(); provider.rows.put(TEST_RAW, CURRENT);
        final android.database.ContentObserver observer = (android.database.ContentObserver) get(service, "observer");
        for (int i = 0; i < 5; i++) observer.onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(350)); drain();
        invoke("scheduleScan", boolean.class, false); drain();
        assertEquals(0, setups()); assertEquals(2, provider.scans); // PREPARE plus the unchanged Resume only.
        assertEquals(retained, prefs.getAll()); assertTrue(debug.hasProductionTest());
        resume(); assertEquals(1, setups());
    }

    @Test public void preparedTestSurvivesAddressRegistryAndServiceRecreationButIsDeviceIsolated() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD);
        final Map<String, ?> retained = new LinkedHashMap<>(prefs.getAll());
        create(GearContactsDebugRequests.forDevice(TEST_ADDRESS)); debug.register(context, TEST_ADDRESS);
        assertTrue(debug.prepareProductionTest(TEST_RAW)); awaitPreparation();
        final GearContactsDebugRequests controller = debug;
        service.dispose(); create(GearContactsDebugRequests.forDevice(TEST_ADDRESS.toLowerCase(java.util.Locale.ROOT)));
        assertSame(controller, debug); assertNotNull(debug.productionTestBaseline());
        final GearContactsDebugRequests other = GearContactsDebugRequests.forDevice("66:77:88:99:AA:BB");
        try { assertNotSame(debug, other); assertFalse(other.hasProductionTest()); }
        finally { other.close(); }
        provider.rows.put(TEST_RAW, CURRENT); resume();
        assertEquals(1, setups()); assertEquals(retained, prefs.getAll());
    }

    @Test public void prepareBroadcastRequiresMatchingAddressAndCapturesWhileSapAbsent() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD);
        create(); set("contactsSession", -1); debug.register(context, TEST_ADDRESS);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.DUMP);
        final Intent intent = new Intent(GearContactsService.DEBUG_PREPARE_PRODUCTION_MODIFY_RAW_ACTION)
                .setPackage(context.getPackageName()).putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, TEST_RAW)
                .putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, "66:77:88:99:AA:BB");
        context.sendBroadcast(intent); idle(); assertFalse(debug.hasProductionTest());
        intent.putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, TEST_ADDRESS);
        context.sendBroadcast(intent); idle(); awaitPreparation();
        assertNotNull(debug.productionTestBaseline()); assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty());
        provider.rows.put(TEST_RAW, CURRENT); set("contactsSession", 9); resume(); assertEquals(1, setups());
    }

    @Test public void prepareCanCaptureWithoutContactsServiceAndWaitForLaterRealResume() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD);
        debug = new GearContactsDebugRequests(); debug.register(context, TEST_ADDRESS);
        assertTrue(debug.prepareProductionTest(TEST_RAW)); awaitPreparation(); assertTrue(emitted.isEmpty());
        create(debug); provider.rows.put(TEST_RAW, CURRENT);
        invoke("scheduleScan", boolean.class, false); drain(); assertEquals(0, setups());
        resume(); assertEquals(1, setups());
    }

    @Test public void duplicatePrepareCannotReseedAndClearNeedsSameRawId() throws Exception {
        final Map<String, ?> retained = prepareTest(); provider.rows.put(TEST_RAW, CURRENT);
        assertFalse(debug.prepareProductionTest(TEST_RAW));
        assertFalse(debug.clearProductionTest(TEST_RAW + 1));
        assertEquals(1, debug.productionTestBaseline().snapshot.compare(Collections.singletonList(contact(TEST_RAW, CURRENT)), false).update.size());
        assertTrue(debug.clearProductionTest(TEST_RAW)); resume();
        assertEquals(0, setups()); assertEquals(retained, prefs.getAll());
    }

    @Test public void testOverrideAndExistingDebugTrialsAreMutuallyExclusive() throws Exception {
        prepareTest();
        assertFalse(debug.request(GearContactsDebugRequests.Mode.INSERT, TEST_RAW));
        assertFalse(debug.request(GearContactsDebugRequests.Mode.REPLACE, TEST_RAW));
        assertTrue(debug.clearProductionTest(TEST_RAW));
        assertTrue(debug.request(GearContactsDebugRequests.Mode.INSERT, TEST_RAW));
        assertFalse(debug.prepareProductionTest(TEST_RAW));
    }

    @Test public void missingExactRawCannotPrepareOrSendUnrelatedContacts() throws Exception {
        assertTrue(snapshot().markPendingVerification()); create(); debug.register(context, TEST_ADDRESS);
        assertTrue(debug.prepareProductionTest(TEST_RAW)); awaitPreparation();
        assertFalse(debug.hasProductionTest()); assertTrue(emitted.isEmpty()); assertTrue(files().isEmpty());
    }

    @Test public void targetDeletionClearsOverrideWithoutSendingDelete() throws Exception {
        final Map<String, ?> retained = prepareTest(); provider.rows.remove(TEST_RAW); resume();
        assertFalse(debug.hasProductionTest()); assertEquals(0, setups()); assertEquals(retained, prefs.getAll());
    }

    @Test public void interruptedTestAttemptRemainsBlockedAndNeverReplayedAfterRecreation() throws Exception {
        final Map<String, ?> retained = prepareTest(); provider.rows.put(TEST_RAW, CURRENT); resume(); complete();
        assertTrue(debug.hasProductionTest()); final GearContactsDebugRequests controller = debug;
        service.dispose(); create(controller); resume();
        assertEquals(1, setups()); assertTrue(files().isEmpty()); assertEquals(retained, prefs.getAll());
    }

    @Test public void initialControlCannotConvertPreparedTestIntoFullImport() throws Exception {
        final Map<String, ?> retained = prepareTest();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertEquals(0, setups()); assertTrue(files().isEmpty()); assertTrue(debug.hasProductionTest());
        assertEquals(retained, prefs.getAll());
    }

    @Test public void clearingDuringPrepareCannotResurrectOverrideAndControllerCloseClearsProcessState() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD);
        create(); debug.register(context, TEST_ADDRESS);
        assertTrue(debug.prepareProductionTest(TEST_RAW)); assertTrue(debug.clearProductionTest(TEST_RAW));
        awaitPreparation(); assertFalse(debug.hasProductionTest());
        assertTrue(debug.prepareProductionTest(TEST_RAW)); awaitPreparation(); assertTrue(debug.hasProductionTest());
        debug.close(); assertFalse(debug.hasProductionTest());
        service.dispose(); create(); debug.register(context, TEST_ADDRESS); resume();
        assertFalse(debug.hasProductionTest()); assertEquals(1, setups()); assertTrue(decode(files().peek()).has("insert"));
    }

    @Test public void prepareRejectsUnpausedRealBaselineAndDoesNotLoosenProductionRules() throws Exception {
        create(); debug.register(context, TEST_ADDRESS);
        assertFalse(debug.prepareProductionTest(1)); assertFalse(debug.hasProductionTest());
        assertEquals(original, prefs.getString(SNAPSHOT, null)); assertEquals(0, setups());
    }

    @Test public void laterEditOfDeliveredIdRunsAnotherProductionModifyAndReplacesHash() throws Exception {
        begin(); complete(); fresh(); complete();
        final String newest = "+15550003003";
        provider.rows.put(1L, newest); resume();
        assertEquals(2, files().size()); assertEquals(3, setups());
        assertEquals(GearContactsCodec.change("insert", Collections.singletonList(contact(1, newest)), Collections.emptyList()).toString(), decode(files().toArray()[1]).toString());
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
        complete(); fresh(); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, newest)), snapshot().lastTransportDeliveredFingerprint(1));
        assertTrue(snapshot().isTransportUncertain(1)); assertFalse(snapshot().isBlocked(1));
        resume(); assertEquals(4, setups()); assertTrue(files().isEmpty());
    }

    @Test public void changesDuringTransferCheckpointSentHashThenScanSchedulesNewerRepresentation() throws Exception {
        begin(); complete(); fresh();
        provider.rows.put(1L, "+15550003003");
        invoke("scheduleScan", boolean.class, false); // Busy: coalesce a scan after this exact I.
        complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
        drain(); assertEquals(2, files().size()); assertEquals(3, setups());
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, "+15550003003")),
                ((Map<?, ?>) get(files().toArray()[1], "sentHashes")).get(1L));
    }

    @Test public void uncertainDeliveredIdDoesNotBlockUnrelatedInsertOrModify() throws Exception {
        begin(); complete(); fresh(); complete();
        provider.rows.put(3L, CURRENT); provider.rows.put(2L, CURRENT); resume();
        assertEquals(1, files().size()); complete(); drain();
        assertTrue(snapshot().isTransportUncertain(3)); assertEquals(2, files().size());
        assertEquals(2, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); fresh(); complete();
        assertTrue(snapshot().isTransportUncertain(2)); assertFalse(snapshot().hasPendingVerification());
        assertTrue(snapshot().compare(GearContactsSource.read(context), false).isEmpty());
    }

    @Test public void interruptedIdIsSkippedAfterRecreationButUnrelatedChangesStillRun() throws Exception {
        begin(); failSend = "payload"; command("filetransfer-setup-rsp", 0);
        service.dispose(); debug.close(); provider.rows.put(2L, CURRENT); provider.rows.put(3L, CURRENT);
        create(); resume(); complete(); drain();
        assertTrue(snapshot().isBlocked(1)); assertTrue(snapshot().isTransportUncertain(3));
        assertEquals(2, files().size());
        assertEquals(2, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); fresh(); complete(); assertTrue(snapshot().isTransportUncertain(2));
        resume(); assertTrue(files().isEmpty()); assertFalse(snapshot().hasPendingVerification());
    }

    @Test public void firstPairCheckpointsEvenWhenSecondPairFails() throws Exception {
        provider.rows.put(1L, CURRENT); provider.rows.put(2L, CURRENT); create(); resume();
        assertFalse(snapshot().isBlocked(1)); command("filetransfer-setup-rsp", 0);
        assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().isBlocked(2));
        complete(); fresh(); complete();
        assertTrue(snapshot().isTransportUncertain(1)); assertFalse(snapshot().isBlocked(1)); assertFalse(snapshot().isBlocked(2));
        fresh(); assertFalse(snapshot().isBlocked(2)); failSend = "payload"; command("filetransfer-setup-rsp", 0); assertTrue(snapshot().isBlocked(2));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(2, OLD)), snapshot().lastTransportDeliveredFingerprint(2));
        assertFalse(snapshot().hasPendingVerification());
    }

    @Test public void completedInsertUsesExactSentFingerprintAndLaterEditUsesModify() throws Exception {
        provider.rows.put(3L, OLD); create(); resume(); provider.rows.put(3L, CURRENT); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
        assertTrue(snapshot().isTransportUncertain(3)); resume();
        assertEquals(2, files().size());
        assertEquals(3, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); fresh(); complete(); resume(); assertTrue(files().isEmpty()); assertEquals(3, setups());
    }

    @Test public void insertFailureBlocksOnlyAttemptedIdsAndPreservesPreviousHashes() throws Exception {
        provider.rows.put(3L, OLD); create(); resume(); failSend = "payload"; command("filetransfer-setup-rsp", 0);
        assertNull(snapshot().lastTransportDeliveredFingerprint(3)); assertTrue(snapshot().isBlocked(3));
        assertFalse(snapshot().hasPendingVerification());
        provider.rows.put(1L, CURRENT); resume(); assertEquals(2, files().size());
    }

    @Test public void unacceptedICompletionCannotCheckpoint() throws Exception {
        begin(); complete(); fresh();
        final Object file = files().peek(); final Field offset = file.getClass().getDeclaredField("offset"); offset.setAccessible(true);
        offset.set(file, ((byte[]) get(file, "bytes")).length); command("filetransfer-complete-req", 0);
        stopped(2); assertFalse(snapshot().isTransportUncertain(1));
    }

    @Test public void blockedIncompleteTransferCannotBeConvertedToInitialReimportAfterRecreation() throws Exception {
        begin(); complete(); service.dispose(); debug.close(); create();
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertTrue(snapshot().isInitialized()); assertTrue(snapshot().isBlocked(1)); assertTrue(files().isEmpty()); assertEquals(1, setups());
    }

    @Test public void productionTestLedgerSupportsUnchangedResumeAndRepeatedEditsWithoutTouchingLegacyState() throws Exception {
        final Map<String, ?> retained = prepareTest(); provider.rows.put(TEST_RAW, CURRENT); resume(); complete(); fresh(); complete();
        final GearContactsSnapshot local = debug.productionTestBaseline().snapshot;
        assertTrue(local.isTransportUncertain(TEST_RAW)); assertFalse(local.isBlocked(TEST_RAW));
        resume(); assertEquals(2, setups());
        provider.rows.put(TEST_RAW, "+15550003003"); provider.rows.put(1L, CURRENT); resume();
        assertEquals(2, files().size()); complete(); fresh(); complete(); resume(); assertEquals(4, setups());
        assertEquals(GearContactsSnapshot.fingerprint(contact(TEST_RAW, "+15550003003")), local.lastTransportDeliveredFingerprint(TEST_RAW));
        assertEquals(retained, prefs.getAll()); assertTrue(snapshot().hasPendingVerification()); assertTrue(debug.hasProductionTest());
        assertTrue(debug.clearProductionTest(TEST_RAW)); resume(); assertEquals(5, setups());
        assertEquals(2, files().size()); // The unrelated edited legacy ID now converges; the trial ID is not replayed.
        assertEquals(1, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
    }

    private static final String LEDGER = "samsung_gear_contacts_transport_ledger_v1";

    private String prepareDurableTest() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD); create();
        debug.register(context, TEST_ADDRESS, prefs);
        final String legacy = prefs.getString(SNAPSHOT, null);
        assertTrue(debug.preparePersistentTest(TEST_RAW)); awaitPreparation();
        assertTrue(debug.hasPersistentTest()); assertFalse(debug.hasProductionTest()); assertTrue(emitted.isEmpty());
        assertEquals(legacy, prefs.getString(SNAPSHOT, null)); return legacy;
    }

    private void recreateDurableService() throws Exception {
        final GearContactsDebugRequests old = debug; service.dispose(); old.close();
        create(new GearContactsDebugRequests()); debug.register(context, TEST_ADDRESS, prefs);
        assertNotSame(old, debug); assertNull(debug.productionTestBaseline());
    }

    @Test public void persistentPrepareWritesRealLedgerWithoutFilesAndPreservesLegacyPause() throws Exception {
        final String legacy = prepareDurableTest();
        assertEquals(GearContactsSnapshot.fingerprint(contact(TEST_RAW, OLD)), snapshot().lastTransportDeliveredFingerprint(TEST_RAW));
        assertEquals(TEST_RAW, new JSONObject(prefs.getString(LEDGER, null)).getLong("debugModifyTestRawId"));
        assertTrue(snapshot().hasPendingVerification()); assertTrue(files().isEmpty()); assertNull(get(service, "pendingDelta"));
        assertEquals(legacy, prefs.getString(SNAPSHOT, null));
        assertFalse(prefs.getAll().toString().contains("Synthetic")); assertFalse(prefs.getAll().toString().contains("+1555"));
    }

    @Test public void persistentLedgerSurvivesNewControllersAndTwoStoreRecreationsWithNoResends() throws Exception {
        final String legacy = prepareDurableTest(); recreateDurableService(); resume(); assertEquals(0, setups());
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("transport ledger restored: rawId=9120; transportUncertain=true; key=samsung_gear_contacts_transport_ledger_v1")));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("persistent transport ledger unchanged: rawId=9120; no files sent")));
        provider.rows.put(TEST_RAW, CURRENT); provider.rows.put(1L, CURRENT); provider.rows.remove(2L); provider.rows.put(3L, CURRENT);
        resume(); assertEquals(2, files().size()); assertFalse((Boolean) get(service, "pendingDebugOneShot"));
        assertEquals(TEST_RAW, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); fresh(); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(TEST_RAW, CURRENT)), snapshot().lastTransportDeliveredFingerprint(TEST_RAW));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("persistent transport-delivered checkpoint: rawId=9120")));
        recreateDurableService(); resume(); assertEquals(2, setups()); assertTrue(files().isEmpty());
        provider.rows.put(TEST_RAW, "+15550003003"); resume(); complete(); fresh(); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(TEST_RAW, "+15550003003")), snapshot().lastTransportDeliveredFingerprint(TEST_RAW));
        recreateDurableService(); resume(); assertEquals(4, setups()); assertTrue(files().isEmpty());
        assertEquals(legacy, prefs.getString(SNAPSHOT, null)); assertTrue(snapshot().hasPendingVerification());
        assertFalse(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("debug one-shot selected")));
    }

    @Test public void persistentCheckpointUsesExactSentIWhenPhoneChangesDuringTransfer() throws Exception {
        prepareDurableTest(); provider.rows.put(TEST_RAW, CURRENT); resume(); complete(); fresh();
        provider.rows.put(TEST_RAW, "+15550003003"); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(TEST_RAW, CURRENT)), snapshot().lastTransportDeliveredFingerprint(TEST_RAW));
        recreateDurableService(); resume(); assertEquals(2, files().size()); assertEquals(3, setups());
    }

    @Test public void interruptedPersistentReplacementRetainsOldHashAcrossProcessStyleRecreation() throws Exception {
        final String legacy = prepareDurableTest(); provider.rows.put(TEST_RAW, CURRENT); resume(); complete();
        assertFalse(debug.clearPersistentTest(TEST_RAW)); // Active close boundary cannot be cleared/reseeded.
        recreateDurableService(); resume();
        assertEquals(1, setups()); assertTrue(files().isEmpty()); assertTrue(snapshot().isBlocked(TEST_RAW));
        assertEquals(GearContactsSnapshot.fingerprint(contact(TEST_RAW, OLD)), snapshot().lastTransportDeliveredFingerprint(TEST_RAW));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("persistent transport ledger blocked: rawId=9120")));
        assertEquals(legacy, prefs.getString(SNAPSHOT, null)); assertFalse(debug.preparePersistentTest(TEST_RAW));
    }

    @Test public void persistentTrialRequiresNewResumeNotObserverOrSapAndInitialCannotImport() throws Exception {
        prepareDurableTest(); resume(); provider.rows.put(TEST_RAW, CURRENT);
        final android.database.ContentObserver observer = (android.database.ContentObserver) get(service, "observer");
        observer.onChange(false); ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(350)); drain();
        invoke("scheduleScan", boolean.class, false); drain(); assertEquals(0, setups());
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertEquals(0, setups()); assertTrue(snapshot().hasPendingVerification()); assertTrue(snapshot().isInitialized());
        resume(); assertEquals(1, setups());
    }

    @Test public void persistentClearOnlyRemovesScopeThenResumeConvergesNormally() throws Exception {
        final String legacy = prepareDurableTest(); final String captured = snapshot().lastTransportDeliveredFingerprint(TEST_RAW);
        assertFalse(debug.clearPersistentTest(1)); assertTrue(debug.clearPersistentTest(TEST_RAW));
        assertEquals(captured, snapshot().lastTransportDeliveredFingerprint(TEST_RAW)); assertEquals(0, snapshot().persistentTestRawId());
        assertFalse(new JSONObject(prefs.getString(LEDGER, null)).has("debugModifyTestRawId"));
        provider.rows.put(TEST_RAW, CURRENT); provider.rows.put(1L, CURRENT); recreateDurableService(); resume();
        assertEquals(1, setups()); assertEquals(4, files().size()); assertEquals(legacy, prefs.getString(SNAPSHOT, null));
    }

    @Test public void persistentAndProcessLocalPrepareAndExplicitDebugTrialsAreMutuallyExclusive() throws Exception {
        prepareDurableTest();
        assertFalse(debug.preparePersistentTest(TEST_RAW)); assertFalse(debug.prepareProductionTest(TEST_RAW));
        assertFalse(debug.request(GearContactsDebugRequests.Mode.INSERT, TEST_RAW));
        assertFalse(debug.request(GearContactsDebugRequests.Mode.REPLACE, TEST_RAW));
        assertFalse(debug.request(GearContactsDebugRequests.Mode.DELETES, -1));
        assertTrue(debug.clearPersistentTest(TEST_RAW)); assertTrue(debug.prepareProductionTest(TEST_RAW)); awaitPreparation();
        assertFalse(debug.preparePersistentTest(TEST_RAW)); assertTrue(debug.hasProductionTest());
    }

    @Test public void persistentPrepareRequiresExactDisposableRowAndCannotWidenScope() throws Exception {
        assertTrue(snapshot().markPendingVerification()); create(); debug.register(context, TEST_ADDRESS, prefs);
        assertFalse(debug.preparePersistentTest(0)); assertTrue(debug.preparePersistentTest(TEST_RAW)); awaitPreparation();
        assertFalse(debug.hasPersistentTest()); assertNull(prefs.getString(LEDGER, null)); assertTrue(emitted.isEmpty());
    }

    @Test public void persistentTargetDisappearanceDoesNotDeleteOrEnableOtherIds() throws Exception {
        final String legacy = prepareDurableTest(); provider.rows.remove(TEST_RAW); provider.rows.put(1L, CURRENT); resume();
        assertEquals(0, setups()); assertTrue(debug.hasPersistentTest()); assertEquals(legacy, prefs.getString(SNAPSHOT, null));
        assertTrue(snapshot().isTransportUncertain(TEST_RAW));
    }

    @Test public void persistentPrepareAndClearBroadcastsEnforceAddressAndPermissionGatedReceiver() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD); create(); debug.register(context, TEST_ADDRESS, prefs);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.DUMP);
        final Intent intent = new Intent(GearContactsService.DEBUG_PREPARE_PERSISTENT_MODIFY_RAW_ACTION).setPackage(context.getPackageName())
                .putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, TEST_RAW).putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, "66:77:88:99:AA:BB");
        context.sendBroadcast(intent); idle(); assertFalse(debug.hasPersistentTest());
        intent.putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, TEST_ADDRESS); context.sendBroadcast(intent); idle(); awaitPreparation();
        assertTrue(debug.hasPersistentTest()); assertTrue(emitted.isEmpty());
        intent.setAction(GearContactsService.DEBUG_CLEAR_PERSISTENT_MODIFY_RAW_ACTION).putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, "66:77:88:99:AA:BB");
        context.sendBroadcast(intent); idle(); assertTrue(debug.hasPersistentTest());
        intent.putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, TEST_ADDRESS); context.sendBroadcast(intent); idle(); assertFalse(debug.hasPersistentTest());
    }

    @Test public void persistentPreparationCanCommitWhileDeviceSupportAndSapAreAbsent() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD);
        debug = new GearContactsDebugRequests(); debug.register(context, TEST_ADDRESS, prefs);
        assertTrue(debug.preparePersistentTest(TEST_RAW)); awaitPreparation(); assertTrue(debug.hasPersistentTest());
        final GearContactsDebugRequests old = debug; old.close(); create(new GearContactsDebugRequests()); debug.register(context, TEST_ADDRESS, prefs);
        assertNotSame(old, debug); resume(); assertEquals(0, setups());
        provider.rows.put(TEST_RAW, CURRENT); resume(); assertEquals(1, setups());
    }

    @Test public void clearDuringPersistentPreparationCannotResurrectDurableScope() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(TEST_RAW, OLD); create(); debug.register(context, TEST_ADDRESS, prefs);
        assertTrue(debug.preparePersistentTest(TEST_RAW)); assertTrue(debug.clearPersistentTest(TEST_RAW)); awaitPreparation();
        assertFalse(debug.hasPersistentTest()); assertNull(prefs.getString(LEDGER, null)); assertTrue(emitted.isEmpty());
    }

    @Test public void realDeviceSupportRecreationWithNewControllerRestoresDurableLedger() throws Exception {
        final String legacy = prepareDurableTest(); service.dispose(); debug.close(); service = null;
        GearS3DeviceSupport support = new GearS3DeviceSupport();
        try {
            support.setContext(new nodomain.freeyourgadget.gadgetbridge.impl.GBDevice(TEST_ADDRESS, "Synthetic Gear", null, null,
                    nodomain.freeyourgadget.gadgetbridge.model.DeviceType.SAMSUNG_GEAR_S3), android.bluetooth.BluetoothAdapter.getDefaultAdapter(), context);
            create((GearContactsDebugRequests) get(support, "contactsDebugRequests")); debug.register(context, TEST_ADDRESS, prefs);
            final Field contacts = support.getClass().getDeclaredField("contactsService"); contacts.setAccessible(true); contacts.set(support, service);
            resume(); assertEquals(0, setups()); final GearContactsDebugRequests old = debug;
            support.dispose(); service = null; old.close(); support = new GearS3DeviceSupport();
            support.setContext(new nodomain.freeyourgadget.gadgetbridge.impl.GBDevice(TEST_ADDRESS, "Synthetic Gear", null, null,
                    nodomain.freeyourgadget.gadgetbridge.model.DeviceType.SAMSUNG_GEAR_S3), android.bluetooth.BluetoothAdapter.getDefaultAdapter(), context);
            create((GearContactsDebugRequests) get(support, "contactsDebugRequests")); debug.register(context, TEST_ADDRESS, prefs);
            final Field nextContacts = support.getClass().getDeclaredField("contactsService"); nextContacts.setAccessible(true); nextContacts.set(support, service);
            assertNotSame(old, debug); resume(); assertEquals(0, setups());
            provider.rows.put(TEST_RAW, CURRENT); resume(); assertEquals(1, setups()); assertEquals(legacy, prefs.getString(SNAPSHOT, null));
        } finally { support.dispose(); service = null; }
    }

    private static final long DELETE_RAW = 1977;

    private String prepareDeleteTrial() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(DELETE_RAW, OLD); create(); debug.register(context, TEST_ADDRESS, prefs);
        final String legacy = prefs.getString(SNAPSHOT, null);
        assertTrue(debug.preparePersistentDeleteTest(DELETE_RAW)); awaitPreparation();
        assertEquals(DELETE_RAW, snapshot().persistentDeleteTestRawId()); assertTrue(debug.hasPersistentTest());
        assertEquals(legacy, prefs.getString(SNAPSHOT, null)); assertTrue(emitted.isEmpty()); return legacy;
    }

    private void beginDeleteTrial() throws Exception {
        prepareDeleteTrial(); provider.rows.remove(DELETE_RAW); resume();
        assertEquals(1, files().size()); assertEquals(1, setups()); assertFalse((Boolean) get(service, "pendingReplacement"));
        assertFalse((Boolean) get(service, "pendingDebugOneShot"));
    }

    private void assertDeleteUncheckpointed() {
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW)); assertTrue(snapshot().isBlocked(DELETE_RAW));
        assertEquals(GearContactsSnapshot.fingerprint(contact(DELETE_RAW, OLD)), snapshot().lastTransportDeliveredFingerprint(DELETE_RAW));
    }

    private void seedTransportLedger(long id, String number) {
        final GearContactsSnapshot state = snapshot(); assertTrue(state.beginTransport(Collections.singleton(id)));
        assertTrue(state.recordTransportDelivered(Collections.singletonMap(id, GearContactsSnapshot.fingerprint(contact(id, number)))));
    }

    @Test public void productionDeleteTrialUsesExactRawIdEnvelopeAndNoUnrelatedLegacyDelta() throws Exception {
        final String legacy = prepareDeleteTrial(); provider.rows.remove(DELETE_RAW); provider.rows.remove(1L); provider.rows.remove(2L); provider.rows.put(3L, CURRENT);
        resume(); assertEquals(1, files().size());
        assertEquals(GearContactsCodec.change("delete", Collections.emptyList(), Collections.singletonList(DELETE_RAW)).toString(), decode(files().peek()).toString());
        final GearContactsSnapshot.Delta selected = (GearContactsSnapshot.Delta) get(service, "pendingDelta");
        assertTrue(selected.insert.isEmpty()); assertTrue(selected.update.isEmpty()); assertEquals(Collections.singletonList(DELETE_RAW), selected.delete);
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("production DELETE selected: rawId=1977")));
        assertFalse(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("debug one-shot selected")));
        assertEquals(legacy, prefs.getString(SNAPSHOT, null));
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW)); assertFalse(snapshot().isBlocked(DELETE_RAW));
    }

    @Test public void deliveredDeleteTombstoneSurvivesTwoProcessStyleRecreationsAndSuppressesD() throws Exception {
        final String legacy = prepareDeleteTrial(); recreateDurableService(); resume(); assertEquals(0, setups());
        provider.rows.remove(DELETE_RAW); resume(); complete();
        assertTrue(snapshot().isTransportDeleted(DELETE_RAW)); assertFalse(snapshot().isBlocked(DELETE_RAW));
        assertEquals(GearContactsSnapshot.fingerprint(contact(DELETE_RAW, OLD)), snapshot().lastTransportDeliveredFingerprint(DELETE_RAW));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("persistent deletion checkpoint: rawId=1977; transport-delivered=true; semantic result unknown")));
        resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
        recreateDurableService(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
        recreateDurableService(); resume(); assertEquals(1, setups()); assertEquals(legacy, prefs.getString(SNAPSHOT, null));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("persistent deletion restored: rawId=1977; transportDeleted=true")));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("persistent deletion unchanged: rawId=1977 still absent; no files sent")));
    }

    @Test public void failedDeleteSetupDoesNotCheckpointAndRetriesAfterReconstruction() throws Exception {
        beginDeleteTrial(); command("filetransfer-setup-rsp", 1);
        assertFalse(snapshot().isBlocked(DELETE_RAW)); assertFalse(snapshot().isTransportDeleted(DELETE_RAW));
        recreateDurableService(); resume(); assertEquals(2, setups()); assertEquals(1, files().size());
    }
    @Test public void failedDeletePayloadDoesNotCheckpoint() throws Exception {
        beginDeleteTrial(); failSend = "payload"; command("filetransfer-setup-rsp", 0); assertDeleteUncheckpointed(); assertTrue(files().isEmpty());
    }
    @Test public void disconnectedDeleteDoesNotCheckpointAndBlocksReplay() throws Exception {
        beginDeleteTrial(); command("filetransfer-setup-rsp", 0); service.dispose(); assertDeleteUncheckpointed();
        debug.close(); create(); debug.register(context, TEST_ADDRESS, prefs); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
    }
    @Test public void deleteSetupTimeoutDoesNotCheckpoint() throws Exception {
        beginDeleteTrial(); ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW)); assertFalse(snapshot().isBlocked(DELETE_RAW)); assertTrue(files().isEmpty());
    }
    @Test public void deleteCompleteBeforeAllBytesOrAcceptedSetupCannotCheckpoint() throws Exception {
        beginDeleteTrial(); command("filetransfer-complete-req", 0);
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW)); assertFalse(snapshot().isBlocked(DELETE_RAW)); assertTrue(files().isEmpty());
    }
    @Test public void failedDeletePersistenceKeepsBlockAndPreviousFingerprint() throws Exception {
        beginDeleteTrial(); command("filetransfer-setup-rsp", 0);
        final SharedPreferences fail = org.mockito.Mockito.mock(SharedPreferences.class, org.mockito.AdditionalAnswers.delegatesTo(prefs));
        final SharedPreferences.Editor editor = org.mockito.Mockito.mock(SharedPreferences.Editor.class, org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.when(fail.edit()).thenReturn(editor); org.mockito.Mockito.when(editor.commit()).thenReturn(false);
        final GearContactsSnapshot active = (GearContactsSnapshot) get(service, "snapshot");
        final Field store = GearContactsSnapshot.class.getDeclaredField("store"); store.setAccessible(true); store.set(active, new GearContactsSnapshotStore(fail));
        complete(); assertDeleteUncheckpointed(); assertTrue(files().isEmpty());
    }

    @Test public void reappearedDeleteTrialUsesInsertAndFreshFtBoundaryThenReplacesTombstone() throws Exception {
        final String legacy = prepareDeleteTrial(); provider.rows.remove(DELETE_RAW); resume(); complete();
        provider.rows.put(DELETE_RAW, OLD); resume(); assertEquals(1, files().size()); assertEquals(1, setups()); // D session must close first.
        assertTrue(decode(files().peek()).has("insert")); assertFalse((Boolean) get(service, "pendingReplacement"));
        assertTrue(snapshot().isTransportDeleted(DELETE_RAW));
        assertFalse(service.onTerminationResponse(new SapServiceConnectionTerminationResponse(22, 21, GearContactsService.FILE_TRANSFER, 0)));
        assertEquals(1, setups()); fresh(); assertEquals(2, setups()); complete();
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW)); assertFalse(snapshot().isBlocked(DELETE_RAW));
        assertEquals(GearContactsSnapshot.fingerprint(contact(DELETE_RAW, OLD)), snapshot().lastTransportDeliveredFingerprint(DELETE_RAW));
        recreateDurableService(); resume(); assertEquals(2, setups()); assertEquals(legacy, prefs.getString(SNAPSHOT, null));
    }
    @Test public void reappearedIdAfterProcessRestartUsesCurrentInsertAndCanBeDeletedAgain() throws Exception {
        beginDeleteTrial(); complete(); recreateDurableService(); provider.rows.put(DELETE_RAW, CURRENT); resume();
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert")); complete();
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW));
        assertEquals(GearContactsSnapshot.fingerprint(contact(DELETE_RAW, CURRENT)), snapshot().lastTransportDeliveredFingerprint(DELETE_RAW));
        provider.rows.remove(DELETE_RAW); resume(); complete(); assertTrue(snapshot().isTransportDeleted(DELETE_RAW));
    }

    @Test public void ledgerAndBaselineNormalDeletesBothWaitForResume() throws Exception {
        seedTransportLedger(3, OLD); provider.rows.put(3L, OLD); create(); resume();
        provider.rows.remove(3L); provider.rows.remove(2L);
        final android.database.ContentObserver observer = (android.database.ContentObserver) get(service, "observer"); observer.onChange(false);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(350)); drain(); assertEquals(0, setups());
        resume(); assertEquals(1, files().size());
        final org.json.JSONArray rows = decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list");
        assertEquals(2, rows.length());
        assertEquals(2, rows.getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        assertEquals(3, rows.getJSONObject(1).getJSONObject("raw_contact").getLong("id"));
        complete(); resume(); assertEquals(1, setups()); assertTrue(snapshot().isTransportDeleted(3));
        assertEquals(original, prefs.getString(SNAPSHOT, null)); assertFalse(snapshot().hasPendingVerification());
    }
    @Test public void normalDeliveredDeletionDoesNotBlockUnrelatedInsertOrModify() throws Exception {
        seedTransportLedger(3, OLD); create(); resume(); complete(); assertTrue(snapshot().isTransportDeleted(3));
        provider.rows.put(4L, OLD); provider.rows.put(2L, CURRENT); resume();
        assertEquals(1, setups()); fresh(); complete(); drain(); // Fresh FT before new I; then normal MODIFY is eligible.
        assertTrue(snapshot().isTransportUncertain(4)); assertEquals(2, files().size());
        complete(); fresh(); complete(); assertTrue(snapshot().isTransportUncertain(2));
        assertTrue(snapshot().isTransportDeleted(3)); assertFalse(snapshot().hasPendingVerification());
    }
    @Test public void normalInterruptedDeletionDoesNotBlockUnrelatedAddAfterRecreation() throws Exception {
        seedTransportLedger(3, OLD); create(); resume(); failSend = "payload"; command("filetransfer-setup-rsp", 0);
        assertTrue(snapshot().isBlocked(3)); recreateDurableService(); provider.rows.put(4L, CURRENT); resume(); complete();
        assertTrue(snapshot().isTransportUncertain(4)); assertTrue(snapshot().isBlocked(3)); assertFalse(snapshot().isTransportDeleted(3));
        assertFalse(snapshot().hasPendingVerification());
    }
    @Test public void normalMixedChangesDeferDeleteWithoutCreatingUnmatchedReplacement() throws Exception {
        seedTransportLedger(3, OLD); provider.rows.put(4L, OLD); provider.rows.put(2L, CURRENT); create(); resume();
        assertEquals(1, files().size()); complete(); drain(); assertEquals(2, files().size());
        complete(); fresh(); complete(); assertFalse(snapshot().isTransportDeleted(3));
        resume(); assertEquals(1, files().size()); complete(); assertTrue(snapshot().isTransportDeleted(3));
    }

    @Test public void deleteTrialClearKeepsTombstoneAndPermitsIndependentProductionChanges() throws Exception {
        final String legacy = prepareDeleteTrial(); provider.rows.remove(DELETE_RAW); resume(); complete();
        assertFalse(debug.clearPersistentDeleteTest(DELETE_RAW + 1)); assertFalse(debug.clearPersistentTest(9120));
        assertTrue(debug.clearPersistentDeleteTest(DELETE_RAW)); assertTrue(snapshot().isTransportDeleted(DELETE_RAW));
        assertEquals(0, snapshot().persistentDeleteTestRawId()); provider.rows.put(DELETE_RAW, CURRENT); provider.rows.put(3L, CURRENT);
        recreateDurableService(); resume(); assertEquals(2, setups()); assertEquals(1, files().size());
        assertTrue(decode(files().peek()).has("insert")); assertEquals(legacy, prefs.getString(SNAPSHOT, null));
    }
    @Test public void deleteScopeMutuallyExcludesOtherDebugAndModifyPreparations() throws Exception {
        prepareDeleteTrial(); assertFalse(debug.preparePersistentDeleteTest(DELETE_RAW)); assertFalse(debug.preparePersistentDeleteTest(DELETE_RAW + 1));
        assertFalse(debug.preparePersistentTest(9120)); assertFalse(debug.prepareProductionTest(DELETE_RAW));
        assertFalse(debug.request(GearContactsDebugRequests.Mode.INSERT, DELETE_RAW)); assertFalse(debug.request(GearContactsDebugRequests.Mode.REPLACE, DELETE_RAW));
        assertTrue(debug.clearPersistentDeleteTest(DELETE_RAW)); assertTrue(debug.preparePersistentTest(9120)); awaitPreparation();
        // Missing 9120 does not create a scope and cannot alter the prior deletion test ledger row.
        assertFalse(debug.hasPersistentTest()); assertEquals(GearContactsSnapshot.fingerprint(contact(DELETE_RAW, OLD)), snapshot().lastTransportDeliveredFingerprint(DELETE_RAW));
    }
    @Test public void deletePreparationRequiresExistingEligibleRowAndSendsNoFiles() throws Exception {
        assertTrue(snapshot().markPendingVerification()); create(); debug.register(context, TEST_ADDRESS, prefs);
        assertFalse(debug.preparePersistentDeleteTest(0)); assertTrue(debug.preparePersistentDeleteTest(DELETE_RAW)); awaitPreparation();
        assertFalse(debug.hasPersistentTest()); assertNull(prefs.getString(LEDGER, null)); assertTrue(emitted.isEmpty());
    }
    @Test public void deleteTrialCannotRunOnSapObserverOrInitialWithoutNewResume() throws Exception {
        final String legacy = prepareDeleteTrial(); resume(); provider.rows.remove(DELETE_RAW);
        invoke("scheduleScan", boolean.class, false); drain(); assertEquals(0, setups());
        invoke("onContactMessage", byte[].class, "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8)); drain();
        assertEquals(0, setups()); assertEquals(legacy, prefs.getString(SNAPSHOT, null)); resume(); assertEquals(1, setups());
    }
    @Test public void deleteTrialDoesNotModifyChangedExistingRowWithoutPriorDeletion() throws Exception {
        prepareDeleteTrial(); provider.rows.put(DELETE_RAW, CURRENT); resume(); assertEquals(0, setups());
        assertFalse(snapshot().isTransportDeleted(DELETE_RAW)); assertTrue(debug.hasPersistentTest());
        provider.rows.remove(DELETE_RAW); resume(); assertEquals(1, setups());
    }
    @Test public void deletePreparationCanPersistDisconnectedAndWrongClearCannotCancelIt() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(DELETE_RAW, OLD); debug = new GearContactsDebugRequests(); debug.register(context, TEST_ADDRESS, prefs);
        assertTrue(debug.preparePersistentDeleteTest(DELETE_RAW)); assertFalse(debug.clearPersistentDeleteTest(DELETE_RAW + 1)); assertFalse(debug.clearPersistentTest(9120));
        awaitPreparation(); assertTrue(debug.hasPersistentTest()); debug.close(); create(); debug.register(context, TEST_ADDRESS, prefs);
        resume(); assertEquals(0, setups()); provider.rows.remove(DELETE_RAW); resume(); assertEquals(1, setups());
    }
    @Test public void deletePreparationCancellationDoesNotResurrectAuthorizationOrPersistPii() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(DELETE_RAW, OLD); create(); debug.register(context, TEST_ADDRESS, prefs);
        assertTrue(debug.preparePersistentDeleteTest(DELETE_RAW)); assertTrue(debug.clearPersistentDeleteTest(DELETE_RAW)); awaitPreparation();
        assertNull(prefs.getString(LEDGER, null)); assertTrue(emitted.isEmpty());
        assertTrue(debug.preparePersistentDeleteTest(DELETE_RAW)); awaitPreparation();
        assertFalse(prefs.getAll().toString().contains("Synthetic")); assertFalse(prefs.getAll().toString().contains("+1555"));
    }
    @Test public void deleteBroadcastsUseExactDeviceAddressAndRawIdWithoutFiles() throws Exception {
        assertTrue(snapshot().markPendingVerification()); provider.rows.put(DELETE_RAW, OLD); create(); debug.register(context, TEST_ADDRESS, prefs);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.DUMP);
        final Intent intent = new Intent(GearContactsService.DEBUG_PREPARE_PERSISTENT_DELETE_RAW_ACTION).setPackage(context.getPackageName())
                .putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, DELETE_RAW).putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, "66:77:88:99:AA:BB");
        context.sendBroadcast(intent); idle(); assertFalse(debug.hasPersistentTest());
        intent.putExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA, TEST_ADDRESS); context.sendBroadcast(intent); idle(); awaitPreparation();
        assertTrue(debug.hasPersistentTest()); assertTrue(emitted.isEmpty());
        intent.setAction(GearContactsService.DEBUG_CLEAR_PERSISTENT_DELETE_RAW_ACTION).putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, DELETE_RAW + 1);
        context.sendBroadcast(intent); idle(); assertTrue(debug.hasPersistentTest());
        intent.putExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, DELETE_RAW); context.sendBroadcast(intent); idle(); assertFalse(debug.hasPersistentTest());
    }
    @Test public void deletionTombstoneSurvivesActualDeviceSupportRecreation() throws Exception {
        beginDeleteTrial(); complete(); service.dispose(); debug.close(); service = null;
        GearS3DeviceSupport support = new GearS3DeviceSupport();
        try {
            support.setContext(new nodomain.freeyourgadget.gadgetbridge.impl.GBDevice(TEST_ADDRESS, "Synthetic Gear", null, null,
                    nodomain.freeyourgadget.gadgetbridge.model.DeviceType.SAMSUNG_GEAR_S3), android.bluetooth.BluetoothAdapter.getDefaultAdapter(), context);
            create((GearContactsDebugRequests) get(support, "contactsDebugRequests")); debug.register(context, TEST_ADDRESS, prefs);
            final Field contacts = support.getClass().getDeclaredField("contactsService"); contacts.setAccessible(true); contacts.set(support, service);
            resume(); assertTrue(files().isEmpty()); assertEquals(1, setups()); assertTrue(snapshot().isTransportDeleted(DELETE_RAW));
        } finally { support.dispose(); service = null; }
    }

    @Test public void multipleDeleteFilesCheckpointIndependentlyAndRequireFreshFtBeforeNextBatch() throws Exception {
        final Map<Long, String> hashes = new LinkedHashMap<>();
        for (long id = 3; id <= 203; id++) hashes.put(id, GearContactsSnapshot.fingerprint(contact(id, OLD)));
        final GearContactsSnapshot state = snapshot(); assertTrue(state.beginTransport(hashes.keySet())); assertTrue(state.recordTransportDelivered(hashes));
        create(); resume(); assertEquals(2, files().size()); complete();
        assertEquals(1, setups()); assertNull(get(service, "sending")); assertFalse(snapshot().isBlocked(203));
        for (long id = 3; id <= 202; id++) assertTrue(snapshot().isTransportDeleted(id));
        fresh(); assertEquals(2, setups()); assertFalse(snapshot().isBlocked(203)); failSend = "payload"; command("filetransfer-setup-rsp", 0);
        assertFalse(snapshot().isTransportDeleted(203)); assertTrue(snapshot().isBlocked(203));
        recreateDurableService(); resume(); assertTrue(files().isEmpty()); assertEquals(2, setups()); assertFalse(snapshot().hasPendingVerification());
    }

    @Test public void preparedModifyBeforeFtSapDisconnectHasNoDurableBlockAndRetriesOnResume() throws Exception {
        beginWithoutFt(); assertEquals(0, setups()); assertEquals(2, files().size());
        assertFalse(snapshot().isBlocked(1)); assertNull(prefs.getString(LEDGER, null));
        service.dispose(); assertFalse(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, OLD)), snapshot().lastTransportDeliveredFingerprint(1));
        recreateDurableService(); resume(); assertEquals(1, setups()); assertEquals(2, files().size());
        assertFalse(snapshot().isBlocked(1)); assertFalse((Boolean) get(service, "pendingDebugOneShot"));
    }

    @Test public void preparedModifyWithAlreadyDisconnectedQueueHasNoBlockAndDeletionConverges() throws Exception {
        dropQueuedSetup = true; begin(); assertEquals(0, setups()); assertEquals(2, files().size());
        assertFalse(snapshot().isBlocked(1)); service.dispose(); dropQueuedSetup = false;
        provider.rows.remove(1L); recreateDurableService(); resume();
        assertEquals(1, setups()); assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("delete"));
        complete(); assertTrue(snapshot().isTransportDeleted(1)); assertFalse(snapshot().isBlocked(1));
    }

    @Test public void preparedModifySetupSendExceptionDoesNotPoisonId() throws Exception {
        provider.rows.put(1L, CURRENT); create(); failSend = "setup"; resume(); stoppedBeforePayload(0);
        recreateDurableService(); resume(); assertEquals(1, setups()); assertEquals(2, files().size());
    }

    @Test public void establishedFtSapAndOfferedDWithoutAcceptanceAreStillRetryable() throws Exception {
        beginWithoutFt(); assertTrue(service.acceptResponse(opened(0))); assertEquals(1, setups());
        assertFalse(snapshot().isBlocked(1)); service.dispose(); recreateDurableService(); resume();
        assertEquals(2, setups()); assertEquals(2, files().size());
    }

    @Test public void disposalBeforePayloadKeepsLegacyUncertaintyAndExactPreviousLedgerHash() throws Exception {
        seedTransportLedger(1, OLD); final String legacy = legacyUncertain();
        final String before = prefs.getString(LEDGER, null); beginWithoutFt(); service.dispose();
        assertEquals(before, prefs.getString(LEDGER, null)); legacyRetained(legacy);
        assertFalse(snapshot().isBlocked(1)); recreateDurableService(); resume(); assertEquals(2, files().size());
    }

    @Test public void setupAcceptedPersistsPayloadStageBeforeEmitterCanSendAnyBytes() throws Exception {
        begin(); assertFalse(snapshot().isBlocked(1)); requireDurablePayloadMarker = true;
        command("filetransfer-setup-rsp", 0); assertTrue(snapshot().isBlocked(1));
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
        assertTrue((Integer) get(files().peek(), "offset") > 0);
    }

    @Test public void disconnectAfterPayloadEnqueueRemainsProtectedAcrossServiceRecreation() throws Exception {
        begin(); command("filetransfer-setup-rsp", 0); service.dispose(); recreateDurableService(); resume();
        assertTrue(snapshot().isBlocked(1)); assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
        assertEquals(1, setups()); assertTrue(files().isEmpty());
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("incomplete attempt restored: rawId=1; stage=PAYLOAD_MAY_HAVE_STARTED")));
    }

    @Test public void ambiguousModifyStillProtectsDeletedIdButAllowsUnrelatedNewInsert() throws Exception {
        begin(); command("filetransfer-setup-rsp", 0); service.dispose();
        provider.rows.remove(1L); provider.rows.put(3L, CURRENT); recreateDurableService(); resume();
        assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert")); complete();
        assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().isTransportDeleted(1)); assertTrue(snapshot().isTransportUncertain(3));
    }

    @Test public void insertDisconnectBeforeSapDoesNotBlockAndRetriesOnNextResume() throws Exception {
        provider.rows.put(3L, CURRENT); create(); set("ftCommandSession", -1); set("ftDataSession", -1); resume();
        service.dispose(); assertFalse(snapshot().isBlocked(3)); assertNull(snapshot().lastTransportDeliveredFingerprint(3));
        recreateDurableService(); resume(); assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert"));
        complete(); assertTrue(snapshot().isTransportUncertain(3));
    }

    @Test public void insertOfferRejectedRemainsRetryableAcrossProcessStyleRecreation() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); command("filetransfer-setup-rsp", 1);
        assertFalse(snapshot().isBlocked(3)); recreateDurableService(); resume();
        assertEquals(2, setups()); assertEquals(1, files().size()); complete();
    }

    @Test public void insertPayloadEnqueueFailureRemainsProtectedAcrossRecreation() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); failSend = "payload"; command("filetransfer-setup-rsp", 0);
        recreateDurableService(); resume(); assertEquals(1, setups()); assertTrue(files().isEmpty());
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(3)); assertNull(snapshot().lastTransportDeliveredFingerprint(3));
    }

    @Test public void deleteDisconnectBeforeSapHasNoTombstoneOrBlockAndRetries() throws Exception {
        seedTransportLedger(3, OLD); create(); set("ftCommandSession", -1); set("ftDataSession", -1); resume(); service.dispose();
        assertFalse(snapshot().isBlocked(3)); assertFalse(snapshot().isTransportDeleted(3));
        recreateDurableService(); resume(); assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("delete"));
        complete(); assertTrue(snapshot().isTransportDeleted(3));
    }

    @Test public void deleteOfferRejectedRetriesAutomaticallyWithoutChangingPreviousFingerprint() throws Exception {
        seedTransportLedger(3, OLD); create(); resume(); command("filetransfer-setup-rsp", 1);
        assertFalse(snapshot().isBlocked(3)); assertFalse(snapshot().isTransportDeleted(3));
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
        recreateDurableService(); resume(); assertEquals(2, setups()); complete(); assertTrue(snapshot().isTransportDeleted(3));
    }

    @Test public void completedFirstPairDoesNotMarkNextPairUntilItsOwnPayloadBoundary() throws Exception {
        provider.rows.put(1L, CURRENT); provider.rows.put(2L, CURRENT); create(); resume(); complete(); fresh(); complete();
        fresh(); assertFalse(snapshot().isBlocked(2)); service.dispose(); recreateDurableService(); resume();
        assertTrue(snapshot().isTransportUncertain(1)); assertEquals(2, files().size());
        assertEquals(2, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
    }

    @Test public void failedMarkerPersistenceSendsNoPayloadAndKeepsIdRetryable() throws Exception {
        begin();
        final SharedPreferences fail = org.mockito.Mockito.mock(SharedPreferences.class, org.mockito.AdditionalAnswers.delegatesTo(prefs));
        final SharedPreferences.Editor editor = org.mockito.Mockito.mock(SharedPreferences.Editor.class, org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.when(fail.edit()).thenReturn(editor); org.mockito.Mockito.when(editor.commit()).thenReturn(false);
        final Field store = GearContactsSnapshot.class.getDeclaredField("store"); store.setAccessible(true);
        store.set(get(service, "snapshot"), new GearContactsSnapshotStore(fail));
        command("filetransfer-setup-rsp", 0); assertFalse(snapshot().isBlocked(1)); assertTrue(files().isEmpty());
        assertFalse(emitted.stream().anyMatch(record -> record.getHeader().getSessionId() == integer("ftDataSession")));
        recreateDurableService(); resume(); assertEquals(2, files().size());
    }

    @Test public void prePayloadStopLogsRetryAndDoesNotClaimImporterSuccess() throws Exception {
        beginWithoutFt(); service.dispose();
        assertTrue(ShadowLog.getLogs().stream().anyMatch(l -> l.msg.contains("production transfer stopped before payload: service disposed")
                && l.msg.contains("no new durable incomplete block") && l.msg.contains("automatic retry eligible")));
        assertEquals(original, prefs.getString(SNAPSHOT, null));
    }


    private void setupTimesOut() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
    }

    private void recoveredFt() throws Exception {
        assertTrue(service.onTerminationResponse(closed(0)));
        assertTrue(service.acceptResponse(opened(0))); drain();
    }

    private void newInsert() throws Exception {
        provider.rows.put(3L, CURRENT); create(); resume(); assertEquals(1, setups());
    }

    private String ftState() throws Exception { return get(service, "ftState").toString(); }

    @Test public void insertSetupTimeoutRetiresFtWithoutPayloadMarkerOrCheckpoint() throws Exception {
        newInsert(); final long control = (Long) get(service, "controlGeneration"); setupTimesOut();
        assertEquals("RETIRING", ftState()); assertTrue(files().isEmpty()); assertNull(get(service, "sending"));
        assertFalse(snapshot().isBlocked(3)); assertNull(snapshot().lastTransportDeliveredFingerprint(3));
        assertEquals(original, prefs.getString(SNAPSHOT, null)); assertEquals(control, get(service, "controlGeneration"));
        assertEquals(0, reconnectRequests); assertEquals(1, setups());
    }

    @Test public void insertRetriesOnFreshFtWithoutAnotherResumeAndCheckpointsExactFingerprint() throws Exception {
        newInsert(); final long control = (Long) get(service, "controlGeneration"); setupTimesOut(); recoveredFt();
        assertNotEquals(10, integer("ftCommandSession")); assertNotEquals(11, integer("ftDataSession"));
        assertEquals(control, get(service, "controlGeneration")); assertEquals(2, setups());
        assertFalse(snapshot().isBlocked(3)); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, CURRENT)), snapshot().lastTransportDeliveredFingerprint(3));
        assertFalse(snapshot().isBlocked(3)); resume(); assertEquals(2, setups());
    }

    @Test public void safeRetryRescansLatestPhoneRepresentationInsteadOfOldPackagedInsert() throws Exception {
        newInsert(); setupTimesOut(); provider.rows.put(3L, OLD); recoveredFt();
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), ((Map<?, ?>) get(files().peek(), "sentHashes")).get(3L));
        complete(); assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
    }

    @Test public void newPhoneContactRemovedDuringSafeRecoveryIsNotInsertedFromStaleFile() throws Exception {
        newInsert(); setupTimesOut(); provider.rows.remove(3L); recoveredFt();
        assertEquals(1, setups()); assertTrue(files().isEmpty()); assertNull(snapshot().lastTransportDeliveredFingerprint(3));
    }

    @Test public void deleteSetupTimeoutReacquiresAndRetriesOnSameReadyControl() throws Exception {
        provider.rows.remove(2L); create(); resume(); setupTimesOut(); recoveredFt();
        assertEquals(2, setups()); assertTrue(decode(files().peek()).has("delete"));
        assertFalse(snapshot().isBlocked(2)); complete(); assertTrue(snapshot().isTransportDeleted(2));
        resume(); assertEquals(2, setups());
    }

    @Test public void modifyDSetupTimeoutReconcilesWholePairSafely() throws Exception {
        begin(); setupTimesOut(); assertFalse(snapshot().isBlocked(1)); recoveredFt();
        assertEquals(2, files().size()); assertTrue(decode(files().peek()).has("delete"));
        complete(); fresh(); complete(); assertEquals(3, setups());
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void modifyISetupTimeoutKeepsCompletedDAndRetriesOnlyCurrentI() throws Exception {
        begin(); complete(); fresh(); setupTimesOut();
        assertTrue(snapshot().isBlocked(1)); assertEquals(1, files().size()); assertTrue(decode(files().peek()).has("insert"));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, OLD)), snapshot().lastTransportDeliveredFingerprint(1));
        recoveredFt(); assertEquals(3, setups()); assertTrue(decode(files().peek()).has("insert"));
        complete(); assertFalse(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, CURRENT)), snapshot().lastTransportDeliveredFingerprint(1));
        resume(); assertEquals(3, setups());
    }

    @Test public void modifyIRecoveryReadsLatestPhoneRowAndCheckpointsItsExactSentHash() throws Exception {
        begin(); complete(); fresh(); setupTimesOut(); provider.rows.put(1L, "+15550003003"); recoveredFt();
        final String sent = GearContactsSnapshot.fingerprint(contact(1, "+15550003003"));
        assertEquals(sent, ((Map<?, ?>) get(files().peek(), "sentHashes")).get(1L));
        provider.rows.put(1L, OLD); complete(); assertEquals(sent, snapshot().lastTransportDeliveredFingerprint(1));
        resume(); drain(); assertTrue(decode(files().peek()).has("delete"));
    }

    @Test public void multipleModifyPairsSurviveFirstIRecoveryWithoutRepeatingItsD() throws Exception {
        provider.rows.put(1L, CURRENT); provider.rows.put(2L, CURRENT); create(); resume();
        complete(); fresh(); setupTimesOut(); recoveredFt(); assertEquals(3, files().size());
        assertTrue(decode(files().peek()).has("insert")); complete(); fresh();
        assertEquals(2L, decode(files().peek()).getJSONObject("delete").getJSONArray("raw_contact_list")
                .getJSONObject(0).getJSONObject("raw_contact").getLong("id"));
        complete(); fresh(); complete(); assertEquals(5, setups());
        assertFalse(snapshot().isBlocked(1)); assertFalse(snapshot().isBlocked(2));
    }

    @Test public void postPayloadInsertTimeoutDoesNotUseSafeRetry() throws Exception {
        newInsert(); command("filetransfer-setup-rsp", 0); setupTimesOut();
        assertTrue(snapshot().isBlocked(3)); assertNull(snapshot().lastTransportDeliveredFingerprint(3));
        assertFalse((Boolean) get(service, "ftRecoveryPending")); assertTrue(files().isEmpty());
        resume(); assertEquals(1, setups());
    }

    @Test public void postPayloadModifyITimeoutKeepsExistingAmbiguityProtection() throws Exception {
        begin(); complete(); fresh(); command("filetransfer-setup-rsp", 0); setupTimesOut();
        assertTrue(snapshot().isBlocked(1)); assertFalse((Boolean) get(service, "ftRecoveryPending"));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, OLD)), snapshot().lastTransportDeliveredFingerprint(1));
        resume(); assertEquals(2, setups());
    }

    @Test public void secondSetupTimeoutExhaustsOneRetryWithoutBusyLoopOrRfcommEscalation() throws Exception {
        newInsert(); setupTimesOut(); recoveredFt(); setupTimesOut();
        assertEquals("EXHAUSTED", ftState()); assertNull(get(service, "ftRecoveryTimeout"));
        assertFalse(snapshot().isBlocked(3)); for (int i = 0; i < 20; i++) observedChange();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(30)); drain();
        assertEquals(2, setups()); assertEquals(0, reconnectRequests); assertTrue((Boolean) get(service, "scanAgain"));
    }

    @Test public void realResumeCanStartNewRecoveryEpisodeAfterBudgetExhaustion() throws Exception {
        newInsert(); setupTimesOut(); recoveredFt(); setupTimesOut(); resume();
        assertEquals("CONNECTING", ftState()); assertTrue(service.acceptResponse(opened(0))); drain();
        assertEquals(3, setups()); complete(); assertTrue(snapshot().isTransportUncertain(3));
    }

    @Test public void exhaustedModifyIRecoveryRetainsUnsentContinuationAndCompletedDMarker() throws Exception {
        begin(); complete(); fresh(); setupTimesOut(); recoveredFt(); setupTimesOut();
        assertEquals("EXHAUSTED", ftState()); assertEquals(1, files().size()); assertTrue(snapshot().isBlocked(1));
        resume(); assertTrue(service.acceptResponse(opened(0))); drain(); assertTrue(decode(files().peek()).has("insert"));
        complete(); assertFalse(snapshot().isBlocked(1)); assertEquals(4, setups());
    }

    @Test public void staleCommandSessionCannotAcceptFreshSetupOrCheckpointNewFile() throws Exception {
        newInsert(); final Object old = files().peek(); setupTimesOut(); recoveredFt();
        final byte[] stale = new JSONObject().put("msgId", "filetransfer-setup-rsp").put("result", 0)
                .put("fileName", ((File) get(old, "path")).getName()).toString().getBytes(StandardCharsets.UTF_8);
        service.onRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0, 10, 0), GearContactsCodec.frame(1, stale)));
        assertFalse((Boolean) get(files().peek(), "setupAccepted")); assertFalse(snapshot().isBlocked(3));
        assertNull(snapshot().lastTransportDeliveredFingerprint(3)); complete(); assertTrue(snapshot().isTransportUncertain(3));
    }

    @Test public void wrongReturnedTransactionIdCannotArmFreshPayloadBoundary() throws Exception {
        newInsert(); setupTimesOut(); recoveredFt();
        invoke("onFtCommand", byte[].class, new JSONObject().put("msgId", "filetransfer-setup-rsp").put("result", 0)
                .put("transId", -1).put("fileName", ((File) get(files().peek(), "path")).getName())
                .toString().getBytes(StandardCharsets.UTF_8));
        assertFalse(snapshot().isBlocked(3)); assertFalse((Boolean) get(files().peek(), "setupAccepted"));
        complete(); assertTrue(snapshot().isTransportUncertain(3));
    }

    @Test public void staleOfferTimeoutCannotRetireFreshGeneration() throws Exception {
        newInsert(); final Runnable stale = (Runnable) get(service, "replaceTransferTimeout");
        setupTimesOut(); recoveredFt(); final long generation = (Long) get(service, "ftGeneration");
        stale.run(); assertEquals(generation, get(service, "ftGeneration")); assertEquals("SETUP_PENDING", ftState());
        assertEquals(2, setups()); assertFalse(snapshot().isBlocked(3));
    }

    @Test public void staleRetirementTimeoutCannotRetireFreshFtGeneration() throws Exception {
        newInsert(); setupTimesOut(); final Runnable stale = (Runnable) get(service, "ftRecoveryTimeout"); recoveredFt();
        final int session = integer("ftCommandSession"); stale.run();
        assertEquals(session, integer("ftCommandSession")); assertEquals("SETUP_PENDING", ftState());
    }

    @Test public void retiredConnectionResponseCannotEstablishNewGeneration() throws Exception {
        newInsert(); setupTimesOut(); assertTrue(service.onTerminationResponse(closed(0)));
        final int pending = integer("pendingFtCommand");
        assertFalse(service.acceptResponse(new SapServiceConnectionResponse(21, integer("ftComponent"),
                GearContactsService.FILE_TRANSFER, 0, Arrays.asList(11, 10))));
        assertEquals(pending, integer("pendingFtCommand")); assertEquals(1, setups());
        assertTrue(service.acceptResponse(opened(0))); drain(); assertEquals(2, setups());
    }

    @Test public void controlLossDuringRetirementDefersFreshFtUntilNewCurrentResume() throws Exception {
        newInsert(); setupTimesOut(); controlLost(); assertTrue(service.onTerminationResponse(closed(0)));
        assertEquals(-1, integer("pendingFtCommand")); controlBack(); assertEquals(1, setups());
        resume(); assertTrue(integer("pendingFtCommand") > 0); assertTrue(service.acceptResponse(opened(0))); drain();
        assertEquals(2, setups()); complete(); assertEquals(0, reconnectRequests);
    }

    @Test public void controlLossDuringFreshConnectionDefersOfferNotValidFreshSessions() throws Exception {
        newInsert(); setupTimesOut(); assertTrue(service.onTerminationResponse(closed(0))); controlLost();
        assertTrue(service.acceptResponse(opened(0))); assertEquals(1, setups());
        controlBack(); assertEquals(1, setups()); resume(); drain(); assertEquals(2, setups()); complete();
    }

    @Test public void observerStormDuringFtRecoveryCoalescesAndReadsLatestState() throws Exception {
        newInsert(); final int scans = provider.scans; setupTimesOut();
        for (int i = 0; i < 20; i++) { provider.rows.put(3L, OLD); observedChange(); }
        assertEquals(scans, provider.scans); assertEquals(1, setups()); recoveredFt();
        assertEquals(scans + 1, provider.scans); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(3, OLD)), snapshot().lastTransportDeliveredFingerprint(3));
    }

    @Test public void retirementResponseTimeoutLocallyInvalidatesAndReacquiresOnlyFt() throws Exception {
        newInsert(); setupTimesOut(); setupTimesOut(); assertEquals("CONNECTING", ftState());
        assertNotEquals(10, integer("pendingFtCommand")); assertTrue(service.acceptResponse(opened(0))); drain();
        complete(); assertEquals(2, setups()); assertEquals(0, reconnectRequests);
    }

    @Test public void rejectedRetirementResponseCannotRestoreStaleSessions() throws Exception {
        newInsert(); setupTimesOut(); assertTrue(service.onTerminationResponse(closed(1)));
        assertEquals("CONNECTING", ftState()); assertNotEquals(10, integer("pendingFtCommand"));
        assertTrue(service.acceptResponse(opened(0))); drain(); complete(); assertEquals(2, setups());
    }

    @Test public void freshConnectionTimeoutIsBoundedAndPreservesSafeEligibility() throws Exception {
        newInsert(); setupTimesOut(); assertTrue(service.onTerminationResponse(closed(0))); setupTimesOut();
        assertEquals("EXHAUSTED", ftState()); assertFalse(snapshot().isBlocked(3));
        for (int i = 0; i < 10; i++) observedChange(); assertEquals(1, setups()); assertEquals(0, reconnectRequests);
    }

    @Test public void freshConnectionRejectedDuringIRecoveryRetainsContinuation() throws Exception {
        begin(); complete(); fresh(); setupTimesOut(); assertTrue(service.onTerminationResponse(closed(0)));
        assertTrue(service.acceptResponse(opened(1))); assertEquals("EXHAUSTED", ftState());
        assertEquals(1, files().size()); assertTrue(snapshot().isBlocked(1));
        assertEquals(GearContactsSnapshot.fingerprint(contact(1, OLD)), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void disposedFtRecoveryCallbacksCannotAcquireOrSendAnything() throws Exception {
        newInsert(); setupTimesOut(); final Runnable stale = (Runnable) get(service, "ftRecoveryTimeout");
        service.dispose(); final int sent = emitted.size(); stale.run();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(30));
        assertEquals(sent, emitted.size()); assertFalse(snapshot().isBlocked(3)); assertTrue((Boolean) get(service, "disposed"));
    }

    @Test public void disconnectedFtRecoveryCallbackDisposesWithoutStartingRetry() throws Exception {
        newInsert(); setupTimesOut(); final Runnable timeout = (Runnable) get(service, "ftRecoveryTimeout");
        emitterConnected = false; timeout.run(); assertTrue((Boolean) get(service, "disposed"));
        assertFalse(snapshot().isBlocked(3)); assertEquals(-1, integer("pendingFtCommand"));
    }

    @Test public void readyControlWatchdogIsNotRecreatedByFtTimeoutRecovery() throws Exception {
        newInsert(); assertNull(get(service, "controlWatchdog")); setupTimesOut(); recoveredFt();
        assertNull(get(service, "controlWatchdog")); assertEquals("READY", get(service, "controlState").toString());
        assertEquals(0, integer("controlRetries")); assertEquals(0, reconnectRequests);
    }

    @Test public void retryPayloadStillRequiresDurableStageBeforeFirstEnqueue() throws Exception {
        begin(); setupTimesOut(); recoveredFt(); requireDurablePayloadMarker = true;
        command("filetransfer-setup-rsp", 0); assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", ftState());
    }

    @Test public void safeRetryUsesGenericRulesWithoutMigrationOrBroadcast() throws Exception {
        provider.rows.put(9135L, CURRENT); create(); resume(); setupTimesOut(); recoveredFt(); complete();
        assertEquals(GearContactsSnapshot.fingerprint(contact(9135, CURRENT)), snapshot().lastTransportDeliveredFingerprint(9135));
        assertFalse(debug.hasPending()); assertFalse(debug.hasPersistentTest()); assertFalse(debug.hasProductionTest());
        resume(); assertEquals(2, setups());
    }

    @Test public void retiredFtSessionsRemainReservedForEverySubsequentGeneration() throws Exception {
        newInsert(); setupTimesOut(); recoveredFt(); final HashSet<Integer> used = new HashSet<>();
        service.addUsedSessions(used); assertTrue(used.contains(10)); assertTrue(used.contains(11));
        assertNotEquals(10, integer("ftCommandSession")); assertNotEquals(11, integer("ftDataSession"));
    }

    @Test public void exhaustedSessionSpaceCannotBusyLoopOrReuseRetiredSession() throws Exception {
        create(); set("ftCommandSession", -1); set("ftDataSession", -1);
        final HashSet<Integer> used = new HashSet<>(); for (int id = 1; id <= 992; id++) used.add(id);
        assertNull(service.startFileTransfer(used)); assertEquals(-1, integer("pendingFtCommand"));
        assertEquals(-1, integer("pendingFtData")); assertEquals("DISCONNECTED", ftState());
    }

    private JSONObject decode(Object file) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream((byte[]) get(file, "bytes")))) {
            assertNotNull(zip.getNextEntry()); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            while ((count = zip.read(buffer)) != -1) bytes.write(buffer, 0, count);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec((byte[]) get(service, "contactKey"), "AES"), new IvParameterSpec(new byte[16]));
            return new JSONObject(new String(cipher.doFinal(Base64.getMimeDecoder().decode(bytes.toByteArray())), StandardCharsets.UTF_8));
        }
    }
    private ArrayDeque<?> files() throws Exception { return (ArrayDeque<?>) get(service, "files"); }
    private static Object get(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private int integer(String name) { try { return (Integer) get(service, name); } catch (Exception e) { throw new AssertionError(e); } }
    private void set(String name, Object value) throws Exception { Field f = service.getClass().getDeclaredField(name); f.setAccessible(true); f.set(service, value); }
    private void invoke(String name, Class<?> type, Object value) throws Exception { Method m = service.getClass().getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(service, value); }

    public static final class Provider extends ContentProvider {
        final Map<Long, String> rows = new LinkedHashMap<>(); int scans;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(columns);
            if (uri.equals(ContactsContract.RawContacts.CONTENT_URI)) { scans++; for (long id : rows.keySet()) cursor.addRow(new Object[]{id, id + 1000, "Synthetic Person", 0}); }
            else if (uri.equals(ContactsContract.Contacts.CONTENT_URI)) for (long id : rows.keySet()) cursor.addRow(new Object[]{id + 1000, id});
            else if (uri.equals(ContactsContract.Data.CONTENT_URI)) for (Map.Entry<Long, String> row : rows.entrySet()) cursor.addRow(new Object[]{row.getKey(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, row.getValue(), "2", null, null, 1});
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
