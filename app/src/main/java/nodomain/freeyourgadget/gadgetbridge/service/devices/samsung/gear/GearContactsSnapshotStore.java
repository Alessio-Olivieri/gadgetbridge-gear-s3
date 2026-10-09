package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Device-scoped baseline and transport ledger; only raw IDs, hashes and structural flags. */
class GearContactsSnapshotStore {
    // Preserve legacy preference keys and JSON flags during reconnect migration.
    // "acknowledged" here means a saved transport baseline, never Gear importer acknowledgement.
    private static final String KEY = "samsung_gear_contacts_acknowledged_snapshot_v1";
    private static final String LEDGER_KEY = "samsung_gear_contacts_transport_ledger_v1";
    private static final int VERSION = 1;
    private static final int MAX_ROWS = 100_000;
    private final SharedPreferences preferences;

    GearContactsSnapshotStore(final SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Missing device contacts preferences");
        this.preferences = preferences;
    }

    static final class State {
        final Map<Long, String> hashes;
        final boolean acknowledged, initialRequired, pendingVerification;
        boolean resetLedger;

        State(final Map<Long, String> hashes, final boolean acknowledged, final boolean initialRequired,
              final boolean pendingVerification) {
            this.hashes = new LinkedHashMap<>(hashes);
            this.acknowledged = acknowledged;
            this.initialRequired = initialRequired;
            this.pendingVerification = pendingVerification;
        }
    }

    static final class Ledger {
        final Map<Long, String> delivered;
        final Set<Long> blocked;
        final long debugModifyTestRawId, debugDeleteTestRawId;
        final Set<Long> deleted;
        // Subset of blocked: v1/v2 blocks have unknown stage; new blocks precede payload enqueue.
        final Set<Long> payloadStarted;
        Ledger(final Map<Long, String> delivered, final Set<Long> blocked) {
            this(delivered, blocked, 0);
        }
        Ledger(final Map<Long, String> delivered, final Set<Long> blocked, final long debugModifyTestRawId) {
            this(delivered, blocked, debugModifyTestRawId, new LinkedHashSet<>(), 0);
        }
        Ledger(final Map<Long, String> delivered, final Set<Long> blocked, final long debugModifyTestRawId,
               final Set<Long> deleted, final long debugDeleteTestRawId) {
            this(delivered, blocked, debugModifyTestRawId, deleted, debugDeleteTestRawId, new LinkedHashSet<>());
        }
        Ledger(final Map<Long, String> delivered, final Set<Long> blocked, final long debugModifyTestRawId,
               final Set<Long> deleted, final long debugDeleteTestRawId, final Set<Long> payloadStarted) {
            this.debugModifyTestRawId = debugModifyTestRawId;
            this.debugDeleteTestRawId = debugDeleteTestRawId;
            this.deleted = new LinkedHashSet<>(deleted);
            this.delivered = new LinkedHashMap<>(delivered);
            this.blocked = new LinkedHashSet<>(blocked);
            this.payloadStarted = new LinkedHashSet<>(payloadStarted);
        }
    }

