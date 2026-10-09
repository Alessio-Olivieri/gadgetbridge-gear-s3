package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.nio.charset.StandardCharsets;

/** PD 3.3 reconnect negotiation and configuration-change response. */
public final class SapReconnectCodec {
    private static final int TYPE_PD_PROBE_3_3 = 0x13;
    private static final int TYPE_PD_CONFIRM_3_3 = 0x16;
    private static final int MAX_PD_VERSION = 0x0401;
    private static final int LOCAL_SOFTWARE_VERSION = 0x00ff;
    private static final int STATUS_CONFIGURATION_CHANGED = 5;
    private static final int PEER_ID_LENGTH = 36;
    private static final String GEAR_PEER_ID_PREFIX = "SAMSUNG_ACCESSARY__";

    private SapReconnectCodec() {
    }

    public static Probe parseProbe(final byte[] payload) {
        if (payload.length != 3 + PEER_ID_LENGTH || (payload[0] & 0xff) != TYPE_PD_PROBE_3_3) {
            throw new IllegalArgumentException("Invalid PD 3.3 probe");
        }

        final int pdVersion = unsigned16(payload, 1);
        if (pdVersion < 0x0200) {
            throw new IllegalArgumentException("Unsupported PD version in reconnect probe");
        }

        final String peerId = new String(payload, 3, PEER_ID_LENGTH, StandardCharsets.UTF_8);
        if (!peerId.matches("SAMSUNG_ACCESSARY__([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            throw new IllegalArgumentException("Invalid Gear peer ID in reconnect probe");
        }
        return new Probe(pdVersion, peerId);
    }

    /**
     * Requests the PD-3.3 configuration-change fallback. The Samsung peer responds
     * with a type-0x14 offer instead of attempting cached-session/resume processing.
     */
    public static byte[] createConfigurationChangedResponse(final Probe probe) {
        final int negotiatedVersion = Math.min(MAX_PD_VERSION, probe.getPdVersion());
        return new byte[]{
                (byte) TYPE_PD_CONFIRM_3_3,
                (byte) (negotiatedVersion >>> 8),
                (byte) negotiatedVersion,
                (byte) (LOCAL_SOFTWARE_VERSION >>> 8),
                (byte) LOCAL_SOFTWARE_VERSION,
                (byte) STATUS_CONFIGURATION_CHANGED,
        };
    }

    private static int unsigned16(final byte[] data, final int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    public static final class Probe {
        private final int pdVersion;
        private final String peerId;

        private Probe(final int pdVersion, final String peerId) {
            this.pdVersion = pdVersion;
            this.peerId = peerId;
        }

        public int getPdVersion() {
            return pdVersion;
        }

        public String getPeerId() {
            return peerId;
        }
    }
}
