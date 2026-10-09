package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Structural persistence only: synthetic fixture data, no watch or semantic ACK. */
@RunWith(RobolectricTestRunner.class)
public class GearContactsLedgerTest {
    private static final String BASE = "samsung_gear_contacts_acknowledged_snapshot_v1";
    private static final String LEDGER = "samsung_gear_contacts_transport_ledger_v1";
    private static final String OLD = "+15550001001", NEW = "+15550002002";
    private SharedPreferences prefs, other;
    private String baseline;

    @Before public void setup() {
        final Context context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("ledger-device-a", Context.MODE_PRIVATE);
        other = context.getSharedPreferences("ledger-device-b", Context.MODE_PRIVATE);
        prefs.edit().clear().commit(); other.edit().clear().commit();
        final GearContactsSnapshot snapshot = snapshot();
        assertTrue(snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), true)));
        baseline = prefs.getString(BASE, null);
    }
    private GearContactsSnapshot snapshot() { return new GearContactsSnapshot(new GearContactsSnapshotStore(prefs)); }
    private static GearContactsCodec.Contact contact(long id, String number) {
        return new GearContactsCodec.Contact(id, id + 1000, id, "Synthetic Person", null, null, false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }
    private static Map<Long, String> sent(long id, String number) {
        return Collections.singletonMap(id, GearContactsSnapshot.fingerprint(contact(id, number)));
    }
    private void delivered(long id, String number) {
        final GearContactsSnapshot state = snapshot();
        assertTrue(state.beginTransport(Collections.singleton(id)));
        assertTrue(state.recordTransportDelivered(sent(id, number)));
    }

    @Test public void deliveredHashAndPerIdUncertaintySurviveRecreationWithoutChangingLegacyBytes() throws Exception {
        delivered(1, NEW);
        final GearContactsSnapshot state = snapshot();
        assertEquals(baseline, prefs.getString(BASE, null));
        assertTrue(state.isTransportUncertain(1)); assertFalse(state.isBlocked(1)); assertFalse(state.hasPendingVerification());
        assertEquals(sent(1, NEW).get(1L), state.lastTransportDeliveredFingerprint(1));
        assertTrue(state.compare(Arrays.asList(contact(1, NEW), contact(2, OLD)), false).isEmpty());
        final JSONObject encoded = new JSONObject(prefs.getString(LEDGER, null));
        assertTrue(encoded.getJSONArray("rows").getJSONArray(0).getBoolean(2));
        assertEquals(0, encoded.getJSONArray("blocked").length());
    }
    @Test public void laterEditIsComparedAgainstDeliveredHashAndCanReplaceItAgain() {
        delivered(1, NEW);
        final GearContactsSnapshot state = snapshot();
        assertEquals(1, state.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), false).update.size());
        assertTrue(state.beginTransport(Collections.singleton(1L)));
        assertTrue(state.recordTransportDelivered(sent(1, OLD)));
        assertEquals(sent(1, OLD).get(1L), snapshot().lastTransportDeliveredFingerprint(1));
        assertTrue(snapshot().compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), false).isEmpty());
    }
    @Test public void incompleteMarkerSurvivesRecreationAndBlocksOnlyThatId() {
        assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final GearContactsSnapshot state = snapshot();
        assertTrue(state.isBlocked(1)); assertFalse(state.isBlocked(2)); assertFalse(state.hasPendingVerification());
        assertEquals(sent(1, OLD).get(1L), state.lastTransportDeliveredFingerprint(1));
        final GearContactsSnapshot.Delta delta = state.compare(Arrays.asList(contact(1, NEW), contact(2, NEW), contact(3, NEW)), false);
        state.excludeBlocked(delta);
        assertEquals(1, delta.insert.size()); assertEquals(3, delta.insert.get(0).rawId);
        assertEquals(1, delta.update.size()); assertEquals(2, delta.update.get(0).rawId);
        assertFalse(state.beginTransport(Collections.singleton(1L)));
        assertTrue(state.beginTransport(Collections.singleton(2L)));
    }
    @Test public void interruptedLaterEditRetainsPreviousDeliveredHashAndUncertainty() {
        delivered(1, NEW); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final GearContactsSnapshot state = snapshot();
        assertTrue(state.isBlocked(1)); assertTrue(state.isTransportUncertain(1));
        assertEquals(sent(1, NEW).get(1L), state.lastTransportDeliveredFingerprint(1));
        assertFalse(state.hasPendingVerification());
    }
    @Test public void insertDeliverySuppressesDuplicateAndLaterChangesAreModify() {
        delivered(3, NEW);
        GearContactsSnapshot.Delta delta = snapshot().compare(Arrays.asList(contact(1, OLD), contact(2, OLD), contact(3, NEW)), false);
        assertTrue(delta.isEmpty());
        delta = snapshot().compare(Arrays.asList(contact(1, OLD), contact(2, OLD), contact(3, OLD)), false);
        assertTrue(delta.insert.isEmpty()); assertEquals(3, delta.update.get(0).rawId);
    }
    @Test public void deviceAddressPreferencesAreIsolated() {
        delivered(1, NEW);
        final GearContactsSnapshot state = new GearContactsSnapshot(new GearContactsSnapshotStore(other));
        assertFalse(state.isInitialized()); assertFalse(state.isTransportUncertain(1)); assertNull(state.lastTransportDeliveredFingerprint(1));
        assertNull(other.getString(LEDGER, null));
    }
    @Test public void existingLegacyUncertaintyIsNotClearedAndDoesNotBlockOtherIds() throws Exception {
        delivered(1, NEW); assertTrue(snapshot().markPendingVerification());
        final GearContactsSnapshot state = snapshot();
        assertTrue(state.hasPendingVerification()); assertTrue(state.isTransportUncertain(1));
        assertTrue(state.beginTransport(Collections.singleton(2L)));
        assertTrue(new JSONObject(prefs.getString(BASE, null)).getBoolean("pendingVerification"));
    }
    @Test public void initialCheckpointResetsLedgerOnlyWhenFullSnapshotCompleted() {
        delivered(1, NEW); final GearContactsSnapshot state = snapshot();
        assertTrue(state.requireInitial()); assertNotNull(prefs.getString(LEDGER, null));
        assertTrue(state.recordTransportDeliveredSnapshot(state.compare(Arrays.asList(contact(1, NEW), contact(2, OLD)), true)));
        assertNull(prefs.getString(LEDGER, null)); assertFalse(snapshot().isTransportUncertain(1));
        assertTrue(snapshot().isInitialized());
    }
    @Test public void disappearedPhoneRowDoesNotAlterLedgerOrClaimDeleteSuccess() {
        delivered(1, NEW); final Map<String, ?> retained = new LinkedHashMap<>(prefs.getAll());
        assertEquals(Collections.singletonList(1L), snapshot().compare(Collections.singletonList(contact(2, OLD)), false).delete);
        assertEquals(retained, prefs.getAll()); assertTrue(snapshot().isTransportUncertain(1));
    }
    @Test public void persistedLedgerHasOnlyIdsHashesAndStructuralFlags() {
        delivered(1, NEW); assertTrue(snapshot().beginTransport(Collections.singleton(2L)));
        final String encoded = prefs.getString(LEDGER, null);
        assertFalse(encoded.contains("Synthetic")); assertFalse(encoded.contains("+1555"));
        assertFalse(encoded.contains("number")); assertFalse(encoded.contains("email"));
        assertFalse(encoded.contains("acknowledged")); assertFalse(encoded.contains("applied"));
    }
    @Test public void corruptLedgerCannotPermitBlindReplay() {
        prefs.edit().putString(LEDGER, "{\"version\":1,\"rows\":[[1,\"bad\",true]],\"blocked\":[]}").commit();
        assertTrue(snapshot().hasPendingVerification()); assertTrue(snapshot().hasBlockedTransfers());
        assertFalse(snapshot().beginTransport(Collections.singleton(1L)));
    }
    @Test public void completingAnUnmarkedIdCannotAdvanceLedger() {
        final GearContactsSnapshot state = snapshot();
        assertFalse(state.recordTransportDelivered(sent(1, NEW)));
        assertNull(prefs.getString(LEDGER, null)); assertEquals(sent(1, OLD).get(1L), state.lastTransportDeliveredFingerprint(1));
    }
    @Test public void failedPersistenceCannotPublishAttemptOrDeliveredHash() {
        final SharedPreferences fail = mock(SharedPreferences.class, org.mockito.AdditionalAnswers.delegatesTo(prefs));
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(fail.edit()).thenReturn(editor); when(editor.commit()).thenReturn(false);
        GearContactsSnapshot state = new GearContactsSnapshot(new GearContactsSnapshotStore(fail));
        assertFalse(state.beginTransport(Collections.singleton(1L))); assertFalse(state.isBlocked(1));
        assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        state = new GearContactsSnapshot(new GearContactsSnapshotStore(fail));
        assertFalse(state.recordTransportDelivered(sent(1, NEW)));
        assertTrue(state.isBlocked(1)); assertEquals(sent(1, OLD).get(1L), state.lastTransportDeliveredFingerprint(1));
        assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().isTransportUncertain(1));
    }
    @Test public void durablePrepareTouchesOnlyTargetLedgerAndPreservesLegacyAndUnrelatedRows() throws Exception {
        delivered(3, NEW); assertTrue(snapshot().beginTransport(Collections.singleton(2L))); assertTrue(snapshot().markPendingVerification());
        final String legacy = prefs.getString(BASE, null);
        final GearContactsSnapshot state = snapshot(); assertTrue(state.preparePersistentTest(contact(9120, OLD)));
        assertEquals(legacy, prefs.getString(BASE, null)); assertTrue(state.hasPendingVerification());
        assertEquals(sent(3, NEW).get(3L), snapshot().lastTransportDeliveredFingerprint(3)); assertTrue(snapshot().isBlocked(2));
        assertEquals(sent(9120, OLD).get(9120L), snapshot().lastTransportDeliveredFingerprint(9120));
        assertEquals(9120, new JSONObject(prefs.getString(LEDGER, null)).getLong("debugModifyTestRawId"));
        assertFalse(prefs.getAll().toString().contains("+1555")); assertFalse(prefs.getAll().toString().contains("Synthetic"));
    }
    @Test public void durableScopeRestrictsExplicitTrialWhileIndependentProductionIdsRemainEligible() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
        final GearContactsSnapshot recreated = snapshot(); assertEquals(9120, recreated.persistentTestRawId());
        assertTrue(recreated.comparePersistentTest(Collections.singletonList(contact(9120, OLD)), 9120).isEmpty());
        final GearContactsSnapshot.Delta delta = recreated.comparePersistentTest(Arrays.asList(contact(1, NEW), contact(3, NEW), contact(9120, NEW)), 9120);
        assertEquals(1, delta.update.size()); assertEquals(9120, delta.update.get(0).rawId); assertTrue(delta.insert.isEmpty()); assertTrue(delta.delete.isEmpty());
        assertTrue(recreated.beginTransport(Collections.singleton(1L))); assertFalse(recreated.beginPersistentTestTransport(Collections.singleton(1L)));
        assertFalse(recreated.beginPersistentTestTransport(new java.util.HashSet<>(Arrays.asList(9120L, 1L))));
        assertTrue(recreated.beginPersistentTestTransport(Collections.singleton(9120L)));
    }
    @Test public void durableTestCheckpointUsesRealLedgerAndSecondReconstructionSeesNewHash() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
        final GearContactsSnapshot recreated = snapshot(); assertTrue(recreated.beginPersistentTestTransport(Collections.singleton(9120L)));
        assertTrue(recreated.recordTransportDelivered(sent(9120, NEW)));
        final GearContactsSnapshot second = snapshot(); assertEquals(9120, second.persistentTestRawId());
        assertEquals(sent(9120, NEW).get(9120L), second.lastTransportDeliveredFingerprint(9120)); assertFalse(second.isBlocked(9120));
        assertTrue(second.comparePersistentTest(Collections.singletonList(contact(9120, NEW)), 9120).isEmpty());
        assertEquals(1, second.comparePersistentTest(Collections.singletonList(contact(9120, OLD)), 9120).update.size());
        assertTrue(second.hasPendingVerification());
    }
    @Test public void incompleteDurableTestRetainsOldHashAndBlockedIdAcrossStoreReconstruction() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
        assertTrue(snapshot().beginPersistentTestTransport(Collections.singleton(9120L)));
        final GearContactsSnapshot recreated = snapshot(); assertEquals(sent(9120, OLD).get(9120L), recreated.lastTransportDeliveredFingerprint(9120));
        assertTrue(recreated.isBlocked(9120)); assertFalse(recreated.beginPersistentTestTransport(Collections.singleton(9120L)));
        assertEquals(9120, recreated.persistentTestRawId()); assertTrue(recreated.hasPendingVerification());
    }
    @Test public void durableClearRemovesOnlyAuthorizationAndKeepsAllDeliveredHashesAndBlocks() throws Exception {
        delivered(3, NEW); assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
        assertTrue(snapshot().beginPersistentTestTransport(Collections.singleton(9120L))); final String legacy = prefs.getString(BASE, null);
        assertFalse(snapshot().clearPersistentTest(1)); assertTrue(snapshot().clearPersistentTest(9120));
        final GearContactsSnapshot restored = snapshot(); assertEquals(0, restored.persistentTestRawId()); assertTrue(restored.isBlocked(9120));
        assertEquals(sent(3, NEW).get(3L), restored.lastTransportDeliveredFingerprint(3));
        assertEquals(sent(9120, OLD).get(9120L), restored.lastTransportDeliveredFingerprint(9120));
        assertEquals(legacy, prefs.getString(BASE, null)); assertFalse(new JSONObject(prefs.getString(LEDGER, null)).has("debugModifyTestRawId"));
        assertFalse(restored.beginPersistentTestTransport(Collections.singleton(9120L)));
        assertFalse(restored.preparePersistentTest(contact(9120, OLD))); // CLEAR never unlocks blind replay.
    }
    @Test public void durablePrepareRejectsInvalidIdsUnpausedStateDuplicatesAndDifferingExistingHash() {
        assertFalse(snapshot().preparePersistentTest(contact(9120, OLD))); assertTrue(snapshot().markPendingVerification());
        assertFalse(snapshot().preparePersistentTest(contact(0, OLD))); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
        assertFalse(snapshot().preparePersistentTest(contact(9120, NEW))); assertTrue(snapshot().clearPersistentTest(9120));
        assertFalse(snapshot().preparePersistentTest(contact(9120, NEW))); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
    }
    @Test public void durableModifyScopeAcceptsAnyPositiveSuppliedIdAndRetainsOnlyItsAuthorization() {
        assertTrue(snapshot().markPendingVerification());
        final String legacy = prefs.getString(BASE, null);
        assertTrue(snapshot().preparePersistentTest(contact(70001, OLD)));
        assertEquals(70001, snapshot().persistentTestRawId());
        assertEquals(sent(70001, OLD).get(70001L), snapshot().lastTransportDeliveredFingerprint(70001));
        assertFalse(snapshot().clearPersistentTest(70002));
        assertTrue(snapshot().clearPersistentTest(70001));
        assertEquals(0, snapshot().persistentTestRawId());
        assertEquals(legacy, prefs.getString(BASE, null));
        assertEquals(sent(70001, OLD).get(70001L), snapshot().lastTransportDeliveredFingerprint(70001));
    }

    @Test public void negativeDurableModifyScopeFailsClosed() throws Exception {
        delivered(3, OLD);
        final JSONObject encoded = new JSONObject(prefs.getString(LEDGER, null)).put("debugModifyTestRawId", -1);
        prefs.edit().putString(LEDGER, encoded.toString()).commit();
        assertTrue(snapshot().hasInvalidLedger());
        assertEquals(0, snapshot().persistentTestRawId());
    }

    @Test public void durableScopeIsIsolatedByDevicePreferences() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().preparePersistentTest(contact(9120, OLD)));
        final GearContactsSnapshot anotherDevice = new GearContactsSnapshot(new GearContactsSnapshotStore(other));
        assertEquals(0, anotherDevice.persistentTestRawId()); assertFalse(anotherDevice.beginPersistentTestTransport(Collections.singleton(9120L)));
        assertNull(other.getString(LEDGER, null));
    }
    @Test public void invalidOrUnbackedTestScopeCannotAuthorizeProduction() {
        prefs.edit().putString(LEDGER, "{\"version\":1,\"rows\":[],\"blocked\":[],\"debugModifyTestRawId\":9120}").commit();
        assertTrue(snapshot().hasPendingVerification()); assertEquals(0, snapshot().persistentTestRawId());
        assertFalse(snapshot().beginPersistentTestTransport(Collections.singleton(9120L)));
    }
    @Test public void failedDurablePreparationDoesNotPublishScopeOrWriteLegacySnapshot() {
        assertTrue(snapshot().markPendingVerification()); final String legacy = prefs.getString(BASE, null);
        final SharedPreferences fail = mock(SharedPreferences.class, org.mockito.AdditionalAnswers.delegatesTo(prefs));
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(fail.edit()).thenReturn(editor); when(editor.commit()).thenReturn(false);
        final GearContactsSnapshot state = new GearContactsSnapshot(new GearContactsSnapshotStore(fail));
        assertFalse(state.preparePersistentTest(contact(9120, OLD))); assertEquals(0, state.persistentTestRawId());
        assertNull(prefs.getString(LEDGER, null)); assertEquals(legacy, prefs.getString(BASE, null));
    }

    @Test public void uncertainLegacyDeleteMigratesOnlyCompletedIdAndPreservesLegacyBytes() throws Exception {
        assertTrue(snapshot().markPendingVerification()); final String legacy = prefs.getString(BASE, null);
        final GearContactsSnapshot state = snapshot(); assertTrue(state.beginTransport(Collections.singleton(1L)));
        assertFalse(state.isTransportUncertain(1)); assertTrue(state.recordTransportDeleted(Collections.singleton(1L)));
        final GearContactsSnapshot recreated = snapshot();
        assertTrue(recreated.isTransportDeleted(1)); assertFalse(recreated.isBlocked(1)); assertFalse(recreated.isTransportUncertain(2));
        assertEquals(sent(1, OLD).get(1L), recreated.lastTransportDeliveredFingerprint(1));
        assertTrue(recreated.compare(Collections.singletonList(contact(2, OLD)), false).isEmpty());
        assertEquals(legacy, prefs.getString(BASE, null)); assertTrue(recreated.hasPendingVerification());
        assertEquals(1, new JSONObject(prefs.getString(LEDGER, null)).getJSONArray("rows").length());
    }

    @Test public void uncertainLegacyNewInsertMarksAttemptWithoutSeedingAllOldRows() throws Exception {
        assertTrue(snapshot().markPendingVerification()); final String legacy = prefs.getString(BASE, null);
        assertTrue(snapshot().beginTransport(Collections.singleton(3L)));
        assertEquals(0, new JSONObject(prefs.getString(LEDGER, null)).getJSONArray("rows").length());
        assertTrue(snapshot().recordTransportDelivered(sent(3, NEW)));
        assertTrue(snapshot().isTransportUncertain(3)); assertFalse(snapshot().isTransportUncertain(1));
        assertEquals(legacy, prefs.getString(BASE, null)); assertTrue(snapshot().hasPendingVerification());
    }

    @Test public void uncertainLegacyBlockedIdsRemainAuthoritativeForOtherEligibleDeltas() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final GearContactsSnapshot recreated = snapshot();
        final GearContactsSnapshot.Delta delta = recreated.compare(Arrays.asList(contact(1, NEW), contact(3, NEW)), false);
        recreated.excludeBlocked(delta); assertTrue(delta.update.isEmpty()); assertEquals(3, delta.insert.get(0).rawId);
        assertEquals(0, recreated.retainKnownDeletes(delta, true)); assertEquals(Collections.singletonList(2L), delta.delete);
        assertFalse(recreated.beginTransport(Collections.singleton(1L))); assertTrue(recreated.beginTransport(Collections.singleton(2L)));
    }

    @Test public void uncertainLegacyFailedDeleteCheckpointRetainsSpecificBlockWithoutSeedingRow() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final SharedPreferences fail = mock(SharedPreferences.class, org.mockito.AdditionalAnswers.delegatesTo(prefs));
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(fail.edit()).thenReturn(editor); when(editor.commit()).thenReturn(false);
        final GearContactsSnapshot state = new GearContactsSnapshot(new GearContactsSnapshotStore(fail));
        assertFalse(state.recordTransportDeleted(Collections.singleton(1L)));
        assertTrue(snapshot().isBlocked(1)); assertFalse(snapshot().isTransportDeleted(1)); assertFalse(snapshot().isTransportUncertain(1));
        assertEquals(sent(1, OLD).get(1L), snapshot().lastTransportDeliveredFingerprint(1));
    }

    @Test public void unknownRawIdCannotGainDeletionTombstoneEvenWithAttemptMarker() {
        assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().beginTransport(Collections.singleton(77L)));
        assertFalse(snapshot().recordTransportDeleted(Collections.singleton(77L)));
        assertTrue(snapshot().isBlocked(77)); assertFalse(snapshot().isTransportDeleted(77));
    }

    @Test public void uncertainLegacyTombstoneUsesOnlyIdsHashesAndTransportFlags() {
        assertTrue(snapshot().markPendingVerification()); deleted(1);
        final String encoded = prefs.getString(LEDGER, null);
        assertFalse(encoded.contains("Synthetic")); assertFalse(encoded.contains(OLD)); assertFalse(encoded.contains(NEW));
        assertFalse(encoded.contains("acknowledged")); assertFalse(encoded.contains("applied")); assertFalse(encoded.contains("verified"));
        assertTrue(snapshot().hasPendingVerification());
    }

    @Test public void reconstructionWithoutPayloadAttemptRetainsHashAndRetryEligibility() {
        delivered(1, NEW);
        final GearContactsSnapshot recreated = snapshot();
        assertEquals("NONE", recreated.incompleteStage(1)); assertFalse(recreated.isBlocked(1));
        assertEquals(sent(1, NEW).get(1L), recreated.lastTransportDeliveredFingerprint(1));
        assertEquals(1, recreated.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), false).update.size());
    }

    @Test public void payloadStageIsDurableStructuralStateAndLegacyBytesAreUnchanged() throws Exception {
        assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final GearContactsSnapshot recreated = snapshot();
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", recreated.incompleteStage(1));
        final JSONObject encoded = new JSONObject(prefs.getString(LEDGER, null));
        assertEquals(3, encoded.getInt("version")); assertEquals(1, encoded.getJSONArray("payloadStarted").getLong(0));
        assertEquals(baseline, prefs.getString(BASE, null));
        assertFalse(encoded.toString().contains("Synthetic")); assertFalse(encoded.toString().contains("+1555"));
    }

    @Test public void insertCheckpointRemovesOnlyItsPayloadStage() {
        final GearContactsSnapshot state = snapshot(); assertTrue(state.beginTransport(new java.util.LinkedHashSet<>(Arrays.asList(1L, 2L))));
        assertTrue(state.recordTransportDelivered(sent(1, NEW)));
        assertEquals("NONE", snapshot().incompleteStage(1)); assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(2));
    }

    @Test public void deletionCheckpointClearsPayloadStageAndRetainsTombstone() {
        delivered(3, NEW); deleted(3);
        assertEquals("NONE", snapshot().incompleteStage(3)); assertTrue(snapshot().isTransportDeleted(3));
    }

    private void legacyBlocks(long... ids) throws Exception {
        final org.json.JSONArray blocked = new org.json.JSONArray(); for (long id : ids) blocked.put(id);
        final String saved = prefs.getString(LEDGER, null);
        final JSONObject legacy = saved == null ? new JSONObject().put("rows", new org.json.JSONArray()).put("deleted", new org.json.JSONArray()) : new JSONObject(saved);
        legacy.put("version", 2).put("blocked", blocked); legacy.remove("payloadStarted");
        prefs.edit().putString(LEDGER, legacy.toString()).commit();
    }

    @Test public void legacyUnknownBlocksSurviveUpgradeAlongsideNewPayloadStages() throws Exception {
        legacyBlocks(1); final GearContactsSnapshot state = snapshot();
        assertEquals("LEGACY_UNKNOWN", state.incompleteStage(1)); assertTrue(state.beginTransport(Collections.singleton(2L)));
        assertEquals("LEGACY_UNKNOWN", snapshot().incompleteStage(1)); assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(2));
        final GearContactsSnapshot.Delta delta = snapshot().compare(Arrays.asList(contact(1, NEW), contact(2, NEW)), false);
        snapshot().excludeBlocked(delta); assertTrue(delta.isEmpty());
    }

    @Test public void payloadStageWithoutBlockedIdFailsClosed() throws Exception {
        assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final JSONObject root = new JSONObject(prefs.getString(LEDGER, null)).put("blocked", new org.json.JSONArray());
        prefs.edit().putString(LEDGER, root.toString()).commit(); assertTrue(snapshot().hasInvalidLedger());
    }

    @Test public void missingV3StageInformationFailsClosed() throws Exception {
        assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final JSONObject root = new JSONObject(prefs.getString(LEDGER, null)); root.remove("payloadStarted");
        prefs.edit().putString(LEDGER, root.toString()).commit(); assertTrue(snapshot().hasInvalidLedger());
    }

    @Test public void duplicatedPayloadStageFailsClosed() throws Exception {
        assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final JSONObject root = new JSONObject(prefs.getString(LEDGER, null)).put("payloadStarted", new org.json.JSONArray().put(1).put(1));
        prefs.edit().putString(LEDGER, root.toString()).commit(); assertTrue(snapshot().hasInvalidLedger());
    }

    @Test public void modifyPrepareAndClearPreserveOtherIdsPayloadStages() {
        delivered(9120, OLD); assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final GearContactsSnapshot state = snapshot(); assertTrue(state.preparePersistentTest(contact(9120, OLD)));
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1)); assertTrue(state.clearPersistentTest(9120));
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
    }

    @Test public void deletePrepareAndClearPreserveOtherIdsPayloadStages() {
        delivered(3, OLD); assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().beginTransport(Collections.singleton(1L)));
        final GearContactsSnapshot state = snapshot(); assertTrue(state.preparePersistentDeleteTest(contact(3, OLD)));
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1)); assertTrue(state.clearPersistentDeleteTest(3));
        assertEquals("PAYLOAD_MAY_HAVE_STARTED", snapshot().incompleteStage(1));
    }

    private void deleted(long id) {
        final GearContactsSnapshot state = snapshot(); assertTrue(state.beginTransport(Collections.singleton(id)));
        assertTrue(state.recordTransportDeleted(Collections.singleton(id)));
    }
    @Test public void deliveredDeletionPersistsTombstoneAndPreviousHashAndDoesNotResend() throws Exception {
        delivered(3, NEW); deleted(3);
        final GearContactsSnapshot state = snapshot(); assertTrue(state.isTransportDeleted(3)); assertTrue(state.isTransportUncertain(3));
        assertFalse(state.isBlocked(3)); assertEquals(sent(3, NEW).get(3L), state.lastTransportDeliveredFingerprint(3));
        assertTrue(state.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), false).isEmpty());
        assertEquals(baseline, prefs.getString(BASE, null)); assertFalse(state.hasPendingVerification());
        assertEquals(3, new JSONObject(prefs.getString(LEDGER, null)).getInt("version"));
        assertEquals(3, new JSONObject(prefs.getString(LEDGER, null)).getJSONArray("deleted").getLong(0));
    }
    @Test public void reappearedSameRawIdNeedsInsertEvenWithIdenticalHashAndReinsertClearsTombstone() {
        delivered(3, NEW); deleted(3);
        final GearContactsSnapshot state = snapshot();
        final GearContactsSnapshot.Delta delta = state.compare(Arrays.asList(contact(1, OLD), contact(2, OLD), contact(3, NEW)), false);
        assertEquals(1, delta.insert.size()); assertEquals(3, delta.insert.get(0).rawId); assertTrue(delta.update.isEmpty());
        assertTrue(state.beginTransport(Collections.singleton(3L))); assertTrue(state.recordTransportDelivered(sent(3, OLD)));
        assertFalse(snapshot().isTransportDeleted(3)); assertEquals(sent(3, OLD).get(3L), snapshot().lastTransportDeliveredFingerprint(3));
        assertTrue(snapshot().compare(Arrays.asList(contact(1, OLD), contact(2, OLD), contact(3, OLD)), false).isEmpty());
    }
    @Test public void interruptedDeleteKeepsPreviousFingerprintWithoutTombstoneAndBlocksReplay() {
        delivered(3, NEW); assertTrue(snapshot().beginTransport(Collections.singleton(3L)));
        final GearContactsSnapshot state = snapshot(); assertFalse(state.isTransportDeleted(3)); assertTrue(state.isBlocked(3));
        assertEquals(sent(3, NEW).get(3L), state.lastTransportDeliveredFingerprint(3));
        final GearContactsSnapshot.Delta delta = state.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), false);
        state.excludeBlocked(delta); assertTrue(delta.isEmpty()); assertFalse(state.beginTransport(Collections.singleton(3L)));
    }
    @Test public void failedDeletionCheckpointCannotPublishTombstone() {
        delivered(3, NEW); assertTrue(snapshot().beginTransport(Collections.singleton(3L)));
        final SharedPreferences fail = mock(SharedPreferences.class, org.mockito.AdditionalAnswers.delegatesTo(prefs));
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(fail.edit()).thenReturn(editor); when(editor.commit()).thenReturn(false);
        final GearContactsSnapshot state = new GearContactsSnapshot(new GearContactsSnapshotStore(fail));
        assertFalse(state.recordTransportDeleted(Collections.singleton(3L))); assertFalse(state.isTransportDeleted(3));
        assertTrue(snapshot().isBlocked(3)); assertFalse(snapshot().isTransportDeleted(3));
    }
    @Test public void knownBaselineAndLedgerIdsAtResumeAreEligibleForGenuineDeletion() {
        delivered(3, NEW); final GearContactsSnapshot state = snapshot();
        GearContactsSnapshot.Delta delta = state.compare(Collections.emptyList(), false);
        assertEquals(3, state.retainKnownDeletes(delta, false)); assertTrue(delta.delete.isEmpty());
        delta = state.compare(Collections.emptyList(), false);
        assertEquals(0, state.retainKnownDeletes(delta, true)); assertEquals(Arrays.asList(1L, 2L, 3L), delta.delete);
        assertFalse(state.recordTransportDeleted(Collections.singleton(1L))); // Still requires a durable attempt.
    }
    @Test public void oneTombstoneDoesNotBlockIndependentInsertOrModify() {
        delivered(3, NEW); deleted(3); delivered(4, NEW); delivered(1, NEW);
        final GearContactsSnapshot state = snapshot(); assertTrue(state.isTransportDeleted(3)); assertFalse(state.hasPendingVerification());
        final GearContactsSnapshot.Delta delta = state.compare(Arrays.asList(contact(1, OLD), contact(2, OLD), contact(4, NEW), contact(5, NEW)), false);
        assertEquals(1, delta.update.size()); assertEquals(1, delta.insert.size()); assertTrue(delta.delete.isEmpty());
    }
    @Test public void legacyV1LedgerMigratesWithoutDroppingFingerprintsBlocksOrModifyScope() throws Exception {
        assertTrue(snapshot().markPendingVerification());
        final JSONObject v1 = new JSONObject().put("version", 1).put("rows", new org.json.JSONArray().put(new org.json.JSONArray().put(9120).put(sent(9120, OLD).get(9120L)).put(true)))
                .put("blocked", new org.json.JSONArray()).put("debugModifyTestRawId", 9120);
        prefs.edit().putString(LEDGER, v1.toString()).commit();
        final GearContactsSnapshot state = snapshot(); assertEquals(9120, state.persistentTestRawId());
        assertTrue(state.beginPersistentTestTransport(Collections.singleton(9120L)));
        assertTrue(state.recordTransportDelivered(sent(9120, NEW)));
        assertEquals(3, new JSONObject(prefs.getString(LEDGER, null)).getInt("version"));
        assertEquals(9120, snapshot().persistentTestRawId()); assertEquals(sent(9120, NEW).get(9120L), snapshot().lastTransportDeliveredFingerprint(9120));
    }
    @Test public void invalidTombstoneOrConflictingScopesFailClosed() throws Exception {
        delivered(3, NEW);
        final JSONObject bad = new JSONObject(prefs.getString(LEDGER, null)).put("deleted", new org.json.JSONArray().put(9));
        prefs.edit().putString(LEDGER, bad.toString()).commit(); assertTrue(snapshot().hasPendingVerification());
        bad.put("deleted", new org.json.JSONArray()).put("debugModifyTestRawId", 9120).put("debugDeleteTestRawId", 3);
        prefs.edit().putString(LEDGER, bad.toString()).commit(); assertTrue(snapshot().hasPendingVerification());
    }
    @Test public void arbitraryPositiveDeleteScopeSurvivesStoreRecreationAndKeepsLegacyBytes() {
        delivered(3, NEW); assertTrue(snapshot().markPendingVerification()); final String legacy = prefs.getString(BASE, null);
        assertTrue(snapshot().preparePersistentDeleteTest(contact(1977, OLD)));
        final GearContactsSnapshot state = snapshot(); assertEquals(1977, state.persistentDeleteTestRawId());
        assertTrue(state.comparePersistentTest(Collections.singletonList(contact(1977, OLD)), 1977).isEmpty());
        assertEquals(Collections.singletonList(1977L), state.comparePersistentTest(Collections.emptyList(), 1977).delete);
        assertFalse(state.beginPersistentTestTransport(Collections.singleton(3L))); assertTrue(state.beginPersistentTestTransport(Collections.singleton(1977L)));
        assertTrue(state.recordTransportDeleted(Collections.singleton(1977L)));
        assertTrue(snapshot().isTransportDeleted(1977)); assertEquals(1977, snapshot().persistentDeleteTestRawId());
        assertEquals(sent(3, NEW).get(3L), snapshot().lastTransportDeliveredFingerprint(3)); assertEquals(legacy, prefs.getString(BASE, null));
        assertFalse(prefs.getAll().toString().contains("Synthetic")); assertFalse(prefs.getAll().toString().contains("+1555"));
    }
    @Test public void deleteScopeClearRemovesOnlyMatchingAuthorizationAndKeepsTombstonesAndBlocks() {
        delivered(3, NEW); deleted(3); assertTrue(snapshot().beginTransport(Collections.singleton(2L))); assertTrue(snapshot().markPendingVerification());
        assertTrue(snapshot().preparePersistentDeleteTest(contact(1977, OLD))); assertTrue(snapshot().beginPersistentTestTransport(Collections.singleton(1977L)));
        assertTrue(snapshot().recordTransportDeleted(Collections.singleton(1977L)));
        assertFalse(snapshot().clearPersistentDeleteTest(3)); assertFalse(snapshot().clearPersistentTest(9120));
        assertTrue(snapshot().clearPersistentDeleteTest(1977)); assertEquals(0, snapshot().persistentDeleteTestRawId());
        assertTrue(snapshot().isTransportDeleted(3)); assertTrue(snapshot().isTransportDeleted(1977)); assertTrue(snapshot().isBlocked(2));
        assertTrue(snapshot().hasPendingVerification());
    }
    @Test public void deleteScopeRejectsDifferingLedgerBlockedOrDeletedStateAndOtherScope() {
        delivered(3, NEW); assertTrue(snapshot().markPendingVerification());
        assertFalse(snapshot().preparePersistentDeleteTest(contact(3, OLD))); assertTrue(snapshot().preparePersistentDeleteTest(contact(1977, OLD)));
        assertFalse(snapshot().preparePersistentTest(contact(9120, OLD))); assertFalse(snapshot().preparePersistentDeleteTest(contact(1978, OLD)));
        assertTrue(snapshot().beginPersistentTestTransport(Collections.singleton(1977L))); assertTrue(snapshot().clearPersistentDeleteTest(1977));
        assertFalse(snapshot().preparePersistentDeleteTest(contact(1977, OLD))); // CLEAR does not erase interrupted state.
        assertTrue(snapshot().preparePersistentDeleteTest(contact(1978, OLD))); assertTrue(snapshot().beginPersistentTestTransport(Collections.singleton(1978L)));
        assertTrue(snapshot().recordTransportDeleted(Collections.singleton(1978L))); assertTrue(snapshot().clearPersistentDeleteTest(1978));
        assertFalse(snapshot().preparePersistentDeleteTest(contact(1978, OLD))); // Nor does it erase a delivered tombstone.
    }
    @Test public void deleteScopeIsDeviceIsolatedAndInitialCheckpointResetsTombstonesOnlyOnCompletion() {
        delivered(3, NEW); deleted(3); assertTrue(snapshot().markPendingVerification()); assertTrue(snapshot().preparePersistentDeleteTest(contact(1977, OLD)));
        final GearContactsSnapshot another = new GearContactsSnapshot(new GearContactsSnapshotStore(other));
        assertEquals(0, another.persistentDeleteTestRawId()); assertFalse(another.isTransportDeleted(3));
        assertTrue(snapshot().clearPersistentDeleteTest(1977));
        final GearContactsSnapshot state = snapshot(); assertTrue(state.requireInitial()); assertTrue(snapshot().isTransportDeleted(3));
        assertTrue(state.recordTransportDeliveredSnapshot(state.compare(Arrays.asList(contact(1, OLD), contact(2, OLD)), true)));
        assertFalse(snapshot().isTransportDeleted(3)); assertNull(prefs.getString(LEDGER, null));
    }

}
