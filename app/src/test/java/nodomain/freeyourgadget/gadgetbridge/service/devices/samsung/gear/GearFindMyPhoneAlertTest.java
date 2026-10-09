package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.app.Notification;
import android.app.NotificationManager;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.FindPhoneActivity;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.*;
import nodomain.freeyourgadget.gadgetbridge.test.GBTestApplication;
import nodomain.freeyourgadget.gadgetbridge.util.FindPhoneSession;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real asynchronous adapter/event path with the phone's missing-CDM condition. */
@RunWith(RobolectricTestRunner.class)
@Config(application = GBTestApplication.class, sdk = 29)
public class GearFindMyPhoneAlertTest {
    private static final String START = "{\"msgId\":\"fmp-startalert-req\",\"time\":\"180\"}";
    private final List<SapPostAuthRecord> sent = new ArrayList<>();
    private GearFindMyPhoneService service;
    private Context app;
    private GBDevice device;
    private ActivityController<FindPhoneActivity> activity;
    private boolean rejectLaunch;
    private int launches;
    private Intent launched;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                app.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(300_000, 0));
        CompanionDeviceManager cdm = (CompanionDeviceManager) app.getSystemService(Context.COMPANION_DEVICE_SERVICE);
        assertTrue(cdm.getAssociations().isEmpty());
        assertTrue(FindPhoneSession.INSTANCE.idle());
        device = new GBDevice("00:11:22:33:44:55", "Synthetic Gear", null, null, DeviceType.SAMSUNG_GEAR_S3);
        Context context = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public void startActivity(Intent intent) {
                if (rejectLaunch) throw new SecurityException("Synthetic Android launch rejection");
                launches++;
                launched = intent;
            }
        };
        service = new GearFindMyPhoneService(new SapFindMyPhoneRegistration(0x1234, 0x2345),
                new GearFindMyPhoneService.Emitter() {
                    public boolean connected() { return true; }
                    public void send(SapPostAuthRecord record, long generation) { sent.add(record); }
                }, new GearFindMyPhoneAlert(context, device), (task, delay) -> () -> {});
        service.learnRemote(Collections.singletonList(new SapAgentDescriptor(0x5678, "consumer",
                Collections.singletonList(new SapServiceDescriptor(0x6789, "/system/fmp", 0x0100, 5, 0)))));
        assertEquals(0, service.accept(new SapServiceConnectionRequest(0x2345, 0x6789, "/system/fmp",
                Collections.singletonList(new SapServiceConnectionRequest.Session(603, 103,
                        new SapQosDescriptor(4, 1, 2), 0))), Collections.emptySet()).getStatus());
    }
    private void command(String json) {
        service.onRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0, service.session(), 0),
                SapFindMyPhoneCodec.frame(json)), service.generation());
    }
    private void drain() { shadowOf(Looper.getMainLooper()).idle(); }
    private void createActivity() {
        activity = Robolectric.buildActivity(FindPhoneActivity.class, launched).create().start().resume();
    }
    @After public void cleanup() {
        service.dispose();
        drain();
        if (activity != null) activity.pause().stop().destroy();
        assertTrue(FindPhoneSession.INSTANCE.idle());
    }

    @Test public void noCompanionAssociationStillSchedulesAndAcknowledgesBeforeActivityCreation() {
        command(START);
        assertTrue(sent.isEmpty());
        drain();
        assertEquals(1, launches);
        assertArrayEquals(SapFindMyPhoneCodec.frame("{\"msgId\":\"fmp-startalert-rsp\",\"result\":0,\"reason\":0}"), sent.get(0).getBody());
        assertTrue(FindPhoneSession.INSTANCE.current(launched.getStringExtra(FindPhoneSession.EXTRA_TOKEN)));
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = shadowOf(manager).getNotification(GB.NOTIFICATION_ID_PHONE_FIND);
        assertNotNull(notification.contentIntent);
        assertNull(notification.fullScreenIntent); // No new full-screen permission granted by this feature.
        createActivity();
        drain();
        assertEquals(1, sent.size()); // Playback callback must not repeat START response.
        assertFalse(activity.get().isFinishing());
    }
    @Test public void actualLaunchExceptionReturnsFailureAndReleasesOwnership() {
        rejectLaunch = true;
        command(START);
        drain();
        assertEquals(0, launches);
        assertEquals(1, sent.size());
        assertArrayEquals(SapFindMyPhoneCodec.frame("{\"msgId\":\"fmp-startalert-rsp\",\"result\":1,\"reason\":-1}"), sent.get(0).getBody());
        assertTrue(FindPhoneSession.INSTANCE.idle());
    }
    @Test public void duplicateAcceptedStartKeepsSingleLaunch() {
        command(START);drain();command(START);drain();
        assertEquals(1, launches);
        assertEquals(2, sent.size());
        assertArrayEquals(SapFindMyPhoneCodec.startResponse(true), sent.get(1).getBody());
    }
    @Test public void stopBeforeEventDispatchNeverLaunches() {
        command(START);command("{\"msgId\":\"fmp-stopalert-req\"}");drain();
        assertEquals(0, launches);
        assertEquals(1, sent.size());
        assertArrayEquals(SapFindMyPhoneCodec.stopResponse(), sent.get(0).getBody());
    }
    @Test public void activityDeliveredAfterStopCannotResurrectAlert() {
        command(START);drain();command("{\"msgId\":\"fmp-stopalert-req\"}");drain();
        createActivity();drain();
        assertTrue(activity.get().isFinishing());
        assertEquals(2, sent.size());
        assertTrue(FindPhoneSession.INSTANCE.idle());
    }
    @Test public void stopCancelsActiveActivity() {
        command(START);drain();createActivity();drain();
        command("{\"msgId\":\"fmp-stopalert-req\"}");drain();
        assertTrue(activity.get().isFinishing());
        assertEquals(2, sent.size());
    }
    @Test public void disconnectBeforeDispatchAndDisposeAfterAcceptanceCancelOwnership() {
        command(START);service.close();drain();
        assertEquals(0, launches);
        // Re-open through the generic acceptor without retaining the previous session.
        assertEquals(0, service.accept(new SapServiceConnectionRequest(0x2345, 0x6789, "/system/fmp",
                Collections.singletonList(new SapServiceConnectionRequest.Session(71, 103,
                        new SapQosDescriptor(4, 1, 2), 0))), Collections.emptySet()).getStatus());
        command(START);drain();service.dispose();drain();createActivity();drain();
        assertTrue(activity.get().isFinishing());
        assertEquals(1, sent.size());
    }
    @Test public void legacyFindPhoneWithoutAssociationRetainsInstructionsPath() {
        GBDeviceEventFindPhone event = new GBDeviceEventFindPhone();
        event.event = GBDeviceEventFindPhone.Event.START;
        event.evaluate(app, device);
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertTrue(FindPhoneSession.INSTANCE.idle());
    }
}
