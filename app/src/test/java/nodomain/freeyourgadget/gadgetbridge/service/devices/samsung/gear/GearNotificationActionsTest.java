package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;

import static org.junit.Assert.*;

public class GearNotificationActionsTest {
    private final AtomicInteger nextId = new AtomicInteger(11);

    static NotificationSpec.Action reply(final long handle, final String key) {
        final NotificationSpec.Action action = new NotificationSpec.Action();
        action.setType(NotificationSpec.Action.TYPE_WEARABLE_REPLY);
        action.setTitle("Reply");
        action.setHandle(handle);
        action.setRemoteInputKey(key);
        action.setRemoteInputAllowFreeInput(true);
        return action;
    }

    static NotificationSpec.Action simple(final long handle) {
        final NotificationSpec.Action action = new NotificationSpec.Action();
        action.setType(NotificationSpec.Action.TYPE_WEARABLE_SIMPLE);
        action.setTitle("Mark as read");
        action.setHandle(handle);
        return action;
    }

    private GearNotificationActions update(final GearNotificationActions previous,
                                           final NotificationSpec.Action... actions) {
        return GearNotificationActions.update("whatsapp-window", "com.whatsapp", Arrays.asList(actions),
                previous, nextId::getAndIncrement);
    }

    @Test
    public void composerActionSurvivesRepeatedUpdatesAndUsesNewestAndroidHandle() {
        final GearNotificationActions beforeComposer = update(null, reply(100, "reply-key"), simple(101));
        final GearNotificationActions whileComposing = update(beforeComposer, reply(200, "reply-key"), simple(201));
        final NotificationSpec.Action latestReply = reply(300, "reply-key");
        final GearNotificationActions afterUpdate = update(whileComposing, latestReply, simple(301));

        // The Gear can combine its current notification sequence with the composer action 11.
        assertEquals(beforeComposer.keySet(), afterUpdate.keySet());
        assertSame(latestReply, afterUpdate.resolve(11, "whatsapp-window"));
        assertEquals(300, afterUpdate.get(11).getHandle());
        assertEquals(100, beforeComposer.get(11).getHandle());
        assertEquals(13, nextId.get()); // Updates do not allocate replacement IDs.
    }

    @Test
    public void reorderingDoesNotSwapReplyAndMarkReadExecutors() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"), simple(101));
        final GearNotificationActions reordered = update(first, simple(201), reply(200, "reply-key"));
        assertEquals(200, reordered.get(11).getHandle());
        assertEquals(201, reordered.get(12).getHandle());
        assertEquals(Arrays.asList(12, 11), Arrays.asList(reordered.keySet().toArray()));
    }

    @Test
    public void unrelatedWindowsOrPackagesCannotReuseOrResolveAnotherAction() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"));
        final GearNotificationActions otherWindow = GearNotificationActions.update("other-window", "com.whatsapp",
                Collections.singletonList(reply(200, "reply-key")), first, nextId::getAndIncrement);
        final GearNotificationActions otherPackage = GearNotificationActions.update("whatsapp-window", "other.app",
                Collections.singletonList(reply(300, "reply-key")), first, nextId::getAndIncrement);
        assertNull(otherWindow.get(11));
        assertNull(otherPackage.get(11));
        assertNull(first.resolve(11, "other-window"));
        assertNull(first.get(999));
    }

    @Test
    public void changedResultKeyRetiresOldComposerAction() {
        final GearNotificationActions first = update(null, reply(100, "old-key"));
        final GearNotificationActions changed = update(first, reply(200, "new-key"));
        assertNull(changed.get(11));
        assertEquals("new-key", changed.get(12).getRemoteInputKey());
    }

    @Test
    public void changedActionKindOrTitleIsNotAnAlias() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"));
        final NotificationSpec.Action changedKind = reply(200, "reply-key");
        changedKind.setType(NotificationSpec.Action.TYPE_WEARABLE_SIMPLE);
        assertNull(update(first, changedKind).get(11));
        final NotificationSpec.Action changedTitle = reply(300, "reply-key");
        changedTitle.setTitle("Reply to someone else");
        assertNull(update(first, changedTitle).get(11));
    }

    @Test
    public void removedReplyCannotBeRevivedByLaterReaddition() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"), simple(101));
        final GearNotificationActions removed = update(first, simple(201));
        final GearNotificationActions readded = update(removed, reply(300, "reply-key"), simple(301));
        assertNull(removed.get(11));
        assertNull(readded.get(11));
        assertEquals(301, readded.get(12).getHandle());
    }

    @Test
    public void duplicateSemanticActionsAreNeverMatchedByPosition() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"));
        final GearNotificationActions duplicate = update(first, reply(200, "reply-key"), reply(201, "reply-key"));
        assertNull(duplicate.get(11));
        assertEquals(2, duplicate.keySet().size());
        final GearNotificationActions singleAgain = update(duplicate, reply(300, "reply-key"));
        for (final int duplicateId : duplicate.keySet()) assertNull(singleAgain.get(duplicateId));
    }

    @Test
    public void differentRemoteInputKeysDisambiguateSameTitles() {
        final GearNotificationActions first = update(null, reply(100, "a"), reply(101, "b"));
        final GearNotificationActions reordered = update(first, reply(201, "b"), reply(200, "a"));
        assertEquals(200, reordered.get(11).getHandle());
        assertEquals(201, reordered.get(12).getHandle());
    }

    @Test
    public void refreshedComposerMetadataDoesNotReplaceSemanticIdentity() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"));
        final NotificationSpec.Action latest = reply(200, "reply-key");
        latest.setRemoteInputAllowFreeInput(false);
        latest.setRemoteInputChoices(new String[]{"Yes", "No"});
        latest.setRemoteInputLabel("Updated input label");
        final GearNotificationActions updated = update(first, latest);
        assertSame(latest, updated.get(11));
        assertFalse(updated.get(11).getRemoteInputAllowFreeInput());
        assertArrayEquals(new String[]{"Yes", "No"}, updated.get(11).getRemoteInputChoices());
    }

    @Test
    public void discardingWindowOnDeleteOrReconnectDiscardsReusableIdentity() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"));
        // Production stores this state only in NotificationRecord. DELETE/clear remove that record;
        // a later CREATE passes no previous state and cannot inherit a retained action/alias map.
        final GearNotificationActions newLifecycle = update(null, reply(200, "reply-key"));
        assertNull(newLifecycle.get(11));
        assertEquals(200, newLifecycle.get(12).getHandle());
        assertEquals(100, first.get(11).getHandle());
    }

    @Test
    public void emptySnapshotAndNonWearableActionsCannotRetainWearableExecutors() {
        final GearNotificationActions first = update(null, reply(100, "reply-key"));
        final NotificationSpec.Action panelReply = reply(200, "reply-key");
        panelReply.setType(NotificationSpec.Action.TYPE_CUSTOM_REPLY);
        assertTrue(update(first, null, panelReply).keySet().isEmpty());
        assertTrue(GearNotificationActions.update("whatsapp-window", "com.whatsapp", null,
                first, nextId::getAndIncrement).keySet().isEmpty());
    }
}
