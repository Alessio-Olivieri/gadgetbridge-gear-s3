package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GearContactsOneShotGateTest {
    private static GearContactsCodec.Contact contact(final long id) {
        return contact(id, "+15550002002");
    }

    private static GearContactsCodec.Contact contact(final long id, final String number) {
        return new GearContactsCodec.Contact(id, id + 100, id, "Synthetic Person", "Synthetic", "Person", false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }

    @Test public void oneCommandCanAuthorizeOnlyOneScan() {
        final GearContactsOneShotGate gate = new GearContactsOneShotGate();
        assertFalse(gate.consume());
        assertTrue(gate.arm());
        assertFalse(gate.arm());
        assertTrue(gate.isArmed());
        assertTrue(gate.consume());
        assertFalse(gate.isArmed());
        assertFalse(gate.consume());
        assertFalse(new GearContactsOneShotGate().isArmed()); // reconnect never rearms
    }

    @Test public void selectsAllDeletesInAscendingOrderAndIgnoresInserts() {
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Arrays.asList(contact(9), contact(2), contact(5)), true));
        final GearContactsSnapshot.Delta scan = snapshot.compare(Collections.singletonList(contact(10)), false);
        assertEquals(1, scan.insert.size());
        assertEquals(Arrays.asList(9L, 2L, 5L), scan.delete);
        assertEquals(3, GearContactsOneShotGate.retainDeletes(scan));
        assertTrue(scan.insert.isEmpty());
        assertTrue(scan.update.isEmpty());
        assertEquals(Arrays.asList(2L, 5L, 9L), scan.delete);
        final GearContactsSnapshot.Delta reordered = snapshot.compare(Collections.singletonList(contact(10)), false);
        Collections.reverse(reordered.delete);
        assertEquals(3, GearContactsOneShotGate.retainDeletes(reordered));
        assertEquals(scan.delete, reordered.delete);
        assertEquals(3, snapshot.size());
    }

    @Test public void noPendingDeleteProducesNoDebugOperation() {
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Collections.singletonList(contact(1)), true));
        final GearContactsSnapshot.Delta insertsOnly = snapshot.compare(Arrays.asList(contact(1), contact(2)), false);
        assertEquals(0, GearContactsOneShotGate.retainDeletes(insertsOnly));
        assertTrue(insertsOnly.isEmpty());
        assertEquals(0, GearContactsOneShotGate.retainDeletes(snapshot.compare(Collections.singletonList(contact(1)), false)));
        assertEquals(0, GearContactsOneShotGate.retainDeletes(null));
    }

    @Test public void debugGateDoesNotAlterPendingPersistedBaseline() {
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Arrays.asList(contact(1), contact(2)), true));
        assertTrue(snapshot.markPendingVerification());
        final GearContactsOneShotGate gate = new GearContactsOneShotGate();
        assertTrue(gate.arm());
        assertTrue(gate.consume());
        assertTrue(snapshot.hasPendingVerification());
        assertEquals(2, snapshot.size());
        assertEquals(Collections.singletonList(2L), snapshot.compare(Collections.singletonList(contact(1)), false).delete);
        gate.cancel();
        assertFalse(gate.isArmed());
        assertTrue(snapshot.hasPendingVerification());
    }

    @Test public void isolatedInsertTrialSelectsOnlyNewRawId() {
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Collections.singletonList(contact(1)), true));
        final GearContactsSnapshot.Delta scan = snapshot.compare(Arrays.asList(contact(1), contact(2), contact(3)), false);
        assertTrue(GearContactsOneShotGate.retainInsert(scan, 2));
        assertEquals(1, scan.insert.size());
        assertEquals(2, scan.insert.get(0).rawId);
        assertTrue(scan.update.isEmpty());
        assertTrue(scan.delete.isEmpty());
        assertFalse(GearContactsOneShotGate.retainInsert(snapshot.compare(Collections.singletonList(contact(1)), false), 1));
    }

    @Test public void replacementTrialTargetsOnlySeededRawIdAndRequiresChangedState() {
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Arrays.asList(contact(1), contact(2)), true));
        final GearContactsCodec.Contact old = contact(3, "+15550001001");
        final GearContactsCodec.Contact current = contact(3, "+15550002002");
        final GearContactsSnapshot.Delta scan = snapshot.compare(Arrays.asList(contact(1), contact(2), current, contact(4)), false);
        assertTrue(GearContactsOneShotGate.retainReplacement(scan, 3, GearContactsSnapshot.fingerprint(old)));
        assertEquals(Collections.singletonList(3L), scan.delete);
        assertEquals(1, scan.insert.size());
        assertEquals(3, scan.insert.get(0).rawId);
        assertTrue(scan.update.isEmpty());
        assertEquals(2, snapshot.size()); // neither linked sibling nor persisted baseline changed
        assertFalse(GearContactsOneShotGate.retainReplacement(
                snapshot.compare(Arrays.asList(contact(1), contact(2), current), false), 3,
                GearContactsSnapshot.fingerprint(current)));
    }
}
