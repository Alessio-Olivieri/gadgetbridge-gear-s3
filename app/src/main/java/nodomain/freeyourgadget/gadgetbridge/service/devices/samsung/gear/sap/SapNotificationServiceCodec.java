package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Clear API-104 NotificationService framing and binary TLV grammar.
 * This deliberately contains no Host Manager crypto or XML framing.
 */
public final class SapNotificationServiceCodec {
    public static final int PROTOCOL_NORMAL = 2;
    public static final int PROTOCOL_INIT = 15;
    public static final int TYPE_CREATE_POPUP = 0;
    public static final int TYPE_DELETE = 1;
    public static final int TYPE_READ = 2;
    public static final int TYPE_ADDITIONAL_DATA = 3;
    public static final int TYPE_OPEN = 4;
    public static final int TYPE_CREATE_SILENT = 5;
    public static final int TYPE_READ_ALL = 8;
    public static final int TYPE_CLEAR_ALL = 9;
    public static final int TYPE_ACTION = 10;
    public static final int TYPE_ACTION_ACK = 13;
    public static final int ATTR_PACKAGE = 0;
    public static final int ATTR_COUNT = 1;
    public static final int ATTR_TITLE = 2;
    public static final int ATTR_TIME = 3;
    public static final int ATTR_BODY = 5;
    public static final int ATTR_THUMBNAIL = 6;
    public static final int ATTR_APP_NAME = 7;
    public static final int ATTR_HOST_LAUNCH = 10;
    public static final int ATTR_NOTIFICATION_ID = 11;
    public static final int ATTR_ACTIONS = 16;
    public static final int ATTR_ACTION_REQUEST = 17;
    public static final int ATTR_LARGE_ICON = 19;
    /** Native Samsung BigPicture: raw PNG bytes, unlike attr 19's image-data package. */
    public static final int ATTR_BIG_PICTURE = 23;
    public static final int ATTR_CONTENT_TITLE = 26;
    public static final int ATTR_CONTENT_TEXT = 27;
    public static final int ATTR_ALERT_TYPE = 31;
    public static final int ATTR_WINDOW_ID = 32;
    public static final int ATTR_GROUP_KEY = 33;
    public static final int ATTR_SORT_KEY = 34;
    public static final int ATTR_ACK = 35;
    public static final int ATTR_FLAGS = 36;
    public static final int ATTR_NACK = 37;
    /** WearableExtender action JSON, distinct from panel-action attr 16. */
    public static final int ATTR_WEAR_EXTENDER = 41;
    public static final int ATTR_WEAR_ACTION_REQUEST = 42;
    public static final int ATTR_MESSAGING_STYLE = 44;
    public static final int ATTR_CATEGORY = 45;
    public static final int RELIABLE_CONTROL_BLOCK_ACK = 1;
    public static final int RELIABLE_CONTROL_NAK = 2;
    private static final int MAX_ATTRIBUTES = 63;
    private static final int MAX_ATTRIBUTE_LENGTH = 64 * 1024;
    private static final int MAX_INIT_ITEMS = 4096;

    public enum AttributeProfile { LEGACY, MODERN, COMPATIBILITY }

    /** API-104 clear SDK service-channel envelope: reliable sequence + clear/uncompressed header. */
    public static byte[] encodeServiceFrame(final int reliableSequence, final byte[] notificationBytes) {
        if (reliableSequence < 0 || reliableSequence > 0xffff || notificationBytes == null) throw new IllegalArgumentException("Invalid API-104 frame");
        final byte[] frame = new byte[3 + notificationBytes.length];
        frame[0] = (byte) (reliableSequence >>> 8); frame[1] = (byte) reliableSequence;
        frame[2] = 0; // clear, uncompressed SDK service-channel header
        System.arraycopy(notificationBytes, 0, frame, 3, notificationBytes.length);
        return frame;
    }

