package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;

/** Device-scoped transport baseline; its hashes do not prove Gear importer success. */
public final class GearContactsSnapshot {
    private final Map<Long, String> transportDelivered = new LinkedHashMap<>();
    private final GearContactsSnapshotStore store;
    private boolean initialized;
    private boolean initialRequired;
    private boolean pendingVerification;
    private final Map<Long, String> ledgerDelivered = new LinkedHashMap<>();
    private final Set<Long> blocked = new LinkedHashSet<>();
    private final Set<Long> payloadStarted = new LinkedHashSet<>();
    private boolean invalidLedger;
    private long persistentTestRawId, persistentDeleteTestRawId;
    private final Set<Long> deleted = new LinkedHashSet<>();

    /** Process-local state for tests. Normal production uses a device store. */
    GearContactsSnapshot() { store = null; }

    GearContactsSnapshot(final GearContactsSnapshotStore store) {
        if (store == null) throw new IllegalArgumentException("Missing contacts snapshot store");
        this.store = store;
        final GearContactsSnapshotStore.State saved = store.load();
        if (saved != null) {
            transportDelivered.putAll(saved.hashes);
            initialized = saved.acknowledged;
            initialRequired = saved.initialRequired;
            pendingVerification = saved.pendingVerification;
        }
        reloadLedger();
    }

    synchronized void reloadLedger() {
        final GearContactsSnapshotStore.Ledger ledger = store.loadLedger();
        invalidLedger = ledger == null;
        ledgerDelivered.clear(); blocked.clear(); deleted.clear(); payloadStarted.clear(); persistentTestRawId = persistentDeleteTestRawId = 0;
        if (ledger != null) {
            ledgerDelivered.putAll(ledger.delivered); blocked.addAll(ledger.blocked);
            persistentTestRawId = ledger.debugModifyTestRawId;
            persistentDeleteTestRawId = ledger.debugDeleteTestRawId; deleted.addAll(ledger.deleted);
            payloadStarted.addAll(ledger.payloadStarted);
        }
    }

    public static final class Delta {
        public final List<GearContactsCodec.Contact> insert = new ArrayList<>(), update = new ArrayList<>();
        public final List<Long> delete = new ArrayList<>();
        private final Map<Long, String> next;
        private Delta(final Map<Long, String> next) { this.next = next; }
        public boolean isEmpty() { return insert.isEmpty() && update.isEmpty() && delete.isEmpty(); }
        public boolean isInsertOnly() { return !insert.isEmpty() && update.isEmpty() && delete.isEmpty(); }
    }

    public synchronized Delta compare(final List<GearContactsCodec.Contact> contacts, final boolean initial) {
        final Map<Long, GearContactsCodec.Contact> unique = new LinkedHashMap<>();
        for (final GearContactsCodec.Contact contact : contacts) unique.put(contact.rawId, contact);
        final Map<Long, String> next = new LinkedHashMap<>();
        final Delta delta = new Delta(next);
        final Map<Long, String> delivered = new LinkedHashMap<>(transportDelivered);
        delivered.putAll(ledgerDelivered);
        for (final GearContactsCodec.Contact contact : unique.values()) {
            final String fingerprint = fingerprint(contact);
            next.put(contact.rawId, fingerprint);
            if (initial || deleted.contains(contact.rawId) || !delivered.containsKey(contact.rawId)) delta.insert.add(contact);
            else if (!fingerprint.equals(delivered.get(contact.rawId))) delta.update.add(contact);
        }
        if (!initial) for (final long id : delivered.keySet()) if (!next.containsKey(id) && !deleted.contains(id)) delta.delete.add(id);
        return delta;
    }

    /** Marks an explicit Gear initial-sync request before sending its response or files. */
    public synchronized boolean requireInitial() {
        if (store != null && !store.save(new GearContactsSnapshotStore.State(transportDelivered, initialized, true, false))) return false;
        initialRequired = true;
        pendingVerification = false;
        return true;
    }

    /** Retain historical uncertainty; normal transfers converge independently through beginTransport(). */
    public synchronized boolean markPendingVerification() {
        if (!isInitialized() || pendingVerification) return false;
        if (store != null && !store.save(new GearContactsSnapshotStore.State(transportDelivered, true, false, true))) return false;
        pendingVerification = true;
        return true;
    }

