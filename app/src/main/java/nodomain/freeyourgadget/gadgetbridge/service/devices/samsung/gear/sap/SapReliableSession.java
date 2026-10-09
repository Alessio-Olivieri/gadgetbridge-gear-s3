package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/**
 * Small generic receiver-side subset of Samsung's reliable SAP transport.
 * Reliable reception starts at sequence 1, waits 1500 ms,
 * then emits a block ACK; duplicate data is not delivered twice and is ACKed.
 */
public final class SapReliableSession {
    public static final long RX_BLOCK_ACK_TIMEOUT_MILLIS = 1500L;
    public enum Inbound { ACCEPT, DUPLICATE, OUT_OF_ORDER }
    private int lastValidSequence;
    private boolean ackPending;

    public Inbound receive(final int sequence) {
        validate(sequence);
        if (sequence == next(lastValidSequence)) {
            lastValidSequence = sequence;
            ackPending = true;
            return Inbound.ACCEPT;
        }
        if (sequence == lastValidSequence) {
            // Do not redeliver a duplicate; acknowledge the last valid sequence.
            return Inbound.DUPLICATE;
        }
        return Inbound.OUT_OF_ORDER;
    }

    /** Returns the delayed block ACK sequence once, after the caller's timer fires. */
    public int consumePendingBlockAck() {
        if (!ackPending) return -1;
        ackPending = false;
        return lastValidSequence;
    }

    public boolean isAckPending() { return ackPending; }
    public int getExpectedSequence() { return next(lastValidSequence); }
    public int getLastValidSequence() { return lastValidSequence; }
    public void reset() { lastValidSequence = 0; ackPending = false; }

    private static int next(final int sequence) { return sequence >= 0xffff ? 1 : sequence + 1; }
    private static void validate(final int sequence) { if (sequence < 1 || sequence > 0xffff) throw new IllegalArgumentException("Invalid reliable SAP sequence"); }
}