    /** null means corrupt ledger, never permission to blindly replay its lost attempts. */
    Ledger loadLedger() {
        try {
            final String encoded = preferences.getString(LEDGER_KEY, null);
            if (encoded == null) return new Ledger(new LinkedHashMap<>(), new LinkedHashSet<>());
            final JSONObject root = new JSONObject(encoded);
            final int version = root.getInt("version");
            if (version != 1 && version != 2 && version != 3) return null;
            if (version < 3 && root.has("payloadStarted")) return null;
            if (version == 1 && (root.has("deleted") || root.has("debugDeleteTestRawId"))) return null;
            final JSONArray rows = root.getJSONArray("rows"), pending = root.getJSONArray("blocked");
            if (rows.length() > MAX_ROWS || pending.length() > MAX_ROWS) return null;
            final Map<Long, String> hashes = new LinkedHashMap<>();
            final Set<Long> blocked = new LinkedHashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                final JSONArray row = rows.getJSONArray(i);
                if (row.length() != 3 || !row.getBoolean(2)) return null;
                final long id = row.getLong(0); final String hash = row.getString(1);
                if (id <= 0 || !hash.matches("[0-9a-f]{64}") || hashes.put(id, hash) != null) return null;
            }
            for (int i = 0; i < pending.length(); i++) {
                final long id = pending.getLong(i);
                if (id <= 0 || !blocked.add(id)) return null;
            }
            final long testId = root.has("debugModifyTestRawId") ? root.getLong("debugModifyTestRawId") : 0;
            if (testId != 0 && (testId < 0 || !hashes.containsKey(testId))) return null;
            final Set<Long> deleted = new LinkedHashSet<>();
            if (version >= 2) {
                final JSONArray tombstones = root.getJSONArray("deleted");
                if (tombstones.length() > MAX_ROWS) return null;
                for (int i = 0; i < tombstones.length(); i++) {
                    final long id = tombstones.getLong(i);
                    if (!hashes.containsKey(id) || !deleted.add(id)) return null;
                }
            }
            final long deleteTestId = root.has("debugDeleteTestRawId") ? root.getLong("debugDeleteTestRawId") : 0;
            if (deleteTestId != 0 && (deleteTestId < 0 || testId != 0 || !hashes.containsKey(deleteTestId))) return null;
            final Set<Long> payloadStarted = new LinkedHashSet<>();
            if (version == 3) {
                final JSONArray started = root.getJSONArray("payloadStarted");
                if (started.length() > MAX_ROWS) return null;
                for (int i = 0; i < started.length(); i++) {
                    final long id = started.getLong(i);
                    if (!blocked.contains(id) || !payloadStarted.add(id)) return null;
                }
            }
            return new Ledger(hashes, blocked, testId, deleted, deleteTestId, payloadStarted);
        } catch (final Exception e) { return null; }
    }

    boolean saveLedger(final Ledger ledger) {
        if (ledger.delivered.size() > MAX_ROWS || ledger.blocked.size() > MAX_ROWS) return false;
        try {
            if (!ledger.delivered.keySet().containsAll(ledger.deleted) || !ledger.blocked.containsAll(ledger.payloadStarted)) return false;
            final JSONArray rows = new JSONArray(), blocked = new JSONArray(), deleted = new JSONArray(), payloadStarted = new JSONArray();
            for (final Map.Entry<Long, String> row : ledger.delivered.entrySet())
                rows.put(new JSONArray().put(row.getKey()).put(row.getValue()).put(true));
            for (final long id : ledger.blocked) blocked.put(id);
            for (final long id : ledger.deleted) deleted.put(id);
            for (final long id : ledger.payloadStarted) payloadStarted.put(id);
            // Older binaries fail closed rather than dropping attempt-stage or tombstone information.
            final JSONObject root = new JSONObject().put("version", 3).put("rows", rows).put("blocked", blocked)
                    .put("deleted", deleted).put("payloadStarted", payloadStarted);
            if (ledger.debugModifyTestRawId != 0) {
                if (ledger.debugModifyTestRawId < 0 || !ledger.delivered.containsKey(ledger.debugModifyTestRawId)) return false;
                root.put("debugModifyTestRawId", ledger.debugModifyTestRawId);
            }
            if (ledger.debugDeleteTestRawId != 0) {
                if (ledger.debugDeleteTestRawId < 0 || ledger.debugModifyTestRawId != 0
                        || !ledger.delivered.containsKey(ledger.debugDeleteTestRawId)) return false;
                root.put("debugDeleteTestRawId", ledger.debugDeleteTestRawId);
            }
            final String encoded = root.toString();
            final String previous = preferences.getString(LEDGER_KEY, null);
            final SharedPreferences.Editor write = preferences.edit().putString(LEDGER_KEY, encoded);
            if (write.commit()) return true;
            final SharedPreferences.Editor rollback = preferences.edit();
            if (previous == null) rollback.remove(LEDGER_KEY); else rollback.putString(LEDGER_KEY, previous);
            rollback.commit(); return false;
        } catch (final Exception e) { return false; }
    }

    State load() {
        final String encoded;
        try { encoded = preferences.getString(KEY, null); }
        catch (final ClassCastException e) { return null; }
        if (encoded == null) return null;
        try {
            final JSONObject root = new JSONObject(encoded);
            if (root.getInt("version") != VERSION) return null;
            final boolean acknowledged = root.getBoolean("acknowledged");
            final boolean initialRequired = root.getBoolean("initialRequired");
            // Older builds advanced on transport completion. Their baseline may be divergent.
            final boolean pendingVerification = root.has("pendingVerification")
                    ? root.getBoolean("pendingVerification") : acknowledged && !initialRequired;
            final JSONArray rows = root.getJSONArray("rows");
            if (rows.length() > MAX_ROWS || (!acknowledged && rows.length() != 0)
                    || (pendingVerification && !acknowledged)) return null;
            final Map<Long, String> hashes = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                final JSONArray row = rows.getJSONArray(i);
                if (row.length() != 2) return null;
                final long id = row.getLong(0);
                final String hash = row.getString(1);
                if (id <= 0 || !hash.matches("[0-9a-f]{64}") || hashes.put(id, hash) != null) return null;
            }
            return new State(hashes, acknowledged, initialRequired, pendingVerification);
        } catch (final Exception e) {
            return null;
        }
    }

    boolean save(final State state) {
        if (state.hashes.size() > MAX_ROWS) return false;
        try {
            final JSONArray rows = new JSONArray();
            for (final Map.Entry<Long, String> entry : state.hashes.entrySet()) {
                rows.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
            }
            final String encoded = new JSONObject().put("version", VERSION)
                    .put("acknowledged", state.acknowledged)
                    .put("initialRequired", state.initialRequired)
                    .put("pendingVerification", state.pendingVerification)
                    .put("rows", rows).toString();
            // Synchronous structural-state checkpoint; completed initial import also resets the ledger.
            final String previous = preferences.getString(KEY, null);
            final String previousLedger = preferences.getString(LEDGER_KEY, null);
            final SharedPreferences.Editor write = preferences.edit().putString(KEY, encoded);
            if (state.resetLedger) write.remove(LEDGER_KEY);
            if (write.commit()) return true;
            // SharedPreferences may change its in-memory map even if disk commit failed.
            final SharedPreferences.Editor rollback = preferences.edit();
            if (previous == null) rollback.remove(KEY);
            else rollback.putString(KEY, previous);
            if (state.resetLedger) {
                if (previousLedger == null) rollback.remove(LEDGER_KEY); else rollback.putString(LEDGER_KEY, previousLedger);
            }
            rollback.commit();
            return false;
        } catch (final Exception e) {
            return false;
        }
    }
}