    /** Persist first, then publish a transport-delivered snapshot to in-memory readers. */
    public synchronized boolean recordTransportDeliveredSnapshot(final Delta delta) {
        if (delta == null) throw new IllegalArgumentException("Missing completed contact delta");
        final GearContactsSnapshotStore.State saved = new GearContactsSnapshotStore.State(delta.next, true, false, false);
        saved.resetLedger = true;
        if (store != null && !store.save(saved)) return false;
        transportDelivered.clear(); transportDelivered.putAll(delta.next);
        ledgerDelivered.clear(); blocked.clear(); deleted.clear(); payloadStarted.clear(); invalidLedger = false;
        initialized = true; initialRequired = false; pendingVerification = false; persistentTestRawId = persistentDeleteTestRawId = 0;
        return true;
    }

    /** Legacy insert-checkpoint compatibility; normal production uses the per-ID ledger. */
    public synchronized boolean recordTransportDeliveredInserts(final Delta delta) {
        if (delta == null || !delta.isInsertOnly() || !initialized || initialRequired || !pendingVerification) return false;
        final Map<Long, String> delivered = new LinkedHashMap<>(transportDelivered);
        for (final GearContactsCodec.Contact contact : delta.insert) {
            if (transportDelivered.containsKey(contact.rawId)) return false;
            final String hash = delta.next.get(contact.rawId);
            if (hash == null) return false;
            delivered.put(contact.rawId, hash);
        }
        if (store != null && !store.save(new GearContactsSnapshotStore.State(delivered, true, false, false))) return false;
        transportDelivered.clear(); transportDelivered.putAll(delivered);
        pendingVerification = false;
        return true;
    }

    public synchronized boolean isInitialized() { return initialized && !initialRequired; }
    /** Historical uncertainty is retained, not permission to freeze every raw ID. */
    public synchronized boolean hasPendingVerification() { return pendingVerification || invalidLedger; }
    /** Lost/corrupt per-ID attempt state must still fail closed to prevent blind replay. */
    synchronized boolean hasInvalidLedger() { return invalidLedger; }
    public synchronized int size() { final Set<Long> ids = new LinkedHashSet<>(transportDelivered.keySet()); ids.addAll(ledgerDelivered.keySet()); return ids.size(); }

    synchronized void excludeBlocked(final Delta delta) {
        delta.insert.removeIf(c -> blocked.contains(c.rawId));
        delta.update.removeIf(c -> blocked.contains(c.rawId));
        delta.delete.removeIf(blocked::contains);
    }

    /** Durable immediately before first payload enqueue, after accepted FT setup. Never for local preparation. */
    synchronized boolean beginTransport(final Set<Long> ids) {
        if (!isInitialized() || invalidLedger) return false;
        return beginTransportIds(ids);
    }

    private boolean beginTransportIds(final Set<Long> ids) {
        final Set<Long> pending = new LinkedHashSet<>(blocked);
        for (final long id : ids) if (id <= 0 || !pending.add(id)) return false;
        if (ids.isEmpty()) return false;
        final Set<Long> started = new LinkedHashSet<>(payloadStarted); started.addAll(ids);
        if (store != null && !store.saveLedger(new GearContactsSnapshotStore.Ledger(ledgerDelivered, pending,
                persistentTestRawId, deleted, persistentDeleteTestRawId, started))) return false;
        blocked.clear(); blocked.addAll(pending); payloadStarted.clear(); payloadStarted.addAll(started); return true;
    }

    /** Per-file I completion, using the fingerprint frozen before that payload was packaged. */
    synchronized boolean recordTransportDelivered(final Map<Long, String> sentHashes) {
        if (sentHashes.isEmpty()) return false;
        final Set<Long> tombstones = new LinkedHashSet<>(deleted);
        final Map<Long, String> delivered = new LinkedHashMap<>(ledgerDelivered);
        final Set<Long> pending = new LinkedHashSet<>(blocked);
        for (final Map.Entry<Long, String> row : sentHashes.entrySet()) {
            if (!pending.remove(row.getKey())) return false;
            delivered.put(row.getKey(), row.getValue());
            tombstones.remove(row.getKey());
        }
        if (!saveLedger(delivered, pending, tombstones)) return false;
        deleted.clear(); deleted.addAll(tombstones);
        ledgerDelivered.clear(); ledgerDelivered.putAll(delivered);
        blocked.clear(); blocked.addAll(pending); payloadStarted.retainAll(pending); return true;
    }

