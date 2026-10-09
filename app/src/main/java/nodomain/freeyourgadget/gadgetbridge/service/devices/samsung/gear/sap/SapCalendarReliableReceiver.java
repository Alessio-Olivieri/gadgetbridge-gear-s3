package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calendar-only bounded receive window. TX numbering never changes this state. */
public final class SapCalendarReliableReceiver {
    private static final int MAX_BUFFERED = 64; // Local resource bound, not a negotiated wire parameter.
    public enum Status { ACCEPT, DUPLICATE, QUEUED, OVERFLOW }
    private final SapReliableSession contiguous = new SapReliableSession();
    private final Set<Integer> delivered = new LinkedHashSet<>();
    private final Map<Integer, SapCalendarCodec.Frame> waiting = new LinkedHashMap<>();

    public Result receive(final SapCalendarCodec.Frame frame) {
        final List<SapCalendarCodec.Frame> ready = new ArrayList<>();
        if (frame.sequence == getExpectedSequence()) {
            accept(frame, ready);
            SapCalendarCodec.Frame next;
            while ((next = waiting.remove(getExpectedSequence())) != null) accept(next, ready);
            return new Result(Status.ACCEPT, ready);
        }
        if (delivered.contains(frame.sequence)) return new Result(Status.DUPLICATE, ready);
        if (waiting.containsKey(frame.sequence)) return new Result(Status.QUEUED, ready);
        if (waiting.size() >= MAX_BUFFERED) return new Result(Status.OVERFLOW, ready);
        // Buffer forward packets and deliver them only after the sequence gap is filled.
        waiting.put(frame.sequence, frame);
        return new Result(Status.QUEUED, ready);
    }

    private void accept(final SapCalendarCodec.Frame frame, final List<SapCalendarCodec.Frame> ready) {
        contiguous.receive(frame.sequence);
        delivered.remove(frame.sequence);
        delivered.add(frame.sequence);
        if (delivered.size() > MAX_BUFFERED) delivered.remove(delivered.iterator().next());
        ready.add(frame);
    }

    public int getExpectedSequence() { return contiguous.getExpectedSequence(); }
    public int getLastValidSequence() { return contiguous.getLastValidSequence(); }
    public int getQueuedCount() { return waiting.size(); }
    public boolean hasPendingBlockAck() { return contiguous.isAckPending(); }
    public int consumePendingBlockAck() { return contiguous.consumePendingBlockAck(); }
    public void reset() { contiguous.reset(); delivered.clear(); waiting.clear(); }

    public static final class Result {
        public final Status status;
        public final List<SapCalendarCodec.Frame> ready;
        private Result(final Status status, final List<SapCalendarCodec.Frame> ready) {
            this.status = status;
            this.ready = ready;
        }
    }
}
