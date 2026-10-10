package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.media.AudioManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.Manifest;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.provider.CalendarContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.AbstractBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPdCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPd33Codec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapClientIdentity;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapAccessoryAuthenticationCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapReconnectCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapRecordCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecordCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCapabilityExchange;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCapexCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerAcceptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapBcmServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapBcmServiceConnector;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapBcmFeatureExchange;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerApplication;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerAppCipher;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerServiceChannelHeader;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTermination;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionTerminationResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapAgentDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationIdentityStore;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceAcceptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapNotificationServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapReliableSession;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapMusicServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapMusicServiceAcceptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapMusicCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapMusicStateCache;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapWeatherCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapWeatherDeliveryState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapWeatherServiceAcceptor;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapWeatherServiceConnector;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapWeatherServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapWeatherStateCache;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapExerciseRecords;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerCapabilityResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerSecureDecoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerSecureEncoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerServiceConnector;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapFindMyPhoneRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarServiceConnection;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarPayloadDiagnostics;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarRangeSet;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarReliableReceiver;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarEventSelection;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarSnapshot;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarRangeStore;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarMirrorStore;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarBootstrap;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.WsmV2Client;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3StepImporter;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3HeartRateImporter;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3ExerciseImporter;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3HealthFetchState;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3CommonSyncState;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarEvent;
import nodomain.freeyourgadget.gadgetbridge.util.calendar.CalendarManager;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;

