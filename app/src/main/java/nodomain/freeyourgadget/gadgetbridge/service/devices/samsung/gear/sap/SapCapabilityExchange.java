package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/** Session-1020 CapEx state and responder. */
public final class SapCapabilityExchange {
    public enum State { WAIT_RESPONSE, WAIT_RECIPROCAL_QUERY, WAIT_PEER_QUERY, WAIT_FOLLOW_UP }

    private final int localProfileCount;
    private final int remoteProfileCount;
    private final int role;
    private final List<LocalServiceMetadata> localServices;
    private final Set<String> persistentProfiles = new LinkedHashSet<>();
    private State state;
    private List<SapAgentDescriptor> remoteAgents = Collections.emptyList();
    private SapCapexCodec.Query reciprocalQuery;

    public SapCapabilityExchange(final int localProfileCount, final int remoteProfileCount, final int role) {
        this(localProfileCount, remoteProfileCount, role, Collections.<LocalServiceMetadata>emptyList());
    }

    public SapCapabilityExchange(final int localProfileCount,
                                 final int remoteProfileCount,
                                 final int role,
                                 final List<LocalServiceMetadata> localServices) {
        if (localProfileCount < 0 || localProfileCount > 255 || remoteProfileCount < -1 || remoteProfileCount > 255
                || role < 0 || role > 1 || localServices == null) {
            throw new IllegalArgumentException("Invalid CapEx peer description counts");
        }
        this.localProfileCount = localProfileCount;
        this.remoteProfileCount = remoteProfileCount;
        this.role = role;
        this.localServices = Collections.unmodifiableList(new ArrayList<>(localServices));
        this.state = shouldWaitForPeerQuery(localProfileCount, remoteProfileCount, role)
                ? State.WAIT_PEER_QUERY : State.WAIT_RESPONSE;
    }

    public static boolean shouldWaitForPeerQuery(final int localProfileCount, final int remoteProfileCount, final int role) {
        return remoteProfileCount != -1 && (remoteProfileCount == localProfileCount ? role == 1 : remoteProfileCount < localProfileCount);
    }

    public SapPostAuthRecord createInitialQuery(final long peerChecksum) {
        if (state != State.WAIT_RESPONSE) throw new IllegalStateException("CapEx local side is not the query initiator");
        final Set<String> profiles = new LinkedHashSet<>();
        for (final LocalServiceMetadata service : localServices) profiles.add(service.profileId);
        final List<String> sortedProfiles = new ArrayList<>(profiles);
        Collections.sort(sortedProfiles);
        final SapCapexCodec.Query query = new SapCapexCodec.Query(SapCapexCodec.QUERY_TYPE_PERSISTENT, peerChecksum, sortedProfiles);
        return recordFor(SapCapexCodec.encodeQuery(query));
    }

    /**
     * Processes one session-1020 CapEx record. A returned reply must be sent
     * by the caller on the same reserved SAP session.
     */
    public Reply onRecord(final SapPostAuthRecord record) {
        record.getHeader().requireCapexDataFrame();
        final byte[] body = record.getBody();
        if (body.length == 0) throw new IllegalArgumentException("Empty CapEx body");
        final int type = body[0] & 0xff;
        if (type == SapCapexCodec.TYPE_RESPONSE && state == State.WAIT_RESPONSE) {
            remoteAgents = Collections.unmodifiableList(new ArrayList<>(SapCapexCodec.parseResponse(body).getAgents()));
            state = State.WAIT_RECIPROCAL_QUERY;
            return null;
        }
        if (type == SapCapexCodec.TYPE_QUERY
                && (state == State.WAIT_RECIPROCAL_QUERY || state == State.WAIT_PEER_QUERY || state == State.WAIT_FOLLOW_UP)) {
            final SapCapexCodec.Query query = SapCapexCodec.parseQuery(body);
            if (query.getQueryType() != SapCapexCodec.QUERY_TYPE_SELECTIVE && query.getQueryType() != SapCapexCodec.QUERY_TYPE_PERSISTENT) {
                throw new IllegalArgumentException("Unsupported CapEx query type " + query.getQueryType());
            }
            final boolean initialReciprocal = state == State.WAIT_RECIPROCAL_QUERY;
            if (initialReciprocal) reciprocalQuery = query;
            final Reply reply = createReply(query);
            state = State.WAIT_FOLLOW_UP;
            return reply;
        }
        throw new IllegalStateException("Unexpected CapEx message for state " + state);
    }

