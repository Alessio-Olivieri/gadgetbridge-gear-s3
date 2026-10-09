package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import java.util.Map;

/** Persistent locally-owned application IDs, allocated independently of peer IDs. */
public final class SapNotificationIdentityStore {
    private static final String PREFIX = "samsung_gear_notification_app_id.";
    private final SharedPreferences preferences;

    public SapNotificationIdentityStore(final SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Missing notification identity preferences");
        this.preferences = preferences;
    }

    public synchronized int appIdFor(final String packageName) {
        if (packageName == null || packageName.isEmpty()) throw new IllegalArgumentException("Missing notification package");
        final String key = PREFIX + packageName;
        final int saved = preferences.getInt(key, -1);
        if (saved >= 0x0100 && saved <= 0xfffe) return saved;
        int candidate = 0x0100 + (Math.floorMod(packageName.hashCode(), 0xfefe));
        final Map<String, ?> all = preferences.getAll();
        while (inUseByOtherPackage(all, key, candidate)) { candidate++; if (candidate > 0xfffe) candidate = 0x0100; }
        if (!preferences.edit().putInt(key, candidate).commit()) throw new IllegalStateException("Could not persist Gear notification app ID");
        return candidate;
    }

    private static boolean inUseByOtherPackage(final Map<String, ?> values, final String ownKey, final int candidate) {
        for (final Map.Entry<String, ?> entry : values.entrySet()) {
            if (!entry.getKey().equals(ownKey) && entry.getKey().startsWith(PREFIX) && entry.getValue() instanceof Integer
                    && ((Integer) entry.getValue()) == candidate) return true;
        }
        return false;
    }
}
