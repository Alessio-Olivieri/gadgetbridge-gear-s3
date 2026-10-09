package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;

/**
 * Tracks a user-requested API999 refresh without changing the unsolicited
 * connection-time CommonSync path.
 */
public final class GearS3HealthFetchState {
    public enum Phase { IDLE, WAIT_TERMINATION, WAIT_CONNECTION, WAIT_COMMON_SYNC }

    private Phase phase = Phase.IDLE;
    private int requestedDataTypes;

    public static boolean requestsActivity(final int dataTypes) {
        return (dataTypes & RecordedDataTypes.TYPE_ACTIVITY) != 0;
    }

    public boolean begin(final int dataTypes) {
        if (!requestsActivity(dataTypes) || phase != Phase.IDLE) {
            return false;
        }
        requestedDataTypes = dataTypes;
        phase = Phase.WAIT_TERMINATION;
        return true;
    }

    /** Attaches a request directly to an already active CommonSync transaction. */
    public boolean attachToCommonSync(final int dataTypes) {
        if (!requestsActivity(dataTypes) || phase != Phase.IDLE) {
            return false;
        }
        requestedDataTypes = dataTypes;
        phase = Phase.WAIT_COMMON_SYNC;
        return true;
    }

    public void awaitingConnection() {
        requireActive();
        phase = Phase.WAIT_CONNECTION;
    }

    public void awaitingCommonSync() {
        requireActive();
        phase = Phase.WAIT_COMMON_SYNC;
    }

    /** Returns true only for the terminal REQUEST_DATA/SUCCESS phase. */
    public boolean complete() {
        if (phase != Phase.WAIT_COMMON_SYNC) {
            return false;
        }
        reset();
        return true;
    }

    /** Returns true when a pending fetch was cleared. */
    public boolean fail() {
        if (phase == Phase.IDLE) {
            return false;
        }
        reset();
        return true;
    }

    public boolean isInProgress() {
        return phase != Phase.IDLE;
    }

    public Phase getPhase() {
        return phase;
    }

    public int getRequestedDataTypes() {
        return requestedDataTypes;
    }

    private void requireActive() {
        if (phase == Phase.IDLE) {
            throw new IllegalStateException("No Gear S3 health fetch is active");
        }
    }

    private void reset() {
        phase = Phase.IDLE;
        requestedDataTypes = 0;
    }
}
