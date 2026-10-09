package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.WsmV2Crypto;

/** In-memory-only material from one fresh WSM v2 authentication. */
public final class GearSessionSecrets {
    private byte[] prf;

    public synchronized void setPrf(final byte[] value) {
        clear();
        prf = Arrays.copyOf(value, value.length);
    }

    public synchronized boolean isAuthenticated() {
        return prf != null;
    }

    /** Derive one WSM v2 application key without exposing the retained PRF. */
    public synchronized byte[] deriveApplicationKey(final int providerComponent, final int consumerComponent) {
        if (prf == null || prf.length != 32) throw new IllegalStateException("Missing authenticated WSM PRF");
        if (providerComponent == 0 || consumerComponent == 0) throw new IllegalArgumentException("Invalid application component ID");
        final byte[] data = new byte[40];
        System.arraycopy(prf, 0, data, 0, prf.length);
        writeLe32(data, 32, providerComponent);
        writeLe32(data, 36, consumerComponent);
        try {
            return WsmV2Crypto.hmac("HmacSHA256", prf, data);
        } catch (final Exception e) {
            throw new IllegalStateException("Could not derive WSM application key", e);
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    public synchronized void clear() {
        if (prf != null) {
            Arrays.fill(prf, (byte) 0);
            prf = null;
        }
    }

    private static void writeLe32(final byte[] bytes, final int offset, final int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}
