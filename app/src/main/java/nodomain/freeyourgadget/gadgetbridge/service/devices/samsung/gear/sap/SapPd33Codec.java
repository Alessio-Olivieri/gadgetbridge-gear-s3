package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** PD 3.3 full-configuration offer (0x14) and answer (0x15). */
public final class SapPd33Codec {
    private static final int TYPE_OFFER = 0x14;
    private static final int TYPE_ANSWER = 0x15;
    private static final int PD_VERSION = 0x0401;
    private static final int SOFTWARE_VERSION = 0x00ff;
    private static final int PEER_ID_LENGTH = 36;
    private static final int MODE_AUTHENTICATED = 0x2d;
    private static final int MAX_APDU = 0x00200000;
    private static final int MAX_SSDU = 0xfffb;
    private static final int MAX_SESSIONS = 0x03fe;
    private static final int REQUEST_RESPONSE_TIMEOUT = 10000;
    private static final int TRANSPORT_WINDOW = 10;
    private static final String GEAR_PEER_ID_PREFIX = "SAMSUNG_ACCESSARY__";
    private static final String PROFILE = "SWatch";

    private SapPd33Codec() {
    }

    public static Offer parseOffer(final byte[] payload) {
        if (payload.length < 44 || unsigned8(payload[0]) != TYPE_OFFER) {
            throw new IllegalArgumentException("Invalid PD 3.3 offer");
        }
        if (unsigned16(payload, 1) != PD_VERSION || unsigned8(payload[5]) != 0) {
            throw new IllegalArgumentException("Unsupported PD 3.3 offer version or configuration code");
        }

        final String peerId = new String(payload, 6, PEER_ID_LENGTH, StandardCharsets.UTF_8);
        if (!peerId.matches("SAMSUNG_ACCESSARY__([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            throw new IllegalArgumentException("Invalid Gear peer ID in PD 3.3 offer");
        }
        if (unsigned8(payload[42]) != MODE_AUTHENTICATED) {
            throw new IllegalArgumentException("Unsupported PD 3.3 mode");
        }

        final int parameterCount = unsigned8(payload[43]);
        final Map<Integer, Object> parameters = new HashMap<>();
        int offset = 44;
        for (int i = 0; i < parameterCount; i++) {
            if (offset >= payload.length) {
                throw new IllegalArgumentException("Truncated PD 3.3 parameter list");
            }
            final int tag = unsigned8(payload[offset++]);
            if (parameters.containsKey(tag)) {
                throw new IllegalArgumentException("Duplicate PD 3.3 parameter " + tag);
            }
            switch (tag) {
                case 1:
                    requireRemaining(payload, offset, 4);
                    parameters.put(tag, unsigned32(payload, offset));
                    offset += 4;
                    break;
                case 2:
                case 3:
                case 4:
                case 5:
                    requireRemaining(payload, offset, 2);
                    parameters.put(tag, unsigned16(payload, offset));
                    offset += 2;
                    break;
                case 6:
                case 7:
                case 8:
                case 9:
                    requireRemaining(payload, offset, 1);
                    final int length = unsigned8(payload[offset++]);
                    if (length == 0 || length > 32) {
                        throw new IllegalArgumentException("Invalid PD 3.3 string parameter " + tag);
                    }
                    requireRemaining(payload, offset, length);
                    parameters.put(tag, new String(payload, offset, length, StandardCharsets.UTF_8));
                    offset += length;
                    break;
                case 10:
                case 11:
                case 12:
                    requireRemaining(payload, offset, 1);
                    parameters.put(tag, unsigned8(payload[offset++]));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown PD 3.3 parameter " + tag);
            }
        }
        if (offset != payload.length) {
            throw new IllegalArgumentException("Trailing bytes in PD 3.3 offer");
        }

        final Set<Integer> required = new HashSet<>();
        for (int tag = 1; tag <= 12; tag++) {
            required.add(tag);
        }
        if (!parameters.keySet().containsAll(required)) {
            throw new IllegalArgumentException("Incomplete PD 3.3 offer");
        }
        return new Offer(peerId, unsigned16(payload, 3), parameters);
    }

    /**
     * Builds the PD 3.3 answer. It lists only limits which must be
     * counter-offered, followed by the six feature metadata parameters in Samsung's order.
     */
    public static byte[] createAnswer(final Offer offer, final ClientInfo clientInfo) {
        validateClientInfo(clientInfo);
        if (offer.getApduSize() <= 0 || offer.getSsduSize() <= 0 || offer.getMaxSessions() <= 0
                || offer.getTimeout() <= 0 || offer.getWindowSize() <= 0
                || offer.getCompression() != 1) {
            throw new IllegalArgumentException("Unsupported PD 3.3 offer capabilities");
        }

        final ByteArrayOutputStream parameters = new ByteArrayOutputStream();
        if (MAX_APDU < offer.getApduSize()) write32Parameter(parameters, 1, MAX_APDU);
        if (MAX_SSDU < offer.getSsduSize()) write16Parameter(parameters, 2, MAX_SSDU);
        if (MAX_SESSIONS < offer.getMaxSessions()) write16Parameter(parameters, 3, MAX_SESSIONS);
        if (REQUEST_RESPONSE_TIMEOUT < offer.getTimeout()) write16Parameter(parameters, 4, REQUEST_RESPONSE_TIMEOUT);
        if (TRANSPORT_WINDOW < offer.getWindowSize()) write16Parameter(parameters, 5, TRANSPORT_WINDOW);

        writeStringParameter(parameters, 8, clientInfo.bluetoothName);
        writeStringParameter(parameters, 7, clientInfo.manufacturer);
        writeStringParameter(parameters, 6, clientInfo.model);
        writeByteParameter(parameters, 10, clientInfo.deviceCategory);
        writeStringParameter(parameters, 9, PROFILE);
        writeByteParameter(parameters, 12, clientInfo.localProfileCount);

        final byte[] parameterBytes = parameters.toByteArray();
        final int parameterCount = countParameters(parameterBytes);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(44 + parameterBytes.length);
        out.write(TYPE_ANSWER);
        write16(out, PD_VERSION);
        write16(out, SOFTWARE_VERSION);
        out.write(0); // Full configuration, accepted.
        writeBytes(out, clientInfo.peerId.getBytes(StandardCharsets.UTF_8));
        out.write(MODE_AUTHENTICATED);
        out.write(parameterCount);
        writeBytes(out, parameterBytes);
        return out.toByteArray();
    }

    private static void validateClientInfo(final ClientInfo clientInfo) {
        if (clientInfo == null || clientInfo.peerId == null
                || clientInfo.peerId.getBytes(StandardCharsets.UTF_8).length != PEER_ID_LENGTH
                || !clientInfo.peerId.startsWith("SAP_")) {
            throw new IllegalArgumentException("PD 3.3 client peer ID must be a 36-byte SAP ID");
        }
        validateString(clientInfo.model, "model");
        validateString(clientInfo.manufacturer, "manufacturer");
        validateString(clientInfo.bluetoothName, "Bluetooth name");
        if (clientInfo.deviceCategory < 0 || clientInfo.deviceCategory > 0xff
                || clientInfo.localProfileCount < 0 || clientInfo.localProfileCount > 0xff) {
            throw new IllegalArgumentException("Invalid PD 3.3 byte capability");
        }
    }

    private static void validateString(final String value, final String field) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 32) {
            throw new IllegalArgumentException("Invalid PD 3.3 " + field);
        }
    }

    private static int countParameters(final byte[] bytes) {
        int count = 0;
        int offset = 0;
        while (offset < bytes.length) {
            final int tag = unsigned8(bytes[offset++]);
            count++;
            if (tag == 1) offset += 4;
            else if (tag >= 2 && tag <= 5) offset += 2;
            else if (tag >= 6 && tag <= 9) offset += 1 + unsigned8(bytes[offset]);
            else if (tag >= 10 && tag <= 12) offset += 1;
            else throw new IllegalStateException("Invalid generated PD 3.3 parameter");
        }
        return count;
    }

    private static void write32Parameter(final ByteArrayOutputStream out, final int tag, final int value) {
        out.write(tag);
        write32(out, value);
    }

    private static void write16Parameter(final ByteArrayOutputStream out, final int tag, final int value) {
        out.write(tag);
        write16(out, value);
    }

    private static void writeStringParameter(final ByteArrayOutputStream out, final int tag, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(tag);
        out.write(bytes.length);
        writeBytes(out, bytes);
    }

    private static void writeByteParameter(final ByteArrayOutputStream out, final int tag, final int value) {
        out.write(tag);
        out.write(value);
    }

    private static void requireRemaining(final byte[] bytes, final int offset, final int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("Truncated PD 3.3 parameter");
        }
    }

    private static int unsigned8(final byte value) {
        return value & 0xff;
    }

    private static int unsigned16(final byte[] bytes, final int offset) {
        return (unsigned8(bytes[offset]) << 8) | unsigned8(bytes[offset + 1]);
    }

    private static int unsigned32(final byte[] bytes, final int offset) {
        return (unsigned8(bytes[offset]) << 24) | (unsigned8(bytes[offset + 1]) << 16)
                | (unsigned8(bytes[offset + 2]) << 8) | unsigned8(bytes[offset + 3]);
    }

    private static void write16(final ByteArrayOutputStream out, final int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void write32(final ByteArrayOutputStream out, final int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static void writeBytes(final ByteArrayOutputStream out, final byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    public static final class Offer {
        private final String peerId;
        private final int softwareVersion;
        private final Map<Integer, Object> parameters;

        private Offer(final String peerId, final int softwareVersion, final Map<Integer, Object> parameters) {
            this.peerId = peerId;
            this.softwareVersion = softwareVersion;
            this.parameters = parameters;
        }

        public String getPeerId() { return peerId; }
        public int getSoftwareVersion() { return softwareVersion; }
        public int getApduSize() { return (Integer) parameters.get(1); }
        public int getSsduSize() { return (Integer) parameters.get(2); }
        public int getMaxSessions() { return (Integer) parameters.get(3); }
        public int getTimeout() { return (Integer) parameters.get(4); }
        public int getWindowSize() { return (Integer) parameters.get(5); }
        public String getModel() { return (String) parameters.get(6); }
        public String getManufacturer() { return (String) parameters.get(7); }
        public String getFriendlyName() { return (String) parameters.get(8); }
        public String getProfile() { return (String) parameters.get(9); }
        public int getDeviceCategory() { return (Integer) parameters.get(10); }
        public int getCompression() { return (Integer) parameters.get(11); }
        public int getProfileCount() { return (Integer) parameters.get(12); }
    }

    public static final class ClientInfo {
        private final String peerId;
        private final String model;
        private final String manufacturer;
        private final String bluetoothName;
        private final int deviceCategory;
        private final int localProfileCount;

        public ClientInfo(final String peerId, final String model, final String manufacturer,
                          final String bluetoothName, final int deviceCategory, final int localProfileCount) {
            this.peerId = peerId;
            this.model = model;
            this.manufacturer = manufacturer;
            this.bluetoothName = bluetoothName;
            this.deviceCategory = deviceCategory;
            this.localProfileCount = localProfileCount;
        }
    }
}
