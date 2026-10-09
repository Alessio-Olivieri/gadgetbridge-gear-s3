package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;

/** Latest lightweight music state retained until the watch enables media-change delivery. */
public final class SapMusicStateCache {
    private byte[] musicMetadata;
    private MusicStateSpec musicStateSpec;
    private boolean mediaChangedSubscribed;

    public void reset() { musicMetadata = null; musicStateSpec = null; mediaChangedSubscribed = false; }
    /** Encode artwork immediately so the cache never retains the source-sized bitmap. */
    public void setMusicSpec(final MusicSpec value) { musicMetadata = value == null ? null : SapMusicCodec.encodeMetadata(value); }
    public void setMusicStateSpec(final MusicStateSpec value) { musicStateSpec = value == null ? null : new MusicStateSpec(value); }
    public byte[] getMusicMetadata() { return musicMetadata == null ? null : musicMetadata.clone(); }
    public MusicStateSpec getMusicStateSpec() { return musicStateSpec == null ? null : new MusicStateSpec(musicStateSpec); }
    public void setMediaChangedSubscribed(final boolean value) { mediaChangedSubscribed = value; }
    public boolean isMediaChangedSubscribed() { return mediaChangedSubscribed; }
}