    private boolean saveLedger(final Map<Long, String> hashes, final Set<Long> pending, final Set<Long> tombstones) {
        final Set<Long> started = new LinkedHashSet<>(payloadStarted); started.retainAll(pending);
        return store == null || store.saveLedger(new GearContactsSnapshotStore.Ledger(hashes, pending,
                persistentTestRawId, tombstones, persistentDeleteTestRawId, started));
    }
    synchronized int transportUncertainCount() { return ledgerDelivered.size(); }
    synchronized int blockedTransferCount() { return blocked.size(); }
    synchronized boolean hasBlockedTransfers() { return invalidLedger || !blocked.isEmpty(); }
    synchronized boolean isBlocked(final long id) { return blocked.contains(id); }
    synchronized Set<Long> blockedTransferIds() { return new LinkedHashSet<>(blocked); }
    synchronized String incompleteStage(final long id) {
        return !blocked.contains(id) ? "NONE" : (payloadStarted.contains(id) ? "PAYLOAD_MAY_HAVE_STARTED" : "LEGACY_UNKNOWN");
    }

    synchronized boolean isTransportUncertain(final long id) { return ledgerDelivered.containsKey(id); }
    synchronized String lastTransportDeliveredFingerprint(final long id) {
        return ledgerDelivered.containsKey(id) ? ledgerDelivered.get(id) : transportDelivered.get(id);
    }

    /** Durable DEBUG scope; never grants whole-device permission or changes the legacy snapshot. */
    synchronized long persistentTestRawId() { return BuildConfig.DEBUG ? persistentTestRawId : 0; }

    synchronized boolean preparePersistentTest(final GearContactsCodec.Contact current) {
        if (!BuildConfig.DEBUG || store == null || !isInitialized() || !pendingVerification || invalidLedger
                || current.rawId <= 0 || persistentTestRawId != 0 || persistentDeleteTestRawId != 0
                || blocked.contains(current.rawId) || deleted.contains(current.rawId)) return false;
        final String hash = fingerprint(current);
        // Never overwrite an existing differing transport record or an incomplete attempt to reseed.
        if (ledgerDelivered.containsKey(current.rawId) && !hash.equals(ledgerDelivered.get(current.rawId))) return false;
        final Map<Long, String> delivered = new LinkedHashMap<>(ledgerDelivered);
        delivered.put(current.rawId, hash);
        if (!store.saveLedger(new GearContactsSnapshotStore.Ledger(delivered, blocked, current.rawId, deleted, 0, payloadStarted))) return false;
        ledgerDelivered.clear(); ledgerDelivered.putAll(delivered); persistentTestRawId = current.rawId;
        return true;
    }

    synchronized boolean clearPersistentTest(final long id) {
        if (!BuildConfig.DEBUG || store == null || id <= 0 || persistentTestRawId != id) return false;
        if (!store.saveLedger(new GearContactsSnapshotStore.Ledger(ledgerDelivered, blocked, 0, deleted, persistentDeleteTestRawId, payloadStarted))) return false;
        persistentTestRawId = 0; // Keep hashes/blocked IDs; CLEAR is not a retry or an assertion of success.
        return true;
    }

    synchronized Delta comparePersistentTest(final List<GearContactsCodec.Contact> contacts, final long id) {
        if (!persistentTestAllowed(id)) throw new IllegalStateException("Persistent test scope unavailable");
        final Delta delta = compare(contacts, false);
        delta.insert.removeIf(c -> c.rawId != id);
        delta.update.removeIf(c -> c.rawId != id);
        delta.delete.removeIf(rawId -> rawId != id);
        return delta;
    }

    synchronized boolean beginPersistentTestTransport(final Set<Long> ids) {
        final long id = persistentDeleteTestRawId > 0 ? persistentDeleteTestRawId : persistentTestRawId;
        return ids.size() == 1 && ids.contains(id) && persistentTestAllowed(id) && beginTransportIds(ids);
    }