    private Reply createReply(final SapCapexCodec.Query query) {
        registerPersistentProfiles(query);
        final long checksum = generateLocalChecksum();
        final boolean checksumCurrent = query.getChecksum() == checksum;
        final List<SapAgentDescriptor> agents = checksumCurrent
                ? Collections.<SapAgentDescriptor>emptyList() : descriptorsFor(query.getProfiles());
        final SapCapexCodec.Response response = new SapCapexCodec.Response(checksum, agents);
        return new Reply(query, response, recordFor(SapCapexCodec.encodeResponse(response)), checksumCurrent,
                new ArrayList<>(persistentProfiles));
    }

    private void registerPersistentProfiles(final SapCapexCodec.Query query) {
        if (query.getQueryType() == SapCapexCodec.QUERY_TYPE_SELECTIVE || query.getQueryType() == SapCapexCodec.QUERY_TYPE_PERSISTENT) {
            persistentProfiles.addAll(query.getProfiles());
        }
    }

    private List<SapAgentDescriptor> descriptorsFor(final List<String> requestedProfiles) {
        final Map<Integer, MutableAgent> agents = new LinkedHashMap<>();
        final Set<String> requested = new LinkedHashSet<>(requestedProfiles);
        for (final String profile : requested) {
            boolean found = false;
            for (final LocalServiceMetadata service : localServices) {
                if (service.profileId.trim().equalsIgnoreCase(profile)) {
                    agent(agents, service.uuid, service.friendlyName).services.add(service.toDescriptor());
                    found = true;
                }
            }
            if (!found) {
                agent(agents, 0xffff, profile).services.add(new SapServiceDescriptor(0xff00, profile, 0x0100, 0x01, 0));
            }
        }
        final List<SapAgentDescriptor> result = new ArrayList<>();
        for (final MutableAgent agent : agents.values()) result.add(new SapAgentDescriptor(agent.uuid, agent.friendlyName, agent.services));
        return result;
    }

    private static MutableAgent agent(final Map<Integer, MutableAgent> agents, final int uuid, final String friendlyName) {
        MutableAgent result = agents.get(uuid);
        if (result == null) {
            result = new MutableAgent(uuid, friendlyName);
            agents.put(uuid, result);
        }
        return result;
    }

