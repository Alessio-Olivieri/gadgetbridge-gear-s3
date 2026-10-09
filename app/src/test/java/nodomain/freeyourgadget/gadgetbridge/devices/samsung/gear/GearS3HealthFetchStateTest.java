package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GearS3HealthFetchStateTest {
    @Test
    public void syncAndActivityRequestsStartAHealthFetch() {
        final GearS3HealthFetchState sync = new GearS3HealthFetchState();
        assertTrue(sync.begin(RecordedDataTypes.TYPE_SYNC));
        assertEquals(GearS3HealthFetchState.Phase.WAIT_TERMINATION, sync.getPhase());

        final GearS3HealthFetchState activity = new GearS3HealthFetchState();
        assertTrue(activity.begin(RecordedDataTypes.TYPE_ACTIVITY));
    }

    @Test
    public void supportedActivityBitIsNotRejectedByExtraTypes() {
        final GearS3HealthFetchState state = new GearS3HealthFetchState();
        assertTrue(state.begin(RecordedDataTypes.TYPE_ACTIVITY | RecordedDataTypes.TYPE_STRESS));
    }

    @Test
    public void duplicateFetchDoesNotStartAnotherSession() {
        final GearS3HealthFetchState state = new GearS3HealthFetchState();
        assertTrue(state.begin(RecordedDataTypes.TYPE_ACTIVITY));
        assertFalse(state.begin(RecordedDataTypes.TYPE_ACTIVITY));
    }

    @Test
    public void requestAttachesToActiveCommonSyncWithoutTerminationPhase() {
        final GearS3HealthFetchState state = new GearS3HealthFetchState();
        assertTrue(state.attachToCommonSync(RecordedDataTypes.TYPE_ACTIVITY));
        assertEquals(GearS3HealthFetchState.Phase.WAIT_COMMON_SYNC, state.getPhase());
        assertFalse(state.attachToCommonSync(RecordedDataTypes.TYPE_ACTIVITY | RecordedDataTypes.TYPE_STRESS));
    }

    @Test
    public void terminalRequestDataCompletesExactlyOnce() {
        final GearS3HealthFetchState state = new GearS3HealthFetchState();
        assertTrue(state.begin(RecordedDataTypes.TYPE_ACTIVITY));
        state.awaitingConnection();
        assertFalse(state.complete());
        state.awaitingCommonSync();
        assertTrue(state.complete());
        assertFalse(state.complete());
    }

    @Test
    public void unsolicitedCommonSyncCannotCompleteAFetch() {
        assertFalse(new GearS3HealthFetchState().complete());
    }

    @Test
    public void failureClearsPendingStateForAnotherFetch() {
        final GearS3HealthFetchState state = new GearS3HealthFetchState();
        assertTrue(state.begin(RecordedDataTypes.TYPE_ACTIVITY));
        assertTrue(state.fail());
        assertFalse(state.isInProgress());
        assertTrue(state.begin(RecordedDataTypes.TYPE_ACTIVITY));
    }
}
