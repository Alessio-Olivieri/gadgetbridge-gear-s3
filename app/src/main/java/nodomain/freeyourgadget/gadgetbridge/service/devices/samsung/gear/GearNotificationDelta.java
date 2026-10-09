package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import java.util.LinkedHashMap;
import java.util.Map;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceCodec;

/** Local alert policy, not Samsung history storage. Contains only bounded message fingerprints. */
public final class GearNotificationDelta {
    public static final long REMOVAL_GRACE_MS = 30_000;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    public static final class Decision {
        public final int self, remote;
        public final boolean continuation;
        public final int previousSequence;
        Decision(int self, int remote, boolean continuation, int previousSequence) {
            this.self = self; this.remote = remote; this.continuation = continuation;
            this.previousSequence = previousSequence;
        }
        public boolean alert() { return remote > 0; }
    }

    public Decision update(String key, Map<String, Boolean> snapshot, long now) {
        expire(now);
        final Entry previous = entries.remove(key);
        int self = 0, remote = 0;
        final LinkedHashMap<String, Boolean> bounded = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> message : snapshot.entrySet()) {
            if (previous == null || !previous.messages.containsKey(message.getKey())) {
                if (message.getValue()) self++; else remote++;
            }
            bounded.put(message.getKey(), message.getValue());
            if (bounded.size() > 64) bounded.remove(bounded.keySet().iterator().next());
        }
        entries.put(key, new Entry(bounded, now + 3_600_000));
        while (entries.size() > 32) entries.remove(entries.keySet().iterator().next());
        return new Decision(self, remote, previous != null, previous == null ? -1 : previous.sequence);
    }

    public void sent(String key, int sequence) {
        final Entry entry = entries.get(key);
        if (entry != null) entry.sequence = sequence;
    }

    public void removed(String key, long now) {
        expire(now);
        final Entry entry = entries.get(key);
        if (entry != null) entry.expires = now + REMOVAL_GRACE_MS;
    }

    public void clear() { entries.clear(); }

    /** Compatibility replacement: delete the previous Gear card when the inline image style changes. */
    public static SapNotificationServiceCodec.Message styleRemoval(SapNotificationServiceCodec.Message previous,
                                                                   SapNotificationServiceCodec.Message next) {
        if (previous == null) return null;
        final boolean oldImage = previous.findAttribute(SapNotificationServiceCodec.ATTR_BIG_PICTURE) != null;
        final boolean newImage = next.findAttribute(SapNotificationServiceCodec.ATTR_BIG_PICTURE) != null;
        if (oldImage == newImage) return null;
        final SapNotificationServiceCodec.Attribute oldWindow = previous.findAttribute(SapNotificationServiceCodec.ATTR_WINDOW_ID);
        final SapNotificationServiceCodec.Attribute newWindow = next.findAttribute(SapNotificationServiceCodec.ATTR_WINDOW_ID);
        if (oldWindow == null || newWindow == null || !oldWindow.getUtf8().equals(newWindow.getUtf8())) return null;
        return SapNotificationServiceCodec.delete(previous.getSequence(), oldWindow.getUtf8());
    }

    public void expire(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expires <= now);
    }

    private static final class Entry {
        final Map<String, Boolean> messages;
        long expires;
        int sequence = -1;
        Entry(Map<String, Boolean> messages, long expires) { this.messages = messages; this.expires = expires; }
    }
}
