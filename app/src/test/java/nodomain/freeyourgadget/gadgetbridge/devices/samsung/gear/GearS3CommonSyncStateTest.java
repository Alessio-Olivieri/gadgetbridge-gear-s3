package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GearS3CommonSyncStateTest {
    @Test
    public void idleHasNoFetchCoalescingWindow() {
        final GearS3CommonSyncState state = new GearS3CommonSyncState();
        assertEquals(GearS3CommonSyncState.Phase.IDLE, state.getPhase());
        assertFalse(state.canCoalesceFetch());
    }

    @Test
    public void remainsActiveAcrossMultipleChunks() {
        final GearS3CommonSyncState state = new GearS3CommonSyncState();
        assertTrue(state.begin());
        assertFalse(state.begin());
        assertTrue(state.isActive());
        assertEquals(GearS3CommonSyncState.Phase.ACTIVE, state.getPhase());
    }

    @Test
    public void capabilityStartupIsASeparateCoalescingWindow() {
        final GearS3CommonSyncState state = new GearS3CommonSyncState();
        assertFalse(state.canCoalesceFetch());
        assertTrue(state.expectingSync());
        assertTrue(state.canCoalesceFetch());
        assertTrue(state.fail());
        assertEquals(GearS3CommonSyncState.Phase.IDLE, state.getPhase());
        assertTrue(state.expectingSync());
        assertTrue(state.begin());
        assertEquals(GearS3CommonSyncState.Phase.ACTIVE, state.getPhase());
        assertTrue(state.fail());
        assertFalse(state.canCoalesceFetch());
    }

    @Test
    public void terminalCompletionIsExactlyOnce() {
        final GearS3CommonSyncState state = new GearS3CommonSyncState();
        assertTrue(state.expectingSync());
        assertTrue(state.begin());
        assertTrue(state.complete());
        assertFalse(state.complete());
        assertFalse(state.isActive());
    }

    @Test
    public void failureResetsActiveTransaction() {
        final GearS3CommonSyncState state = new GearS3CommonSyncState();
        assertTrue(state.begin());
        assertTrue(state.fail());
        assertFalse(state.isActive());
    }
}
