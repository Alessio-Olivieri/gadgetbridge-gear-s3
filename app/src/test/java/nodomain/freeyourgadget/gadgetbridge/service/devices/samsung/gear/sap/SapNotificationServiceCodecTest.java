package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SapNotificationServiceCodecTest {
    @Test
    public void roundTripsClearFrameAndCompatibilityCreate() {
        final SapNotificationServiceCodec.Message create = SapNotificationServiceCodec.create(7, 0x1234, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "org.example", "Example", "Title", "Body",
                1234L, 42, "org.example#42", "message", 0);
        final byte[] logical = SapNotificationServiceCodec.encode(create);
        final SapNotificationServiceCodec.ServiceFrame frame = SapNotificationServiceCodec.decodeServiceFrame(
                SapNotificationServiceCodec.encodeServiceFrame(1, logical));
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(frame.getPayload());
        assertEquals(1, frame.getSequence());
        assertEquals(SapNotificationServiceCodec.TYPE_CREATE_POPUP, decoded.getType());
        assertEquals("Title", decoded.findAttribute(SapNotificationServiceCodec.ATTR_TITLE).getUtf8());
        assertEquals("Title", decoded.findAttribute(SapNotificationServiceCodec.ATTR_CONTENT_TITLE).getUtf8());
        assertEquals("Body", decoded.findAttribute(SapNotificationServiceCodec.ATTR_BODY).getUtf8());
        assertEquals("Body", decoded.findAttribute(SapNotificationServiceCodec.ATTR_CONTENT_TEXT).getUtf8());
    }

    @Test
    public void legacyAndModernProfilesAreExplicit() {
        final SapNotificationServiceCodec.Message legacy = SapNotificationServiceCodec.create(1, 1, true,
                SapNotificationServiceCodec.AttributeProfile.LEGACY, "p", null, "t", "b", 0, 1, "p#1", null, 0);
        final SapNotificationServiceCodec.Message modern = SapNotificationServiceCodec.create(2, 1, true,
                SapNotificationServiceCodec.AttributeProfile.MODERN, "p", null, "t", "b", 0, 1, "p#1", null, 0);
        assertFalse(SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(legacy)).findAttribute(SapNotificationServiceCodec.ATTR_CONTENT_TITLE) != null);
        assertFalse(SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(modern)).findAttribute(SapNotificationServiceCodec.ATTR_TITLE) != null);
    }

    @Test
    public void deleteUsesStableWindowIdAndThreeByteSequence() {
        final SapNotificationServiceCodec.Message deleted = SapNotificationServiceCodec.delete(0x12345, "p#9");
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(deleted));
        assertEquals(SapNotificationServiceCodec.TYPE_DELETE, decoded.getType());
        assertEquals(0x12345, decoded.getSequence());
        assertEquals("p#9", decoded.getWindowId());
    }

    @Test
    public void rejectsMalformedSdkAndTlvLengths() {
        assertThrows(IllegalArgumentException.class, () -> SapNotificationServiceCodec.decodeServiceFrame(new byte[]{0, 1, 4, 0}));
        assertThrows(IllegalArgumentException.class, () -> SapNotificationServiceCodec.decode(new byte[]{0x20, 0, 0, 1, 0, 1, 0, 1, 2, 0, 1}));
    }

    @Test
    public void decodesBoundedV15InitializationAndRejectsDuplicateGarbage() {
        final byte[] init = new byte[]{(byte) 0xf0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        final SapNotificationServiceCodec.Initialization decoded = SapNotificationServiceCodec.decodeInitialization(init);
        assertEquals(7, decoded.getLastSequence());
        assertTrue(decoded.getUnread().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SapNotificationServiceCodec.decodeInitialization(new byte[]{(byte) 0xf0, 0, 0, 0}));
    }

    @Test
    public void decodesAdditionalInfoRequestAsCompactSequenceOnlyMessage() {
        final SapNotificationServiceCodec.Message request = SapNotificationServiceCodec.decode(new byte[]{0x23, 0, 0, 1});
        assertEquals(SapNotificationServiceCodec.PROTOCOL_NORMAL, request.getProtocolVersion());
        assertEquals(SapNotificationServiceCodec.TYPE_ADDITIONAL_DATA, request.getType());
        assertEquals(1, request.getSequence());
        assertTrue(request.getAttributes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SapNotificationServiceCodec.decode(new byte[]{0x23, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> SapNotificationServiceCodec.decode(new byte[]{0x23, 0, 0, 1, 0}));
    }

    @Test
    public void applicationThumbnailIsARegularSourceBackedCreateAttribute() {
        final byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        final SapNotificationServiceCodec.Message create = SapNotificationServiceCodec.create(1, 0x658f, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "nodomain.freeyourgadget.gadgetbridge",
                "Gadgetbridge", "Hello", "Body", 1L, 2, "probe#2", "status", 0, png);
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(create));
        assertArrayEquals(png, decoded.findAttribute(SapNotificationServiceCodec.ATTR_THUMBNAIL).getValue());
    }

    @Test
    public void richCreateKeepsReplacementWindowIdAndSeparatesAvatarAndMessagingStyle() {
        final byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        final SapNotificationServiceCodec.Message create = SapNotificationServiceCodec.createRich(9, 0x658f, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "org.example", "Example", "Alice", "Hello",
                1L, 2, "0|org.example|2|tag|1000", "msg", 0, null, png,
                "content://org.example/image/1", "[{\"TITLE\":\"Reply\",\"ID\":0,\"ICON_URI\":\"\"}]",
                null, "{\"user_display_name\":\"Me\",\"messages\":[{\"mSender\":\"Alice\",\"mTimestamp\":1,\"mText\":\"Hello\"}],\"historicMessages\":[]}", null);
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(create));
        assertEquals("0|org.example|2|tag|1000", decoded.findAttribute(SapNotificationServiceCodec.ATTR_WINDOW_ID).getUtf8());
        assertEquals("[{\"TITLE\":\"Reply\",\"ID\":0,\"ICON_URI\":\"\"}]", decoded.findAttribute(SapNotificationServiceCodec.ATTR_ACTIONS).getUtf8());
        assertTrue(decoded.findAttribute(SapNotificationServiceCodec.ATTR_MESSAGING_STYLE).getUtf8().contains("Alice"));
        final byte[] packed = decoded.findAttribute(SapNotificationServiceCodec.ATTR_LARGE_ICON).getValue();
        assertEquals(29, packed[1] & 0xff); // U16 URI length is followed by Samsung image-attribute 19
        assertEquals(SapNotificationServiceCodec.ATTR_LARGE_ICON, packed[2 + 29] & 0xff);
    }

    @Test
    public void richCreateKeepsWearableExtenderSeparateFromPanelActions() {
        final SapNotificationServiceCodec.Message create = SapNotificationServiceCodec.createRich(9, 1, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "org.example", "Example", "Alice", "Hello",
                1L, 2, "stable-key", "msg", 0, null, null, null,
                "[{\"TITLE\":\"Ordinary\",\"ID\":0,\"ICON_URI\":\"\"}]",
                "{\"actions\":[{\"title\":\"Reply\",\"action_id\":3,\"remote_inputs\":[{\"key\":\"reply\",\"allow_free_input\":1}]}],\"hint_hide_icon\":false}",
                null, null);
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(create));
        assertTrue(decoded.findAttribute(SapNotificationServiceCodec.ATTR_ACTIONS).getUtf8().contains("TITLE"));
        assertTrue(decoded.findAttribute(SapNotificationServiceCodec.ATTR_WEAR_EXTENDER).getUtf8().contains("action_id"));
    }

    @Test
    public void actionAckUsesSourceDefinedTypeAndAckAttribute() {
        final SapNotificationServiceCodec.Message ack = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(
                SapNotificationServiceCodec.actionAck(7, 2, true)));
        assertEquals(SapNotificationServiceCodec.TYPE_ACTION_ACK, ack.getType());
        assertEquals(7, ack.getSequence());
        assertEquals(2, ack.findAttribute(SapNotificationServiceCodec.ATTR_ACK).getValue()[3]);
    }

    @Test
    public void optionalRichImageCanBeRemovedWithoutChangingOtherRichAttributes() {
        final SapNotificationServiceCodec.Message rich = SapNotificationServiceCodec.createRich(1, 1, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "p", "P", "t", "b", 1, 2, "key", null, 0,
                null, new byte[4096], "content://p/image/1", "[{\"TITLE\":\"Reply\",\"ID\":0,\"ICON_URI\":\"\"}]",
                null, "{\"messages\":[]}", null);
        final SapNotificationServiceCodec.Message noImage = SapNotificationServiceCodec.withoutAttribute(rich, SapNotificationServiceCodec.ATTR_LARGE_ICON);
        assertTrue(rich.findAttribute(SapNotificationServiceCodec.ATTR_LARGE_ICON) != null);
        assertTrue(noImage.findAttribute(SapNotificationServiceCodec.ATTR_LARGE_ICON) == null);
        assertTrue(noImage.findAttribute(SapNotificationServiceCodec.ATTR_ACTIONS) != null);
        assertTrue(noImage.findAttribute(SapNotificationServiceCodec.ATTR_MESSAGING_STYLE) != null);
    }

    @Test
    public void richCreateKeepsRawBigPictureSeparateFromImagePackages() {
        final byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        final SapNotificationServiceCodec.Message create = SapNotificationServiceCodec.createRich(1, 1, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "p", "P", "t", "b", 1, 2, "key", null, 0,
                null, null, null, null, null, null, png);
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(create));
        assertArrayEquals(png, decoded.findAttribute(SapNotificationServiceCodec.ATTR_BIG_PICTURE).getValue());
    }

    @Test
    public void additionalInfoResponseReusesTheRequestedNotificationSequence() {
        final SapNotificationServiceCodec.Message request = SapNotificationServiceCodec.decode(new byte[]{0x23, 0, 0, 1});
        final SapNotificationServiceCodec.Message response = SapNotificationServiceCodec.create(request.getSequence(), 0x658f, true,
                SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY, "nodomain.freeyourgadget.gadgetbridge",
                "Gadgetbridge", "Hello", "Notification protocol works.", 1L, 2, "probe#2", "status", 0,
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        final SapNotificationServiceCodec.Message decoded = SapNotificationServiceCodec.decode(SapNotificationServiceCodec.encode(response));
        assertEquals(request.getSequence(), decoded.getSequence());
        assertEquals(SapNotificationServiceCodec.TYPE_CREATE_POPUP, decoded.getType());
        assertEquals(0x658f, decoded.getAppId());
    }

    @Test
    public void unknownApplicationTypeRemainsAParsedMessageForCallerScopedHandling() {
        final SapNotificationServiceCodec.Message unknown = SapNotificationServiceCodec.decode(
                new byte[]{0x2f, 0, 0, 1, 0, 1, 0, 0});
        assertEquals(15, unknown.getType());
        assertEquals(1, unknown.getSequence());
        assertTrue(unknown.getAttributes().isEmpty());
    }

    @Test
    public void reliableInboundSequencesAreAckedWithoutDuplicateDelivery() {
        final SapReliableSession reliable = new SapReliableSession();
        assertEquals(SapReliableSession.Inbound.ACCEPT, reliable.receive(1));
        assertTrue(reliable.isAckPending());
        assertEquals(1, reliable.consumePendingBlockAck());
        assertFalse(reliable.isAckPending());
        assertEquals(SapReliableSession.Inbound.DUPLICATE, reliable.receive(1));
        assertEquals(2, reliable.getExpectedSequence());
        assertEquals(SapReliableSession.Inbound.OUT_OF_ORDER, reliable.receive(3));
        assertEquals(1, reliable.getLastValidSequence());
        assertEquals(SapReliableSession.Inbound.ACCEPT, reliable.receive(2));
        assertEquals(2, reliable.consumePendingBlockAck());
        assertEquals(1, SapNotificationServiceCodec.encodeReliableBlockAck(1)[0]);
        assertEquals(1, SapNotificationServiceCodec.encodeReliableBlockAck(1)[2]);
        assertEquals(2, SapNotificationServiceCodec.encodeReliableNak(2, 2)[0]);
        final SapPostAuthRecord ack = new SapPostAuthRecord(new SapTransportHeader(0, 1, 210, 0),
                SapNotificationServiceCodec.encodeReliableBlockAck(1));
        final SapPostAuthRecord decodedAck = new SapPostAuthRecordCodec().accept(SapPostAuthRecordCodec.encode(ack)).get(0);
        assertEquals(1, decodedAck.getHeader().getFrameType());
        assertEquals(210, decodedAck.getHeader().getSessionId());
        assertArrayEquals(new byte[]{1, 0, 1}, decodedAck.getBody());
    }

    @Test
    public void reliableSequenceRejectsZeroAndResetsToInitialState() {
        final SapReliableSession reliable = new SapReliableSession();
        assertThrows(IllegalArgumentException.class, () -> reliable.receive(0));
        assertEquals(SapReliableSession.Inbound.ACCEPT, reliable.receive(1));
        reliable.reset();
        assertEquals(1, reliable.getExpectedSequence());
        assertEquals(-1, reliable.consumePendingBlockAck());
    }

    @Test
    public void readyIsIdempotentButDisconnectedIsStillRejected() {
        final SapNotificationServiceRegistration registration = new SapNotificationServiceRegistration(0x1111, 0x2222);
        final SapNotificationServiceAcceptor acceptor = new SapNotificationServiceAcceptor(registration);
        assertThrows(IllegalStateException.class, acceptor::markReady);
        registration.setRemoteConsumer(new SapAgentDescriptor(0x4444, "Gear", Collections.singletonList(
                new SapServiceDescriptor(0x3333, SapNotificationServiceRegistration.PROFILE, 0x0100, 0x05, 0))),
                new SapServiceDescriptor(0x3333, SapNotificationServiceRegistration.PROFILE, 0x0100, 0x05, 0));
        assertEquals(0, acceptor.handle(request(0x2222, 0x3333, 7, 104, 5, 0, 0, 0), Collections.<Integer>emptySet()).getStatus());
        acceptor.beginInitialization();
        acceptor.markReady();
        acceptor.markReady();
        assertEquals(SapNotificationServiceAcceptor.State.READY, acceptor.getState());
    }

    @Test
    public void strictConsumerConnectionAcceptsOnlyApi104ReliableQos() {
        final SapNotificationServiceRegistration registration = new SapNotificationServiceRegistration(0x1111, 0x2222);
        final SapServiceDescriptor remote = new SapServiceDescriptor(0x3333, SapNotificationServiceRegistration.PROFILE, 0x0100, 0x05, 0);
        registration.setRemoteConsumer(new SapAgentDescriptor(0x4444, "Gear", Collections.singletonList(remote)), remote);
        final SapNotificationServiceAcceptor acceptor = new SapNotificationServiceAcceptor(registration);
        final SapServiceConnectionRequest valid = request(0x2222, 0x3333, 555, 104, 5, 0, 0, 0);
        assertEquals(0, acceptor.handle(valid, Collections.<Integer>emptySet()).getStatus());
        assertTrue(acceptor.isApplicationSession(555));
        acceptor.clear();
        assertEquals(1, acceptor.handle(request(0x2222, 0x3333, 555, 104, 4, 0, 0, 0), Collections.<Integer>emptySet()).getStatus());
        assertEquals(3, acceptor.handle(valid, Collections.singleton(555)).getStatus());
    }

    private static SapServiceConnectionRequest request(final int acceptor, final int initiator, final int session, final int channel,
                                                       final int type, final int rate, final int priority, final int payload) {
        return new SapServiceConnectionRequest(acceptor, initiator, SapNotificationServiceRegistration.PROFILE,
                Collections.singletonList(new SapServiceConnectionRequest.Session(session, channel,
                        new SapQosDescriptor(type, rate, priority), payload)));
    }
}
