package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict encoder/parser for SAP 3.x capability-exchange bodies on session 1020. */
public final class SapCapexCodec {
    public static final int TYPE_QUERY = 1;
    public static final int TYPE_RESPONSE = 2;
    public static final int QUERY_TYPE_SELECTIVE = 2;
    public static final int QUERY_TYPE_PERSISTENT = 3;
    private static final int MAX_PROFILES = 255;
    private static final int MAX_AGENTS = 1024;
    private static final int MAX_SERVICES_PER_AGENT = 1024;
    private static final int MAX_VARIABLE_STRING_BYTES = 64;

    private SapCapexCodec() { }

    public static Query parseQuery(final byte[] body) {
        if (body == null || body.length < 3 || unsigned8(body, 0) != TYPE_QUERY) throw new IllegalArgumentException("Invalid CapEx query");
        final int queryType = unsigned8(body, 1);
        int offset;
        final long checksum;
        final int count;
        if (queryType == QUERY_TYPE_SELECTIVE || queryType == QUERY_TYPE_PERSISTENT) {
            require(body, 2, 5);
            checksum = unsigned32(body, 2);
            count = unsigned8(body, 6);
            offset = 7;
        } else {
            checksum = -1;
            count = unsigned8(body, 2);
            offset = 3;
        }
        if (count > MAX_PROFILES) throw new IllegalArgumentException("Too many CapEx profile filters");
        final List<String> profiles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final StringResult profile = readProfile(body, offset);
            profiles.add(profile.value);
            offset = profile.offset;
        }
        if (offset != body.length) throw new IllegalArgumentException("Trailing bytes in CapEx query");
        return new Query(queryType, checksum, profiles);
    }

    public static Response parseResponse(final byte[] body) {
        if (body == null || body.length < 4 || unsigned8(body, 0) != TYPE_RESPONSE || unsigned8(body, 1) != QUERY_TYPE_PERSISTENT) {
            throw new IllegalArgumentException("Invalid CapEx response");
        }
        require(body, 2, 6);
        final long checksum = unsigned32(body, 2);
        final int agentCount = unsigned16(body, 6);
        if (agentCount > MAX_AGENTS) throw new IllegalArgumentException("Too many CapEx agents");
        int offset = 8;
        final List<SapAgentDescriptor> agents = new ArrayList<>();
        for (int i = 0; i < agentCount; i++) {
            require(body, offset, 2);
            final int uuid = unsigned16(body, offset);
            offset += 2;
            final StringResult name = readTerminatedString(body, offset);
            offset = name.offset;
            require(body, offset, 2);
            final int serviceCount = unsigned16(body, offset);
            offset += 2;
            if (serviceCount > MAX_SERVICES_PER_AGENT) throw new IllegalArgumentException("Too many CapEx services");
            final List<SapServiceDescriptor> services = new ArrayList<>();
            for (int j = 0; j < serviceCount; j++) {
                require(body, offset, 2);
                final int componentId = unsigned16(body, offset);
                offset += 2;
                final StringResult profile = readProfile(body, offset);
                offset = profile.offset;
                require(body, offset, 5);
                services.add(new SapServiceDescriptor(componentId, profile.value, unsigned16(body, offset),
                        unsigned8(body, offset + 2), unsigned16(body, offset + 3)));
                offset += 5;
            }
            agents.add(new SapAgentDescriptor(uuid, name.value, services));
        }
        if (offset != body.length) throw new IllegalArgumentException("Trailing bytes in CapEx response");
        return new Response(checksum, agents);
    }

    public static byte[] encodeQuery(final Query query) {
        if (query == null || query.getProfiles().size() > MAX_PROFILES) throw new IllegalArgumentException("Invalid CapEx query");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_QUERY);
        out.write(query.getQueryType());
        if (query.getQueryType() == QUERY_TYPE_SELECTIVE || query.getQueryType() == QUERY_TYPE_PERSISTENT) {
            write32(out, query.getChecksum());
        }
        out.write(query.getProfiles().size());
        for (final String profile : query.getProfiles()) writeProfile(out, profile);
        return out.toByteArray();
    }

    public static byte[] encodeResponse(final Response response) {
        if (response == null || response.getAgents().size() > MAX_AGENTS) throw new IllegalArgumentException("Invalid CapEx response");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_RESPONSE);
        out.write(QUERY_TYPE_PERSISTENT);
        write32(out, response.getChecksum());
        write16(out, response.getAgents().size());
        for (final SapAgentDescriptor agent : response.getAgents()) {
            if (agent.getServices().size() > MAX_SERVICES_PER_AGENT) throw new IllegalArgumentException("Too many CapEx services");
            write16(out, agent.getUuid());
            writeString(out, agent.getFriendlyName());
            write16(out, agent.getServices().size());
            for (final SapServiceDescriptor service : agent.getServices()) {
                write16(out, service.getComponentId());
                writeProfile(out, service.getProfileId());
                write16(out, service.getAspVersion());
                out.write(service.getRoleFlags());
                write16(out, service.getTimeout());
            }
        }
        return out.toByteArray();
    }

    public static final class Query {
        private final int queryType;
        private final long checksum;
        private final List<String> profiles;
        public Query(final int queryType, final long checksum, final List<String> profiles) {
            if (queryType < 0 || queryType > 0xff || checksum < -1 || checksum > 0xffffffffL || profiles == null) throw new IllegalArgumentException("Invalid CapEx query");
            this.queryType = queryType;
            this.checksum = checksum;
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        }
        public int getQueryType() { return queryType; }
        public long getChecksum() { return checksum; }
        public List<String> getProfiles() { return profiles; }
    }

    public static final class Response {
        private final long checksum;
        private final List<SapAgentDescriptor> agents;
        public Response(final long checksum, final List<SapAgentDescriptor> agents) {
            if (checksum < 0 || checksum > 0xffffffffL || agents == null) throw new IllegalArgumentException("Invalid CapEx response");
            this.checksum = checksum;
            this.agents = Collections.unmodifiableList(new ArrayList<>(agents));
        }
        public long getChecksum() { return checksum; }
        public List<SapAgentDescriptor> getAgents() { return agents; }
    }

    private static StringResult readProfile(final byte[] body, final int offset) {
        if (offset >= body.length) throw new IllegalArgumentException("Truncated CapEx profile");
        if (body[offset] == '=') {
            require(body, offset, 17);
            return new StringResult(new String(body, offset, 17, StandardCharsets.UTF_8), offset + 17);
        }
        return readTerminatedString(body, offset);
    }

    private static StringResult readTerminatedString(final byte[] body, final int offset) {
        int end = offset;
        while (end < body.length && end - offset <= MAX_VARIABLE_STRING_BYTES && body[end] != ';') end++;
        if (end == body.length || end - offset > MAX_VARIABLE_STRING_BYTES) throw new IllegalArgumentException("Invalid CapEx terminated string");
        return new StringResult(new String(body, offset, end - offset, StandardCharsets.UTF_8), end + 1);
    }

    private static void writeProfile(final ByteArrayOutputStream out, final String profile) {
        if (profile == null) throw new IllegalArgumentException("Missing CapEx profile");
        final byte[] value = profile.getBytes(StandardCharsets.UTF_8);
        if (value.length == 17 && value[0] == '=') out.write(value, 0, value.length);
        else writeString(out, profile);
    }

    private static void writeString(final ByteArrayOutputStream out, final String value) {
        if (value == null || value.indexOf(';') >= 0) throw new IllegalArgumentException("Invalid CapEx string");
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_VARIABLE_STRING_BYTES) throw new IllegalArgumentException("CapEx string too long");
        out.write(bytes, 0, bytes.length);
        out.write(';');
    }
    private static void require(final byte[] body, final int offset, final int length) { if (offset < 0 || length < 0 || body.length - offset < length) throw new IllegalArgumentException("Truncated CapEx body"); }
    private static int unsigned8(final byte[] body, final int offset) { return body[offset] & 0xff; }
    private static int unsigned16(final byte[] body, final int offset) { return (unsigned8(body, offset) << 8) | unsigned8(body, offset + 1); }
    private static long unsigned32(final byte[] body, final int offset) { return ((long) unsigned8(body, offset) << 24) | ((long) unsigned8(body, offset + 1) << 16) | ((long) unsigned8(body, offset + 2) << 8) | unsigned8(body, offset + 3); }
    private static void write16(final ByteArrayOutputStream out, final int value) { out.write(value >>> 8); out.write(value); }
    private static void write32(final ByteArrayOutputStream out, final long value) { out.write((int) (value >>> 24)); out.write((int) (value >>> 16)); out.write((int) (value >>> 8)); out.write((int) value); }
    private static final class StringResult { private final String value; private final int offset; private StringResult(final String value, final int offset) { this.value = value; this.offset = offset; } }
}