    private boolean persistentTestAllowed(final long id) {
        return BuildConfig.DEBUG && store != null && id > 0
                && ((persistentTestRawId == id) || persistentDeleteTestRawId == id)
                && isInitialized() && pendingVerification && !invalidLedger && ledgerDelivered.containsKey(id);
    }

    /** Only a completed standalone D may create tombstones; replacement D is not a deletion checkpoint. */
    synchronized boolean recordTransportDeleted(final Set<Long> ids) {
        if (ids.isEmpty()) return false;
        final Map<Long, String> delivered = new LinkedHashMap<>(ledgerDelivered);
        final Set<Long> pending = new LinkedHashSet<>(blocked), tombstones = new LinkedHashSet<>(deleted);
        for (final long id : ids) {
            final String previous = lastTransportDeliveredFingerprint(id);
            if (previous == null || !pending.remove(id) || !tombstones.add(id)) return false;
            // Migrate only this completed D, retaining its known prior hash, never the entire legacy snapshot.
            delivered.put(id, previous);
        }
        if (!saveLedger(delivered, pending, tombstones)) return false;
        ledgerDelivered.clear(); ledgerDelivered.putAll(delivered);
        deleted.clear(); deleted.addAll(tombstones); blocked.clear(); blocked.addAll(pending); payloadStarted.retainAll(pending);
        return true;
    }

    synchronized boolean isTransportDeleted(final long id) { return deleted.contains(id); }

    /** Known raw IDs converge lazily; genuine deletes remain deferred until real Resume. */
    synchronized int retainKnownDeletes(final Delta delta, final boolean fromResume) {
        final int before = delta.delete.size();
        delta.delete.removeIf(id -> !fromResume || lastTransportDeliveredFingerprint(id) == null
                || deleted.contains(id) || blocked.contains(id));
        return before - delta.delete.size();
    }

    synchronized long persistentDeleteTestRawId() { return BuildConfig.DEBUG ? persistentDeleteTestRawId : 0; }

    synchronized boolean preparePersistentDeleteTest(final GearContactsCodec.Contact current) {
        final long id = current.rawId;
        if (!BuildConfig.DEBUG || store == null || !isInitialized() || !pendingVerification || invalidLedger
                || id <= 0 || persistentTestRawId != 0 || persistentDeleteTestRawId != 0 || blocked.contains(id) || deleted.contains(id)) return false;
        final String hash = fingerprint(current);
        if (ledgerDelivered.containsKey(id) && !hash.equals(ledgerDelivered.get(id))) return false;
        final Map<Long, String> delivered = new LinkedHashMap<>(ledgerDelivered); delivered.put(id, hash);
        if (!store.saveLedger(new GearContactsSnapshotStore.Ledger(delivered, blocked, 0, deleted, id, payloadStarted))) return false;
        ledgerDelivered.clear(); ledgerDelivered.putAll(delivered); persistentDeleteTestRawId = id;
        return true;
    }

    synchronized boolean clearPersistentDeleteTest(final long id) {
        if (!BuildConfig.DEBUG || store == null || id <= 0 || persistentDeleteTestRawId != id) return false;
        if (!store.saveLedger(new GearContactsSnapshotStore.Ledger(ledgerDelivered, blocked, persistentTestRawId, deleted, 0, payloadStarted))) return false;
        persistentDeleteTestRawId = 0;
        return true; // Keep every hash, tombstone and block. This removes authorization only.
    }

    static String fingerprint(final GearContactsCodec.Contact contact) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(contact.rawId);
            out.writeLong(contact.contactId);
            out.writeLong(contact.displayRawId);
            writeString(out, contact.displayName);
            writeString(out, contact.first);
            writeString(out, contact.last);
            out.writeBoolean(contact.favorite);
            out.writeInt(contact.numbers.size());
            for (final GearContactsCodec.Number number : contact.numbers) {
                writeString(out, number.value);
                writeString(out, number.normalized);
                out.writeInt(number.type);
                writeString(out, number.label);
                out.writeBoolean(number.primary);
            }
            out.flush();
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            final char[] alphabet = "0123456789abcdef".toCharArray();
            final char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = alphabet[(digest[i] >>> 4) & 15];
                hex[i * 2 + 1] = alphabet[digest[i] & 15];
            }
            return new String(hex);
        } catch (final IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash Gear contact metadata", e);
        }
    }

    private static void writeString(final DataOutputStream out, final String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }
}