/** Bluetooth Classic SAP transport through the bounded bcmservice feature exchange. */
public class GearS3DeviceSupport extends AbstractBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(GearS3DeviceSupport.class);
    private static final UUID SAP_UUID = UUID.fromString("a49eb41e-cb06-495c-9f4f-bb80a90cdf00");
    private static final Map<String, WeakReference<GearS3DeviceSupport>> CALENDAR_DEBUG_SUPPORTS =
            new ConcurrentHashMap<>();
    private final SapRecordCodec records = new SapRecordCodec();
    private final GearSessionSecrets secrets = new GearSessionSecrets();
    private WsmV2Client wsm;
    private byte[] serverPoint;
    private String sapClientId;
    private String watchPeerId;
    private GearS3PdStateMachine pdStateMachine;
    private volatile SapPostAuthRecordCodec postAuthRecords;
    private SapCapabilityExchange capabilityExchange;
    private SapHostManagerRegistration hostManagerRegistration;
    private SapHostManagerAcceptor hostManagerAcceptor;
    private SapHostManagerApplication hostManagerApplication;
    private SapHostManagerAppCipher hostManagerAppCipher;
    private SapBcmServiceRegistration bcmServiceRegistration;
    private SapBcmServiceConnector bcmServiceConnector;
    private SapBcmFeatureExchange bcmFeatureExchange;
    private SapNotificationServiceRegistration notificationServiceRegistration;
    private SapNotificationServiceAcceptor notificationServiceAcceptor;
    private SapMusicServiceRegistration musicServiceRegistration;
    private SapMusicServiceAcceptor musicServiceAcceptor;
    private SapWeatherServiceRegistration weatherServiceRegistration;
    private SapWeatherServiceAcceptor weatherServiceAcceptor;
    private SapWeatherServiceConnector weatherServiceConnector;
    private final SapWeatherStateCache weatherStateCache = new SapWeatherStateCache();
    private final SapWeatherDeliveryState weatherDeliveryState = new SapWeatherDeliveryState();
    private volatile ScheduledExecutorService weatherDeliveryExecutor = newWeatherDeliveryExecutor();
    private volatile long weatherRuntimeGeneration;
    private ScheduledFuture<?> weatherAckTimeout;
    private ScheduledFuture<?> weatherConnectionTimeout;
    private final ArrayDeque<SapPostAuthRecord> weatherPreResponseRecords = new ArrayDeque<>();
    private int weatherReliableTxSequence;
    private final SapReliableSession weatherReliableRxSession = new SapReliableSession();
    private volatile byte[] weatherReliableLastWire;
    private volatile int weatherReliableLastSequence;
    private int recentlyClosedPhoneWeatherSession = -1;
    private long recentlyClosedPhoneWeatherAt;
    private static final long WEATHER_ACK_TIMEOUT_SECONDS = 10;
    private long lastWeatherSequence;
    private SapPedometerServiceRegistration pedometerServiceRegistration;
    private SapPedometerServiceConnector pedometerServiceConnector;
    private volatile GearFindMyPhoneService findMyPhoneService;
    private final Handler findMyPhoneHandler = new Handler(android.os.Looper.getMainLooper());
    private SapCalendarServiceRegistration calendarServiceRegistration;
    private SapCalendarServiceConnection calendarServiceConnection;
    private final SapCalendarSnapshot calendarSnapshot = new SapCalendarSnapshot();
    private final SapCalendarRangeSet calendarWindows = new SapCalendarRangeSet();
    private final List<SapCalendarCodec.RequestRange> calendarRanges = calendarWindows.getRanges();
    private final List<String> calendarRangeZones = calendarWindows.getZones();
    private SapCalendarRangeStore calendarRangeStore;
    private SapCalendarMirrorStore calendarMirrorStore;
    private boolean calendarStateRestored;
    private final Object calendarLock = new Object();
    private final android.os.HandlerThread calendarThread = startCalendarThread();
    private final Handler calendarHandler = new Handler(calendarThread.getLooper());
    private final java.util.concurrent.ExecutorService sapApplicationExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "GearS3SapApplications"));
    private volatile long sapDispatchGeneration;
    private volatile boolean sapDispatchDisposed;
    private volatile boolean sapTransportClosed;
    private volatile CalendarRoute calendarRoute;
    private final SapCalendarReliableReceiver calendarReliableRxSession = new SapCalendarReliableReceiver();
    private final AtomicInteger calendarIngressPending = new AtomicInteger();
    private final AtomicBoolean calendarRefreshRunning = new AtomicBoolean();
    private long calendarAckScheduledAt;
    private final ContentObserver calendarObserver = new ContentObserver(calendarHandler) {
        @Override public void onChange(final boolean selfChange) { calendarSourceChanged(); }
        @Override public void onChange(final boolean selfChange, final Uri uri) { calendarSourceChanged(); }
    };
    private boolean calendarObserverRegistered;
    private boolean calendarProfileExposed;
    private boolean calendarInitialSequenceRequested;
    private boolean calendarUpdatePending;
    private boolean calendarRefreshQueued;
    private boolean calendarRefreshPendingDurable;
    private long calendarSequence;
    private int calendarReliableTxSequence;
    private int calendarMaxTextLength;
    private boolean calendarReminders;
    private SapCalendarSnapshot.Difference calendarPendingDifference;
    private final Runnable calendarRefreshRunnable = () -> {
        calendarRefreshRunning.set(true);
        try { synchronized (calendarLock) { refreshCalendarFromPhone(); } }
        finally { calendarRefreshRunning.set(false); }
    };
    private final Runnable calendarAckRunnable = () -> {
        synchronized (calendarLock) { sendPendingCalendarReliableAck(); }
    };
    private final Runnable calendarCreateTimeoutRunnable = () -> {
        enqueueSapApplication(() -> {
            if (calendarServiceConnection == null
                    || calendarServiceConnection.getPendingPhoneSessionId() < 1) return;
            LOG.warn("Gear S3 calendar phone->watch create timed out: state={} pendingSession={} sessionMapBefore={}; retiring outgoing proposal without immediate retry",
                    calendarServiceConnection.getState(), calendarServiceConnection.getPendingPhoneSessionId(),
                    usedApplicationSessions());
            calendarServiceConnection.expirePendingPhoneRequest();
            LOG.info("Gear S3 calendar create timeout: state={} sessionMapAfter={}",
                    calendarServiceConnection.getState(), usedApplicationSessions());
        });
    };
    private final Runnable calendarUpdateAckTimeoutRunnable = new Runnable() {
        @Override public void run() {
            synchronized (calendarLock) {
                if (!calendarUpdatePending) return;
                // Replaying the application operation with a NEW reliable sequence can insert twice.
                // Keep its pending diff for a late ACK; an interrupted session invalidates the baseline.
                LOG.warn("Gear S3 calendar application ACK overdue: sequence={}; no application replay; retaining pending operation", calendarSequence);
            }
        }
    };
    private SapPedometerSecureDecoder pedometerSecureDecoder;
    private SapPedometerSecureEncoder pedometerSecureEncoder;
    private final GearHealthDiagnostics healthDiagnostics;
    private GearContactsService contactsService;
    private GearContactsDebugRequests contactsDebugRequests;
    private boolean pedometerFirstPayloadLogged;
    private boolean pedometerManagerCapabilityResponded;
    private boolean pedometerRichCapabilityResponded;
    private boolean commonSyncActivityDataPersisted;
    private static final long HEALTH_FETCH_TIMEOUT_MILLIS = 90_000L;
    private static final long COMMON_SYNC_EXPECTING_TIMEOUT_MILLIS = 90_000L;
    private final GearS3HealthFetchState healthFetchState = new GearS3HealthFetchState();
    private final GearS3CommonSyncState commonSyncState = new GearS3CommonSyncState();
    private final Handler healthFetchHandler = new Handler(Looper.getMainLooper());
    private final Runnable healthFetchTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (GearS3DeviceSupport.this) {
                healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
                commonSyncState.fail();
                failHealthFetch("timed out waiting for CommonSync");
            }
        }
    };
    private final Runnable commonSyncExpectationTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (GearS3DeviceSupport.this) {
                if (commonSyncState.isExpectingSync()) {
                    commonSyncState.fail();
                    failHealthFetch("CommonSync did not start after capability exchange");
                    LOG.warn("Gear S3 CommonSync state: EXPECTING_SYNC -> IDLE (timeout)");
                }
            }
        }
    };
    private final SapMusicStateCache musicStateCache = new SapMusicStateCache();
    private SapNotificationIdentityStore notificationIdentityStore;
    private final SapReliableSession notificationReliableRxSession = new SapReliableSession();
    private final Handler notificationReliableAckHandler = new Handler(Looper.getMainLooper());
    private byte[] notificationLastInitialization;
    private final Runnable notificationReliableAckRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (GearS3DeviceSupport.this) {
                sendPendingNotificationReliableAck();
            }
        }
    };
    private int notificationReliableTxSequence;
    private int notificationSequence;
    /** Start the per-service action namespace at 3. */
    private int nextWearableActionId = 3;
    /** NotificationUnit.windowID is the Android SBN key; NotificationSpec.id is transient on repost. */
    private final Map<String, NotificationRecord> notifications = new HashMap<>();
    private final Map<Integer, String> notificationIdToWindowId = new HashMap<>();
    private final Map<String, Long> notificationPreparationTokens = new HashMap<>();
    private final Map<Integer, String> notificationPreparingIds = new HashMap<>();
    private final GearNotificationDelta notificationDelta = new GearNotificationDelta();
    // An interrupted provider read may outlive shutdown while the next connection starts a worker.
    private final Map<String, byte[]> applicationThumbnailCache = new java.util.concurrent.ConcurrentHashMap<>();
    private ExecutorService notificationImageExecutor = newNotificationImageExecutor();
    private long notificationPreparationToken;
    private final ArrayDeque<NotificationSpec> pendingNotifications = new ArrayDeque<>();
    private static final int MAX_PENDING_NOTIFICATIONS = 32;
    private boolean bcmPeerResponsesCompleteLogged;
    private boolean bcmPeerRequestHandlingCompleteLogged;
    private int remoteProfileCount = -1;

    public GearS3DeviceSupport() {
        this(new GearHealthDiagnostics(GearS3DeviceSupport::logHealthDiagnostics));
    }

    GearS3DeviceSupport(final GearHealthDiagnostics healthDiagnostics) {
        super(LOG, 8192);
        this.healthDiagnostics = healthDiagnostics;
        addSupportedService(SAP_UUID);
    }

    private static ScheduledExecutorService newWeatherDeliveryExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "GearS3WeatherDelivery"));
    }

    private static ExecutorService newNotificationImageExecutor() {
        return Executors.newSingleThreadExecutor(r -> new Thread(r, "GearS3NotificationImages"));
    }

    private void ensureNotificationPreparationExecutor() {
        if (sapDispatchDisposed) return;
        final boolean recreated = notificationImageExecutor.isShutdown();
        if (recreated) notificationImageExecutor = newNotificationImageExecutor();
        if (recreated) LOG.debug("Gear S3 notification preparation worker recreated for reconnect");
    }

    @Override
    public void setContext(final GBDevice device, final BluetoothAdapter adapter, final Context context) {
        super.setContext(device, adapter, context);
        contactsDebugRequests = GearContactsDebugRequests.forDevice(device.getAddress());
        contactsDebugRequests.register(context, device.getAddress(),
                GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()));
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected synchronized TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        disposeFindMyPhone();
        weatherRuntimeGeneration++;
        cancelWeatherAckTimeout();
        cancelWeatherConnectionTimeout();
        if (weatherDeliveryExecutor.isShutdown()) {
            weatherDeliveryExecutor = newWeatherDeliveryExecutor();
            LOG.info("Gear S3 weather worker recreated for RFCOMM reconnect");
        }
        sapDispatchGeneration++;
        sapTransportClosed = false;
        ensureNotificationPreparationExecutor();
        closeCalendarSession();
        restoreCalendarRanges();
        if (BuildConfig.DEBUG) CALENDAR_DEBUG_SUPPORTS.put(getDevice().getAddress().toUpperCase(Locale.ROOT),
                new WeakReference<>(this));
        calendarProfileExposed = false; // Relearn the peer on the new SAP transport.
        healthFetchHandler.removeCallbacks(healthFetchTimeoutRunnable);
        healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
        healthFetchState.fail();
        commonSyncState.fail();
        clearHostManagerAppCipher();
        clearPedometerAppCipher();
        healthDiagnostics.restart();
        secrets.clear();
        wsm = null;
        serverPoint = null;
        sapClientId = SapClientIdentity.getOrCreate(GBApplication.getPrefs().getPreferences());
        watchPeerId = null;
        remoteProfileCount = -1;
        postAuthRecords = null;
        capabilityExchange = null;
        hostManagerRegistration = SapHostManagerRegistration.getOrCreate(GBApplication.getPrefs().getPreferences());
        hostManagerAcceptor = new SapHostManagerAcceptor(hostManagerRegistration);
        hostManagerApplication = null;
        bcmServiceRegistration = SapBcmServiceRegistration.getOrCreate(GBApplication.getPrefs().getPreferences(), hostManagerRegistration);
        bcmServiceConnector = new SapBcmServiceConnector(bcmServiceRegistration);
        notificationServiceRegistration = SapNotificationServiceRegistration.getOrCreate(GBApplication.getPrefs().getPreferences(), hostManagerRegistration, bcmServiceRegistration);
        notificationServiceAcceptor = new SapNotificationServiceAcceptor(notificationServiceRegistration);
        musicServiceRegistration = SapMusicServiceRegistration.getOrCreate(GBApplication.getPrefs().getPreferences(), hostManagerRegistration, bcmServiceRegistration, notificationServiceRegistration);
        musicServiceAcceptor = new SapMusicServiceAcceptor(musicServiceRegistration);
        weatherServiceRegistration = SapWeatherServiceRegistration.getOrCreate(GBApplication.getPrefs().getPreferences(),
                hostManagerRegistration, bcmServiceRegistration, notificationServiceRegistration, musicServiceRegistration);
        weatherServiceAcceptor = new SapWeatherServiceAcceptor(weatherServiceRegistration);
        weatherServiceConnector = new SapWeatherServiceConnector(weatherServiceRegistration);
        weatherReliableTxSequence = 1;
        weatherReliableRxSession.reset();
        weatherPreResponseRecords.clear();
        recentlyClosedPhoneWeatherSession = -1;
        weatherDeliveryState.reset();
        lastWeatherSequence = 0;
        weatherStateCache.clear();
        pedometerServiceRegistration = SapPedometerServiceRegistration.getOrCreate(GBApplication.getPrefs().getPreferences(),
                hostManagerRegistration, bcmServiceRegistration, notificationServiceRegistration, musicServiceRegistration);
        pedometerServiceConnector = new SapPedometerServiceConnector(pedometerServiceRegistration);
        calendarServiceRegistration = SapCalendarServiceRegistration.getOrCreate(GBApplication.getPrefs().getPreferences(),
                hostManagerRegistration, bcmServiceRegistration, notificationServiceRegistration,
                musicServiceRegistration, pedometerServiceRegistration, weatherServiceRegistration.getComponentId());
        calendarServiceConnection = new SapCalendarServiceConnection(calendarServiceRegistration);
        LOG.info("Gear S3 local calendar provider registered: agent=0x{} component=0x{} API={} ASP=0x{} role=0x{} QoS={}/{}/{}",
                Integer.toHexString(calendarServiceRegistration.getAgentUuid()),
                Integer.toHexString(calendarServiceRegistration.getComponentId()),
                SapCalendarServiceRegistration.API_CHANNEL,
                Integer.toHexString(SapCalendarServiceRegistration.ASP_VERSION),
                Integer.toHexString(SapCalendarServiceRegistration.ROLE_FLAGS),
                SapCalendarServiceRegistration.QOS_TYPE, SapCalendarServiceRegistration.QOS_DATA_RATE,
                SapCalendarServiceRegistration.QOS_CLASS);
        final Set<Integer> usedContactsComponents = new HashSet<>(Arrays.asList(
                hostManagerRegistration.getComponentId(), bcmServiceRegistration.getComponentId(),
                notificationServiceRegistration.getComponentId(), musicServiceRegistration.getComponentId(),
                pedometerServiceRegistration.getComponentId(), weatherServiceRegistration.getComponentId(),
                calendarServiceRegistration.getComponentId()));
        final SapFindMyPhoneRegistration fmpRegistration = SapFindMyPhoneRegistration.getOrCreate(
                GBApplication.getPrefs().getPreferences(), hostManagerRegistration, usedContactsComponents);
        usedContactsComponents.add(fmpRegistration.getComponentId());
        findMyPhoneService = new GearFindMyPhoneService(fmpRegistration, new GearFindMyPhoneService.Emitter() {
            @Override public void send(final SapPostAuthRecord record, final long generation) {
                final GearFindMyPhoneService owner = findMyPhoneService;
                final long transportGeneration = sapDispatchGeneration;
                createTransactionBuilder("gearS3FindMyPhone")
                        .run(socket -> findMyPhoneService == owner && owner != null
                                && owner.generation() == generation && transportGeneration == sapDispatchGeneration
                                && owner.hasSession(record.getHeader().getSessionId()))
                        .write(SapPostAuthRecordCodec.encode(record)).queue();
            }
            @Override public boolean connected() { return GearS3DeviceSupport.this.isConnected(); }
        }, new GearFindMyPhoneAlert(getContext(), getDevice()), (task, delay) -> {
            findMyPhoneHandler.postDelayed(task, delay);
            return () -> findMyPhoneHandler.removeCallbacks(task);
        });
        LOG.info("Gear S3 FMP provider registered: agent={} component={} ASP=0200 role=04 channel=103",
                fmpRegistration.getAgentUuid(), fmpRegistration.getComponentId());
        if (contactsService != null) contactsService.dispose();
        final long contactsTransportGeneration = sapDispatchGeneration;
        contactsService = new GearContactsService(getContext(), GBApplication.getPrefs().getPreferences(),
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()), getDevice().getAddress(), secrets,
                hostManagerRegistration, usedContactsComponents, new GearContactsService.Emitter() {
            @Override public void send(final SapPostAuthRecord record) { sendContactsRecord(record, contactsTransportGeneration); }
            @Override public Set<Integer> usedSessions() { return usedApplicationSessions(); }
            @Override public boolean isConnected() {
                return !sapTransportClosed && contactsTransportGeneration == sapDispatchGeneration
                        && GearS3DeviceSupport.this.isConnected();
            }
            @Override public void reconnect(final GearContactsService requester) {
                recoverContactsConnection(requester);
            }
        }, contactsDebugRequests);
        pedometerSecureEncoder = null;
        pedometerFirstPayloadLogged = false;
        pedometerManagerCapabilityResponded = false;
        pedometerRichCapabilityResponded = false;
        musicStateCache.reset();
        notificationIdentityStore = new SapNotificationIdentityStore(GBApplication.getPrefs().getPreferences());
        notificationReliableTxSequence = 1;
        notificationReliableRxSession.reset();
        notificationReliableAckHandler.removeCallbacks(notificationReliableAckRunnable);
        notificationLastInitialization = null;
        notificationSequence = 0;
        nextWearableActionId = 3;
        notifications.clear();
        notificationDelta.clear();
        notificationIdToWindowId.clear();
        pendingNotifications.clear();
        notificationPreparationTokens.clear();
        notificationPreparingIds.clear();
        bcmFeatureExchange = null;
        bcmPeerResponsesCompleteLogged = false;
        bcmPeerRequestHandlingCompleteLogged = false;
        LOG.debug("Gear S3 local bcmservice consumer registered: agent=0x{} component=0x{}",
                Integer.toHexString(bcmServiceRegistration.getAgentUuid()),
                Integer.toHexString(bcmServiceRegistration.getComponentId()));
        LOG.debug("Gear S3 local NotificationService provider registered: agent=0x{} component=0x{} API={} QoS={}/{}/{} payloadType={}",
                Integer.toHexString(notificationServiceRegistration.getAgentUuid()), Integer.toHexString(notificationServiceRegistration.getComponentId()),
                SapNotificationServiceRegistration.API_CHANNEL, SapNotificationServiceRegistration.QOS_TYPE,
                SapNotificationServiceRegistration.QOS_DATA_RATE, SapNotificationServiceRegistration.QOS_CLASS,
                SapNotificationServiceRegistration.PAYLOAD_TYPE);
        pdStateMachine = new GearS3PdStateMachine();
        LOG.debug("Gear S3 RFCOMM connected via SAP SDP UUID; waiting for PD request");
        return builder.setDeviceState(GBDevice.State.INITIALIZING);
    }

    @Override
    public void onSocketRead(final byte[] data) {
        final long generation = sapDispatchGeneration;
        final long receivedAt = android.os.SystemClock.elapsedRealtime();
        final SapPostAuthRecordCodec decoder = postAuthRecords;
        if (decoder == null) {
            handleHandshakeRead(data);
            return;
        }
        try {
            // Only the RFCOMM reader owns framing. Application work must not stop its next read.
            for (final SapPostAuthRecord record : decoder.accept(data)) {
                dispatchPostAuthRecord(record, receivedAt, generation);
            }
        } catch (final RuntimeException e) {
            enqueueSapApplication(() -> fail("invalid SAP input", e), generation);
        }
    }

    private synchronized void handleHandshakeRead(final byte[] data) {
        try {
            if (postAuthRecords != null) {
                for (final SapPostAuthRecord record : postAuthRecords.accept(data)) {
                    dispatchPostAuthRecord(record, android.os.SystemClock.elapsedRealtime(), sapDispatchGeneration);
                }
                return;
            }
            byte[] next = data;
            while (true) {
                final List<SapRecord> decoded = records.accept(next, 1);
                if (decoded.isEmpty()) break;
                handleRecord(decoded.get(0));
                if (postAuthRecords != null) {
                    final byte[] trailing = records.takeBuffered();
                    if (trailing.length > 0) {
                        for (final SapPostAuthRecord record : postAuthRecords.accept(trailing)) {
                            dispatchPostAuthRecord(record, android.os.SystemClock.elapsedRealtime(), sapDispatchGeneration);
                        }
                    }
                    return;
                }
                next = new byte[0];
            }
        } catch (final Exception e) {
            fail("invalid SAP/PD/WSM input", e);
        }
    }

    private void handleRecord(final SapRecord record) throws Exception {
        final int type = record.getType() & 0xff;
        final byte[] payload = record.getPayload();
        LOG.debug("Gear S3 received SAP record type=0x{} length={}", Integer.toHexString(type), payload.length);
        switch (type) {
            case 0x13:
                final SapReconnectCodec.Probe reconnectProbe = SapReconnectCodec.parseProbe(payload);
                requirePdAction(pdStateMachine.onReconnectProbe(), GearS3PdStateMachine.Action.SEND_CONFIGURATION_CHANGED);
                send(new SapRecord(SapReconnectCodec.createConfigurationChangedResponse(reconnectProbe)));
                LOG.debug("Gear S3 PD 3.3 reconnect probe from {}; requested fresh configuration negotiation", reconnectProbe.getPeerId());
                break;
            case 0x14:
                final SapPd33Codec.Offer offer = SapPd33Codec.parseOffer(payload);
                requirePdAction(pdStateMachine.onPd33OfferAccepted(), GearS3PdStateMachine.Action.SEND_WSM_INIT);
                send(new SapRecord(SapPd33Codec.createAnswer(offer, new SapPd33Codec.ClientInfo(
                        localPeerId(), Build.MODEL, Build.MANUFACTURER, bluetoothName(), 0, 0))));
                watchPeerId = offer.getPeerId();
                remoteProfileCount = offer.getProfileCount();
                startFreshWsm("PD 3.3 answer; sent WSM INIT (0x20)");
                LOG.debug("Gear S3 PD 3.3 offer from {} accepted; sent PD answer (0x15)", offer.getPeerId());
                break;
            case 0x05:
                final String watchModel = SapPdCodec.parseWatchModel(payload);
                if (!"SM-R760".equals(watchModel)) throw new IllegalArgumentException("Expected SM-R760, got " + watchModel);
                getDevice().setModel(watchModel);
                watchPeerId = SapPdCodec.parseWatchPeerId(payload);
                remoteProfileCount = payload[payload.length - 1] & 0xff;
                requirePdAction(pdStateMachine.onLegacyRequestAccepted(), GearS3PdStateMachine.Action.SEND_LEGACY_RESPONSE);
                send(new SapRecord(SapPdCodec.createResponse(sapClientId, Build.MODEL, Build.MANUFACTURER, bluetoothName())));
                LOG.debug("Gear S3 PD type 5 accepted; sent PD type 6 for model={}", watchModel);
                break;
            case 0x09:
                requirePdAction(pdStateMachine.onLegacySuccess(), GearS3PdStateMachine.Action.SEND_WSM_INIT);
                startFreshWsm("legacy PD success; sent WSM INIT (0x20)");
                break;
            case 0x10:
                ensureWsm(WsmV2Client.State.WAIT_REQUEST);
                final byte[] request = SapAccessoryAuthenticationCodec.decode(payload, 0x10, 136);
                serverPoint = Arrays.copyOfRange(request, 3, 136);
                sendWsm(0x11, wsm.handleRequest(request));
                LOG.debug("Gear S3 received WSM REQUEST (0x10); sent RESPONSE (0x11)");
                break;
            case 0x12:
                ensureWsm(WsmV2Client.State.WAIT_CONFIRM);
                final byte[] confirm = SapAccessoryAuthenticationCodec.decode(payload, 0x12, 67);
                final byte[] prf = wsm.handleConfirm(confirm, serverPoint);
                clearHostManagerAppCipher();
                clearPedometerAppCipher();
                secrets.setPrf(prf);
                Arrays.fill(prf, (byte) 0);
                LOG.debug("Gear S3 WSM CONFIRM verified; authentication complete (PRF retained in memory)");
                startCapabilityExchange();
                break;
            default:
                throw new IllegalArgumentException("Unexpected SAP record type 0x" + Integer.toHexString(type));
        }
    }

    private void sendWsm(final int type, final byte[] inner) {
        final byte[] payload = SapAccessoryAuthenticationCodec.encode(type, inner);
        final byte[] encoded = SapRecordCodec.encode(new SapRecord(payload));
        if (type == 0x20) {
            LOG.debug("Gear S3 outgoing WSM INIT record: {}", GB.hexdump(encoded));
        } else if (type == 0x11) {
            LOG.debug("Gear S3 outgoing WSM RESPONSE record: {}", GB.hexdump(encoded));
        }
        createTransactionBuilder("gearS3SapWrite").write(encoded).queue();
    }

    private void send(final SapRecord record) {
        final byte[] encoded = SapRecordCodec.encode(record);
        if ((record.getType() & 0xff) == 0x15) {
            LOG.debug("Gear S3 sending PD 3.3 0x15 wire={}", GB.hexdump(encoded));
        }
        createTransactionBuilder("gearS3SapWrite").write(encoded).queue();
    }

    private void startCapabilityExchange() {
        if (remoteProfileCount < 0) throw new IllegalStateException("Missing negotiated Gear profile count");
        final List<SapCapabilityExchange.LocalServiceMetadata> localServices = new ArrayList<>(Arrays.asList(
                hostManagerRegistration.toCapabilityMetadata(), bcmServiceRegistration.toCapabilityMetadata(),
                notificationServiceRegistration.toCapabilityMetadata(), musicServiceRegistration.toCapabilityMetadata(),
                weatherServiceRegistration.toCapabilityMetadata(),
                pedometerServiceRegistration.toCapabilityMetadata(), calendarServiceRegistration.toCapabilityMetadata()));
        localServices.addAll(contactsService.metadata());
        localServices.add(findMyPhoneService.registration().toCapabilityMetadata());
        capabilityExchange = new SapCapabilityExchange(localServices.size(), remoteProfileCount, 0, localServices);
        postAuthRecords = new SapPostAuthRecordCodec();
        if (capabilityExchange.getState() == SapCapabilityExchange.State.WAIT_RESPONSE) {
            final byte[] encoded = SapPostAuthRecordCodec.encode(capabilityExchange.createInitialQuery(0xffffffffL));
            LOG.debug("Gear S3 sending initial CapEx query wire={}", GB.hexdump(encoded));
            createTransactionBuilder("gearS3CapexInitialQuery").write(encoded).queue();
            LOG.debug("Gear S3 CapEx started: phone query initiator; localProfiles={} remoteProfiles={}", localServices.size(), remoteProfileCount);
        } else {
            LOG.debug("Gear S3 CapEx started: waiting for peer query; localProfiles={} remoteProfiles={}", localServices.size(), remoteProfileCount);
        }
    }

    private static android.os.HandlerThread startCalendarThread() {
        final android.os.HandlerThread thread = new android.os.HandlerThread("GearS3Calendar");
        thread.start();
        return thread;
    }

    private static final class CalendarRoute {
        final int session;
        final long generation;
        CalendarRoute(final int session, final long generation) {
            this.session = session;
            this.generation = generation;
        }
    }

    private void enqueueSapApplication(final Runnable task) {
        enqueueSapApplication(task, sapDispatchGeneration);
    }

    private void enqueueSapApplication(final Runnable task, final long generation) {
        if (sapDispatchDisposed || sapTransportClosed) return;
        try {
            sapApplicationExecutor.execute(() -> {
                synchronized (GearS3DeviceSupport.this) {
                    if (sapDispatchDisposed || sapTransportClosed || generation != sapDispatchGeneration) return;
                    task.run();
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            if (!sapDispatchDisposed) LOG.warn("Gear S3 SAP application dispatch rejected", e);
        }
    }

    private void dispatchPostAuthRecord(final SapPostAuthRecord record, final long receivedAt,
                                       final long generation) {
        if (sapDispatchDisposed || generation != sapDispatchGeneration) return;
        final GearFindMyPhoneService fmp = findMyPhoneService;
        if (fmp != null && fmp.hasSession(record.getHeader().getSessionId())) {
            final long fmpGeneration = fmp.generation();
            enqueueSapApplication(() -> {
                if (generation == sapDispatchGeneration && findMyPhoneService == fmp)
                    fmp.onRecord(record, fmpGeneration);
            });
            return;
        }
        final CalendarRoute route = calendarRoute;
        if (route != null && route.generation == generation && route.session == record.getHeader().getSessionId()) {
            final byte[] body = record.getBody();
            final int wireSequence = record.getHeader().getFrameType() == 0 && body.length >= 2
                    ? ((body[0] & 0xff) << 8) | (body[1] & 0xff) : -1;
            LOG.info("Gear S3 calendar ingress: session={} frameType={} wireSeq={} receivedAtMs={} queued=calendar",
                    route.session, record.getHeader().getFrameType(), wireSequence, receivedAt);
            calendarIngressPending.incrementAndGet();
            if (!calendarHandler.post(() -> {
                try {
                    synchronized (calendarLock) {
                        if (calendarRoute != route || generation != sapDispatchGeneration) {
                            LOG.info("Gear S3 calendar queued frame dropped: stale session generation session={} wireSeq={}", route.session, wireSequence);
                            return;
                        }
                        LOG.info("Gear S3 calendar dispatch: wireSeq={} deliveredAtMs={} queueDelayMs={}",
                                wireSequence, android.os.SystemClock.elapsedRealtime(), android.os.SystemClock.elapsedRealtime() - receivedAt);
                        handleCalendarApplicationRecord(record);
                    }
                } finally {
                    calendarIngressPending.decrementAndGet();
                }
            })) calendarIngressPending.decrementAndGet();
            return;
        }
        enqueueSapApplication(() -> {
            if (generation != sapDispatchGeneration) return;
            try {
                // A create confirmation preceding this frame may just have installed the route.
                final CalendarRoute current = calendarRoute;
                if (current != null && current.session == record.getHeader().getSessionId()) {
                    dispatchPostAuthRecord(record, receivedAt, generation);
                } else {
                    handlePostAuthRecord(record);
                }
            } catch (final Exception e) {
                fail("invalid SAP application input", e);
            }
        });
    }

    private void handlePostAuthRecord(final SapPostAuthRecord record) {
        final int session = record.getHeader().getSessionId();
        if (session == SapTransportHeader.CAPABILITY_SESSION_ID) {
            handleCapabilityRecord(record);
            return;
        }
        if (session == SapTransportHeader.SERVICE_CONTROL_SESSION_ID) {
            handleServiceControlRecord(record);
            return;
        }
        if (hostManagerAcceptor != null && hostManagerAcceptor.isApplicationSession(session)) {
            record.getHeader().requireUnfragmentedDataFrame();
            handleHostManagerApplicationRecord(record);
            return;
        }
        if (bcmServiceConnector != null && bcmServiceConnector.isApplicationSession(session)) {
            record.getHeader().requireUnfragmentedDataFrame();
            handleBcmServiceApplicationRecord(record);
            return;
        }
        if (pedometerServiceConnector != null && pedometerServiceConnector.isApplicationSession(session)) {
            try {
                record.getHeader().requireUnfragmentedDataFrame();
            } catch (final IllegalArgumentException e) {
                LOG.warn("Gear S3 ignored malformed watch pedometer API999 transport frame: {}", e.getMessage());
                return;
            }
            handlePedometerServiceApplicationRecord(record);
            return;
        }
        if (notificationServiceAcceptor != null && notificationServiceAcceptor.isApplicationSession(session)) {
            handleNotificationServiceApplicationRecord(record);
            return;
        }
        if (musicServiceAcceptor != null && musicServiceAcceptor.isApplicationSession(session)) {
            handleMusicServiceApplicationRecord(record);
            return;
        }
        if (findMyPhoneService != null && findMyPhoneService.hasSession(session)) {
            findMyPhoneService.onRecord(record, findMyPhoneService.generation());
            return;
        }
        if (contactsService != null && contactsService.hasSession(session)) {
            contactsService.onRecord(record);
            return;
        }
        if (calendarServiceConnection != null && calendarServiceConnection.isApplicationSession(session)) {
            dispatchPostAuthRecord(record, android.os.SystemClock.elapsedRealtime(), sapDispatchGeneration);
            return;
        }
        if (weatherServiceAcceptor != null && weatherServiceAcceptor.isApplicationSession(session)) {
            handleWeatherServiceApplicationRecord(record);
            return;
        }
        if (weatherServiceConnector != null && weatherServiceConnector.isApplicationSession(session)) {
            handleWeatherServiceApplicationRecord(record);
            return;
        }
        if (weatherServiceConnector != null && weatherServiceConnector.isAwaitingResponse()
                && weatherServiceConnector.isReservedSession(session)) {
            if (weatherPreResponseRecords.size() < 2) {
                weatherPreResponseRecords.addLast(record);
                LOG.warn("Gear S3 deferred weather application frame pending service response: session={} frameType={}",
                        session, record.getHeader().getFrameType());
            } else {
                LOG.warn("Gear S3 dropped excess weather frame pending service response: session={}", session);
            }
            return;
        }
        if (session == recentlyClosedPhoneWeatherSession
                && SystemClock.elapsedRealtime() - recentlyClosedPhoneWeatherAt < 30_000L) {
            LOG.debug("Gear S3 ignored late QoS-5 weather frame after service close: session={} frameType={}",
                    session, record.getHeader().getFrameType());
            return;
        }
        if (session >= 1 && session <= 992) {
            // Drop records without a live dynamic session.
            // A late application frame must not tear down the authenticated RFCOMM link.
            LOG.warn("Gear S3 ignored application frame for unknown SAP session: session={} frameType={}",
                    session, record.getHeader().getFrameType());
            return;
        }
        throw new IllegalArgumentException("Unexpected post-auth SAP session " + session);
    }

    private void handleCapabilityRecord(final SapPostAuthRecord record) {
        if (capabilityExchange == null) throw new IllegalStateException("Missing CapEx exchange state");
        final SapCapabilityExchange.State previousState = capabilityExchange.getState();
        final SapCapabilityExchange.Reply reply = capabilityExchange.onRecord(record);
        if (reply != null) {
            sendCapabilityReply(previousState, reply);
            return;
        }
        switch (capabilityExchange.getState()) {
            case WAIT_RECIPROCAL_QUERY:
                LOG.debug("Gear S3 received CapEx response: {} agents, {} services; waiting for reciprocal query",
                        capabilityExchange.getRemoteAgents().size(), countServices(capabilityExchange.getRemoteAgents()));
                learnRemoteHostManager(capabilityExchange.getRemoteAgents());
                learnRemoteBcmService(capabilityExchange.getRemoteAgents());
                learnRemoteNotificationService(capabilityExchange.getRemoteAgents());
                learnRemoteMusicService(capabilityExchange.getRemoteAgents());
                learnRemoteWeatherService(capabilityExchange.getRemoteAgents());
                learnRemotePedometerService(capabilityExchange.getRemoteAgents());
                learnRemoteCalendarService(capabilityExchange.getRemoteAgents());
                contactsService.learnRemote(capabilityExchange.getRemoteAgents());
                findMyPhoneService.learnRemote(capabilityExchange.getRemoteAgents());
                break;
            default:
                throw new IllegalStateException("Unexpected CapEx state after received record");
        }
    }

    private void learnRemoteHostManager(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) {
            for (final SapServiceDescriptor service : agent.getServices()) {
                if (SapHostManagerRegistration.PROFILE.equals(service.getProfileId())
                        && service.getAspVersion() == SapHostManagerRegistration.ASP_VERSION
                        && (service.getRoleFlags() & 0x03) == 0x01 && (service.getRoleFlags() & 0x04) != 0) {
                    hostManagerAcceptor.setRemoteDescriptor(agent, service);
                    LOG.debug("Gear S3 remote Host Manager learned: agent=0x{} component=0x{} asp=0x{} flags=0x{} timeout={}",
                            Integer.toHexString(agent.getUuid()), Integer.toHexString(service.getComponentId()),
                            Integer.toHexString(service.getAspVersion()), Integer.toHexString(service.getRoleFlags()), service.getTimeout());
                    return;
                }
            }
        }
        LOG.debug("Gear S3 remote Host Manager descriptor not present in this CapEx response");
    }

    /** Retain the dynamically discovered provider; connection starts only after our descriptor is exposed. */
    private void learnRemoteBcmService(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) {
            for (final SapServiceDescriptor service : agent.getServices()) {
                if (SapBcmServiceRegistration.PROFILE.equals(service.getProfileId())
                        && service.getAspVersion() == SapBcmServiceRegistration.ASP_VERSION
                        && (service.getRoleFlags() & 0x03) == 0x00 && (service.getRoleFlags() & 0x04) != 0) {
                    bcmServiceRegistration.setRemoteProvider(agent, service);
                    LOG.debug("Gear S3 remote bcmservice provider learned: agent=0x{} component=0x{} asp=0x{} flags=0x{} timeout={}",
                            Integer.toHexString(agent.getUuid()), Integer.toHexString(service.getComponentId()),
                            Integer.toHexString(service.getAspVersion()), Integer.toHexString(service.getRoleFlags()), service.getTimeout());
                    return;
                }
            }
        }
        LOG.debug("Gear S3 remote bcmservice provider descriptor not present in this CapEx response");
    }

    private void learnRemoteNotificationService(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) {
            for (final SapServiceDescriptor service : agent.getServices()) {
                if (SapNotificationServiceRegistration.PROFILE.equals(service.getProfileId())
                        && service.getAspVersion() == SapNotificationServiceRegistration.REMOTE_ASP_VERSION
                        && service.getRoleFlags() == SapNotificationServiceRegistration.REMOTE_ROLE_FLAGS
                        && service.getTimeout() == 0) {
                    notificationServiceRegistration.setRemoteConsumer(agent, service);
                    LOG.info("Gear S3 remote NotificationService consumer learned: agent=0x{} component=0x{} asp=0x{} flags=0x{} timeout={}",
                            Integer.toHexString(agent.getUuid()), Integer.toHexString(service.getComponentId()),
                            Integer.toHexString(service.getAspVersion()), Integer.toHexString(service.getRoleFlags()), service.getTimeout());
                    return;
                }
            }
        }
        LOG.debug("Gear S3 remote NotificationService descriptor not present in this CapEx response");
    }

    private void learnRemoteMusicService(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) for (final SapServiceDescriptor service : agent.getServices()) {
            if (SapMusicServiceRegistration.PROFILE.equals(service.getProfileId()) && service.getAspVersion() == SapMusicServiceRegistration.REMOTE_ASP_VERSION
                    && service.getRoleFlags() == SapMusicServiceRegistration.REMOTE_ROLE_FLAGS && service.getTimeout() == 0) {
                musicServiceRegistration.setRemoteConsumer(agent, service);
                LOG.info("Gear S3 remote music consumer learned: component=0x{}", Integer.toHexString(service.getComponentId()));
                return;
            }
        }
        LOG.debug("Gear S3 remote music consumer descriptor not present in this CapEx response");
    }

    private void learnRemoteWeatherService(final List<SapAgentDescriptor> agents) {
        if (weatherServiceRegistration == null) return;
        for (final SapAgentDescriptor agent : agents) for (final SapServiceDescriptor service : agent.getServices()) {
            if (SapWeatherServiceRegistration.PROFILE.equals(service.getProfileId())
                    && service.getAspVersion() == SapWeatherServiceRegistration.REMOTE_ASP_VERSION
                    && service.getRoleFlags() == SapWeatherServiceRegistration.REMOTE_ROLE_FLAGS
                    && service.getTimeout() == 0) {
                weatherServiceRegistration.setRemoteConsumer(agent, service);
                LOG.info("Gear S3 remote weather consumer learned: agent=0x{} component=0x{} asp=0x{} flags=0x{} timeout={}",
                        Integer.toHexString(agent.getUuid()), Integer.toHexString(service.getComponentId()),
                        Integer.toHexString(service.getAspVersion()), Integer.toHexString(service.getRoleFlags()), service.getTimeout());
                return;
            }
        }
        LOG.debug("Gear S3 remote weather consumer descriptor not present in this CapEx response");
    }

    private void learnRemotePedometerService(final List<SapAgentDescriptor> agents) {
        if (pedometerServiceRegistration == null) return;
        for (final SapAgentDescriptor agent : agents) for (final SapServiceDescriptor service : agent.getServices()) {
            if (SapPedometerServiceRegistration.PROFILE.equals(service.getProfileId())
                    && service.getAspVersion() == SapPedometerServiceRegistration.REMOTE_ASP_VERSION
                    && service.getRoleFlags() == SapPedometerServiceRegistration.REMOTE_ROLE_FLAGS
                    && service.getTimeout() == 0) {
                pedometerServiceRegistration.setRemoteProvider(agent, service);
                LOG.info("Gear S3 remote watch pedometer provider learned: agent=0x{} component=0x{} asp=0x{} flags=0x{} timeout={}",
                        Integer.toHexString(agent.getUuid()), Integer.toHexString(service.getComponentId()),
                        Integer.toHexString(service.getAspVersion()), Integer.toHexString(service.getRoleFlags()), service.getTimeout());
                return;
            }
        }
        LOG.debug("Gear S3 remote watch pedometer provider descriptor not present in this CapEx response");
    }

    private void learnRemoteCalendarService(final List<SapAgentDescriptor> agents) {
        for (final SapAgentDescriptor agent : agents) for (final SapServiceDescriptor service : agent.getServices()) {
            if (SapCalendarServiceRegistration.PROFILE.equals(service.getProfileId())
                    && service.getAspVersion() == SapCalendarServiceRegistration.REMOTE_ASP_VERSION
                    && service.getRoleFlags() == SapCalendarServiceRegistration.REMOTE_ROLE_FLAGS
                    && service.getTimeout() == 0) {
                calendarServiceRegistration.setRemoteConsumer(agent, service);
                LOG.info("Gear S3 remote calendar consumer learned: agent=0x{} component=0x{}",
                        Integer.toHexString(agent.getUuid()), Integer.toHexString(service.getComponentId()));
                return;
            }
        }
        LOG.debug("Gear S3 remote calendar consumer descriptor not present in this CapEx response");
    }

    private void handleServiceControlRecord(final SapPostAuthRecord record) {
        record.getHeader().requireServiceControlDataFrame();
        LOG.debug("Gear S3 incoming service-control wire={}", GB.hexdump(SapPostAuthRecordCodec.encode(record)));
        final Object message = SapServiceConnectionCodec.parse(record.getBody());
        if (message instanceof SapServiceConnectionResponse && contactsService != null
                && contactsService.acceptResponse((SapServiceConnectionResponse) message)) return;
        if (message instanceof SapServiceConnectionResponse
                && SapCalendarServiceRegistration.PROFILE.equals(((SapServiceConnectionResponse) message).getProfile())) {
            final SapServiceConnectionResponse response = (SapServiceConnectionResponse) message;
            LOG.info("Gear S3 calendar phone->watch create confirmation RX: status={}({}) slots=0x{}/0x{} ids={} state={} pendingPhoneSession={}",
                    response.getStatus(), calendarStatusName(response.getStatus()),
                    Integer.toHexString(response.getAcceptorSlot()), Integer.toHexString(response.getInitiatorSlot()),
                    response.getSessionIds(), calendarServiceConnection.getState(),
                    calendarServiceConnection.getPendingPhoneSessionId());
            try {
                if (calendarServiceConnection.handleResponse(response)) onCalendarSessionEstablished();
                else LOG.info("Gear S3 calendar phone->watch create confirmation ignored: simultaneous or expired request; activeSession={} state={}",
                        calendarServiceConnection.getSessionId(), calendarServiceConnection.getState());
            } catch (final RuntimeException e) {
                LOG.warn("Gear S3 calendar phone->watch create confirmation rejected: reason={} stateAfter={} sessionMapAfter={}; waiting for Gear request or next calendar change",
                        e.getMessage(), calendarServiceConnection.getState(), usedApplicationSessions());
            } finally {
                calendarHandler.removeCallbacks(calendarCreateTimeoutRunnable);
            }
            return;
        }
        if (message instanceof SapServiceConnectionResponse
                && SapBcmServiceRegistration.PROFILE.equals(((SapServiceConnectionResponse) message).getProfile())
                && bcmServiceConnector != null && bcmServiceConnector.getState() == SapBcmServiceConnector.State.PENDING) {
            LOG.debug("Gear S3 incoming bcmservice connection response wire={}", GB.hexdump(SapPostAuthRecordCodec.encode(record)));
            bcmServiceConnector.handleResponse((SapServiceConnectionResponse) message);
            LOG.debug("Gear S3 bcmservice transport session established: session={} channel={}",
                    bcmServiceConnector.getApplicationSessionId(), SapBcmServiceRegistration.API_CHANNEL);
            startBcmFeatureExchange();
            return;
        }
        if (message instanceof SapServiceConnectionResponse
                && SapPedometerServiceRegistration.PROFILE.equals(((SapServiceConnectionResponse) message).getProfile())
                && pedometerServiceConnector != null && pedometerServiceConnector.getState() == SapPedometerServiceConnector.State.PENDING) {
            pedometerServiceConnector.handleResponse((SapServiceConnectionResponse) message);
            final SapPedometerServiceRegistration.RemoteDescriptor remote = pedometerServiceRegistration.getRemoteProvider();
            clearPedometerAppCipher();
            pedometerSecureDecoder = SapPedometerSecureDecoder.forDynamicComponents(secrets,
                    remote.getComponentId(), pedometerServiceRegistration.getComponentId());
            pedometerSecureEncoder = pedometerSecureDecoder.encoder();
            LOG.info("Gear S3 watch pedometer API999 transport session established: session={} channel={} qos={}/{}/{} payloadType={}",
                    pedometerServiceConnector.getApplicationSessionId(), SapPedometerServiceRegistration.API_CHANNEL,
                    SapPedometerServiceRegistration.QOS_TYPE, SapPedometerServiceRegistration.QOS_DATA_RATE,
                    SapPedometerServiceRegistration.QOS_CLASS, SapPedometerServiceRegistration.PAYLOAD_TYPE);
            if (healthFetchState.isInProgress()) {
                healthFetchState.awaitingCommonSync();
                LOG.info("Gear S3 health fetch: awaiting CommonSync");
            }
            return;
        }
        if (message instanceof SapServiceConnectionResponse
                && SapWeatherServiceRegistration.PROFILE.equals(((SapServiceConnectionResponse) message).getProfile())) {
            final SapServiceConnectionResponse response = (SapServiceConnectionResponse) message;
            if (weatherServiceConnector == null || !weatherServiceConnector.isAwaitingResponse()) {
                LOG.warn("Gear S3 ignored weather service response without pending phone connection: status={}", response.getStatus());
                return;
            }
            final int requestedSession = weatherServiceConnector.getSessionId();
            LOG.info("Gear S3 phone weather service response: session={} status={} returnedSessions={}",
                    requestedSession, response.getStatus(), response.getSessionIds());
            try {
                weatherServiceConnector.handleResponse(response);
                cancelWeatherConnectionTimeout();
                weatherReliableTxSequence = 1;
                weatherReliableRxSession.reset();
                postWeatherWork(() -> {
                    if (!weatherServiceConnector.isApplicationSession(requestedSession)) return;
                    weatherDeliveryState.open(requestedSession);
                    weatherDeliveryState.fetch(requestedSession);
                    LOG.info("Gear S3 phone weather session established: session={} channel={} qos={}/{}/{}; sending newest cached WeatherSpec",
                            requestedSession, SapWeatherServiceRegistration.API_CHANNEL,
                            SapWeatherServiceRegistration.DECLARED_QOS_TYPE, SapWeatherServiceRegistration.QOS_DATA_RATE,
                            SapWeatherServiceRegistration.QOS_CLASS);
                    sendPendingWeatherEnvelope();
                });
                while (!weatherPreResponseRecords.isEmpty()) {
                    handleWeatherServiceApplicationRecord(weatherPreResponseRecords.removeFirst());
                }
            } catch (final IllegalArgumentException e) {
                LOG.warn("Gear S3 ignored mismatched weather service response; pending session retained", e);
            } catch (final IllegalStateException e) {
                cancelWeatherConnectionTimeout();
                weatherPreResponseRecords.clear();
                LOG.warn("Gear S3 phone weather connection was not accepted; newest WeatherSpec retained", e);
            }
            return;
        }
        if (message instanceof SapServiceConnectionResponse) {
            // Expected responses were handled above. A late, repeated or unsolicited
            // reply does not create a session and must not tear down the RFCOMM link.
            final SapServiceConnectionResponse response = (SapServiceConnectionResponse) message;
            LOG.warn("Gear S3 ignored unmatched SAP service connection response: profile={} status={} sessions={}",
                    response.getProfile(), response.getStatus(), response.getSessionIds());
            return;
        }
        if (message instanceof SapServiceConnectionTerminationResponse
                && SapPedometerServiceRegistration.PROFILE.equals(((SapServiceConnectionTerminationResponse) message).getProfile())
                && pedometerServiceConnector != null && pedometerServiceConnector.getState() == SapPedometerServiceConnector.State.TERMINATING) {
            try {
                pedometerServiceConnector.handleTerminationResponse((SapServiceConnectionTerminationResponse) message);
                clearPedometerAppCipher();
                resetPedometerConnectionDiagnostics();
                LOG.info("Gear S3 health fetch: watch_pedometer termination confirmed");
                if (healthFetchState.isInProgress()) {
                    healthFetchState.awaitingConnection();
                    LOG.info("Gear S3 health fetch: reopening watch_pedometer session");
                    if (!startPedometerServiceConnection()) {
                        failHealthFetch("watch_pedometer descriptor unavailable after termination");
                    }
                }
            } catch (final RuntimeException e) {
                failHealthFetch("watch_pedometer termination was rejected");
                LOG.warn("Gear S3 health fetch: invalid watch_pedometer termination response", e);
            }
            return;
        }
        if (message instanceof SapServiceConnectionTerminationResponse
                && SapWeatherServiceRegistration.PROFILE.equals(((SapServiceConnectionTerminationResponse) message).getProfile())) {
            final SapServiceConnectionTerminationResponse response = (SapServiceConnectionTerminationResponse) message;
            try {
                if (weatherServiceConnector != null
                        && weatherServiceConnector.getState() == SapWeatherServiceConnector.State.TERMINATING) {
                    final int closingSession = weatherServiceConnector.getSessionId();
                    weatherServiceConnector.handleTerminationResponse(response);
                    recentlyClosedPhoneWeatherSession = closingSession;
                    recentlyClosedPhoneWeatherAt = SystemClock.elapsedRealtime();
                } else {
                    if (weatherServiceAcceptor == null) throw new IllegalStateException("Weather service unavailable");
                    weatherServiceAcceptor.handleTerminationResponse(response);
                }
                postWeatherWork(this::clearWeatherDeliverySession);
                LOG.info("Gear S3 weather service termination confirmed; cached WeatherSpec retained");
            } catch (final IllegalStateException e) {
                postWeatherWork(() -> {
                    weatherDeliveryState.cancelTermination();
                    sendPendingWeatherEnvelope();
                });
                LOG.warn("Gear S3 weather service termination rejected: status={}", response.getStatus(), e);
            } catch (final IllegalArgumentException e) {
                LOG.warn("Gear S3 ignored mismatched weather termination response", e);
            }
            return;
        }
        if (message instanceof SapServiceConnectionTerminationResponse) {
            if (contactsService != null && contactsService.onTerminationResponse((SapServiceConnectionTerminationResponse) message)) return;
            LOG.debug("Gear S3 ignoring unrelated SAP service termination response: profile={}",
                    ((SapServiceConnectionTerminationResponse) message).getProfile());
            return;
        }
        if (message instanceof SapServiceConnectionTermination) {
            final SapServiceConnectionTermination termination = (SapServiceConnectionTermination) message;
            if (SapFindMyPhoneRegistration.PROFILE.equals(termination.getProfile())) {
                final int status = findMyPhoneService == null ? 1 : findMyPhoneService.terminate(termination);
                sendFindMyPhoneControl(SapServiceConnectionCodec.encode(
                        SapServiceConnectionCodec.terminationResponse(termination, status)));
                return;
            }
            if (contactsService != null && contactsService.onTermination(termination)) {
                final SapServiceConnectionTerminationResponse response = SapServiceConnectionCodec.terminationResponse(
                        termination, SapServiceConnectionTerminationResponse.STATUS_SUCCESS);
                sendContactsRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                        SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response)));
                return;
            }
            if (SapCalendarServiceRegistration.PROFILE.equals(termination.getProfile())) {
                final int status = calendarServiceConnection != null
                        && calendarServiceConnection.isApplicationSession(calendarServiceConnection.getSessionId())
                        && termination.getAcceptorComponentId() == calendarServiceRegistration.getComponentId()
                        && calendarServiceRegistration.getRemoteConsumer() != null
                        && termination.getInitiatorComponentId() == calendarServiceRegistration.getRemoteConsumer().getComponentId()
                        ? SapServiceConnectionTerminationResponse.STATUS_SUCCESS : 1;
                final SapServiceConnectionTerminationResponse reply = SapServiceConnectionCodec.terminationResponse(termination, status);
                sendCalendarControl(SapServiceConnectionCodec.encode(reply), "gearS3CalendarTerminationResponse");
                if (status == 0) closeCalendarSession();
                return;
            }
            if (SapWeatherServiceRegistration.PROFILE.equals(termination.getProfile())) {
                handleWeatherServiceTermination(termination);
            } else {
                handlePedometerServiceTermination(termination);
            }
            return;
        }
        if (!(message instanceof SapServiceConnectionRequest)) {
            throw new IllegalArgumentException("Unexpected SAP service-control response from Gear");
        }
        final SapServiceConnectionRequest request = (SapServiceConnectionRequest) message;
        LOG.debug("Gear S3 service request: acceptor=0x{} initiator=0x{} profile={} sessions={}",
                Integer.toHexString(request.getAcceptorComponentId()), Integer.toHexString(request.getInitiatorComponentId()),
                request.getProfile(), request.getSessions().size());
        for (final SapServiceConnectionRequest.Session session : request.getSessions()) {
            LOG.debug("Gear S3 proposed session={} channel={} qos={}/{}/{} payloadType={}", session.getSessionId(),
                    session.getChannelId(), session.getQos().getType(), session.getQos().getDataRate(),
                    session.getQos().getClassType(), session.getPayloadType());
        }
        if (SapFindMyPhoneRegistration.PROFILE.equals(request.getProfile())) {
            final SapServiceConnectionResponse response = findMyPhoneService.accept(request, usedApplicationSessions());
            sendFindMyPhoneControl(SapServiceConnectionCodec.encode(response));
            if (response.getStatus() == SapServiceConnectionResponse.STATUS_SUCCESS)
                hostManagerAcceptor.reserveTransportSession(findMyPhoneService.session());
            return;
        }
        if (SapNotificationServiceRegistration.PROFILE.equals(request.getProfile())) {
            handleNotificationServiceConnectionRequest(request);
            return;
        }
        if (SapMusicServiceRegistration.PROFILE.equals(request.getProfile())) {
            handleMusicServiceConnectionRequest(request);
            return;
        }
        if (GearContactsService.CONTACTS.equals(request.getProfile())) {
            final SapServiceConnectionResponse response = contactsService.acceptContacts(request, usedApplicationSessions());
            sendContactsRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response)));
            if (response.getStatus() == SapServiceConnectionResponse.STATUS_SUCCESS) contactsService.establishedContacts();
            return;
        }
        if (SapWeatherServiceRegistration.PROFILE.equals(request.getProfile())) {
            handleWeatherServiceConnectionRequest(request);
            return;
        }
        if (SapCalendarServiceRegistration.PROFILE.equals(request.getProfile())) {
            handleCalendarServiceConnectionRequest(request);
            return;
        }
        // The phone is the consumer for this profile; it initiates the connection.
        if (SapPedometerServiceRegistration.PROFILE.equals(request.getProfile())) {
            LOG.warn("Gear S3 ignored unexpected Gear-initiated watch pedometer service request");
            return;
        }
        final SapServiceConnectionResponse response = hostManagerAcceptor.handle(request);
        final SapPostAuthRecord responseRecord = new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                SapServiceConnectionCodec.encode(response));
        final byte[] encoded = SapPostAuthRecordCodec.encode(responseRecord);
        LOG.debug("Gear S3 outgoing service-control response: status={} wire={}", response.getStatus(), GB.hexdump(encoded));
        createTransactionBuilder("gearS3HostManagerServiceResponse").write(encoded).queue();
        if (response.getStatus() == SapServiceConnectionResponse.STATUS_SUCCESS) {
            final SapHostManagerAcceptor.RemoteDescriptor remote = hostManagerAcceptor.getRemoteDescriptor();
            final byte[] applicationKey = secrets.deriveApplicationKey(hostManagerRegistration.getComponentId(), remote.getComponentId());
            clearHostManagerAppCipher();
            try {
                hostManagerAppCipher = new SapHostManagerAppCipher(applicationKey);
            } finally {
                Arrays.fill(applicationKey, (byte) 0);
            }
            LOG.debug("Gear S3 Host Manager transport session established: API channel={} session={} remoteComponent=0x{} localComponent=0x{}",
                    SapHostManagerRegistration.API_CHANNEL, hostManagerAcceptor.getApplicationSessionId(),
                    Integer.toHexString(remote.getComponentId()), Integer.toHexString(hostManagerRegistration.getComponentId()));
            startHostManagerWatchInfoRequest();
        }
    }

    private void handleNotificationServiceConnectionRequest(final SapServiceConnectionRequest request) {
        if (notificationServiceAcceptor == null) throw new IllegalStateException("NotificationService acceptor unavailable");
        final Set<Integer> inUse = usedApplicationSessions();
        if (hostManagerAcceptor != null && hostManagerAcceptor.getApplicationSessionId() >= 1) inUse.add(hostManagerAcceptor.getApplicationSessionId());
        if (bcmServiceConnector != null && bcmServiceConnector.getApplicationSessionId() >= 1) inUse.add(bcmServiceConnector.getApplicationSessionId());
        final SapServiceConnectionResponse response = notificationServiceAcceptor.handle(request, inUse);
        final SapPostAuthRecord responseRecord = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response));
        final byte[] encoded = SapPostAuthRecordCodec.encode(responseRecord);
        LOG.info("Gear S3 NotificationService API104 session acceptance: status={} session={} state={}", response.getStatus(),
                notificationServiceAcceptor.getApplicationSessionId(), notificationServiceAcceptor.getState());
        LOG.debug("Gear S3 NotificationService service-control response wire={}", GB.hexdump(encoded));
        createTransactionBuilder("gearS3NotificationServiceResponse").write(encoded).queue();
    }

    private void handleMusicServiceConnectionRequest(final SapServiceConnectionRequest request) {
        try {
            final Set<Integer> inUse = usedApplicationSessions();
            if (hostManagerAcceptor != null && hostManagerAcceptor.getApplicationSessionId() >= 1) inUse.add(hostManagerAcceptor.getApplicationSessionId());
            if (bcmServiceConnector != null && bcmServiceConnector.getApplicationSessionId() >= 1) inUse.add(bcmServiceConnector.getApplicationSessionId());
            if (notificationServiceAcceptor != null && notificationServiceAcceptor.getApplicationSessionId() >= 1) inUse.add(notificationServiceAcceptor.getApplicationSessionId());
            final SapServiceConnectionResponse response = musicServiceAcceptor.handle(request, inUse);
            sendMusicServiceControlResponse(response);
            LOG.info("Gear S3 music API100 session acceptance: status={} session={}", response.getStatus(), musicServiceAcceptor.getApplicationSessionId());
        } catch (final IllegalArgumentException e) {
            // A bad optional music request must not terminate the already established SAP link.
            LOG.warn("Gear S3 ignored malformed music service request: {}", e.getMessage());
        }
    }

    private void sendMusicServiceControlResponse(final SapServiceConnectionResponse response) {
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response));
        createTransactionBuilder("gearS3MusicServiceResponse").write(SapPostAuthRecordCodec.encode(record)).queue();
    }

    private void handleWeatherServiceConnectionRequest(final SapServiceConnectionRequest request) {
        try {
            if (weatherServiceConnector != null
                    && weatherServiceConnector.getState() != SapWeatherServiceConnector.State.IDLE) {
                final List<Integer> requested = new ArrayList<>();
                for (final SapServiceConnectionRequest.Session session : request.getSessions()) {
                    requested.add(session.getSessionId());
                }
                final SapServiceConnectionResponse busy = new SapServiceConnectionResponse(
                        request.getInitiatorComponentId(), request.getAcceptorComponentId(), request.getProfile(),
                        SapServiceConnectionResponse.STATUS_REJECTED, requested);
                final SapPostAuthRecord busyRecord = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                        SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(busy));
                LOG.warn("Gear S3 weather request rejected while phone weather connection is {}: session={}",
                        weatherServiceConnector.getState(), weatherServiceConnector.getSessionId());
                createTransactionBuilder("gearS3WeatherBusyResponse")
                        .write(SapPostAuthRecordCodec.encode(busyRecord)).queue();
                return;
            }
            final Set<Integer> inUse = activeApplicationSessions();
            final SapServiceConnectionResponse response = weatherServiceAcceptor.handle(request, inUse);
            final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response));
            createTransactionBuilder("gearS3WeatherServiceResponse")
                    .write(SapPostAuthRecordCodec.encode(record)).queue();
            final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
            LOG.info("Gear S3 weather service request: status={} session={} channel={} qos={}/{}/{} payloadType={}",
                    response.getStatus(), session.getSessionId(), session.getChannelId(), session.getQos().getType(),
                    session.getQos().getDataRate(), session.getQos().getClassType(), session.getPayloadType());
            if (response.getStatus() == SapServiceConnectionResponse.STATUS_SUCCESS) {
                postWeatherWork(() -> weatherDeliveryState.open(session.getSessionId()));
            }
        } catch (final IllegalArgumentException e) {
            LOG.warn("Gear S3 ignored malformed weather service request: {}", e.getMessage());
        }
    }

    private Set<Integer> activeApplicationSessions() {
        return usedApplicationSessions();
    }

    private void handleWeatherServiceApplicationRecord(final SapPostAuthRecord record) {
        try {
            final boolean reliable = weatherServiceConnector != null
                    && weatherServiceConnector.isApplicationSession(record.getHeader().getSessionId());
            if (reliable && record.getHeader().getFrameType() == 1) {
                handleWeatherReliableControl(record);
                return;
            }
            record.getHeader().requireUnfragmentedDataFrame();
            byte[] body = record.getBody();
            if (reliable) {
                if (body.length < 3) throw new IllegalArgumentException("Truncated reliable weather frame");
                final int sequence = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
                final SapReliableSession.Inbound inbound = weatherReliableRxSession.receive(sequence);
                if (inbound == SapReliableSession.Inbound.OUT_OF_ORDER) {
                    sendWeatherReliableNak(record.getHeader().getSessionId(), weatherReliableRxSession.getExpectedSequence(),
                            sequence == 1 ? 0xffff : sequence - 1);
                } else {
                    sendWeatherReliableBlockAck(record.getHeader().getSessionId(), sequence);
                }
                if (inbound != SapReliableSession.Inbound.ACCEPT) {
                    LOG.debug("Gear S3 weather reliable frame not redelivered: session={} sequence={} result={}",
                            record.getHeader().getSessionId(), sequence, inbound);
                    return;
                }
                body = Arrays.copyOfRange(body, 2, body.length);
            }
            try {
                final SapWeatherCodec.WeatherRequest request = SapWeatherCodec.decodeRequest(body);
                LOG.info("Gear S3 weather request: session={} channel={} qos={}/{}/{} topLevelKeys={} revision={} fetch={} updatedByGear={} addCurrentCity={} ackSeq={}",
                        record.getHeader().getSessionId(), SapWeatherServiceRegistration.API_CHANNEL,
                        reliable ? SapWeatherServiceRegistration.DECLARED_QOS_TYPE : SapWeatherServiceRegistration.OBSERVED_QOS_TYPE,
                        SapWeatherServiceRegistration.QOS_DATA_RATE,
                        SapWeatherServiceRegistration.QOS_CLASS, request.getTopLevelKeys(), request.getRevision(),
                        request.getFetchRequest(), request.getUpdatedByGear(), request.getAddCurrentCity(), request.getAckSequence());
                sendWeatherApplicationBody(record.getHeader().getSessionId(), SapWeatherCodec.encodeAck(request.getAckSequence()),
                        "gearS3WeatherRequestAck");
                if (request.getFetchRequest() == 1) {
                    final int sessionId = record.getHeader().getSessionId();
                    postWeatherWork(() -> {
                        if (!weatherStateCache.hasWeather()) {
                            final WeatherSpec available = Weather.getWeatherSpec();
                            weatherStateCache.update(available);
                            weatherDeliveryState.update(available);
                        }
                        weatherDeliveryState.fetch(sessionId);
                        sendPendingWeatherEnvelope();
                    });
                } else if (request.getUpdatedByGear() == 1) {
                    // Acknowledge this request, then ask the Android weather provider to
                    // refresh. Gadgetbridge receives provider results as WeatherSpec callbacks;
                    // there is no provider-neutral request-to-refresh API to invoke here.
                    LOG.info("Gear S3 watch-requested weather refresh acknowledged: session={}; waiting for a new WeatherSpec callback",
                            record.getHeader().getSessionId());
                }
                return;
            } catch (final IllegalArgumentException requestError) {
                final SapWeatherCodec.WeatherAcknowledgement acknowledgement = SapWeatherCodec.decodeAcknowledgement(body);
                final int sessionId = record.getHeader().getSessionId();
                postWeatherWork(() -> handleWeatherEnvelopeAcknowledgement(sessionId, acknowledgement));
            }
        } catch (final IllegalArgumentException e) {
            LOG.warn("Gear S3 ignored malformed weather application request: {}", e.getMessage());
        }
    }

    @Override
    public void onSendWeather() {
        final WeatherSpec weather = Weather.getWeatherSpec();
        if (weather == null) {
            LOG.warn("Gear S3 received a weather send request with no WeatherSpec available");
            return;
        }
        // DeviceCommunicationService calls this on Android's main thread. Never wait for
        // the Bluetooth receive monitor or build/compress a weather envelope there.
        postWeatherWork(() -> {
            weatherStateCache.update(weather);
            weatherDeliveryState.update(weather);
            if (weatherDeliveryState.getSessionId() < 1) {
                if (weatherServiceConnector != null && weatherServiceConnector.isAwaitingResponse()) {
                    LOG.debug("Gear S3 coalesced newest WeatherSpec while phone weather connection is pending: session={}",
                            weatherServiceConnector.getSessionId());
                } else {
                    LOG.info("Gear S3 cached newest WeatherSpec; no active weather session, attempting phone-initiated connection");
                    startPhoneWeatherConnection("WeatherSpec update");
                }
            } else {
                LOG.debug("Gear S3 coalesced WeatherSpec into active weather delivery: session={}", weatherDeliveryState.getSessionId());
                sendPendingWeatherEnvelope();
            }
        });
    }

    private void postWeatherWork(final Runnable work) {
        final long generation = weatherRuntimeGeneration;
        final ScheduledExecutorService executor = weatherDeliveryExecutor;
        try {
            executor.execute(() -> {
                if (generation == weatherRuntimeGeneration && !Thread.currentThread().isInterrupted()) work.run();
            });
        } catch (final RejectedExecutionException e) {
            LOG.warn("Gear S3 weather work rejected by closed worker; waiting for RFCOMM initialization");
        }
    }

    private synchronized void startPhoneWeatherConnection(final String reason) {
        if (weatherServiceConnector == null || weatherServiceRegistration == null) return;
        if (weatherDeliveryState.getSessionId() >= 1
                || weatherServiceAcceptor.isApplicationSession(weatherServiceAcceptor.getApplicationSessionId())
                || weatherServiceConnector.getState() != SapWeatherServiceConnector.State.IDLE) return;
        final SapWeatherServiceRegistration.RemoteDescriptor remote = weatherServiceRegistration.getRemoteConsumer();
        if (remote == null) {
            LOG.info("Gear S3 weather update remains cached: remote weather consumer not learned from CapEx");
            return;
        }
        try {
            final SapServiceConnectionRequest request = weatherServiceConnector.createRequest(activeApplicationSessions());
            final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
            final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(request));
            LOG.info("Gear S3 phone weather connection requested: reason={} localComponent=0x{} remoteComponent=0x{} session={} channel={} qos={}/{}/{}",
                    reason, Integer.toHexString(request.getInitiatorComponentId()),
                    Integer.toHexString(request.getAcceptorComponentId()), session.getSessionId(), session.getChannelId(),
                    session.getQos().getType(), session.getQos().getDataRate(), session.getQos().getClassType());
            createTransactionBuilder("gearS3PhoneWeatherServiceRequest").write(SapPostAuthRecordCodec.encode(record)).queue();
            final int pendingSession = session.getSessionId();
            final long generation = weatherRuntimeGeneration;
            // This timer is diagnostic, not permission to reuse a possibly accepted
            // session. The Gear's response can sit in an already-read batch while this
            // worker runs; only a validated response or RFCOMM reset resolves it.
            weatherConnectionTimeout = weatherDeliveryExecutor.schedule(() -> {
                synchronized (GearS3DeviceSupport.this) {
                    if (generation != weatherRuntimeGeneration) return;
                    if (weatherServiceConnector.markOverdue(pendingSession)) {
                        LOG.warn("Gear S3 phone weather response overdue: session={}; reservation retained until a definitive response or transport reset", pendingSession);
                    }
                }
            }, WEATHER_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final RuntimeException e) {
            weatherServiceConnector.clear();
            LOG.warn("Gear S3 phone weather connection could not be requested; newest WeatherSpec retained", e);
        }
    }

    private void cancelWeatherConnectionTimeout() {
        if (weatherConnectionTimeout != null) weatherConnectionTimeout.cancel(false);
        weatherConnectionTimeout = null;
    }

    private synchronized byte[] encodeWeatherApplicationBody(final int sessionId, final byte[] applicationBody) {
        byte[] body = applicationBody;
        final boolean reliable = weatherServiceConnector != null && weatherServiceConnector.isApplicationSession(sessionId);
        if (reliable) {
            final int sequence = weatherReliableTxSequence;
            body = new byte[applicationBody.length + 2];
            body[0] = (byte) (sequence >>> 8);
            body[1] = (byte) sequence;
            System.arraycopy(applicationBody, 0, body, 2, applicationBody.length);
            weatherReliableTxSequence = sequence == 0xffff ? 1 : sequence + 1;
            weatherReliableLastSequence = sequence;
        }
        final byte[] wire = SapPostAuthRecordCodec.encode(new SapPostAuthRecord(
                new SapTransportHeader(0, 0, sessionId, 0), body));
        if (reliable) weatherReliableLastWire = wire;
        return wire;
    }

    private void sendWeatherApplicationBody(final int sessionId, final byte[] body, final String transaction) {
        createTransactionBuilder(transaction).write(encodeWeatherApplicationBody(sessionId, body)).queue();
    }

    private void sendWeatherReliableBlockAck(final int sessionId, final int sequence) {
        if (sequence < 1) return;
        final SapPostAuthRecord ack = new SapPostAuthRecord(new SapTransportHeader(0, 1, sessionId, 0),
                SapNotificationServiceCodec.encodeReliableBlockAck(sequence));
        createTransactionBuilder("gearS3WeatherReliableAck").write(SapPostAuthRecordCodec.encode(ack)).queue();
    }

    private void sendWeatherReliableNak(final int sessionId, final int first, final int last) {
        final SapPostAuthRecord nak = new SapPostAuthRecord(new SapTransportHeader(0, 1, sessionId, 0),
                SapNotificationServiceCodec.encodeReliableNak(first, last));
        createTransactionBuilder("gearS3WeatherReliableNak").write(SapPostAuthRecordCodec.encode(nak)).queue();
    }

    private void handleWeatherReliableControl(final SapPostAuthRecord record) {
        final byte[] body = record.getBody();
        if (body.length == 3 && (body[0] & 0xff) == SapNotificationServiceCodec.RELIABLE_CONTROL_BLOCK_ACK) {
            final int sequence = ((body[1] & 0xff) << 8) | (body[2] & 0xff);
            LOG.debug("Gear S3 weather transport block ACK: session={} sequence={}", record.getHeader().getSessionId(), sequence);
            return;
        }
        if (body.length == 6 && (body[0] & 0xff) == SapNotificationServiceCodec.RELIABLE_CONTROL_NAK
                && (body[1] & 0xff) == 1) {
            final int first = ((body[2] & 0xff) << 8) | (body[3] & 0xff);
            final int last = ((body[4] & 0xff) << 8) | (body[5] & 0xff);
            if (weatherReliableLastWire != null && first <= weatherReliableLastSequence && weatherReliableLastSequence <= last) {
                LOG.warn("Gear S3 weather transport NAK; retransmitting session={} sequence={}",
                        record.getHeader().getSessionId(), weatherReliableLastSequence);
                createTransactionBuilder("gearS3WeatherReliableRetransmit").write(weatherReliableLastWire).queue();
            } else {
                LOG.warn("Gear S3 weather transport NAK for unavailable sequence: session={} first={} last={}",
                        record.getHeader().getSessionId(), first, last);
            }
            return;
        }
        LOG.warn("Gear S3 ignored unsupported weather reliable control frame: session={} length={}",
                record.getHeader().getSessionId(), body.length);
    }

    private void sendPendingWeatherEnvelope() {
        final long generation = weatherRuntimeGeneration;
        if (!weatherDeliveryState.hasPending()) return;
        final int sessionId = weatherDeliveryState.getSessionId();
        final WeatherSpec weather = weatherDeliveryState.beginSend();
        weatherStateCache.update(weather);
        long sequence = System.currentTimeMillis();
        if (sequence <= lastWeatherSequence) sequence = lastWeatherSequence + 1;
        lastWeatherSequence = sequence;
        try {
            final SapWeatherCodec.EncodedWeatherEnvelope encoded = weatherStateCache.encode(sequence, TimeZone.getDefault());
            if (generation != weatherRuntimeGeneration || Thread.currentThread().isInterrupted()) return;
            final byte[] wire = encodeWeatherApplicationBody(sessionId, encoded.getFramed());
            weatherDeliveryState.sent(sequence);
            LOG.info("Gear S3 sending weather envelope: session={} topLevelKeys={} cityCount={} cityName={} currentTemp={} convertedIcon={} ackSeq={} clearJsonSize={} compressed={} encodedPayloadSize={}",
                    sessionId, encoded.getTopLevelKeys(), encoded.getWeatherInfoCount(), encoded.getCityName(), encoded.getCurrentTemperature(),
                    encoded.getConvertedIcon(), sequence, encoded.getClearJsonSize(), encoded.isCompressed(), encoded.getEncodedPayloadSize());
            createTransactionBuilder("gearS3WeatherEnvelope").write(wire).queue();
            final long sentSequence = sequence;
            weatherAckTimeout = weatherDeliveryExecutor.schedule(() -> {
                if (generation != weatherRuntimeGeneration) return;
                if (weatherDeliveryState.timeOut(sessionId, sentSequence)) {
                    LOG.warn("Gear S3 weather envelope ACK timed out: session={} ackSeq={}", sessionId, sentSequence);
                    terminateWeatherServiceAfterAck();
                }
            }, WEATHER_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final RuntimeException e) {
            weatherDeliveryState.abortSend();
            LOG.warn("Gear S3 could not encode weather envelope; keeping weather session open", e);
        }
    }

    private void handleWeatherEnvelopeAcknowledgement(final int sessionId,
                                                       final SapWeatherCodec.WeatherAcknowledgement acknowledgement) {
        final SapWeatherDeliveryState.AckResult result = weatherDeliveryState.acknowledge(
                sessionId, acknowledgement.getAckSequence());
        if (result == SapWeatherDeliveryState.AckResult.UNEXPECTED) {
            LOG.warn("Gear S3 sent unexpected weather acknowledgement: session={} ackSeq={} expected={}",
                    sessionId, acknowledgement.getAckSequence(), weatherDeliveryState.getExpectedAck());
            return;
        }
        cancelWeatherAckTimeout();
        LOG.info("Gear S3 acknowledged full weather envelope: session={} ackSeq={} topLevelKeys={}",
                sessionId, acknowledgement.getAckSequence(), acknowledgement.getTopLevelKeys());
        if (result == SapWeatherDeliveryState.AckResult.SEND_PENDING) {
            sendPendingWeatherEnvelope();
        } else {
            terminateWeatherServiceAfterAck();
        }
    }

    private void terminateWeatherServiceAfterAck() {
        if (!weatherDeliveryState.beginTermination()) return;
        final SapServiceConnectionTermination termination;
        try {
            if (weatherServiceConnector != null && weatherServiceConnector.isApplicationSession(weatherDeliveryState.getSessionId())) {
                termination = weatherServiceConnector.createTermination();
            } else {
                if (weatherServiceAcceptor == null) throw new IllegalStateException("Weather service unavailable");
                termination = weatherServiceAcceptor.createTermination();
            }
        } catch (final IllegalStateException e) {
            weatherDeliveryState.cancelTermination();
            LOG.warn("Gear S3 could not initiate weather service termination", e);
            return;
        }
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(termination));
        LOG.info("Gear S3 weather envelope complete; requesting /system/weather2 service termination");
        createTransactionBuilder("gearS3WeatherServiceTerminationRequest")
                .write(SapPostAuthRecordCodec.encode(record)).queue();
    }

    private void cancelWeatherAckTimeout() {
        if (weatherAckTimeout != null) weatherAckTimeout.cancel(false);
        weatherAckTimeout = null;
    }

    private void clearWeatherDeliverySession() {
        final boolean reconnect = weatherDeliveryState.needsReconnectAfterTermination();
        cancelWeatherAckTimeout();
        weatherDeliveryState.clearSession();
        weatherReliableLastWire = null;
        weatherReliableLastSequence = 0;
        weatherReliableRxSession.reset();
        if (reconnect) {
            LOG.info("Gear S3 weather update arrived during service termination; reconnecting with newest WeatherSpec");
            startPhoneWeatherConnection("new WeatherSpec during termination");
        }
    }

    private void handleMusicServiceApplicationRecord(final SapPostAuthRecord record) {
        if (record.getHeader().getFrameType() == 1) return;
        try { record.getHeader().requireUnfragmentedDataFrame(); }
        catch (final IllegalArgumentException e) { LOG.warn("Gear S3 ignored malformed music API100 transport frame: {}", e.getMessage()); return; }
        final SapMusicCodec.Request request = SapMusicCodec.decodeRequest(record.getBody());
        switch (request.getType()) {
            case MEDIA_CHANGED:
                musicStateCache.setMediaChangedSubscribed(request.isMediaChangedEnabled());
                if (musicStateCache.isMediaChangedSubscribed()) sendCachedMusicState();
                return;
            case GET_ATTRIBUTE:
                sendMusicPayload(SapMusicCodec.encodeGetAttributeResponse(musicStateCache.getMusicStateSpec(), getMusicVolume()), "gearS3MusicGetAttribute");
                if (musicStateCache.isMediaChangedSubscribed()) sendCachedMusicState();
                return;
            case SET_ATTRIBUTE:
                final GBDeviceEventMusicControl.Event volumeEvent = request.getVolumeEvent();
                if (volumeEvent == null) { LOG.debug("Gear S3 ignored unsupported music setattribute request"); return; }
                evaluateGBDeviceEvent(new GBDeviceEventMusicControl(volumeEvent));
                sendMusicPayload(SapMusicCodec.encodeSetAttributeResponse(), "gearS3MusicSetAttribute");
                return;
            case CAPABILITY_FEATURE:
                sendMusicPayload(SapMusicCodec.encodeCapabilityFeature(getMusicMaxVolume()), "gearS3MusicCapabilityFeature");
                return;
            case REMOTE_CONTROL:
                final GBDeviceEventMusicControl.Event event = request.getControlEvent();
                if (event != null) evaluateGBDeviceEvent(new GBDeviceEventMusicControl(event));
                else LOG.debug("Gear S3 ignored music remote-control release or unsupported action");
                return;
            case UNKNOWN:
                LOG.debug("Gear S3 ignored unsupported music message {}", request.getMessageId());
                return;
            default:
                LOG.warn("Gear S3 ignored malformed music API100 frame");
        }
    }

    @Override public synchronized void onSetMusicInfo(final MusicSpec musicSpec) { musicStateCache.setMusicSpec(musicSpec); if (musicStateCache.isMediaChangedSubscribed()) sendCachedMusicState(); }
    @Override public synchronized void onSetMusicState(final MusicStateSpec stateSpec) { musicStateCache.setMusicStateSpec(stateSpec); if (musicStateCache.isMediaChangedSubscribed()) sendCachedMusicState(); }
    private void sendCachedMusicState() {
        final byte[] musicMetadata = musicStateCache.getMusicMetadata();
        final MusicStateSpec musicStateSpec = musicStateCache.getMusicStateSpec();
        if (musicMetadata != null) sendMusicPayload(musicMetadata, "gearS3MusicMetadata");
        if (musicStateSpec != null) sendMusicPayload(SapMusicCodec.encodePlaybackState(musicStateSpec), "gearS3MusicPlaybackState");
    }
    private int getMusicVolume() {
        final AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        return audioManager == null ? 0 : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }
    private int getMusicMaxVolume() {
        final AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        return audioManager == null ? 0 : audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }
    @Override public synchronized void onSetPhoneVolume(final float ignoredVolume) {
        if (musicStateCache.isMediaChangedSubscribed()) sendMusicPayload(SapMusicCodec.encodeVolumeChanged(getMusicVolume()), "gearS3MusicVolumeChanged");
    }
    private void sendMusicPayload(final byte[] payload, final String transaction) {
        if (musicServiceAcceptor == null || !musicServiceAcceptor.isApplicationSession(musicServiceAcceptor.getApplicationSessionId())) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0, musicServiceAcceptor.getApplicationSessionId(), 0), SapMusicCodec.encodeFrame(payload));
        createTransactionBuilder(transaction).write(SapPostAuthRecordCodec.encode(record)).queue();
    }

    private void handleNotificationServiceApplicationRecord(final SapPostAuthRecord record) {
        if (record.getHeader().getFrameType() == 1) {
            handleNotificationServiceReliableControl(record);
            return;
        }
        record.getHeader().requireUnfragmentedDataFrame();
        final SapNotificationServiceCodec.ServiceFrame frame = SapNotificationServiceCodec.decodeServiceFrame(record.getBody());
        final SapReliableSession.Inbound reliableInbound = notificationReliableRxSession.receive(frame.getSequence());
        if (reliableInbound == SapReliableSession.Inbound.DUPLICATE) {
            LOG.debug("Gear S3 NotificationService duplicate reliable data: session={} sequence={}; not redelivering",
                    record.getHeader().getSessionId(), frame.getSequence());
            // Restart the delayed ACK while one is pending; after it has
            // already ACKed a retransmission gets an immediate block ACK.
            if (notificationReliableRxSession.isAckPending()) scheduleNotificationReliableAck();
            else sendNotificationReliableBlockAck(notificationReliableRxSession.getLastValidSequence());
            return;
        }
        if (reliableInbound == SapReliableSession.Inbound.OUT_OF_ORDER) {
            LOG.warn("Gear S3 NotificationService stale/out-of-order reliable data: session={} sequence={} expected={}; sending NAK",
                    record.getHeader().getSessionId(), frame.getSequence(), notificationReliableRxSession.getExpectedSequence());
            sendNotificationReliableNak(notificationReliableRxSession.getExpectedSequence(), previousReliableSequence(frame.getSequence()));
            return;
        }
        scheduleNotificationReliableAck();
        final byte[] payload = frame.getPayload();
        final int protocol = (payload[0] >>> 4) & 0x0f;
        LOG.debug("Gear S3 NotificationService RX API104 session={} reliableSeq={} protocol={} payloadBytes={}",
                record.getHeader().getSessionId(), frame.getSequence(), protocol, payload.length);
        if (protocol == SapNotificationServiceCodec.PROTOCOL_INIT) {
            if (notificationServiceAcceptor.getState() == SapNotificationServiceAcceptor.State.READY) {
                if (Arrays.equals(notificationLastInitialization, payload)) {
                    LOG.debug("Gear S3 NotificationService duplicate v15 initialization while READY; ignoring application replay");
                } else {
                    LOG.warn("Gear S3 NotificationService unexpected v15 initialization while READY; ignoring application replay");
                }
                return;
            }
            final SapNotificationServiceCodec.Initialization init;
            try {
                init = SapNotificationServiceCodec.decodeInitialization(payload);
            } catch (final IllegalArgumentException e) {
                LOG.warn("Gear S3 NotificationService ignored malformed v15 initialization: payloadBytes={}",
                        payload.length, redactedDiagnosticException(e));
                return;
            }
            notificationServiceAcceptor.beginInitialization();
            notificationSequence = Math.max(notificationSequence, init.getLastSequence());
            notificationServiceAcceptor.markReady();
            notificationLastInitialization = Arrays.copyOf(payload, payload.length);
            LOG.info("Gear S3 NotificationService READY: version=15 lastSequence={} unread={} blockedApps={}",
                    init.getLastSequence(), init.getUnread().size(), init.getBlockedApps().size());
            flushPendingNotifications();
            return;
        }
        try {
            final SapNotificationServiceCodec.Message message = SapNotificationServiceCodec.decode(payload);
            handleNotificationServiceEvent(message);
        } catch (final IllegalArgumentException e) {
            // The API-104 reliable transport has already been ACKed. A malformed or unknown
            // NotificationService application message must not tear down Host Manager/RFCOMM.
            LOG.warn("Gear S3 NotificationService ignored malformed/unsupported application message: protocol={} payloadBytes={}",
                    protocol, payload.length, redactedDiagnosticException(e));
        }
    }

    private void handleNotificationServiceReliableControl(final SapPostAuthRecord record) {
        final byte[] body = record.getBody();
        if (body.length < 1) throw new IllegalArgumentException("Truncated NotificationService reliable control frame");
        final int type = body[0] & 0xff;
        if (type == SapNotificationServiceCodec.RELIABLE_CONTROL_BLOCK_ACK && body.length == 3) {
            final int sequence = ((body[1] & 0xff) << 8) | (body[2] & 0xff);
            LOG.debug("Gear S3 NotificationService reliable block ACK: session={} sequence={}", record.getHeader().getSessionId(), sequence);
            return;
        }
        if (type == SapNotificationServiceCodec.RELIABLE_CONTROL_NAK && body.length >= 2) {
            LOG.warn("Gear S3 NotificationService reliable NAK: session={} ranges={}", record.getHeader().getSessionId(), body[1] & 0xff);
            return;
        }
        throw new IllegalArgumentException("Unsupported NotificationService reliable control type " + type);
    }

    private void scheduleNotificationReliableAck() {
        notificationReliableAckHandler.removeCallbacks(notificationReliableAckRunnable);
        notificationReliableAckHandler.postDelayed(notificationReliableAckRunnable, SapReliableSession.RX_BLOCK_ACK_TIMEOUT_MILLIS);
    }

    private void sendPendingNotificationReliableAck() {
        if (notificationServiceAcceptor == null || notificationServiceAcceptor.getApplicationSessionId() < 1) return;
        final int sequence = notificationReliableRxSession.consumePendingBlockAck();
        if (sequence < 1) return;
        sendNotificationReliableBlockAck(sequence);
    }

    private void sendNotificationReliableBlockAck(final int sequence) {
        if (notificationServiceAcceptor == null || notificationServiceAcceptor.getApplicationSessionId() < 1) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 1,
                notificationServiceAcceptor.getApplicationSessionId(), 0), SapNotificationServiceCodec.encodeReliableBlockAck(sequence));
        final byte[] wire = SapPostAuthRecordCodec.encode(record);
        LOG.debug("Gear S3 NotificationService reliable block ACK TX: session={} sequence={} wire={}",
                notificationServiceAcceptor.getApplicationSessionId(), sequence, GB.hexdump(wire));
        createTransactionBuilder("gearS3NotificationServiceReliableAck").write(wire).queue();
    }

    private void sendNotificationReliableNak(final int firstMissing, final int lastMissing) {
        if (notificationServiceAcceptor == null || notificationServiceAcceptor.getApplicationSessionId() < 1) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 1,
                notificationServiceAcceptor.getApplicationSessionId(), 0), SapNotificationServiceCodec.encodeReliableNak(firstMissing, lastMissing));
        createTransactionBuilder("gearS3NotificationServiceReliableNak").write(SapPostAuthRecordCodec.encode(record)).queue();
    }

    private static int previousReliableSequence(final int sequence) { return sequence <= 1 ? 0xffff : sequence - 1; }

    /** Preserve exception stacks while hiding provider URIs and malformed notification contents. */
    private static Throwable redactedDiagnosticException(final Throwable error) {
        return redactedDiagnosticException(error, new java.util.IdentityHashMap<>());
    }

    private static Throwable redactedDiagnosticException(final Throwable error, final Map<Throwable, Throwable> seen) {
        if (seen.containsKey(error)) return seen.get(error);
        final Throwable redacted = new Throwable(error.getClass().getName() + ": message redacted");
        seen.put(error, redacted);
        redacted.setStackTrace(error.getStackTrace());
        if (error.getCause() != null && error.getCause() != error)
            redacted.initCause(redactedDiagnosticException(error.getCause(), seen));
        for (final Throwable suppressed : error.getSuppressed())
            redacted.addSuppressed(redactedDiagnosticException(suppressed, seen));
        return redacted;
    }

    private void handleNotificationServiceEvent(final SapNotificationServiceCodec.Message message) {
        final NotificationRecord known = findNotificationBySequence(message.getSequence());
        LOG.info("Gear S3 NotificationService incoming event: type={} sequence={} known={}", message.getType(), message.getSequence(), known != null);
        if (message.getType() == SapNotificationServiceCodec.TYPE_ADDITIONAL_DATA) {
            if (known == null) {
                LOG.warn("Gear S3 NotificationService additional-info request for unknown sequence={}; ignoring", message.getSequence());
                return;
            }
            // Type 3 requests additional information for the notification sequence, which
            // re-forwards the stored notification using the same notification sequence.
            // Reuse the original ext-action IDs when re-forwarding the stored notification.
            sendNotificationMessage(known.message,
                    "ADDITIONAL_INFO_RESPONSE", known.id, NOTIFICATION_ATTRIBUTE_PROFILE);
            return;
        }
        if (message.getType() == SapNotificationServiceCodec.TYPE_DELETE) {
            if (known != null) sendNotificationControl(known.id, GBDeviceEventNotificationControl.Event.DISMISS, null);
            return;
        }
        if (message.getType() == SapNotificationServiceCodec.TYPE_READ) {
            LOG.info("Gear S3 NotificationService read/view event received; no Gadgetbridge read callback exists");
            return;
        }
        if (message.getType() == SapNotificationServiceCodec.TYPE_CLEAR_ALL || message.getType() == SapNotificationServiceCodec.TYPE_READ_ALL) {
            notificationDelta.clear();
            sendNotificationControl(-1, GBDeviceEventNotificationControl.Event.DISMISS_ALL, null);
            return;
        }
        if (message.getType() == SapNotificationServiceCodec.TYPE_OPEN) {
            if (known != null) sendNotificationControl(known.id, GBDeviceEventNotificationControl.Event.OPEN, null);
            return;
        }
        if (message.getType() == SapNotificationServiceCodec.TYPE_ACTION) {
            final SapNotificationServiceCodec.Attribute action = message.findAttribute(SapNotificationServiceCodec.ATTR_ACTION_REQUEST);
            final SapNotificationServiceCodec.Attribute wearableAction = message.findAttribute(SapNotificationServiceCodec.ATTR_WEAR_ACTION_REQUEST);
            if (action == null && wearableAction == null) {
                LOG.warn("Gear S3 action request has no action identity: sequence={}", message.getSequence());
                return; // No action ID exists to correlate a protocol result; never invent one.
            }
            handleSamsungAction(known, message, wearableAction != null ? wearableAction : action, wearableAction != null);
            return;
        }
        LOG.debug("Gear S3 NotificationService unsupported inbound event type={} attributes={}", message.getType(), message.getAttributes().size());
    }

    private void sendNotificationControl(final int id, final GBDeviceEventNotificationControl.Event event, final String reply) {
        final GBDeviceEventNotificationControl control = new GBDeviceEventNotificationControl();
        control.handle = id;
        control.event = event;
        control.reply = reply;
        evaluateGBDeviceEvent(control);
    }

    /**
     * Samsung's protocol-2 attr 17 is JSON {action_id,results:[{key,value},...]}.  The
     * stable Gadgetbridge action handle reaches NotificationListener's existing PendingIntent /
     * RemoteInput implementation; this class never manufactures an app Intent.
     */
    private void handleSamsungAction(final NotificationRecord sequenceRecord, final SapNotificationServiceCodec.Message message,
                                     final SapNotificationServiceCodec.Attribute attribute, final boolean wearable) {
        try {
            final JSONObject request = new JSONObject(attribute.getUtf8());
            final int actionId = request.optInt("action_id", -1);
            NotificationRecord resolved = sequenceRecord;
            NotificationSpec.Action chosen = null;
            String mode = "CURRENT_SEQUENCE";
            String reason = null;
            final String requestedWindow = request.optString("window_id", "");
            if (wearable && sequenceRecord == null) {
                mode = "STALE_SEQUENCE_CURRENT_WINDOW";
                resolved = requestedWindow.isEmpty() ? null : notifications.get(requestedWindow);
                if (resolved == null) reason = requestedWindow.isEmpty() ? "MISSING_WINDOW" : "WINDOW_NOT_ACTIVE";
                else {
                    // A historical sequence may never authorize a different conversation, even
                    // if a numeric action ID collides. Reject ambiguous sequence ownership too.
                    for (final NotificationRecord other : notifications.values()) {
                        if (other != resolved && other.wearableActions.hasSequence(message.getSequence())) {
                            reason = "SEQUENCE_WINDOW_MISMATCH";
                            break;
                        }
                    }
                    if (reason == null) {
                        chosen = resolved.wearableActions.resolveStale(message.getSequence(), actionId, requestedWindow);
                        if (chosen == null) reason = !resolved.wearableActions.hasSequence(message.getSequence())
                                ? "SEQUENCE_NOT_RETAINED" : "ACTION_NOT_CONTINUOUS_OR_AMBIGUOUS";
                    }
                }
            } else if (resolved != null) {
                chosen = wearable ? resolved.wearableActions.resolve(actionId, request.optString("window_id", resolved.windowId))
                        : findSamsungAction(resolved.spec, actionId);
                if (chosen == null) reason = "ACTION_OR_WINDOW_MISMATCH";
            } else reason = "SEQUENCE_NOT_CURRENT";
            final NotificationRecord record = resolved;
            final NotificationSpec.Action selected = chosen;
            LOG.debug("Gear S3 action resolution: incomingSequence={} currentSequence={} actionId={} mode={} selected={} reason={}",
                    message.getSequence(), record == null ? -1 : record.sequence, actionId, mode,
                    selected != null && selected.getHandle() != 0, reason == null ? "OK" : reason);
            if (record == null) {
                rejectNotificationAction(message, actionId, -1, reason);
                return;
            }
            if (selected == null || selected.getHandle() == 0) {
                LOG.warn("Gear S3 NotificationService action rejected: sequence={} windowId={} actionId={}", message.getSequence(), record.windowId, actionId);
                rejectNotificationAction(message, actionId, record.id, reason == null ? "NO_ANDROID_EXECUTOR" : reason);
                return;
            }
            String reply = null;
            final JSONArray results = request.optJSONArray("results");
            if (results != null && results.length() > 0) {
                final JSONObject first = results.optJSONObject(0);
                if (first != null && selected.getRemoteInputKey() != null && selected.getRemoteInputKey().equals(first.optString("key", null))) reply = first.optString("value", null);
            }
            if (selected.isReply() && reply == null) {
                LOG.warn("Gear S3 NotificationService reply rejected: missing matching RemoteInput result sequence={} actionId={}", message.getSequence(), actionId);
                sendNotificationMessage(SapNotificationServiceCodec.actionAck(message.getSequence(), actionId, false), "ACTION_NACK", record.id, NOTIFICATION_ATTRIBUTE_PROFILE);
                return;
            }
            final GBDeviceEventNotificationControl control = new GBDeviceEventNotificationControl();
            control.handle = selected.getHandle();
            control.event = selected.isReply() ? GBDeviceEventNotificationControl.Event.REPLY : GBDeviceEventNotificationControl.Event.UNKNOWN;
            control.reply = reply;
            if (control.event == GBDeviceEventNotificationControl.Event.UNKNOWN) {
                rejectNotificationAction(message, actionId, record.id, "UNSUPPORTED_ACTION_KIND");
                return;
            }
            LOG.debug("Gear S3 NotificationService reply RX: sequence={} gearActionId={} androidHandle={} textLength={}",
                    message.getSequence(), actionId, selected.getHandle(), reply == null ? 0 : reply.length());
            final long actionGeneration = sapDispatchGeneration;
            control.replyResultReceiver = new ResultReceiver(notificationReliableAckHandler) {
                @Override protected void onReceiveResult(final int resultCode, final android.os.Bundle resultData) {
                    synchronized (GearS3DeviceSupport.this) {
                        if (sapDispatchDisposed || actionGeneration != sapDispatchGeneration) {
                            return;
                        }
                        final boolean success = resultCode == 1;
                        sendNotificationMessage(SapNotificationServiceCodec.actionAck(message.getSequence(), actionId, success),
                                success ? "ACTION_ACK" : "ACTION_NACK", record.id, NOTIFICATION_ATTRIBUTE_PROFILE);
                        LOG.info("Gear S3 NotificationService reply dispatchResult={}", success ? "success" : "failure");
                    }
                }
            };
            evaluateGBDeviceEvent(control);
        } catch (final JSONException e) {
            LOG.warn("Gear S3 malformed action request: sequence={}", message.getSequence(), redactedDiagnosticException(e));
        }
    }

    private void rejectNotificationAction(final SapNotificationServiceCodec.Message request, final int actionId,
                                          final int notificationId, final String reason) {
        LOG.debug("Gear S3 action rejected: sequence={} actionId={} reason={}", request.getSequence(), actionId, reason);
        if (actionId >= 0) sendNotificationMessage(SapNotificationServiceCodec.actionAck(request.getSequence(), actionId, false),
                "ACTION_NACK", notificationId, NOTIFICATION_ATTRIBUTE_PROFILE);
    }

    private void sendCapabilityReply(final SapCapabilityExchange.State previousState,
                                     final SapCapabilityExchange.Reply reply) {
        final byte[] encoded = SapPostAuthRecordCodec.encode(reply.getRecord());
        final SapCapexCodec.Query query = reply.getQuery();
        final SapCapexCodec.Response response = reply.getResponse();
        LOG.debug("Gear S3 CapEx query: type={} queryType={} checksum=0x{} profiles={} values={}",
                SapCapexCodec.TYPE_QUERY, query.getQueryType(), Long.toHexString(query.getChecksum()),
                query.getProfiles().size(), query.getProfiles());
        if (query.getProfiles().contains(SapBcmServiceRegistration.PROFILE)) {
            LOG.debug("Gear S3 Gear queried local bcmservice capability");
        }
        LOG.debug("Gear S3 CapEx persistent profiles={} local checksum=0x{} checksumCurrent={}",
                reply.getPersistentProfiles(), Long.toHexString(response.getChecksum()), reply.isChecksumCurrent());
        LOG.debug("Gear S3 sending CapEx response: agents={} services={} wire={}",
                response.getAgents().size(), countServices(response.getAgents()), GB.hexdump(encoded));
        createTransactionBuilder("gearS3CapexResponse").write(encoded).queue();
        if (query.getProfiles().contains(SapCalendarServiceRegistration.PROFILE)) {
            LOG.info("Gear S3 Gear queried local calendar capability");
            if (containsCalendarDescriptor(response)) {
                calendarProfileExposed = true;
                LOG.info("Gear S3 real calendar descriptor advertised: agent=0x{} component=0x{} ASP=0x{} role=0x{}",
                        Integer.toHexString(calendarServiceRegistration.getAgentUuid()),
                        Integer.toHexString(calendarServiceRegistration.getComponentId()),
                        Integer.toHexString(SapCalendarServiceRegistration.ASP_VERSION),
                        Integer.toHexString(SapCalendarServiceRegistration.ROLE_FLAGS));
            } else if (!reply.isChecksumCurrent()) {
                LOG.warn("Gear S3 calendar query did not advertise the registered local provider descriptor");
            }
        }
        LOG.debug("Gear S3 CapEx state transition: {} -> {}", previousState, capabilityExchange.getState());
        maybeStartBcmServiceConnection(reply);
        maybeStartPedometerServiceConnection(reply);
        maybeStartCalendarServiceConnection("CapEx/provider ready");
        maybeStartContactsServiceConnection(reply);
    }

    private boolean containsCalendarDescriptor(final SapCapexCodec.Response response) {
        for (final SapAgentDescriptor agent : response.getAgents()) for (final SapServiceDescriptor service : agent.getServices()) {
            if (calendarServiceRegistration.matchesLocalDescriptor(agent, service)) return true;
        }
        return false;
    }

    /** Initiate the calendar connection after service discovery. */
    private void maybeStartCalendarServiceConnection(final String reason) {
        if (!calendarProfileExposed || calendarServiceConnection == null
                || calendarServiceConnection.getState() != SapCalendarServiceConnection.State.IDLE
                || calendarServiceRegistration.getRemoteConsumer() == null
                || hostManagerApplication == null
                || hostManagerApplication.getState() != SapHostManagerApplication.State.HOST_MANAGER_READY) {
            LOG.debug("Gear S3 calendar phone->watch create skipped: reason={} profileExposed={} state={} remoteKnown={} hostReady={}",
                    reason, calendarProfileExposed,
                    calendarServiceConnection == null ? "null" : calendarServiceConnection.getState(),
                    calendarServiceRegistration != null && calendarServiceRegistration.getRemoteConsumer() != null,
                    hostManagerApplication != null
                            && hostManagerApplication.getState() == SapHostManagerApplication.State.HOST_MANAGER_READY);
            return;
        }
        LOG.info("Gear S3 calendar phone->watch create pending table before TX: reason={} sessions={} state={} localComponent=0x{} remoteComponent=0x{}",
                reason, usedApplicationSessions(), calendarServiceConnection.getState(),
                Integer.toHexString(calendarServiceRegistration.getComponentId()),
                Integer.toHexString(calendarServiceRegistration.getRemoteConsumer().getComponentId()));
        final SapServiceConnectionRequest request = calendarServiceConnection.createRequest(usedApplicationSessions());
        sendCalendarControl(SapServiceConnectionCodec.encode(request), "gearS3CalendarServiceRequest");
        final SapServiceConnectionRequest.Session proposed = request.getSessions().get(0);
        LOG.info("Gear S3 calendar service request TX: direction=phone->watch localComponent=0x{} remoteComponent=0x{} session={} channel={} qos={}/{}/{} payloadType={} pendingTableAfter={}",
                Integer.toHexString(calendarServiceRegistration.getComponentId()),
                Integer.toHexString(calendarServiceRegistration.getRemoteConsumer().getComponentId()),
                proposed.getSessionId(), proposed.getChannelId(), proposed.getQos().getType(),
                proposed.getQos().getDataRate(), proposed.getQos().getClassType(), proposed.getPayloadType(),
                usedApplicationSessions());
        calendarHandler.removeCallbacks(calendarCreateTimeoutRunnable);
        calendarHandler.postDelayed(calendarCreateTimeoutRunnable, 10_000L);
    }

    private static String calendarStatusName(final int status) {
        if (status == SapServiceConnectionResponse.STATUS_SUCCESS) return "ACCEPTED";
        if (status == SapServiceConnectionResponse.STATUS_REJECTED) return "REJECTED";
        if (status == SapServiceConnectionResponse.STATUS_SESSION_COLLISION) return "SESSION_COLLISION";
        if (status == 4) return "TIMEOUT";
        if (status == 5) return "ALREADY_CONNECTED";
        return "UNKNOWN";
    }

    private void maybeStartContactsServiceConnection(final SapCapabilityExchange.Reply reply) {
        if (capabilityExchange.getState() != SapCapabilityExchange.State.WAIT_FOLLOW_UP
                || contactsService == null || !reply.getQuery().getProfiles().contains(GearContactsService.CONTACTS)) return;
        boolean advertised = false;
        for (final SapAgentDescriptor agent : reply.getResponse().getAgents()) for (final SapServiceDescriptor service : agent.getServices()) {
            if (GearContactsService.CONTACTS.equals(service.getProfileId())) advertised = true;
        }
        if (!advertised) return;
        final SapServiceConnectionRequest request = contactsService.startContacts(usedApplicationSessions());
        if (request != null) sendContactsRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(request)));
    }

    private Set<Integer> usedApplicationSessions() {
        final Set<Integer> used = new HashSet<>();
        if (hostManagerAcceptor != null && hostManagerAcceptor.getApplicationSessionId() >= 1) used.add(hostManagerAcceptor.getApplicationSessionId());
        if (bcmServiceConnector != null && bcmServiceConnector.getApplicationSessionId() >= 1) used.add(bcmServiceConnector.getApplicationSessionId());
        if (bcmServiceConnector != null && bcmServiceConnector.getPendingSessionId() >= 1) used.add(bcmServiceConnector.getPendingSessionId());
        if (pedometerServiceConnector != null && pedometerServiceConnector.getApplicationSessionId() >= 1) used.add(pedometerServiceConnector.getApplicationSessionId());
        if (notificationServiceAcceptor != null && notificationServiceAcceptor.getApplicationSessionId() >= 1) used.add(notificationServiceAcceptor.getApplicationSessionId());
        if (musicServiceAcceptor != null && musicServiceAcceptor.getApplicationSessionId() >= 1) used.add(musicServiceAcceptor.getApplicationSessionId());
        if (weatherServiceAcceptor != null && weatherServiceAcceptor.getApplicationSessionId() >= 1) used.add(weatherServiceAcceptor.getApplicationSessionId());
        if (weatherServiceConnector != null && weatherServiceConnector.getSessionId() >= 1) used.add(weatherServiceConnector.getSessionId());
        if (calendarServiceConnection != null && calendarServiceConnection.getSessionId() >= 1) used.add(calendarServiceConnection.getSessionId());
        if (calendarServiceConnection != null && calendarServiceConnection.getPendingPhoneSessionId() >= 1) {
            used.add(calendarServiceConnection.getPendingPhoneSessionId());
        }
        if (findMyPhoneService != null && findMyPhoneService.session() >= 1) used.add(findMyPhoneService.session());
        if (contactsService != null) contactsService.addUsedSessions(used);
        return used;
    }

    private void handleCalendarServiceConnectionRequest(final SapServiceConnectionRequest request) {
        final SapServiceConnectionRequest.Session proposed = request.getSessions().size() == 1
                ? request.getSessions().get(0) : null;
        LOG.info("Gear S3 calendar service request RX: direction=watch->phone localComponent=0x{} remoteComponent=0x{} requestedAcceptor=0x{} requestedInitiator=0x{} proposedSession={} channel={} qos={}/{}/{} payloadType={} state={} pendingPhoneSession={} simultaneous={} sessionMapBefore={}",
                Integer.toHexString(calendarServiceRegistration.getComponentId()),
                calendarServiceRegistration.getRemoteConsumer() == null ? "unknown"
                        : Integer.toHexString(calendarServiceRegistration.getRemoteConsumer().getComponentId()),
                Integer.toHexString(request.getAcceptorComponentId()),
                Integer.toHexString(request.getInitiatorComponentId()),
                proposed == null ? -1 : proposed.getSessionId(), proposed == null ? -1 : proposed.getChannelId(),
                proposed == null ? -1 : proposed.getQos().getType(),
                proposed == null ? -1 : proposed.getQos().getDataRate(),
                proposed == null ? -1 : proposed.getQos().getClassType(),
                proposed == null ? -1 : proposed.getPayloadType(), calendarServiceConnection.getState(),
                calendarServiceConnection.getPendingPhoneSessionId(),
                calendarServiceConnection.getState() == SapCalendarServiceConnection.State.PENDING,
                usedApplicationSessions());
        final SapServiceConnectionResponse response = calendarServiceConnection.handleRequest(request, usedApplicationSessions());
        sendCalendarControl(SapServiceConnectionCodec.encode(response), "gearS3CalendarServiceResponse");
        LOG.info("Gear S3 calendar watch->phone create response TX: status={}({}) decision={} responseSessionIds={} activeSession={} pendingPhoneSession={} sessionMapAfter={}",
                response.getStatus(), calendarStatusName(response.getStatus()), calendarServiceConnection.getLastDecision(),
                response.getSessionIds(), calendarServiceConnection.getSessionId(),
                calendarServiceConnection.getPendingPhoneSessionId(), usedApplicationSessions());
        if (response.getStatus() == SapServiceConnectionResponse.STATUS_SUCCESS) {
            if (calendarServiceConnection.getPendingPhoneSessionId() < 1) {
                calendarHandler.removeCallbacks(calendarCreateTimeoutRunnable);
            }
            onCalendarSessionEstablished();
        }
    }

    private void sendCalendarControl(final byte[] body, final String transaction) {
        final SapPostAuthRecord record = new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), body);
        createTransactionBuilder(transaction).write(SapPostAuthRecordCodec.encode(record)).queue();
    }

    private void restoreCalendarRanges() {
        synchronized (calendarLock) { restoreCalendarRangesLocked(); }
    }

    private void restoreCalendarRangesLocked() {
        if (calendarStateRestored) return;
        calendarRangeStore = new SapCalendarRangeStore(
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()));
        calendarMirrorStore = new SapCalendarMirrorStore(
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()));
        final SapCalendarRangeStore.State state = calendarRangeStore.load();
        calendarWindows.restore(state);
        calendarSequence = state.sequence;
        calendarInitialSequenceRequested = state.sequenceSeen;
        calendarRefreshQueued = state.refreshPending || state.shouldReconcileOnConnect();
        calendarRefreshPendingDurable = state.refreshPending;
        final String mirrorRestore = calendarMirrorStore.restore(calendarSnapshot, TimeZone.getDefault().getID());
        LOG.info("Gear S3 calendar persisted mirror loaded: trusted={} events={} sequence={} reason={}",
                calendarSnapshot.hasBaseline(), calendarSnapshot.getEvents().size(), calendarSequence, mirrorRestore);
        if (!calendarRanges.isEmpty()) {
            final SapCalendarCodec.RequestRange last = calendarRanges.get(calendarRanges.size() - 1);
            calendarMaxTextLength = last.maxTextLength;
            calendarReminders = last.reminders;
        }
        calendarStateRestored = true;
        LOG.info("Gear S3 persisted calendar ranges loaded: count={} sequence={} initialSequenceSeen={} refreshPending={} replaceOnNextRange={}",
                calendarRanges.size(), calendarSequence, calendarInitialSequenceRequested,
                state.refreshPending, state.replaceOnNextRange);
        for (int i = 0; i < calendarRanges.size(); i++) {
            LOG.info("Gear S3 persisted range: start={} end={} zone={}",
                    calendarRanges.get(i).start, calendarRanges.get(i).end, calendarRangeZones.get(i));
        }
        if (calendarRanges.isEmpty()) LOG.info("Gear S3 no persisted/observed Gear window available");
    }

    /** Debug receiver lookup only; it never opens or creates a Calendar SAP session. */
    public static GearS3DeviceSupport calendarDebugSupport(final String address) {
        if (!BuildConfig.DEBUG || address == null) return null;
        final WeakReference<GearS3DeviceSupport> reference = CALENDAR_DEBUG_SUPPORTS.get(address.toUpperCase(Locale.ROOT));
        return reference == null ? null : reference.get();
    }

    private void requireCalendarDebugIdle() {
        requireCalendarDebugIdle(false);
    }

    private void requireCalendarDebugIdle(final boolean emptyWatch) {
        final boolean ready = BuildConfig.DEBUG && calendarStateRestored && calendarMirrorStore != null
                && calendarRangeStore != null && calendarServiceConnection != null;
        final SapCalendarServiceConnection.State connection = ready
                ? calendarServiceConnection.getState() : null;
        final CalendarRoute route = calendarRoute;
        final boolean routeMatches = route != null && ready
                && route.session == calendarServiceConnection.getSessionId()
                && route.generation == sapDispatchGeneration;
        new SapCalendarBootstrap.Quiescence(ready, connection, route != null, routeMatches,
                !calendarSnapshot.hasBaseline(),
                calendarUpdatePending || calendarPendingDifference != null,
                calendarSnapshot.isCollectingFullSync() || calendarWindows.isReplacementPending(),
                calendarIngressPending.get() != 0,
                calendarReliableRxSession.getQueuedCount() != 0
                        || calendarReliableRxSession.hasPendingBlockAck(),
                calendarRefreshRunning.get(), calendarSequence == 0 && calendarInitialSequenceRequested)
                .requireIdle();
        final String storedRefusal = emptyWatch ? calendarMirrorStore.debugEmptyBootstrapRefusal()
                : calendarMirrorStore.debugBootstrapRefusal();
        if (storedRefusal != null) throw new SapCalendarBootstrap.Refusal(storedRefusal);
        if (!GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, false)
                || getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SapCalendarBootstrap.Refusal("sync-disabled-or-permission-denied");
        }
    }

    private SapCalendarBootstrap.Plan inspectCalendarDebugLocked(final int expectedRangeCount) throws JSONException {
        requireCalendarDebugIdle();
        final CalendarManager manager = new CalendarManager(getContext(), getDevice().getAddress());
        final SapCalendarBootstrap.Plan plan;
        try {
            plan = SapCalendarBootstrap.inspect(
                    GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()),
                    TimeZone.getDefault(), manager::getCalendarEventList, expectedRangeCount);
        } catch (final SapCalendarBootstrap.Refusal e) {
            throw e;
        } catch (final JSONException | RuntimeException e) {
            throw new SapCalendarBootstrap.Refusal("android-query-or-content-unverifiable");
        }
        // CalendarManager tolerates a failed reminder subquery for ordinary synchronization.
        // This one-time proof needs a separate successful read of every selected event.
        final Set<Long> checked = new HashSet<>();
        for (final CalendarEvent event : plan.events) {
            if (!checked.add(event.getEventId())) continue;
            try (android.database.Cursor reminders = getContext().getContentResolver().query(
                    CalendarContract.Reminders.CONTENT_URI,
                    new String[]{CalendarContract.Reminders._ID},
                    CalendarContract.Reminders.EVENT_ID + " = ?",
                    new String[]{Long.toString(event.getEventId())}, null)) {
                if (reminders == null || reminders.moveToFirst()) {
                    throw new SapCalendarBootstrap.Refusal("reminders-not-verifiable");
                }
            } catch (final SapCalendarBootstrap.Refusal e) {
                throw e;
            } catch (final RuntimeException e) {
                throw new SapCalendarBootstrap.Refusal("reminders-not-verifiable");
            }
        }
        return plan;
    }

    /** Read-only diagnostic for the exported debug receiver. No transaction is created. */
    public SapCalendarBootstrap.Plan inspectCalendarDebug(final int expectedRangeCount) throws JSONException {
        synchronized (calendarLock) { return inspectCalendarDebugLocked(expectedRangeCount); }
    }

    /** Separate recovery for a freshly verified empty watch. No event read or SAP send. */
    public SapCalendarBootstrap.Plan inspectEmptyCalendarDebug() throws JSONException {
        synchronized (calendarLock) {
            requireCalendarDebugIdle(true);
            return SapCalendarBootstrap.inspectEmpty(
                    GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()), TimeZone.getDefault());
        }
    }

    public void bootstrapEmptyCalendarDebug(final String databaseSha256, final int watchCount,
                                           final long watchSequence, final long verifiedAt,
                                           final String inspectedRanges, final String inspectedZone) throws JSONException {
        synchronized (calendarLock) {
            final CalendarRoute route = calendarRoute;
            final long generation = sapDispatchGeneration;
            final int txBefore = calendarReliableTxSequence;
            SapCalendarBootstrap.requireEmptyWatchProof(databaseSha256, watchCount, watchSequence,
                    verifiedAt, System.currentTimeMillis());
            final SapCalendarBootstrap.Plan plan = inspectEmptyCalendarDebug();
            if (!plan.ranges.equals(inspectedRanges) || !plan.phoneZone.equals(inspectedZone)) {
                throw new SapCalendarBootstrap.Refusal("range-options-mismatch");
            }
            requireCalendarDebugIdle(true);
            if (calendarRoute != route || sapDispatchGeneration != generation) {
                throw new SapCalendarBootstrap.Refusal("session-changed");
            }
            if (!calendarMirrorStore.debugBootstrapEmpty(databaseSha256, plan.ranges, plan.phoneZone)) {
                throw new SapCalendarBootstrap.Refusal("checkpoint-refused");
            }
            final String restored = calendarMirrorStore.restore(calendarSnapshot, plan.phoneZone);
            if (!"trusted-debug-empty-bootstrap".equals(restored)) {
                calendarMirrorStore.invalidate("debug-empty-bootstrap-restore-failed");
                calendarSnapshot.clear();
                throw new SapCalendarBootstrap.Refusal("checkpoint-restore-failed");
            }
            try {
                SapCalendarBootstrap.requireNoSapTx(txBefore, calendarReliableTxSequence);
            } catch (final SapCalendarBootstrap.Refusal e) {
                calendarMirrorStore.invalidate("debug-empty-bootstrap-sap-tx");
                calendarSnapshot.clear();
                throw e;
            }
            // Leave reconciliation pending but do not start it from the recovery action.
            // The next observer notification or ordinary reconnect uses normal update/ACK handling.
            calendarHandler.removeCallbacks(calendarRefreshRunnable);
            calendarRefreshQueued = true;
            LOG.info("Gear S3 calendar debug empty mirror bootstrap: trusted=true events=0 sequence=0 SAP_TX=0");
        }
    }

    /** Explicit debug-only checkpoint; called after independent watch-DB fingerprint verification. */
    public boolean bootstrapCalendarDebug(final String expectedFingerprint, final int expectedCount,
                                          final String inspectedRanges, final String inspectedZone,
                                          final int expectedRangeCount) throws JSONException {
        synchronized (calendarLock) {
            final CalendarRoute inspectedRoute = calendarRoute;
            final long inspectedGeneration = sapDispatchGeneration;
            final int txBefore = calendarReliableTxSequence;
            final SapCalendarBootstrap.Plan current = inspectCalendarDebugLocked(expectedRangeCount);
            if (expectedCount != current.count()) throw new SapCalendarBootstrap.Refusal("count-mismatch");
            if (!current.fingerprint.equals(expectedFingerprint)) throw new SapCalendarBootstrap.Refusal("fingerprint-mismatch");
            if (!current.ranges.equals(inspectedRanges) || !current.phoneZone.equals(inspectedZone)) {
                throw new SapCalendarBootstrap.Refusal("range-options-mismatch");
            }
            // Requery just before the preference commit. A changed Android event cannot use
            // a fingerprint issued for the earlier read-only diagnostic.
            final SapCalendarBootstrap.Plan confirmed = inspectCalendarDebugLocked(expectedRangeCount);
            if (confirmed.count() != current.count() || !confirmed.fingerprint.equals(current.fingerprint)) {
                throw new SapCalendarBootstrap.Refusal("android-snapshot-changed");
            }
            if (!confirmed.ranges.equals(current.ranges) || !confirmed.phoneZone.equals(current.phoneZone)) {
                throw new SapCalendarBootstrap.Refusal("range-options-mismatch");
            }
            requireCalendarDebugIdle();
            if (calendarRoute != inspectedRoute || sapDispatchGeneration != inspectedGeneration) {
                throw new SapCalendarBootstrap.Refusal("session-changed");
            }
            if (!calendarMirrorStore.debugBootstrap(confirmed.events, 0, confirmed.phoneZone,
                    expectedFingerprint, confirmed.fingerprint, expectedCount, confirmed.ranges)) {
                throw new SapCalendarBootstrap.Refusal("checkpoint-refused");
            }
            final String restored = calendarMirrorStore.restore(calendarSnapshot, confirmed.phoneZone);
            if (!"trusted-debug-bootstrap".equals(restored)) {
                calendarMirrorStore.invalidate("debug-bootstrap-restore-failed");
                calendarSnapshot.clear();
                throw new SapCalendarBootstrap.Refusal("checkpoint-restore-failed");
            }
            try {
                SapCalendarBootstrap.requireNoSapTx(txBefore, calendarReliableTxSequence);
            } catch (final SapCalendarBootstrap.Refusal e) {
                calendarMirrorStore.invalidate("debug-bootstrap-sap-tx");
                calendarSnapshot.clear();
                throw e;
            }
            calendarRefreshPendingDurable = false;
            calendarRefreshQueued = false;
            calendarHandler.removeCallbacks(calendarRefreshRunnable);
            LOG.info("Gear S3 calendar debug mirror bootstrap: trusted=true events={} sequence=0 fingerprint={}",
                    confirmed.count(), confirmed.fingerprint);
            return true;
        }
    }

    private boolean persistCalendarRanges(final String requestId) {
        if (calendarRangeStore == null) return false;
        final boolean saved = calendarRangeStore.saveRanges(calendarRanges, calendarRangeZones);
        LOG.info("Gear S3 Gear range persisted/updated: request={} count={} saved={}",
                requestId, calendarRanges.size(), saved);
        return saved;
    }

    private boolean markCalendarMirrorUntrusted(final String reason) {
        final boolean saved = calendarMirrorStore != null && calendarMirrorStore.invalidate(reason);
        LOG.info("Gear S3 calendar durable mirror invalidated: reason={} saved={}", reason, saved);
        if (!saved) {
            calendarSnapshot.clear();
            LOG.warn("Gear S3 calendar mutation suppressed: unable to persist mirror safety marker");
        }
        return saved;
    }

    private boolean commitCalendarMirror(final String boundary) {
        final boolean saved = calendarMirrorStore != null && calendarMirrorStore.commit(
                calendarSnapshot, calendarSequence, TimeZone.getDefault().getID());
        LOG.info("Gear S3 calendar mirror checkpoint: boundary={} saved={} events={} sequence={}",
                boundary, saved, calendarSnapshot.getEvents().size(), calendarSequence);
        if (!saved) {
            // Never continue against an in-memory mirror newer than a failed durable checkpoint.
            markCalendarMirrorUntrusted("checkpoint-failed");
            calendarSnapshot.clear();
        }
        return saved;
    }

    private void setCalendarRefreshPending(final boolean pending) {
        calendarRefreshPendingDurable = pending;
        if (calendarRangeStore != null && !calendarRangeStore.setRefreshPending(pending)) {
            LOG.warn("Gear S3 calendar pending refresh could not be persisted: pending={}", pending);
        }
    }

    private void onCalendarSessionEstablished() {
        synchronized (calendarLock) { onCalendarSessionEstablishedLocked(); }
    }

    private void onCalendarSessionEstablishedLocked() {
        calendarRoute = new CalendarRoute(calendarServiceConnection.getSessionId(), sapDispatchGeneration);
        calendarHandler.removeCallbacks(calendarUpdateAckTimeoutRunnable);
        calendarReliableRxSession.reset();
        calendarReliableTxSequence = 1;
        if (calendarUpdatePending) {
            calendarSnapshot.clear(); // Delivery may have happened before the lost application ACK.
            calendarRefreshQueued = true;
            setCalendarRefreshPending(true);
        }
        calendarUpdatePending = false;
        calendarPendingDifference = null;
        if (!calendarObserverRegistered) {
            try {
                getContext().getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver);
                calendarObserverRegistered = true;
            } catch (final RuntimeException e) {
                LOG.warn("Gear S3 calendar observer unavailable; initial range requests still work", e);
            }
        }
        LOG.info("Gear S3 calendar API101 session established: session={} knownRanges={} sequence={} initialSequenceSeen={}",
                calendarServiceConnection.getSessionId(), calendarRanges.size(), calendarSequence,
                calendarInitialSequenceRequested);
        LOG.info("Gear S3 calendar mirror baseline: valid={} (unknown is not empty)", calendarSnapshot.hasBaseline());
        LOG.info("Gear S3 calendar source settings: syncEnabled={} readCalendarGranted={}",
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                        .getBoolean(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, false),
                getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED);
        if (calendarRefreshPendingDurable && !calendarRanges.isEmpty()) {
            LOG.info("Gear S3 calendar pending refresh resumed after API101 establishment: knownRanges={}", calendarRanges.size());
        }
        if (calendarInitialSequenceRequested && !calendarRanges.isEmpty()) {
            calendarRefreshQueued = true; // Reconcile changes missed while the SAP session was down.
            scheduleCalendarRefresh();
        }
    }

    private void handleCalendarApplicationRecord(final SapPostAuthRecord record) {
        try {
            if (record.getHeader().getFrameType() == 1) {
                final byte[] body = record.getBody();
                if (body.length == 3 && (body[0] & 0xff) == SapNotificationServiceCodec.RELIABLE_CONTROL_BLOCK_ACK) {
                    final int sequence = ((body[1] & 0xff) << 8) | (body[2] & 0xff);
                    LOG.info("Gear S3 calendar reliable block ACK RX: sequence={} nextTx={} expectedRx={} receivedAtMs={}",
                            sequence, calendarReliableTxSequence, calendarReliableRxSession.getExpectedSequence(),
                            android.os.SystemClock.elapsedRealtime());
                } else {
                    LOG.warn("Gear S3 calendar unsupported reliable control: bytes={}", body.length);
                }
                return;
            }
            record.getHeader().requireUnfragmentedDataFrame();
            final SapCalendarCodec.Frame frame = SapCalendarCodec.decodeFrame(record.getBody());
            final int expected = calendarReliableRxSession.getExpectedSequence();
            final SapCalendarReliableReceiver.Result result = calendarReliableRxSession.receive(frame);
            LOG.info("Gear S3 calendar reliable RX: wireSeq={} expectedRx={} nextTx={} decision={} queued={} ready={}",
                    frame.sequence, expected, calendarReliableTxSequence, result.status,
                    calendarReliableRxSession.getQueuedCount(), result.ready.size());
            if (result.status == SapCalendarReliableReceiver.Status.DUPLICATE) {
                sendCalendarReliableAck(calendarReliableRxSession.getLastValidSequence());
                return; // Already delivered, never an application replay.
            }
            if (result.status != SapCalendarReliableReceiver.Status.ACCEPT) {
                if (result.status == SapCalendarReliableReceiver.Status.OVERFLOW) {
                    LOG.warn("Gear S3 calendar reliable frame dropped: bounded gap buffer full wireSeq={} expectedRx={}", frame.sequence, expected);
                }
                sendCalendarReliableNak(expected); // Request the missing frame; preserve forward frames.
                return;
            }
            scheduleCalendarReliableAck();
            for (final SapCalendarCodec.Frame ready : result.ready) {
                try {
                    deliverCalendarFrame(ready);
                } catch (final RuntimeException | JSONException e) {
                    if ("calendar-listwithoption-req".equals(ready.messageId)
                            || "calendar-change-req".equals(ready.messageId)
                            || "calendar-sequence-req".equals(ready.messageId)
                            || "calendar-datechanged-req".equals(ready.messageId)) {
                        markCalendarMirrorUntrusted("exchange-failed");
                        calendarSnapshot.clear();
                    }
                    LOG.warn("Gear S3 calendar application delivery rejected: wireSeq={} type={}", ready.sequence, e.getClass().getSimpleName());
                }
            }
        } catch (final RuntimeException | JSONException e) {
            LOG.warn("Gear S3 calendar message rejected: type={}", e.getClass().getSimpleName());
        }
    }

    private void deliverCalendarFrame(final SapCalendarCodec.Frame frame) throws JSONException {
            LOG.info("Gear S3 calendar RX: msgId={} sequence={} deliveredAtMs={}",
                    frame.messageId, frame.sequence, android.os.SystemClock.elapsedRealtime());
            switch (frame.messageId) {
                case "calendar-firstdayofweek-req":
                    sendCalendarPayload(SapCalendarCodec.firstDayResponse(Calendar.getInstance().getFirstDayOfWeek()), "calendar-firstdayofweek-rsp");
                    break;
                case "calendar-home-timezone-req":
                    sendCalendarPayload(SapCalendarCodec.homeTimezoneResponse(), "calendar-home-timezone-rsp");
                    break;
                case "calendar-change-req":
                    LOG.info("Gear S3 calendar full-sync reset requested: value={} supportSync={}",
                            frame.payload.optString("value"), frame.payload.optString("supportSync"));
                    if (!markCalendarMirrorUntrusted("full-sync")) break;
                    calendarSnapshot.beginFullSync();
                    calendarWindows.requestReplacement();
                    LOG.info("Gear S3 calendar replacement pending: retaining genuine windows count={}", calendarRanges.size());
                    calendarSequence = 0;
                    calendarInitialSequenceRequested = false;
                    if (calendarRangeStore == null || !calendarRangeStore.markFullSyncRequested()) {
                        calendarSnapshot.clear();
                        break;
                    }
                    calendarUpdatePending = false;
                    calendarPendingDifference = null;
                    calendarRefreshQueued = false;
                    calendarHandler.removeCallbacks(calendarUpdateAckTimeoutRunnable);
                    sendCalendarPayload(SapCalendarCodec.changeResponse(), "calendar-change-rsp");
                    break;
                case "calendar-listwithoption-req":
                    answerCalendarList(SapCalendarCodec.parseRange(frame.payload));
                    break;
                case "calendar-datechanged-req":
                    final SapCalendarCodec.RequestRange previousRange = calendarRanges.isEmpty()
                            ? null : calendarRanges.get(calendarRanges.size() - 1);
                    final SapCalendarCodec.RequestRange changedRange = SapCalendarCodec.parseDateChangedRange(
                            frame.payload, previousRange);
                    if (!markCalendarMirrorUntrusted("range-change")) break;
                    calendarWindows.requestReplacement();
                    calendarWindows.observe(changedRange, TimeZone.getDefault().getID());
                    if (!persistCalendarRanges("calendar-datechanged-req")) {
                        calendarSnapshot.clear();
                        break;
                    }
                    calendarMaxTextLength = changedRange.maxTextLength;
                    calendarReminders = changedRange.reminders;
                    LOG.info("Gear S3 calendar date-change window: start={} end={} zone={} recoveredWithoutPriorRange={}",
                            changedRange.start, changedRange.end, TimeZone.getDefault().getID(), previousRange == null);
                    sendCalendarPayload(SapCalendarCodec.dateChangedResponse(), "calendar-datechanged-rsp");
                    scheduleCalendarRefresh();
                    break;
                case "calendar-sequence-req":
                    // This follows the consumer's final list/DB attempt. A sequence-only
                    // reconnect cannot promote an unknown mirror; completeFullSync enforces it.
                    calendarSnapshot.completeFullSync();
                    LOG.info("Gear S3 calendar mirror baseline after sequence request: valid={}", calendarSnapshot.hasBaseline());
                    calendarInitialSequenceRequested = true;
                    if (calendarSnapshot.hasBaseline() && !calendarUpdatePending) {
                        commitCalendarMirror("full-sync-sequence-request");
                    } else if (!calendarUpdatePending && calendarRangeStore != null) {
                        calendarRangeStore.saveProgress(calendarSequence, true);
                    }
                    LOG.info("Gear S3 calendar sequence requested: current={} knownRanges={}",
                            calendarSequence, calendarRanges.size());
                    sendCalendarPayload(SapCalendarCodec.sequenceResponse(calendarSequence), "calendar-sequence-rsp");
                    if (calendarRefreshQueued) refreshCalendarFromPhone();
                    break;
                case "calendar-update-ack":
                    if (calendarUpdatePending && calendarPendingDifference != null) {
                        LOG.info("Gear S3 calendar update ACK RX: sequence={} added={} modified={} deleted={} wireDeletedCount={}",
                                calendarSequence, calendarPendingDifference.addedCount,
                                calendarPendingDifference.modifiedCount, calendarPendingDifference.removedCount(),
                                calendarPendingDifference.deleted.size());
                        calendarHandler.removeCallbacks(calendarUpdateAckTimeoutRunnable);
                        calendarSnapshot.commit(calendarPendingDifference);
                        calendarPendingDifference = null;
                        calendarUpdatePending = false;
                        final boolean checkpointSaved = commitCalendarMirror("update-application-ack");
                        if (checkpointSaved && !calendarRefreshQueued) setCalendarRefreshPending(false);
                        else if (!checkpointSaved) setCalendarRefreshPending(true);
                        LOG.info("Gear S3 calendar update ACK: sequence={}", calendarSequence);
                        if (calendarRefreshQueued) refreshCalendarFromPhone();
                    }
                    break;
                default:
                    LOG.info("Gear S3 calendar message not handled: {}", frame.messageId);
            }
    }

    private void answerCalendarList(final SapCalendarCodec.RequestRange range) throws JSONException {
        LOG.info("Gear S3 calendar list request: start={} end={} zone={} maxEvents={} maxAllDay={} maxText={} reminders={}",
                range.start, range.end, TimeZone.getDefault().getID(), range.maxEvents, range.maxAllDay,
                range.maxTextLength, range.reminders);
        // Even a list outside a full exchange can replace watch rows. Without a genuine
        // change/list/sequence chain it must not silently amend a trusted checkpoint.
        if (!markCalendarMirrorUntrusted("list-in-progress")) return;
        if (!calendarSnapshot.isCollectingFullSync()) calendarSnapshot.clear();
        calendarWindows.observe(range, TimeZone.getDefault().getID());
        if (!persistCalendarRanges("calendar-listwithoption-req")) {
            calendarSnapshot.clear();
            return;
        }
        if (getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            LOG.info("Gear S3 calendar list: READ_CALENDAR not granted; sending empty response");
        }
        final List<CalendarEvent> candidates = new CalendarManager(getContext(), getDevice().getAddress())
                .getCalendarEventList(range.start, range.end);
        final List<CalendarEvent> selected = SapCalendarEventSelection.select(candidates, range, TimeZone.getDefault());
        final String json = SapCalendarCodec.listResponse(selected, range.maxTextLength, TimeZone.getDefault(), range.reminders);
        final int byteLength = json.getBytes(StandardCharsets.UTF_8).length;
        for (final CalendarEvent event : selected) {
            LOG.info("Gear S3 calendar event encoded: eventId={} instanceStart={} instanceEnd={} allDay={} timezone={}",
                    event.getEventId(), event.getBegin(), event.getEnd(), event.isAllDay(), event.getTimezone());
        }
        calendarMaxTextLength = range.maxTextLength;
        calendarReminders = range.reminders;
        calendarSnapshot.recordList(selected);
        sendCalendarPayload(json, "calendar-listwithoption-rsp");
        LOG.info("Gear S3 calendar list response TX: msgId=calendar-listwithoption-rsp rangeStart={} rangeEnd={} androidRetained={} encodedCount={} bytes={} truncated={}",
                range.start, range.end, candidates.size(), selected.size(), byteLength,
                selected.size() < candidates.size());
    }

    private void scheduleCalendarRefresh() {
        synchronized (calendarLock) {
            calendarRefreshQueued = true;
            calendarHandler.removeCallbacks(calendarRefreshRunnable);
            calendarHandler.postDelayed(calendarRefreshRunnable, 2_500L);
        }
    }

    private void calendarSourceChanged() {
        synchronized (calendarLock) { calendarSourceChangedLocked(); }
    }

    private void calendarSourceChangedLocked() {
        LOG.info("Gear S3 calendar observer fired: knownRanges={} sessionEstablished={}",
                calendarRanges.size(), calendarRoute != null);
        setCalendarRefreshPending(true);
        scheduleCalendarRefresh();
    }

    private void refreshCalendarFromPhone() {
        if (!GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, false)
                || getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            LOG.info("Gear S3 calendar refresh deferred: sync disabled or READ_CALENDAR denied; keeping Gear ranges and mirror");
            setCalendarRefreshPending(true);
            return;
        }
        if (calendarRoute == null) {
            setCalendarRefreshPending(true);
            LOG.info("Gear S3 calendar pending refresh set because API101 unavailable");
            LOG.info("Gear S3 calendar update deferred: API101 session unavailable; requesting service connection");
            enqueueSapApplication(() -> maybeStartCalendarServiceConnection("pending observer refresh while API101 unavailable"));
            return;
        }
        if (calendarRanges.isEmpty()) {
            LOG.info("Gear S3 calendar update deferred: no persisted/observed Gear window available");
            return;
        }
        if (!calendarInitialSequenceRequested) {
            LOG.info("Gear S3 calendar update deferred: waiting for Gear calendar-sequence-req; knownRanges={}", calendarRanges.size());
            return;
        }
        if (!calendarSnapshot.hasBaseline()) {
            setCalendarRefreshPending(true);
            LOG.info("Gear S3 calendar update suppressed: watch baseline unknown; awaiting genuine full sync; retainedRanges={}", calendarRanges.size());
            return;
        }
        if (calendarUpdatePending) {
            LOG.info("Gear S3 calendar update deferred: waiting for previous update ACK");
            return;
        }
        calendarRefreshQueued = false;
        final CalendarManager manager = new CalendarManager(getContext(), getDevice().getAddress());
        final Map<String, CalendarEvent> unique = new LinkedHashMap<>();
        try {
            for (final SapCalendarCodec.RequestRange range : calendarRanges) {
                for (final CalendarEvent event : SapCalendarEventSelection.select(
                        manager.getCalendarEventList(range.start, range.end), range, TimeZone.getDefault())) {
                    unique.put(SapCalendarSnapshot.key(event), event);
                }
            }
        } catch (final RuntimeException | JSONException e) {
            calendarRefreshQueued = true;
            setCalendarRefreshPending(true);
            LOG.warn("Gear S3 calendar refresh could not read Android instances; keeping last acknowledged mirror", e);
            return;
        }
        final SapCalendarSnapshot.Difference difference = calendarSnapshot.diff(new ArrayList<>(unique.values()));
        LOG.info("Gear S3 calendar snapshot diff: added={} modified={} deleted={} current={}",
                difference.addedCount, difference.modifiedCount, difference.removedCount(), unique.size());
        if (difference.isEmpty()) {
            // A date-change request can change the range binding without changing events.
            if (!commitCalendarMirror("unchanged-reconciliation")) return;
            setCalendarRefreshPending(false);
            return;
        }
        try {
            final long nextSequence = calendarSequence + 1;
            final String json = SapCalendarCodec.updateIndication(difference.changed, difference.deleted,
                    nextSequence, calendarMaxTextLength, TimeZone.getDefault(), calendarReminders);
            if (json.getBytes(StandardCharsets.UTF_8).length > 48 * 1024) {
                LOG.warn("Gear S3 calendar update exceeds one clear frame: changed={} deleted={}; retaining prior mirror",
                        difference.changed.size(), difference.deleted.size());
                calendarRefreshQueued = true;
                setCalendarRefreshPending(true);
                return;
            }
            if (!markCalendarMirrorUntrusted("update-pending")) return;
            calendarSequence = nextSequence;
            calendarPendingDifference = difference;
            calendarUpdatePending = true;
            setCalendarRefreshPending(true);
            if (!sendCalendarPayload(json, "calendar-update-ind")) {
                LOG.info("Gear S3 calendar update deferred: transport changed before queueing; retaining uncertain operation");
                return;
            }
            calendarHandler.postDelayed(calendarUpdateAckTimeoutRunnable, 30_000L);
            LOG.info("Gear S3 calendar update/delete indication TX: sequence={} added={} modified={} deleted={} replacementDeletes={} wireDeletedCount={} bytes={}",
                    calendarSequence, difference.addedCount, difference.modifiedCount, difference.removedCount(),
                    difference.modifiedCount, difference.deleted.size(),
                    json.getBytes(StandardCharsets.UTF_8).length);
        } catch (final JSONException e) {
            calendarRefreshQueued = true;
            setCalendarRefreshPending(true);
            LOG.warn("Gear S3 calendar update encoding failed", e);
        }
    }

    private boolean sendCalendarPayload(final String json, final String label) {
        final CalendarRoute route = calendarRoute;
        if (route == null) return false;
        if (LOG.isDebugEnabled() && (label.startsWith("calendar-listwithoption-rsp")
                || label.startsWith("calendar-update-ind"))) {
            try {
                final JSONObject diagnostic = SapCalendarPayloadDiagnostics.describe(json);
                final org.json.JSONArray events = diagnostic.getJSONArray("events");
                diagnostic.remove("events");
                LOG.debug("Gear S3 calendar serialized envelope: {}", diagnostic);
                for (int i = 0; i < events.length(); i++) {
                    LOG.debug("Gear S3 calendar serialized event: msgId={} {}", diagnostic.getString("msgId"), events.getJSONObject(i));
                }
            } catch (final JSONException | RuntimeException e) {
                // Diagnostics must neither suppress the original payload nor log its private contents.
                LOG.warn("Gear S3 calendar serialized diagnostics unavailable: type={}", e.getClass().getSimpleName());
            }
        }
        final int sequence = calendarReliableTxSequence;
        calendarReliableTxSequence = sequence == 0xffff ? 1 : sequence + 1;
        final byte[] frame = SapCalendarCodec.encodeFrame(sequence, json);
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                route.session, 0), frame);
        final TransactionBuilder transaction = createTransactionBuilder("gearS3CalendarPayload")
                .run(socket -> calendarRoute == route && sapDispatchGeneration == route.generation)
                .write(SapPostAuthRecordCodec.encode(record));
        if (!queueCalendarTransaction(transaction, route)) return false;
        LOG.info("Gear S3 calendar TX: msgId={} reliableSeq={} bytes={}", label, sequence, frame.length);
        return true;
    }

    /** Calendar runs independently of the support monitor, but queue lifetime uses ConnectionMonitor. */
    private boolean queueCalendarTransaction(final TransactionBuilder transaction, final CalendarRoute route) {
        synchronized (ConnectionMonitor) {
            if (sapDispatchDisposed || sapTransportClosed || calendarRoute != route || sapDispatchGeneration != route.generation
                    || !isConnected()) {
                LOG.info("Gear S3 calendar TX deferred: stale or disconnected transport; task={}",
                        transaction.getTaskName());
                return false;
            }
            // disconnect/dispose cannot remove the queue between this check and queue().
            transaction.queue();
            return true;
        }
    }

    private void scheduleCalendarReliableAck() {
        calendarAckScheduledAt = android.os.SystemClock.elapsedRealtime();
        calendarHandler.removeCallbacks(calendarAckRunnable);
        calendarHandler.postDelayed(calendarAckRunnable, SapReliableSession.RX_BLOCK_ACK_TIMEOUT_MILLIS);
    }

    private void sendPendingCalendarReliableAck() {
        final int sequence = calendarReliableRxSession.consumePendingBlockAck();
        if (sequence >= 1) sendCalendarReliableAck(sequence);
    }

    private void sendCalendarReliableAck(final int sequence) {
        final CalendarRoute route = calendarRoute;
        if (route == null || sequence < 1) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 1,
                route.session, 0), SapNotificationServiceCodec.encodeReliableBlockAck(sequence));
        final long queuedAt = android.os.SystemClock.elapsedRealtime();
        LOG.info("Gear S3 calendar reliable ACK TX queued: session={} sequence={} queuedAtMs={} timerAgeMs={}",
                calendarRoute.session, sequence, queuedAt, queuedAt - calendarAckScheduledAt);
        final int session = route.session;
        final TransactionBuilder transaction = createTransactionBuilder("gearS3CalendarReliableAck")
                .run(socket -> calendarRoute == route && sapDispatchGeneration == route.generation)
                .write(SapPostAuthRecordCodec.encode(record))
                .run(() -> LOG.info("Gear S3 calendar reliable ACK TX written: session={} sequence={} queueDelayMs={}",
                        session, sequence, android.os.SystemClock.elapsedRealtime() - queuedAt));
        queueCalendarTransaction(transaction, route);
    }

    private void sendCalendarReliableNak(final int expected) {
        final CalendarRoute route = calendarRoute;
        if (route == null) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 1,
                route.session, 0), SapNotificationServiceCodec.encodeReliableNak(expected, expected));
        LOG.info("Gear S3 calendar reliable NAK TX: missing={} queued={}", expected, calendarReliableRxSession.getQueuedCount());
        final TransactionBuilder transaction = createTransactionBuilder("gearS3CalendarReliableNak")
                .run(socket -> calendarRoute == route && sapDispatchGeneration == route.generation)
                .write(SapPostAuthRecordCodec.encode(record));
        queueCalendarTransaction(transaction, route);
    }

    private void closeCalendarSession() {
        synchronized (calendarLock) { closeCalendarSessionLocked(); }
    }

    private void closeCalendarSessionLocked() {
        calendarRoute = null;
        LOG.info("Gear S3 calendar session map before close: state={} activeOrPending={} pendingPhone={} sessions={}",
                calendarServiceConnection == null ? "null" : calendarServiceConnection.getState(),
                calendarServiceConnection == null ? -1 : calendarServiceConnection.getSessionId(),
                calendarServiceConnection == null ? -1 : calendarServiceConnection.getPendingPhoneSessionId(),
                usedApplicationSessions());
        calendarHandler.removeCallbacks(calendarCreateTimeoutRunnable);
        calendarHandler.removeCallbacks(calendarAckRunnable);
        calendarHandler.removeCallbacks(calendarUpdateAckTimeoutRunnable);
        if (calendarUpdatePending) {
            calendarSnapshot.clear();
            calendarRefreshQueued = true;
            setCalendarRefreshPending(true);
        }
        calendarUpdatePending = false;
        calendarPendingDifference = null;
        calendarSnapshot.sessionClosed();
        calendarReliableRxSession.reset();
        if (calendarServiceConnection != null) calendarServiceConnection.clear();
        LOG.info("Gear S3 calendar session map after close: sessions={}", usedApplicationSessions());
        LOG.info("Gear S3 calendar API101 session closed: retainedRanges={} sequence={} observerRegistered={}",
                calendarRanges.size(), calendarSequence, calendarObserverRegistered);
    }

    private void clearCalendarService() {
        synchronized (calendarLock) { clearCalendarServiceLocked(); }
    }

    private void clearCalendarServiceLocked() {
        if (BuildConfig.DEBUG && getDevice() != null) {
            final String key = getDevice().getAddress().toUpperCase(Locale.ROOT);
            final WeakReference<GearS3DeviceSupport> reference = CALENDAR_DEBUG_SUPPORTS.get(key);
            if (reference != null && reference.get() == this) CALENDAR_DEBUG_SUPPORTS.remove(key, reference);
        }
        calendarRoute = null;
        calendarHandler.removeCallbacks(calendarCreateTimeoutRunnable);
        calendarHandler.removeCallbacks(calendarRefreshRunnable);
        calendarHandler.removeCallbacks(calendarAckRunnable);
        calendarHandler.removeCallbacks(calendarUpdateAckTimeoutRunnable);
        if (calendarObserverRegistered) {
            getContext().getContentResolver().unregisterContentObserver(calendarObserver);
            calendarObserverRegistered = false;
        }
        if (calendarServiceConnection != null) calendarServiceConnection.clear();
        calendarSnapshot.clear();
        calendarWindows.clear();
        calendarStateRestored = false;
        calendarReliableRxSession.reset();
        calendarProfileExposed = false;
        calendarInitialSequenceRequested = false;
        calendarUpdatePending = false;
        calendarRefreshQueued = false;
        calendarRefreshPendingDurable = false;
        calendarPendingDifference = null;
        calendarReliableTxSequence = 1;
        calendarSequence = 0;
        calendarMaxTextLength = 0;
        calendarReminders = false;
    }

    @Override
    public synchronized void onSendConfiguration(final String config) {
        super.onSendConfiguration(config);
        if (config != null && (config.startsWith("calendar_")
                || DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR.equals(config))) {
            synchronized (calendarLock) {
                if (!GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                        .getBoolean(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, false)) {
                    if (calendarUpdatePending) calendarSnapshot.clear();
                    calendarHandler.removeCallbacks(calendarUpdateAckTimeoutRunnable);
                    calendarUpdatePending = false;
                    calendarPendingDifference = null;
                    setCalendarRefreshPending(true);
                }
                scheduleCalendarRefresh();
            }
        }
    }

    private void sendContactsRecord(final SapPostAuthRecord record) {
        sendContactsRecord(record, sapDispatchGeneration);
    }

    private void sendContactsRecord(final SapPostAuthRecord record, final long generation) {
        synchronized (ConnectionMonitor) {
            if (sapDispatchDisposed || sapTransportClosed || generation != sapDispatchGeneration || !isConnected()) {
                LOG.debug("Gear S3 contacts TX dropped: stale or disconnected transport");
                return;
            }
            createTransactionBuilder("gearS3Contacts")
                    .run(socket -> !sapDispatchDisposed && !sapTransportClosed && generation == sapDispatchGeneration)
                    .write(SapPostAuthRecordCodec.encode(record)).queue();
        }
    }

    /** Start only after CapEx has exposed our consumer and learned the Gear provider. */
    private void maybeStartPedometerServiceConnection(final SapCapabilityExchange.Reply reply) {
        if (capabilityExchange.getState() != SapCapabilityExchange.State.WAIT_FOLLOW_UP
                || pedometerServiceConnector == null || pedometerServiceConnector.getState() != SapPedometerServiceConnector.State.IDLE
                || !reply.getQuery().getProfiles().contains(SapPedometerServiceRegistration.PROFILE)
                || !containsPedometerDescriptor(reply.getResponse())
                || pedometerServiceRegistration.getRemoteProvider() == null) return;
        startPedometerServiceConnection();
    }

    /** Uses the already negotiated dynamic provider descriptor; no global SAP transport is touched. */
    private boolean startPedometerServiceConnection() {
        if (pedometerServiceConnector == null
                || pedometerServiceConnector.getState() != SapPedometerServiceConnector.State.IDLE
                || pedometerServiceRegistration == null
                || pedometerServiceRegistration.getRemoteProvider() == null) {
            return false;
        }
        final Set<Integer> inUse = usedApplicationSessions();
        final SapServiceConnectionRequest request = pedometerServiceConnector.createRequest(inUse);
        final SapPostAuthRecord record = new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                SapServiceConnectionCodec.encode(request));
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        LOG.info("Gear S3 outgoing watch pedometer API999 service request: localComponent=0x{} remoteComponent=0x{} session={} channel={} qos={}/{}/{} payloadType={}",
                Integer.toHexString(request.getInitiatorComponentId()), Integer.toHexString(request.getAcceptorComponentId()),
                session.getSessionId(), session.getChannelId(), session.getQos().getType(), session.getQos().getDataRate(),
                session.getQos().getClassType(), session.getPayloadType());
        createTransactionBuilder("gearS3WatchPedometerServiceConnectionRequest").write(SapPostAuthRecordCodec.encode(record)).queue();
        return true;
    }

    private static boolean containsPedometerDescriptor(final SapCapexCodec.Response response) {
        for (final SapAgentDescriptor agent : response.getAgents()) for (final SapServiceDescriptor service : agent.getServices()) {
            if (SapPedometerServiceRegistration.PROFILE.equals(service.getProfileId())) return true;
        }
        return false;
    }

    /** The Gear must first receive our real bcmservice consumer descriptor. */
    private void maybeStartBcmServiceConnection(final SapCapabilityExchange.Reply reply) {
        if (capabilityExchange.getState() != SapCapabilityExchange.State.WAIT_FOLLOW_UP
                || bcmServiceConnector == null || bcmServiceConnector.getState() != SapBcmServiceConnector.State.IDLE
                || !reply.getQuery().getProfiles().contains(SapBcmServiceRegistration.PROFILE)
                || !containsBcmDescriptor(reply.getResponse())) {
            return;
        }
        if (bcmServiceRegistration.getRemoteProvider() == null) {
            LOG.debug("Gear S3 bcmservice descriptor exposed; waiting for remote provider descriptor");
            return;
        }
        final SapServiceConnectionRequest request = bcmServiceConnector.createRequest();
        final SapPostAuthRecord record = new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0),
                SapServiceConnectionCodec.encode(request));
        final byte[] encoded = SapPostAuthRecordCodec.encode(record);
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        LOG.debug("Gear S3 bcmservice connection request: localComponent=0x{} remoteComponent=0x{} session={} channel={} qos={}/{}/{}",
                Integer.toHexString(request.getInitiatorComponentId()), Integer.toHexString(request.getAcceptorComponentId()),
                session.getSessionId(), session.getChannelId(), session.getQos().getType(), session.getQos().getDataRate(),
                session.getQos().getClassType());
        LOG.debug("Gear S3 outgoing bcmservice service-control wire={}", GB.hexdump(encoded));
        createTransactionBuilder("gearS3BcmServiceConnectionRequest").write(encoded).queue();
    }

    private static boolean containsBcmDescriptor(final SapCapexCodec.Response response) {
        for (final SapAgentDescriptor agent : response.getAgents()) {
            for (final SapServiceDescriptor service : agent.getServices()) {
                if (SapBcmServiceRegistration.PROFILE.equals(service.getProfileId())) return true;
            }
        }
        return false;
    }

    private void startBcmFeatureExchange() {
        if (bcmFeatureExchange == null) bcmFeatureExchange = new SapBcmFeatureExchange();
        final byte[] body = bcmFeatureExchange.createLocalRequestFrame();
        if (body == null) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                bcmServiceConnector.getApplicationSessionId(), 0), body);
        final byte[] encoded = SapPostAuthRecordCodec.encode(record);
        LOG.debug("Gear S3 bcmservice feature exchange started");
        LOG.debug("Gear S3 bcmservice TX feature requests: BT GET_VERSION, BT GET_SERVICES, GENERIC GET_SUPPORTED_NETWORK_TYPES, BT GET_LE_ADDRESS, WIFI_P2P GET_MAC_ADDR, BT GET_RSSI wire={}",
                GB.hexdump(encoded));
        createTransactionBuilder("gearS3BcmServiceFeatureRequests").write(encoded).queue();
        LOG.debug("Gear S3 bcmservice feature exchange local request set complete");
    }

    private void handleBcmServiceApplicationRecord(final SapPostAuthRecord record) {
        if (bcmFeatureExchange == null) throw new IllegalStateException("bcmservice application traffic arrived before connection acceptance");
        final SapBcmFeatureExchange.Result result = bcmFeatureExchange.handleFrame(record.getBody(), new SapBcmFeatureExchange.Values() {
            @Override public byte[] bluetoothAddress() { return GearS3DeviceSupport.this.bluetoothAddress(); }
            @Override public byte[] bluetoothVersion() { return GearS3DeviceSupport.bluetoothVersion(); }
            @Override public byte[] manufacturer() { return SapBcmFeatureExchange.utf8(Build.MANUFACTURER); }
            @Override public byte[] leAddress() { return null; }
            @Override public byte[] wifiP2pAddress() { return null; }
        });
        for (final SapBcmFeatureExchange.Pdu pdu : result.getReceived()) {
            LOG.debug("Gear S3 bcmservice RX PDU: main={} sub={} operation={} value={}", pdu.getMain(), pdu.getSub(),
                    pdu.getOperation(), GB.hexdump(pdu.getValue()));
        }
        for (final SapBcmFeatureExchange.Action action : result.getActions()) {
            if (action.getReply() == null) {
                LOG.debug("Gear S3 bcmservice PDU omitted: {}", action.getReason());
            } else {
                LOG.debug("Gear S3 bcmservice generated response: main={} sub={} truthful source/value={} {}",
                        action.getReply().getMain(), action.getReply().getSub(), action.getReason(), GB.hexdump(action.getReply().getValue()));
            }
        }
        final byte[] replyBody = result.getReplyFrame();
        if (replyBody != null) {
            final SapPostAuthRecord reply = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    bcmServiceConnector.getApplicationSessionId(), 0), replyBody);
            final byte[] encoded = SapPostAuthRecordCodec.encode(reply);
            LOG.debug("Gear S3 bcmservice TX feature replies wire={}", GB.hexdump(encoded));
            createTransactionBuilder("gearS3BcmServiceFeatureReplies").write(encoded).queue();
        }
        if (result.isPeerResponsesComplete() && !bcmPeerResponsesCompleteLogged) {
            bcmPeerResponsesCompleteLogged = true;
            LOG.debug("Gear S3 bcmservice feature exchange peer response set complete");
        }
        if (result.isPeerRequestHandlingComplete() && !bcmPeerRequestHandlingCompleteLogged) {
            bcmPeerRequestHandlingCompleteLogged = true;
            LOG.debug("Gear S3 bcmservice peer request handling complete");
        }
    }

    // Called by the serialized SAP application dispatcher under the support monitor.
    // Cipher, protocol transitions, persistence and responses retain master's ordering.
    private void handlePedometerServiceApplicationRecord(final SapPostAuthRecord record) {
        final byte[] payload = record.getBody();
        final boolean firstPayload = !pedometerFirstPayloadLogged;
        pedometerFirstPayloadLogged = true;
        LOG.debug("Gear S3 watch pedometer API999 RX: session={} channel={} qos={}/{}/{} rawPayloadBytes={} protectedBytes={} unsolicited={} firstPayload={}",
                pedometerServiceConnector.getApplicationSessionId(), SapPedometerServiceRegistration.API_CHANNEL,
                SapPedometerServiceRegistration.QOS_TYPE, SapPedometerServiceRegistration.QOS_DATA_RATE,
                SapPedometerServiceRegistration.QOS_CLASS, payload == null ? 0 : payload.length,
                payload == null ? 0 : Math.max(0, payload.length - 1), true, firstPayload);
        if (pedometerSecureDecoder == null) {
            LOG.warn("Gear S3 ignored watch pedometer API999 payload without current health cipher");
            return;
        }
        final SapPedometerSecureDecoder.Result decoded = pedometerSecureDecoder.decode(payload);
        LOG.debug("Gear S3 watch pedometer secure RX: rawFragmentBytes={} sdk=0x{} encrypted={} compressed={} fragment={} ordinal={} sequence={} hmacVerified={} decryptSuccess={} accumulatedBytes={} reassembledBytes={} finalDecryptedBytes={} finalApplicationBytes={}",
                payload == null ? 0 : payload.length,
                decoded.getSdk() < 0 ? "<missing>" : Integer.toHexString(decoded.getSdk()),
                decoded.getSdk() >= 0 && (decoded.getSdk() & SapPedometerSecureDecoder.SDK_ENCRYPTED) != 0,
                decoded.isCompressed(), decoded.getFragmentState(), decoded.getFragmentOrdinal(), decoded.getSequence(),
                decoded.isHmacVerified(), decoded.isDecryptSucceeded(), decoded.getAccumulatedLength(),
                decoded.getReassembledLength(), decoded.getDecryptedLength(), decoded.getApplicationLength());
        if (!decoded.isValid()) {
            LOG.warn("Gear S3 ignored malformed secure watch pedometer payload: {}", decoded.getError());
            return;
        }
        if (!decoded.isReassemblyComplete()) {
            LOG.debug("Gear S3 watch pedometer fragment accepted: ordinal={} accumulatedBytes={}; awaiting final fragment",
                    decoded.getFragmentOrdinal(), decoded.getAccumulatedLength());
            return;
        }
        final byte[] clear = decoded.getApplicationBytes();
        final boolean trailingNul = clear.length > 0 && clear[clear.length - 1] == 0;
        final int finalByte = clear.length == 0 ? -1 : clear[clear.length - 1] & 0xff;
        final SapPedometerJsonProbe.Result result = SapPedometerJsonProbe.parse(clear);
        LOG.debug("Gear S3 watch pedometer clear RX: decryptedBytes={} frameworkClearBytes={} applicationBytes={} trailingNul={} finalByte={} startsWithCOMP={} jsonWithFinalByte={}",
                decoded.getDecryptedLength(), decoded.getFrameworkClearLength(), clear.length, trailingNul,
                finalByte < 0 ? "<none>" : String.format("0x%02x", finalByte), decoded.startsWithComp(), result.isValid());
        if (!result.isValid()) {
            LOG.warn("Gear S3 ignored malformed watch pedometer JSON: {}", result.getError());
            return;
        }
        if (isManagerCapabilityRequest(result)) sendManagerCapabilityResponse();
        if (SapPedometerCapabilityResponse.isRichCapabilityRequest(result)) {
            LOG.debug("Gear S3 watch pedometer rich capability request detected");
            sendRichCapabilityResponse();
        }
        final SapPedometerJsonProbe.CommonSync commonSync = SapPedometerJsonProbe.inspectCommonSync(result);
        if (commonSync != null) {
            if ("SYNC_DATA".equals(commonSync.getAction())
                    && isValidCommonSyncRequest(result, commonSync)) {
                if (commonSyncState.begin()) {
                    healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
                    commonSyncActivityDataPersisted = false;
                    LOG.info("Gear S3 CommonSync state: IDLE -> ACTIVE");
                }
                if (healthFetchState.isInProgress()
                        && healthFetchState.getPhase() != GearS3HealthFetchState.Phase.WAIT_COMMON_SYNC) {
                    healthFetchState.awaitingCommonSync();
                    LOG.info("Gear S3 health fetch: CommonSync already active; coalescing with current sync");
                }
                commonSyncActivityDataPersisted |= persistCommonSyncPedometerData(result);
                commonSyncActivityDataPersisted |= persistCommonSyncHeartRateData(result);
                commonSyncActivityDataPersisted |= persistCommonSyncExerciseData(result);
            }
            if (isValidCommonSyncRequest(result, commonSync)) {
                if (sendCommonSyncSuccessResponse(commonSync.getSequence(), commonSync.getAction())
                        && "REQUEST_DATA".equals(commonSync.getAction())) {
                    if (commonSyncState.complete()) {
                        healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
                        LOG.info("Gear S3 CommonSync state: ACTIVE -> IDLE");
                        final boolean fetchCompleted = completeHealthFetch();
                        if (commonSyncActivityDataPersisted || fetchCompleted) {
                            LOG.info("Gear S3 CommonSync activity data complete; notifying activity UI");
                            GB.signalActivityDataFinish(getDevice());
                        }
                        commonSyncActivityDataPersisted = false;
                    }
                }
            }
        }
        healthDiagnostics.submit(clear);
    }

    // Diagnostic parsing/reporting owns a copied input on the bounded worker, never the support lock.
    // Keep master's compact summaries; do not restore the former recursive table/matcher dumps.
    private static void logHealthDiagnostics(final GearHealthDiagnostics.Report report, final BooleanSupplier current) {
        if (!current.getAsBoolean() || !report.result.isValid()) return;
        final SapPedometerJsonProbe.Result result = report.result;
        final SapPedometerJsonProbe.CommonSync commonSync = report.commonSync;
        if (commonSync != null) {
            LOG.debug("Gear S3 CommonSync request: jsonBytes={} action={} requestObjects={}",
                    result.getByteLength(), commonSync.getAction(), commonSync.getRequestCount());
        }
        if (!current.getAsBoolean()) return;
        if (SapPedometerJsonProbe.GEAR_SYNC_COMMAND.equals(result.getRequestMessage())) {
            LOG.debug("Gear S3 watch pedometer Gear_Sync_Cmd identified");
        }
        if (SapPedometerJsonProbe.GEAR_SHEALTH_SYNC.equals(result.getResponseMessage())) {
            LOG.debug("Gear S3 watch pedometer SPedoResult count={}", result.getRecords().size());
        }
    }

    private static boolean isSupportedCommonSyncAction(final String action) {
        return "SYNC_DATA".equals(action) || "REQUEST_DATA".equals(action);
    }

    private static boolean isValidCommonSyncRequest(final SapPedometerJsonProbe.Result result,
                                                    final SapPedometerJsonProbe.CommonSync commonSync) {
        return commonSync != null
                && isSupportedCommonSyncAction(commonSync.getAction())
                && "DATA".equals(stringField(result, "type"))
                && "com.samsung.tizengear.app.shealth.wearable.syncmanager".equals(stringField(result, "sender"))
                && "com.samsung.android.app.shealth.wearable.syncmanager".equals(stringField(result, "receiver"))
                && commonSync.getSequence() instanceof Number
                && ((Number) commonSync.getSequence()).longValue() > 0;
    }

    private boolean persistCommonSyncPedometerData(final SapPedometerJsonProbe.Result result) {
        final SapPedometerJsonProbe.PedometerStepRecords parsed =
                SapPedometerJsonProbe.parsePedometerStepRecords(result);
        if (parsed.getRecords().isEmpty()) {
            if (parsed.getMalformedCount() > 0) {
                LOG.warn("Gear S3 CommonSync pedometer persistence skipped: validRecords=0 malformedRecords={} duplicateRecords={}",
                        parsed.getMalformedCount(), parsed.getDuplicateCount());
            }
            return false;
        }
        final boolean persisted = GearS3StepImporter.persist(getDevice(), parsed.getRecords(), getContext());
        LOG.info("Gear S3 CommonSync pedometer persistence: validRecords={} malformedRecords={} duplicateRecords={} persisted={}",
                parsed.getRecords().size(), parsed.getMalformedCount(), parsed.getDuplicateCount(), persisted);
        return persisted;
    }

    private boolean persistCommonSyncHeartRateData(final SapPedometerJsonProbe.Result result) {
        final SapPedometerJsonProbe.HeartRateRecords parsed =
                SapPedometerJsonProbe.parseHeartRateRecords(result);
        if (parsed.getRecords().isEmpty() && parsed.getMalformedCount() == 0) return false;
        final boolean persisted = !parsed.getRecords().isEmpty()
                && GearS3HeartRateImporter.persist(getDevice(), parsed.getRecords(), getContext());
        LOG.info("Gear S3 CommonSync heart-rate persistence: validRecords={} malformedRecords={} duplicateRecords={} persisted={}",
                parsed.getRecords().size(), parsed.getMalformedCount(), parsed.getDuplicateCount(), persisted);
        return persisted;
    }

    private boolean persistCommonSyncExerciseData(final SapPedometerJsonProbe.Result result) {
        final SapExerciseRecords.Result parsed = SapExerciseRecords.parse(result);
        if (parsed.records.isEmpty() && parsed.malformedCount == 0) return false;
        final GearS3ExerciseImporter.Outcome outcome =
                GearS3ExerciseImporter.persist(getDevice(), parsed.records);
        LOG.info("Gear S3 CommonSync exercise persistence: validRecords={} malformedRecords={} duplicateRecords={} inserted={} updated={} unchanged={} persisted={}",
                parsed.records.size(), parsed.malformedCount, parsed.duplicateCount,
                outcome.inserted, outcome.updated, outcome.unchanged, outcome.success);
        return outcome.success && outcome.changed();
    }

    /** Minimal V4.51 CommonSync ACK using a direct-array body. */
    private boolean sendCommonSyncSuccessResponse(final Object sequence, final String action) {
        if (pedometerSecureEncoder == null || pedometerServiceConnector == null
                || pedometerServiceConnector.getState() != SapPedometerServiceConnector.State.ESTABLISHED) {
            LOG.warn("Gear S3 cannot answer CommonSync without an established health TX cipher");
            return false;
        }
        try {
            final byte[] clear = SapPedometerJsonProbe.commonSyncSuccessResponse(sequence, action);
            final SapPedometerSecureEncoder.EncodedFrame frame = pedometerSecureEncoder.encode(clear);
            final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    pedometerServiceConnector.getApplicationSessionId(), 0), frame.getBody());
            final byte[] encoded = SapPostAuthRecordCodec.encode(record);
            LOG.debug("Gear S3 CommonSync ACK: txSequence={} sdk=0x{} compressed={} clearBytes={} protectedBytes={}",
                    frame.getSequence(), Integer.toHexString(frame.getSdk()),
                    frame.isCompressed(), frame.getClearLength(), frame.getProtectedLength());
            createTransactionBuilder("gearS3WatchPedometerCommonSyncAck")
                    .write(encoded)
                    .run(() -> LOG.info("Gear S3 CommonSync ACK sent"))
                    .queue();
            return true;
        } catch (final RuntimeException e) {
            LOG.warn("Gear S3 CommonSync ACK send=failure", e);
            return false;
        }
    }

    private static boolean isManagerCapabilityRequest(final SapPedometerJsonProbe.Result result) {
        return SapPedometerCapabilityResponse.REQUEST.equals(stringField(result, "capability_type"))
                && "wearable".equals(stringField(result, "sender"))
                && "wearable_manager".equals(stringField(result, "receiver"));
    }

    private static String stringField(final SapPedometerJsonProbe.Result result, final String name) {
        final Object value = result.getTopLevel().get(name);
        return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
    }

    private void sendManagerCapabilityResponse() {
        if (pedometerManagerCapabilityResponded) {
            LOG.info("Gear S3 watch pedometer manager capability request already answered; ignoring duplicate");
            return;
        }
        if (pedometerSecureEncoder == null || pedometerServiceConnector == null
                || pedometerServiceConnector.getState() != SapPedometerServiceConnector.State.ESTABLISHED) {
            LOG.warn("Gear S3 cannot answer manager capability request without an established health TX cipher");
            return;
        }
        final byte[] clear = SapPedometerCapabilityResponse.managerResponseJson(getContext());
        try {
            try {
                final JSONObject managerResponse = new JSONObject(new String(clear, StandardCharsets.UTF_8));
                LOG.info("Gear S3 watch pedometer manager capability response fields: health_version={} status={}",
                        managerResponse.getLong("health_version"), managerResponse.getString("status"));
            } catch (final JSONException e) {
                throw new IllegalStateException("Unable to inspect health manager capability response", e);
            }
            final SapPedometerSecureEncoder.EncodedFrame frame = pedometerSecureEncoder.encode(clear);
            final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    pedometerServiceConnector.getApplicationSessionId(), 0), frame.getBody());
            final byte[] encoded = SapPostAuthRecordCodec.encode(record);
            createTransactionBuilder("gearS3WatchPedometerManagerCapabilityResponse")
                    .write(encoded)
                    .run(() -> LOG.info("Gear S3 watch pedometer manager capability response send=success"))
                    .queue();
            pedometerManagerCapabilityResponded = true;
            LOG.info("Gear S3 watch pedometer manager capability response: discriminator={} clearUtf8Bytes={} sdk=0x{} compressed={} txSequence={} protectedBytes={} send=queued",
                    SapPedometerCapabilityResponse.RESPONSE, clear.length, Integer.toHexString(frame.getSdk()),
                    frame.isCompressed(), frame.getSequence(), frame.getProtectedLength());
        } catch (final RuntimeException e) {
            LOG.warn("Gear S3 watch pedometer manager capability response send=failure", e);
        }
    }

    /** Reply once per health session so the watch can initiate CommonSync in every build type. */
    private void sendRichCapabilityResponse() {
        if (pedometerRichCapabilityResponded) {
            LOG.debug("Gear S3 watch pedometer rich capability response already queued; ignoring duplicate");
            return;
        }
        if (pedometerSecureEncoder == null || pedometerServiceConnector == null
                || pedometerServiceConnector.getState() != SapPedometerServiceConnector.State.ESTABLISHED) {
            LOG.warn("Gear S3 cannot send rich capability response without an established health TX cipher");
            return;
        }
        final byte[] clear = SapPedometerCapabilityResponse.richResponseJson(getContext());
        try {
            final SapPedometerSecureEncoder.EncodedFrame frame = pedometerSecureEncoder.encode(clear);
            final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    pedometerServiceConnector.getApplicationSessionId(), 0), frame.getBody());
            final byte[] encoded = SapPostAuthRecordCodec.encode(record);
            LOG.debug("Gear S3 watch pedometer rich capability response queued: txSequence={} sdk=0x{} compressed={} compressionChoice={} clearBytes={} protectedBytes={}",
                    frame.getSequence(), Integer.toHexString(frame.getSdk()), frame.isCompressed(),
                    frame.isCompressed() ? "zlib" : "none", frame.getClearLength(), frame.getProtectedLength());
            createTransactionBuilder("gearS3WatchPedometerRichCapabilityResponse")
                    .write(encoded)
                    .run(() -> {
                        synchronized (GearS3DeviceSupport.this) {
                            if (commonSyncState.expectingSync()) {
                                healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
                                healthFetchHandler.postDelayed(commonSyncExpectationTimeoutRunnable,
                                        COMMON_SYNC_EXPECTING_TIMEOUT_MILLIS);
                                LOG.info("Gear S3 CommonSync state: IDLE -> EXPECTING_SYNC");
                            }
                            LOG.info("Gear S3 watch pedometer rich capability response sent");
                        }
                    })
                    .queue();
            pedometerRichCapabilityResponded = true;
        } catch (final RuntimeException e) {
            LOG.warn("Gear S3 watch pedometer rich capability response send=failure", e);
        }
    }

    private void startHostManagerWatchInfoRequest() {
        if (hostManagerApplication == null) hostManagerApplication = new SapHostManagerApplication();
        final byte[] body = hostManagerApplication.createWatchInfoRequest(getDevice().getAddress(), bluetoothAddressString(),
                Locale.getDefault().toString());
        if (body == null) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                hostManagerAcceptor.getApplicationSessionId(), 0), body);
        final byte[] encoded = SapPostAuthRecordCodec.encode(record);
        LOG.debug("Gear S3 Host Manager TX mgr_watch_info_req json={} wire={}",
                new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8), GB.hexdump(encoded));
        createTransactionBuilder("gearS3HostManagerWatchInfoRequest").write(encoded).queue();
    }

    private void startHostManagerWearableStatusRequest() {
        final byte[] body = hostManagerApplication.createWearableStatusRequest(
                getDevice().getAddress(), System.currentTimeMillis());
        if (body == null) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                hostManagerAcceptor.getApplicationSessionId(), 0), body);
        final byte[] encoded = SapPostAuthRecordCodec.encode(record);
        LOG.debug("Gear S3 Host Manager TX mgr_wearable_status_req json={} wire={}",
                new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8), GB.hexdump(encoded));
        createTransactionBuilder("gearS3HostManagerWearableStatusRequest").write(encoded).queue();
    }

    private void handleHostManagerApplicationRecord(final SapPostAuthRecord record) {
        if (hostManagerApplication == null) throw new IllegalStateException("Host Manager application traffic arrived before session acceptance");
        final byte[] body = record.getBody();
        final boolean secure = body.length > 0 && SapHostManagerServiceChannelHeader.isSecure(body[0] & 0xff);
        final SapHostManagerApplication.DecodedMessage message = secure
                ? hostManagerApplication.decodeSecure(body, hostManagerAppCipher) : hostManagerApplication.decode(body);
        if (secure) LOG.debug("Gear S3 Host Manager RX secure msgId={} rxExpected={}",
                message.getMessageId(), hostManagerAppCipher.getRxExpected());
        if (SapHostManagerApplication.WATCH_INFO_RESPONSE.equals(message.getMessageId())) {
            LOG.info("Gear S3 Host Manager mgr_watch_info_res received");
            LOG.debug("Gear S3 Host Manager watch-info fields={}", message.getWatchInfo().getFields());
            startHostManagerWearableStatusRequest();
            return;
        }
        if (SapHostManagerApplication.WEARABLE_STATUS_RESPONSE.equals(message.getMessageId())) {
            LOG.info("Gear S3 Host Manager mgr_wearable_status_res received");
            LOG.debug("Gear S3 Host Manager wearable-status type={} dataLength={}",
                    message.getWearableStatus().getType(), message.getWearableStatus().getData().length());
            return;
        }
        if (SapHostManagerApplication.HOST_STATUS_REQUEST.equals(message.getMessageId())) {
            LOG.info("Gear S3 Host Manager mgr_host_status_req received: type={}", message.getHostStatus().getType());
            startHostManagerHostStatusResponse(message.getHostStatus().getType());
            return;
        }
        if (SapHostManagerApplication.STATUS_EXCHANGE_DONE.equals(message.getMessageId())) {
            LOG.info("Gear S3 Host Manager mgr_status_exchange_done received: result={} reason={}",
                    message.getJson().opt("result"), message.getJson().opt("reason"));
            startHostManagerReadyForRestoreRequest();
            return;
        }
        if (SapHostManagerApplication.READY_FOR_RESTORE_RESPONSE.equals(message.getMessageId())) {
            LOG.info("Gear S3 Host Manager mgr_wearable_ready_for_restore_res received; application startup complete");
            if (hostManagerApplication.getState() == SapHostManagerApplication.State.HOST_MANAGER_READY) {
                getDevice().setUpdateState(GBDevice.State.INITIALIZED, getContext());
                maybeStartCalendarServiceConnection("Host Manager ready");
            }
            return;
        }
        if (SapHostManagerApplication.DATA_NETWORK_REQUEST.equals(message.getMessageId())) {
            final String requestedDeviceId = message.getJson().optString("deviceid", "");
            if (!requestedDeviceId.isEmpty() && !getDevice().getAddress().equalsIgnoreCase(requestedDeviceId)) {
                LOG.warn("Gear S3 ignored Host Manager data-network request for another device");
                return;
            }
            final int networkType = currentHostNetworkType();
            // A settings request contains only msgId;
            // dispatcher supplies deviceid from its connection context for the reply.
            final byte[] response = SapHostManagerApplication.createDataNetworkResponse(getDevice().getAddress(), networkType);
            final SapPostAuthRecord reply = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                    hostManagerAcceptor.getApplicationSessionId(), 0), response);
            LOG.info("Gear S3 Host Manager data-network response: type={} (0=none, 1=mobile, 2=wifi, 3=other)", networkType);
            createTransactionBuilder("gearS3HostManagerDataNetworkResponse")
                    .write(SapPostAuthRecordCodec.encode(reply)).queue();
            return;
        }
        LOG.debug("Gear S3 Host Manager startup ignored unsupported message msgId={}", message.getMessageId());
    }

    @SuppressWarnings("deprecation")
    private int currentHostNetworkType() {
        try {
            final ConnectivityManager manager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
            if (active == null || !active.isConnected()) return 0;
            if (active.getType() == ConnectivityManager.TYPE_MOBILE) return 1;
            if (active.getType() == ConnectivityManager.TYPE_WIFI) return 2;
            return 3;
        } catch (final SecurityException e) {
            LOG.warn("Gear S3 cannot read host network state; reporting no network", e);
            return 0;
        }
    }

    private void startHostManagerHostStatusResponse(final String type) {
        if (hostManagerAppCipher == null) throw new IllegalStateException("Host Manager AppCipher missing after session acceptance");
        final SapHostManagerApplication.HostStatusValues values = new SapHostManagerApplication.HostStatusValues(
                getDevice().getAddress(), type, bluetoothAddressString(), bluetoothAdapterName(), Build.VERSION.RELEASE,
                Build.MODEL, Build.DISPLAY, Integer.toString(Build.VERSION.SDK_INT));
        final byte[] logical = hostManagerApplication.createHostStatusResponse(values);
        if (logical == null) return;
        final SapHostManagerApplication.SecureFrame frame = SapHostManagerApplication.protectHostStatus(hostManagerAppCipher, logical);
        Arrays.fill(logical, (byte) 0);
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                hostManagerAcceptor.getApplicationSessionId(), 0), frame.getBody());
        final byte[] encoded = SapPostAuthRecordCodec.encode(record);
        LOG.info("Gear S3 Host Manager TX secure mgr_host_status_res: providerComponent=0x{} consumerComponent=0x{} session={} sdkHeader=0x{} logicalLength={} protectedLength={} txSequence={}",
                Integer.toHexString(hostManagerRegistration.getComponentId()),
                Integer.toHexString(hostManagerAcceptor.getRemoteDescriptor().getComponentId()),
                hostManagerAcceptor.getApplicationSessionId(), frame.isCompressed() ? "14" : "04",
                frame.getLogicalLength(), frame.getProtectedLength(), frame.getSequence());
        createTransactionBuilder("gearS3HostManagerHostStatusResponse").write(encoded).queue();
    }

    private void startHostManagerReadyForRestoreRequest() {
        final byte[] body = hostManagerApplication.createReadyForRestoreRequest(getDevice().getAddress());
        if (body == null) return;
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                hostManagerAcceptor.getApplicationSessionId(), 0), body);
        final byte[] encoded = SapPostAuthRecordCodec.encode(record);
        LOG.info("Gear S3 Host Manager TX mgr_wearable_ready_for_restore_req: session={} deviceid=<current Gear>",
                hostManagerAcceptor.getApplicationSessionId());
        LOG.debug("Gear S3 Host Manager TX mgr_wearable_ready_for_restore_req wire={}", GB.hexdump(encoded));
        createTransactionBuilder("gearS3HostManagerReadyForRestoreRequest").write(encoded).queue();
    }

    /** Select notification attributes for image, action, and conversation metadata. */
    private static final SapNotificationServiceCodec.AttributeProfile NOTIFICATION_ATTRIBUTE_PROFILE =
            SapNotificationServiceCodec.AttributeProfile.COMPATIBILITY;

    @Override
    public synchronized void onNotification(final NotificationSpec notificationSpec) {
        if (notificationSpec == null) return;
        if (notificationServiceAcceptor == null || notificationServiceAcceptor.getState() != SapNotificationServiceAcceptor.State.READY) {
            queuePendingNotification(notificationSpec);
            LOG.debug("Gear S3 NotificationService queued notification id={} while state={}", notificationSpec.getId(),
                    notificationServiceAcceptor == null ? "DISCONNECTED" : notificationServiceAcceptor.getState());
            return;
        }
        prepareAndSendNotification(notificationSpec);
    }

    @Override
    public synchronized void onDeleteNotification(final int id) {
        removePendingNotification(id);
        final String activeWindowId = notificationIdToWindowId.remove(id);
        final String preparingWindowId = notificationPreparingIds.remove(id);
        final String windowId = activeWindowId == null ? preparingWindowId : activeWindowId;
        if (windowId != null) notificationPreparationTokens.remove(windowId);
        final NotificationRecord existing = windowId == null ? null : notifications.remove(windowId);
        if (windowId != null) {
            notificationDelta.removed(windowId, android.os.SystemClock.elapsedRealtime());
            notificationReliableAckHandler.postDelayed(() -> {
                synchronized (GearS3DeviceSupport.this) {
                    notificationDelta.expire(android.os.SystemClock.elapsedRealtime());
                }
            }, GearNotificationDelta.REMOVAL_GRACE_MS);
        }
        if (existing == null) {
            LOG.debug("Gear S3 NotificationService ignored removal of unknown notification id={}", id);
            return;
        }
        notificationIdToWindowId.remove(existing.id);
        if (notificationServiceAcceptor == null || notificationServiceAcceptor.getState() != SapNotificationServiceAcceptor.State.READY) {
            LOG.debug("Gear S3 NotificationService retained local removal id={} until reconnect; no stale dynamic session used", id);
            return;
        }
        final SapNotificationServiceCodec.Message message = SapNotificationServiceCodec.delete(existing.sequence, existing.windowId);
        sendNotificationMessage(message, "REMOVE", existing.id, NOTIFICATION_ATTRIBUTE_PROFILE);
    }

    private void queuePendingNotification(final NotificationSpec notificationSpec) {
        final String windowId = notificationWindowId(notificationSpec);
        final java.util.Iterator<NotificationSpec> iterator = pendingNotifications.iterator();
        while (iterator.hasNext()) if (notificationWindowId(iterator.next()).equals(windowId)) iterator.remove();
        while (pendingNotifications.size() >= MAX_PENDING_NOTIFICATIONS) pendingNotifications.removeFirst();
        pendingNotifications.addLast(notificationSpec.copyOf());
    }

    private void removePendingNotification(final int id) {
        final java.util.Iterator<NotificationSpec> iterator = pendingNotifications.iterator();
        while (iterator.hasNext()) if (iterator.next().getId() == id) iterator.remove();
    }

    private void flushPendingNotifications() {
        while (!pendingNotifications.isEmpty() && notificationServiceAcceptor.getState() == SapNotificationServiceAcceptor.State.READY) {
            prepareAndSendNotification(pendingNotifications.removeFirst());
        }
    }

    /** Bitmap decode/resize/PNG compression stays off the Bluetooth/service thread; only ordered wire TX returns here. */
    private void prepareAndSendNotification(final NotificationSpec spec) {
        final NotificationSpec immutable = spec.copyOf();
        final String windowId = notificationWindowId(immutable);
        final long token = ++notificationPreparationToken;
        notificationPreparationTokens.put(windowId, token);
        notificationPreparingIds.values().removeIf(windowId::equals);
        notificationPreparingIds.put(immutable.getId(), windowId);
        final long generation = sapDispatchGeneration;
        try {
            notificationImageExecutor.execute(() -> {
                if (sapDispatchDisposed || generation != sapDispatchGeneration) {
                    return;
                }
                PreparedNotificationImages prepared = null;
                String stage = "ICON";
                try {
                    final byte[] icon = applicationThumbnail(nonEmpty(immutable.getSourceAppId(), "nodomain.freeyourgadget.gadgetbridge"));
                    stage = "PERSON";
                    final byte[] person = notificationLargeIcon(immutable);
                    stage = "MEDIA";
                    final PreparedBigPicture media = notificationBigPictureCompatibility(immutable);
                    prepared = new PreparedNotificationImages(icon, person, media);
                } catch (final RuntimeException e) {
                    LOG.warn("Gear S3 notification preparation failed: token={} generation={} stage={}",
                            token, generation, stage, redactedDiagnosticException(e));
                }
                final PreparedNotificationImages images = prepared;
                notificationReliableAckHandler.post(() -> {
                    synchronized (GearS3DeviceSupport.this) {
                        final Long current = notificationPreparationTokens.get(windowId);
                        final String dropped = sapDispatchDisposed ? "disposed"
                                : generation != sapDispatchGeneration ? "stale-generation"
                                : current == null || current != token ? "superseded-or-deleted"
                                : notificationServiceAcceptor == null || notificationServiceAcceptor.getState() != SapNotificationServiceAcceptor.State.READY ? "not-ready"
                                : null;
                        if (dropped != null) {
                            return;
                        }
                        notificationPreparationTokens.remove(windowId);
                        notificationPreparingIds.remove(immutable.getId());
                        if (images == null) {
                            return;
                        }
                        try {
                            sendOrUpdateNotification(immutable, images);
                        } catch (final RuntimeException e) {
                            LOG.warn("Gear S3 prepared notification could not be sent: token={} generation={}",
                                    token, generation, redactedDiagnosticException(e));
                            throw e;
                        }
                    }
                });
            });
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            notificationPreparationTokens.remove(windowId);
            notificationPreparingIds.remove(immutable.getId());
            LOG.warn("Gear S3 notification preparation rejected: token={} generation={} workerShutdown={}",
                    token, generation, notificationImageExecutor.isShutdown(), redactedDiagnosticException(e));
        }
    }

    private void sendOrUpdateNotification(final NotificationSpec spec, final PreparedNotificationImages images) {
        final String windowId = notificationWindowId(spec);
        final NotificationRecord previous = notifications.get(windowId);
        final int sequence = allocateNotificationSequence();
        final String packageName = nonEmpty(spec.getSourceAppId(), "nodomain.freeyourgadget.gadgetbridge");
        final int appId = notificationIdentityStore.appIdFor(packageName);
        // Use the Android notification key as the stable window identity.
        // This key, not the protocol sequence or integer notification id, is the replacement identity.
        final String title = firstNonEmpty(spec.getTitle(), spec.getSubject(), spec.getSourceName());
        final String body = firstNonEmpty(spec.getBody(), spec.getSubject(), "");
        final GearNotificationActions wearableActions = GearNotificationActions.update(windowId, packageName,
                spec.getAttachedActions(), previous == null ? null : previous.wearableActions,
                () -> nextWearableActionId++);
        final String panelActions = notificationPanelActionsJson(spec);
        final String wearableExtender = notificationWearableExtenderJson(wearableActions);
        boolean popup = true;
        int oldSequence = previous == null ? -1 : previous.sequence;
        if (spec.getSamsungMessagingSnapshot() != null) {
            try {
                final JSONArray snapshot = new JSONArray(spec.getSamsungMessagingSnapshot());
                final Map<String, Boolean> fingerprints = new java.util.LinkedHashMap<>();
                for (int i = 0; i < snapshot.length(); i++) {
                    final JSONObject item = snapshot.getJSONObject(i);
                    // Media URI is intentionally excluded: deferred pixels do not make a new message.
                    final String identity = new JSONArray().put(item.getLong("time")).put(item.getString("sender"))
                            .put(item.getString("text")).toString();
                    final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    fingerprints.put(GB.hexdump(digest), item.getBoolean("self"));
                }
                final GearNotificationDelta.Decision delta = notificationDelta.update(windowId, fingerprints, android.os.SystemClock.elapsedRealtime());
                popup = delta.alert();
                if (previous == null) oldSequence = delta.previousSequence;
                notificationDelta.sent(windowId, sequence);
                LOG.info("conversationDelta: key={} newMessages={} self={} remote={} metadataOnly={} alert={} reason={}",
                        windowId, delta.self + delta.remote, delta.self, delta.remote, delta.self + delta.remote == 0,
                        popup, popup ? "REMOTE_MESSAGE" : delta.self > 0 ? "SELF_ONLY" : "METADATA_ONLY");
            } catch (final Exception e) {
                LOG.warn("Unable to classify notification delta; retaining normal alert policy");
            }
        }
        // Select against the fully populated notification (including actions/history), not a
        // guessed image budget. Attr TLV itself costs type + BE24 length.
        final SapNotificationServiceCodec.Message withoutBigPicture = createNotificationMessage(spec, sequence, appId, windowId, images,
                panelActions, wearableExtender, null, popup);
        final int availableBigPictureBytes = SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH - 5
                - SapNotificationServiceCodec.encode(withoutBigPicture).length - 4;
        final byte[] bigPicture = images.bigPicture == null ? null : images.bigPicture.largestFitting(availableBigPictureBytes);
        if (bigPicture != null) {
            LOG.info("Gear BigPicture compatibility experiment: mime={} sourceDimensions={}x{} sourceBytes={} finalDimensions={}x{} pngBytes={} attr23=true",
                    images.bigPicture.mime, images.bigPicture.sourceWidth, images.bigPicture.sourceHeight, images.bigPicture.sourceBytes,
                    images.bigPicture.widthFor(bigPicture), images.bigPicture.heightFor(bigPicture), bigPicture.length);
        }
        final SapNotificationServiceCodec.Message message = createNotificationMessage(spec, sequence, appId, windowId, images,
                panelActions, wearableExtender, bigPicture, popup);
        final SapNotificationServiceCodec.Message styleRemoval = GearNotificationDelta.styleRemoval(previous == null ? null : previous.message, message);
        if (styleRemoval != null && !fitsPostAuthPayload(SapNotificationServiceCodec.encode(message))) {
            LOG.warn("Gear NotificationService oversized replacement skipped before style removal");
            return;
        }
        LOG.info("mediaStyle: latestMessageMedia={} mime={} attr23={}", spec.getSamsungBigPictureMediaUri() != null,
                spec.getSamsungBigPictureMediaMimeType(), bigPicture != null);
        if (styleRemoval != null) {
            // Compatibility policy using the established Gear-only DELETE; no Android dismissal.
            LOG.info("Gear NotificationService style transition: REMOVE oldSequence={} before newSequence={}", previous.sequence, sequence);
            sendNotificationMessage(styleRemoval, "STYLE_REMOVE", previous.id, NOTIFICATION_ATTRIBUTE_PROFILE);
        }
        if (previous != null) notificationIdToWindowId.remove(previous.id);
        wearableActions.recordSequence(sequence);
        notifications.put(windowId, new NotificationRecord(spec.getId(), sequence, windowId, appId, spec.copyOf(), images, message, wearableActions));
        notificationIdToWindowId.put(spec.getId(), windowId);
        LOG.info("Gear S3 NotificationService {} id={} key={} windowId={} oldSequence={} newSequence={} protocolVersion=15 attributeProfile={} titleLength={} bodyLength={} sourceDerivedPhysicallyUnvalidated=true",
                oldSequence < 0 ? "CREATE" : "UPDATE", spec.getId(), spec.getKey(), windowId, oldSequence, sequence, NOTIFICATION_ATTRIBUTE_PROFILE,
                title == null ? 0 : title.length(), body.length());
        sendNotificationMessage(message, oldSequence < 0 ? "CREATE" : "UPDATE", spec.getId(), NOTIFICATION_ATTRIBUTE_PROFILE);
    }

    private SapNotificationServiceCodec.Message createNotificationMessage(final NotificationSpec spec, final int sequence,
                                                                            final int appId, final String windowId, final PreparedNotificationImages images,
                                                                            final String panelActions, final String wearableExtender,
                                                                            final byte[] bigPicture, final boolean popup) {
        final String packageName = nonEmpty(spec.getSourceAppId(), "nodomain.freeyourgadget.gadgetbridge");
        final String title = firstNonEmpty(spec.getTitle(), spec.getSubject(), spec.getSourceName());
        final String body = firstNonEmpty(spec.getBody(), spec.getSubject(), "");
        final byte[] largeIcon = images.largeIcon;
        final String iconUri = largeIcon == null ? null : notificationImageUri(largeIcon);
        return SapNotificationServiceCodec.createRich(sequence, appId, popup, NOTIFICATION_ATTRIBUTE_PROFILE, packageName,
                nonEmpty(spec.getSourceName(), packageName), title, body, spec.getWhen(), spec.getId(), windowId,
                spec.getCategory(), spec.getFlags(), images.applicationThumbnail, largeIcon, iconUri,
                panelActions, wearableExtender, spec.getMessagingStyleJson(), bigPicture);
    }

    /**
     * Optional attr-23 BigPicture conversion for the newest MessagingStyle image.
     * This does not alter attr 19, 40, or 44.
     */
    private PreparedBigPicture notificationBigPictureCompatibility(final NotificationSpec spec) {
        final String uriText = spec.getSamsungBigPictureMediaUri();
        final String mime = spec.getSamsungBigPictureMediaMimeType();
        if (uriText == null || mime == null || !mime.startsWith("image/")) return null;
        try {
            final Uri uri = Uri.parse(uriText);
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                if (input == null) return null;
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 32_000_000L) {
                LOG.warn("Gear BigPicture compatibility experiment: invalid or oversized source image mime={}", mime);
                return null;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = bitmapSampleSize(bounds.outWidth, bounds.outHeight, 720);
            final CountingInputStream input;
            try (InputStream raw = getContext().getContentResolver().openInputStream(uri)) {
                if (raw == null) return null;
                input = new CountingInputStream(raw, 8 * 1024 * 1024);
                final Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
                if (decoded == null) return null;
                try {
                    return encodeBigPictureCompatibility(decoded, mime, bounds.outWidth, bounds.outHeight, input.getCount());
                } finally {
                    decoded.recycle();
                }
            }
        } catch (final Exception e) {
            // URI and message content are deliberately not logged.
            LOG.warn("Gear BigPicture compatibility experiment: unable to read/decode MessagingStyle image mime={}", mime, redactedDiagnosticException(e));
            return null;
        }
    }

    /** Scale to width 360 at PNG quality 50, with retries to fit the transport bound. */
    private PreparedBigPicture encodeBigPictureCompatibility(final Bitmap source, final String mime, final int sourceWidth,
                                                              final int sourceHeight, final long sourceBytes) {
        int width = Math.min(360, source.getWidth());
        final ArrayList<PreparedBigPicture.Candidate> candidates = new ArrayList<>();
        for (int attempt = 0; attempt < 7 && width >= 48; attempt++, width = Math.max(48, (width * 3) / 4)) {
            final int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
            final Bitmap scaled = source.getWidth() == width ? source : Bitmap.createScaledBitmap(source, width, height, true);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 50, output);
            if (scaled != source) scaled.recycle();
            final byte[] png = output.toByteArray();
            // Preserve room for the rest of a rich WhatsApp notification. A final exact post-auth
            // selection below uses the complete message; this merely bounds worker memory.
            if (png.length <= 48 * 1024) {
                candidates.add(new PreparedBigPicture.Candidate(png, width, height));
            }
        }
        if (!candidates.isEmpty()) return new PreparedBigPicture(candidates, mime, sourceWidth, sourceHeight, sourceBytes);
        LOG.warn("Gear BigPicture compatibility experiment: image cannot fit bounded attr23 budget mime={} sourceDimensions={}x{} sourceBytes={}",
                mime, sourceWidth, sourceHeight, sourceBytes);
        return null;
    }

    private static int bitmapSampleSize(final int width, final int height, final int target) {
        int sample = 1;
        while (width / sample > target || height / sample > target) sample <<= 1;
        return sample;
    }

    private static String notificationWindowId(final NotificationSpec spec) {
        final String packageName = nonEmpty(spec.getSourceAppId(), "nodomain.freeyourgadget.gadgetbridge");
        return nonEmpty(spec.getKey(), packageName + "#" + spec.getId());
    }

    /** Samsung attr 19 is deliberately distinct from attr 6's application thumbnail. */
    private byte[] notificationLargeIcon(final NotificationSpec spec) {
        if (spec.getAvatarIcon() == null) return null;
        try {
            final Drawable drawable = spec.getAvatarIcon().loadDrawable(getContext());
            if (drawable == null) return null;
            final int sourceWidth = Math.max(1, drawable.getIntrinsicWidth());
            final int sourceHeight = Math.max(1, drawable.getIntrinsicHeight());
            final Bitmap source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(source);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            final int width = Math.min(360, source.getWidth());
            final int height = source.getWidth() > 360 ? Math.max(1, Math.round(source.getHeight() * (360f / source.getWidth()))) : source.getHeight();
            final Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, false);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 50, output);
            if (scaled != source) scaled.recycle();
            source.recycle();
            final byte[] result = output.toByteArray();
            LOG.debug("Gear S3 NotificationService large/person image: original={}x{} final={}x{} bytes={} format=PNG quality=50",
                    sourceWidth, sourceHeight, width, height, result.length);
            return result;
        } catch (final Exception e) {
            LOG.warn("Gear S3 NotificationService large/person icon unavailable", redactedDiagnosticException(e));
            return null;
        }
    }

    /** The URI is an opaque local cache key in Samsung's inline image package; it is not a Samsung identifier. */
    private static String notificationImageUri(final byte[] image) {
        return "content://nodomain.freeyourgadget.gadgetbridge.notification/image/" + Integer.toUnsignedString(Arrays.hashCode(image), 16);
    }

    /** attr 16 is Samsung's ordinary panel-action format; WearableExtender actions belong in attr 41. */
    private static String notificationPanelActionsJson(final NotificationSpec spec) {
        if (spec.getAttachedActions() == null) return null;
        final JSONArray actions = new JSONArray();
        final Set<String> titles = new HashSet<>();
        int id = 0;
        for (final NotificationSpec.Action action : spec.getAttachedActions()) {
            if (action == null || !isSamsungPanelAction(action) || !titles.add(nonEmpty(action.getTitle(), "")) || id >= 3) continue;
            try {
                final JSONObject object = new JSONObject();
                object.put("TITLE", nonEmpty(action.getTitle(), "")); object.put("ID", id++); object.put("ICON_URI", "");
                if (action.getRemoteInputKey() != null) {
                    final JSONArray inputs = new JSONArray(); final JSONObject input = new JSONObject();
                    if (action.getRemoteInputLabel() != null) input.put("label", action.getRemoteInputLabel());
                    input.put("key", action.getRemoteInputKey()); input.put("allow_free_input", action.getRemoteInputAllowFreeInput() ? 1 : 0);
                    if (action.getRemoteInputChoices() != null) { final JSONArray choices = new JSONArray(); for (final String choice : action.getRemoteInputChoices()) choices.put(choice); input.put("choices", choices); }
                    inputs.put(input); object.put("remote_inputs", inputs);
                }
                actions.put(object);
            } catch (final JSONException e) { throw new IllegalStateException("Unable to encode Samsung NotificationService action", e); }
        }
        return actions.length() == 0 ? null : actions.toString();
    }

    /** Exact WearableJsonBuilder schema; IDs survive compatible same-window updates. */
    static String notificationWearableExtenderJson(final GearNotificationActions actionMap) {
        final JSONArray actions = new JSONArray();
        for (final Map.Entry<Integer, NotificationSpec.Action> entry : actionMap.entrySet()) {
            final NotificationSpec.Action action = entry.getValue();
            try {
                final JSONObject object = new JSONObject();
                object.put("title", nonEmpty(action.getTitle(), ""));
                object.put("action_id", entry.getKey());
                if (action.getRemoteInputKey() != null) {
                    final JSONArray inputs = new JSONArray(); final JSONObject input = new JSONObject();
                    if (action.getRemoteInputLabel() != null) input.put("label", action.getRemoteInputLabel());
                    input.put("key", action.getRemoteInputKey());
                    input.put("allow_free_input", action.getRemoteInputAllowFreeInput() ? 1 : 0);
                    if (action.getRemoteInputChoices() != null) { final JSONArray choices = new JSONArray(); for (final String choice : action.getRemoteInputChoices()) choices.put(choice); input.put("choices", choices); }
                    inputs.put(input); object.put("remote_inputs", inputs);
                }
                actions.put(object);
            } catch (final JSONException e) { throw new IllegalStateException("Unable to encode Samsung NotificationService WearableExtender action", e); }
        }
        if (actions.length() == 0) return null;
        try { final JSONObject result = new JSONObject(); result.put("actions", actions); result.put("hint_hide_icon", false); return result.toString(); }
        catch (final JSONException e) { throw new IllegalStateException("Unable to encode Samsung NotificationService WearableExtender", e); }
    }

    private static boolean isSamsungPanelAction(final NotificationSpec.Action action) {
        return action.getType() == NotificationSpec.Action.TYPE_CUSTOM_REPLY || action.getType() == NotificationSpec.Action.TYPE_CUSTOM_SIMPLE;
    }

    private static NotificationSpec.Action findSamsungAction(final NotificationSpec spec, final int requestedId) {
        if (spec.getAttachedActions() == null || requestedId < 0) return null;
        final Set<String> titles = new HashSet<>(); int id = 0;
        for (final NotificationSpec.Action action : spec.getAttachedActions()) {
            if (action == null || !isSamsungPanelAction(action) || !titles.add(nonEmpty(action.getTitle(), "")) || id >= 3) continue;
            if (id++ == requestedId) return action;
        }
        return null;
    }

    /** Encode attr 6 as a 144x144 PNG at quality 50. */
    private byte[] applicationThumbnail(final String packageName) {
        final byte[] cached = applicationThumbnailCache.get(packageName);
        if (cached != null) return Arrays.copyOf(cached, cached.length);
        try {
            final Drawable drawable = getContext().getPackageManager().getApplicationIcon(packageName);
            final int width = Math.max(1, drawable.getIntrinsicWidth());
            final int height = Math.max(1, drawable.getIntrinsicHeight());
            final Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(source);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            final Bitmap scaled = Bitmap.createScaledBitmap(source, 144, 144, false);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 50, output);
            if (scaled != source) scaled.recycle();
            source.recycle();
            final byte[] thumbnail = output.toByteArray();
            applicationThumbnailCache.put(packageName, Arrays.copyOf(thumbnail, thumbnail.length));
            LOG.debug("Gear S3 NotificationService app thumbnail: package={} bytes={} format=PNG dimensions=144x144", packageName, thumbnail.length);
            return thumbnail;
        } catch (final Exception e) {
            LOG.warn("Gear S3 NotificationService app thumbnail unavailable for {}; sending no fabricated icon", packageName, redactedDiagnosticException(e));
            return null;
        }
    }

    private void sendNotificationMessage(final SapNotificationServiceCodec.Message message, final String operation, final int id,
                                         final SapNotificationServiceCodec.AttributeProfile profile) {
        SapNotificationServiceCodec.Message outgoing = message;
        byte[] logical = SapNotificationServiceCodec.encode(outgoing);
        if (!fitsPostAuthPayload(logical)) {
            final SapNotificationServiceCodec.Attribute bigPicture = outgoing.findAttribute(SapNotificationServiceCodec.ATTR_BIG_PICTURE);
            if (bigPicture != null) {
                LOG.warn("Gear BigPicture compatibility experiment: attr23 omitted because final record exceeds limit: valueBytes={} totalTlv={} postAuthPayload={} limit={}",
                        bigPicture.getValue().length, logical.length, 2 + 3 + logical.length, SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH);
                outgoing = SapNotificationServiceCodec.withoutAttribute(outgoing, SapNotificationServiceCodec.ATTR_BIG_PICTURE);
                logical = SapNotificationServiceCodec.encode(outgoing);
            }
        }
        if (!fitsPostAuthPayload(logical)) {
            final SapNotificationServiceCodec.Attribute avatar = outgoing.findAttribute(SapNotificationServiceCodec.ATTR_LARGE_ICON);
            if (avatar != null) {
                LOG.warn("Gear S3 NotificationService {} id={} omitting optional attr19: valueBytes={} totalTlv={} postAuthPayload={} limit={}",
                        operation, id, avatar.getValue().length, logical.length, 2 + 3 + logical.length, SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH);
                outgoing = SapNotificationServiceCodec.withoutAttribute(outgoing, SapNotificationServiceCodec.ATTR_LARGE_ICON);
                logical = SapNotificationServiceCodec.encode(outgoing);
            }
        }
        logNotificationSerialization(operation, id, outgoing, logical.length);
        final SapNotificationServiceCodec.Attribute encodedBigPicture = outgoing.findAttribute(SapNotificationServiceCodec.ATTR_BIG_PICTURE);
        if (encodedBigPicture != null) {
            LOG.info("Gear BigPicture compatibility experiment: attr23=true pngBytes={} totalTlv={}",
                    encodedBigPicture.getValue().length, logical.length);
        }
        if (!fitsPostAuthPayload(logical)) {
            LOG.error("Gear S3 NotificationService {} id={} not sent: totalTlv={} reliableServicePayload={} postAuthPayload={} limit={}; preserving SAP/RFCOMM", operation, id,
                    logical.length, 3 + logical.length, 2 + 3 + logical.length, SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH);
            return;
        }
        final int reliableSequence = nextReliableNotificationSequence();
        final byte[] body = SapNotificationServiceCodec.encodeServiceFrame(reliableSequence, logical);
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                notificationServiceAcceptor.getApplicationSessionId(), 0), body);
        final byte[] wire;
        try {
            wire = SapPostAuthRecordCodec.encode(record);
        } catch (final IllegalArgumentException e) {
            LOG.error("Gear S3 NotificationService {} id={} record encoder rejected bounded payload; preserving SAP/RFCOMM", operation, id, e);
            return;
        }
        LOG.debug("Gear S3 NotificationService {} id={} reliableSequence={} tlvLength={} attributeProfile={} wireBytes={}",
                operation, id, reliableSequence, logical.length, profile, wire.length);
        createTransactionBuilder("gearS3NotificationService" + operation).write(wire).queue();
    }

    private static boolean fitsPostAuthPayload(final byte[] logical) {
        // post-auth length covers its two-byte SAP header; body is reliable-seq(2), SDK header(1), logical TLV.
        return logical.length <= SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH - 5;
    }

    private static void logNotificationSerialization(final String operation, final int id,
                                                     final SapNotificationServiceCodec.Message message, final int totalTlv) {
        final StringBuilder attributes = new StringBuilder();
        for (final SapNotificationServiceCodec.Attribute attribute : message.getAttributes()) {
            if (attributes.length() > 0) attributes.append(' ');
            attributes.append(attribute.getType()).append('=').append(attribute.getValue().length);
        }
        LOG.info("NotificationService serialized {}: id={} seq={} totalTlv={} attrs=[{}] reliableServicePayload={} postAuthPayload={} limit={}",
                operation, id, message.getSequence(), totalTlv, attributes, 3 + totalTlv, 2 + 3 + totalTlv,
                SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH);
    }

    private int nextReliableNotificationSequence() { final int current = notificationReliableTxSequence; notificationReliableTxSequence = current >= 0xffff ? 1 : current + 1; return current; }
    private int allocateNotificationSequence() { notificationSequence = (notificationSequence + 1) % 0x40000; return notificationSequence; }
    private NotificationRecord findNotificationBySequence(final int sequence) { for (final NotificationRecord record : notifications.values()) if (record.sequence == sequence) return record; return null; }
    private static String nonEmpty(final String value, final String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private static String firstNonEmpty(final String... values) { for (final String value : values) if (value != null && !value.isEmpty()) return value; return ""; }
    private static final class PreparedNotificationImages { private final byte[] applicationThumbnail, largeIcon; private final PreparedBigPicture bigPicture; private PreparedNotificationImages(final byte[] applicationThumbnail, final byte[] largeIcon, final PreparedBigPicture bigPicture) { this.applicationThumbnail = applicationThumbnail; this.largeIcon = largeIcon; this.bigPicture = bigPicture; } }
    private static final class PreparedBigPicture { private final List<Candidate> candidates; private final String mime; private final int sourceWidth, sourceHeight; private final long sourceBytes; private PreparedBigPicture(final List<Candidate> candidates, final String mime, final int sourceWidth, final int sourceHeight, final long sourceBytes) { this.candidates = candidates; this.mime = mime; this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight; this.sourceBytes = sourceBytes; } private byte[] largestFitting(final int maximum) { for (final Candidate candidate : candidates) if (candidate.png.length <= maximum) return candidate.png; return null; } private int widthFor(final byte[] value) { for (final Candidate candidate : candidates) if (candidate.png == value) return candidate.width; return 0; } private int heightFor(final byte[] value) { for (final Candidate candidate : candidates) if (candidate.png == value) return candidate.height; return 0; } private static final class Candidate { private final byte[] png; private final int width, height; private Candidate(final byte[] png, final int width, final int height) { this.png = png; this.width = width; this.height = height; } } }
    private static final class CountingInputStream extends FilterInputStream { private final long maximum; private long count; private CountingInputStream(final InputStream input, final long maximum) { super(input); this.maximum = maximum; } @Override public int read() throws IOException { final int value = super.read(); if (value >= 0) increment(1); return value; } @Override public int read(final byte[] buffer, final int offset, final int length) throws IOException { final int read = super.read(buffer, offset, length); if (read > 0) increment(read); return read; } @Override public long skip(final long length) throws IOException { final long skipped = super.skip(length); if (skipped > 0) increment((int) Math.min(Integer.MAX_VALUE, skipped)); return skipped; } private void increment(final int read) throws IOException { count += read; if (count > maximum) throw new IOException("Image source exceeds bound"); } private long getCount() { return count; } }
    private static final class NotificationRecord { private final int id, sequence, appId; private final String windowId; private final NotificationSpec spec; private final PreparedNotificationImages images; private final SapNotificationServiceCodec.Message message; private final GearNotificationActions wearableActions; private NotificationRecord(final int id, final int sequence, final String windowId, final int appId, final NotificationSpec spec, final PreparedNotificationImages images, final SapNotificationServiceCodec.Message message, final GearNotificationActions wearableActions) { this.id = id; this.sequence = sequence; this.windowId = windowId; this.appId = appId; this.spec = spec; this.images = images; this.message = message; this.wearableActions = wearableActions; } }

    private static int countServices(final List<nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapAgentDescriptor> agents) {
        int count = 0;
        for (final nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapAgentDescriptor agent : agents) count += agent.getServices().size();
        return count;
    }
    private void ensureWsm(final WsmV2Client.State expected) {
        if (wsm == null || wsm.getState() != expected) throw new IllegalStateException("Unexpected WSM state");
    }

    private void startFreshWsm(final String reason) throws Exception {
        if (wsm != null || watchPeerId == null || sapClientId == null) {
            throw new IllegalStateException("Missing PD identity or duplicate WSM INIT");
        }
        wsm = new WsmV2Client(watchPeerId, sapClientId);
        sendWsm(0x20, wsm.createInit());
        LOG.debug("Gear S3 {}", reason);
    }

    private static void requirePdAction(final GearS3PdStateMachine.Action actual,
                                        final GearS3PdStateMachine.Action expected) {
        if (actual != expected) {
            throw new IllegalStateException("Unexpected PD transition action " + actual);
        }
    }

    @SuppressLint("MissingPermission")
    private String bluetoothName() {
        final BluetoothAdapter adapter = getBluetoothAdapter();
        return adapter.getName() == null ? "SAMSUNG" : adapter.getName();
    }

    @SuppressLint("MissingPermission")
    private byte[] bluetoothAddress() {
        return SapBcmFeatureExchange.utf8(bluetoothAddressString());
    }

    @SuppressLint("MissingPermission")
    private String bluetoothAddressString() {
        final BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || adapter.getAddress() == null || adapter.getAddress().isEmpty()) return null;
        return adapter.getAddress();
    }

    @SuppressLint("MissingPermission")
    private String bluetoothAdapterName() {
        final BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || adapter.getName() == null || adapter.getName().isEmpty()) return null;
        return adapter.getName();
    }

    private void clearHostManagerAppCipher() {
        if (hostManagerAppCipher != null) hostManagerAppCipher.clear();
        hostManagerAppCipher = null;
    }

    private void clearPedometerAppCipher() {
        healthDiagnostics.invalidate();
        if (pedometerSecureDecoder != null) pedometerSecureDecoder.clear();
        pedometerSecureDecoder = null;
        pedometerSecureEncoder = null;
    }

    @Override
    public synchronized void onFetchRecordedData(final int dataTypes) {
        LOG.info("Gear S3 health fetch requested: dataTypes={}", dataTypes);
        if (!GearS3HealthFetchState.requestsActivity(dataTypes)) {
            LOG.debug("Gear S3 health fetch ignored: no supported activity type requested");
            return;
        }
        if (commonSyncState.canCoalesceFetch()) {
            if (healthFetchState.attachToCommonSync(dataTypes)) {
                healthFetchHandler.removeCallbacks(healthFetchTimeoutRunnable);
                healthFetchHandler.postDelayed(healthFetchTimeoutRunnable, HEALTH_FETCH_TIMEOUT_MILLIS);
            } else if (healthFetchState.isInProgress()
                    && healthFetchState.getPhase() != GearS3HealthFetchState.Phase.WAIT_COMMON_SYNC) {
                healthFetchState.awaitingCommonSync();
            }
            LOG.info("Gear S3 health fetch: CommonSync already active; coalescing with current sync");
            return;
        }
        LOG.warn("Gear S3 health fetch cannot start while CommonSync is idle: no proven V4.51 host trigger; leaving API999 untouched");
    }

    private void handlePedometerServiceTermination(final SapServiceConnectionTermination termination) {
        if (!SapPedometerServiceRegistration.PROFILE.equals(termination.getProfile())
                || pedometerServiceConnector == null
                || !pedometerServiceConnector.matchesPeerTermination(termination)) {
            LOG.debug("Gear S3 ignoring unrelated SAP service termination: profile={}", termination.getProfile());
            return;
        }
        pedometerServiceConnector.clear();
        clearPedometerAppCipher();
        resetPedometerConnectionDiagnostics();
        final SapServiceConnectionTerminationResponse response = SapServiceConnectionCodec.terminationResponse(
                termination, SapServiceConnectionTerminationResponse.STATUS_SUCCESS);
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response));
        LOG.info("Gear S3 watch_pedometer termination accepted");
        createTransactionBuilder("gearS3WatchPedometerServiceTerminationResponse")
                .write(SapPostAuthRecordCodec.encode(record))
                .run(() -> {
                    synchronized (GearS3DeviceSupport.this) {
                        if (healthFetchState.isInProgress()) {
                            healthFetchState.awaitingConnection();
                            LOG.info("Gear S3 health fetch: reopening watch_pedometer session");
                            if (!startPedometerServiceConnection()) {
                                failHealthFetch("watch_pedometer descriptor unavailable after peer termination");
                            }
                        }
                    }
                })
                .queue();
    }

    private void handleWeatherServiceTermination(final SapServiceConnectionTermination termination) {
        final boolean fromConnector = weatherServiceConnector != null && weatherServiceConnector.matchesPeerTermination(termination);
        final boolean fromAcceptor = weatherServiceAcceptor != null && weatherServiceAcceptor.matchesPeerTermination(termination);
        if (!fromConnector && !fromAcceptor) {
            LOG.debug("Gear S3 ignoring unrelated weather service termination");
            return;
        }
        if (fromConnector) {
            recentlyClosedPhoneWeatherSession = weatherServiceConnector.getSessionId();
            recentlyClosedPhoneWeatherAt = SystemClock.elapsedRealtime();
            weatherServiceConnector.clear();
        }
        else weatherServiceAcceptor.clear();
        postWeatherWork(this::clearWeatherDeliverySession);
        final SapServiceConnectionTerminationResponse response = SapServiceConnectionCodec.terminationResponse(
                termination, SapServiceConnectionTerminationResponse.STATUS_SUCCESS);
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), SapServiceConnectionCodec.encode(response));
        LOG.info("Gear S3 weather service termination accepted; cached WeatherSpec retained");
        createTransactionBuilder("gearS3WeatherServiceTerminationResponse")
                .write(SapPostAuthRecordCodec.encode(record)).queue();
    }

    private void resetPedometerConnectionDiagnostics() {
        pedometerFirstPayloadLogged = false;
        pedometerManagerCapabilityResponded = false;
        pedometerRichCapabilityResponded = false;
    }

    private boolean completeHealthFetch() {
        if (healthFetchState.complete()) {
            healthFetchHandler.removeCallbacks(healthFetchTimeoutRunnable);
            LOG.info("Gear S3 health fetch completed");
            return true;
        }
        return false;
    }

    private void failHealthFetch(final String reason) {
        if (healthFetchState.fail()) {
            healthFetchHandler.removeCallbacks(healthFetchTimeoutRunnable);
            LOG.warn("Gear S3 health fetch failed/timed out: {}", reason);
        }
    }

    private static byte[] bluetoothVersion() {
        return Build.VERSION.SDK_INT > 22 ? new byte[]{6} : null;
    }

    /** Persistent SAP identity; it is not the adapter's controller address. */
    private String localPeerId() {
        if (sapClientId == null) throw new IllegalStateException("Missing SAP client identity");
        return sapClientId;
    }

    private synchronized void recoverContactsConnection(final GearContactsService requester) {
        if (contactsService != requester || !isConnected()) return;
        // Called only after bounded Contacts-only recovery and with no active Contacts FT.
        // Normal initialization recreates all dynamic SAP sessions on the fresh connection.
        LOG.warn("Gear S3 recycling RFCOMM after bounded Contacts handshake recovery; reconnecting this device through normal connection service");
        disconnect();
        getDevice().setUpdateState(GBDevice.State.WAITING_FOR_RECONNECT, getContext());
        GBApplication.deviceService(getDevice()).connect();
    }

    private void sendFindMyPhoneControl(final byte[] body) {
        final long generation = sapDispatchGeneration;
        createTransactionBuilder("gearS3FindMyPhoneControl")
                .run(socket -> generation == sapDispatchGeneration && !sapDispatchDisposed)
                .write(SapPostAuthRecordCodec.encode(new SapPostAuthRecord(
                        new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), body))).queue();
    }

    private void disposeFindMyPhone() {
        final GearFindMyPhoneService service = findMyPhoneService;
        findMyPhoneService = null;
        if (service != null) service.dispose();
    }

    @Override
    public synchronized void disconnect() {
        // Retire queued SAP work before the queue is removed. initializeDevice starts a new generation.
        sapTransportClosed = true;
        sapDispatchGeneration++;
        closeCalendarSession();
        disposeFindMyPhone();
        healthDiagnostics.close();
        // Underlying device loss differs from control-profile churn: cancel recovery callbacks
        // and preserve the existing stage-aware incomplete-transfer accounting.
        if (contactsService != null) { contactsService.dispose(); contactsService = null; }
        super.disconnect();
    }

    private void fail(final String reason, final Exception e) {
        sapDispatchGeneration++;
        LOG.warn("Gear S3 Milestone 1 failed: {}", reason, e);
        clearCalendarService();
        healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
        healthFetchHandler.removeCallbacks(healthFetchTimeoutRunnable);
        clearHostManagerAppCipher();
        clearPedometerAppCipher();
        commonSyncState.fail();
        failHealthFetch("SAP connection failed");
        secrets.clear();
        if (contactsService != null) { contactsService.dispose(); contactsService = null; }
        if (bcmServiceConnector != null) bcmServiceConnector.clear();
        if (pedometerServiceConnector != null) pedometerServiceConnector.clear();
        if (weatherServiceAcceptor != null) weatherServiceAcceptor.clear();
        if (weatherServiceConnector != null) weatherServiceConnector.clear();
        cancelWeatherConnectionTimeout();
        cancelWeatherAckTimeout();
        weatherPreResponseRecords.clear();
        weatherDeliveryExecutor.shutdownNow();
        clearNotificationServiceConnection();
        notificationImageExecutor.shutdownNow();
        bcmFeatureExchange = null;
        hostManagerApplication = null;
        disconnect();
        // Failure reporting must work even when a previous disconnect already removed the queue.
        // Publish after disconnect's NOT_CONNECTED update so reconnect is not overwritten.
        if (!sapDispatchDisposed && getDevice() != null) {
            getDevice().setUpdateState(GBDevice.State.WAITING_FOR_RECONNECT, getContext());
        }
    }

    @Override
    public synchronized void dispose() {
        disposeFindMyPhone();
        sapDispatchDisposed = true;
        sapDispatchGeneration++;
        clearCalendarService();
        calendarThread.quitSafely();
        sapApplicationExecutor.shutdownNow();
        notificationImageExecutor.shutdownNow();
        healthDiagnostics.close();
        // Debug queue/seed survive ordinary reconnect in memory; service disposal detaches its target.
        healthFetchHandler.removeCallbacks(healthFetchTimeoutRunnable);
        healthFetchHandler.removeCallbacks(commonSyncExpectationTimeoutRunnable);
        commonSyncState.fail();
        healthFetchState.fail();
        clearHostManagerAppCipher();
        clearPedometerAppCipher();
        secrets.clear();
        if (contactsService != null) { contactsService.dispose(); contactsService = null; }
        if (bcmServiceConnector != null) bcmServiceConnector.clear();
        if (pedometerServiceConnector != null) pedometerServiceConnector.clear();
        if (weatherServiceAcceptor != null) weatherServiceAcceptor.clear();
        if (weatherServiceConnector != null) weatherServiceConnector.clear();
        cancelWeatherConnectionTimeout();
        cancelWeatherAckTimeout();
        weatherPreResponseRecords.clear();
        weatherDeliveryExecutor.shutdownNow();
        clearNotificationServiceConnection();
        bcmFeatureExchange = null;
        hostManagerApplication = null;
        super.dispose();
    }

    private void clearNotificationServiceConnection() {
        notificationReliableAckHandler.removeCallbacks(notificationReliableAckRunnable);
        notificationReliableRxSession.reset();
        notificationLastInitialization = null;
        if (notificationServiceAcceptor != null) notificationServiceAcceptor.clear();
        notificationReliableTxSequence = 1;
        notificationSequence = 0;
        nextWearableActionId = 3;
        pendingNotifications.clear();
        notificationPreparationTokens.clear();
        notificationPreparingIds.clear();
        // Notification lifecycle identities deliberately remain only while this support instance lives;
        // no dynamic SAP session or per-connection transport state survives a reconnect.
        notifications.clear();
        notificationDelta.clear();
        notificationIdToWindowId.clear();
    }
}
