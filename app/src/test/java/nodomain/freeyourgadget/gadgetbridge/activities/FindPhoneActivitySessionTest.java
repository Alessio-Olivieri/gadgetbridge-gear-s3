package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
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
import nodomain.freeyourgadget.gadgetbridge.test.GBTestApplication;
import nodomain.freeyourgadget.gadgetbridge.util.FindPhoneSession;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = GBTestApplication.class, sdk = 28)
public class FindPhoneActivitySessionTest {
    private String token;
    private ActivityController<FindPhoneActivity> controller;
    private int starts, finishes;
    private boolean failed;
    @Before public void setup() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                RuntimeEnvironment.getApplication().getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(300_000, 0));
        token = FindPhoneSession.INSTANCE.begin(new FindPhoneSession.Listener() {
            public void started() { starts++; }
            public void finished(boolean failure) { finishes++; failed = failure; }
        });
        assertNotNull(token);
    }
    private Intent intent() {
        return new Intent(RuntimeEnvironment.getApplication(), FindPhoneActivity.class)
                .putExtra(FindPhoneSession.EXTRA_TOKEN, token);
    }
    private FindPhoneActivity create() {
        controller = Robolectric.buildActivity(FindPhoneActivity.class, intent()).create().start().resume();
        return controller.get();
    }
    @After public void cleanup() {
        FindPhoneSession.INSTANCE.cancel(token);
        shadowOf(Looper.getMainLooper()).idle();
        if (controller != null) controller.pause().stop().destroy();
    }
    @Test public void canceledLaunchNeverCreatesPlayer() {
        FindPhoneSession.INSTANCE.cancel(token);
        FindPhoneActivity activity = create();
        assertNull(activity.mp);
        assertTrue(activity.isFinishing());
        assertEquals(0, starts);
    }
    @Test public void duplicateIntentKeepsOnePlayerAndAlarmVolume() {
        AudioManager audio = (AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0);
        FindPhoneActivity activity = create();
        MediaPlayer player = activity.mp;
        assertNotNull(player);
        assertTrue(player.isPlaying());
        controller.newIntent(intent());
        assertSame(player, activity.mp);
        assertEquals(1, starts);
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM));
    }
    @Test public void remoteCancellationReleasesPlayerWithoutLocalFound() {
        FindPhoneActivity activity = create();
        FindPhoneSession.INSTANCE.cancel(token);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(activity.mp);
        assertTrue(activity.isFinishing());
        assertEquals(0, finishes);
    }
    @Test public void localFinishReleasesBeforeReportingFound() {
        FindPhoneActivity activity = create();
        activity.finish();
        assertNull(activity.mp);
        assertEquals(1, finishes);
        assertFalse(failed);
    }
    @Test public void tokenlessNewIntentCannotReplaceOwnedPlayer() {
        FindPhoneActivity activity = create();
        MediaPlayer player = activity.mp;
        controller.newIntent(new Intent(RuntimeEnvironment.getApplication(), FindPhoneActivity.class));
        assertSame(player, activity.mp);
        assertEquals(1, starts);
    }
    @Test public void bothSourcesFailWithoutSuccessOrLeakedPlayer() {
        ShadowMediaPlayer.setMediaInfoProvider(source -> null);
        FindPhoneActivity activity = create();
        assertEquals(0, starts);
        assertEquals(1, finishes);
        assertTrue(failed);
        assertNull(activity.mp);
    }
    @Test public void configuredSourceFailureResetsPlayerBeforeFallback() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        ShadowMediaPlayer.setMediaInfoProvider(source -> attempts.incrementAndGet() == 1
                ? null : new ShadowMediaPlayer.MediaInfo(300_000, 0));
        FindPhoneActivity activity = create();
        assertNotNull(activity.mp);
        assertTrue(activity.mp.isPlaying());
        assertEquals(1, starts);
        assertEquals(0, finishes);
    }
    @Test public void legacyDuplicateIntentRestoresOriginalVolume() {
        FindPhoneSession.INSTANCE.cancel(token);
        AudioManager audio = (AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0);
        Intent legacy = new Intent(RuntimeEnvironment.getApplication(), FindPhoneActivity.class);
        controller = Robolectric.buildActivity(FindPhoneActivity.class, legacy).create().start().resume();
        MediaPlayer player = controller.get().mp;
        assertTrue(player.isPlaying());
        controller.newIntent(legacy);
        assertSame(player, controller.get().mp);
        controller.get().finish();
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(controller.get().mp);
    }

    @Test public void releasedPlayerErrorCannotFinishLaterOperation() {
        FindPhoneActivity activity = create();
        MediaPlayer previous = activity.mp;
        FindPhoneSession.INSTANCE.cancel(token);
        token = FindPhoneSession.INSTANCE.begin(new FindPhoneSession.Listener() {
            public void started() { starts++; }
            public void finished(boolean failure) { finishes++; }
        });
        controller.newIntent(intent());
        assertNotSame(previous, activity.mp);
        assertTrue(activity.mp.isPlaying());
        shadowOf(previous).invokeErrorListener(1, 0);
        assertTrue(activity.mp.isPlaying());
        assertEquals(0, finishes);
        assertEquals(2, starts);
    }

}
