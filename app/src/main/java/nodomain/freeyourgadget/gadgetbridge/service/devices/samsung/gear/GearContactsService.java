package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.Manifest;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;


import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearContactsDebugRequests.Mode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapAgentDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCapabilityExchange;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerAppCipher;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapQosDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapReliableSession;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTermination;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTerminationResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;

/** Bounded b2contacts provider and file-transfer sender. No contact content is logged. */
public final class GearContactsService implements GearContactsDebugRequests.Target {
    public static final String CONTACTS = "/system/b2contacts";
    public static final String FILE_TRANSFER = "/system/filetransfer";
    public static final String DEBUG_PENDING_DELETES_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_PENDING_DELETES";
    public static final String DEBUG_INSERT_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_INSERT_RAW";
    public static final String DEBUG_REPLACE_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_REPLACE_RAW";
    public static final String DEBUG_PREPARE_PRODUCTION_MODIFY_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_PREPARE_PRODUCTION_MODIFY_RAW";
    public static final String DEBUG_CLEAR_PRODUCTION_MODIFY_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_CLEAR_PRODUCTION_MODIFY_RAW";
    public static final String DEBUG_PREPARE_PERSISTENT_MODIFY_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_PREPARE_PERSISTENT_MODIFY_RAW";
    public static final String DEBUG_CLEAR_PERSISTENT_MODIFY_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_CLEAR_PERSISTENT_MODIFY_RAW";
    public static final String DEBUG_PREPARE_PERSISTENT_DELETE_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_PREPARE_PERSISTENT_DELETE_RAW";
    public static final String DEBUG_CLEAR_PERSISTENT_DELETE_RAW_ACTION = "nodomain.freeyourgadget.gadgetbridge.DEBUG_GEAR_S3_CONTACTS_CLEAR_PERSISTENT_DELETE_RAW";
    public static final String DEBUG_DEVICE_ADDRESS_EXTRA = "device_address";
    public static final String DEBUG_RAW_ID_EXTRA = "raw_contact_id";
    private static final long OBSERVER_DEBOUNCE_MS = 350;
    // Connection watchdog, not a key receipt or importer ACK.
    // Allow the initial idle grace period of 60 seconds.
    private static final long CONTROL_HANDSHAKE_TIMEOUT_MS = 60000;
    // Retry control setup five times, two seconds apart.
    private static final long CONTROL_RETRY_MS = 2000;
    private static final int MAX_CONTROL_RETRIES = 5;
    private static final long CONTROL_CLOSE_TIMEOUT_MS = 10000;
    // Bound automatic full reconnects across support recreation, until a real handshake.
    // Process-local device addresses only; no preferences, payloads or fingerprints.
    private static final Set<String> CONTROL_RECONNECT_USED = new HashSet<>();
    // Bluetooth file-transfer setup timeout; a failure bound, never an inter-file delay.
    private static final long REPLACE_FT_TIMEOUT_MS = 10000;
    private static final Logger LOG = LoggerFactory.getLogger(GearContactsService.class);
    private static final String CONTACT_COMPONENT = "samsung_gear_contacts_component_id";
    private static final String FT_COMPONENT = "samsung_gear_contacts_ft_component_id";
    private static final String FT_AGENT = "samsung_gear_contacts_ft_agent_id";
    private static final int MAX_CHUNK = 16384;
    // SAContactB2FileSendingInOrder.COUNT_FOR_RETRY: one retry per failed file.
    private static final int MAX_FT_RECOVERY_RETRIES = 1;
    private final Context context;
    private final String deviceAddress;
    private final GearSessionSecrets secrets;
    private final Emitter emitter;
    private final int contactsComponent, ftComponent, ftAgent, hostAgent;
    private final SecureRandom random = new SecureRandom();
    private final byte[] contactKey = new byte[32];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final GearContactsSnapshot snapshot;
    private GearContactsSnapshot pendingTransportSnapshot;
    private boolean pendingPersistentTest;
    private boolean ftNeedsRefreshAfterDelete;
    private final GearContactsOneShotGate debugOneShot = new GearContactsOneShotGate();
    private final GearContactsDebugRequests debugRequests;
    private final SapReliableSession contactsRx = new SapReliableSession(), ftCommandRx = new SapReliableSession(), ftDataRx = new SapReliableSession();
    private final ArrayDeque<PendingFile> files = new ArrayDeque<>();
    private final Set<Long> remoteAttemptIds = new HashSet<>();
    private final ContentObserver observer;
    private final Runnable observerScan = () -> scheduleScan(false);
    private final BroadcastReceiver connectionUpdates = new BroadcastReceiver() {
        @Override public void onReceive(final Context context, final Intent intent) {
            final GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            // Check live transport too: a delayed disconnect broadcast must not dispose
            // a service already initialized for a replacement connection.
            if (device != null && deviceAddress.equals(device.getAddress())
                    && !device.isConnected() && !emitter.isConnected()) dispose();
        }
    };
    private enum ReplaceBoundary { NONE, CLOSING_FT, OPENING_FT }
    private ReplaceBoundary replaceBoundary = ReplaceBoundary.NONE;
    private Runnable replaceBoundaryTimeout;
    private long ftBoundaryEpoch;
    private Runnable replaceTransferTimeout;
    private long ftTimeoutEpoch, ftGeneration, ftRecoveryEpoch;
    private enum FtState { DISCONNECTED, CONNECTING, READY, SETUP_PENDING, PAYLOAD_MAY_HAVE_STARTED, RETIRING, EXHAUSTED }
    private FtState ftState = FtState.DISCONNECTED;
    private final Set<Integer> retiredFtSessions = new HashSet<>();
    private Runnable ftRecoveryTimeout;
    private boolean ftRecoveryPending, ftRecoveryContinuation, ftRecoveryFromResume, ftRefreshInProgress;
    private int ftRecoveryRetries;
    private int remoteContacts = -1, remoteFt = -1, contactsSession = -1, ftCommandSession = -1, ftDataSession = -1;
    private int pendingContacts = -1, pendingFtCommand = -1, pendingFtData = -1;
    private int supersededContacts = -1;
    private int contactsTx = 1, ftCommandTx = 1, ftDataTx = 1, transferId = 1, lastDataAck;
    private int scanGeneration, sourceRevision;
    private SapHostManagerAppCipher keyCipher;
    private boolean initialPending, scanInProgress, scanAgain, watching, disposed, logSyncRequested;
    private boolean pendingInitial, pendingVerificationLogged, pendingDebugOneShot, pendingReplacement, pendingFromResume;
    private boolean controlReady;
    // Local key enqueue is not remote key receipt. Only this SAP generation's
    // watch-originated Resume/Initial boundary permits starting another file.
    private long controlGeneration, readyControlGeneration = -1;
    private final Set<Integer> retiredContactsSessions = new HashSet<>();
    private boolean resumeScanPending;
    private enum ControlState { DISCONNECTED, CONNECTING, AWAITING_HANDSHAKE, READY, RETIRING, EXHAUSTED }
    private ControlState controlState = ControlState.DISCONNECTED;
    private int controlRetries;
    private long controlRecoveryEpoch;
    private Runnable controlWatchdog, controlRetry, controlEscalation;
    private boolean controlReconnectPending;
    private Mode debugMode = Mode.NONE, activeDebugMode = Mode.NONE;
    private long debugRawId = -1;
    private String seededHash;
    private GearContactsSnapshot.Delta pendingDelta;
    private PendingFile sending;

    public interface Emitter {
        void send(SapPostAuthRecord record);
        Set<Integer> usedSessions();
        default void reconnect(final GearContactsService requester) { }
        default boolean isConnected() { return true; }
    }

    public GearContactsService(final Context context, final SharedPreferences preferences,
                               final SharedPreferences devicePreferences, final String deviceAddress, final GearSessionSecrets secrets,
                               final SapHostManagerRegistration host, final Set<Integer> usedComponents, final Emitter emitter) {
        this(context, preferences, devicePreferences, deviceAddress, secrets, host, usedComponents, emitter,
                new GearContactsDebugRequests());
    }

    GearContactsService(final Context context, final SharedPreferences preferences,
                        final SharedPreferences devicePreferences, final String deviceAddress, final GearSessionSecrets secrets,
                        final SapHostManagerRegistration host, final Set<Integer> usedComponents, final Emitter emitter,
                        final GearContactsDebugRequests debugRequests) {
        this.context = context.getApplicationContext(); this.deviceAddress = deviceAddress; this.secrets = secrets; this.emitter = emitter;
        this.debugRequests = debugRequests;
        snapshot = new GearContactsSnapshot(new GearContactsSnapshotStore(devicePreferences));
        LOG.info("Gear S3 contacts baseline restored: usable={} records={} deltaVerificationPending={} transportUncertainIds={} blockedIncompleteIds={}",
                snapshot.isInitialized(), snapshot.size(), snapshot.hasPendingVerification(),
                snapshot.transportUncertainCount(), snapshot.blockedTransferCount());
        for (final long id : snapshot.blockedTransferIds())
            LOG.debug("Gear S3 contacts incomplete attempt restored: rawId={}; stage={}; protected; semantic result unknown", id, snapshot.incompleteStage(id));
        logPersistentTestRestored();
        this.hostAgent = host.getAgentUuid();
        final Set<Integer> used = new HashSet<>(usedComponents);
        contactsComponent = stableId(preferences, CONTACT_COMPONENT, used); used.add(contactsComponent);
        ftComponent = stableId(preferences, FT_COMPONENT, used); used.add(ftComponent);
        used.add(hostAgent);
        ftAgent = stableId(preferences, FT_AGENT, used);
        random.nextBytes(contactKey);
        observer = new ContentObserver(handler) {
            @Override public void onChange(final boolean selfChange) {
                sourceRevision++;
                handler.removeCallbacks(observerScan);
                handler.postDelayed(observerScan, OBSERVER_DEBOUNCE_MS);
            }
        };
        LocalBroadcastManager.getInstance(this.context).registerReceiver(connectionUpdates,
                new IntentFilter(GBDevice.ACTION_DEVICE_CHANGED));
        debugRequests.attach(this);
    }

    @Override public String debugWaitingReason() {
        if (disposed) return "contacts service absent";
        if (contactsSession < 1) return "SAP not established";
        if (!controlReady || !newFileReady()) return "waiting for Resume boundary";
        if (!snapshot.isInitialized()) return "usable retained baseline unavailable";
        if (!hasPermission()) return "READ_CONTACTS permission missing";
        if (scanInProgress || pendingDelta != null || sending != null || !files.isEmpty()) return "Contacts scan or transfer busy";
        if (remoteFt < 0) return "file-transfer descriptor unavailable";
        return null;
    }

    @Override public String productionTestPreparationRejection() {
        if (scanInProgress || pendingDelta != null || sending != null || !files.isEmpty() || initialPending) return "Contacts scan or transfer busy";
        if (!snapshot.isInitialized() || !snapshot.hasPendingVerification()) return "production test requires a usable paused retained baseline";
        return null;
    }

