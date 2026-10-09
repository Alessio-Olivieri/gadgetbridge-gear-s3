package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GearContactsSnapshotPersistenceTest {
    private SharedPreferences gearA, gearB;

    @Before public void setUp() {
        final Context context = RuntimeEnvironment.getApplication();
        gearA = context.getSharedPreferences("devicesettings_00:11:22:33:44:55", Context.MODE_PRIVATE);
        gearB = context.getSharedPreferences("devicesettings_66:77:88:99:AA:BB", Context.MODE_PRIVATE);
        gearA.edit().clear().commit();
        gearB.edit().clear().commit();
    }

    private static GearContactsSnapshot snapshot(final SharedPreferences preferences) {
        return new GearContactsSnapshot(new GearContactsSnapshotStore(preferences));
    }

    private static GearContactsCodec.Contact contact(final long id, final String number) {
        return new GearContactsCodec.Contact(id, id + 1000, id, "Synthetic Person", "Synthetic", "Person", false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }

    private static List<GearContactsCodec.Contact> addressBook(final int count) {
        final List<GearContactsCodec.Contact> records = new ArrayList<>();
        for (int i = 1; i <= count; i++) records.add(contact(i, "+1555000" + i));
        return records;
    }

    @Test public void freshStateRequiresInitialAndDoesNotPersistBeforeCompletion() {
        final GearContactsSnapshot fresh = snapshot(gearA);
        assertFalse(fresh.isInitialized());
        assertEquals("Response_Initial_Sync_Required_Contact", GearContactsCodec.resumeResponse(fresh.isInitialized()));
        assertTrue(fresh.requireInitial());
        assertEquals(1, fresh.compare(Collections.singletonList(contact(1, "+15550001")), true).insert.size());
        assertFalse(snapshot(gearA).isInitialized());
    }

    @Test public void completedInitialRestoresAcrossServiceRecreationWithoutFullImport() {
        final List<GearContactsCodec.Contact> book = addressBook(1092);
        final GearContactsSnapshot first = snapshot(gearA);
        assertTrue(first.requireInitial());
        final GearContactsSnapshot.Delta transfer = first.compare(book, true);
        assertEquals(1092, transfer.insert.size());
        assertFalse(snapshot(gearA).isInitialized()); // incomplete file transfer cannot advance the baseline
        assertTrue(first.recordTransportDeliveredSnapshot(transfer)); // called only after the final file-transfer completion
        final GearContactsSnapshot reconnected = snapshot(gearA);
        assertTrue(reconnected.isInitialized());
        assertEquals(1092, reconnected.size());
        assertEquals("Response_Resume_Sync_Contact", GearContactsCodec.resumeResponse(reconnected.isInitialized()));
        assertTrue(reconnected.compare(book, false).isEmpty());
        final String persisted = gearA.getAll().toString();
        assertFalse(persisted.contains("Synthetic Person"));
        assertFalse(persisted.contains("+1555"));
    }

    @Test public void restoredBaselineProducesOnlyOneInsertUpdateOrDelete() {
        final GearContactsSnapshot initial = snapshot(gearA);
        final List<GearContactsCodec.Contact> original = Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002"));
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(original, true)));

        GearContactsSnapshot reconnect = snapshot(gearA);
        GearContactsSnapshot.Delta delta = reconnect.compare(Arrays.asList(original.get(0), original.get(1), contact(3, "+15550003")), false);
        assertEquals(1, delta.insert.size()); assertTrue(delta.update.isEmpty()); assertTrue(delta.delete.isEmpty());
        assertTrue(reconnect.markPendingVerification());
        assertTrue(snapshot(gearA).hasPendingVerification());
        // This synthetic checkpoint checks diff/persistence only; it is not an importer ACK.
        assertTrue(reconnect.recordTransportDeliveredSnapshot(delta));

        reconnect = snapshot(gearA);
        delta = reconnect.compare(Arrays.asList(contact(1, "+15550999"), original.get(1), contact(3, "+15550003")), false);
        assertTrue(delta.insert.isEmpty()); assertEquals(1, delta.update.size()); assertTrue(delta.delete.isEmpty());
        assertTrue(reconnect.markPendingVerification());
        assertTrue(reconnect.recordTransportDeliveredSnapshot(delta));

        reconnect = snapshot(gearA);
        delta = reconnect.compare(Arrays.asList(contact(1, "+15550999"), contact(3, "+15550003")), false);
        assertTrue(delta.insert.isEmpty()); assertTrue(delta.update.isEmpty()); assertEquals(Collections.singletonList(2L), delta.delete);
        assertTrue(reconnect.markPendingVerification());
        assertTrue(reconnect.recordTransportDeliveredSnapshot(delta));
        assertTrue(snapshot(gearA).compare(Arrays.asList(contact(1, "+15550999"), contact(3, "+15550003")), false).isEmpty());
    }

    @Test public void failedOrCancelledDeltaKeepsPreviousPersistedBaseline() {
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final GearContactsSnapshot inFlight = snapshot(gearA);
        assertEquals(1, inFlight.compare(Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false).insert.size());
        assertTrue(inFlight.markPendingVerification());
        // No file completion: the transport baseline must remain at the previous rows.
        final GearContactsSnapshot restored = snapshot(gearA);
        assertTrue(restored.isInitialized());
        assertTrue(restored.hasPendingVerification());
        assertEquals(1, restored.size());
        assertEquals(1, restored.compare(Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false).insert.size());
    }

    @Test public void deliveredInsertCheckpointsOnlyThatRawIdAndSurvivesReconnect() {
        final GearContactsSnapshot original = snapshot(gearA);
        assertTrue(original.requireInitial());
        assertTrue(original.recordTransportDeliveredSnapshot(original.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final GearContactsSnapshot sending = snapshot(gearA);
        final GearContactsSnapshot.Delta addition = sending.compare(
                Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false);
        assertTrue(addition.isInsertOnly());
        assertTrue(sending.markPendingVerification());
        assertTrue(sending.recordTransportDeliveredInserts(addition));
        final GearContactsSnapshot reconnected = snapshot(gearA);
        assertTrue(reconnected.isInitialized());
        assertFalse(reconnected.hasPendingVerification());
        assertEquals(2, reconnected.size());
        assertTrue(reconnected.compare(Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false).isEmpty());
        // A later phone edit is an update, even if an unseen Gear importer rejection left the I absent.
        assertEquals(1, reconnected.compare(Arrays.asList(contact(1, "+15550001"),
                contact(2, "+15550999")), false).update.size());
        assertFalse(gearA.getAll().toString().contains("+1555"));
    }

    @Test public void interruptedInsertRetainsMarkerAndCannotBeBlindlyRetried() {
        final GearContactsSnapshot original = snapshot(gearA);
        assertTrue(original.requireInitial());
        assertTrue(original.recordTransportDeliveredSnapshot(original.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final GearContactsSnapshot sending = snapshot(gearA);
        final GearContactsSnapshot.Delta addition = sending.compare(
                Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false);
        assertTrue(sending.markPendingVerification());
        final GearContactsSnapshot reconnected = snapshot(gearA);
        assertTrue(reconnected.hasPendingVerification());
        assertEquals(1, reconnected.size());
        assertEquals(1, reconnected.compare(Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false).insert.size());
        assertEquals(1, snapshot(gearA).size());
        assertTrue(snapshot(gearA).hasPendingVerification());
    }

    @Test public void explicitGearInitialRequestSurvivesInterruptionWithoutLosingOldBaseline() {
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        assertTrue(initial.requireInitial()); // watch explicitly requested a new full sync
        final GearContactsSnapshot interrupted = snapshot(gearA);
        assertFalse(interrupted.isInitialized());
        assertEquals(1, interrupted.size()); // old transport rows are retained, not advanced
        assertEquals("Response_Initial_Sync_Required_Contact", GearContactsCodec.resumeResponse(interrupted.isInitialized()));
        assertTrue(interrupted.recordTransportDeliveredSnapshot(interrupted.compare(Collections.singletonList(contact(1, "+15550999")), true)));
        assertTrue(snapshot(gearA).isInitialized());
    }

    @Test public void rejectedDeleteRetainsOldIdsAndPausesRetriesAcrossReconnect() {
        final GearContactsSnapshot initial = snapshot(gearA);
        final GearContactsCodec.Contact kept = contact(1, "+15550001");
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Arrays.asList(kept, contact(2, "+15550002")), true)));
        final GearContactsSnapshot attempted = snapshot(gearA);
        final GearContactsSnapshot.Delta deletion = attempted.compare(Collections.singletonList(kept), false);
        assertEquals(Collections.singletonList(2L), deletion.delete);
        assertTrue(attempted.markPendingVerification());
        // File rejection must not be treated as application success.
        final GearContactsSnapshot reconnected = snapshot(gearA);
        assertTrue(reconnected.isInitialized());
        assertTrue(reconnected.hasPendingVerification());
        assertEquals(2, reconnected.size());
        assertEquals(Collections.singletonList(2L), reconnected.compare(Collections.singletonList(kept), false).delete);
    }

    @Test public void mixedScanDebugDeleteLeavesPersistenceAndAutomaticPauseUnchanged() throws Exception {
        final GearContactsSnapshot original = snapshot(gearA);
        assertTrue(original.requireInitial());
        final List<GearContactsCodec.Contact> baseline = addressBook(7);
        Collections.reverse(baseline); // deletion order must not decide which ID is sent
        assertTrue(original.recordTransportDeliveredSnapshot(original.compare(baseline, true)));
        assertTrue(original.markPendingVerification());
        final String before = gearA.getString("samsung_gear_contacts_acknowledged_snapshot_v1", null);

        final GearContactsOneShotGate gate = new GearContactsOneShotGate();
        assertTrue(original.hasPendingVerification());
        assertFalse(gate.isArmed()); // scheduleScan's normal pause guard remains closed
        assertTrue(gate.arm());
        assertTrue(gate.consume());
        final List<GearContactsCodec.Contact> current = Arrays.asList(contact(1, "+15550991"), contact(2, "+15550992"),
                contact(8, "+15550008"), contact(9, "+15550009"), contact(10, "+15550010"));
        final GearContactsSnapshot.Delta attempted = original.compare(current, false);
        assertEquals(3, attempted.insert.size());
        assertEquals(2, attempted.update.size());
        assertEquals(5, attempted.delete.size());
        assertEquals(5, GearContactsOneShotGate.retainDeletes(attempted));
        assertTrue(attempted.insert.isEmpty());
        assertTrue(attempted.update.isEmpty());
        assertEquals(Arrays.asList(3L, 4L, 5L, 6L, 7L), attempted.delete);
        final JSONObject payload = GearContactsCodec.change("delete", Collections.emptyList(), attempted.delete);
        assertEquals(1, payload.length());
        final JSONObject deletion = payload.getJSONObject("delete");
        assertEquals(5, deletion.getInt("number_of_raw_contacts"));
        assertEquals(5, deletion.getJSONArray("raw_contact_list").length());
        for (int i = 0; i < 5; i++) {
            final JSONObject row = deletion.getJSONArray("raw_contact_list").getJSONObject(i);
            assertEquals(1, row.length());
            assertEquals(1, row.getJSONObject("raw_contact").length());
            assertEquals(i + 3L, row.getJSONObject("raw_contact").getLong("id"));
        }
        // Generic file delivery has no importer result; this debug action keeps the baseline.
        assertEquals(before, gearA.getString("samsung_gear_contacts_acknowledged_snapshot_v1", null));
        gate.cancel(); // interruption also leaves the token spent and baseline untouched
        final GearContactsSnapshot reconnected = snapshot(gearA);
        assertTrue(reconnected.hasPendingVerification());
        assertEquals(7, reconnected.size());
        assertEquals(3, reconnected.compare(current, false).insert.size());
        assertEquals(2, reconnected.compare(current, false).update.size());
        assertEquals(5, reconnected.compare(current, false).delete.size());
        assertFalse(gate.isArmed());
        assertFalse(gate.consume());
        assertFalse(new GearContactsOneShotGate().isArmed());
        assertEquals(before, gearA.getString("samsung_gear_contacts_acknowledged_snapshot_v1", null));
    }

    @Test public void olderTransportOnlySnapshotPausesDeltasButStillResumesUnchanged() throws Exception {
        final String oldKey = "samsung_gear_contacts_acknowledged_snapshot_v1";
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final String saved = gearA.getString(oldKey, null);
        final JSONObject legacy = new JSONObject(saved);
        legacy.remove("pendingVerification");
        gearA.edit().putString(oldKey, legacy.toString()).commit();
        final GearContactsSnapshot migrated = snapshot(gearA);
        assertTrue(migrated.isInitialized());
        assertTrue(migrated.hasPendingVerification());
        assertEquals("Response_Resume_Sync_Contact", GearContactsCodec.resumeResponse(migrated.isInitialized()));
        assertTrue(migrated.compare(Collections.singletonList(contact(1, "+15550001")), false).isEmpty());
    }

    @Test public void failedPersistenceCommitCannotAdvanceMemoryOrDurableBaseline() {
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final GearContactsSnapshot failing = new GearContactsSnapshot(new GearContactsSnapshotStore(gearA) {
            @Override boolean save(final GearContactsSnapshotStore.State state) { return false; }
        });
        assertFalse(failing.recordTransportDeliveredSnapshot(failing.compare(Arrays.asList(contact(1, "+15550001"), contact(2, "+15550002")), false)));
        assertEquals(1, failing.size());
        assertEquals(1, snapshot(gearA).size());
    }

    @Test public void failedPendingMarkerCommitMustBlockDeltaSend() {
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final GearContactsSnapshot failing = new GearContactsSnapshot(new GearContactsSnapshotStore(gearA) {
            @Override boolean save(final GearContactsSnapshotStore.State state) { return false; }
        });
        assertFalse(failing.markPendingVerification());
        assertFalse(failing.hasPendingVerification());
        assertFalse(snapshot(gearA).hasPendingVerification());
        assertEquals(1, snapshot(gearA).size());
    }

    @Test public void failedResetMarkerCommitDoesNotClaimGearInitialWasRecorded() {
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.recordTransportDeliveredSnapshot(initial.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        final GearContactsSnapshot failing = new GearContactsSnapshot(new GearContactsSnapshotStore(gearA) {
            @Override boolean save(final GearContactsSnapshotStore.State state) { return false; }
        });
        assertFalse(failing.requireInitial());
        assertTrue(failing.isInitialized());
        assertTrue(snapshot(gearA).isInitialized());
    }

    @Test public void emptyInitialWithoutTransferMustNotBeAcknowledged() {
        final GearContactsSnapshot initial = snapshot(gearA);
        assertTrue(initial.requireInitial());
        assertTrue(initial.compare(Collections.emptyList(), true).isEmpty());
        // The service cannot checkpoint: there is no file-transfer completion.
        final GearContactsSnapshot restored = snapshot(gearA);
        assertFalse(restored.isInitialized());
        assertEquals(0, restored.size());
    }

    @Test public void corruptOrUnsupportedSavedStateRequiresInitial() {
        final String key = "samsung_gear_contacts_acknowledged_snapshot_v1";
        gearA.edit().putString(key, "{\"version\":2,\"acknowledged\":true,\"initialRequired\":false,\"rows\":[]}").commit();
        assertFalse(snapshot(gearA).isInitialized());
        gearA.edit().putString(key, "{\"version\":1,\"acknowledged\":true,\"initialRequired\":false,\"rows\":[[1,\"not-a-hash\"]]}").commit();
        assertFalse(snapshot(gearA).isInitialized());
    }

    @Test public void baselineIsIsolatedByGadgetbridgeDevicePreferences() {
        final GearContactsSnapshot firstGear = snapshot(gearA);
        assertTrue(firstGear.requireInitial());
        assertTrue(firstGear.recordTransportDeliveredSnapshot(firstGear.compare(Collections.singletonList(contact(1, "+15550001")), true)));
        assertTrue(firstGear.markPendingVerification());
        assertTrue(snapshot(gearA).isInitialized());
        assertTrue(snapshot(gearA).hasPendingVerification());
        assertFalse(snapshot(gearB).isInitialized());
        assertFalse(snapshot(gearB).hasPendingVerification());
        assertEquals(1, snapshot(gearB).compare(Collections.singletonList(contact(1, "+15550001")), true).insert.size());
    }
}
