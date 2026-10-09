package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;

/** Debug queues/seeds are process-local; the isolated durable test scope belongs to the device ledger. */
final class GearContactsDebugRequests {
    enum Mode { NONE, DELETES, INSERT, REPLACE }
    interface Target {
        String debugWaitingReason();
        void armDebugRequest(Mode mode, long rawId, String previousHash);
        default String productionTestPreparationRejection() { return null; }
        default void persistentTestStateChanged() {}
    }
    private static final Logger LOG = LoggerFactory.getLogger(GearContactsDebugRequests.class);
    // Queue/seed state is never persisted. The receiver owns application context and device preferences,
    // never DeviceSupport; durable test authorization is reconstructed from the ledger.
    private static final Map<String, GearContactsDebugRequests> PROCESS_REQUESTS = new HashMap<>();

    static synchronized GearContactsDebugRequests forDevice(final String address) {
        if (!BuildConfig.DEBUG) return new GearContactsDebugRequests();
        final String key = address.toUpperCase(Locale.ROOT);
        GearContactsDebugRequests requests = PROCESS_REQUESTS.get(key);
        if (requests == null || requests.closed) {
            requests = new GearContactsDebugRequests();
            PROCESS_REQUESTS.put(key, requests);
        } else {
            requests.logRestored();
        }
        return requests;
    }