    public static ServiceFrame decodeServiceFrame(final byte[] frame) {
        if (frame == null || frame.length < 4) throw new IllegalArgumentException("Truncated API-104 service frame");
        final int sdk = frame[2] & 0xff;
        if (sdk != 0) throw new IllegalArgumentException("Unsupported encrypted, compressed, or fragmented NotificationService SDK frame 0x" + Integer.toHexString(sdk));
        return new ServiceFrame(((frame[0] & 0xff) << 8) | (frame[1] & 0xff), Arrays.copyOfRange(frame, 3, frame.length));
    }

    /** Generic SAP reliable control body; transport header carries frameType=control and session ID. */
    public static byte[] encodeReliableBlockAck(final int sequence) {
        validateReliableSequence(sequence);
        return new byte[]{RELIABLE_CONTROL_BLOCK_ACK, (byte) (sequence >>> 8), (byte) sequence};
    }

    /** Generic SAP NAK body for one missing inclusive sequence range. */
    public static byte[] encodeReliableNak(final int firstMissing, final int lastMissing) {
        validateReliableSequence(firstMissing); validateReliableSequence(lastMissing);
        return new byte[]{RELIABLE_CONTROL_NAK, 1, (byte) (firstMissing >>> 8), (byte) firstMissing,
                (byte) (lastMissing >>> 8), (byte) lastMissing};
    }

    public static byte[] encode(final Message message) {
        if (message == null) throw new IllegalArgumentException("Missing notification message");
        if (message.protocolVersion == PROTOCOL_INIT) throw new IllegalArgumentException("Gear initialization is receive-only");
        if (message.protocolVersion != PROTOCOL_NORMAL) throw new IllegalArgumentException("Unsupported notification protocol version");
        validateSequence(message.sequence);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((PROTOCOL_NORMAL << 4) | (message.type & 0x0f));
        out.write((message.urgent ? 0x40 : 0) | ((message.source & 0x07) << 3) | ((message.sequence >>> 16) & 0x03));
        out.write((message.sequence >>> 8) & 0xff); out.write(message.sequence & 0xff);
        if (message.type == TYPE_DELETE || (message.type == TYPE_READ && message.windowId != null)) {
            final byte[] window = utf8(message.windowId);
            writeU32(out, window.length); out.write(window, 0, window.length);
            return out.toByteArray();
        }
        if (message.appId < 0 || message.appId > 0xffff || message.category < 0 || message.category > 0xff || message.attributes.size() > MAX_ATTRIBUTES) throw new IllegalArgumentException("Invalid notification header");
        writeU16(out, message.appId); out.write(message.category); out.write(message.attributes.size());
        for (final Attribute attribute : message.attributes) encodeAttribute(out, attribute);
        return out.toByteArray();
    }

