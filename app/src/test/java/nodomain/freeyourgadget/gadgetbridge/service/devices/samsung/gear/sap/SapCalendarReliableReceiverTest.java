package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import static org.junit.Assert.*;

public class SapCalendarReliableReceiverTest {
    private static SapCalendarCodec.Frame frame(final int sequence) throws Exception {
        return SapCalendarCodec.decodeFrame(SapCalendarCodec.encodeFrame(sequence,
                "{\"msgId\":\"calendar-sequence-req\"}"));
    }

    @Test
    public void oldDuplicateOneAfterTwoDoesNotBlockThreeOrRedeliver() throws Exception {
        final SapCalendarReliableReceiver rx = new SapCalendarReliableReceiver();
        assertEquals(1, rx.receive(frame(1)).ready.size());
        assertEquals(1, rx.receive(frame(2)).ready.size());
        assertTrue(rx.hasPendingBlockAck());
        assertEquals(SapCalendarReliableReceiver.Status.DUPLICATE, rx.receive(frame(1)).status);
        assertEquals(3, rx.getExpectedSequence());
        assertEquals(2, rx.consumePendingBlockAck());
        assertFalse(rx.hasPendingBlockAck());
        assertEquals(1, rx.receive(frame(3)).ready.size());
        assertEquals(3, rx.consumePendingBlockAck());
    }

    @Test
    public void forwardFrameWaitsForHoleAndIsDeliveredOnlyOnce() throws Exception {
        final SapCalendarReliableReceiver rx = new SapCalendarReliableReceiver();
        rx.receive(frame(1));
        assertEquals(SapCalendarReliableReceiver.Status.QUEUED, rx.receive(frame(3)).status);
        assertEquals(SapCalendarReliableReceiver.Status.QUEUED, rx.receive(frame(3)).status);
        assertEquals(1, rx.getQueuedCount());
        assertEquals(1, rx.consumePendingBlockAck()); // Never ACK over the missing frame.
        final SapCalendarReliableReceiver.Result filled = rx.receive(frame(2));
        assertEquals(2, filled.ready.size());
        assertEquals(2, filled.ready.get(0).sequence);
        assertEquals(3, filled.ready.get(1).sequence);
        assertEquals(0, rx.getQueuedCount());
        assertTrue(rx.receive(frame(3)).ready.isEmpty());
        assertEquals(1, rx.receive(frame(4)).ready.size());
    }

    @Test
    public void outgoingFramesDoNotAdvanceReceiveSequenceAndNewSessionResetsIt() throws Exception {
        final SapCalendarReliableReceiver rx = new SapCalendarReliableReceiver();
        for (int tx = 1; tx <= 10; tx++) SapCalendarCodec.encodeFrame(tx, "{}");
        assertEquals(1, rx.getExpectedSequence());
        rx.receive(frame(1));
        rx.receive(frame(3));
        rx.reset();
        assertEquals(0, rx.getQueuedCount());
        assertEquals(1, rx.receive(frame(1)).ready.size());
        assertEquals(2, rx.getExpectedSequence());
    }
}