    private synchronized void logRestored() {
        if (pending != Mode.NONE) {
            LOG.debug("Gear S3 contacts pending debug request restored into newly-created GearS3DeviceSupport: mode={} rawId={}", pending, rawId);
        }
        if (seededHash != null) {
            LOG.debug("Gear S3 contacts seeded INSERT state restored into newly-created GearS3DeviceSupport: rawId={}; transport delivered, importer result unknown", seededRawId);
        }
        if (productionTest != null) LOG.debug("Gear S3 contacts production-test baseline restored into newly-created GearS3DeviceSupport: rawId={}; process-local, persisted baseline unchanged", productionTest.rawId);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deliver = this::deliverPending;
    private Target target, deliveredTo, active;
    private Mode pending = Mode.NONE;
    private long rawId = -1, seededRawId = -1;
    private String seededHash;
    private boolean seedAttempted, closed;
    // Explicit trials deliberately do not checkpoint. Do not let normal scans replay their IDs.
    private final Set<Long> debugAttemptedIds = new LinkedHashSet<>();
    private Context receiverContext;
    private BroadcastReceiver receiver;
    private final ExecutorService preparer = Executors.newSingleThreadExecutor();
    private boolean preparingProductionTest;
    private long preparationGeneration;
    private long preparingRawId = -1;
    private ProductionTestBaseline productionTest;
    private GearContactsSnapshotStore persistentStore;
    private boolean preparingPersistentTest, preparingPersistentDeletion;

    static final class ProductionTestBaseline {
        final long rawId;
        final GearContactsSnapshot snapshot = new GearContactsSnapshot();
        ProductionTestBaseline(final GearContactsCodec.Contact current) {
            rawId = current.rawId;
            // Stores only the production fingerprint/ID; does not retain the Contact or persist anything.
            snapshot.recordTransportDeliveredSnapshot(snapshot.compare(Collections.singletonList(current), true));
        }
    }

    void register(final Context context, final String deviceAddress) { register(context, deviceAddress, null); }

    void register(final Context context, final String deviceAddress, final SharedPreferences devicePreferences) {
        if (!BuildConfig.DEBUG) return;
        if (devicePreferences != null) persistentStore = new GearContactsSnapshotStore(devicePreferences);
        if (receiver != null) return;
        receiverContext = context.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(final Context context, final Intent intent) {
                final String address = intent.getStringExtra(GearContactsService.DEBUG_DEVICE_ADDRESS_EXTRA);
                if (address == null || !deviceAddress.equalsIgnoreCase(address)) return;
                final String action = intent.getAction();
                if (GearContactsService.DEBUG_PREPARE_PERSISTENT_DELETE_RAW_ACTION.equals(action)) {
                    preparePersistentDeleteTest(intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
                    return;
                }
                if (GearContactsService.DEBUG_CLEAR_PERSISTENT_DELETE_RAW_ACTION.equals(action)) {
                    clearPersistentDeleteTest(intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
                    return;
                }
                if (GearContactsService.DEBUG_PREPARE_PERSISTENT_MODIFY_RAW_ACTION.equals(action)) {
                    preparePersistentTest(intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
                    return;
                }
                if (GearContactsService.DEBUG_CLEAR_PERSISTENT_MODIFY_RAW_ACTION.equals(action)) {
                    clearPersistentTest(intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
                    return;
                }
                if (GearContactsService.DEBUG_PREPARE_PRODUCTION_MODIFY_RAW_ACTION.equals(action)) {
                    prepareProductionTest(intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
                    return;
                }
                if (GearContactsService.DEBUG_CLEAR_PRODUCTION_MODIFY_RAW_ACTION.equals(action)) {
                    clearProductionTest(intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
                    return;
                }
                final Mode mode = GearContactsService.DEBUG_INSERT_RAW_ACTION.equals(action) ? Mode.INSERT
                        : GearContactsService.DEBUG_REPLACE_RAW_ACTION.equals(action) ? Mode.REPLACE
                        : GearContactsService.DEBUG_PENDING_DELETES_ACTION.equals(action) ? Mode.DELETES : Mode.NONE;
                request(mode, intent.getLongExtra(GearContactsService.DEBUG_RAW_ID_EXTRA, -1));
            }
        };
        final IntentFilter filter = new IntentFilter(GearContactsService.DEBUG_PENDING_DELETES_ACTION);
        filter.addAction(GearContactsService.DEBUG_INSERT_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_REPLACE_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_PREPARE_PRODUCTION_MODIFY_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_CLEAR_PRODUCTION_MODIFY_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_PREPARE_PERSISTENT_MODIFY_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_CLEAR_PERSISTENT_MODIFY_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_PREPARE_PERSISTENT_DELETE_RAW_ACTION);
        filter.addAction(GearContactsService.DEBUG_CLEAR_PERSISTENT_DELETE_RAW_ACTION);
        ContextCompat.registerReceiver(receiverContext, receiver, filter, "android.permission.DUMP", handler,
                ContextCompat.RECEIVER_EXPORTED);
    }

    synchronized boolean request(final Mode mode, final long id) {
        LOG.debug("Gear S3 contacts debug request received: mode={} rawId={}", mode, id);
        final String rejection = closed ? "debug controller closed"
                : hasPersistentTest() ? "persistent MODIFY test scope active"
                : hasProductionTest() ? "production MODIFY test baseline already prepared or preparing"
                : pending != Mode.NONE || active != null ? "another debug request already pending"
                : mode == Mode.NONE || (mode != Mode.DELETES && id <= 0) ? "invalid mode or raw ID"
                : mode == Mode.INSERT && seedAttempted ? "INSERT already attempted; replay unsafe"
                : mode == Mode.REPLACE && seededHash == null ? "REPLACE requires delivered seeded INSERT"
                : mode == Mode.REPLACE && id != seededRawId ? "REPLACE raw ID differs from seeded INSERT"
                : null;
        if (rejection != null) {
            LOG.warn("Gear S3 contacts debug request rejected: {}", rejection);
            return false;
        }
        pending = mode;
        rawId = id;
        if (mode == Mode.REPLACE) LOG.debug("Gear S3 contacts debug REPLACE accepted: rawId={}; previous seeded fingerprint required", id);
        wake();
        return true;
    }

    synchronized boolean prepareProductionTest(final long id) {
        final String rejection = !BuildConfig.DEBUG || closed || receiverContext == null ? "debug receiver unavailable"
                : id <= 0 ? "invalid raw ID"
                : pending != Mode.NONE || active != null || hasProductionTest() || hasPersistentTest() ? "another debug request or production test already pending"
                : target == null ? null : target.productionTestPreparationRejection();
        if (rejection != null) {
            LOG.warn("Gear S3 contacts production-test PREPARE rejected: {}", rejection);
            return false;
        }
        preparingProductionTest = true;
        preparingRawId = id;
        final long generation = ++preparationGeneration;
        LOG.debug("Gear S3 contacts production-test PREPARE received: rawId={}; local read only", id);
        preparer.execute(() -> {
            ProductionTestBaseline baseline = null;
            String failure = null;
            try {
                final GearContactsCodec.Contact current = GearContactsSource.readRaw(receiverContext, id);
                if (current == null) failure = "raw ID absent or not sync-eligible";
                else baseline = new ProductionTestBaseline(current);
            } catch (final SecurityException e) { failure = "READ_CONTACTS permission missing"; }
            catch (final Exception e) { failure = "Android contact query failed"; }
            final ProductionTestBaseline prepared = baseline;
            final String error = failure;
            handler.post(() -> {
                synchronized (GearContactsDebugRequests.this) {
                    if (closed || generation != preparationGeneration) return;
                    preparingProductionTest = false; preparingRawId = -1;
                    final String busy = target == null ? null : target.productionTestPreparationRejection();
                    if (error != null || busy != null) {
                        LOG.warn("Gear S3 contacts production-test PREPARE rejected: {}", error != null ? error : busy);
                        return;
                    }
                    productionTest = prepared;
                    // This user-asserted test baseline is not in the real ledger. CLEAR must not cause a blind I.
                    debugAttemptedIds.add(id);
                    LOG.debug("Gear S3 contacts production-test baseline prepared: rawId={}; current production fingerprint captured; process-local; persisted baseline unchanged; no files sent; awaiting real Resume", id);
                }
            });
        });
        return true;
    }

    synchronized boolean hasPersistentTest() {
        if (!BuildConfig.DEBUG || persistentStore == null) return false;
        final GearContactsSnapshotStore.Ledger ledger = persistentStore.loadLedger();
        return ledger != null && (ledger.debugModifyTestRawId > 0 || ledger.debugDeleteTestRawId > 0);
    }

    synchronized boolean preparePersistentTest(final long id) { return preparePersistentTest(id, false); }
    synchronized boolean preparePersistentDeleteTest(final long id) { return preparePersistentTest(id, true); }

    private boolean preparePersistentTest(final long id, final boolean deletion) {
        final String rejection = !BuildConfig.DEBUG || closed || receiverContext == null || persistentStore == null ? "debug persistent store unavailable"
                : id <= 0 ? "invalid disposable raw ID for test mode"
                : pending != Mode.NONE || active != null || hasProductionTest() || hasPersistentTest() ? "another debug request or test already pending"
                : target == null ? null : target.productionTestPreparationRejection();
        if (rejection != null) { LOG.warn("Gear S3 contacts persistent-test PREPARE rejected: {}", rejection); return false; }
        preparingProductionTest = preparingPersistentTest = true; preparingRawId = id; preparingPersistentDeletion = deletion;
        final long generation = ++preparationGeneration;
        LOG.debug("Gear S3 contacts {} PREPARE received: rawId={}; local read only", deletion ? "persistent-delete test" : "persistent-test", id);
        preparer.execute(() -> {
            GearContactsCodec.Contact current = null;
            try { current = GearContactsSource.readRaw(receiverContext, id); }
            catch (final Exception e) { /* Structural rejection below; never log provider data. */ }
            final GearContactsCodec.Contact captured = current;
            handler.post(() -> {
                synchronized (GearContactsDebugRequests.this) {
                    if (closed || generation != preparationGeneration) return;
                    preparingProductionTest = preparingPersistentTest = preparingPersistentDeletion = false; preparingRawId = -1;
                    final String busy = target == null ? null : target.productionTestPreparationRejection();
                    final GearContactsSnapshot durable = new GearContactsSnapshot(persistentStore);
                    if (captured == null || busy != null || !(deletion ? durable.preparePersistentDeleteTest(captured) : durable.preparePersistentTest(captured))) {
                        LOG.warn("Gear S3 contacts persistent-test PREPARE rejected: target missing, service busy, legacy state unsafe, existing ledger differs, or persistence failed; no files sent");
                        return;
                    }
                    if (target != null) target.persistentTestStateChanged();
                    if (deletion) LOG.debug("Gear S3 contacts persistent deletion baseline prepared: rawId={}; key=samsung_gear_contacts_transport_ledger_v1; user-prepared current fingerprint; legacy baseline unchanged; no files sent; awaiting real Resume", id);
                    else LOG.debug("Gear S3 contacts persistent-test baseline prepared: rawId={}; key=samsung_gear_contacts_transport_ledger_v1; user-prepared current fingerprint, no transfer performed; semantic result unknown; legacy baseline unchanged; no files sent; awaiting real Resume", id);
                }
            });
        });
        return true;
    }

    synchronized boolean clearPersistentTest(final long id) { return clearPersistentTest(id, false); }
    synchronized boolean clearPersistentDeleteTest(final long id) { return clearPersistentTest(id, true); }

    private boolean clearPersistentTest(final long id, final boolean deletion) {
        if (!BuildConfig.DEBUG || closed || persistentStore == null || id <= 0) {
            LOG.warn("Gear S3 contacts persistent-test CLEAR rejected: matching disposable raw ID/store required"); return false;
        }
        final String busy = target == null ? null : target.productionTestPreparationRejection();
        if (busy != null) { LOG.warn("Gear S3 contacts persistent-test CLEAR rejected: {}", busy); return false; }
        if (preparingPersistentTest && preparingPersistentDeletion == deletion && preparingRawId == id) {
            preparationGeneration++; preparingProductionTest = preparingPersistentTest = preparingPersistentDeletion = false; preparingRawId = -1;
            LOG.debug("Gear S3 contacts {} preparation cancelled: rawId={}; no files sent", deletion ? "persistent-delete test" : "persistent-test", id); return true;
        }
        final GearContactsSnapshot durable = new GearContactsSnapshot(persistentStore);
        if (!(deletion ? durable.clearPersistentDeleteTest(id) : durable.clearPersistentTest(id))) {
            LOG.warn("Gear S3 contacts persistent-test CLEAR rejected: no matching scope or persistence failed"); return false;
        }
        if (target != null) target.persistentTestStateChanged();
        if (deletion) LOG.debug("Gear S3 contacts persistent deletion test scope cleared: rawId={}; ledger hashes, tombstones and blocked attempts retained; legacy baseline unchanged; no files sent", id);
        else LOG.debug("Gear S3 contacts persistent-test scope cleared: rawId={}; delivered ledger entries and blocked attempts retained; legacy baseline unchanged; no files sent", id);
        return true;
    }

    synchronized boolean hasProductionTest() { return preparingProductionTest || productionTest != null; }
    synchronized ProductionTestBaseline productionTestBaseline() { return productionTest; }
    synchronized boolean isCurrentProductionTest(final ProductionTestBaseline baseline) {
        return baseline != null && productionTest == baseline;
    }
    synchronized boolean clearProductionTest(final long id) {
        final long current = productionTest != null ? productionTest.rawId : preparingPersistentTest ? -1 : preparingRawId;
        if (id <= 0 || id != current) {
            LOG.warn("Gear S3 contacts production-test CLEAR rejected: no matching prepared raw ID");
            return false;
        }
        preparationGeneration++;
        productionTest = null; preparingProductionTest = false; preparingRawId = -1;
        LOG.debug("Gear S3 contacts production-test override cleared: rawId={}; persisted baseline unchanged", id);
        return true;
    }

    synchronized void attach(final Target service) { target = service; wake(); }
    synchronized void detach(final Target service) {
        if (target == service) target = null;
        if (deliveredTo == service) {
            deliveredTo = null; // No file setup emitted: preserve the request for the next service.
            LOG.info("Gear S3 contacts debug request queued: Contacts service recreated before transmission");
        }
        if (active == service) {
            active = null;
            seededHash = null;
            LOG.warn("Gear S3 contacts debug trial interrupted after transmission; no automatic replay; baseline unchanged");
        }
        if (seededHash != null) LOG.debug("Gear S3 contacts seeded INSERT retained: rawId={}; process-local, transport delivered only", seededRawId);
        wake();
    }
    synchronized void wake() {
        if (closed) return;
        handler.removeCallbacks(deliver);
        handler.post(deliver);
    }
    private synchronized void deliverPending() {
        if (closed || pending == Mode.NONE || deliveredTo != null || active != null) return;
        final String waiting = target == null ? "contacts service absent" : target.debugWaitingReason();
        if (waiting != null) {
            LOG.info("Gear S3 contacts debug request queued: {}", waiting);
            return;
        }
        deliveredTo = target;
        LOG.debug("Gear S3 contacts delivered pending request to Contacts service: mode={} rawId={}", pending, rawId);
        target.armDebugRequest(pending, rawId, seededHash);
    }

    synchronized void excludeDebugAttempts(final GearContactsSnapshot.Delta delta) {
        delta.insert.removeIf(c -> debugAttemptedIds.contains(c.rawId));
        delta.update.removeIf(c -> debugAttemptedIds.contains(c.rawId));
        delta.delete.removeIf(debugAttemptedIds::contains);
    }

    synchronized void started(final Target service, final Mode mode, final Set<Long> ids) {
        if (deliveredTo != service && active != service) return;
        debugAttemptedIds.addAll(ids);
        if (active == service) return;
        if (mode == Mode.INSERT) seedAttempted = true;
        deliveredTo = null;
        active = service;
        pending = Mode.NONE;
        rawId = -1;
    }
    synchronized void completed(final Target service, final long id, final String hash) {
        if (active != service) return;
        seededRawId = id;
        seededHash = hash; // Transport receipt only; user must check the watch importer/UI.
        active = null;
        LOG.debug("Gear S3 contacts seeded INSERT retained: rawId={}; process-local, transport delivered, importer result unknown", id);
    }
    synchronized void finish(final Target service) {
        if (deliveredTo == service) { pending = Mode.NONE; rawId = -1; deliveredTo = null; }
        if (active == service) { active = null; seededHash = null; }
    }
    synchronized boolean hasPending() { return pending != Mode.NONE; }
    synchronized boolean isActive() { return active != null; }
    synchronized void invalidateSeed() { seededHash = null; }
    synchronized void retryBeforeTransmission(final Target service) {
        if (deliveredTo == service) deliveredTo = null;
        wake();
    }
    synchronized void close() {
        closed = true;
        preparationGeneration++;
        preparer.shutdownNow();
        productionTest = null; preparingProductionTest = preparingPersistentTest = preparingPersistentDeletion = false; preparingRawId = -1;
        handler.removeCallbacks(deliver);
        if (receiver != null) receiverContext.unregisterReceiver(receiver);
        receiver = null;
        pending = Mode.NONE;
        target = deliveredTo = active = null;
        seededHash = null;
        debugAttemptedIds.clear();
    }
}
