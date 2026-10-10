package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerAppCipher;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerCapabilityResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerSecureDecoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerSecureEncoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerServiceConnector;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecordCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Runs the real encrypted request handler in both debug and release unit-test variants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearS3HealthCapabilityTest {
    // Synthetic session key and ID; no watch data or Bluetooth connection.
    private static final byte[] KEY = new byte[32];
    private static final int SESSION = 77;
    private GearS3DeviceSupport support;
    private SapPedometerSecureEncoder watchEncoder;
    private final List<byte[]> writes = new ArrayList<>();
    private final List<Runnable> afterWrite = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        support = spy(new GearS3DeviceSupport());
        doReturn(RuntimeEnvironment.getApplication()).when(support).getContext();
        doNothing().when(support).disconnect();
        final TransactionBuilder tx = mock(TransactionBuilder.class, RETURNS_SELF);
        doAnswer(call -> {
            writes.add(((byte[]) call.getRawArguments()[0]).clone());
            return tx;
        }).when(tx).write(any(byte[].class));
        doAnswer(call -> {
            afterWrite.add(call.getArgument(0));
            return tx;
        }).when(tx).run(any(Runnable.class));
        doAnswer(call -> {
            for (final Runnable callback : afterWrite) callback.run();
            afterWrite.clear();
            return null;
        }).when(tx).queue();
        doReturn(tx).when(support).createTransactionBuilder(anyString());
        final SapPedometerServiceConnector connector = mock(SapPedometerServiceConnector.class);
        when(connector.getState()).thenReturn(SapPedometerServiceConnector.State.ESTABLISHED);
        when(connector.getApplicationSessionId()).thenReturn(SESSION);
        set("pedometerServiceConnector", connector);
        set("pedometerSecureDecoder", new SapPedometerSecureDecoder(new SapHostManagerAppCipher(KEY)));
        set("pedometerSecureEncoder", new SapPedometerSecureDecoder(new SapHostManagerAppCipher(KEY)).encoder());
        watchEncoder = new SapPedometerSecureDecoder(new SapHostManagerAppCipher(KEY)).encoder();
    }

    @After
    public void cleanup() {
        if (support != null) support.dispose();
    }

    @Test
    public void encryptedRichRequestRepliesWithoutADebugBuildGate() throws Exception {
        receive(request());
        assertEquals(1, writes.size());
        final List<SapPostAuthRecord> frames = new SapPostAuthRecordCodec().accept(writes.get(0));
        assertEquals(1, frames.size());
        assertEquals(SESSION, frames.get(0).getHeader().getSessionId());
        final SapPedometerSecureDecoder.Result decoded = new SapPedometerSecureDecoder(
                new SapHostManagerAppCipher(KEY)).decode(frames.get(0).getBody());
        assertTrue(decoded.isValid());
        final JSONObject response = new JSONObject(new String(decoded.getApplicationBytes(), StandardCharsets.UTF_8));
        assertEquals(SapPedometerCapabilityResponse.RESPONSE, response.getString("capability_type"));
        assertEquals(SapPedometerCapabilityResponse.RICH_RESPONSE, response.getString("message_type"));
        assertEquals("shealth", response.getString("sender"));
        assertEquals("wearable", response.getString("receiver"));
        assertEquals(4.51, response.getDouble("protocol_version"), 0.00001);
        assertFalse(response.has("sync_address"));
    }

    @Test
    public void duplicateRequestRepliesOnceUntilHealthSessionReset() throws Exception {
        receive(request());
        receive(request());
        assertEquals(1, writes.size());
        invoke("resetPedometerConnectionDiagnostics", new Class<?>[0]);
        receive(request());
        assertEquals(2, writes.size());
    }

    @Test
    public void mismatchedDiscriminatorsOrEndpointsDoNotReply() throws Exception {
        for (final String field : new String[]{"capability_type", "message_type", "sender", "receiver"}) {
            receive(request().put(field, "unsupported"));
        }
        assertTrue(writes.isEmpty());
    }

    @Test
    public void missingTxCipherDoesNotConsumeTheReply() throws Exception {
        set("pedometerSecureEncoder", null);
        receive(request());
        assertTrue(writes.isEmpty());
        set("pedometerSecureEncoder", new SapPedometerSecureDecoder(new SapHostManagerAppCipher(KEY)).encoder());
        receive(request());
        assertEquals(1, writes.size());
    }

    private JSONObject request() throws Exception {
        return new JSONObject()
                .put("capability_type", SapPedometerCapabilityResponse.REQUEST)
                .put("message_type", SapPedometerCapabilityResponse.RICH_REQUEST)
                .put("sender", "wearable").put("receiver", "shealth");
    }

    private void receive(final JSONObject request) throws Exception {
        final byte[] body = watchEncoder.encode(request.toString().getBytes(StandardCharsets.UTF_8)).getBody();
        invoke("handlePedometerServiceApplicationRecord", new Class<?>[]{SapPostAuthRecord.class},
                new SapPostAuthRecord(new SapTransportHeader(0, 0, SESSION, 0), body));
    }

    private void set(final String name, final Object value) throws Exception {
        final Field field = GearS3DeviceSupport.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(support, value);
    }

    private void invoke(final String name, final Class<?>[] types, final Object... args) throws Exception {
        final Method method = GearS3DeviceSupport.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        synchronized (support) {
            method.invoke(support, args);
        }
    }
}
