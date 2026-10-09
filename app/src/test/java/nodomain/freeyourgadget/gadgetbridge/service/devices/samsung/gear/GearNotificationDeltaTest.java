package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceCodec;

public class GearNotificationDeltaTest {
    private Map<String, Boolean> snapshot(String... messages) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String message : messages) result.put(message, message.startsWith("self"));
        return result;
    }

    @Test public void remoteAlertsSelfAndMetadataAreSilent() {
        GearNotificationDelta state = new GearNotificationDelta();
        assertTrue(state.update("k", snapshot("remote1"), 0).alert());
        assertFalse(state.update("k", snapshot("remote1"), 1).alert());
        assertFalse(state.update("k", snapshot("remote1", "self1"), 2).alert());
        assertTrue(state.update("k", snapshot("remote1", "self1", "remote2"), 3).alert());
    }

    @Test public void enrichmentOfSameLogicalMessageIsSilent() {
        GearNotificationDelta state = new GearNotificationDelta();
        state.update("k", snapshot("remote-photo-timestamp-sender-text"), 0);
        // Pixel availability is deliberately not part of logical identity.
        GearNotificationDelta.Decision enrichment = state.update("k", snapshot("remote-photo-timestamp-sender-text"), 5_000);
        assertFalse(enrichment.alert());
        assertEquals(0, enrichment.remote);
    }

    @Test public void removalGraceRetainsOnlyDeltaAndSequenceThenExpires() {
        GearNotificationDelta state = new GearNotificationDelta();
        state.update("k", snapshot("remote1"), 0);
        state.sent("k", 7);
        state.removed("k", 1);
        GearNotificationDelta.Decision repost = state.update("k", snapshot("remote1", "self1"), 10);
        assertTrue(repost.continuation);
        assertEquals(7, repost.previousSequence);
        assertFalse(repost.alert());
        state.removed("k", 11);
        assertFalse(state.update("k", snapshot("remote1"), 30_011).continuation);
    }

    @Test public void newRemoteAndSelfTogetherAlertAndConversationsStaySeparate() {
        GearNotificationDelta state = new GearNotificationDelta();
        state.update("a", snapshot("remote1"), 0);
        assertTrue(state.update("a", snapshot("self1", "remote2"), 1).alert());
        assertTrue(state.update("b", snapshot("remote1"), 2).alert());
        state.clear();
        assertFalse(state.update("a", snapshot("remote2"), 3).continuation);
    }

    @Test public void atMost32ConversationsAndOneHourInactivity() {
        GearNotificationDelta state = new GearNotificationDelta();
        for (int i = 0; i < 33; i++) state.update("key" + i, snapshot("remote1"), 0);
        assertFalse(state.update("key0", snapshot("remote1"), 1).continuation);
        assertFalse(state.update("key32", snapshot("remote1"), 3_600_000).continuation);
    }

    private SapNotificationServiceCodec.Message create(int sequence, boolean image, boolean popup) {
        return SapNotificationServiceCodec.createRich(sequence, 1, popup,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "app", "App", "title", "body",
                1, 1, "stable-key", "msg", 0, null, null, null, null,
                "{\"actions\":[]}", "{\"messages\":[]}", image ? new byte[]{1, 2, 3} : null);
    }

    @Test public void bothStyleTransitionsDeleteOldSequenceAndWindowBeforeReplacement() {
        for (boolean oldImage : new boolean[]{true, false}) {
            SapNotificationServiceCodec.Message replacement = create(8, !oldImage, false);
            SapNotificationServiceCodec.Message removal = GearNotificationDelta.styleRemoval(create(7, oldImage, true), replacement);
            SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(removal));
            assertEquals(SapNotificationServiceCodec.TYPE_DELETE, decoded.getType());
            assertEquals(7, decoded.getSequence());
            assertEquals("stable-key", decoded.getWindowId());
            assertEquals(SapNotificationServiceCodec.TYPE_CREATE_SILENT, replacement.getType());
            assertNotNull(replacement.findAttribute(SapNotificationServiceCodec.ATTR_MESSAGING_STYLE));
            assertNotNull(replacement.findAttribute(SapNotificationServiceCodec.ATTR_WEAR_EXTENDER));
        }
        assertNull(GearNotificationDelta.styleRemoval(create(1, false, true), create(2, false, true)));
    }
}
