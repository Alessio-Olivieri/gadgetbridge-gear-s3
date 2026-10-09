package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GearContactsCodecTest {
    private static GearContactsCodec.Contact record(final String name, final String number) {
        return new GearContactsCodec.Contact(700001, 800001, 700001, name, "Sample", "Person", false,
                Collections.singletonList(new GearContactsCodec.Number(number, null, 2, null, true)));
    }

    @Test public void encodesSourceShapedContactAndDelete() throws Exception {
        final JSONObject change = GearContactsCodec.change("insert", Collections.singletonList(record("Sample Person", "+15550001001")), Collections.emptyList());
        final JSONObject item = change.getJSONObject("insert").getJSONArray("raw_contact_list").getJSONObject(0);
        assertEquals(1, change.getJSONObject("insert").getInt("number_of_raw_contacts"));
        assertEquals(700001, item.getJSONObject("raw_contact").getLong("id"));
        assertEquals(800001, item.getJSONObject("raw_contact").getLong("contact_id"));
        assertEquals("Sample Person", item.getJSONObject("raw_contact").getString("display_name"));
        assertEquals("name", item.getJSONArray("data_list").getJSONObject(0).getString("type"));
        assertEquals("+15550001001", item.getJSONArray("data_list").getJSONObject(1).getJSONObject("data").getString("number"));
        final JSONObject deleted = GearContactsCodec.change("delete", Collections.emptyList(), Collections.singletonList(700001L));
        assertEquals(1, deleted.getJSONObject("delete").getInt("number_of_raw_contacts"));
        final JSONObject deleteItem = deleted.getJSONObject("delete").getJSONArray("raw_contact_list").getJSONObject(0);
        assertEquals(1, deleteItem.length());
        assertEquals(1, deleteItem.getJSONObject("raw_contact").length());
        assertEquals(700001L, deleteItem.getJSONObject("raw_contact").getLong("id"));
        assertFalse(deleteItem.has("id"));
    }

    @Test public void updateCarriesCurrentPhoneRowsWithoutInventedDataIds() throws Exception {
        final GearContactsCodec.Contact updated = new GearContactsCodec.Contact(700001, 800001, 700001,
                "Synthetic Person", "Synthetic", "Person", false,
                Collections.singletonList(new GearContactsCodec.Number("+15550002002", null, 2, null, true)));
        final JSONObject item = GearContactsCodec.change("update", Collections.singletonList(updated), Collections.emptyList())
                .getJSONObject("update").getJSONArray("raw_contact_list").getJSONObject(0);
        assertEquals(700001L, item.getJSONObject("raw_contact").getLong("id"));
        assertEquals(2, item.getJSONArray("data_list").length());
        final JSONObject phone = item.getJSONArray("data_list").getJSONObject(1);
        assertEquals("number", phone.getString("type"));
        assertEquals("+15550002002", phone.getJSONObject("data").getString("number"));
        assertFalse(phone.has("data_id"));
        assertFalse(phone.has("id"));
    }

    @Test public void removedPhoneRowIsAbsentFromSourceShapedCurrentRowList() throws Exception {
        final GearContactsCodec.Contact current = new GearContactsCodec.Contact(700001, 800001, 700001,
                "Synthetic Person", "Synthetic", "Person", false,
                Collections.singletonList(new GearContactsCodec.Number("+15550002002", null, 2, null, true)));
        final JSONObject item = GearContactsCodec.change("update", Collections.singletonList(current), Collections.emptyList())
                .getJSONObject("update").getJSONArray("raw_contact_list").getJSONObject(0);
        final String encoded = item.toString();
        assertFalse(encoded.contains("+15550001001"));
        assertEquals(2, item.getJSONArray("data_list").length());
    }

    @Test public void reliableFrameAndFeatureResponseMatchCapturedShape() throws Exception {
        final byte[] body = "Request_Initial_Sync_Contact".getBytes(StandardCharsets.UTF_8);
        final byte[] frame = GearContactsCodec.frame(3, body);
        assertArrayEquals(new byte[]{0, 3, 0}, Arrays.copyOf(frame, 3));
        assertEquals(3, GearContactsCodec.sequence(frame));
        assertArrayEquals(body, GearContactsCodec.payload(frame));
        final JSONObject feature = GearContactsCodec.featureResponse(new JSONObject("{\"msgId\":\"contacts-getfeature-req\",\"requestId\":1}"));
        assertEquals("1", feature.getString("requestId"));
        assertEquals("1.35", feature.getString("version"));
        assertFalse(feature.getJSONArray("feature").getJSONObject(0).getBoolean("supportLog"));
    }

    @Test public void recognizesCryptPrefixedSyncAndSeparateLogIdControl() {
        assertEquals(GearContactsCodec.Control.RESUME_SYNC, GearContactsCodec.control("crypt_Request_Resume_Sync_Contact"));
        assertEquals(GearContactsCodec.Control.INITIAL_SYNC, GearContactsCodec.control("crypt_Request_Initial_Sync_Contact"));
        assertEquals(GearContactsCodec.Control.ALL_LOG_IDS, GearContactsCodec.control("crypt_Request_get_all_log_ids"));
        assertEquals(GearContactsCodec.Control.UNKNOWN, GearContactsCodec.control("crypt_Request_other"));
        assertEquals("Response_Initial_Sync_Required_Contact", GearContactsCodec.resumeResponse(false));
        assertEquals("Response_Resume_Sync_Contact", GearContactsCodec.resumeResponse(true));
    }

    @Test public void logActivationResponseMatchesCapturedControlShape() throws Exception {
        final JSONObject response = GearContactsCodec.logActivationResponse(new JSONObject(
                "{\"msgId\":\"logs-activatesync-req\",\"value\":\"true\",\"requestId\":1}"));
        assertEquals("logs-activatesync-rsp", response.getString("msgId"));
        assertEquals("1", response.getString("requestId"));
        assertEquals("success", response.getString("result"));
        assertEquals(3, response.length());
    }

    @Test public void encryptedZipContainsSamsungDatPayload() throws Exception {
        final byte[] key = new byte[32]; Arrays.fill(key, (byte) 7);
        final JSONObject change = GearContactsCodec.change("insert", Collections.singletonList(record("Sample Person", "+15550001001")), Collections.emptyList());
        final byte[] zipped = GearContactsCodec.contactFile(change, key, "SAContactJSONFile123");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            final ZipEntry entry = zip.getNextEntry();
            assertEquals("SAContactJSONFile123.dat", entry.getName());
            final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            final byte[] buffer = new byte[1024]; int count;
            while ((count = zip.read(buffer)) != -1) encoded.write(buffer, 0, count);
            final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            final String decoded = new String(cipher.doFinal(Base64.getMimeDecoder().decode(encoded.toByteArray())), StandardCharsets.UTF_8);
            assertEquals(change.getJSONObject("insert").getInt("number_of_raw_contacts"), new JSONObject(decoded).getJSONObject("insert").getInt("number_of_raw_contacts"));
            assertEquals(null, zip.getNextEntry());
        }
    }
}
