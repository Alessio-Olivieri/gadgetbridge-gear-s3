package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.Context;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual CREATE/UPDATE, type10 dispatch, Android executor event and type13 SAP wire. No Bluetooth. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearNotificationStaleSequenceTest {
    private GearS3DeviceSupport support;
    private final List<byte[]> wires = new ArrayList<>();
    private final List<GBDeviceEventNotificationControl> dispatched = new ArrayList<>();
    private boolean complete = true;
    private int resultCode = 1;

    @Before public void setup() throws Exception {
        support = spy(new GearS3DeviceSupport());
        Context context = RuntimeEnvironment.getApplication();
        doReturn(context).when(support).getContext();
        doNothing().when(support).disconnect();
        TransactionBuilder tx = mock(TransactionBuilder.class, RETURNS_SELF);
        doAnswer(call -> { wires.add(((byte[]) call.getRawArguments()[0]).clone()); return tx; }).when(tx).write(any(byte[].class));
        doReturn(tx).when(support).createTransactionBuilder(anyString());
        doAnswer(call -> {
            final GBDeviceEvent event = call.getArgument(0);
            assertTrue(event instanceof GBDeviceEventNotificationControl);
            final GBDeviceEventNotificationControl control = (GBDeviceEventNotificationControl) event;
            dispatched.add(control);
            if (complete && control.replyResultReceiver != null) control.replyResultReceiver.send(resultCode, null);
            return null;
        }).when(support).evaluateGBDeviceEvent(any(GBDeviceEvent.class));
        SapNotificationServiceAcceptor acceptor = mock(SapNotificationServiceAcceptor.class);
        when(acceptor.getState()).thenReturn(SapNotificationServiceAcceptor.State.READY);
        when(acceptor.getApplicationSessionId()).thenReturn(77);
        set("notificationServiceAcceptor", acceptor);
        set("notificationIdentityStore", new SapNotificationIdentityStore(context.getSharedPreferences("stale-sequence", 0)));
        set("sapDispatchGeneration", 1L); set("notificationSequence", 9); set("notificationReliableTxSequence", 1);
        ((Map<String, byte[]>) get("applicationThumbnailCache")).put("com.whatsapp", new byte[]{1});
    }

    @After public void cleanup() { if (support != null) support.dispose(); }
    private Object get(String name) throws Exception {
        Field f = GearS3DeviceSupport.class.getDeclaredField(name); f.setAccessible(true); return f.get(support);
    }
    private void set(String name, Object value) throws Exception {
        Field f = GearS3DeviceSupport.class.getDeclaredField(name); f.setAccessible(true); f.set(support, value);
    }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GearS3DeviceSupport.class.getDeclaredMethod(name, types); m.setAccessible(true);
        synchronized (support) { m.invoke(support, args); }
    }
    private void post(String window, int id, NotificationSpec.Action... actions) throws Exception {
        NotificationSpec spec = new NotificationSpec(id);
        spec.setKey(window); spec.setSourceAppId("com.whatsapp"); spec.setSourceName("Test");
        spec.setTitle("Synthetic"); spec.setBody("Synthetic");
        spec.setAttachedActions(new ArrayList<>(Arrays.asList(actions)));
        support.onNotification(spec);
        ((ExecutorService) get("notificationImageExecutor")).submit(() -> {}).get(3, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    private NotificationSpec.Action reply(long handle) { return GearNotificationActionsTest.reply(handle, "reply-key"); }
    private void race() throws Exception {
        post("W", 10, reply(100), GearNotificationActionsTest.simple(101)); // seq10, IDs3/4
        post("W", 11, GearNotificationActionsTest.simple(201), reply(200));
        post("W", 12, reply(300), GearNotificationActionsTest.simple(301));
        post("W", 13, GearNotificationActionsTest.simple(401), reply(400));
        wires.clear();
    }
    private void incoming(int sequence, String window, int actionId) throws Exception { incoming(sequence, window, actionId, 42); }
    private void incoming(int sequence, String window, int actionId, int attribute) throws Exception {
        JSONObject request = new JSONObject().put("action_id", actionId)
                .put("results", new JSONArray().put(new JSONObject().put("key", "reply-key").put("value", "synthetic-reply")));
        if (window != null) request.put("window_id", window);
        SapNotificationServiceCodec.Message message = new SapNotificationServiceCodec.Message(2, 10, sequence, false, 0, 0, 0,
                Collections.singletonList(new SapNotificationServiceCodec.Attribute(attribute, request.toString().getBytes(StandardCharsets.UTF_8))), null, null);
        // Exercise the actual codec and event entry point, including the formerly silent null-record gate.
        invoke("handleNotificationServiceEvent", new Class<?>[]{SapNotificationServiceCodec.Message.class},
                SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(message)));
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void result(int sequence, int id, boolean success) {
        assertEquals("Exactly one correlated result", 1, wires.size());
        SapPostAuthRecord record = new SapPostAuthRecordCodec().accept(wires.get(0)).get(0);
        assertEquals(77, record.getHeader().getSessionId());
        SapNotificationServiceCodec.Message ack = SapNotificationServiceCodec.decode(
                SapNotificationServiceCodec.decodeServiceFrame(record.getBody()).getPayload());
        assertEquals(13, ack.getType()); assertEquals(sequence, ack.getSequence());
        assertArrayEquals(new byte[]{(byte)(id >>> 24), (byte)(id >>> 16), (byte)(id >>> 8), (byte)id},
                ack.findAttribute(success ? 35 : 37).getValue());
    }
    private void rejected(int sequence, String window, int id) throws Exception {
        incoming(sequence, window, id); assertTrue(dispatched.isEmpty()); result(sequence, id, false);
    }


    @Test public void normalCurrentSequenceStillDispatchesAndEchoesRequest() throws Exception {
        race(); incoming(13, "W", 3);
        assertEquals(400, dispatched.get(0).handle); result(13, 3, true);
    }
    @Test public void currentSequenceWithoutWindowPreservesExistingPath() throws Exception {
        race(); incoming(13, null, 3); assertEquals(400, dispatched.get(0).handle); result(13, 3, true);
    }
    @Test public void composerSequenceSurvivesMultipleReorderedUpdatesUsesLatestExecutorAndEchoesOldSequence() throws Exception {
        race(); incoming(10, "W", 3);
        assertEquals(1, dispatched.size()); assertEquals(400, dispatched.get(0).handle);
        assertEquals(GBDeviceEventNotificationControl.Event.REPLY, dispatched.get(0).event);
        result(10, 3, true);
    }
    @Test public void staleSequenceWrongWindowWithDeliberateNumericIdCollisionCannotCrossRoute() throws Exception {
        race(); set("nextWearableActionId", 3);
        post("Other-conversation", 20, reply(900)); // same numeric ID, different sequence provenance
        wires.clear(); rejected(10, "Other-conversation", 3);
    }
    @Test public void currentSequenceWrongWindowWithNumericIdCollisionCannotCrossRoute() throws Exception {
        race(); set("nextWearableActionId", 3); post("Other-conversation", 20, reply(900));
        wires.clear(); rejected(13, "Other-conversation", 3);
    }
    @Test public void unknownActionGetsExplicitNack() throws Exception { race(); rejected(10, "W", 999); }
    @Test public void unknownWindowGetsExplicitNack() throws Exception { race(); rejected(10, "Missing", 3); }
    @Test public void missingWindowCannotRecoverStaleSequence() throws Exception { race(); rejected(10, null, 3); }
    @Test public void arbitraryUnrecordedSequenceCannotRecoverCurrentAction() throws Exception { race(); rejected(5, "W", 3); }
    @Test public void removedReplyCannotRecover() throws Exception {
        race(); post("W", 14, GearNotificationActionsTest.simple(500)); wires.clear(); rejected(10, "W", 3);
    }
    @Test public void changedRemoteInputIdentityCannotRecover() throws Exception {
        race(); post("W", 14, GearNotificationActionsTest.reply(500, "different-key")); wires.clear(); rejected(10, "W", 3);
    }
    @Test public void changedTitleCannotRecover() throws Exception {
        race(); NotificationSpec.Action changed = reply(500); changed.setTitle("Different semantic label");
        post("W", 14, changed); wires.clear(); rejected(10, "W", 3);
    }
    @Test public void changedKindCannotRecover() throws Exception {
        race(); NotificationSpec.Action changed = reply(500); changed.setType(NotificationSpec.Action.TYPE_WEARABLE_SIMPLE);
        post("W", 14, changed); wires.clear(); rejected(10, "W", 3);
    }
    @Test public void duplicateSemanticActionsCannotRecover() throws Exception {
        race(); post("W", 14, reply(500), reply(600)); wires.clear(); rejected(10, "W", 3);
    }
    @Test public void initiallyAmbiguousIdentityCannotRecoverItsOwnOldId() throws Exception {
        post("W", 10, reply(100), reply(200)); post("W", 11, reply(300), reply(400));
        wires.clear(); rejected(10, "W", 3);
    }
    @Test public void deleteAndRecreateSameWindowCannotReviveComposer() throws Exception {
        race(); support.onDeleteNotification(13); set("nextWearableActionId", 3);
        post("W", 14, reply(500)); wires.clear(); rejected(10, "W", 3);
    }
    @Test public void reconnectClearsRecoveryEvenWithSameWindowAndNumericId() throws Exception {
        race(); set("sapDispatchGeneration", 2L);
        invoke("clearNotificationServiceConnection", new Class<?>[0]);
        set("notificationSequence", 12); post("W", 14, reply(500));
        wires.clear(); rejected(10, "W", 3);
    }
    @Test public void expiredBoundedProvenanceNacksRatherThanGuessing() throws Exception {
        post("W", 10, reply(100));
        for (int i = 11; i <= 74; i++) post("W", i, reply(i * 100));
        wires.clear(); rejected(10, "W", 3);
    }
    @Test public void stalePanelActionDoesNotGainWearableRecovery() throws Exception {
        race(); incoming(10, "W", 0, 17); assertTrue(dispatched.isEmpty()); result(10, 0, false);
    }
    @Test public void androidDispatchFailureReturnsNackForOriginalComposer() throws Exception {
        race(); resultCode = 0; incoming(10, "W", 3);
        assertEquals(400, dispatched.get(0).handle); result(10, 3, false);
    }
    @Test public void delayedAndroidResultCannotBeSentIntoNewGeneration() throws Exception {
        race(); complete = false; incoming(10, "W", 3); assertTrue(wires.isEmpty());
        set("sapDispatchGeneration", 2L); invoke("clearNotificationServiceConnection", new Class<?>[0]);
        dispatched.get(0).replyResultReceiver.send(1, null); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(wires.isEmpty());
    }
}
