package nodomain.freeyourgadget.gadgetbridge.util;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MediaManagerArtworkTest {
    @Test public void nullArtworkConversionDegradesSafely() {
        assertNull(MediaManager.encodeMusicArtwork(null));
    }
}