    @Override public void persistentTestStateChanged() {
        snapshot.reloadLedger();
        scanGeneration++; // Invalidate any obsolete selection even if CLEAR/PREPARE races service recreation.
        logPersistentTestRestored();
    }

    private void logPersistentTestRestored() {
        final long id = snapshot.persistentTestRawId();
        final long deleteId = snapshot.persistentDeleteTestRawId();
        if (deleteId > 0) LOG.debug("Gear S3 contacts persistent deletion restored: rawId={}; transportDeleted={}; blockedIncomplete={}; semantic result unknown", deleteId, snapshot.isTransportDeleted(deleteId), snapshot.isBlocked(deleteId));
        if (id > 0) LOG.debug("Gear S3 contacts transport ledger restored: rawId={}; transportUncertain=true; key=samsung_gear_contacts_transport_ledger_v1; persistent-test scope only; blockedIncomplete={}; semantic result unknown", id, snapshot.isBlocked(id));
    }

    @Override public void armDebugRequest(final Mode mode, final long rawId, final String previousHash) {
        if (!debugOneShot.arm()) {
            LOG.warn("Gear S3 contacts debug request rejected: one-shot already armed");
            debugRequests.finish(this);
            return;
        }
        debugMode = mode;
        debugRawId = rawId;
        seededHash = previousHash;
        pendingVerificationLogged = false;
        LOG.debug("Gear S3 contacts debug one-shot armed: mode={} rawId={}; baseline unchanged", mode, rawId);
        scheduleScan(false);
    }

    private int stableId(final SharedPreferences prefs, final String key, final Set<Integer> used) {
        int value = prefs.getInt(key, -1);
        if (value < 0x100 || value > 0xfeff || used.contains(value)) {
            do { value = 0x100 + random.nextInt(0xfe00); } while (used.contains(value));
            if (!prefs.edit().putInt(key, value).commit()) throw new IllegalStateException("Could not save Gear contacts SAP identity");
        }
        return value;
    }

    public List<SapCapabilityExchange.LocalServiceMetadata> metadata() {
        final List<SapCapabilityExchange.LocalServiceMetadata> result = new ArrayList<>();
        result.add(new SapCapabilityExchange.LocalServiceMetadata(hostAgent, Integer.toString(hostAgent),
                SapHostManagerRegistration.FRIENDLY_NAME, contactsComponent, Integer.toString(contactsComponent),
                CONTACTS, 0x0200, "2.0", 0, 0x04, 0, 2, 0));
        result.add(new SapCapabilityExchange.LocalServiceMetadata(ftAgent, Integer.toString(ftAgent),
                "SASystemProviders", ftComponent, Integer.toString(ftComponent),
                FILE_TRANSFER, 0x0200, "2.0", 0, 0x04, 10, 2, 9));
        return result;
    }

