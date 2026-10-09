/*  Copyright (C) 2018-2024 Andreas Shimokawa, José Rebelo

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

package nodomain.freeyourgadget.gadgetbridge.deviceevents;

import static nodomain.freeyourgadget.gadgetbridge.util.GB.NOTIFICATION_CHANNEL_HIGH_PRIORITY_ID;

import android.app.PendingIntent;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.FindPhoneActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.FindPhoneSession;

public class GBDeviceEventFindPhone extends GBDeviceEvent {
    private static final Logger LOG = LoggerFactory.getLogger(GBDeviceEventFindPhone.class);

    public Event event = Event.UNKNOWN;
    public String sessionToken;

    @Override
    public void evaluate(final Context context, final GBDevice device) {
        if (sessionToken != null && !FindPhoneSession.INSTANCE.current(sessionToken)) return;
        LOG.info("Got GBDeviceEventFindPhone: {}", this.event);
        switch (this.event) {
            case START:
                handleGBDeviceEventFindPhoneStart(context, true);
                break;
            case START_VIBRATE:
                handleGBDeviceEventFindPhoneStart(context, false);
                break;
            case VIBRATE:
                final Intent intentVibrate = new Intent(FindPhoneActivity.ACTION_VIBRATE);
                intentVibrate.setPackage(BuildConfig.APPLICATION_ID);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intentVibrate);
                break;
            case RING:
                final Intent intentRing = new Intent(FindPhoneActivity.ACTION_RING);
                intentRing.setPackage(BuildConfig.APPLICATION_ID);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intentRing);
                break;
            case STOP:
                final Intent intentStop = new Intent(FindPhoneActivity.ACTION_FOUND);
                intentStop.setPackage(BuildConfig.APPLICATION_ID);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intentStop);
                break;
            default:
                LOG.warn("unknown GBDeviceEventFindPhone");
        }
    }

    @NonNull
    @Override
    public String toString() {
        return super.toString() + "event: " + event;
    }

    private void handleGBDeviceEventFindPhoneStart(final Context context, final boolean ring) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // this could be used if app in foreground // TODO: Below Q?
            final Intent startIntent = new Intent(context, FindPhoneActivity.class);
            startIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startIntent.putExtra(FindPhoneActivity.EXTRA_RING, ring);
            addSession(startIntent);
            LOG.info("Find Phone Activity launch attempt: owned={} route=direct", sessionToken != null);
            context.startActivity(startIntent);
            LOG.info("Find Phone Activity launch scheduled: owned={}", sessionToken != null);
        } else {
            handleGBDeviceEventFindPhoneStartNotification(context, ring);
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void handleGBDeviceEventFindPhoneStartNotification(final Context context, final boolean ring) {
        LOG.info("Got handleGBDeviceEventFindPhoneStartNotification");
        final CompanionDeviceManager manager = (CompanionDeviceManager) context.getSystemService(Context.COMPANION_DEVICE_SERVICE);
        final boolean associated = manager != null && !manager.getAssociations().isEmpty();
        final boolean owned = sessionToken != null;
        LOG.info("Find Phone launch prerequisites: owned={} companionAssociation={} notificationsEnabled={}",
                owned, associated, NotificationManagerCompat.from(context).areNotificationsEnabled());
        if (!owned && !associated) {
            // On Android Q and above, we need the device to be paired as companion. If it is not, display a notification
            // notifying the user and linking to further instructions
            final Intent instructionsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://gadgetbridge.org/basics/pairing/companion-device/"));
            final PendingIntent pi = PendingIntent  .getActivity(
                    context,
                    0,
                    instructionsIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );
            final NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_HIGH_PRIORITY_ID)
                    .setSmallIcon(R.drawable.ic_warning)
                    .setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(R.string.find_my_phone_notification))
                    .setContentText(context.getString(R.string.find_my_phone_companion_warning));

            GB.notify(GB.NOTIFICATION_ID_PHONE_FIND, notification.build(), context);

            return;
        }

        final Intent intent = new Intent(context, FindPhoneActivity.class);
        intent.setPackage(BuildConfig.APPLICATION_ID);
        intent.putExtra(FindPhoneActivity.EXTRA_RING, ring);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        addSession(intent);
        final PendingIntent pi = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        final NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_HIGH_PRIORITY_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setGroup("BackgroundService")
                .setContentTitle(context.getString(R.string.find_my_phone_notification));

        final boolean fullScreen = !owned || canUseFullScreenIntent(context);
        if (fullScreen) notification.setFullScreenIntent(pi, true);
        // Gear uses an existing Classic bond without CDM association. Let Android decide whether
        // the launch is allowed (for example, while GB is visible), rather than rejecting it here.
        LOG.info("Find Phone notification creation: owned={} fullScreen={}", owned, fullScreen);
        try {
            GB.notify(GB.NOTIFICATION_ID_PHONE_FIND, notification.build(), context);
        } catch (final RuntimeException e) {
            if (!owned) throw e;
            LOG.warn("Find Phone notification creation failed; trying direct Activity launch", e);
        }
        if (owned && !FindPhoneSession.INSTANCE.current(sessionToken)) return;
        LOG.info("Find Phone Activity launch attempt: owned={} companionAssociation={}", owned, associated);
        context.startActivity(intent);
        // startActivity has no synchronous playback result; Android can silently block a BAL.
        LOG.info("Find Phone Activity launch scheduled: owned={}; awaiting lifecycle callback", owned);
    }

    private static boolean canUseFullScreenIntent(final Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.USE_FULL_SCREEN_INTENT)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.canUseFullScreenIntent();
    }

    private void addSession(final Intent intent) {
        if (sessionToken == null) return;
        intent.putExtra(FindPhoneSession.EXTRA_TOKEN, sessionToken);
        // Distinct PendingIntent identity: cancellation cannot target a later operation.
        intent.setData(Uri.parse("gadgetbridge://find-phone/" + sessionToken));
    }

    public static void cancelSessionNotification(final Context context, final String token) {
        final Intent intent = new Intent(context, FindPhoneActivity.class)
                .setPackage(BuildConfig.APPLICATION_ID)
                .setData(Uri.parse("gadgetbridge://find-phone/" + token));
        final PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) pi.cancel();
        // Only remove a notification while no newer owned operation is using it.
        if (FindPhoneSession.INSTANCE.idle()) {
            GB.removeNotification(GB.NOTIFICATION_ID_PHONE_FIND, context);
        }
    }

    public enum Event {
        UNKNOWN,
        START,
        START_VIBRATE,
        STOP,
        VIBRATE,
        RING,
    }
}
