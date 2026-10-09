package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Persistent local SAP peer identity used by PD and WSM v2, independent of the controller MAC. */
public final class SapClientIdentity {
    public static final String PREFERENCE_KEY = "samsung_gear_sap_client_identity";
    private static final String PREFIX = "SAP_B";

    private SapClientIdentity() {
    }

    public static String getOrCreate(final SharedPreferences preferences) {
        synchronized (SapClientIdentity.class) {
            final String existing = preferences.getString(PREFERENCE_KEY, null);
            if (isValid(existing)) {
                return existing;
            }

            final String generated = generate(new SecureRandom());
            if (!preferences.edit().putString(PREFERENCE_KEY, generated).commit()) {
                throw new IllegalStateException("Could not persist Samsung SAP client identity");
            }
            return generated;
        }
    }

    static String generate(final SecureRandom random) {
        final byte[] randomBytes = new byte[16];
        random.nextBytes(randomBytes);
        final char[] hex = new char[randomBytes.length * 2];
        final char[] alphabet = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < randomBytes.length; i++) {
            final int value = randomBytes[i] & 0xff;
            hex[i * 2] = alphabet[value >>> 4];
            hex[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return PREFIX + new String(hex, 0, 31);
    }

    public static boolean isValid(final String identity) {
        return identity != null && identity.matches("SAP_B[0-9A-F]{31}");
    }
}
