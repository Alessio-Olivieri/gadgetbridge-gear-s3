package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded ASP 2.0 bcmservice feature exchange; it does not implement bcmservice generally. */
public final class SapBcmFeatureExchange {
    public static final int OPERATION_REQUEST = 1;
    public static final int OPERATION_RESPONSE = 2;
    public static final int OPERATION_RESPONSE_ERROR = 3;
    public static final int OPERATION_NOTIFICATION = 4;

    public static final int MAIN_GENERIC = 1;
    public static final int MAIN_BLUETOOTH = 2;
    public static final int MAIN_WIFI_P2P = 6;
    public static final int SUB_BLUETOOTH_VERSION = 1;
    public static final int SUB_BLUETOOTH_ADDRESS = 2;
    public static final int SUB_MANUFACTURER = 3;
    public static final int SUB_SUPPORTED_NETWORK_TYPES = 5;
    public static final int SUB_SUPPORTED_SERVICES = 5;
    public static final int SUB_LE_ADDRESS = 6;
    public static final int SUB_RSSI = 7;
    public static final int SUB_HFP_OPTIONAL = 35;
    public static final int SUB_WIFI_P2P_ADDRESS = 2;

    private static final int MAX_VALUE_LENGTH = 251;
    private static final Set<Key> EXPECTED_PEER_RESPONSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            new Key(MAIN_BLUETOOTH, SUB_BLUETOOTH_VERSION),
            new Key(MAIN_BLUETOOTH, SUB_SUPPORTED_SERVICES),
            new Key(MAIN_GENERIC, SUB_SUPPORTED_NETWORK_TYPES),
            new Key(MAIN_BLUETOOTH, SUB_LE_ADDRESS),
            new Key(MAIN_WIFI_P2P, SUB_WIFI_P2P_ADDRESS),
            new Key(MAIN_BLUETOOTH, SUB_RSSI)
    )));

    private boolean localRequestsSent;
    private boolean peerRequestHandlingComplete;
    private final Set<Key> receivedPeerResponses = new HashSet<>();
    private final Set<Key> receivedGearRequests = new HashSet<>();

    public byte[] createLocalRequestFrame() {
        if (localRequestsSent) return null;
        localRequestsSent = true;
        return wrap(Arrays.asList(
                request(MAIN_BLUETOOTH, SUB_BLUETOOTH_VERSION),
                request(MAIN_BLUETOOTH, SUB_SUPPORTED_SERVICES),
                request(MAIN_GENERIC, SUB_SUPPORTED_NETWORK_TYPES),
                request(MAIN_BLUETOOTH, SUB_LE_ADDRESS),
                request(MAIN_WIFI_P2P, SUB_WIFI_P2P_ADDRESS),
                request(MAIN_BLUETOOTH, SUB_RSSI)));
    }

    public void clear() {
        localRequestsSent = false;
        peerRequestHandlingComplete = false;
        receivedPeerResponses.clear();
        receivedGearRequests.clear();
    }

    public Result handleFrame(final byte[] frame, final Values values) {
        if (values == null) throw new IllegalArgumentException("Missing bcmservice feature values");
        final int offset = SapBcmServiceChannelHeader.decodeNormalUnencryptedSingleFragment(frame);
        final List<Pdu> received = parse(Arrays.copyOfRange(frame, offset, frame.length));
        final List<Pdu> replies = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();
        for (final Pdu pdu : received) {
            if (pdu.operation == OPERATION_REQUEST) {
                final Action action = handleRequest(pdu, values);
                actions.add(action);
                if (action.reply != null) replies.add(action.reply);
            } else {
                if ((pdu.operation == OPERATION_RESPONSE || pdu.operation == OPERATION_RESPONSE_ERROR)
                        && EXPECTED_PEER_RESPONSES.contains(new Key(pdu.main, pdu.sub))) {
                    receivedPeerResponses.add(new Key(pdu.main, pdu.sub));
                }
                actions.add(new Action(pdu, null, pdu.operation == OPERATION_NOTIFICATION
                        && pdu.main == MAIN_BLUETOOTH && pdu.sub == SUB_HFP_OPTIONAL
                        ? "HFP-optional notification parsed; no HFP action" : "peer response/notification recorded"));
            }
        }
        for (final Pdu pdu : received) {
            if (pdu.operation == OPERATION_REQUEST || pdu.operation == OPERATION_NOTIFICATION) receivedGearRequests.add(new Key(pdu.main, pdu.sub));
        }
        if (allObservedGearRequests()) peerRequestHandlingComplete = true;
        return new Result(received, replies.isEmpty() ? null : wrap(replies), actions,
                localRequestsSent, peerRequestHandlingComplete, receivedPeerResponses.containsAll(EXPECTED_PEER_RESPONSES));
    }

    private boolean allObservedGearRequests() {
        return receivedGearRequests.containsAll(Arrays.asList(
                new Key(MAIN_BLUETOOTH, SUB_BLUETOOTH_ADDRESS), new Key(MAIN_BLUETOOTH, SUB_SUPPORTED_SERVICES),
                new Key(MAIN_BLUETOOTH, SUB_LE_ADDRESS), new Key(MAIN_BLUETOOTH, SUB_BLUETOOTH_VERSION),
                new Key(MAIN_GENERIC, SUB_MANUFACTURER), new Key(MAIN_BLUETOOTH, SUB_HFP_OPTIONAL),
                new Key(MAIN_WIFI_P2P, SUB_WIFI_P2P_ADDRESS)));
    }

    private static Action handleRequest(final Pdu pdu, final Values values) {
        if (pdu.main == MAIN_BLUETOOTH && pdu.sub == SUB_BLUETOOTH_ADDRESS) return optionalResponse(pdu, values.bluetoothAddress(), "Bluetooth adapter address");
        if (pdu.main == MAIN_BLUETOOTH && pdu.sub == SUB_BLUETOOTH_VERSION) return optionalResponse(pdu, values.bluetoothVersion(), "Android SDK Bluetooth-version mapping");
        if (pdu.main == MAIN_GENERIC && pdu.sub == SUB_MANUFACTURER) return optionalResponse(pdu, values.manufacturer(), "Android Build.MANUFACTURER");
        if (pdu.main == MAIN_BLUETOOTH && pdu.sub == SUB_LE_ADDRESS) return optionalResponse(pdu, values.leAddress(), "random LE address unavailable");
        if (pdu.main == MAIN_WIFI_P2P && pdu.sub == SUB_WIFI_P2P_ADDRESS) return optionalResponse(pdu, values.wifiP2pAddress(), "Wi-Fi P2P address unavailable");
        if (pdu.main == MAIN_BLUETOOTH && pdu.sub == SUB_SUPPORTED_SERVICES) return new Action(pdu, null, "supported-services omitted: no Gadgetbridge capability bits are established");
        return new Action(pdu, null, pdu.main == MAIN_BLUETOOTH && pdu.sub == SUB_HFP_OPTIONAL
                ? "HFP-optional notification/request parsed; no HFP action" : "unsupported request omitted");
    }

    private static Action optionalResponse(final Pdu request, final byte[] value, final String source) {
        if (value == null) return new Action(request, null, source + "; response omitted");
        return new Action(request, new Pdu(request.main, request.sub, OPERATION_RESPONSE, value), source);
    }

    public static Pdu request(final int main, final int sub) { return new Pdu(main, sub, OPERATION_REQUEST, new byte[0]); }
    public static byte[] wrap(final List<Pdu> pdus) {
        final byte[] payload = encode(pdus);
        final byte[] frame = new byte[SapBcmServiceChannelHeader.SIZE + payload.length];
        System.arraycopy(SapBcmServiceChannelHeader.encodeNormalUnencryptedSingleFragment(), 0, frame, 0, SapBcmServiceChannelHeader.SIZE);
        System.arraycopy(payload, 0, frame, SapBcmServiceChannelHeader.SIZE, payload.length);
        return frame;
    }
    public static byte[] encode(final List<Pdu> pdus) {
        if (pdus == null || pdus.isEmpty()) throw new IllegalArgumentException("Empty bcmservice PDU list");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (final Pdu pdu : pdus) {
            if (pdu == null || pdu.value.length > MAX_VALUE_LENGTH) throw new IllegalArgumentException("Invalid bcmservice PDU value");
            output.write(pdu.value.length + 4); output.write(pdu.main); output.write(pdu.sub); output.write(pdu.operation); output.write(pdu.value, 0, pdu.value.length);
        }
        return output.toByteArray();
    }
    public static List<Pdu> parse(final byte[] payload) {
        if (payload == null || payload.length == 0) throw new IllegalArgumentException("Empty bcmservice feature payload");
        final List<Pdu> result = new ArrayList<>();
        for (int offset = 0; offset < payload.length;) {
            final int length = payload[offset] & 0xff;
            if (length < 4 || length > payload.length - offset) throw new IllegalArgumentException("Truncated or invalid bcmservice feature entry");
            result.add(new Pdu(payload[offset + 1] & 0xff, payload[offset + 2] & 0xff, payload[offset + 3] & 0xff,
                    Arrays.copyOfRange(payload, offset + 4, offset + length)));
            offset += length;
        }
        return result;
    }

    public interface Values {
        byte[] bluetoothAddress();
        byte[] bluetoothVersion();
        byte[] manufacturer();
        byte[] leAddress();
        byte[] wifiP2pAddress();
    }
    public static final class Pdu {
        private final int main, sub, operation;
        private final byte[] value;
        public Pdu(final int main, final int sub, final int operation, final byte[] value) {
            if (main < 0 || main > 255 || sub < 0 || sub > 255 || operation < 0 || operation > 255 || value == null || value.length > MAX_VALUE_LENGTH) throw new IllegalArgumentException("Invalid bcmservice PDU");
            this.main = main; this.sub = sub; this.operation = operation; this.value = Arrays.copyOf(value, value.length);
        }
        public int getMain() { return main; } public int getSub() { return sub; } public int getOperation() { return operation; } public byte[] getValue() { return Arrays.copyOf(value, value.length); }
    }
    public static final class Action {
        private final Pdu received, reply; private final String reason;
        Action(final Pdu received, final Pdu reply, final String reason) { this.received = received; this.reply = reply; this.reason = reason; }
        public Pdu getReceived() { return received; } public Pdu getReply() { return reply; } public String getReason() { return reason; }
    }
    public static final class Result {
        private final List<Pdu> received; private final byte[] replyFrame; private final List<Action> actions;
        private final boolean localRequestsSent, peerRequestHandlingComplete, peerResponsesComplete;
        Result(final List<Pdu> received, final byte[] replyFrame, final List<Action> actions, final boolean localRequestsSent, final boolean peerRequestHandlingComplete, final boolean peerResponsesComplete) {
            this.received = Collections.unmodifiableList(new ArrayList<>(received)); this.replyFrame = replyFrame == null ? null : Arrays.copyOf(replyFrame, replyFrame.length); this.actions = Collections.unmodifiableList(new ArrayList<>(actions)); this.localRequestsSent = localRequestsSent; this.peerRequestHandlingComplete = peerRequestHandlingComplete; this.peerResponsesComplete = peerResponsesComplete;
        }
        public List<Pdu> getReceived() { return received; } public byte[] getReplyFrame() { return replyFrame == null ? null : Arrays.copyOf(replyFrame, replyFrame.length); } public List<Action> getActions() { return actions; }
        public boolean isLocalRequestsSent() { return localRequestsSent; } public boolean isPeerRequestHandlingComplete() { return peerRequestHandlingComplete; } public boolean isPeerResponsesComplete() { return peerResponsesComplete; }
    }
    private static final class Key {
        private final int main, sub; Key(final int main, final int sub) { this.main = main; this.sub = sub; }
        @Override public boolean equals(final Object other) { return other instanceof Key && main == ((Key) other).main && sub == ((Key) other).sub; }
        @Override public int hashCode() { return 31 * main + sub; }
    }
    public static byte[] utf8(final String value) { return value == null ? null : value.getBytes(StandardCharsets.UTF_8); }
}
