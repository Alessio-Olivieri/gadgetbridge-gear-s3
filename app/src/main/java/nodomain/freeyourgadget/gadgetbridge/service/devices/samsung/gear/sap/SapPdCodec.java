package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** PD request validation and type-6 response encoding. */
public final class SapPdCodec {
    private SapPdCodec() { }

    public static String parseWatchModel(final byte[] request) {
        return parseWatchIdentity(request).getModel();
    }

    public static String parseWatchPeerId(final byte[] request) {
        return parseWatchIdentity(request).getPeerId();
    }

    private static WatchIdentity parseWatchIdentity(final byte[] request) {
        if (request.length < 21 || request[0] != 0x05) throw new IllegalArgumentException("Invalid PD request");
        final int pdVersion = unsigned16(request, 1);
        if (pdVersion < 0x0200) throw new IllegalArgumentException("Unsupported PD version");
        if (request.length < 23) throw new IllegalArgumentException("Malformed PD identity fields");
        // Product, maker, friendly name and profile are semicolon-separated after fixed parameters;
        // the 36-byte SAP peer ID is followed by category/compression/profile-count bytes.
        final int strings = 20;
        final String text = new String(request, strings, request.length - strings - 3, StandardCharsets.UTF_8);
        final String[] values = text.split(";", -1);
        if (values.length != 5 || values[0].isEmpty()
                || !values[4].matches("SAMSUNG_ACCESSARY__([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            throw new IllegalArgumentException("Malformed PD identity fields");
        }
        return new WatchIdentity(values[0], values[4]);
    }

    public static byte[] createResponse(final String clientPeerId, final String model,
                                        final String manufacturer, final String bluetoothName) {
        if (!SapClientIdentity.isValid(clientPeerId)) {
            throw new IllegalArgumentException("Invalid SAP client peer ID");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x06);                 // PD response
        write16(out, 0x0401);            // PD protocol v4.1
        write16(out, 0x4d00);            // Android-M/non-Samsung framework software version
        out.write(0);                    // accepted
        out.write(0x02);                 // configuration code
        write32(out, 0x00200000);        // max APDU
        write16(out, 0xfffb);            // max SSDU/CSDU
        write16(out, 0x03fe);            // max sessions
        write16(out, 10000);             // request/response timeout
        out.write(0x20);                 // TL mode=2, auth/CL mode=0
        write16(out, 10);                // transport window
        out.write(1);                    // CL mode
        writeUtf8(out, limited(model));
        out.write(';');
        writeUtf8(out, limited(manufacturer));
        out.write(';');
        writeUtf8(out, limited(bluetoothName));
        out.write(';');
        writeUtf8(out, "SWatch");
        out.write(';');
        writeUtf8(out, clientPeerId);
        out.write(0);                    // device category
        out.write(1);                    // compression supported
        out.write(0);                    // local profile count: no SAP services in milestone 1
        return out.toByteArray();
    }

    private static String limited(final String value) { return value.length() <= 32 ? value : value.substring(0, 32); }
    private static int unsigned16(final byte[] data, final int offset) { return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff); }
    private static void writeUtf8(final ByteArrayOutputStream out, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }
    private static void write16(final ByteArrayOutputStream out, final int value) { out.write(value >>> 8); out.write(value); }
    private static void write32(final ByteArrayOutputStream out, final int value) { out.write(value >>> 24); out.write(value >>> 16); out.write(value >>> 8); out.write(value); }

    private static final class WatchIdentity {
        private final String model;
        private final String peerId;

        private WatchIdentity(final String model, final String peerId) {
            this.model = model;
            this.peerId = peerId;
        }

        private String getModel() {
            return model;
        }

        private String getPeerId() {
            return peerId;
        }
    }
}