    /** Samsung's CRC32 metadata construction; dummy records are intentionally excluded. */
    public long generateLocalChecksum() {
        final List<LocalServiceMetadata> selected = new ArrayList<>();
        for (final LocalServiceMetadata service : localServices) {
            if (persistentProfiles.contains(service.profileId)) selected.add(service);
        }
        Collections.sort(selected, new Comparator<LocalServiceMetadata>() {
            @Override
            public int compare(final LocalServiceMetadata first, final LocalServiceMetadata second) {
                final int uuid = first.uuidText.compareTo(second.uuidText);
                if (uuid != 0) return uuid;
                final int profile = first.profileId.compareTo(second.profileId);
                return profile != 0 ? profile : first.role - second.role;
            }
        });
        final List<String> sortedProfiles = new ArrayList<>(persistentProfiles);
        Collections.sort(sortedProfiles);
        final StringBuilder source = new StringBuilder();
        for (final LocalServiceMetadata service : selected) {
            source.append(service.uuidText).append(service.profileId).append(service.componentId).append(service.role)
                    .append(service.friendlyName).append(service.aspVersionText).append(service.connectivityFlags)
                    .append(service.serviceLimit).append(service.timeout);
        }
        for (final String profile : sortedProfiles) source.append(profile);
        final CRC32 crc32 = new CRC32();
        crc32.update(source.toString().getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    private static SapPostAuthRecord recordFor(final byte[] body) {
        return new SapPostAuthRecord(new SapTransportHeader(0, 0, SapTransportHeader.CAPABILITY_SESSION_ID, 0), body);
    }

    public State getState() { return state; }
    public int getLocalProfileCount() { return localProfileCount; }
    public int getRemoteProfileCount() { return remoteProfileCount; }
    public List<SapAgentDescriptor> getRemoteAgents() { return remoteAgents; }
    public SapCapexCodec.Query getReciprocalQuery() { return reciprocalQuery; }
    public List<String> getPersistentProfiles() { return Collections.unmodifiableList(new ArrayList<>(persistentProfiles)); }

    public static final class Reply {
        private final SapCapexCodec.Query query;
        private final SapCapexCodec.Response response;
        private final SapPostAuthRecord record;
        private final boolean checksumCurrent;
        private final List<String> persistentProfiles;

        private Reply(final SapCapexCodec.Query query, final SapCapexCodec.Response response, final SapPostAuthRecord record,
                      final boolean checksumCurrent, final List<String> persistentProfiles) {
            this.query = query;
            this.response = response;
            this.record = record;
            this.checksumCurrent = checksumCurrent;
            this.persistentProfiles = Collections.unmodifiableList(persistentProfiles);
        }
        public SapCapexCodec.Query getQuery() { return query; }
        public SapCapexCodec.Response getResponse() { return response; }
        public SapPostAuthRecord getRecord() { return record; }
        public boolean isChecksumCurrent() { return checksumCurrent; }
        public List<String> getPersistentProfiles() { return persistentProfiles; }
    }

    /** Future real services can supply all Samsung checksum metadata through this immutable model. */
    public static final class LocalServiceMetadata {
        private final int uuid;
        private final String uuidText;
        private final String friendlyName;
        private final int componentId;
        private final String componentIdText;
        private final String profileId;
        private final int aspVersion;
        private final String aspVersionText;
        private final int role;
        private final int roleFlags;
        private final int timeout;
        private final int connectivityFlags;
        private final int serviceLimit;

        public LocalServiceMetadata(final int uuid, final String uuidText, final String friendlyName, final int componentId,
                                    final String componentIdText, final String profileId, final int aspVersion,
                                    final String aspVersionText, final int role, final int roleFlags, final int timeout,
                                    final int connectivityFlags, final int serviceLimit) {
            if (uuid < 0 || uuid > 0xffff || componentId < 0 || componentId > 0xffff || aspVersion < 0 || aspVersion > 0xffff
                    || role < 0 || role > 3 || roleFlags < 0 || roleFlags > 0xff || timeout < 0 || timeout > 0xffff
                    || uuidText == null || friendlyName == null || componentIdText == null || profileId == null || aspVersionText == null) {
                throw new IllegalArgumentException("Invalid local CapEx service metadata");
            }
            this.uuid = uuid;
            this.uuidText = uuidText;
            this.friendlyName = friendlyName;
            this.componentId = componentId;
            this.componentIdText = componentIdText;
            this.profileId = profileId;
            this.aspVersion = aspVersion;
            this.aspVersionText = aspVersionText;
            this.role = role;
            this.roleFlags = roleFlags;
            this.timeout = timeout;
            this.connectivityFlags = connectivityFlags;
            this.serviceLimit = serviceLimit;
        }

        private SapServiceDescriptor toDescriptor() {
            return new SapServiceDescriptor(componentId, profileId, aspVersion, roleFlags, timeout);
        }
    }

    private static final class MutableAgent {
        private final int uuid;
        private final String friendlyName;
        private final List<SapServiceDescriptor> services = new ArrayList<>();
        private MutableAgent(final int uuid, final String friendlyName) {
            this.uuid = uuid;
            this.friendlyName = friendlyName;
        }
    }
}