    public void learnRemote(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) for (final SapServiceDescriptor service : agent.getServices()) {
            if (CONTACTS.equals(service.getProfileId()) && service.getAspVersion() == 0x0100
                    && service.getRoleFlags() == 0x05 && service.getTimeout() == 0) remoteContacts = service.getComponentId();
            if (FILE_TRANSFER.equals(service.getProfileId()) && service.getAspVersion() == 0x0200
                    && service.getRoleFlags() == 0x05) remoteFt = service.getComponentId();
        }
        LOG.info("Gear S3 contacts descriptors: b2contacts={} filetransfer={}", remoteContacts >= 0, remoteFt >= 0);
        debugRequests.wake();
    }

    public boolean hasSession(final int session) {
        return session >= 1 && (session == contactsSession || session == ftCommandSession || session == ftDataSession);
    }
    public void addUsedSessions(final Set<Integer> used) {
        used.addAll(retiredContactsSessions);
        used.addAll(retiredFtSessions);
        for (final int id : new int[]{contactsSession, ftCommandSession, ftDataSession, pendingContacts,
                pendingFtCommand, pendingFtData, supersededContacts}) if (id >= 1) used.add(id);
    }

    public SapServiceConnectionRequest startContacts(final Set<Integer> used) {
        if (disposed || remoteContacts < 0 || contactsSession >= 1 || pendingContacts >= 1
                || controlState == ControlState.RETIRING || controlState == ControlState.EXHAUSTED) return null;
        pendingContacts = allocate(used);
        if (pendingContacts < 1) return null;
        controlState = ControlState.CONNECTING;
        armControlWatchdog(pendingContacts, CONTROL_HANDSHAKE_TIMEOUT_MS);
        return new SapServiceConnectionRequest(remoteContacts, contactsComponent, CONTACTS,
                Collections.singletonList(new SapServiceConnectionRequest.Session(pendingContacts, 109,
                        new SapQosDescriptor(5, 0, 0), 0)));
    }

    public SapServiceConnectionRequest startFileTransfer(final Set<Integer> used) {
        if (disposed || remoteFt < 0 || ftCommandSession >= 1 || pendingFtCommand >= 1) return null;
        ftDataRx.reset(); ftCommandRx.reset();
        ftGeneration++;
        ftState = FtState.CONNECTING;
        LOG.info("Gear S3 contacts acquiring fresh FT generation={}; control generation={}; readiness still required before offer", ftGeneration, controlGeneration);
        pendingFtData = allocate(used);
        if (pendingFtData >= 1) used.add(pendingFtData);
        pendingFtCommand = pendingFtData >= 1 ? allocate(used) : -1;
        if (pendingFtCommand < 1) {
            pendingFtData = -1; ftState = FtState.DISCONNECTED; return null;
        }
        return new SapServiceConnectionRequest(remoteFt, ftComponent, FILE_TRANSFER, Arrays.asList(
                new SapServiceConnectionRequest.Session(pendingFtData, 101, new SapQosDescriptor(5, 0, 0), 0),
                new SapServiceConnectionRequest.Session(pendingFtCommand, 100, new SapQosDescriptor(5, 0, 2), 0)));
    }

    private int allocate(final Set<Integer> used) {
        // Retired sessions cannot alias a late frame. Exhaustion must fail boundedly,
        // rather than spin forever after many close/reopen transactions.
        final int start = random.nextInt(992);
        for (int offset = 0; offset < 992; offset++) {
            final int id = 1 + (start + offset) % 992;
            if (!used.contains(id) && !hasSession(id) && !retiredFtSessions.contains(id)
                    && !retiredContactsSessions.contains(id) && id != pendingContacts
                    && id != pendingFtData && id != pendingFtCommand) return id;
        }
        LOG.warn("Gear S3 contacts session allocation exhausted; no retired session reused");
        return -1;
    }

    public SapServiceConnectionResponse acceptContacts(final SapServiceConnectionRequest request, final Set<Integer> used) {
        int status = SapServiceConnectionResponse.STATUS_REJECTED;
        if (!disposed && controlState != ControlState.RETIRING && remoteContacts >= 0 && contactsSession < 1 && request.getAcceptorComponentId() == contactsComponent
                && request.getInitiatorComponentId() == remoteContacts && request.getSessions().size() == 1) {
            final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
            if (session.getSessionId() >= 1 && session.getSessionId() <= 992 && session.getChannelId() == 109
                    && session.getQos().getType() == 5 && session.getQos().getDataRate() == 0
                    && session.getQos().getClassType() == 0 && session.getPayloadType() == 0) {
                status = (used.contains(session.getSessionId()) || retiredContactsSessions.contains(session.getSessionId())) ? SapServiceConnectionResponse.STATUS_SESSION_COLLISION : SapServiceConnectionResponse.STATUS_SUCCESS;
                if (status == 0) {
                    contactsSession = session.getSessionId();
                    supersededContacts = pendingContacts;
                    pendingContacts = -1;
                }
            }
        }
        final SapServiceConnectionResponse response = new SapServiceConnectionResponse(request.getInitiatorComponentId(),
                request.getAcceptorComponentId(), CONTACTS, status,
                Collections.singletonList(request.getSessions().get(0).getSessionId()));
        return response;
    }

    public void establishedContacts() {
        cancelControlCallbacks();
        pendingContacts = -1;
        controlGeneration++;
        scanGeneration++;
        controlReady = false;
        readyControlGeneration = -1;
        contactsRx.reset(); contactsTx = 1;
        final byte[] appKey = secrets.deriveApplicationKey(contactsComponent, remoteContacts);
        try { keyCipher = new SapHostManagerAppCipher(appKey); }
        finally { Arrays.fill(appKey, (byte) 0); }
        controlState = ControlState.AWAITING_HANDSHAKE;
        armControlWatchdog(contactsSession, CONTROL_HANDSHAKE_TIMEOUT_MS);
        sendKey();
        LOG.info("Gear S3 contacts SAP established: session={} key queued securely; receipt unproven", contactsSession);
        debugRequests.wake();
        LOG.info("Gear S3 contacts control generation={} started: session={}; awaiting watch Resume/Initial; key queued locally only; no new file offers", controlGeneration, contactsSession);
    }

    private void cancelControlCallbacks() {
        controlRecoveryEpoch++;
        if (controlWatchdog != null) handler.removeCallbacks(controlWatchdog);
        if (controlRetry != null) handler.removeCallbacks(controlRetry);
        if (controlEscalation != null) handler.removeCallbacks(controlEscalation);
        controlWatchdog = controlRetry = controlEscalation = null;
    }

    private boolean recoveryMatches(final long epoch, final long generation, final int session,
                                    final ControlState state) {
        if (disposed || epoch != controlRecoveryEpoch || generation != controlGeneration || controlState != state
                || (state == ControlState.CONNECTING ? pendingContacts : contactsSession) != session) return false;
        if (!emitter.isConnected()) {
            cancelControlCallbacks();
            controlReady = false; readyControlGeneration = -1;
            controlState = ControlState.DISCONNECTED;
            return false;
        }
        return true;
    }

    private void armControlWatchdog(final int session, final long timeout) {
        cancelControlCallbacks();
        final long epoch = controlRecoveryEpoch, generation = controlGeneration;
        final ControlState state = controlState;
        controlWatchdog = () -> {
            if (!recoveryMatches(epoch, generation, session, state)) return;
            if (state == ControlState.RETIRING) finishControlRetirement();
            else controlStalled("current-session Resume/Initial timed out");
        };
        handler.postDelayed(controlWatchdog, timeout);
        LOG.info("Gear S3 contacts handshake recovery armed: generation={} session={} state={} timeoutMs={}; pending reconciliation retained; no readiness granted",
                generation, session, state, timeout);
    }

    private void controlHandshakeReceived(final String boundary) {
        cancelControlCallbacks();
        controlState = ControlState.READY;
        controlRetries = 0; controlReconnectPending = false;
        synchronized (CONTROL_RECONNECT_USED) { CONTROL_RECONNECT_USED.remove(deviceAddress); }
        LOG.info("Gear S3 contacts handshake recovery canceled: {}; generation={}; pending reconciliation released to current-state scan", boundary, controlGeneration);
    }

    private void controlStalled(final String reason) {
        cancelControlCallbacks();
        scanAgain = true;
        scanGeneration++;
        controlReady = false; readyControlGeneration = -1;
        LOG.warn("Gear S3 contacts control generation={} handshake failed/stalled: {}; retryCount={}; pending reconciliation retained; active FT retained",
                controlGeneration, reason, controlRetries);
        if (contactsSession < 1) {
            if (pendingContacts >= 1) retiredContactsSessions.add(pendingContacts);
            pendingContacts = -1;
            controlState = ControlState.DISCONNECTED;
            scheduleControlRetry();
            return;
        }
        // Retire only this control socket. FT setup/payload/boundary callbacks keep ownership.
        retiredContactsSessions.add(contactsSession);
        controlState = ControlState.RETIRING;
        armControlWatchdog(contactsSession, CONTROL_CLOSE_TIMEOUT_MS);
        final SapServiceConnectionTermination termination = new SapServiceConnectionTermination(remoteContacts, contactsComponent, CONTACTS);
        try { emitter.send(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec.encode(termination))); }
        catch (final RuntimeException e) { finishControlRetirement(); }
    }

    private void finishControlRetirement() {
        cancelControlCallbacks();
        contactsSession = -1; contactsRx.reset();
        if (keyCipher != null) { keyCipher.clear(); keyCipher = null; }
        controlState = ControlState.DISCONNECTED;
        scheduleControlRetry();
    }

    private void scheduleControlRetry() {
        if (disposed || controlRetry != null) return;
        if (controlRetries >= MAX_CONTROL_RETRIES) {
            controlState = ControlState.EXHAUSTED;
            controlReconnectPending = true;
            maybeReconnectControl();
            return;
        }
        final long epoch = controlRecoveryEpoch, generation = controlGeneration;
        final int session = contactsSession;
        controlRetry = () -> {
            if (!recoveryMatches(epoch, generation, session, ControlState.DISCONNECTED)) return;
            controlRetry = null;
            controlRetries++;
            LOG.info("Gear S3 contacts control retry: retired generation={} -> next control attempt={}; pending reconciliation retained", generation, controlRetries);
            final SapServiceConnectionRequest request = startContacts(usedSessions());
            if (request != null) {
                try { emitter.send(serviceRequest(request)); }
                catch (final RuntimeException e) { controlStalled("connection request enqueue failed"); }
            } else {
                controlStalled("Contacts descriptor unavailable");
            }
        };
        handler.postDelayed(controlRetry, CONTROL_RETRY_MS);
    }

    private boolean retainedContactsTransfer() {
        return sending != null || pendingDelta != null || !files.isEmpty()
                || pendingFtCommand >= 1 || replaceBoundary != ReplaceBoundary.NONE || scanInProgress
                || (ftRecoveryPending && ftState != FtState.EXHAUSTED && ftState != FtState.DISCONNECTED);
    }

    private void maybeReconnectControl() {
        if (disposed || !controlReconnectPending || controlEscalation != null || !emitter.isConnected()) return;
        // Never sacrifice an independently active transaction or a retained D/I continuation.
        // Its existing FT deadlines still apply; completion/failure rechecks this fallback.
        if (retainedContactsTransfer()) {
            LOG.info("Gear S3 contacts bounded control recovery exhausted; RFCOMM fallback deferred while Contacts FT operation retained");
            return;
        }
        cancelControlCallbacks();
        final long epoch = controlRecoveryEpoch, generation = controlGeneration;
        final int session = contactsSession;
        // Hand off after the current FT/control callback finishes mutating state.
        // Disposal must not run reentrantly inside onTermination/onFtCommand.
        controlEscalation = () -> {
            if (!recoveryMatches(epoch, generation, session, ControlState.EXHAUSTED)) return;
            controlEscalation = null;
            if (retainedContactsTransfer()) return;
            synchronized (CONTROL_RECONNECT_USED) {
                if (!CONTROL_RECONNECT_USED.add(deviceAddress)) {
                    controlReconnectPending = false;
                    LOG.warn("Gear S3 contacts bounded control recovery exhausted; automatic RFCOMM fallback already used; pending reconciliation retained; no retry loop");
                    return;
                }
            }
            controlReconnectPending = false;
            LOG.warn("Gear S3 contacts automatic RFCOMM recovery requested: five control retries exhausted without Resume/Initial; no active Contacts FT; pending phone changes reconcile after real reconnect handshake");
            emitter.reconnect(this);
        };
        handler.post(controlEscalation);
    }

    private void sendKey() {
        if (keyCipher == null || contactsSession < 1) return;
        final byte[] clear = new byte[37];
        System.arraycopy("key :".getBytes(StandardCharsets.UTF_8), 0, clear, 0, 5);
        System.arraycopy(contactKey, 0, clear, 5, 32);
        final byte[] protectedKey = keyCipher.protect(clear);
        Arrays.fill(clear, (byte) 0);
        final byte[] frame = new byte[3 + protectedKey.length];
        frame[0] = (byte) (contactsTx >>> 8); frame[1] = (byte) contactsTx; frame[2] = 4;
        System.arraycopy(protectedKey, 0, frame, 3, protectedKey.length);
        emit(contactsSession, 0, frame);
        contactsTx = next(contactsTx);
    }

    public boolean acceptResponse(final SapServiceConnectionResponse response) {
        if (CONTACTS.equals(response.getProfile()) && supersededContacts >= 1
                && response.getSessionIds().contains(supersededContacts)) {
            supersededContacts = -1;
            LOG.debug("Gear S3 contacts ignored superseded phone-initiated SAP response");
            return true;
        }
        if (CONTACTS.equals(response.getProfile()) && pendingContacts >= 1) {
            if (!response.getSessionIds().contains(pendingContacts)) return false;
            if (response.getAcceptorSlot() != remoteContacts || response.getInitiatorSlot() != contactsComponent
                    || response.getSessionIds().size() != 1 || response.getSessionIds().get(0) != pendingContacts || response.getStatus() != 0) {
                LOG.warn("Gear S3 contacts SAP connection rejected or malformed");
                controlStalled("connection rejected or malformed"); return true;
            }
            contactsSession = pendingContacts; establishedContacts(); return true;
        }
        if (FILE_TRANSFER.equals(response.getProfile()) && pendingFtCommand >= 1) {
            if (!response.getSessionIds().contains(pendingFtCommand) && !response.getSessionIds().contains(pendingFtData)) {
                LOG.info("Gear S3 contacts stale FT connection callback ignored: generation={}", ftGeneration);
                return false;
            }
            if (response.getAcceptorSlot() != remoteFt || response.getInitiatorSlot() != ftComponent
                    || response.getSessionIds().size() != 2 || !response.getSessionIds().contains(pendingFtData)
                    || !response.getSessionIds().contains(pendingFtCommand) || response.getStatus() != 0) {
                pendingFtData = pendingFtCommand = -1;
                LOG.warn("Gear S3 contacts file-transfer SAP connection rejected or malformed");
                if (ftRecoveryPending) exhaustFtRecovery("fresh FT SAP rejected or malformed");
                else if (trackedTransfer()) stopReplacement("file-transfer SAP rejected or malformed");
                return true;
            }
            ftDataSession = pendingFtData; ftCommandSession = pendingFtCommand;
            ftState = FtState.READY;
            final boolean afterStandaloneDelete = ftNeedsRefreshAfterDelete;
            ftNeedsRefreshAfterDelete = false;
            pendingFtData = pendingFtCommand = -1;
            if (trackedTransfer()) cancelBoundaryTimeout();
            LOG.info("Gear S3 contacts file-transfer SAP established: commandSession={} dataSession={}", ftCommandSession, ftDataSession);
            if (replaceBoundary == ReplaceBoundary.OPENING_FT) {
                cancelBoundaryTimeout();
                replaceBoundary = ReplaceBoundary.NONE;
                if (afterStandaloneDelete)
                    LOG.info("Gear S3 contacts DELETE starting next file after matching FT termination and fresh SAP establishment; importer result unknown");
                else if (!files.isEmpty() && files.peek().replacementDelete) {
                    LOG.info("Gear S3 contacts REPLACE starting next D after FT termination response and fresh SAP establishment; previous importer result unknown");
                } else LOG.info("Gear S3 contacts REPLACE starting I after FT termination response and fresh SAP establishment; D importer result unknown");
            }
            if (ftRecoveryPending) { cancelFtRecoveryTimeout(); releaseFtRecovery(); }
            else sendNextFile();
            return true;
        }
        return false;
    }

    /** Production and debug replacement share the close/reopen boundary. */
    public boolean onTerminationResponse(final SapServiceConnectionTerminationResponse response) {
        if (controlState == ControlState.RETIRING && CONTACTS.equals(response.getProfile())
                && response.getInitiatorComponentId() == remoteContacts
                && response.getAcceptorComponentId() == contactsComponent) {
            LOG.info("Gear S3 contacts control retirement response: generation={} status={}; retrying Contacts only; FT retained", controlGeneration, response.getStatus());
            finishControlRetirement(); // No file-transfer state is retired here.
            return true;
        }
        if (ftRecoveryPending && ftState == FtState.RETIRING && FILE_TRANSFER.equals(response.getProfile())
                && response.getAcceptorComponentId() == ftComponent && response.getInitiatorComponentId() == remoteFt) {
            LOG.info("Gear S3 contacts stale FT retirement response: generation={} status={}; transport boundary only", ftGeneration, response.getStatus());
            finishFtRetirement();
            return true;
        }
        if (replaceBoundary != ReplaceBoundary.CLOSING_FT || !FILE_TRANSFER.equals(response.getProfile())) return false;
        if (response.getAcceptorComponentId() != ftComponent || response.getInitiatorComponentId() != remoteFt) {
            LOG.warn("Gear S3 contacts REPLACE ignored unrelated FT termination response; still waiting");
            return false;
        }
        cancelBoundaryTimeout();
        if (response.getStatus() != SapServiceConnectionTerminationResponse.STATUS_SUCCESS) {
            stopReplacement("file-transfer termination rejected");
            return true;
        }
        final Set<Integer> used = usedSessions(); // Reserve retired IDs to avoid stale-frame aliasing.
        retireFtSessions();
        ftCommandRx.reset(); ftDataRx.reset();
        ftCommandTx = ftDataTx = 1; lastDataAck = -1;
        replaceBoundary = ReplaceBoundary.OPENING_FT;
        if (ftNeedsRefreshAfterDelete) LOG.info("Gear S3 contacts DELETE FT termination accepted; opening fresh FT SAP before next file; transport boundary only");
        else LOG.info("Gear S3 contacts REPLACE FT termination accepted; opening fresh FT SAP before I; transport boundary only");
        final SapServiceConnectionRequest request = startFileTransfer(used);
        if (request == null) {
            stopReplacement("fresh file-transfer descriptor unavailable");
            return true;
        }
        armBoundaryTimeout();
        try { emitter.send(serviceRequest(request)); }
        catch (final RuntimeException e) { stopReplacement("fresh file-transfer request failed"); }
        return true;
    }

    private void closeReplacementTransfer(final boolean deleted) {
        replaceBoundary = ReplaceBoundary.CLOSING_FT;
        if (ftNeedsRefreshAfterDelete) LOG.info("Gear S3 contacts DELETE transport completed; waiting for matching FT termination before next file; importer result unknown");
        else if (deleted) LOG.info("Gear S3 contacts REPLACE D transport completed; waiting before I because Samsung FT close-path advances queue after service disconnect; importer result unknown");
        else LOG.info("Gear S3 contacts REPLACE I transport completed; waiting for fresh FT before next D; importer result unknown");
        armBoundaryTimeout();
        final SapServiceConnectionTermination termination = new SapServiceConnectionTermination(remoteFt, ftComponent, FILE_TRANSFER);
        try { emitter.send(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec.encode(termination))); }
        catch (final RuntimeException e) { stopReplacement("file-transfer termination request failed"); }
    }

    private void stopReplacement(final String reason) {
        if (!trackedTransfer() && replaceBoundary == ReplaceBoundary.NONE) return;
        cancelBoundaryTimeout();
        cancelTransferTimeout();
        cancelFtRecoveryTimeout();
        ftRecoveryPending = ftRecoveryContinuation = ftRecoveryFromResume = false;
        replaceBoundary = ReplaceBoundary.NONE;
        final boolean debug = pendingDebugOneShot;
        final boolean remotePossible = !remoteAttemptIds.isEmpty();
        clearFiles(); pendingDelta = null;
        pendingTransportSnapshot = null; pendingPersistentTest = false;
        pendingInitial = pendingDebugOneShot = pendingReplacement = pendingFromResume = false;
        pendingFtCommand = pendingFtData = -1;
        scanAgain = false;
        if (debug) logDebugReturnToPaused("REPLACE stopped: " + reason + "; no automatic replay");
        else if (remotePossible)
            LOG.warn("Gear S3 contacts production transfer stopped: {}; payload may have started; affected incomplete raw IDs remain blocked; last transport-delivered fingerprints retained; semantic result unknown; no automatic replay for those IDs", reason);
        else LOG.info("Gear S3 contacts production transfer stopped before payload: {}; no new durable incomplete block; prior transport fingerprints retained; automatic retry eligible on next Resume", reason);
        debugRequests.wake();
        maybeReconnectControl();
    }

    public boolean onTermination(final SapServiceConnectionTermination termination) {
        final boolean contacts = CONTACTS.equals(termination.getProfile()) && contactsSession >= 1
                && ((termination.getAcceptorComponentId() == contactsComponent && termination.getInitiatorComponentId() == remoteContacts)
                || (termination.getAcceptorComponentId() == remoteContacts && termination.getInitiatorComponentId() == contactsComponent));
        final boolean transfer = FILE_TRANSFER.equals(termination.getProfile()) && ftCommandSession >= 1
                && ((termination.getAcceptorComponentId() == ftComponent && termination.getInitiatorComponentId() == remoteFt)
                || (termination.getAcceptorComponentId() == remoteFt && termination.getInitiatorComponentId() == ftComponent));
        if (!contacts && !transfer) return false;
        if (contacts) {
            cancelControlCallbacks();
            controlState = ControlState.DISCONNECTED;
            // Keep the SDK transfer alive and track its completion separately.
            // Control loss must not retire FT sessions,
            // payload-stage protection, or the intentional D -> close -> fresh I boundary.
            scanGeneration++;
            resumeScanPending |= pendingFromResume;
            if (scanInProgress) { scanAgain = true; resumeScanPending |= controlReady; }
            retiredContactsSessions.add(contactsSession);
            contactsSession = -1; controlReady = false; readyControlGeneration = -1;
            contactsRx.reset();
            if (keyCipher != null) { keyCipher.clear(); keyCipher = null; }
            LOG.info("Gear S3 contacts control SAP terminated; independent file-transfer sessions and active operation retained; subsequent offers wait for current-generation Resume/Initial");
            debugRequests.wake();
            if (scanAgain || pendingDelta != null || !files.isEmpty() || debugRequests.hasPending())
                scheduleControlRetry();
            return true;
        }
        if (ftRecoveryPending && ftState == FtState.RETIRING) {
            finishFtRetirement(); return true;
        }
        if (canRecoverSetup()) {
            recoverFtSetup("FT SAP terminated before payload", true); return true;
        }
        if (ftRecoveryPending) {
            exhaustFtRecovery("fresh FT SAP lost before retry offer"); return true;
        }
        if (trackedTransfer()) stopReplacement("file-transfer SAP terminated");
        cancelBoundaryTimeout();
        replaceBoundary = ReplaceBoundary.NONE;
        debugRequests.detach(this);
        retireFtSessions();
        ftCommandRx.reset(); ftDataRx.reset();
        if (pendingDelta != null || !files.isEmpty()) {
            clearFiles(); pendingDelta = null; pendingInitial = false;
            LOG.warn("Gear S3 contacts transfer interrupted; previous transport baseline retained");
        }
        if (pendingDebugOneShot || debugOneShot.isArmed()) {
            pendingDebugOneShot = false; debugOneShot.cancel();
            debugMode = activeDebugMode = Mode.NONE;
            seededHash = null;
            logDebugReturnToPaused("SAP terminated");
        }
        debugRequests.attach(this);
        LOG.info("Gear S3 contacts SAP termination accepted: profile={}", termination.getProfile());
        return true;
    }

    public void onRecord(final SapPostAuthRecord record) {
        final int session = record.getHeader().getSessionId();
        if (!hasSession(session)) return; // Late retired-control frames cannot authorize a new generation.
        if (record.getHeader().getFrameType() == 1) {
            final byte[] body = record.getBody();
            if (body.length == 3 && body[0] == 1 && session == ftDataSession && sending != null) {
                final int ack = ((body[1] & 255) << 8) | (body[2] & 255);
                if (ack == lastDataAck) {
                    lastDataAck = -1;
                    sendNextChunk();
                }
            }
            return;
        }
        record.getHeader().requireUnfragmentedDataFrame();
        final byte[] body = record.getBody();
        final int sequence;
        try { sequence = GearContactsCodec.sequence(body); }
        catch (final IllegalArgumentException e) { LOG.warn("Gear S3 contacts parsing failure: SDK frame"); return; }
        final SapReliableSession rx = session == contactsSession ? contactsRx : session == ftCommandSession ? ftCommandRx : ftDataRx;
        final SapReliableSession.Inbound disposition = rx.receive(sequence);
        if (disposition != SapReliableSession.Inbound.ACCEPT) {
            if (disposition == SapReliableSession.Inbound.DUPLICATE) sendAck(session, rx.getLastValidSequence());
            else LOG.warn("Gear S3 contacts parsing failure: reliable sequence session={}", session);
            return;
        }
        sendAck(session, sequence);
        if (session == ftDataSession) return;
        final byte[] payload = GearContactsCodec.payload(body);
        if (session == contactsSession) onContactMessage(payload);
        else onFtCommand(payload, session);
    }

    private void onContactMessage(final byte[] payload) {
        if (disposed || controlState == ControlState.RETIRING) return;
        final String message = new String(payload, StandardCharsets.UTF_8);
        try {
            if (message.startsWith("{")) {
                final JSONObject request = new JSONObject(message);
                if ("contacts-getfeature-req".equals(request.optString("msgId"))) {
                    sendContact(GearContactsCodec.featureResponse(request).toString().getBytes(StandardCharsets.UTF_8));
                } else if ("logs-activatesync-req".equals(request.optString("msgId"))) {
                    sendContact(GearContactsCodec.logActivationResponse(request).toString().getBytes(StandardCharsets.UTF_8));
                    logSyncRequested = request.getBoolean("value");
                    LOG.info("Gear S3 contacts log-activation control acknowledged: requested={}; call-log data sync is not implemented", logSyncRequested);
                } else LOG.info("Gear S3 contacts unsupported control request");
            } else if (GearContactsCodec.control(message) == GearContactsCodec.Control.INITIAL_SYNC) {
                controlHandshakeReceived("Initial");
                readyControlGeneration = -1; resumeScanPending = false;
                if (pendingReplacement && !pendingDebugOneShot) {
                    controlReady = false;
                    stopReplacement("Gear requested Initial during replacement");
                    return; // Do not clear the uncertainty marker or initiate a reimport.
                }
                if (snapshot.persistentTestRawId() > 0 || snapshot.persistentDeleteTestRawId() > 0) {
                    controlReady = false;
                    LOG.warn("Gear S3 contacts Initial held: persistent Contacts test scope active; legacy baseline unchanged; no full import");
                    return;
                }
                if (snapshot.hasBlockedTransfers()) {
                    controlReady = false;
                    LOG.warn("Gear S3 contacts Initial held: incomplete per-ID transfers require recovery; no full import");
                    return;
                }
                if (debugRequests.hasPending() || debugRequests.isActive() || debugRequests.hasProductionTest() || pendingReplacement) {
                    controlReady = false;
                    LOG.warn("Gear S3 contacts Initial held: debug request or replacement active; retained baseline unchanged");
                    return;
                }
                controlReady = false;
                readyControlGeneration = -1; resumeScanPending = false;
                debugRequests.invalidateSeed();
                if (!snapshot.requireInitial()) {
                    LOG.warn("Gear S3 contacts initial sync blocked: could not persist reset marker");
                    return;
                }
                scanGeneration++;
                handler.removeCallbacks(observerScan);
                initialPending = false;
                clearFiles(); pendingDelta = null; pendingInitial = false; pendingVerificationLogged = false;
                if (pendingDebugOneShot || debugOneShot.isArmed()) {
                    pendingDebugOneShot = false; debugOneShot.cancel();
                    debugMode = activeDebugMode = Mode.NONE;
                    LOG.info("Gear S3 contacts debug one-shot cancelled by explicit Gear initial sync");
                }
                seededHash = null;
                if (!hasPermission()) { LOG.warn("Gear S3 contacts permission missing; initial sync not started"); return; }
                initialPending = true;
                readyControlGeneration = controlGeneration;
                sendContact("Response_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8));
                LOG.info("Gear S3 contacts initial sync requested");
                scheduleScan(true);
            } else if (GearContactsCodec.control(message) == GearContactsCodec.Control.RESUME_SYNC) {
                controlHandshakeReceived("Resume");
                if (!hasPermission()) { LOG.warn("Gear S3 contacts permission missing; resume sync not started"); return; }
                if (!snapshot.isInitialized()) {
                    sendContact(GearContactsCodec.resumeResponse(false).getBytes(StandardCharsets.UTF_8));
                    LOG.info("Gear S3 contacts resume requires initial sync");
                } else {
                    sendContact(GearContactsCodec.resumeResponse(true).getBytes(StandardCharsets.UTF_8));
                    LOG.info("Gear S3 contacts resume accepted: {} transport-baseline records", snapshot.size());
                    controlReady = true;
                    readyControlGeneration = controlGeneration;
                    LOG.info("Gear S3 contacts control generation={} ready after current-session Resume; new file offers permitted; importer result unknown", controlGeneration);
                    if (ftRecoveryPending && ftState == FtState.EXHAUSTED) {
                        ftRecoveryRetries = 0; ftState = FtState.DISCONNECTED;
                    }
                    releaseFtRecovery();
                    sendNextFile(); // Continue a retained pair/queue once, without replaying its active file.
                    debugRequests.wake();
                    if (!debugRequests.hasPending()) scheduleScan(false, true);
                }
            } else if (GearContactsCodec.control(message) == GearContactsCodec.Control.ENCRYPTION_KEY) {
                sendKey();
            } else if (GearContactsCodec.control(message) == GearContactsCodec.Control.ALL_LOG_IDS) {
                LOG.info("Gear S3 contacts call-log ID request not answered: activationRequested={} call-log sync is outside this feature", logSyncRequested);
            } else LOG.info("Gear S3 contacts unsupported control request");
        } catch (final JSONException e) { LOG.warn("Gear S3 contacts parsing failure: JSON control"); }
    }

    private void sendContact(final byte[] payload) { emit(contactsSession, 0, GearContactsCodec.frame(contactsTx, payload)); contactsTx = next(contactsTx); }
    private boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private void scheduleScan(final boolean initial) {
        scheduleScan(initial, false);
    }

    private void scheduleScan(final boolean initial, final boolean fromResume) {
        if (disposed) return;
        if (ftRecoveryPending) {
            scanAgain = true; resumeScanPending |= fromResume;
            releaseFtRecovery(); return;
        }
        final boolean reconcileDeletes = !initial && (fromResume || resumeScanPending);
        if (scanInProgress || sending != null || !files.isEmpty()) {
            initialPending |= initial;
            scanAgain = true;
            resumeScanPending |= reconcileDeletes;
            return;
        }
        final boolean persistentDeletion = snapshot.persistentDeleteTestRawId() > 0;
        final long persistentRawId = persistentDeletion ? snapshot.persistentDeleteTestRawId() : snapshot.persistentTestRawId();
        if (persistentRawId > 0 && (!reconcileDeletes || initial || debugOneShot.isArmed())) return;
        final GearContactsDebugRequests.ProductionTestBaseline testBaseline = reconcileDeletes && !initial
                && !debugOneShot.isArmed() ? debugRequests.productionTestBaseline() : null;
        if (debugRequests.hasProductionTest() && testBaseline == null) return; // PREPARE/observer never authorizes transmission.
        if (testBaseline != null && (!snapshot.isInitialized() || !snapshot.hasPendingVerification())) {
            LOG.warn("Gear S3 contacts production-test rejected at Resume: retained baseline must remain usable and paused");
            debugRequests.clearProductionTest(testBaseline.rawId);
            return;
        }
        if (snapshot.hasInvalidLedger() && !debugOneShot.isArmed()) {
            LOG.warn("Gear S3 contacts delta blocked: invalid transport ledger; incomplete per-ID state unavailable; no automatic replay");
            return;
        }
        if (snapshot.hasPendingVerification() && !pendingVerificationLogged) {
            LOG.info("Gear S3 contacts legacy uncertainty retained; automatic per-ID convergence enabled; semantic result unknown");
            pendingVerificationLogged = true;
        }
        if (contactsSession < 1) {
            scanAgain = true;
            resumeScanPending |= reconcileDeletes;
            if (controlState == ControlState.EXHAUSTED) { maybeReconnectControl(); return; }
            if (controlRetry != null || controlState == ControlState.RETIRING) return;
            if (snapshot.isInitialized()) {
                final SapServiceConnectionRequest reconnect = startContacts(usedSessions());
                if (reconnect != null) {
                    emitter.send(serviceRequest(reconnect));
                    LOG.info("Gear S3 contacts change: reconnect requested; awaiting watch sync control");
                }
            }
            return;
        }
        if (!newFileReady()) {
            scanAgain = true;
            resumeScanPending |= reconcileDeletes;
            return; // A previous generation's Resume and local key enqueue are insufficient.
        }
        if (!controlReady && !initialPending && !initial) return;
        if (!snapshot.isInitialized() && !initialPending && !initial) return;
        initialPending |= initial;
        if (!watching) {
            try {
                context.getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer);
                context.getContentResolver().registerContentObserver(ContactsContract.RawContacts.CONTENT_URI, true, observer);
                context.getContentResolver().registerContentObserver(ContactsContract.Data.CONTENT_URI, true, observer);
            } catch (final SecurityException e) {
                context.getContentResolver().unregisterContentObserver(observer);
                LOG.warn("Gear S3 contacts permission missing; observer not registered");
                if (debugOneShot.isArmed()) { debugOneShot.cancel(); logDebugReturnToPaused("observer unavailable"); }
                return;
            }
            watching = true;
        }
        final boolean debugAuthorized = debugOneShot.consume();
        final Mode selectedDebugMode = debugAuthorized ? debugMode : Mode.NONE;
        final long selectedRawId = debugRawId;
        if (debugAuthorized) { debugMode = Mode.NONE; debugRawId = -1; }
        if (debugAuthorized) LOG.warn("Gear S3 contacts debug one-shot consumed: starting selected scan");
        scanInProgress = true;
        resumeScanPending = false;
        final boolean reset = !debugAuthorized && initialPending;
        final int generation = scanGeneration;
        final int observedRevision = sourceRevision;
        loader.execute(() -> {
            List<GearContactsCodec.Contact> contacts = null;
            GearContactsSnapshot.Delta delta = null;
            String failure = null;
            try {
                if (testBaseline == null && persistentRawId == 0) contacts = GearContactsSource.read(context);
                else {
                    final GearContactsCodec.Contact current = GearContactsSource.readRaw(context, persistentRawId > 0 ? persistentRawId : testBaseline.rawId);
                    contacts = current == null ? Collections.emptyList() : Collections.singletonList(current);
                }
                delta = persistentRawId > 0 ? snapshot.comparePersistentTest(contacts, persistentRawId)
                        : (testBaseline == null ? snapshot : testBaseline.snapshot).compare(contacts, reset);
            }
            catch (final SecurityException e) { failure = "permission missing"; }
            catch (final Exception e) { failure = "Android contact query or encoding failed"; }
            final boolean scopedRowPresent = contacts != null && !contacts.isEmpty();
            final int sourceCount = contacts == null ? 0 : contacts.size();
            final GearContactsSnapshot.Delta result = delta;
            final String error = failure;
            handler.post(() -> {
                scanInProgress = false;
                debugRequests.wake();
                if (disposed || contactsSession < 1 || !newFileReady()) {
                    if (debugAuthorized) debugRequests.retryBeforeTransmission(this);
                    else if (!disposed) { scanAgain = true; resumeScanPending |= reconcileDeletes; }
                    return;
                }
                if (testBaseline != null && !debugRequests.isCurrentProductionTest(testBaseline)) {
                    scanAgain = false;
                    LOG.info("Gear S3 contacts production-test scan discarded: override invalidated; no files sent");
                    return;
                }
                if (generation != scanGeneration) {
                    scanAgain = false;
                    if (debugAuthorized) debugRequests.retryBeforeTransmission(this);
                    if (initialPending) scheduleScan(true);
                    else if (!debugAuthorized) scheduleScan(false, reconcileDeletes);
                    return;
                }
                if (observedRevision != sourceRevision) {
                    if (debugAuthorized) { debugRequests.retryBeforeTransmission(this); return; }
                    if (reset) scheduleScan(true);
                    else {
                        resumeScanPending |= reconcileDeletes;
                        handler.removeCallbacks(observerScan);
                        handler.postDelayed(observerScan, OBSERVER_DEBOUNCE_MS);
                    }
                    return;
                }
                if (error != null) {
                    LOG.warn("Gear S3 contacts sync stopped: {}", error);
                    if (debugAuthorized) logDebugReturnToPaused("scan failed");
                    return;
                }
                LOG.debug("Gear S3 contacts scan diff: sourceRecords={} insert={} modified={} delete={} initial={} scopedRawId={}",
                        sourceCount, result.insert.size(), result.update.size(), result.delete.size(), reset,
                        persistentRawId > 0 ? persistentRawId : (testBaseline == null ? 0 : testBaseline.rawId));
                if (persistentRawId > 0) {
                    if (snapshot.isBlocked(persistentRawId)) {
                        LOG.debug("Gear S3 contacts persistent transport ledger blocked: rawId={}; incomplete attempt retained; no files sent; no automatic replay", persistentRawId);
                        scanAgain = false; return;
                    }
                    if (!persistentDeletion && (!result.delete.isEmpty() || !result.insert.isEmpty())) {
                        LOG.debug("Gear S3 contacts persistent-test stopped: rawId={}; target absent or ledger unavailable; no files sent", persistentRawId);
                        scanAgain = false; return;
                    }
                    if (persistentDeletion && (!result.update.isEmpty()
                            || (!result.insert.isEmpty() && !snapshot.isTransportDeleted(persistentRawId)))) {
                        LOG.debug("Gear S3 contacts persistent deletion test held: rawId={}; target changed without delivered deletion; no files sent", persistentRawId);
                        scanAgain = false; return;
                    }
                    if (result.isEmpty()) {
                        if (persistentDeletion && !scopedRowPresent && snapshot.isTransportDeleted(persistentRawId))
                            LOG.debug("Gear S3 contacts persistent deletion unchanged: rawId={} still absent; no files sent", persistentRawId);
                        else if (persistentDeletion)
                            LOG.debug("Gear S3 contacts persistent deletion baseline unchanged: rawId={}; no files sent", persistentRawId);
                        else LOG.debug("Gear S3 contacts persistent transport ledger unchanged: rawId={}; no files sent", persistentRawId);
                        scanAgain = false; return;
                    }
                }
                if (testBaseline != null && !result.delete.isEmpty()) {
                    debugRequests.clearProductionTest(testBaseline.rawId);
                    LOG.debug("Gear S3 contacts production-test stopped: rawId={} absent or not sync-eligible; no deletion sent", testBaseline.rawId);
                    scanAgain = false;
                    return;
                }
                if (testBaseline != null && result.isEmpty()) {
                    LOG.debug("Gear S3 contacts production-test baseline unchanged: rawId={}; awaiting edit and next Resume; no files sent", testBaseline.rawId);
                    scanAgain = false;
                    return;
                }
                initialPending = false;
                boolean deferredChanges = false;
                if (debugAuthorized) {
                    final boolean selected;
                    if (selectedDebugMode == Mode.DELETES) selected = GearContactsOneShotGate.retainDeletes(result) > 0;
                    else if (selectedDebugMode == Mode.INSERT) selected = GearContactsOneShotGate.retainInsert(result, selectedRawId);
                    else if (selectedDebugMode == Mode.REPLACE) selected = GearContactsOneShotGate.retainReplacement(result, selectedRawId, seededHash);
                    else selected = false;
                    if (!selected) { logDebugReturnToPaused("selected raw contact unavailable"); return; }
                    if (selectedDebugMode != Mode.DELETES) LOG.debug("Gear S3 contacts debug one-shot selected raw-contact ID={}", selectedRawId);
                    LOG.warn("Gear S3 contacts debug one-shot selected: mode={} insert={} delete={}",
                            selectedDebugMode, result.insert.size(), result.delete.size());
                } else if (!reset) {
                    final GearContactsSnapshot selectedSnapshot = testBaseline == null ? snapshot : testBaseline.snapshot;
                    final Set<Long> candidateIds = new HashSet<>();
                    if (LOG.isDebugEnabled()) {
                        for (final GearContactsCodec.Contact current : result.insert) candidateIds.add(current.rawId);
                        for (final GearContactsCodec.Contact current : result.update) candidateIds.add(current.rawId);
                        candidateIds.addAll(result.delete);
                    }
                    selectedSnapshot.excludeBlocked(result);
                    if (testBaseline == null && persistentRawId == 0) debugRequests.excludeDebugAttempts(result);
                    if (LOG.isDebugEnabled()) {
                        for (final GearContactsCodec.Contact current : result.insert) candidateIds.remove(current.rawId);
                        for (final GearContactsCodec.Contact current : result.update) candidateIds.remove(current.rawId);
                        candidateIds.removeAll(result.delete);
                        for (final long id : candidateIds)
                            LOG.debug("Gear S3 contacts candidate excluded: rawId={}; reason={}", id,
                                    selectedSnapshot.isBlocked(id) ? "incomplete transport attempt" : "process-local debug attempt quarantine");
                    }
                    if (selectedSnapshot.hasBlockedTransfers())
                        LOG.warn("Gear S3 contacts incomplete per-ID attempts excluded: count={}; no automatic replay; other IDs remain eligible", selectedSnapshot.blockedTransferCount());
                    // Only real Resume may delete known IDs, including lazy legacy migration.
                    int deferredDeletes = selectedSnapshot.retainKnownDeletes(result, reconcileDeletes);
                    if (!result.delete.isEmpty() && (!result.insert.isEmpty() || !result.update.isEmpty())) {
                        deferredDeletes += result.delete.size(); result.delete.clear(); // No mixed genuine D/I transfer.
                    }
                    if (deferredDeletes > 0) {
                        LOG.info("Gear S3 contacts phone deletions deferred: count={}; awaiting later Resume or per-ID ineligible", deferredDeletes);
                        deferredChanges = true;
                    }
                    for (final long id : result.delete)
                        LOG.debug("Gear S3 contacts production DELETE selected: rawId={}; known baseline or transport ledger; semantic result unknown", id);
                    for (final GearContactsCodec.Contact current : result.insert)
                        if (selectedSnapshot.isTransportDeleted(current.rawId))
                            LOG.debug("Gear S3 contacts production INSERT selected: rawId={}; previously transport-deleted; current phone representation", current.rawId);
                        else LOG.debug("Gear S3 contacts production INSERT selected: rawId={}; new phone raw ID; semantic result unknown", current.rawId);
                    if (!result.insert.isEmpty() && !result.update.isEmpty()) {
                        // Complete/checkpoint new inserts first; re-scan the retained update hashes afterwards.
                        LOG.info("Gear S3 contacts MODIFY waiting for insert transport checkpoint: count={}", result.update.size());
                        result.update.clear();
                        scanAgain = true;
                        deferredChanges = true;
                    } else if (!result.update.isEmpty()) {
                        for (final GearContactsCodec.Contact current : result.update) {
                            // compare() classified this ID against the old transport baseline. D has only that ID.
                            result.delete.add(current.rawId);
                            result.insert.add(current);
                            LOG.debug("Gear S3 contacts production MODIFY selected: rawId={}", current.rawId);
                        }
                        result.update.clear();
                        LOG.info("Gear S3 contacts production MODIFY selected: replacements={}; same baseline raw IDs, current phone I; importer ACK unavailable", result.delete.size());
                    }
                }
                if (result.isEmpty()) {
                    if (reset) LOG.info("Gear S3 contacts empty initial scan: no file completion to establish transport baseline");
                    else if (!deferredChanges) LOG.info("Gear S3 contacts no eligible phone changes: {} records; blocked incomplete IDs excluded", snapshot.size());
                    if (scanAgain) { scanAgain = false; scheduleScan(false); }
                    return;
                }
                try { prepareFiles(result); }
                catch (final Exception e) {
                    clearFiles();
                    LOG.warn("Gear S3 contacts file preparation failed");
                    if (debugAuthorized) logDebugReturnToPaused("file preparation failed");
                    return;
                }
                if (testBaseline != null && !debugRequests.isCurrentProductionTest(testBaseline)) {
                    clearFiles(); scanAgain = false;
                    LOG.info("Gear S3 contacts production-test attempt discarded: override invalidated; no files sent");
                    return;
                }
                pendingDelta = result;
                pendingInitial = reset && !debugAuthorized;
                pendingFromResume = reconcileDeletes && !reset && !debugAuthorized;
                pendingDebugOneShot = debugAuthorized;
                pendingReplacement = !result.insert.isEmpty() && !result.delete.isEmpty();
                pendingPersistentTest = persistentRawId > 0;
                pendingTransportSnapshot = !reset && !debugAuthorized ? (testBaseline == null ? snapshot : testBaseline.snapshot) : null;
                activeDebugMode = selectedDebugMode;
                LOG.info("Gear S3 contacts delta prepared: insert={} update={} delete={} files={}",
                        result.insert.size(), result.update.size(), result.delete.size(), files.size());
                if (ftCommandSession >= 1) sendNextFile();
                else {
                    final Set<Integer> used = usedSessions();
                    final SapServiceConnectionRequest request = startFileTransfer(used);
                    if (request != null) {
                        if (trackedTransfer()) armBoundaryTimeout();
                        try { emitter.send(serviceRequest(request)); }
                        catch (final RuntimeException e) {
                            if (trackedTransfer()) stopReplacement("file-transfer connection request failed");
                            else throw e;
                        }
                    }
                    else {
                        LOG.warn("Gear S3 contacts file-transfer descriptor unavailable");
                        if (trackedTransfer()) stopReplacement("file-transfer descriptor unavailable");
                        else if (debugAuthorized) {
                            clearFiles(); pendingDelta = null; pendingDebugOneShot = false;
                            logDebugReturnToPaused("file-transfer descriptor unavailable");
                        }
                    }
                }
            });
        });
    }

    private void prepareFiles(final GearContactsSnapshot.Delta delta) throws Exception {
        boolean replacement = false;
        for (final GearContactsCodec.Contact contact : delta.insert) {
            if (delta.delete.contains(contact.rawId)) { replacement = true; break; }
        }
        if (replacement) {
            if (!delta.update.isEmpty() || delta.insert.size() != delta.delete.size()) {
                throw new IllegalArgumentException("Replacement must contain only matched D/I pairs");
            }
            final List<GearContactsCodec.Contact> current = new ArrayList<>(delta.insert);
            current.sort((a, b) -> Long.compare(a.rawId, b.rawId));
            for (final GearContactsCodec.Contact contact : current) {
                if (!delta.delete.contains(contact.rawId)) throw new IllegalArgumentException("Unmatched replacement ID");
                // One raw ID per pair. Never a multi-ID D then multi-ID I.
                queueFile("delete", Collections.emptyList(), Collections.singletonList(contact.rawId), true);
                queueFile("insert", Collections.singletonList(contact), Collections.emptyList(), false);
            }
            return;
        }
        final String[] order = new String[]{"insert", "update", "delete"};
        for (final String kind : order) {
            final int count = "insert".equals(kind) ? delta.insert.size() : "update".equals(kind) ? delta.update.size() : delta.delete.size();
            for (int offset = 0; offset < count; offset += 200) {
                final int end = Math.min(count, offset + 200);
                queueFile(kind,
                        "insert".equals(kind) ? delta.insert.subList(offset, end) : "update".equals(kind) ? delta.update.subList(offset, end) : Collections.emptyList(),
                        "delete".equals(kind) ? delta.delete.subList(offset, end) : Collections.emptyList(), false);
            }
        }
    }

    private void queueFile(final String kind, final List<GearContactsCodec.Contact> contacts,
                           final List<Long> ids, final boolean replacementDelete) throws Exception {
        final JSONObject json = GearContactsCodec.change(kind, contacts, ids);
        final Map<Long, String> sentHashes = new LinkedHashMap<>();
        final Set<Long> attemptIds = new HashSet<>(ids);
        for (final GearContactsCodec.Contact contact : contacts) {
            attemptIds.add(contact.rawId);
            if ("insert".equals(kind)) sentHashes.put(contact.rawId, GearContactsSnapshot.fingerprint(contact));
        }
        final String name = "SAContactJSONFile" + System.currentTimeMillis() + transferId++;
        final byte[] zip = GearContactsCodec.contactFile(json, contactKey, name);
        final File path = new File(context.getCacheDir(), name + ".zip");
        try (FileOutputStream output = new FileOutputStream(path)) { output.write(zip); }
        files.add(new PendingFile(path, zip, replacementDelete, "delete".equals(kind) && !replacementDelete, sentHashes, attemptIds));
    }

    private Set<Integer> usedSessions() { final Set<Integer> used = emitter.usedSessions(); addUsedSessions(used); return used; }
    private SapPostAuthRecord serviceRequest(final SapServiceConnectionRequest request) {
        if (request == null) throw new IllegalStateException("Gear contacts file transfer descriptor unavailable");
        return new SapPostAuthRecord(new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec.encode(request));
    }

    private boolean newFileReady() {
        return !disposed && contactsSession >= 1 && readyControlGeneration == controlGeneration;
    }

    private void sendNextFile() {
        if (ftRecoveryPending || replaceBoundary != ReplaceBoundary.NONE || sending != null || ftCommandSession < 1 || files.isEmpty()) return;
        if (!newFileReady()) {
            LOG.info("Gear S3 contacts next file waiting for current-generation Resume/Initial: generation={}; active FT retained; no payload replay", controlGeneration);
            return; // Only a real handshake releases an offer; watchdogs never grant readiness.
        }
        if (ftNeedsRefreshAfterDelete) {
            LOG.info("Gear S3 contacts DELETE transport boundary: closing previous FT before next file; importer result unknown");
            closeReplacementTransfer(true); // Reuse the matching close -> fresh SAP boundary.
            return;
        }
        sending = files.peek();
        sending.offerGeneration = ftGeneration;
        sending.offerSession = ftCommandSession;
        sending.offerTransaction = transferId++;
        ftState = FtState.SETUP_PENDING;
        if (pendingDebugOneShot) debugRequests.started(this, activeDebugMode, sending.attemptIds);
        if (trackedTransfer()) {
            cancelTransferTimeout();
            armTransferTimeout();
        }
        try {
            final JSONObject setup = new JSONObject().put("msgId", "filetransfer-setup-req")
                    .put("transId", sending.offerTransaction).put("fileName", sending.path.getName())
                    .put("filePath", sending.path.getAbsolutePath()).put("fileSize", sending.bytes.length)
                    .put("peerId", Integer.toString(remoteContacts)).put("containerId", Integer.toString(contactsComponent))
                    .put("accId", 0);
            sendFtCommand(setup);
            LOG.info("Gear S3 contacts file transfer setup: bytes={}", sending.bytes.length);
        } catch (final JSONException e) {
            LOG.warn("Gear S3 contacts file transfer setup encoding failed");
            if (trackedTransfer()) stopReplacement("file-transfer setup encoding failed");
        } catch (final RuntimeException e) {
            if (trackedTransfer()) stopReplacement("file-transfer setup send failed");
            else throw e;
        }
    }

    private void cancelBoundaryTimeout() {
        ftBoundaryEpoch++;
        if (replaceBoundaryTimeout != null) handler.removeCallbacks(replaceBoundaryTimeout);
        replaceBoundaryTimeout = null;
    }

    private void armBoundaryTimeout() {
        cancelBoundaryTimeout();
        final long epoch = ftBoundaryEpoch, generation = ftGeneration;
        final ReplaceBoundary boundary = replaceBoundary;
        final int session = pendingFtCommand >= 1 ? pendingFtCommand : ftCommandSession;
        replaceBoundaryTimeout = () -> {
            if (disposed || ftRecoveryPending || epoch != ftBoundaryEpoch || generation != ftGeneration
                    || boundary != replaceBoundary || session != (pendingFtCommand >= 1 ? pendingFtCommand : ftCommandSession)) return;
            stopReplacement("file-transfer boundary timed out");
        };
        handler.postDelayed(replaceBoundaryTimeout, REPLACE_FT_TIMEOUT_MS);
    }

    private void cancelTransferTimeout() {
        ftTimeoutEpoch++;
        if (replaceTransferTimeout != null) handler.removeCallbacks(replaceTransferTimeout);
        replaceTransferTimeout = null;
    }

    private void armTransferTimeout() {
        cancelTransferTimeout();
        final long epoch = ftTimeoutEpoch, generation = ftGeneration;
        final PendingFile offered = sending;
        final int session = ftCommandSession, transaction = offered.offerTransaction;
        replaceTransferTimeout = () -> {
            if (disposed || epoch != ftTimeoutEpoch || generation != ftGeneration || sending != offered
                    || session != ftCommandSession || transaction != offered.offerTransaction) return;
            if (!emitter.isConnected()) { dispose(); return; }
            if (canRecoverSetup()) recoverFtSetup("setup timeout before payload", false);
            else stopReplacement("file-transfer setup or payload timed out");
        };
        handler.postDelayed(replaceTransferTimeout, REPLACE_FT_TIMEOUT_MS);
    }

    private boolean canRecoverSetup() {
        return !disposed && !pendingInitial && !pendingDebugOneShot && pendingTransportSnapshot != null
                && sending != null && !sending.setupAccepted && sending.offset == 0 && !sending.attemptMarked;
    }

    private void retireFtSessions() {
        for (final int session : new int[]{ftCommandSession, ftDataSession, pendingFtCommand, pendingFtData})
            if (session >= 1) retiredFtSessions.add(session);
        ftCommandSession = ftDataSession = pendingFtCommand = pendingFtData = -1;
        ftCommandRx.reset(); ftDataRx.reset(); ftCommandTx = ftDataTx = 1; lastDataAck = -1;
        ftState = FtState.DISCONNECTED;
    }

    private void cancelFtRecoveryTimeout() {
        ftRecoveryEpoch++;
        if (ftRecoveryTimeout != null) handler.removeCallbacks(ftRecoveryTimeout);
        ftRecoveryTimeout = null;
    }

    private void armFtRecoveryTimeout(final FtState state) {
        cancelFtRecoveryTimeout();
        final long epoch = ftRecoveryEpoch, generation = ftGeneration;
        final int session = state == FtState.CONNECTING ? pendingFtCommand : ftCommandSession;
        ftRecoveryTimeout = () -> {
            if (disposed || epoch != ftRecoveryEpoch || generation != ftGeneration || ftState != state
                    || session != (state == FtState.CONNECTING ? pendingFtCommand : ftCommandSession)) return;
            if (!emitter.isConnected()) { dispose(); return; }
            if (state == FtState.RETIRING) finishFtRetirement();
            else exhaustFtRecovery("fresh FT connection timed out");
        };
        handler.postDelayed(ftRecoveryTimeout, REPLACE_FT_TIMEOUT_MS);
    }

    private void recoverFtSetup(final String reason, final boolean alreadyTerminated) {
        cancelTransferTimeout(); cancelBoundaryTimeout();
        ftRecoveryContinuation = pendingReplacement && !sending.replacementDelete;
        ftRecoveryFromResume |= pendingFromResume || resumeScanPending;
        ftRecoveryPending = true; scanAgain = true; scanGeneration++;
        LOG.info("Gear S3 contacts FT generation={} {}; payload not started; safe pre-payload reconciliation retained; replacementI={}",
                ftGeneration, reason, ftRecoveryContinuation);
        // A completed D must never be replayed. Keep the pending I/pairs and durable D marker.
        if (ftRecoveryContinuation) sending = null;
        else {
            clearFiles(); pendingDelta = null; pendingTransportSnapshot = null;
            pendingInitial = pendingReplacement = pendingPersistentTest = pendingFromResume = false;
        }
        if (ftRecoveryRetries >= MAX_FT_RECOVERY_RETRIES) { exhaustFtRecovery("one automatic FT retry exhausted"); return; }
        ftRecoveryRetries++;
        ftState = FtState.RETIRING;
        LOG.info("Gear S3 contacts retiring stale FT generation={}; control generation={} unchanged", ftGeneration, controlGeneration);
        if (alreadyTerminated) { finishFtRetirement(); return; }
        armFtRecoveryTimeout(FtState.RETIRING);
        final SapServiceConnectionTermination termination = new SapServiceConnectionTermination(remoteFt, ftComponent, FILE_TRANSFER);
        try { emitter.send(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec.encode(termination))); }
        catch (final RuntimeException e) { finishFtRetirement(); }
    }

    private void finishFtRetirement() {
        cancelFtRecoveryTimeout(); retireFtSessions(); replaceBoundary = ReplaceBoundary.NONE;
        releaseFtRecovery();
    }

    private void exhaustFtRecovery(final String reason) {
        cancelTransferTimeout(); cancelFtRecoveryTimeout(); retireFtSessions();
        replaceBoundary = ReplaceBoundary.NONE; ftState = FtState.EXHAUSTED;
        LOG.warn("Gear S3 contacts FT recovery bounded: {}; pending reconciliation/unsent I retained; no new incomplete marker; awaiting real Resume; no RFCOMM escalation", reason);
    }

    private void releaseFtRecovery() {
        if (!ftRecoveryPending || disposed || ftRefreshInProgress || ftState == FtState.RETIRING
                || ftState == FtState.CONNECTING || ftState == FtState.EXHAUSTED) return;
        if (!newFileReady()) {
            LOG.info("Gear S3 contacts safe FT retry waiting for current-generation Resume/Initial; reconciliation retained"); return;
        }
        if (ftCommandSession < 1) {
            final SapServiceConnectionRequest request = startFileTransfer(usedSessions());
            if (request == null) { exhaustFtRecovery("fresh FT descriptor unavailable"); return; }
            armFtRecoveryTimeout(FtState.CONNECTING);
            try { emitter.send(serviceRequest(request)); }
            catch (final RuntimeException e) { exhaustFtRecovery("fresh FT request enqueue failed"); }
            return;
        }
        LOG.info("Gear S3 contacts safe FT retry released: FT generation={} control generation={} READY; current phone state will be read", ftGeneration, controlGeneration);
        if (ftRecoveryContinuation) { refreshRecoveredInsertion(); return; }
        ftRecoveryPending = false;
        final boolean fromResume = ftRecoveryFromResume;
        ftRecoveryFromResume = false;
        scheduleScan(false, fromResume);
    }

    private void refreshRecoveredInsertion() {
        final PendingFile previous = files.peek();
        if (previous == null || previous.sentHashes.size() != 1) { exhaustFtRecovery("replacement continuation unavailable"); return; }
        final long rawId = previous.sentHashes.keySet().iterator().next();
        final long generation = ftGeneration, recovery = ftRecoveryEpoch, control = controlGeneration;
        final int revision = sourceRevision;
        ftRefreshInProgress = true;
        loader.execute(() -> {
            GearContactsCodec.Contact read = null;
            boolean failed = false;
            try { read = GearContactsSource.readRaw(context, rawId); }
            catch (final Exception e) { failed = true; }
            final GearContactsCodec.Contact current = read;
            final boolean failure = failed;
            handler.post(() -> {
                ftRefreshInProgress = false;
                if (disposed || !ftRecoveryPending || files.peek() != previous || generation != ftGeneration
                        || recovery != ftRecoveryEpoch) return;
                if (!newFileReady() || control != controlGeneration || revision != sourceRevision) { releaseFtRecovery(); return; }
                if (failure || current == null) {
                    exhaustFtRecovery("current replacement row unavailable; completed D marker and unsent I retained"); return;
                }
                try {
                    queueFile("insert", Collections.singletonList(current), Collections.emptyList(), false);
                    final PendingFile replacement = files.removeLast(); files.removeFirst(); files.addFirst(replacement);
                    if (!previous.path.delete()) LOG.debug("Gear S3 contacts cache cleanup deferred");
                    Arrays.fill(previous.bytes, (byte) 0);
                } catch (final Exception e) { exhaustFtRecovery("current replacement file preparation failed"); return; }
                ftRecoveryPending = ftRecoveryContinuation = false; ftRecoveryFromResume = false;
                sendNextFile();
            });
        });
    }

    private boolean trackedTransfer() { return pendingReplacement || pendingTransportSnapshot != null; }

    private boolean markPayloadAttempt(final PendingFile file) {
        if (pendingTransportSnapshot == null || file.attemptMarked || (pendingReplacement && !file.replacementDelete)) return true;
        final boolean started = pendingPersistentTest
                ? pendingTransportSnapshot.beginPersistentTestTransport(file.attemptIds)
                : pendingTransportSnapshot.beginTransport(file.attemptIds);
        if (!started) {
            LOG.warn("Gear S3 contacts per-ID attempt blocked: persistence failed or raw ID already blocked; no payload enqueued");
            return false;
        }
        file.attemptMarked = true;
        remoteAttemptIds.addAll(file.attemptIds);
        for (final long id : file.attemptIds)
            LOG.debug("Gear S3 contacts payload boundary armed: rawId={}; setup accepted; durable stage=PAYLOAD_MAY_HAVE_STARTED; before first payload enqueue; semantic result unknown", id);
        return true;
    }

    private void onFtCommand(final byte[] payload) { onFtCommand(payload, ftCommandSession); }

    private void onFtCommand(final byte[] payload, final int commandSession) {
        if (disposed || ftRecoveryPending || sending == null || commandSession != ftCommandSession
                || sending.offerSession != commandSession || sending.offerGeneration != ftGeneration) {
            LOG.info("Gear S3 contacts stale FT callback ignored: generation={}", ftGeneration);
            return;
        }
        final JSONObject command;
        try { command = new JSONObject(new String(payload, StandardCharsets.UTF_8)); }
        catch (final JSONException e) { LOG.warn("Gear S3 contacts parsing failure: file-transfer JSON"); return; }
        final String type = command.optString("msgId");
        // A control response carries fileName; a transaction ID is optional.
        // Validate a returned ID if present; never invent one in the peer's response.
        if (command.has("transId") && command.optInt("transId", -1) != sending.offerTransaction) return;
        if ("filetransfer-setup-rsp".equals(type)) {
            if (sending == null || !sending.path.getName().equals(command.optString("fileName"))) return;
            if (command.optInt("result", -1) != 0) {
                LOG.warn("Gear S3 contacts file-transfer setup rejected: reason={}", command.optInt("reason", -1));
                if (trackedTransfer()) stopReplacement("file-transfer setup rejected");
                else if (pendingDebugOneShot) {
                    clearFiles(); pendingDelta = null; pendingDebugOneShot = false;
                    logDebugReturnToPaused("file-transfer setup rejected");
                }
                return;
            }
            if (trackedTransfer() && sending.setupAccepted) return;
            sending.setupAccepted = true;
            ftState = FtState.PAYLOAD_MAY_HAVE_STARTED;
            sendNextChunk();
        } else if (trackedTransfer() && "filetransfer-cancel-req".equals(type)) {
            if (sending == null || !sending.path.getName().equals(command.optString("fileName"))) return;
            final int reason = command.optInt("reason", -1);
            try { sendFtCommand(new JSONObject().put("msgId", "filetransfer-cancel-rsp").put("result", 0)
                    .put("reason", reason == 5 ? 9 : reason).put("fileName", sending.path.getName())); }
            catch (final JSONException | RuntimeException e) { /* Stop regardless of response delivery. */ }
            stopReplacement("file-transfer cancelled by receiver");
        } else if ("filetransfer-receive-progress".equals(type)) {
            final long progress = command.optLong("progress", -1);
            LOG.debug("Gear S3 contacts file-transfer progress={} bytes", progress);
            if (trackedTransfer() && sending != null && sending.setupAccepted
                    && progress > sending.progress && progress <= sending.offset) {
                sending.progress = progress;
                cancelTransferTimeout();
                armTransferTimeout();
            }
        } else if ("filetransfer-complete-req".equals(type)) {
            if (sending == null || !sending.path.getName().equals(command.optString("fileName"))) return;
            if (sending.offset != sending.bytes.length) {
                LOG.warn("Gear S3 contacts file-transfer completion before all bytes sent");
                if (trackedTransfer()) stopReplacement("file-transfer completion before all bytes sent");
                return;
            }
            if (pendingTransportSnapshot != null && !sending.setupAccepted) {
                stopReplacement("file-transfer completion without accepted setup"); return;
            }
            try { sendFtCommand(new JSONObject().put("msgId", "filetransfer-complete-rsp")
                    .put("result", 0).put("reason", -1).put("fileName", sending.path.getName())); }
            catch (final JSONException e) {
                LOG.warn("Gear S3 contacts file-transfer completion encoding failed");
                if (trackedTransfer()) stopReplacement("file-transfer completion encoding failed");
                return;
            } catch (final RuntimeException e) {
                if (trackedTransfer()) { stopReplacement("file-transfer completion response send failed"); return; }
                throw e;
            }
            cancelTransferTimeout();
            final PendingFile completed = files.remove();
            if (!completed.path.delete()) LOG.debug("Gear S3 contacts cache cleanup deferred");
            Arrays.fill(completed.bytes, (byte) 0);
            sending = null;
            ftState = FtState.READY; ftRecoveryRetries = 0;
            LOG.info("Gear S3 contacts file-transfer completed; remaining={}", files.size());
            if (pendingTransportSnapshot != null && completed.deletionTombstone) {
                ftNeedsRefreshAfterDelete = true;
                if (!pendingTransportSnapshot.recordTransportDeleted(completed.attemptIds)) {
                    stopReplacement("deletion transport checkpoint persistence failed"); return;
                }
                for (final long id : completed.attemptIds)
                    LOG.debug("Gear S3 contacts persistent deletion checkpoint: rawId={}; transport-delivered=true; semantic result unknown", id);
                remoteAttemptIds.removeAll(completed.attemptIds);
            }
            if (pendingTransportSnapshot != null && !completed.sentHashes.isEmpty()) {
                if (!pendingTransportSnapshot.recordTransportDelivered(completed.sentHashes)) {
                    stopReplacement("transport-delivered checkpoint persistence failed"); return;
                }
                for (final long id : completed.sentHashes.keySet())
                    LOG.debug("Gear S3 contacts transport-delivered checkpoint: rawId={}; per-ID uncertainty=true; semantic result unknown", id);
                remoteAttemptIds.removeAll(completed.sentHashes.keySet());
                if (pendingPersistentTest && snapshot.persistentDeleteTestRawId() > 0)
                    LOG.debug("Gear S3 contacts persistent reinsert checkpoint: rawId={}; exact sent I fingerprint persisted; transportDeleted=false; semantic result unknown", snapshot.persistentDeleteTestRawId());
                else if (pendingPersistentTest)
                    LOG.debug("Gear S3 contacts persistent transport-delivered checkpoint: rawId={}; key=samsung_gear_contacts_transport_ledger_v1; exact sent I fingerprint persisted; per-ID uncertainty=true; semantic result unknown", snapshot.persistentTestRawId());
                if (debugRequests.hasProductionTest())
                    LOG.info("Gear S3 contacts production-test ledger retained: process-local; persisted legacy baseline unchanged; awaiting next real Resume");
            }
            if (files.isEmpty() && pendingDelta != null) {
                if (pendingInitial) {
                    if (snapshot.recordTransportDeliveredSnapshot(pendingDelta)) {
                        LOG.info("Gear S3 contacts initial files delivered: {}-record transport baseline saved", snapshot.size());
                        controlReady = newFileReady(); // Old-generation completion cannot grant new-generation readiness.
                        debugRequests.wake();
                    } else {
                        LOG.warn("Gear S3 contacts initial files delivered but baseline persistence failed; previous baseline retained");
                    }
                } else if (pendingDebugOneShot) {
                    LOG.warn("Gear S3 contacts delta files delivered; application result unknown, previous baseline retained");
                }
                if (pendingDebugOneShot && (activeDebugMode == Mode.INSERT || activeDebugMode == Mode.REPLACE)) {
                    final GearContactsCodec.Contact seeded = pendingDelta.insert.get(0);
                    debugRequests.completed(this, seeded.rawId, GearContactsSnapshot.fingerprint(seeded));
                }
                pendingDelta = null;
                pendingTransportSnapshot = null; pendingPersistentTest = false;
                pendingInitial = pendingReplacement = pendingFromResume = false;
                if (pendingDebugOneShot) {
                    pendingDebugOneShot = false;
                    activeDebugMode = Mode.NONE;
                    logDebugReturnToPaused("file delivered; semantic result still unknown");
                }
                debugRequests.wake();
                if (scanAgain) { scanAgain = false; scheduleScan(false); }
                maybeReconnectControl();
            } else if (pendingReplacement) closeReplacementTransfer(completed.replacementDelete);
            else sendNextFile();
        }
    }

    private void sendFtCommand(final JSONObject command) {
        emit(ftCommandSession, 0, GearContactsCodec.frame(ftCommandTx, command.toString().getBytes(StandardCharsets.UTF_8)));
        ftCommandTx = next(ftCommandTx);
    }

    private void sendNextChunk() {
        if (sending == null || ftDataSession < 1 || sending.offset >= sending.bytes.length) return;
        if (pendingTransportSnapshot != null && !sending.setupAccepted) return;
        final int length = Math.min(MAX_CHUNK, sending.bytes.length - sending.offset);
        final byte[] payload = new byte[length + 1]; payload[0] = 1;
        System.arraycopy(sending.bytes, sending.offset, payload, 1, length);
        // Queue submission can race disconnection and is not a wire receipt. Persist before it,
        // never before SAP/open/setup: only payload bytes can deliver a Contacts DB operation.
        if (!markPayloadAttempt(sending)) { stopReplacement("could not persist payload attempt marker"); return; }
        try { emit(ftDataSession, 0, GearContactsCodec.frame(ftDataTx, payload)); }
        catch (final RuntimeException e) {
            if (trackedTransfer()) { stopReplacement("file-transfer payload send failed"); return; }
            throw e;
        }
        lastDataAck = ftDataTx;
        ftDataTx = next(ftDataTx); sending.offset += length;
        if (trackedTransfer()) {
            cancelTransferTimeout();
            armTransferTimeout();
        }
    }

    private void sendAck(final int session, final int sequence) {
        emit(session, 1, SapNotificationServiceCodec.encodeReliableBlockAck(sequence));
    }
    private void emit(final int session, final int type, final byte[] body) {
        emitter.send(new SapPostAuthRecord(new SapTransportHeader(0, type, session, 0), body));
    }
    private static int next(final int sequence) { return sequence == 65535 ? 1 : sequence + 1; }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        LocalBroadcastManager.getInstance(context).unregisterReceiver(connectionUpdates);
        cancelControlCallbacks();
        cancelFtRecoveryTimeout();
        ftRecoveryPending = false; ftState = FtState.DISCONNECTED;
        controlState = ControlState.DISCONNECTED;
        controlReconnectPending = false;
        controlReady = false; readyControlGeneration = -1;
        if (trackedTransfer()) stopReplacement("service disposed");
        cancelBoundaryTimeout();
        cancelTransferTimeout();
        replaceBoundary = ReplaceBoundary.NONE;
        scanGeneration++;
        debugRequests.detach(this);
        handler.removeCallbacks(observerScan);
        if (pendingDebugOneShot || debugOneShot.isArmed()) {
            pendingDebugOneShot = false; debugOneShot.cancel();
            logDebugReturnToPaused("service disposed");
        }
        if (watching) context.getContentResolver().unregisterContentObserver(observer);
        loader.shutdownNow();
        if (keyCipher != null) keyCipher.clear();
        Arrays.fill(contactKey, (byte) 0);
        clearFiles();
        pendingTransportSnapshot = null;
    }

    private void logDebugReturnToPaused(final String reason) {
        debugRequests.finish(this);
        debugMode = activeDebugMode = Mode.NONE;
        debugRawId = -1;
        pendingVerificationLogged = true;
        LOG.warn("Gear S3 contacts debug one-shot returned to paused state: {}; baseline unchanged", reason);
    }

    private void clearFiles() {
        for (final PendingFile file : files) {
            if (!file.path.delete()) LOG.debug("Gear S3 contacts cache cleanup deferred");
            Arrays.fill(file.bytes, (byte) 0);
        }
        files.clear();
        sending = null;
        remoteAttemptIds.clear();
    }

    private static final class PendingFile {
        final File path; final byte[] bytes; final boolean replacementDelete, deletionTombstone;
        final Map<Long, String> sentHashes;
        final Set<Long> attemptIds;
        int offset; long progress; boolean setupAccepted, attemptMarked;
        long offerGeneration;
        int offerSession = -1, offerTransaction = -1;
        PendingFile(final File path, final byte[] bytes, final boolean replacementDelete, final boolean deletionTombstone,
                    final Map<Long, String> sentHashes, final Set<Long> attemptIds) {
            this.path = path; this.bytes = bytes; this.replacementDelete = replacementDelete; this.deletionTombstone = deletionTombstone;
            this.sentHashes = sentHashes; this.attemptIds = attemptIds;
        }
    }
}
