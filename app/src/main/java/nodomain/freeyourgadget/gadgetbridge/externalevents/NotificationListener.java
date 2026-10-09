/*  Copyright (C) 2015-2024 abettenburg, Andreas Böhler, Andreas Shimokawa,
    AndrewBedscastle, Arjan Schrijver, Carsten Pfeiffer, Daniel Dakhno, Daniele
    Gobbetti, Davis Mosenkovs, Dmitriy Bogdanov, Dmitry Markin, Frank Slezak,
    gnufella, Gordon Williams, Hasan Ammar, José Rebelo, Julien Pivotto,
    Kevin Richter, mamucho, Matthieu Baerts, mvn23, Normano64, Petr Kadlec,
    Petr Vaněk, Steffen Liebergeld, Taavi Eomäe, theghostofheathledger, t-m-w,
    veecue, Zhong Jianxin

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import de.greenrobot.dao.query.Query;
import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationFilter;
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationFilterDao;
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationFilterEntry;
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationFilterEntryDao;
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.GoogleMapsNotificationHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.AppNotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.LimitedQueue;
import nodomain.freeyourgadget.gadgetbridge.util.MediaManager;
import nodomain.freeyourgadget.gadgetbridge.util.NotificationUtils;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

import static nodomain.freeyourgadget.gadgetbridge.activities.NotificationFilterActivity.NOTIFICATION_FILTER_MODE_BLACKLIST;
import static nodomain.freeyourgadget.gadgetbridge.activities.NotificationFilterActivity.NOTIFICATION_FILTER_MODE_WHITELIST;
import static nodomain.freeyourgadget.gadgetbridge.activities.NotificationFilterActivity.NOTIFICATION_FILTER_SUBMODE_ALL;
import static nodomain.freeyourgadget.gadgetbridge.util.StringUtils.ensureNotNull;

public class NotificationListener extends NotificationListenerService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationListener.class);

    public static final String ACTION_DISMISS
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.dismiss";
    public static final String ACTION_DISMISS_ALL
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.dismiss_all";
    public static final String ACTION_OPEN
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.open";
    public static final String ACTION_MUTE
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.mute";
    public static final String ACTION_REPLY
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.reply";
    public static final String EXTRA_REPLY_RESULT_RECEIVER = "reply_result_receiver";

    private final LimitedQueue<Integer, NotificationAction> mActionLookup = new LimitedQueue<>(128);
    private final LimitedQueue<Integer, String> mPackageLookup = new LimitedQueue<>(64);
    private final LimitedQueue<Integer, Long> mNotificationHandleLookup = new LimitedQueue<>(128);
    private long lastPictureNotificationTime = 0;

    private final HashMap<String, Long> notificationBurstPrevention = new HashMap<>();
    private final HashMap<String, Long> notificationOldRepeatPrevention = new HashMap<>();

    private static final Set<String> GROUP_SUMMARY_WHITELIST = new HashSet<>() {{
        add("com.microsoft.office.lync15");
        add("com.skype.raider");
        add("mikado.bizcalpro");
    }};

    private static final Set<String> PHONE_CALL_APPS = new HashSet<>() {{
        add("com.android.dialer");
        add("com.android.incallui");
        add("com.asus.asusincallui");
        add("com.google.android.dialer");
        add("com.samsung.android.incallui");
        add("org.fossify.phone");
    }};

    private static final Set<String> NOTI_USE_TITLE_APPS = new HashSet<>() {{
        add("com.whatsapp");
        add("org.thoughtcrime.securesms");
    }};

    public static final ArrayList<String> notificationStack = new ArrayList<>();
    private static final ArrayList<Integer> notificationsActive = new ArrayList<>();

    private static final Set<String> supportedPictureMimeTypes = new HashSet<>() {{
        add("image/"); //for im.vector.app
        add("image/jpeg");
        add("image/png");
        add("image/gif");
        add("image/bmp");
        add("image/webp");
    }};

    private File notificationPictureCacheDirectory;

    private long activeCallPostTime;
    private int mLastCallCommand = CallSpec.CALL_UNDEFINED;

    private final Handler mHandler = new Handler();
    private Runnable mSetMusicInfoRunnable = null;
    private Runnable mSetMusicStateRunnable = null;

    private boolean isDreaming = false;

    private final GoogleMapsNotificationHandler googleMapsNotificationHandler = new GoogleMapsNotificationHandler();

    private final BroadcastReceiver mExportedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                LOG.warn("Got intent without action");
                return;
            }

            LOG.debug("Got action: {}", action);

            switch (action) {
                case Intent.ACTION_DREAMING_STARTED -> isDreaming = true;
                case Intent.ACTION_DREAMING_STOPPED -> isDreaming = false;
                default -> LOG.warn("Unknown action: {}", action);
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                LOG.warn("no action");
                return;
            }

            int handle = (int) intent.getLongExtra("handle", -1);
            switch (action) {
                case GBApplication.ACTION_QUIT:
                    stopSelf();
                    break;

                case ACTION_OPEN: {
                    StatusBarNotification[] sbns = NotificationListener.this.getActiveNotifications();
                    Long ts = mNotificationHandleLookup.lookup(handle);
                    if (ts == null) {
                        LOG.info("could not look up handle for open action");
                        break;
                    }

                    for (StatusBarNotification sbn : sbns) {
                        if (sbn.getPostTime() == ts) {
                            try {
                                PendingIntent pi = sbn.getNotification().contentIntent;
                                if (pi != null) {
                                    sendAllowingBackgroundActivityStart(pi);
                                }
                            } catch (final PendingIntent.CanceledException e) {
                                LOG.error("Failed to open notification {}", sbn.getId());
                            }
                        }
                    }
                    break;
                }
                case ACTION_MUTE:
                    String packageName = mPackageLookup.lookup(handle);
                    if (packageName == null) {
                        LOG.info("could not look up handle for mute action");
                        break;
                    }
                    LOG.info("going to mute {}", packageName);
                    if (GBApplication.getPrefs().getString("notification_list_is_blacklist", "true").equals("true")) {
                        GBApplication.addAppToNotifBlacklist(packageName);
                    } else {
                        GBApplication.removeFromAppsNotifBlacklist(packageName);
                    }
                    break;
                case ACTION_DISMISS: {
                    StatusBarNotification[] sbns = NotificationListener.this.getActiveNotifications();
                    Long ts = mNotificationHandleLookup.lookup(handle);
                    if (ts == null) {
                        LOG.info("could not look up handle for dismiss action");
                        break;
                    }
                    for (StatusBarNotification sbn : sbns) {
                        if (sbn.getPostTime() == ts) {
                            String key = sbn.getKey();
                            NotificationListener.this.cancelNotification(key);
                        }
                    }
                    break;
                }
                case ACTION_DISMISS_ALL:
                    NotificationListener.this.cancelAllNotifications();
                    break;
                case ACTION_REPLY:
                    final android.os.ResultReceiver replyResultReceiver = intent.getParcelableExtra(EXTRA_REPLY_RESULT_RECEIVER);
                    NotificationAction wearableAction = mActionLookup.lookup(handle);
                    String reply = intent.getStringExtra("reply");
                    if (wearableAction != null) {
                        PendingIntent actionIntent = wearableAction.getIntent();
                        if (actionIntent == null) {
                            LOG.warn("Action intent is null");
                            if (replyResultReceiver != null) replyResultReceiver.send(0, null);
                            break;
                        }

                        final RemoteInput remoteInput = wearableAction.getRemoteInput();

                        try {
                            LOG.info("Will send exec intent to remote application");

                            if (remoteInput != null) {
                                final Intent localIntent = new Intent();
                                localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                final Bundle extras = new Bundle();
                                extras.putCharSequence(remoteInput.getResultKey(), reply);
                                RemoteInput.addResultsToIntent(new RemoteInput[]{remoteInput}, localIntent, extras);
                                actionIntent.send(context, 0, localIntent);
                            } else {
                                sendAllowingBackgroundActivityStart(actionIntent);
                            }
                            mActionLookup.remove(handle);
                            if (replyResultReceiver != null) replyResultReceiver.send(1, null);
                        } catch (final PendingIntent.CanceledException e) {
                            LOG.warn("replyToLastNotification error", e);
                            if (replyResultReceiver != null) replyResultReceiver.send(0, null);
                        }
                    } else {
                        LOG.warn("Received ACTION_REPLY for handle {}, but cannot find the corresponding wearableAction", handle);
                        if (replyResultReceiver != null) replyResultReceiver.send(0, null);
                    }
                    break;
            }
        }
    };

    /// Send a PendingIntent, granting it our background activity start privileges, which
    /// Android 14 and later require to start an activity from the background
    private static void sendAllowingBackgroundActivityStart(final PendingIntent pendingIntent)
            throws PendingIntent.CanceledException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            final ActivityOptions activityOptions = ActivityOptions.makeBasic();
            final Bundle bundle = activityOptions.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle();
            pendingIntent.send(bundle);
        } else {
            pendingIntent.send();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBApplication.ACTION_QUIT);
        filterLocal.addAction(ACTION_OPEN);
        filterLocal.addAction(ACTION_DISMISS);
        filterLocal.addAction(ACTION_DISMISS_ALL);
        filterLocal.addAction(ACTION_MUTE);
        filterLocal.addAction(ACTION_REPLY);
        //noinspection deprecation
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        ContextCompat.registerReceiver(this, mExportedReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        createNotificationPictureCacheDirectory();
        cleanUpNotificationPictureProvider();
    }

    @Override
    public void onDestroy() {
        //noinspection deprecation
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        unregisterReceiver(mExportedReceiver);
        notificationStack.clear();
        notificationsActive.clear();
        cleanUpNotificationPictureProvider();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        onNotificationPosted(sbn, null);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        logNotification(sbn, true);

        notificationStack.remove(sbn.getPackageName());
        notificationStack.add(sbn.getPackageName());

        if (isServiceNotRunningAndShouldIgnoreNotifications()) return;

        final GBPrefs prefs = GBApplication.getPrefs();

        if (isOutsideNotificationTimes(prefs)) {
            return;
        }

        final boolean ignoreWorkProfile = prefs.getBoolean("notifications_ignore_work_profile", false);
        if (ignoreWorkProfile && isWorkProfile(sbn)) {
            LOG.debug("Ignoring notification from work profile");
            return;
        }

        final boolean mediaIgnoresAppList = prefs.getBoolean("notification_media_ignores_application_list", false);

        // If media notifications ignore app list, check them before
        if (mediaIgnoresAppList && handleMediaSessionNotification(sbn)) return;

        if (shouldIgnoreSource(sbn)) return;

        /* Check for navigation notifications and ignore if we're handling them */
        if (googleMapsNotificationHandler.handle(getApplicationContext(), sbn)) return;

        // If media notifications do NOT ignore app list, check them after
        if (!mediaIgnoresAppList && handleMediaSessionNotification(sbn)) return;

        int dndSuppressed = 0;
        if (rankingMap != null) {
            // Handle priority notifications for Do Not Disturb
            Ranking ranking = new Ranking();
            if (rankingMap.getRanking(sbn.getKey(), ranking)) {
                if (!ranking.matchesInterruptionFilter()) dndSuppressed = 1;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Digital Wellbeing app pause and similar restrictions can suppress notifications
                // changing the interruption filter, but the app will be marked as suspended
                if (ranking.isSuspended()) {
                    LOG.debug("Ignoring notification - app is suspended");
                    return;
                }

                // If importance is none, it should also not even show up
                if (ranking.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    LOG.debug("Ignoring notification - importance is NONE");
                    return;
                }
            }
        }

        if (prefs.getBoolean("notification_filter", false) && dndSuppressed == 1) {
            LOG.debug("Ignoring notification because of do not disturb");
            return;
        }

        if (NotificationCompat.CATEGORY_CALL.equals(sbn.getNotification().category)
                && prefs.getBoolean("notification_support_voip_calls", false)
                && (sbn.isOngoing() || shouldDisplayNonOngoingCallNotification(sbn))) {
            handleCallNotification(sbn);
            return;
        }

        final boolean hasPicture = notificationHasPicture(sbn.getNotification());

        if (shouldIgnoreNotification(sbn, false, hasPicture)) {
            if (!"com.sec.android.app.clockpackage".equals(sbn.getPackageName())) {  // workaround to allow phone alarm notification
                LOG.info("Ignoring notification: {}", sbn.getPackageName());          // need to fix
                return;
            }
        }

        String source = sbn.getPackageName();
        Notification notification = sbn.getNotification();

        Long notificationOldRepeatPreventionValue = notificationOldRepeatPrevention.get(source);
        if (notificationOldRepeatPreventionValue != null
                && notification.when <= notificationOldRepeatPreventionValue
                && !shouldIgnoreRepeatPrevention(sbn)
        ) {
            if (!hasPicture || notification.when <= lastPictureNotificationTime) {
                LOG.info("NOT processing notification, already sent newer notifications from this source.");
                return;
            } else {
                LOG.info("Allowing repeat notification, it has a picture now");
            }
        }

        // Ignore too frequent notifications, according to user preference
        final int notificationsTimeoutSeconds = prefs.getInt("notifications_timeout", 0);
        long curTime = System.nanoTime();
        Long notificationBurstPreventionValue = notificationBurstPrevention.get(source);

        // If this notification contains a picture, and we did not yet send a picture inside the timeout interval,
        // we should still send it (eg. notification updates)
        final boolean newPicture = hasPicture &&
                notification.when - lastPictureNotificationTime > TimeUnit.SECONDS.toMillis(notificationsTimeoutSeconds);

        if (notificationBurstPreventionValue != null) {
            long diff = curTime - notificationBurstPreventionValue;
            if (diff < TimeUnit.SECONDS.toNanos(notificationsTimeoutSeconds)) {
                if (!newPicture) {
                    LOG.info("Ignoring frequent notification, last one was {} ms ago", TimeUnit.NANOSECONDS.toMillis(diff));
                    return;
                } else {
                    LOG.info("Allowing frequent notification, last one was {} ms ago", TimeUnit.NANOSECONDS.toMillis(diff));
                }
            }
        }

        NotificationSpec notificationSpec = new NotificationSpec(-1, notification.when);
        notificationSpec.setKey(sbn.getKey());

        // determinate Source App Name ("Label")
        String name = NotificationUtils.getApplicationLabel(this, source);
        if (name != null) {
            notificationSpec.setSourceName(name);
        }

        // Get the app ID that generated this notification. For now only used by pebble color, but may be more useful later.
        notificationSpec.setSourceAppId(source);

        populateNotificationIcon(notification, source, notificationSpec);

        notificationSpec.setType(AppNotificationType.getInstance().get(source));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationSpec.setChannelId(notification.getChannelId());
        }

        notificationSpec.setCategory(notification.category);

        //FIXME: some quirks lookup table would be the minor evil here
        if (source.startsWith("com.fsck.k9") || source.startsWith("net.thunderbird.android")) {
            if (NotificationCompat.isGroupSummary(notification)) {
                LOG.info("ignore K9/Thunderbird group summary");
                return;
            }
        }

        if (notificationSpec.getType() == null) {
            notificationSpec.setType(NotificationType.UNKNOWN);
        }

        LOG.info(
                "Processing notification {}, age: {}, source: {}, flags: {}",
                notificationSpec.getId(),
                (System.currentTimeMillis() - notification.when),
                source,
                notification.flags
        );

        boolean preferBigText = prefs.getBoolean("notification_prefer_long_text", true);

        dissectNotificationTo(notification, notificationSpec, preferBigText);
        populateSamsungMessagingStyle(notification, notificationSpec);
        populateSamsungNotificationAvatar(notification, notificationSpec);

        if (notificationSpec.getTitle() != null || notificationSpec.getBody() != null) {
            final String textToCheck = ensureNotNull(notificationSpec.getTitle()) + " " + ensureNotNull(notificationSpec.getBody());
            if (!checkNotificationContentForWhiteAndBlackList(sbn.getPackageName().toLowerCase(), textToCheck)) {
                return;
            }
        }

        // ignore Gadgetbridge's very own notifications, except for those from the debug screen
        if (getApplicationContext().getPackageName().equals(source)) {
            if (!getApplicationContext().getString(R.string.test_notification).equals(notificationSpec.getTitle())) {
                return;
            }
        }

        NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender(notification);
        List<NotificationCompat.Action> wearableActions = wearableExtender.getActions();

        // Some apps such as Telegram send both a group + normal notifications, which would get sent in duplicate to the devices
        // Others only send the group summary, so they need to be whitelisted
        if (wearableActions.isEmpty() && NotificationCompat.isGroupSummary(notification)
                && !GROUP_SUMMARY_WHITELIST.contains(source)) { //this could cause #395 to come back
            LOG.info("Not forwarding notification, FLAG_GROUP_SUMMARY is set and no wearable action present. Notification flags: {}", notification.flags);
            return;
        }

        notificationSpec.setAttachedActions(new ArrayList<>());
        notificationSpec.setDndSuppressed(dndSuppressed);

        // DISMISS action
        NotificationSpec.Action dismissAction = new NotificationSpec.Action();
        dismissAction.setTitle(getString(R.string.dismiss));
        dismissAction.setType(NotificationSpec.Action.TYPE_SYNTHETIC_DISMISS);
        notificationSpec.getAttachedActions().add(dismissAction);

        boolean hasWearableActions = false;
        for (NotificationCompat.Action act : wearableActions) {
            if (act != null) {
                NotificationSpec.Action wearableAction = new NotificationSpec.Action();
                wearableAction.setTitle(String.valueOf(act.getTitle()));
                final RemoteInput remoteInput;
                if (act.getRemoteInputs() != null && act.getRemoteInputs().length > 0) {
                    wearableAction.setType(NotificationSpec.Action.TYPE_WEARABLE_REPLY);
                    remoteInput = act.getRemoteInputs()[0];
                    populateRemoteInput(wearableAction, remoteInput);
                } else {
                    wearableAction.setType(NotificationSpec.Action.TYPE_WEARABLE_SIMPLE);
                    remoteInput = null;
                }
                notificationSpec.getAttachedActions().add(wearableAction);
                wearableAction.setHandle(((long) notificationSpec.getId() << 4) + notificationSpec.getAttachedActions().size());
                mActionLookup.add((int) wearableAction.getHandle(), new NotificationAction(act.getActionIntent(), remoteInput));
                LOG.debug("Found wearable action {}: {} - {}  {}", notificationSpec.getAttachedActions().size(), (int) wearableAction.getHandle(), act.getTitle(), sbn.getTag());
                hasWearableActions = true;
            }
        }

        if (!hasWearableActions && notification.actions != null) {
            // If no wearable actions are sent, fallback to normal custom actions
            for (final Notification.Action act : notification.actions) {
                final NotificationSpec.Action customAction = new NotificationSpec.Action();
                customAction.setTitle(String.valueOf(act.title));
                final RemoteInput remoteInput;
                if (act.getRemoteInputs() != null && act.getRemoteInputs().length > 0) {
                    customAction.setType(NotificationSpec.Action.TYPE_CUSTOM_REPLY);
                    android.app.RemoteInput ri = act.getRemoteInputs()[0];
                    // FIXME this is not very clean
                    remoteInput = new RemoteInput.Builder(ri.getResultKey())
                            .setLabel(ri.getLabel())
                            .setChoices(ri.getChoices())
                            .setAllowFreeFormInput(ri.getAllowFreeFormInput())
                            .addExtras(ri.getExtras())
                            .build();
                    populateRemoteInput(customAction, remoteInput);
                } else {
                    customAction.setType(NotificationSpec.Action.TYPE_CUSTOM_SIMPLE);
                    remoteInput = null;
                }
                notificationSpec.getAttachedActions().add(customAction);
                customAction.setHandle(((long) notificationSpec.getId() << 4) + notificationSpec.getAttachedActions().size());
                mActionLookup.add((int) customAction.getHandle(), new NotificationAction(act.actionIntent, remoteInput));
                LOG.info("Found custom action {}: {} - {}", notificationSpec.getAttachedActions().size(), (int) customAction.getHandle(), act.title);
            }
        }

        // OPEN action
        NotificationSpec.Action openAction = new NotificationSpec.Action();
        openAction.setTitle(getString(R.string._pebble_watch_open_on_phone));
        openAction.setType(NotificationSpec.Action.TYPE_SYNTHETIC_OPEN);
        notificationSpec.getAttachedActions().add(openAction);

        // MUTE action
        NotificationSpec.Action muteAction = new NotificationSpec.Action();
        muteAction.setTitle(getString(R.string._pebble_watch_mute));
        muteAction.setType(NotificationSpec.Action.TYPE_SYNTHETIC_MUTE);
        notificationSpec.getAttachedActions().add(muteAction);

        mNotificationHandleLookup.add(notificationSpec.getId(), sbn.getPostTime()); // for both DISMISS and OPEN
        mPackageLookup.add(notificationSpec.getId(), sbn.getPackageName()); // for MUTE

        if (notificationSpec.getPicturePath() != null) {
            lastPictureNotificationTime = notificationSpec.getWhen();
        }

        notificationBurstPrevention.put(source, curTime);
        if (notification.when == 0) {
            LOG.info("This app might show old/duplicate notifications. notification.when is 0 for {}", source);
        } else if ((notification.when - System.currentTimeMillis()) > 30_000L) {
            // #4327 - Some apps such as outlook send reminder notifications in the future
            // If we add them to the oldRepeatPrevention, they never show up again
            LOG.info("This app might show old/duplicate notifications. notification.when is in the future for {}", source);
        } else {
            notificationOldRepeatPrevention.put(source, notification.when);
        }
        notificationsActive.add(notificationSpec.getId());
        // NOTE for future developers: this call goes to implementations of DeviceService.onNotification(NotificationSpec), like in GBDeviceService
        // this does NOT directly go to implementations of DeviceSupport.onNotification(NotificationSpec)!
        GBApplication.deviceService().onNotification(notificationSpec);
    }

    static boolean isOutsideNotificationTimes(final LocalTime now, final LocalTime start, final LocalTime end) {
        if (start.isBefore(end)) {
            // eg. 06:00 -> 22:00
            return now.isBefore(start) || now.isAfter(end);
        } else {
            // goes past midnight, eg. 22:00 -> 06:00
            return now.isBefore(start) && now.isAfter(end);
        }
    }

    private static boolean isOutsideNotificationTimes(final GBPrefs prefs) {
        if (!prefs.getNotificationTimesEnabled()) {
            return false;
        }

        final LocalTime now = LocalTime.now();
        final LocalTime start = prefs.getNotificationTimesStart();
        final LocalTime end = prefs.getNotificationTimesEnd();
        final boolean shouldIgnore = isOutsideNotificationTimes(now, start, end);

        if (shouldIgnore) {
            LOG.debug("Ignoring notification outside of notification times {}/{}", start, end);
        }

        return shouldIgnore;
    }

    private boolean checkNotificationContentForWhiteAndBlackList(String packageName, String body) {
        long start = System.currentTimeMillis();

        List<String> wordsList = new ArrayList<>();
        NotificationFilter notificationFilter;

        try (DBHandler db = GBApplication.acquireDB()) {

            NotificationFilterDao notificationFilterDao = db.getDaoSession().getNotificationFilterDao();
            NotificationFilterEntryDao notificationFilterEntryDao = db.getDaoSession().getNotificationFilterEntryDao();

            Query<NotificationFilter> query = notificationFilterDao.queryBuilder().where(NotificationFilterDao.Properties.AppIdentifier.eq(packageName.toLowerCase())).build();
            notificationFilter = query.unique();

            if (notificationFilter == null) {
                LOG.debug("No Notification Filter found");
                return true;
            }

            LOG.debug("Loaded notification filter for '{}'", packageName);
            Query<NotificationFilterEntry> queryEntries = notificationFilterEntryDao.queryBuilder().where(NotificationFilterEntryDao.Properties.NotificationFilterId.eq(notificationFilter.getId())).build();

            List<NotificationFilterEntry> filterEntries = queryEntries.list();

            if (BuildConfig.DEBUG) {
                LOG.info("Database lookup took '{}' ms", System.currentTimeMillis() - start);
            }

            if (!filterEntries.isEmpty()) {
                for (NotificationFilterEntry temp : filterEntries) {
                    wordsList.add(temp.getNotificationFilterContent());
                    LOG.debug("Loaded filter word: " + temp.getNotificationFilterContent());
                }
            }

        } catch (Exception e) {
            LOG.error("Could not acquire DB.", e);
            return true;
        }

        return shouldContinueAfterFilter(body, wordsList, notificationFilter);
    }

    private void handleCallNotification(StatusBarNotification sbn) {
        String app = sbn.getPackageName();
        LOG.debug("got call from: {}", app);
        if (PHONE_CALL_APPS.contains(app)) {
            LOG.debug("Ignoring non-voip call");
            return;
        }

        // #5113 - Firefox incorrectly categorizes recording notifications as calls - ignore them
        if (app.equals("org.mozilla.firefox") ||
                app.equals("org.mozilla.firefox_beta") ||
                app.equals("org.mozilla.fenix") ||
                app.equals("org.mozilla.fennec_aurora") ||
                app.equals("org.mozilla.focus") ||
                app.equals("org.mozilla.fennec_fdroid")) {
            LOG.debug("Ignoring firefox call");
            return;
        }

        Notification noti = sbn.getNotification();
        dumpExtras(noti.extras);
        boolean callStarted = false;
        if (noti.actions != null && noti.actions.length > 0) {
            for (Notification.Action action : noti.actions) {
                LOG.info("Found call action: {}", action.title);
            }
            if (noti.actions.length == 1) {
                if (mLastCallCommand == CallSpec.CALL_INCOMING) {
                    LOG.info("There is only one call action and previous state was CALL_INCOMING, assuming call started");
                    callStarted = true;
                } else {
                    LOG.info("There is only one call action and previous state was not CALL_INCOMING, assuming outgoing call / duplicate notification and ignoring");
                    // FIXME: is there a way to detect transition CALL_OUTGOING -> CALL_START for more complete VoIP call state tracking?
                    return;
                }
            }
            /*try {
                LOG.info("Executing first action");
                noti.actions[0].actionIntent.send();
            } catch (PendingIntent.CanceledException e) {
                e.printStackTrace();
            }*/
        }

        if (app.equals("com.microsoft.teams")) {
            // #5525 - Microsoft Teams spams notifications with slightly increasing timestamps
            // we use a different key for the burst prevention to prevent suppressing notifications
            final String burstPreventionKey = "call:" + app;
            final Long notificationBurstPreventionValue = notificationBurstPrevention.get(burstPreventionKey);
            long curTime = System.nanoTime();
            if (notificationBurstPreventionValue != null) {
                long diff = curTime - notificationBurstPreventionValue;
                if (diff < TimeUnit.SECONDS.toNanos(1)) {
                    LOG.info("Ignoring burst call notification from Microsoft Teams, last one was {} ms ago", TimeUnit.NANOSECONDS.toMillis(diff));
                    return;
                }
            }
            notificationBurstPrevention.put(burstPreventionKey, curTime);
        }

        // figure out sender
        String number = null;
        String notiTitle = null;
        String notiText = null;
        String appName = NotificationUtils.getApplicationLabel(this, app);
        if (noti.extras.containsKey(Notification.EXTRA_PEOPLE)) {
            String[] people = noti.extras.getStringArray(Notification.EXTRA_PEOPLE);
            if (people != null && people.length > 0 && people[0] != null) {
                number = people[0];
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && noti.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
            List<Person> people = noti.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
            if (people != null && !people.isEmpty()) {
                Person firstPerson = people.get(0);
                if (firstPerson != null && firstPerson.getName() != null) {
                    number = firstPerson.getName().toString();
                }
            }
        }
        if (noti.extras.containsKey(Notification.EXTRA_TEXT)) {
            notiText = noti.extras.getString(Notification.EXTRA_TEXT);
        }
        if (noti.extras.containsKey(Notification.EXTRA_TITLE)) {
            notiTitle = noti.extras.getString(Notification.EXTRA_TITLE);
        }
        if (number == null) {
            number = NOTI_USE_TITLE_APPS.contains(app) ? notiTitle : notiText;
        }
        if (number == null) {
            number = appName != null ? appName : app;
        }
        activeCallPostTime = sbn.getPostTime();
        CallSpec callSpec = new CallSpec();
        callSpec.setNumber(number);
        callSpec.setSourceAppId(app);
        if (appName != null) {
            callSpec.setSourceName(appName);
        }

        callSpec.setVoip(true);

        callSpec.setKey(sbn.getKey());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callSpec.setChannelId(noti.getChannelId());
        }
        callSpec.setCategory(noti.category);

        callSpec.setCommand(callStarted ? CallSpec.CALL_START : CallSpec.CALL_INCOMING);
        mLastCallCommand = callSpec.getCommand();
        GBApplication.deviceService().onSetCallState(callSpec);
    }

    boolean shouldContinueAfterFilter(String body, @NonNull List<String> wordsList, @NonNull NotificationFilter notificationFilter) {
        LOG.debug("Mode: '{}' Submode: '{}' WordsList: '{}'", notificationFilter.getNotificationFilterMode(), notificationFilter.getNotificationFilterSubMode(), wordsList);

        boolean allMode = notificationFilter.getNotificationFilterSubMode() == NOTIFICATION_FILTER_SUBMODE_ALL;

        switch (notificationFilter.getNotificationFilterMode()) {
            case NOTIFICATION_FILTER_MODE_BLACKLIST:
                if (allMode) {
                    for (String word : wordsList) {
                        if (!body.contains(word)) {
                            LOG.info("Not every word was found, blacklist has no effect, processing continues.");
                            return true;
                        }
                    }
                    LOG.info("Every word was found, blacklist has effect, processing stops.");
                    return false;
                } else {
                    boolean containsAny = Strings.CS.containsAny(body, wordsList.toArray(new CharSequence[0]));
                    if (!containsAny) {
                        LOG.info("No matching word was found, blacklist has no effect, processing continues.");
                    } else {
                        LOG.info("At least one matching word was found, blacklist has effect, processing stops.");
                    }
                    return !containsAny;
                }

            case NOTIFICATION_FILTER_MODE_WHITELIST:
                if (allMode) {
                    for (String word : wordsList) {
                        if (!body.contains(word)) {
                            LOG.info("Not every word was found, whitelist has no effect, processing stops.");
                            return false;
                        }
                    }
                    LOG.info("Every word was found, whitelist has effect, processing continues.");
                    return true;
                } else {
                    boolean containsAny = Strings.CS.containsAny(body, wordsList.toArray(new CharSequence[0]));
                    if (containsAny) {
                        LOG.info("At least one matching word was found, whitelist has effect, processing continues.");
                    } else {
                        LOG.info("No matching word was found, whitelist has no effect, processing stops.");
                    }
                    return containsAny;
                }

            default:
                return true;
        }
    }

    // Strip Unicode control sequences: some apps like Telegram add a lot of them for unknown reasons.
    // Keep newline and whitespace characters
    private String sanitizeUnicode(String orig) {
        return orig.replaceAll("[\\p{C}&&\\S]", "");
    }

    /// Helper method to quickly check whether a notification has a picture, without
    /// dissecting the full thing
    private boolean notificationHasPicture(final Notification notification) {
        final NotificationCompat.MessagingStyle messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification);
        if (messagingStyle != null) {
            final List<NotificationCompat.MessagingStyle.Message> messages = messagingStyle.getMessages();
            if (!messages.isEmpty()) {
                final NotificationCompat.MessagingStyle.Message lastMessage = messages.get(messages.size() - 1);

                if (supportedPictureMimeTypes.contains(lastMessage.getDataMimeType()) && lastMessage.getDataUri() != null) {
                    return true;
                }
            }
        }

        final Bundle extras = NotificationCompat.getExtras(notification);
        return extras != null && extras.containsKey(NotificationCompat.EXTRA_PICTURE);
    }

    private void dissectNotificationTo(Notification notification, NotificationSpec notificationSpec,
                                       boolean preferBigText) {

        Bundle extras = NotificationCompat.getExtras(notification);

        //dumpExtras(extras);
        if (extras == null) {
            return;
        }

        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null) {
            notificationSpec.setTitle(sanitizeUnicode(title.toString()));
        }

        CharSequence contentCS = null;
        final CharSequence bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT);
        if (preferBigText && !StringUtils.isBlank(bigText)) {
            contentCS = bigText;
        } else if (extras.containsKey(Notification.EXTRA_TEXT)) {
            contentCS = extras.getCharSequence(NotificationCompat.EXTRA_TEXT);
        }
        if (contentCS != null) {
            notificationSpec.setBody(sanitizeUnicode(contentCS.toString()));
        }

        NotificationCompat.MessagingStyle messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification);
        if (messagingStyle != null) {
            List<NotificationCompat.MessagingStyle.Message> messages = messagingStyle.getMessages();
            if (!messages.isEmpty()) {
                // Get the last message (assumed to be the most recent)
                NotificationCompat.MessagingStyle.Message lastMessage = messages.get(messages.size() - 1);

                if (supportedPictureMimeTypes.contains(lastMessage.getDataMimeType()) && lastMessage.getDataUri() != null) {
                    ContentResolver contentResolver = getContentResolver();

                    // Attempt to get the direct path
                    try (Cursor cursor = contentResolver.query(lastMessage.getDataUri(), null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                            notificationSpec.setPicturePath(cursor.getString(dataIndex));
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to get notification picture path", e);
                    }

                    // Fallback - attempt to open the URI and copy it to cache
                    if (notificationSpec.getPicturePath() == null) {
                        Bitmap bmp = null;
                        try (InputStream inputStream = contentResolver.openInputStream(lastMessage.getDataUri())) {
                            bmp = BitmapFactory.decodeStream(inputStream);
                            if (bmp != null) {
                                final File pictureFile = new File(this.notificationPictureCacheDirectory, String.valueOf(notificationSpec.getId()));
                                try (FileOutputStream fos = new FileOutputStream(pictureFile)) {
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                    notificationSpec.setPicturePath(pictureFile.getAbsolutePath());
                                }
                            }
                        } catch (Exception e) {
                            LOG.error("Failed to load picture from data uri to cache: ", e);
                        } finally {
                            if (bmp != null) {
                                bmp.recycle();
                            }
                        }
                    }
                }
            }
        }

        if (notificationSpec.getPicturePath() == null && extras.containsKey(NotificationCompat.EXTRA_PICTURE)) {
            final Bitmap bmp = (Bitmap) extras.get(NotificationCompat.EXTRA_PICTURE);
            if (bmp != null) {
                File pictureFile = new File(this.notificationPictureCacheDirectory, String.valueOf(notificationSpec.getId()));

                try (FileOutputStream fos = new FileOutputStream(pictureFile)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    notificationSpec.setPicturePath(pictureFile.getAbsolutePath());
                } catch (Exception e) {
                    LOG.error("Failed to save picture to notification cache: {}", e.getMessage());
                } finally {
                    bmp.recycle();
                }
            }
        }

        if (notificationSpec.getType() == NotificationType.COL_REMINDER
                && notificationSpec.getBody() == null
                && notificationSpec.getTitle() != null) {
            notificationSpec.setBody(notificationSpec.getTitle());
            notificationSpec.setTitle(null);
        }
    }

    /**
     * Protocol-v15 attr 44 is JSON with up to ten current and ten historic
     * MessagingStyle entries containing sender, timestamp and text. Android
     * keeps the self identity separately as the style user.
     */
    private void populateSamsungMessagingStyle(final Notification notification, final NotificationSpec spec) {
        final NotificationCompat.MessagingStyle style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification);
        if (style == null) return;
        try {
            final JSONObject root = new JSONObject();
            if (style.getUser() != null && style.getUser().getName() != null) root.put("user_display_name", style.getUser().getName());
            if (style.getConversationTitle() != null) root.put("conversation_title", style.getConversationTitle());
            root.put("messages", samsungMessagesJson(style.getMessages()));
            root.put("historicMessages", samsungMessagesJson(style.getHistoricMessages()));
            spec.setMessagingStyleJson(root.toString());
            final JSONArray snapshot = new JSONArray();
            final ArrayList<NotificationCompat.MessagingStyle.Message> all = new ArrayList<>(style.getHistoricMessages());
            all.addAll(style.getMessages());
            for (final NotificationCompat.MessagingStyle.Message message : all) {
                final JSONObject item = new JSONObject();
                item.put("time", message.getTimestamp());
                item.put("sender", samsungMessageIsSelf(style, message) ? "self" : samsungPersonIdentity(message.getPerson()));
                item.put("self", samsungMessageIsSelf(style, message));
                item.put("text", message.getText() == null ? "" : message.getText().toString());
                snapshot.put(item);
            }
            spec.setSamsungMessagingSnapshot(snapshot.toString());
            populateSamsungBigPictureCompatibilityCandidate(style, spec);
            LOG.debug("Gear S3 NotificationService richHistory: current={} historic={} serialized={} senderRuns={} media={}",
                    style.getMessages().size(), style.getHistoricMessages().size(),
                    Math.min(10, style.getMessages().size()) + Math.min(10, style.getHistoricMessages().size()),
                    samsungSenderRuns(style), samsungMediaSummary(style));
        } catch (final JSONException e) {
            LOG.warn("Unable to serialize MessagingStyle for Samsung NotificationService", e);
        }
    }

    /**
     * Attr 23 carries BigPicture PNG data. Its applicability to category-1006
     * MessagingStyle remains uncertain. Keep only URI metadata here; the Gear
     * worker performs all image I/O and compression.
     */
    static void populateSamsungBigPictureCompatibilityCandidate(final NotificationCompat.MessagingStyle style,
                                                                         final NotificationSpec spec) {
        spec.setSamsungBigPictureMediaUri(null);
        spec.setSamsungBigPictureMediaMimeType(null);
        final List<NotificationCompat.MessagingStyle.Message> messages = style.getMessages().isEmpty()
                ? style.getHistoricMessages() : style.getMessages();
        if (messages.isEmpty()) return;
        final NotificationCompat.MessagingStyle.Message message = messages.get(messages.size() - 1);
        final String mime = message.getDataMimeType();
        final boolean media = message.getDataUri() != null && mime != null && mime.startsWith("image/");
        if (media) {
            spec.setSamsungBigPictureMediaUri(message.getDataUri().toString());
            spec.setSamsungBigPictureMediaMimeType(mime);
        }
        LOG.debug("mediaStyle: latestMessageMedia={} mime={}", media, media ? mime : "none");
    }

    private static String samsungPersonIdentity(final androidx.core.app.Person person) {
        if (person == null) return "self";
        if (person.getKey() != null && !person.getKey().isEmpty()) return "key:" + person.getKey();
        if (person.getUri() != null && !person.getUri().isEmpty()) return "uri:" + person.getUri();
        return "name:" + (person.getName() == null ? "" : person.getName().toString());
    }

    static boolean samsungMessageIsSelf(final NotificationCompat.MessagingStyle style,
                                               final NotificationCompat.MessagingStyle.Message message) {
        // Android defines a null sender as a message from the MessagingStyle user.
        if (message.getPerson() == null) return true;
        if (style.getUser() == null) return false;
        final String identity = samsungPersonIdentity(message.getPerson());
        return !identity.equals("name:") && identity.equals(samsungPersonIdentity(style.getUser()));
    }

    private static JSONArray samsungMessagesJson(final List<NotificationCompat.MessagingStyle.Message> messages) throws JSONException {
        final JSONArray result = new JSONArray();
        final int start = Math.max(0, messages.size() - 10); // Maximum entries per MessagingStyle list
        for (int i = start; i < messages.size(); i++) {
            final NotificationCompat.MessagingStyle.Message message = messages.get(i);
            final JSONObject encoded = new JSONObject();
            if (message.getPerson() != null && message.getPerson().getName() != null) encoded.put("mSender", message.getPerson().getName());
            encoded.put("mTimestamp", message.getTimestamp());
            if (message.getText() != null) encoded.put("mText", message.getText());
            result.put(encoded);
        }
        return result;
    }

    /** Privacy-safe diagnostic using the same null-sender / Person identity rules as delta extraction. */
    private static String samsungSenderRuns(final NotificationCompat.MessagingStyle style) {
        final StringBuilder runs = new StringBuilder();
        String previous = null;
        final ArrayList<NotificationCompat.MessagingStyle.Message> all = new ArrayList<>();
        all.addAll(style.getHistoricMessages());
        all.addAll(style.getMessages());
        for (final NotificationCompat.MessagingStyle.Message message : all) {
            final String role = samsungMessageIsSelf(style, message) ? "self" : "remote";
            if (!role.equals(previous)) { if (runs.length() > 0) runs.append('/'); runs.append(role); previous = role; }
        }
        return runs.toString();
    }

    private static String samsungMediaSummary(final NotificationCompat.MessagingStyle style) {
        int count = 0;
        final Set<String> mimeTypes = new HashSet<>();
        final ArrayList<NotificationCompat.MessagingStyle.Message> all = new ArrayList<>();
        all.addAll(style.getHistoricMessages());
        all.addAll(style.getMessages());
        for (final NotificationCompat.MessagingStyle.Message message : all) {
            if (message.getDataUri() == null) continue;
            count++;
            mimeTypes.add(message.getDataMimeType() == null ? "unknown" : message.getDataMimeType());
        }
        return count + ":" + mimeTypes;
    }

    /** Icon priority: extra large icon, framework large icon, then the last message's Person icon. */
    private void populateSamsungNotificationAvatar(final Notification notification, final NotificationSpec spec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Icon icon = notification.getLargeIcon();
        if (icon != null) {
            // Icon is Parcelable: defer Drawable loading, bitmap rasterization and PNG encoding to
            // the Gear NotificationService's single preparation worker.
            spec.setAvatarIcon(icon);
            return;
        }
        if (icon == null) {
            final NotificationCompat.MessagingStyle style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification);
            if (style != null && !style.getMessages().isEmpty()) {
                final NotificationCompat.MessagingStyle.Message last = style.getMessages().get(style.getMessages().size() - 1);
                if (last.getPerson() != null && last.getPerson().getIcon() != null) {
                    spec.setAvatarIcon(last.getPerson().getIcon().toIcon());
                    return;
                }
            }
        }
    }

    private static void populateRemoteInput(final NotificationSpec.Action action, final RemoteInput input) {
        action.setRemoteInputKey(input.getResultKey());
        action.setRemoteInputLabel(input.getLabel() == null ? null : input.getLabel().toString());
        action.setRemoteInputAllowFreeInput(input.getAllowFreeFormInput());
        final CharSequence[] choices = input.getChoices();
        if (choices != null) {
            final String[] strings = new String[choices.length];
            for (int i = 0; i < choices.length; i++) strings[i] = choices[i] == null ? "" : choices[i].toString();
            action.setRemoteInputChoices(strings);
        }
    }

    static void populateNotificationIcon(final Notification notification,
                                         final String sourcePackage,
                                         final NotificationSpec notificationSpec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final Icon smallIcon = notification.getSmallIcon();
            if (smallIcon != null) {
                try {
                    final int resourceId = smallIcon.getResId();
                    if (resourceId != 0) {
                        notificationSpec.setIconId(resourceId);
                        final String resourcePackage = smallIcon.getResPackage();
                        notificationSpec.setIconPackageId(StringUtils.isBlank(resourcePackage)
                                ? sourcePackage
                                : resourcePackage);
                        return;
                    }
                } catch (final IllegalStateException e) {
                    LOG.debug("Notification small icon is not a resource icon");
                }
            }
        }

        //noinspection deprecation
        notificationSpec.setIconId(notification.icon);
        if (notificationSpec.getIconId() != 0) {
            notificationSpec.setIconPackageId(sourcePackage);
        }
    }

    private boolean handleMediaSessionNotification(final StatusBarNotification sbn) {
        final MediaSession.Token token = sbn.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        return token != null && handleMediaSessionNotification(token);
    }

    /**
     * Try to handle media session notifications that tell info about the current play state.
     *
     * @param mediaSession The mediasession to handle.
     * @return true if notification was handled, false otherwise
     */
    public boolean handleMediaSessionNotification(MediaSession.Token mediaSession) {
        try {
            final MediaController c = new MediaController(getApplicationContext(), mediaSession);
            final PlaybackState playbackState = c.getPlaybackState();
            final MediaMetadata metadata = c.getMetadata();
            if (metadata == null) {
                return false;
            }

            final MusicStateSpec stateSpec = MediaManager.extractMusicStateSpec(playbackState);
            final MusicSpec musicSpec = MediaManager.extractMusicSpec(metadata);

            // finally, tell the device about it
            if (mSetMusicInfoRunnable != null) {
                mHandler.removeCallbacks(mSetMusicInfoRunnable);
            }
            mSetMusicInfoRunnable = () -> GBApplication.deviceService().onSetMusicInfo(musicSpec);
            mHandler.postDelayed(mSetMusicInfoRunnable, 100);

            if (stateSpec != null) {
                if (mSetMusicStateRunnable != null) {
                    mHandler.removeCallbacks(mSetMusicStateRunnable);
                }
                mSetMusicStateRunnable = new Runnable() {
                    @Override
                    public void run() {
                        GBApplication.deviceService().onSetMusicState(stateSpec);
                    }
                };
            }
            mHandler.postDelayed(mSetMusicStateRunnable, 100);

            return true;
        } catch (final NullPointerException | SecurityException e) {
            LOG.error("Failed to handle media session notification", e);
            return false;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        logNotification(sbn, false);

        notificationStack.remove(sbn.getPackageName());

        if (isServiceNotRunningAndShouldIgnoreNotifications()) return;

        final GBPrefs prefs = GBApplication.getPrefs();

        if (isOutsideNotificationTimes(prefs)) {
            return;
        }

        final boolean ignoreWorkProfile = prefs.getBoolean("notifications_ignore_work_profile", false);
        if (ignoreWorkProfile && isWorkProfile(sbn)) {
            LOG.debug("Ignoring notification removal from work profile");
            return;
        }

        final boolean mediaIgnoresAppList = prefs.getBoolean("notification_media_ignores_application_list", false);

        // If media notifications ignore app list, check them before
        if (mediaIgnoresAppList && handleMediaSessionNotification(sbn)) return;

        if (shouldIgnoreSource(sbn)) return;

        googleMapsNotificationHandler.handleRemove(sbn);

        // If media notifications do NOT ignore app list, check them after
        if (!mediaIgnoresAppList && handleMediaSessionNotification(sbn)) return;

        if (Notification.CATEGORY_CALL.equals(sbn.getNotification().category)
                && activeCallPostTime == sbn.getPostTime()) {
            activeCallPostTime = 0;
            CallSpec callSpec = new CallSpec();
            callSpec.setCommand(CallSpec.CALL_END);
            mLastCallCommand = callSpec.getCommand();
            GBApplication.deviceService().onSetCallState(callSpec);
        }

        if (shouldIgnoreNotification(sbn, true, false)) return;

        // Build list of all currently active notifications
        ArrayList<Integer> activeNotificationsIds = new ArrayList<>();
        for (StatusBarNotification notification : getActiveNotifications()) {
            Integer id = mNotificationHandleLookup.lookupByValue(notification.getPostTime());
            if (id != null) {
                activeNotificationsIds.add(id);
            }
        }

        // Build list of notifications that aren't active anymore
        ArrayList<Integer> notificationsToRemove = new ArrayList<>();
        for (int notificationId : notificationsActive) {
            if (!activeNotificationsIds.contains(notificationId)) {
                notificationsToRemove.add(notificationId);
                deleteNotificationPicture(notificationId);
            }
        }

        // Clean up removed notifications from internal list
        notificationsActive.removeAll(notificationsToRemove);

        // Send notification remove request to device
        List<GBDevice> devices = GBApplication.app().getDeviceManager().getSelectedDevices();
        for (GBDevice device : devices) {
            if (!device.isInitialized()) {
                continue;
            }

            Prefs devicePrefs = new Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()));
            if (devicePrefs.getBoolean("autoremove_notifications", true)) {
                for (int id : notificationsToRemove) {
                    LOG.info("Notification {} removed, deleting from {}", id, device.getAliasOrName());
                    GBApplication.deviceService(device).onDeleteNotification(id);
                }
            }
        }
    }

    private void deleteNotificationPicture(int notificationId) {
        File pictureFile = new File(this.notificationPictureCacheDirectory, String.valueOf(notificationId));
        if (pictureFile.exists())
            pictureFile.delete();
    }

    private void cleanUpNotificationPictureProvider() {
        File[] pictureFiles = this.notificationPictureCacheDirectory.listFiles();
        if (pictureFiles == null)
            return;

        for (File pictureFile : pictureFiles) {
            pictureFile.delete();
        }
    }

    private void createNotificationPictureCacheDirectory() {
        final File cacheDir = getApplicationContext().getExternalCacheDir();
        this.notificationPictureCacheDirectory = new File(cacheDir, "notification-pictures");
        this.notificationPictureCacheDirectory.mkdir();
    }

    private void logNotification(StatusBarNotification sbn, boolean posted) {
        LOG.debug(
                "Notification {} {}: packageName={}, channelId={} when={}, priority={}, category={}, flags={}",
                sbn.getId(),
                posted ? "posted" : "removed",
                sbn.getPackageName(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? sbn.getNotification().getChannelId() : "N/A",
                sbn.getNotification().when,
                sbn.getNotification().priority,
                sbn.getNotification().category,
                sbn.getNotification().flags
        );
    }

    private void dumpExtras(Bundle bundle) {
        // reading an extra deserializes it, which for a bitmap or a remote view is far from free
        if (!LOG.isDebugEnabled()) {
            return;
        }
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value == null) {
                continue;
            }
            LOG.debug("Notification extra: {} {} ({})", key, value, value.getClass().getName());
        }
    }

    private boolean isServiceNotRunningAndShouldIgnoreNotifications() {
        /*
         * return early if DeviceCommunicationService is not running,
         * else the service would get started every time we get a notification.
         * unfortunately we cannot enable/disable NotificationListener at runtime like we do with
         * broadcast receivers because it seems to invalidate the permissions that are
         * necessary for NotificationListenerService
         */
        if (!DeviceCommunicationService.isRunning(this)) {
            LOG.trace("Service is not running, ignoring notification");
            return true;
        }
        return false;
    }

    private boolean isOtherUser(StatusBarNotification sbn) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (sbn.getPackageName().equals("android")) {
                return "OTHER_USERS".equals(sbn.getNotification().getChannelId());
            }
        }

        return false;
    }

    private boolean shouldIgnoreSource(StatusBarNotification sbn) {
        String source = sbn.getPackageName();

        Prefs prefs = GBApplication.getPrefs();

        /* do not display messages from "android"
         * This includes keyboard selection message, usb connection messages, etc
         * Hope it does not filter out too much, we will see...
         */

        if (source.equals("android") ||
                source.equals("com.android.systemui") ||
                source.equals("com.android.dialer") ||
                source.equals("com.google.android.dialer") ||
                source.equals("com.cyanogenmod.eleven")) {
            if (isOtherUser(sbn)) {
                final boolean ignoreOtherUsers = prefs.getBoolean("notifications_ignore_other_users", false);
                if (ignoreOtherUsers) {
                    LOG.info("Ignoring notification, is from another user");
                } else {
                    LOG.debug("Allowing notification from another user");
                    return false;
                }
            }
            LOG.info("Ignoring notification, is a system event");
            return true;
        }

        if (source.equals("com.moez.QKSMS") ||
                source.equals("com.android.mms") ||
                source.equals("com.sonyericsson.conversations") ||
                source.equals("com.android.messaging") ||
                source.equals("com.google.android.apps.messaging") ||
                source.equals("org.smssecure.smssecure") ||
                source.equals("org.fossify.messages") ||
                source.equals("com.goodwy.smsmessenger") ||
                source.equals("com.simplemobiletools.smsmessenger") ||
                source.equals("dev.octoshrimpy.quik")) {
            if (!"never".equals(prefs.getString("notification_mode_sms", "when_screen_off"))) {
                LOG.info("Ignoring notification, it's an sms notification");
                return true;
            }
        }

        if (GBApplication.getPrefs().getString("notification_list_is_blacklist", "true").equals("true")) {
            if (GBApplication.appIsNotifBlacklisted(source)) {
                LOG.info("Ignoring notification, application is blacklisted");
                return true;
            }
        } else {
            if (GBApplication.appIsNotifBlacklisted(source)) {
                LOG.info("Allowing notification, application is whitelisted");
                return false;
            } else {
                LOG.info("Ignoring notification, application is not whitelisted");
                return true;
            }
        }

        return false;
    }

    private boolean shouldIgnoreRepeatPrevention(StatusBarNotification sbn) {
        if (isFitnessApp(sbn)) {
            return true;
        }
        return false;
    }

    private boolean shouldSendOngoing(StatusBarNotification sbn, NotificationType type) {
        if (isFitnessApp(sbn)) {
            return true;
        }
        if (type == NotificationType.COL_REMINDER) {
            return true;
        }
        return false;
    }

    private boolean isFitnessApp(StatusBarNotification sbn) {
        final String source = sbn.getPackageName();
        return source.equals("de.dennisguse.opentracks")
                || source.equals("de.dennisguse.opentracks.debug")
                || source.equals("de.dennisguse.opentracks.nightly")
                || source.equals("de.dennisguse.opentracks.playstore")
                || source.equals("de.tadris.fitness")
                || source.equals("de.tadris.fitness.debug");
    }

    private boolean isWorkProfile(StatusBarNotification sbn) {
        final UserHandle currentUser = Process.myUserHandle();
        return !sbn.getUser().equals(currentUser);
    }

    private boolean shouldDisplayNonOngoingCallNotification(StatusBarNotification sbn) {
        String source = sbn.getPackageName();
        NotificationType type = AppNotificationType.getInstance().get(source);

        if (type == NotificationType.TELEGRAM) {
            return true;
        }

        return false;
    }

    private boolean shouldIgnoreNotification(StatusBarNotification sbn,
                                             boolean remove,
                                             boolean hasPicture) {
        Notification notification = sbn.getNotification();
        String source = sbn.getPackageName();

        NotificationType type = AppNotificationType.getInstance().get(source);
        //ignore notifications marked as LocalOnly https://developer.android.com/reference/android/app/Notification.html#FLAG_LOCAL_ONLY
        //some Apps always mark their notifications as read-only
        if (NotificationCompat.getLocalOnly(notification) &&
                type != NotificationType.WECHAT &&
                type != NotificationType.TELEGRAM &&
                type != NotificationType.OUTLOOK &&
                type != NotificationType.COL_REMINDER &&
                type != NotificationType.SKYPE) { //see https://github.com/Freeyourgadget/Gadgetbridge/issues/1109
            LOG.info("Ignoring notification, local only");
            return true;
        }

        Prefs prefs = GBApplication.getPrefs();

        // Check for screen on when posting the notification; for removal, the screen
        // has to be on (obviously)
        if (!remove) {
            if (!prefs.getBoolean("notifications_generic_whenscreenon", false)) {
                final PowerManager powermanager = (PowerManager) getSystemService(POWER_SERVICE);
                if (powermanager != null && powermanager.isScreenOn()) {
                    if (!isDreaming) {
                        LOG.info("Not forwarding notification, screen seems to be on and settings do not allow this");
                        return true;
                    } else if (!prefs.getBoolean("notifications_generic_when_screen_saver", true)) {
                        LOG.info("Not forwarding notification, screen saver seems to be on and settings do not allow this");
                        return true;
                    }
                }
            }
        }

        if (prefs.getBoolean("notifications_ignore_low_priority", true)) {
            Boolean isImportant = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notification.getChannelId() != null) {
                String channel_id = notification.getChannelId();
                try {
                    List<NotificationChannel> channels = getNotificationChannels(sbn.getPackageName(), Process.myUserHandle());
                    NotificationChannel channel = null;
                    for (NotificationChannel c : channels) {
                        if (channel_id.equals(c.getId())) {
                            channel = c;
                            break;
                        }
                    }
                    if (channel != null) {
                        isImportant = channel.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
                    }
                } catch (SecurityException ignored) {
                    LOG.warn("Can't call getNotificationChannels, Gadgetbridge needs to be registered as a companion app");
                }
            }
            if (isImportant == null) {
                isImportant = notification.priority >= Notification.PRIORITY_DEFAULT;
            }
            if (!isImportant) {
                // notification updates with a picture are low priority
                if (!hasPicture) {
                    LOG.debug("Ignoring notification, low priority");
                    return true;
                } else {
                    LOG.debug("Allowing low priority notification - has picture");
                }
            }
        }

        if (NotificationCompat.CATEGORY_PROGRESS.equals(sbn.getNotification().category)
                && (sbn.getPackageName().equals("com.whatsapp") || sbn.getPackageName().equals("com.whatsapp.w4b"))) {
            // #5554 - WhatsApp progress notifications are not marked as ongoing and will spam the watch
            LOG.debug("Ignoring WhatsApp progress notification");
            return true;
        }

        if (shouldSendOngoing(sbn, type)) {
            LOG.trace("Not ignoring ongoing notification");
            return false;
        }

        return (notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
    }

    private static class NotificationAction {
        private final PendingIntent intent;
        @Nullable
        private final RemoteInput remoteInput;

        private NotificationAction(final PendingIntent pendingIntent, @Nullable final RemoteInput remoteInput) {
            this.intent = pendingIntent;
            this.remoteInput = remoteInput;
        }

        public PendingIntent getIntent() {
            return intent;
        }

        @Nullable
        public RemoteInput getRemoteInput() {
            return remoteInput;
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void onTimeout(int startId) {
        LOG.info("onTimeout startId={}", startId);
        super.onTimeout(startId);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void onTimeout(int startId, int fgsType) {
        LOG.info("onTimeout startId={} fgsType={}", startId, fgsType);
        super.onTimeout(startId, fgsType);
    }
}