    public static Message decode(final byte[] data) {
        if (data == null || data.length < 1) throw new IllegalArgumentException("Empty NotificationService message");
        final int protocol = (data[0] >>> 4) & 0x0f;
        if (protocol == PROTOCOL_INIT) throw new IllegalArgumentException("Use decodeInitialization for protocol v15");
        if (protocol != PROTOCOL_NORMAL && protocol != 3 && protocol != 4) throw new IllegalArgumentException("Unknown NotificationService protocol version " + protocol);
        if (protocol == 4) return new Message(protocol, data[0] & 0x0f, 0, false, 0, 0, 0, Collections.<Attribute>emptyList(), null, Arrays.copyOfRange(data, 1, data.length));
        require(data, 0, 4);
        final int type = data[0] & 0x0f;
        final boolean urgent = (data[1] & 0x40) != 0;
        final int source = (data[1] >>> 3) & 0x07;
        final int sequence = ((data[1] & 0x03) << 16) | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        int offset = 4;
        if (protocol == PROTOCOL_NORMAL && (type == TYPE_DELETE || type == TYPE_READ) && data.length > 4) {
            final int length = readU32(data, offset); offset += 4; require(data, offset, length);
            final String window = new String(data, offset, length, StandardCharsets.UTF_8); offset += length;
            if (offset != data.length) throw new IllegalArgumentException("Trailing compact NotificationService bytes");
            return new Message(protocol, type, sequence, urgent, source, 0, 0, Collections.<Attribute>emptyList(), window, null);
        }
        // Protocol-2 type 3 is an additional-information request for
        // the notification sequence; it has no TLV header.
        if (protocol == 3 || (protocol == PROTOCOL_NORMAL && (type == TYPE_DELETE || type == TYPE_READ || type == TYPE_ADDITIONAL_DATA))) {
            if (offset != data.length) throw new IllegalArgumentException("Unexpected bytes after compact NotificationService event");
            return new Message(protocol, type, sequence, urgent, source, 0, 0, Collections.<Attribute>emptyList(), null, null);
        }
        require(data, offset, 4);
        final int appId = readU16(data, offset); final int category = data[offset + 2] & 0xff; final int count = data[offset + 3] & 0x3f; offset += 4;
        final List<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            require(data, offset, 4); final int attrType = data[offset] & 0xff; final int length = readU24(data, offset + 1); offset += 4;
            if (length > MAX_ATTRIBUTE_LENGTH) throw new IllegalArgumentException("Overlong NotificationService attribute");
            require(data, offset, length); attributes.add(new Attribute(attrType, Arrays.copyOfRange(data, offset, offset + length))); offset += length;
        }
        if (offset != data.length) throw new IllegalArgumentException("Trailing NotificationService TLV bytes");
        return new Message(protocol, type, sequence, urgent, source, appId, category, attributes, null, null);
    }

    public static Initialization decodeInitialization(final byte[] data) {
        require(data, 0, 1);
        if (((data[0] >>> 4) & 0x0f) != PROTOCOL_INIT) throw new IllegalArgumentException("Not a NotificationService v15 initialization");
        int offset = 1; require(data, offset, 3);
        final int lastSequence = ((data[offset] & 0x03) << 16) | ((data[offset + 1] & 0xff) << 8) | (data[offset + 2] & 0xff); offset += 3;
        final int unreadCount = readCount(data, offset); offset += 4;
        final List<Integer> unread = new ArrayList<>();
        for (int i = 0; i < unreadCount; i++) { require(data, offset, 3); unread.add(((data[offset] & 0xff) << 16) | ((data[offset + 1] & 0xff) << 8) | (data[offset + 2] & 0xff)); offset += 3; }
        final int clearTypeCount = readCount(data, offset); offset += 4;
        final List<ClearRecord> clears = new ArrayList<>();
        for (int i = 0; i < clearTypeCount; i++) {
            require(data, offset, 1); final int type = data[offset++] & 0xff;
            if (type == 1) clears.add(new ClearRecord(type, Collections.<Integer>emptyList(), Collections.<String>emptyList()));
            else if (type == 2) { final int count = readCount(data, offset); offset += 4; final List<String> categories = new ArrayList<>(); for (int j = 0; j < count; j++) { final int length = readCount(data, offset); offset += 4; require(data, offset, length); categories.add(new String(data, offset, length, StandardCharsets.UTF_8)); offset += length; } clears.add(new ClearRecord(type, Collections.<Integer>emptyList(), categories)); }
            else if (type == 3) { final int count = readCount(data, offset); offset += 4; final List<Integer> sequences = new ArrayList<>(); for (int j = 0; j < count; j++) { sequences.add(readU32(data, offset)); offset += 4; } clears.add(new ClearRecord(type, sequences, Collections.<String>emptyList())); }
            else throw new IllegalArgumentException("Unsupported v15 clear record type " + type);
        }
        require(data, offset, 2); final int panelSequence = readU16(data, offset) + 229376; offset += 2;
        final int blockedCount = readCount(data, offset); offset += 4;
        final List<Integer> blockedApps = new ArrayList<>();
        for (int i = 0; i < blockedCount; i++) { require(data, offset, 2); blockedApps.add(readU16(data, offset)); offset += 2; }
        if (offset != data.length) throw new IllegalArgumentException("Trailing NotificationService v15 initialization bytes");
        return new Initialization(data[0] & 0x0f, lastSequence, unread, clears, panelSequence, blockedApps);
    }

    public static Message create(final int sequence, final int appId, final boolean popup, final AttributeProfile profile,
                                 final String packageName, final String appName, final String title, final String body,
                                 final long timestamp, final int notificationId, final String windowId, final String category, final int flags) {
        return create(sequence, appId, popup, profile, packageName, appName, title, body, timestamp, notificationId,
                windowId, category, flags, null);
    }

    /** B3/protocol-2 app metadata is carried with the notification as attr 6 (PNG thumbnail). */
    public static Message create(final int sequence, final int appId, final boolean popup, final AttributeProfile profile,
                                 final String packageName, final String appName, final String title, final String body,
                                 final long timestamp, final int notificationId, final String windowId, final String category,
                                 final int flags, final byte[] thumbnail) {
        if (packageName == null || windowId == null) throw new IllegalArgumentException("Notification package/window ID required");
        final List<Attribute> attrs = new ArrayList<>();
        attrs.add(stringAttribute(ATTR_PACKAGE, packageName));
        attrs.add(new Attribute(ATTR_COUNT, new byte[]{0, 0}));
        if (profile == AttributeProfile.LEGACY || profile == AttributeProfile.COMPATIBILITY) { if (title != null) attrs.add(stringAttribute(ATTR_TITLE, title)); if (body != null) attrs.add(stringAttribute(ATTR_BODY, body)); }
        if (profile == AttributeProfile.MODERN || profile == AttributeProfile.COMPATIBILITY) { if (title != null) attrs.add(stringAttribute(ATTR_CONTENT_TITLE, title)); if (body != null) attrs.add(stringAttribute(ATTR_CONTENT_TEXT, body)); }
        attrs.add(new Attribute(ATTR_TIME, longBytes(timestamp)));
        if (appName != null && !appName.isEmpty()) attrs.add(stringAttribute(ATTR_APP_NAME, appName));
        if (thumbnail != null && thumbnail.length > 0) attrs.add(new Attribute(ATTR_THUMBNAIL, thumbnail));
        attrs.add(new Attribute(ATTR_NOTIFICATION_ID, intBytes(notificationId)));
        attrs.add(stringAttribute(ATTR_WINDOW_ID, windowId));
        if (category != null && !category.isEmpty()) attrs.add(stringAttribute(ATTR_CATEGORY, category));
        if (flags != 0) attrs.add(new Attribute(ATTR_FLAGS, intBytes(flags)));
        return new Message(PROTOCOL_NORMAL, popup ? TYPE_CREATE_POPUP : TYPE_CREATE_SILENT, sequence, false, 0, appId, 0, attrs, null, null);
    }

    /** B3 extensions: attr 19 image package, attr 16 panel actions, attr 44 MessagingStyle JSON. */
    public static Message createRich(final int sequence, final int appId, final boolean popup, final AttributeProfile profile,
                                     final String packageName, final String appName, final String title, final String body,
                                     final long timestamp, final int notificationId, final String windowId, final String category,
                                     final int flags, final byte[] thumbnail, final byte[] largeIcon, final String largeIconUri,
                                     final String actionsJson, final String wearableExtenderJson, final String messagingStyleJson,
                                     final byte[] bigPicture) {
        final Message base = create(sequence, appId, popup, profile, packageName, appName, title, body, timestamp,
                notificationId, windowId, category, flags, thumbnail);
        final List<Attribute> attrs = new ArrayList<>(base.attributes);
        if (largeIcon != null && largeIcon.length > 0) attrs.add(new Attribute(ATTR_LARGE_ICON, imageDataPack(largeIcon, largeIconUri, ATTR_LARGE_ICON)));
        if (actionsJson != null && !actionsJson.isEmpty()) attrs.add(stringAttribute(ATTR_ACTIONS, actionsJson));
        if (wearableExtenderJson != null && !wearableExtenderJson.isEmpty()) attrs.add(stringAttribute(ATTR_WEAR_EXTENDER, wearableExtenderJson));
        if (messagingStyleJson != null && !messagingStyleJson.isEmpty()) attrs.add(stringAttribute(ATTR_MESSAGING_STYLE, messagingStyleJson));
        if (bigPicture != null && bigPicture.length > 0) attrs.add(new Attribute(ATTR_BIG_PICTURE, bigPicture));
        return new Message(PROTOCOL_NORMAL, base.type, sequence, false, 0, appId, 0, attrs, null, null);
    }

    /** Image data package: U16 URI length, URI, image attribute type, U32 image length, PNG bytes. */
    public static byte[] imageDataPack(final byte[] png, final String uri, final int imageAttributeType) {
        if (png == null || png.length == 0 || png.length > MAX_ATTRIBUTE_LENGTH) throw new IllegalArgumentException("Invalid NotificationService image");
        final byte[] uriBytes = utf8(uri == null ? "" : uri);
        if (uriBytes.length > 0xffff) throw new IllegalArgumentException("NotificationService image URI too long");
        final ByteArrayOutputStream out = new ByteArrayOutputStream(7 + uriBytes.length + png.length);
        writeU16(out, uriBytes.length); out.write(uriBytes, 0, uriBytes.length); out.write(imageAttributeType); writeU32(out, png.length); out.write(png, 0, png.length);
        return out.toByteArray();
    }

    /** Type 13: request notification sequence plus attr 35 success or attr 37 failure action ID. */
    public static Message actionAck(final int notificationSequence, final int actionId, final boolean success) {
        validateSequence(notificationSequence);
        return new Message(PROTOCOL_NORMAL, TYPE_ACTION_ACK, notificationSequence, false, 0, 0, 0,
                Collections.singletonList(new Attribute(success ? ATTR_ACK : ATTR_NACK, intBytes(actionId))), null, null);
    }

    /** Returns an otherwise identical message without an optional attribute. */
    public static Message withoutAttribute(final Message message, final int attributeType) {
        final List<Attribute> attrs = new ArrayList<>();
        for (final Attribute attribute : message.attributes) if (attribute.type != attributeType) attrs.add(attribute);
        return new Message(message.protocolVersion, message.type, message.sequence, message.urgent, message.source,
                message.appId, message.category, attrs, message.windowId, message.opaque);
    }

    public static Message delete(final int sequence, final String windowId) { return new Message(PROTOCOL_NORMAL, TYPE_DELETE, sequence, false, 0, 0, 0, Collections.<Attribute>emptyList(), windowId, null); }
    public static Attribute stringAttribute(final int type, final String value) { return new Attribute(type, utf8(value)); }
    private static void encodeAttribute(final ByteArrayOutputStream out, final Attribute attr) { if (attr == null || attr.value.length > MAX_ATTRIBUTE_LENGTH) throw new IllegalArgumentException("Invalid NotificationService attribute"); out.write(attr.type); writeU24(out, attr.value.length); out.write(attr.value, 0, attr.value.length); }
    private static byte[] utf8(final String value) { if (value == null) throw new IllegalArgumentException("Null UTF-8 value"); return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] longBytes(final long value) { final byte[] b = new byte[8]; for (int i = 7; i >= 0; i--) b[7 - i] = (byte) (value >>> (i * 8)); return b; }
    private static byte[] intBytes(final int value) { return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}; }
    private static void writeU16(final ByteArrayOutputStream o, final int v) { o.write(v >>> 8); o.write(v); }
    private static void writeU24(final ByteArrayOutputStream o, final int v) { o.write(v >>> 16); o.write(v >>> 8); o.write(v); }
    private static void writeU32(final ByteArrayOutputStream o, final int v) { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v); }
    private static int readU16(final byte[] b, final int o) { require(b, o, 2); return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static int readU24(final byte[] b, final int o) { require(b, o, 3); return ((b[o] & 0xff) << 16) | ((b[o + 1] & 0xff) << 8) | (b[o + 2] & 0xff); }
    private static int readU32(final byte[] b, final int o) { require(b, o, 4); return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    private static int readCount(final byte[] b, final int o) { final int count = readU32(b, o); if (count < 0 || count > MAX_INIT_ITEMS) throw new IllegalArgumentException("Invalid NotificationService initialization count"); return count; }
    private static void require(final byte[] b, final int o, final int n) { if (b == null || o < 0 || n < 0 || b.length - o < n) throw new IllegalArgumentException("Truncated NotificationService payload"); }
    private static void validateSequence(final int sequence) { if (sequence < 0 || sequence >= 0x40000) throw new IllegalArgumentException("Invalid NotificationService sequence"); }
    private static void validateReliableSequence(final int sequence) { if (sequence < 1 || sequence > 0xffff) throw new IllegalArgumentException("Invalid reliable SAP sequence"); }

    public static final class ServiceFrame { private final int sequence; private final byte[] payload; ServiceFrame(final int sequence, final byte[] payload) { this.sequence = sequence; this.payload = payload; } public int getSequence() { return sequence; } public byte[] getPayload() { return Arrays.copyOf(payload, payload.length); } }
    public static final class Attribute { private final int type; private final byte[] value; public Attribute(final int type, final byte[] value) { if (type < 0 || type > 255 || value == null) throw new IllegalArgumentException("Invalid notification attribute"); this.type = type; this.value = Arrays.copyOf(value, value.length); } public int getType() { return type; } public byte[] getValue() { return Arrays.copyOf(value, value.length); } public String getUtf8() { return new String(value, StandardCharsets.UTF_8); } }
    public static final class Message { private final int protocolVersion, type, sequence, source, appId, category; private final boolean urgent; private final List<Attribute> attributes; private final String windowId; private final byte[] opaque; public Message(final int protocolVersion, final int type, final int sequence, final boolean urgent, final int source, final int appId, final int category, final List<Attribute> attributes, final String windowId, final byte[] opaque) { this.protocolVersion = protocolVersion; this.type = type; this.sequence = sequence; this.urgent = urgent; this.source = source; this.appId = appId; this.category = category; this.attributes = Collections.unmodifiableList(new ArrayList<>(attributes)); this.windowId = windowId; this.opaque = opaque == null ? null : Arrays.copyOf(opaque, opaque.length); } public int getProtocolVersion() { return protocolVersion; } public int getType() { return type; } public int getSequence() { return sequence; } public int getAppId() { return appId; } public int getCategory() { return category; } public List<Attribute> getAttributes() { return attributes; } public String getWindowId() { return windowId; } public Attribute findAttribute(final int type) { for (final Attribute a : attributes) if (a.type == type) return a; return null; } }
    public static final class Initialization { private final int flags, lastSequence, panelSequence; private final List<Integer> unread, blockedApps; private final List<ClearRecord> clears; Initialization(final int flags, final int lastSequence, final List<Integer> unread, final List<ClearRecord> clears, final int panelSequence, final List<Integer> blockedApps) { this.flags = flags; this.lastSequence = lastSequence; this.unread = Collections.unmodifiableList(new ArrayList<>(unread)); this.clears = Collections.unmodifiableList(new ArrayList<>(clears)); this.panelSequence = panelSequence; this.blockedApps = Collections.unmodifiableList(new ArrayList<>(blockedApps)); } public int getLastSequence() { return lastSequence; } public int getPanelSequence() { return panelSequence; } public List<Integer> getUnread() { return unread; } public List<ClearRecord> getClears() { return clears; } public List<Integer> getBlockedApps() { return blockedApps; } }
    public static final class ClearRecord { private final int type; private final List<Integer> sequences; private final List<String> categories; ClearRecord(final int type, final List<Integer> sequences, final List<String> categories) { this.type = type; this.sequences = Collections.unmodifiableList(new ArrayList<>(sequences)); this.categories = Collections.unmodifiableList(new ArrayList<>(categories)); } public int getType() { return type; } }
}
