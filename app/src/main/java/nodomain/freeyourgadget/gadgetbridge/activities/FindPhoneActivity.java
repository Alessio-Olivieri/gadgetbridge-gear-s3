/*  Copyright (C) 2018-2024 Andreas Shimokawa, Anemograph, Carsten Pfeiffer,
    Cre3per, Daniele Gobbetti, Dmitriy Bogdanov, José Rebelo, Pauli Salmenrinne

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
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.FindPhoneSession;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;


public class FindPhoneActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(FindPhoneActivity.class);

    public static final String ACTION_FOUND = "nodomain.freeyourgadget.gadgetbridge.findphone.action.reply";
    public static final String ACTION_VIBRATE = "nodomain.freeyourgadget.gadgetbridge.findphone.action.vibrate";
    public static final String ACTION_RING = "nodomain.freeyourgadget.gadgetbridge.findphone.action.ring";

    public static final String EXTRA_RING = "extra_ring";

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Tokenless broadcasts belong to legacy callers and must not stop an owned alert.
            if (sessionToken != null) return;
            String action = intent.getAction();
            if (action != null) {
                LOG.info("Got action: {}", action);

                switch (action) {
                    case ACTION_FOUND:
                        finish();
                        break;
                    case ACTION_VIBRATE:
                        stopSound();
                        break;
                    case ACTION_RING:
                        playRingtone();
                        break;
                }
            }
        }
    };

    Vibrator mVibrator;
    AudioManager mAudioManager;
    int userVolume;
    private boolean volumeSaved;
    private String sessionToken;
    private boolean legacyOwned;
    private boolean playbackFailed;
    MediaPlayer mp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LOG.info("Find Phone Activity onCreate: owned={}",
                getIntent().hasExtra(FindPhoneSession.EXTRA_TOKEN));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_find_phone);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FOUND);
        filter.addAction(ACTION_VIBRATE);
        filter.addAction(ACTION_RING);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filter);
        ContextCompat.registerReceiver(this, mReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED); // for ACTION_FOUND

        Button foundButton = findViewById(R.id.foundbutton);
        foundButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        LOG.info("Find Phone Activity onStart: owned={} finishing={}", sessionToken != null, isFinishing());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        final String requested = intent.getStringExtra(FindPhoneSession.EXTRA_TOKEN);
        if (requested != null) {
            if (!FindPhoneSession.INSTANCE.current(requested)) {
                LOG.info("Find Phone Activity discarded canceled/stale launch");
                if (sessionToken == null && !legacyOwned) finish();
                return;
            }
            if (requested.equals(sessionToken)) return;
            if (legacyOwned) return;
            if (sessionToken != null) {
                // The previous operation was canceled while Android was delivering this new intent.
                stopVibration();
                stopSound();
            }
            sessionToken = requested;
            playbackFailed = false;
            final boolean accepted = FindPhoneSession.INSTANCE.attachAndRun(requested,
                    () -> runOnUiThread(() -> {
                        if (!requested.equals(sessionToken)) return;
                        stopVibration();
                        stopSound();
                        finish();
                    }), () -> {
                        GB.removeNotification(GB.NOTIFICATION_ID_PHONE_FIND, this);
                        try {
                            vibrate();
                            if (!playRingtone()) throw new IllegalStateException("No Find Phone playback");
                            LOG.info("Find Phone owned playback started");
                        } catch (final RuntimeException e) {
                            LOG.warn("Find Phone owned playback failed", e);
                            playbackFailed = true;
                            stopVibration();
                            stopSound();
                            FindPhoneSession.INSTANCE.finish(requested, true);
                            finish();
                        }
                    });
            if (!accepted) finish();
            return;
        }
        if (sessionToken != null || !FindPhoneSession.INSTANCE.enterLegacy()) return;
        legacyOwned = true;
        final boolean ring = intent.getBooleanExtra(EXTRA_RING, true);
        GB.removeNotification(GB.NOTIFICATION_ID_PHONE_FIND, this);
        vibrate();
        if (ring) playRingtone();
        GBApplication.deviceService().onFindPhone(true);
    }

    private void vibrate() {
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (mVibrator == null) return;
        long[] vibrationPattern = new long[]{1000, 1000};

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, 0);

            mVibrator.vibrate(vibrationEffect);
        } else {
            mVibrator.vibrate(vibrationPattern, 0);
        }
    }

    private boolean playRingtone() {
        if (mp != null && mp.isPlaying()) return true;
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (mp == null) {
            LOG.info("Find Phone MediaPlayer initialization: owned={}", sessionToken != null);
            mp = new MediaPlayer();
            final String playbackToken = sessionToken;
            if (playbackToken != null) mp.setOnErrorListener((player, what, extra) -> {
                if (player != mp || !playbackToken.equals(sessionToken)
                        || !FindPhoneSession.INSTANCE.current(playbackToken)) return true;
                LOG.warn("Find Phone MediaPlayer error: what={} extra={}", what, extra);
                playbackFailed = true;
                finish();
                return true;
            });
        }
        if (!playConfiguredRingtone()) {
            LOG.info("Find Phone ringtone fallback: resetting player after configured source failure");
            mp.reset();
            if (!playFallbackRingtone()) return false;
        }
        // Owned requests respect alarm volume and DND; no persistent system settings to restore.
        if (sessionToken == null && mAudioManager != null && !volumeSaved) {
            userVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            volumeSaved = true;
            try {
                mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                        mAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), AudioManager.FLAG_PLAY_SOUND);
            } catch (final RuntimeException e) { LOG.warn("Could not adjust Find Phone alarm volume", e); }
        }
        return true;
    }

    private void stopVibration() {
        if (mVibrator != null) {
            try { mVibrator.cancel(); }
            catch (final RuntimeException e) { LOG.warn("Could not stop Find Phone vibration", e); }
            mVibrator = null;
        }
    }

    /**
     * Attempt to play the configured ringtone. This fails to get the default ringtone on some ROMs
     * (<a href="https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/2697">#2697</a>)
     *
     * @return whether playing the configured ringtone was successful or not.
     */
    private boolean playConfiguredRingtone() {
        try {
            Uri ringtoneUri = Uri.parse(GBApplication.getPrefs().getString(GBPrefs.PING_TONE, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString()));
            mp.setDataSource(this, ringtoneUri);
            mp.setAudioStreamType(AudioManager.STREAM_ALARM);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            LOG.info("Find Phone ringtone startup succeeded: owned={}", sessionToken != null);
        } catch (final Exception e) {
            LOG.warn("Failed to play configured ringtone", e);
            return false;
        }

        return true;
    }

    private boolean playFallbackRingtone() {
        try (final AssetFileDescriptor afd = getBaseContext().getResources().openRawResourceFd(R.raw.ping_tone)) {
            if (afd == null) {
                LOG.error("Failed to load fallback ringtone");
                return false;
            }

            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setAudioStreamType(AudioManager.STREAM_ALARM);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            LOG.info("Find Phone ringtone startup succeeded: owned={}", sessionToken != null);
        } catch (final Exception e) {
            LOG.warn("Failed to play fallback ringtone", e);
            return false;
        }
        return true;
    }

    private void stopSound() {
        if (mp != null) {
            try { mp.release(); }
            catch (final RuntimeException e) { LOG.warn("Could not release Find Phone player", e); }
            mp = null;
        }
        if (mAudioManager != null && volumeSaved) {
            volumeSaved = false;
            try { mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, userVolume, 0); }
            catch (final RuntimeException e) { LOG.warn("Could not restore Find Phone alarm volume", e); }
        }
    }

    @Override
    public void finish() {
        // Stop now, rather than waiting for Android's eventual onDestroy callback.
        stopVibration();
        stopSound();
        if (sessionToken != null) FindPhoneSession.INSTANCE.finish(sessionToken, playbackFailed);
        super.finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        stopVibration();
        stopSound();
        if (sessionToken != null) FindPhoneSession.INSTANCE.finish(sessionToken, playbackFailed);
        if (legacyOwned) {
            FindPhoneSession.INSTANCE.leaveLegacy();
            GBApplication.deviceService().onFindPhone(false);
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        unregisterReceiver(mReceiver);
    }
}
