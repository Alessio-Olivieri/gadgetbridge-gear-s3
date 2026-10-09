package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict grammar for service-connection bodies carried on SAP session 1023. */
public final class SapServiceConnectionCodec {
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_PROFILE_BYTES = 255;
    private SapServiceConnectionCodec() { }

    public static Object parse(final byte[] body) {
        if (body == null || body.length == 0) throw new IllegalArgumentException("Empty SAP service-control body");
        if ((body[0] & 0xff) == SapServiceConnectionRequest.TYPE) return parseRequest(body);
        if ((body[0] & 0xff) == SapServiceConnectionResponse.TYPE) return parseResponse(body);
        if ((body[0] & 0xff) == SapServiceConnectionTermination.TYPE) return parseTermination(body);
        if ((body[0] & 0xff) == SapServiceConnectionTerminationResponse.TYPE) return parseTerminationResponse(body);
        throw new IllegalArgumentException("Unknown SAP service-control message type " + (body[0] & 0xff));
    }
    public static SapServiceConnectionRequest parseRequest(final byte[] body) {
        requireType(body, SapServiceConnectionRequest.TYPE);
        int offset = 1;
        final int acceptor = readU16(body, offset); offset += 2;
        final int initiator = readU16(body, offset); offset += 2;
        final String profile = readProfile(body, offset); offset += profile.getBytes(StandardCharsets.UTF_8).length + 1;
        final int count = readCount(body, offset); offset += 2;
        requireRemaining(body, offset, count * 8);
        final int[] sessionIds = new int[count];
        final int[] channels = new int[count];
        for (int i = 0; i < count; i++, offset += 2) sessionIds[i] = readU16(body, offset);
        for (int i = 0; i < count; i++, offset += 2) channels[i] = readU16(body, offset);
        final SapQosDescriptor[] qos = new SapQosDescriptor[count];
        for (int i = 0; i < count; i++, offset += 3) qos[i] = new SapQosDescriptor(body[offset] & 0xff, body[offset + 1] & 0xff, body[offset + 2] & 0xff);
        final List<SapServiceConnectionRequest.Session> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++, offset++) sessions.add(new SapServiceConnectionRequest.Session(sessionIds[i], channels[i], qos[i], body[offset] & 0xff));
        if (offset != body.length) throw new IllegalArgumentException("Trailing bytes in SAP service request");
        return new SapServiceConnectionRequest(acceptor, initiator, profile, sessions);
    }
    public static SapServiceConnectionResponse parseResponse(final byte[] body) {
        requireType(body, SapServiceConnectionResponse.TYPE);
        int offset = 1;
        final int acceptor = readU16(body, offset); offset += 2;
        final int initiator = readU16(body, offset); offset += 2;
        final String profile = readProfile(body, offset); offset += profile.getBytes(StandardCharsets.UTF_8).length + 1;
        requireRemaining(body, offset, 3);
        final int status = body[offset++] & 0xff;
        final int count = readCount(body, offset); offset += 2;
        requireRemaining(body, offset, count * 2);
        final List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count; i++, offset += 2) ids.add(readU16(body, offset));
        if (offset != body.length) throw new IllegalArgumentException("Trailing bytes in SAP service response");
        return new SapServiceConnectionResponse(acceptor, initiator, profile, status, ids);
    }
    public static SapServiceConnectionTermination parseTermination(final byte[] body) {
        requireType(body, SapServiceConnectionTermination.TYPE);
        int offset = 1;
        final int acceptor = readU16(body, offset); offset += 2;
        final int initiator = readU16(body, offset); offset += 2;
        final String profile = readProfile(body, offset); offset += profile.getBytes(StandardCharsets.UTF_8).length + 1;
        if (offset != body.length) throw new IllegalArgumentException("Trailing bytes in SAP service termination");
        return new SapServiceConnectionTermination(acceptor, initiator, profile);
    }
    public static SapServiceConnectionTerminationResponse parseTerminationResponse(final byte[] body) {
        requireType(body, SapServiceConnectionTerminationResponse.TYPE);
        int offset = 1;
        final int acceptor = readU16(body, offset); offset += 2;
        final int initiator = readU16(body, offset); offset += 2;
        final String profile = readProfile(body, offset); offset += profile.getBytes(StandardCharsets.UTF_8).length + 1;
        requireRemaining(body, offset, 1);
        final int status = body[offset++] & 0xff;
        if (offset != body.length) throw new IllegalArgumentException("Trailing bytes in SAP service termination response");
        return new SapServiceConnectionTerminationResponse(acceptor, initiator, profile, status);
    }
    public static byte[] encode(final SapServiceConnectionRequest request) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SapServiceConnectionRequest.TYPE); writeU16(out, request.getAcceptorComponentId()); writeU16(out, request.getInitiatorComponentId()); writeProfile(out, request.getProfile()); writeU16(out, request.getSessions().size());
        for (final SapServiceConnectionRequest.Session session : request.getSessions()) writeU16(out, session.getSessionId());
        for (final SapServiceConnectionRequest.Session session : request.getSessions()) writeU16(out, session.getChannelId());
        for (final SapServiceConnectionRequest.Session session : request.getSessions()) { out.write(session.getQos().getType()); out.write(session.getQos().getDataRate()); out.write(session.getQos().getClassType()); }
        for (final SapServiceConnectionRequest.Session session : request.getSessions()) out.write(session.getPayloadType());
        return out.toByteArray();
    }
    public static byte[] encode(final SapServiceConnectionResponse response) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SapServiceConnectionResponse.TYPE); writeU16(out, response.getAcceptorSlot()); writeU16(out, response.getInitiatorSlot()); writeProfile(out, response.getProfile()); out.write(response.getStatus()); writeU16(out, response.getSessionIds().size());
        for (final int id : response.getSessionIds()) writeU16(out, id);
        return out.toByteArray();
    }
    public static byte[] encode(final SapServiceConnectionTermination termination) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SapServiceConnectionTermination.TYPE);
        writeU16(out, termination.getAcceptorComponentId());
        writeU16(out, termination.getInitiatorComponentId());
        writeProfile(out, termination.getProfile());
        return out.toByteArray();
    }
    public static byte[] encode(final SapServiceConnectionTerminationResponse response) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SapServiceConnectionTerminationResponse.TYPE);
        writeU16(out, response.getAcceptorComponentId());
        writeU16(out, response.getInitiatorComponentId());
        writeProfile(out, response.getProfile());
        out.write(response.getStatus());
        return out.toByteArray();
    }
    public static SapServiceConnectionTerminationResponse terminationResponse(
            final SapServiceConnectionTermination request, final int status) {
        return new SapServiceConnectionTerminationResponse(request.getInitiatorComponentId(),
                request.getAcceptorComponentId(), request.getProfile(), status);
    }
    private static void requireType(final byte[] body, final int type) { if (body == null || body.length < 1 || (body[0] & 0xff) != type) throw new IllegalArgumentException("Unexpected SAP service-control type"); }
    private static int readU16(final byte[] data, final int offset) { requireRemaining(data, offset, 2); return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff); }
    private static int readCount(final byte[] data, final int offset) { final int count = readU16(data, offset); if (count == 0 || count > MAX_SESSIONS) throw new IllegalArgumentException("Invalid SAP service session count " + count + " at offset " + offset); return count; }
    private static String readProfile(final byte[] data, final int offset) { int end = offset; while (end < data.length && data[end] != ';' && end - offset <= MAX_PROFILE_BYTES) end++; if (end == data.length || end - offset > MAX_PROFILE_BYTES) throw new IllegalArgumentException("Missing or overlong SAP service profile terminator: offset=" + offset + " bodyBytes=" + data.length); return new String(data, offset, end - offset, StandardCharsets.UTF_8); }
    private static void writeProfile(final ByteArrayOutputStream out, final String profile) { final byte[] bytes = profile.getBytes(StandardCharsets.UTF_8); if (bytes.length == 0 || bytes.length > MAX_PROFILE_BYTES || profile.indexOf(';') >= 0) throw new IllegalArgumentException("Invalid SAP service profile"); out.write(bytes, 0, bytes.length); out.write(';'); }
    private static void writeU16(final ByteArrayOutputStream out, final int value) { out.write((value >>> 8) & 0xff); out.write(value & 0xff); }
    private static void requireRemaining(final byte[] data, final int offset, final int length) { if (offset < 0 || length < 0 || data == null || data.length - offset < length) throw new IllegalArgumentException("Truncated SAP service-control body: offset=" + offset + " required=" + length + " bodyBytes=" + (data == null ? 0 : data.length)); }
}
