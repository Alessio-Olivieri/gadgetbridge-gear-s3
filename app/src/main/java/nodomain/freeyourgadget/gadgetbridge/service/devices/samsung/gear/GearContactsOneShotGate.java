package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import java.util.Collections;
import java.util.Iterator;

/** Volatile, one-scan debug authorization; never changes the persisted contacts baseline. */
final class GearContactsOneShotGate {
    private boolean armed;

    synchronized boolean arm() {
        if (armed) return false;
        armed = true;
        return true;
    }

    synchronized boolean consume() {
        if (!armed) return false;
        armed = false;
        return true;
    }

    synchronized boolean isArmed() { return armed; }

    synchronized void cancel() { armed = false; }

    /** Narrow this debug scan's operations only; never promote its transport baseline. */
    static int retainDeletes(final GearContactsSnapshot.Delta delta) {
        if (delta == null) return 0;
        delta.insert.clear();
        delta.update.clear();
        Collections.sort(delta.delete);
        return delta.delete.size();
    }

    /** Select a newly created raw ID for a bounded insert trial. */
    static boolean retainInsert(final GearContactsSnapshot.Delta delta, final long rawId) {
        if (delta == null || rawId <= 0) return false;
        boolean found = false;
        for (final Iterator<GearContactsCodec.Contact> it = delta.insert.iterator(); it.hasNext();) {
            final GearContactsCodec.Contact contact = it.next();
            if (contact.rawId == rawId) found = true;
            else it.remove();
        }
        delta.update.clear();
        delta.delete.clear();
        return found && delta.insert.size() == 1;
    }

    /** Replace only a previously seeded raw ID, using the current phone record. */
    static boolean retainReplacement(final GearContactsSnapshot.Delta delta, final long rawId, final String previousHash) {
        if (delta == null || rawId <= 0 || previousHash == null) return false;
        GearContactsCodec.Contact selected = null;
        for (final GearContactsCodec.Contact contact : delta.insert) if (contact.rawId == rawId) selected = contact;
        for (final GearContactsCodec.Contact contact : delta.update) if (contact.rawId == rawId) selected = contact;
        delta.insert.clear();
        delta.update.clear();
        delta.delete.clear();
        if (selected == null || previousHash.equals(GearContactsSnapshot.fingerprint(selected))) return false;
        delta.delete.add(rawId);
        delta.insert.add(selected);
        return true;
    }
}
