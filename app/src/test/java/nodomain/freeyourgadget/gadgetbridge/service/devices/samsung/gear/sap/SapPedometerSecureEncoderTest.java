package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.json.JSONObject;
import org.junit.Test;

public class SapPedometerSecureEncoderTest {
    private static final byte[] KEY = hex("8572bc810257ed522b4e5fe80135ff5f3a88d19c3c273dff51170a060617fa26");

    private static Context contextWithSamsungHealthVersion(final long versionCode) throws Exception {
        final Context context = mock(Context.class);
        final PackageManager packageManager = mock(PackageManager.class);
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.versionCode = (int) versionCode;
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getPackageInfo(eq(SapPedometerCapabilityResponse.SAMSUNG_HEALTH_PACKAGE), eq(0)))
                .thenReturn(packageInfo);
        return context;
    }

    @Test
    public void managerResponseReportsInstalledSamsungHealthVersion() throws Exception {
        final Context context = contextWithSamsungHealthVersion(7006011L);
        final JSONObject response = new JSONObject(new String(
                SapPedometerCapabilityResponse.managerResponseJson(context), StandardCharsets.UTF_8));
        assertEquals(SapPedometerCapabilityResponse.RESPONSE, response.getString("capability_type"));
        assertEquals(0, response.getInt("gear_plugin_version"));
        assertEquals(7006011L, response.getLong("health_version"));
        assertEquals("installed", response.getString("status"));
        assertEquals("android", response.getString("os"));
        assertEquals("wearable_manager", response.getString("sender"));
        assertEquals("wearable", response.getString("receiver"));
        assertFalse(response.has("message_feature"));
    }

    @Test
    public void managerResponseReportsAbsentSamsungHealthTruthfully() throws Exception {
        final Context context = mock(Context.class);
        final PackageManager packageManager = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getPackageInfo(eq(SapPedometerCapabilityResponse.SAMSUNG_HEALTH_PACKAGE), eq(0)))
                .thenThrow(new PackageManager.NameNotFoundException());

        final JSONObject response = new JSONObject(new String(
                SapPedometerCapabilityResponse.managerResponseJson(context), StandardCharsets.UTF_8));
        assertEquals(-1L, response.getLong("health_version"));
        assertEquals("uninstalled", response.getString("status"));
        verify(packageManager).getPackageInfo(SapPedometerCapabilityResponse.SAMSUNG_HEALTH_PACKAGE, 0);
    }

    @Test
    public void richResponseUsesExactModernDiscriminatorsAndMinimalSchema() throws Exception {
        final JSONObject response = new JSONObject(new String(
                SapPedometerCapabilityResponse.richResponseJson(
                        contextWithSamsungHealthVersion(7006011L)), StandardCharsets.UTF_8));
        assertEquals(SapPedometerCapabilityResponse.RESPONSE, response.getString("capability_type"));
        assertEquals(SapPedometerCapabilityResponse.RICH_RESPONSE, response.getString("message_type"));
        assertEquals("shealth", response.getString("sender"));
        assertEquals("wearable", response.getString("receiver"));
        assertEquals(7006011L, response.getLong("shealth_version"));
        assertEquals(4.51, response.getDouble("protocol_version"), 0.00001);
        assertEquals("android", response.getJSONObject("config").getString("os"));
        assertFalse(response.has("sync_address"));
    }

    @Test
    public void richResponseUsesExistingUncompressedHealthTxPath() throws Exception {
        final SapPedometerSecureEncoder encoder = new SapPedometerSecureEncoder(new SapHostManagerAppCipher(KEY));
        final SapPedometerSecureEncoder.EncodedFrame frame = encoder.encode(
                SapPedometerCapabilityResponse.richResponseJson(
                        contextWithSamsungHealthVersion(7006011L)));
        assertEquals(0x04, frame.getSdk());
        assertFalse(frame.isCompressed());
        assertEquals(0, frame.getSequence());
    }

    @Test
    public void richCapabilityRequestRequiresBothDiscriminatorsAndEndpoints() {
        final String request = "{\"capability_type\":\"" + SapPedometerCapabilityResponse.REQUEST
                + "\",\"message_type\":\"" + SapPedometerCapabilityResponse.RICH_REQUEST
                + "\",\"sender\":\"wearable\",\"receiver\":\"shealth\"}";
        assertTrue(SapPedometerCapabilityResponse.isRichCapabilityRequest(
                SapPedometerJsonProbe.parse(request.getBytes(StandardCharsets.UTF_8))));
        assertFalse(SapPedometerCapabilityResponse.isRichCapabilityRequest(
                SapPedometerJsonProbe.parse(request.replace("\"sender\":\"wearable\"", "\"sender\":\"other\"")
                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void smallManagerResponseUsesUncompressedSdkFrameAndSequenceZero() throws Exception {
        final SapPedometerSecureEncoder encoder = new SapPedometerSecureEncoder(new SapHostManagerAppCipher(KEY));
        final byte[] clear = SapPedometerCapabilityResponse.managerResponseJson(
                contextWithSamsungHealthVersion(7006011L));
        final SapPedometerSecureEncoder.EncodedFrame frame = encoder.encode(clear);
        assertEquals(0x04, frame.getSdk());
        assertFalse(frame.isCompressed());
        assertEquals(0, frame.getSequence());
        assertEquals(clear.length + SapHostManagerAppCipher.OVERHEAD, frame.getProtectedLength());
    }

    @Test
    public void receiverDecodesEncodedSmallAndCompressedFrames() {
        final SapHostManagerAppCipher senderCipher = new SapHostManagerAppCipher(KEY);
        final SapPedometerSecureEncoder encoder = new SapPedometerSecureEncoder(senderCipher);
        final SapPedometerSecureDecoder decoder = new SapPedometerSecureDecoder(new SapHostManagerAppCipher(KEY));
        final byte[] small = "{\"capability_type\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        final SapPedometerSecureEncoder.EncodedFrame first = encoder.encode(small);
        assertArrayEquals(small, decoder.decode(first.getBody()).getApplicationBytes());

        final byte[] large = new byte[1024];
        Arrays.fill(large, (byte) 'a');
        final SapPedometerSecureEncoder.EncodedFrame second = encoder.encode(large);
        assertTrue(second.isCompressed());
        assertEquals(0x14, second.getSdk());
        assertEquals(1, second.getSequence());
        assertArrayEquals(large, decoder.decode(second.getBody()).getApplicationBytes());
    }

    private static byte[] hex(final String value) {
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
