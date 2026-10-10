package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3CommonSyncState;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapHostManagerAppCipher;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerSecureDecoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerSecureEncoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerServiceConnector;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerServiceRegistration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecordCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapServiceConnectionResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Actual framed socket RX and serialized dispatcher, without Bluetooth or private captures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearS3ServiceControlResponseTest {
    private GearS3DeviceSupport support;
    private SapPedometerServiceConnector connector;
    private SapPedometerSecureDecoder decoder;
    private SapPedometerSecureEncoder encoder;

    @Before
    public void setup() throws Exception {
        support = spy(new GearS3DeviceSupport());
        doReturn(RuntimeEnvironment.getApplication()).when(support).getContext();
        doNothing().when(support).disconnect();
        doReturn(mock(TransactionBuilder.class, RETURNS_SELF)).when(support).createTransactionBuilder(anyString());
        connector = mock(SapPedometerServiceConnector.class);
        when(connector.getState()).thenReturn(SapPedometerServiceConnector.State.ESTABLISHED);
        when(connector.getApplicationSessionId()).thenReturn(77);
        set("pedometerServiceConnector", connector);
        decoder = new SapPedometerSecureDecoder(new SapHostManagerAppCipher(new byte[32]));
        encoder = decoder.encoder();
        set("pedometerSecureDecoder", decoder);
        set("pedometerSecureEncoder", encoder);
        set("postAuthRecords", new SapPostAuthRecordCodec());
        set("sapDispatchGeneration", 1L);
    }

    @After
    public void cleanup() {
        if (support != null) support.dispose();
    }

    @Test
    public void repeatedEstablishedHealthResponseDoesNotDisconnectOrClearSync() throws Exception {
        final GearS3CommonSyncState sync = (GearS3CommonSyncState) get("commonSyncState");
        assertTrue(sync.begin());
        receive(response(SapPedometerServiceRegistration.PROFILE, 0));
        receive(response(SapPedometerServiceRegistration.PROFILE, 0));
        assertHealthy();
        assertTrue(sync.isActive());
        verify(connector, never()).handleResponse(any(SapServiceConnectionResponse.class));
    }

    @Test
    public void unrelatedResponseDoesNotConsumePendingHealthRequest() throws Exception {
        when(connector.getState()).thenReturn(SapPedometerServiceConnector.State.PENDING);
        receive(response("/system/test-unrelated", 0));
        assertHealthy();
        assertEquals(SapPedometerServiceConnector.State.PENDING, connector.getState());
        verify(connector, never()).handleResponse(any(SapServiceConnectionResponse.class));
    }

    @Test
    public void lateRejectedResponseWithNoPendingRequestIsIgnored() throws Exception {
        when(connector.getState()).thenReturn(SapPedometerServiceConnector.State.IDLE);
        receive(response(SapPedometerServiceRegistration.PROFILE, SapServiceConnectionResponse.STATUS_REJECTED));
        assertHealthy();
        verify(connector, never()).handleResponse(any(SapServiceConnectionResponse.class));
    }

    @Test
    public void matchingPendingResponseStillRunsConnectorValidation() throws Exception {
        when(connector.getState()).thenReturn(SapPedometerServiceConnector.State.PENDING);
        doThrow(new IllegalArgumentException("Synthetic mismatched response"))
                .when(connector).handleResponse(any(SapServiceConnectionResponse.class));
        receive(response(SapPedometerServiceRegistration.PROFILE, 0));
        verify(connector).handleResponse(any(SapServiceConnectionResponse.class));
        verify(support).disconnect();
        assertEquals(2L, get("sapDispatchGeneration"));
    }

    @Test
    public void malformedResponseIsStillRejectedByTheCodec() throws Exception {
        receive(new byte[]{SapServiceConnectionResponse.TYPE});
        verify(support).disconnect();
        assertEquals(2L, get("sapDispatchGeneration"));
    }

    private byte[] response(final String profile, final int status) {
        return SapServiceConnectionCodec.encode(new SapServiceConnectionResponse(0x1234, 0x2345,
                profile, status, Collections.singletonList(77)));
    }

    private void receive(final byte[] body) throws Exception {
        support.onSocketRead(SapPostAuthRecordCodec.encode(new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.SERVICE_CONTROL_SESSION_ID, 0), body)));
        ((ExecutorService) get("sapApplicationExecutor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private void assertHealthy() throws Exception {
        verify(support, never()).disconnect();
        verify(connector, never()).clear();
        assertEquals(1L, get("sapDispatchGeneration"));
        assertSame(decoder, get("pedometerSecureDecoder"));
        assertSame(encoder, get("pedometerSecureEncoder"));
    }

    private Object get(final String name) throws Exception {
        final Field field = GearS3DeviceSupport.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(support);
    }

    private void set(final String name, final Object value) throws Exception {
        final Field field = GearS3DeviceSupport.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(support, value);
    }
}
