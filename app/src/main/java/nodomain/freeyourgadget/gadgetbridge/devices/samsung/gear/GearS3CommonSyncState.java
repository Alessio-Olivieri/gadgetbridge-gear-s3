package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

/** Tracks one watch-initiated CommonSync transaction across all chunks. */
public final class GearS3CommonSyncState {
    public enum Phase { IDLE, EXPECTING_SYNC, ACTIVE }

    private Phase phase = Phase.IDLE;

    /** Marks the short capability-to-CommonSync startup window. */
    public boolean expectingSync() {
        if (phase != Phase.IDLE) {
            return false;
        }
        phase = Phase.EXPECTING_SYNC;
        return true;
    }

    /** Starts a transaction, returning false when it was already active. */
    public boolean begin() {
        if (phase == Phase.ACTIVE) {
            return false;
        }
        phase = Phase.ACTIVE;
        return true;
    }

    /** Clears the transaction only after terminal REQUEST_DATA/SUCCESS. */
    public boolean complete() {
        if (phase != Phase.ACTIVE) {
            return false;
        }
        phase = Phase.IDLE;
        return true;
    }

    public boolean fail() {
        if (phase == Phase.IDLE) {
            return false;
        }
        phase = Phase.IDLE;
        return true;
    }

    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public boolean isExpectingSync() {
        return phase == Phase.EXPECTING_SYNC;
    }

    public boolean canCoalesceFetch() {
        return phase != Phase.IDLE;
    }

    public Phase getPhase() {
        return phase;
    }
}
