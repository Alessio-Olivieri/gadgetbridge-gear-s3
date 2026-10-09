package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GearContactsSnapshotTest {
    private static GearContactsCodec.Contact contact(final long rawId, final String number) {
        return new GearContactsCodec.Contact(rawId, rawId + 100, rawId, "Disposable Example", "Disposable", "Example", false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }

    @Test public void initialInsertOnlyCheckpointsAfterTransportCompletion() throws Exception {
        final GearContactsSnapshot state = new GearContactsSnapshot();
        final GearContactsSnapshot.Delta first = state.compare(Collections.singletonList(contact(7, "+15550001001")), true);
        assertEquals(1, first.insert.size());
        assertFalse(state.isInitialized());
        assertEquals(1, state.compare(Collections.singletonList(contact(7, "+15550001001")), true).insert.size());
        state.recordTransportDeliveredSnapshot(first);
        assertTrue(state.isInitialized());
        assertTrue(state.compare(Collections.singletonList(contact(7, "+15550001001")), false).isEmpty());
    }

    @Test public void updateDeleteAndDuplicateRawIdsAreSeparated() throws Exception {
        final GearContactsSnapshot state = new GearContactsSnapshot();
        state.recordTransportDeliveredSnapshot(state.compare(Arrays.asList(contact(7, "+15550001001"), contact(8, "+15550001002")), true));
        final GearContactsSnapshot.Delta delta = state.compare(Arrays.asList(contact(7, "+15550001999"),
                contact(7, "+15550001999"), contact(9, "+15550001003")), false);
        assertEquals(1, delta.insert.size());
        assertEquals(1, delta.update.size());
        assertEquals(Collections.singletonList(8L), delta.delete);
        assertEquals(2, state.size());
        state.recordTransportDeliveredSnapshot(delta);
        assertEquals(2, state.size());
        assertTrue(state.compare(Arrays.asList(contact(7, "+15550001999"), contact(9, "+15550001003")), false).isEmpty());
    }
}
