package nodomain.freeyourgadget.gadgetbridge.model;

import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nodomain.freeyourgadget.gadgetbridge.test.GBTestApplication;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23, application = GBTestApplication.class)
public class MusicSpecParcelTest {
    @Test public void boundedArtworkAndMetadataSurviveParcelRoundTrip() {
        final MusicSpec source = new MusicSpec("Artist", "Album", "Title", 123, 10, 2, new byte[] {1, 2, 3});
        final Parcel parcel = Parcel.obtain();
        source.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final MusicSpec restored = MusicSpec.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        assertEquals("Artist", restored.getArtist());
        assertEquals("Album", restored.getAlbum());
        assertEquals("Title", restored.getTrack());
        assertEquals(123, restored.getDuration());
        assertArrayEquals(new byte[] {1, 2, 3}, restored.getAlbumArt());
    }

    @Test public void nullArtworkSurvivesParcelRoundTrip() {
        final Parcel parcel = Parcel.obtain();
        new MusicSpec().writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        assertNull(MusicSpec.CREATOR.createFromParcel(parcel).getAlbumArt());
        parcel.recycle();
    }
}
