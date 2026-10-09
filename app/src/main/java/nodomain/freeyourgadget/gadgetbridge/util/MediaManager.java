/*  Copyright (C) 2023-2024 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.io.ByteArrayOutputStream;

import nodomain.freeyourgadget.gadgetbridge.externalevents.NotificationListener;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;

public class MediaManager {
    private static final Logger LOG = LoggerFactory.getLogger(MediaManager.class);
    private static final int MUSIC_ART_SIZE = 320;
    private static final int MAX_MUSIC_ART_BYTES = 32 * 1024;

    private final Context context;

    private MusicSpec bufferMusicSpec = null;
    private MusicStateSpec bufferMusicStateSpec = null;

    public MediaManager(final Context context) {
        this.context = context;
    }

    @Nullable
    public MusicSpec getBufferMusicSpec() {
        return bufferMusicSpec;
    }

    @Nullable
    public MusicStateSpec getBufferMusicStateSpec() {
        return bufferMusicStateSpec;
    }

    /**
     * Returns true if the spec changed, so the device should be updated.
     */
    public boolean onSetMusicState(final MusicStateSpec stateSpec) {
        if (stateSpec != null && !stateSpec.equals(bufferMusicStateSpec)) {
            bufferMusicStateSpec = stateSpec;
            return true;
        }

        return false;
    }

    /**
     * Returns true if the spec changed, so the device should be updated.
     */
    public boolean onSetMusicInfo(MusicSpec musicSpec) {
        if (musicSpec != null && !musicSpec.equals(bufferMusicSpec)) {
            bufferMusicSpec = musicSpec;
            if (bufferMusicStateSpec != null) {
                bufferMusicStateSpec.setState((byte) 0);
                bufferMusicStateSpec.setPosition(0);
            }
            return true;
        }
        return false;
    }

    public void refresh() {
        LOG.info("Refreshing media state");

        final MediaSessionManager mediaSessionManager =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);

        try {
            final List<MediaController> controllers = mediaSessionManager.getActiveSessions(
                    new ComponentName(context, NotificationListener.class)
            );
            final MediaController controller;
            if (controllers.isEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Fallback to the one that would handle the media key event, if any
                    final MediaSession.Token mediaKeyEventSession = mediaSessionManager.getMediaKeyEventSession();
                    if (mediaKeyEventSession != null) {
                        LOG.debug("Got media key event session controller");
                        controller = new MediaController(context, mediaKeyEventSession);
                    } else {
                        LOG.debug("No fallback media controller available");
                        bufferMusicSpec = null;
                        bufferMusicStateSpec = null;
                        return;
                    }
                } else {
                    LOG.debug("No active media controller available");
                    bufferMusicSpec = null;
                    bufferMusicStateSpec = null;
                    return;
                }
            } else {
                controller = controllers.get(0);
            }

            bufferMusicSpec = extractMusicSpec(controller.getMetadata());
            bufferMusicStateSpec = extractMusicStateSpec(controller.getPlaybackState());
        } catch (final SecurityException e) {
            LOG.warn("No permission to get media sessions - did not grant notification access?", e);
        } catch (final Exception e) {
            LOG.error("Failed to get media info", e);
        }
    }

    @Nullable
    public static MusicSpec extractMusicSpec(final MediaMetadata d) {
        if (d == null) {
            return null;
        }

        final MusicSpec musicSpec = new MusicSpec();

        try {
            if (d.containsKey(MediaMetadata.METADATA_KEY_ARTIST))
                musicSpec.setArtist(d.getString(MediaMetadata.METADATA_KEY_ARTIST));
            if (d.containsKey(MediaMetadata.METADATA_KEY_ALBUM))
                musicSpec.setAlbum(d.getString(MediaMetadata.METADATA_KEY_ALBUM));
            if (d.containsKey(MediaMetadata.METADATA_KEY_TITLE))
                musicSpec.setTrack(d.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (d.containsKey(MediaMetadata.METADATA_KEY_DURATION))
                musicSpec.setDuration((int) d.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);
            final Bitmap albumArt = d.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                    ? d.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    : d.getBitmap(MediaMetadata.METADATA_KEY_ART);
            musicSpec.setAlbumArt(encodeMusicArtwork(albumArt));
            if (d.containsKey(MediaMetadata.METADATA_KEY_NUM_TRACKS))
                musicSpec.setTrackCount((int) d.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS));
            if (d.containsKey(MediaMetadata.METADATA_KEY_TRACK_NUMBER))
                musicSpec.setTrackNr((int) d.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER));
        } catch (final Exception e) {
            LOG.error("Failed to extract music spec", e);
        }

        return musicSpec;
    }

    /** Converts the MediaSession bitmap before MusicSpec is placed in an Intent. */
    @Nullable
    static byte[] encodeMusicArtwork(@Nullable final Bitmap artwork) {
        if (artwork == null) return null;
        try {
            int width = MUSIC_ART_SIZE;
            int height = MUSIC_ART_SIZE;
            if (artwork.getWidth() < artwork.getHeight()) width = Math.max(1, MUSIC_ART_SIZE * artwork.getWidth() / artwork.getHeight());
            if (artwork.getWidth() > artwork.getHeight()) height = Math.max(1, MUSIC_ART_SIZE * artwork.getHeight() / artwork.getWidth());
            final Bitmap scaled = Bitmap.createScaledBitmap(artwork, width, height, true);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 50, output);
            if (scaled != artwork) scaled.recycle();
            final byte[] bytes = output.toByteArray();
            return bytes.length <= MAX_MUSIC_ART_BYTES ? bytes : null;
        } catch (final Exception e) {
            LOG.warn("Failed to convert music artwork", e);
            return null;
        }
    }

    @Nullable
    public static MusicStateSpec extractMusicStateSpec(final PlaybackState s) {
        if (s == null) {
            return null;
        }

        final MusicStateSpec stateSpec = new MusicStateSpec();

        try {
            stateSpec.setPosition((int) (s.getPosition() / 1000));
            stateSpec.setPlayRate(Math.round(100 * s.getPlaybackSpeed()));
            stateSpec.setRepeat((byte) MusicStateSpec.STATE_UNKNOWN);
            stateSpec.setShuffle((byte) MusicStateSpec.STATE_UNKNOWN);
            switch (s.getState()) {
                case PlaybackState.STATE_PLAYING:
                    stateSpec.setState((byte) MusicStateSpec.STATE_PLAYING);
                    break;
                case PlaybackState.STATE_STOPPED:
                    stateSpec.setState((byte) MusicStateSpec.STATE_STOPPED);
                    break;
                case PlaybackState.STATE_PAUSED:
                    stateSpec.setState((byte) MusicStateSpec.STATE_PAUSED);
                    break;
                default:
                    stateSpec.setState((byte) MusicStateSpec.STATE_UNKNOWN);
                    break;
            }
        } catch (final Exception e) {
            LOG.error("Failed to extract music state spec", e);
        }

        return stateSpec;
    }

    public int getPhoneVolume() {
        return getPhoneVolume(context);
    }

    public static int getPhoneVolume(final Context context) {
        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        final int volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        final int volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return Math.round(100 * (volumeLevel / (float) volumeMax));
    }
}
