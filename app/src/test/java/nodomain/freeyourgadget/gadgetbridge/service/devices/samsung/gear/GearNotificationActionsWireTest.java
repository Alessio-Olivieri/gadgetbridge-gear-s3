package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceCodec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class GearNotificationActionsWireTest {
    @Test
    public void attr41KeepsComposerIdAndCurrentSequenceReplyResolvesNewestHandle() throws Exception {
        final AtomicInteger nextId = new AtomicInteger(11);
        GearNotificationActions actions = GearNotificationActions.update("window", "com.whatsapp",
                Arrays.asList(GearNotificationActionsTest.reply(100, "reply-key"), GearNotificationActionsTest.simple(101)),
                null, nextId::getAndIncrement);
        final JSONObject initial = new JSONObject(GearS3DeviceSupport.notificationWearableExtenderJson(actions));
        final int composerId = initial.getJSONArray("actions").getJSONObject(0).getInt("action_id");
        for (int sequence = 8; sequence <= 11; sequence++) {
            actions = GearNotificationActions.update("window", "com.whatsapp",
                    Arrays.asList(GearNotificationActionsTest.simple(sequence * 100 + 1),
                            GearNotificationActionsTest.reply(sequence * 100, "reply-key")), actions, nextId::getAndIncrement);
            final String extender = GearS3DeviceSupport.notificationWearableExtenderJson(actions);
            final SapNotificationServiceCodec.Message create = SapNotificationServiceCodec.createRich(sequence, 1, true,
                    SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "com.whatsapp", "WhatsApp", "t", "b",
                    1, sequence, "window", "msg", 0, null, null, null, null, extender, null, null);
            final SapNotificationServiceCodec.Message wire = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(create));
            final JSONArray serializedActions = new JSONObject(wire.findAttribute(SapNotificationServiceCodec.ATTR_WEAR_EXTENDER).getUtf8())
                    .getJSONArray("actions");
            assertEquals(composerId, serializedActions.getJSONObject(1).getInt("action_id"));
            assertEquals("reply-key", serializedActions.getJSONObject(1).getJSONArray("remote_inputs").getJSONObject(0).getString("key"));

            // A real protocol-2 type-10/attr-42 event: latest sequence, composer-held action ID.
            final String request = new JSONObject().put("window_id", "window").put("action_id", composerId)
                    .put("results", new JSONArray().put(new JSONObject().put("key", "reply-key").put("value", "test-reply"))).toString();
            final byte[] json = request.getBytes(StandardCharsets.UTF_8);
            final byte[] incoming = new byte[12 + json.length];
            incoming[0] = 0x2a;
            incoming[3] = (byte) sequence;
            incoming[7] = 1;
            incoming[8] = 42;
            incoming[9] = (byte) (json.length >>> 16);
            incoming[10] = (byte) (json.length >>> 8);
            incoming[11] = (byte) json.length;
            System.arraycopy(json, 0, incoming, 12, json.length);
            final SapNotificationServiceCodec.Message reply = SapNotificationServiceCodec.decode(incoming);
            final JSONObject decoded = new JSONObject(reply.findAttribute(SapNotificationServiceCodec.ATTR_WEAR_ACTION_REQUEST).getUtf8());
            assertEquals(sequence, reply.getSequence());
            assertEquals(sequence * 100, actions.resolve(decoded.getInt("action_id"), decoded.getString("window_id")).getHandle());
            final SapNotificationServiceCodec.Message ack = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(
                    SapNotificationServiceCodec.actionAck(reply.getSequence(), composerId, true)));
            assertEquals(13, ack.getType());
            assertEquals(sequence, ack.getSequence());
            assertArrayEquals(new byte[]{0, 0, 0, (byte) composerId}, ack.findAttribute(35).getValue());
        }
    }
}
