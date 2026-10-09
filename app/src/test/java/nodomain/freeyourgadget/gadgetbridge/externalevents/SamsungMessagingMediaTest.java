package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.net.Uri;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SamsungMessagingMediaTest {
    private final Person self = new Person.Builder().setName("Me").setKey("self-key").build();
    private final Person remote = new Person.Builder().setName("Contact").setKey("remote-key").build();

    @Test public void latestPhotoOrStickerEligibleButLaterTextClearsMedia() {
        for (String mime : new String[]{"image/jpeg", "image/webp"}) {
            NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(self);
            NotificationSpec spec = new NotificationSpec();
            style.addMessage(new NotificationCompat.MessagingStyle.Message("photo", 1, remote)
                    .setData(mime, Uri.parse("content://test/image")));
            NotificationListener.populateSamsungBigPictureCompatibilityCandidate(style, spec);
            assertEquals(mime, spec.getSamsungBigPictureMediaMimeType());
            style.addMessage(new NotificationCompat.MessagingStyle.Message("later", 2, remote));
            NotificationListener.populateSamsungBigPictureCompatibilityCandidate(style, spec);
            assertNull(spec.getSamsungBigPictureMediaUri());
        }
    }

    @Test public void selfReplyAfterPhotoClearsMediaAndIdentityUsesKeys() {
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(self);
        style.addMessage(new NotificationCompat.MessagingStyle.Message("photo", 1, remote)
                .setData("image/jpeg", Uri.parse("content://test/image")));
        NotificationCompat.MessagingStyle.Message reply = new NotificationCompat.MessagingStyle.Message("reply", 2, (Person) null);
        style.addMessage(reply);
        NotificationSpec spec = new NotificationSpec();
        NotificationListener.populateSamsungBigPictureCompatibilityCandidate(style, spec);
        assertNull(spec.getSamsungBigPictureMediaUri());
        assertTrue(NotificationListener.samsungMessageIsSelf(style, reply));
        assertTrue(NotificationListener.samsungMessageIsSelf(style, new NotificationCompat.MessagingStyle.Message("", 3,
                new Person.Builder().setName("Renamed").setKey("self-key").build())));
        assertFalse(NotificationListener.samsungMessageIsSelf(style, new NotificationCompat.MessagingStyle.Message("", 3,
                new Person.Builder().setName("Me").setKey("remote-key").build())));
    }
}
