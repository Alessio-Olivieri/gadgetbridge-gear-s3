package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarCodec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarRangeSet;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapCalendarSnapshot;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPostAuthRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapTransportHeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Queue lifetime regressions; no Bluetooth, event contents, or physical watch state. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearS3TransportLifecycleTest {
    private GearS3DeviceSupport support;
    private GBDevice device;

    @Before
    public void setup() throws Exception {
        support = spy(new GearS3DeviceSupport());
        device = mock(GBDevice.class);
        when(device.getAddress()).thenReturn("00:11:22:33:44:55");
        doReturn(device).when(support).getDevice();
        doReturn(RuntimeEnvironment.getApplication()).when(support).getContext();
        set("sapDispatchGeneration", 1L);
    }

    @After
    public void cleanup() {
        support.dispose();
    }

    @Test
    public void failureAfterQueueRemovalReportsReconnectWithoutQueuing() throws Exception {
        support.disconnect(); // No queue exists, as in the captured failure.
        invoke("fail", new Class<?>[]{String.class, Exception.class},
                "synthetic input failure", new IllegalArgumentException("synthetic"));
        verify(support, never()).createTransactionBuilder(anyString());
        verify(device).setUpdateState(GBDevice.State.WAITING_FOR_RECONNECT, support.getContext());
    }

    @Test
    public void disconnectRetiresQueuedAndNewSapWork() throws Exception {
        final AtomicBoolean ran = new AtomicBoolean();
        synchronized (support) {
            invoke("enqueueSapApplication", new Class<?>[]{Runnable.class}, (Runnable) () -> ran.set(true));
            support.disconnect();
        }
        drain();
        invoke("enqueueSapApplication", new Class<?>[]{Runnable.class}, (Runnable) () -> ran.set(true));
        drain();
        assertFalse(ran.get());
        assertTrue((Boolean) get("sapTransportClosed"));
        assertEquals(2L, get("sapDispatchGeneration"));
    }

    @Test
    public void disconnectClearsRouteButRetainsCompletedBaseline() throws Exception {
        installRoute(1L);
        final SapCalendarRangeSet windows = (SapCalendarRangeSet) get("calendarWindows");
        windows.observe(SapCalendarCodec.parseRange(new JSONObject()
                .put("startTime", 1000L).put("endTime", 2000L)
                .put("maxEventCnt", 10).put("maxAlldayEventCnt", 5)), "UTC");
        final SapCalendarSnapshot snapshot = (SapCalendarSnapshot) get("calendarSnapshot");
        snapshot.beginFullSync();
        snapshot.recordList(Collections.emptyList());
        snapshot.completeFullSync();
        support.disconnect();
        assertNull(get("calendarRoute"));
        assertTrue(snapshot.hasBaseline());
        assertEquals(1, windows.getRanges().size());
    }

    @Test
    public void disconnectDoesNotPromoteInterruptedFullSync() throws Exception {
        final SapCalendarSnapshot snapshot = (SapCalendarSnapshot) get("calendarSnapshot");
        snapshot.beginFullSync();
        snapshot.recordList(Collections.emptyList());
        support.disconnect();
        assertFalse(snapshot.hasBaseline());
        assertFalse(snapshot.isCollectingFullSync());
    }

    @Test
    public void staleFramingFailureCannotRunInNewTransportGeneration() throws Exception {
        support.disconnect();
        set("sapTransportClosed", false); // Simulate normal initialization of the next transport.
        final AtomicBoolean ran = new AtomicBoolean();
        invoke("enqueueSapApplication", new Class<?>[]{Runnable.class, long.class},
                (Runnable) () -> ran.set(true), 1L);
        drain();
        assertFalse(ran.get());
    }

    @Test
    public void disconnectedCalendarPayloadAckAndNakNeverQueue() throws Exception {
        installRoute(1L);
        final TransactionBuilder transaction = transaction();
        doReturn(false).when(support).isConnected();
        invoke("sendCalendarPayload", new Class<?>[]{String.class, String.class}, "{}", "synthetic");
        invoke("sendCalendarReliableAck", new Class<?>[]{int.class}, 1);
        invoke("sendCalendarReliableNak", new Class<?>[]{int.class}, 1);
        verify(transaction, never()).queue();
    }

    @Test
    public void staleCalendarGenerationNeverQueuesIntoNewConnection() throws Exception {
        installRoute(0L);
        final TransactionBuilder transaction = transaction();
        doReturn(true).when(support).isConnected();
        invoke("sendCalendarPayload", new Class<?>[]{String.class, String.class}, "{}", "synthetic");
        verify(transaction, never()).queue();
    }

    @Test
    public void currentConnectedCalendarPayloadStillQueues() throws Exception {
        installRoute(1L);
        final TransactionBuilder transaction = transaction();
        doReturn(true).when(support).isConnected();
        invoke("sendCalendarPayload", new Class<?>[]{String.class, String.class}, "{}", "synthetic");
        verify(transaction).queue();
    }

    @Test
    public void disconnectedContactsCallbackNeverCreatesTransaction() throws Exception {
        doReturn(false).when(support).isConnected();
        sendContacts(1L);
        verify(support, never()).createTransactionBuilder(anyString());
    }

    @Test
    public void oldContactsEmitterCannotQueueIntoReplacementTransport() throws Exception {
        support.disconnect();
        set("sapTransportClosed", false);
        doReturn(true).when(support).isConnected();
        sendContacts(1L);
        verify(support, never()).createTransactionBuilder(anyString());
    }

    @Test
    public void currentContactsEmitterStillQueues() throws Exception {
        doReturn(true).when(support).isConnected();
        final TransactionBuilder transaction = transaction();
        sendContacts(1L);
        verify(transaction).queue();
    }

    private void sendContacts(final long generation) throws Exception {
        invoke("sendContactsRecord", new Class<?>[]{SapPostAuthRecord.class, long.class},
                new SapPostAuthRecord(new SapTransportHeader(0, 0, 9, 0), new byte[]{1}), generation);
    }

    private TransactionBuilder transaction() {
        final TransactionBuilder transaction = mock(TransactionBuilder.class, RETURNS_SELF);
        doReturn(transaction).when(support).createTransactionBuilder(anyString());
        return transaction;
    }

    private void installRoute(final long generation) throws Exception {
        final Class<?> type = Class.forName(GearS3DeviceSupport.class.getName() + "$CalendarRoute");
        final Constructor<?> constructor = type.getDeclaredConstructor(int.class, long.class);
        constructor.setAccessible(true);
        set("calendarRoute", constructor.newInstance(77, generation));
        // Match onCalendarSessionEstablishedLocked(): reliable application sequences start at one.
        set("calendarReliableTxSequence", 1);
    }

    private void drain() throws Exception {
        ((ExecutorService) get("sapApplicationExecutor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private void invoke(final String name, final Class<?>[] types, final Object... arguments) throws Exception {
        final Method method = GearS3DeviceSupport.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(support, arguments);
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
