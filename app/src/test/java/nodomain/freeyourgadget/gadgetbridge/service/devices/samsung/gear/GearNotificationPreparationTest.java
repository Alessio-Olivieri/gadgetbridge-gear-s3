package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.Context;
import android.os.Looper;

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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the actual worker, main-Handler callback, SAP dispatch and fail/cleanup paths. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GearNotificationPreparationTest {
    private GearS3DeviceSupport support;
    private TransactionBuilder tx;
    private SapNotificationServiceAcceptor acceptor;
    private final CountDownLatch release = new CountDownLatch(1);

    @Before public void setup() throws Exception {
        support = spy(new GearS3DeviceSupport());
        final Context context = RuntimeEnvironment.getApplication();
        doReturn(context).when(support).getContext();
        doNothing().when(support).disconnect(); // No physical Bluetooth in this test.
        tx = mock(TransactionBuilder.class, RETURNS_SELF);
        doReturn(tx).when(support).createTransactionBuilder(anyString());
        acceptor = mock(SapNotificationServiceAcceptor.class);
        when(acceptor.getState()).thenReturn(SapNotificationServiceAcceptor.State.READY);
        when(acceptor.getApplicationSessionId()).thenReturn(77);
        when(acceptor.isApplicationSession(77)).thenReturn(true);
        set("notificationServiceAcceptor", acceptor);
        set("notificationIdentityStore", new SapNotificationIdentityStore(context.getSharedPreferences("reply-preparation", 0)));
        set("sapDispatchGeneration", 1L);
        set("notificationReliableTxSequence", 1);
        // Cached synthetic app icon avoids PackageManager I/O, not the preparation machinery.
        ((Map<String, byte[]>) get("applicationThumbnailCache")).put("test.package", new byte[]{1, 2, 3});
    }

    @After public void cleanup() {
        release.countDown();
        if (support != null) support.dispose();
    }

    private Object get(String name) throws Exception {
        final Field f = GearS3DeviceSupport.class.getDeclaredField(name);
        f.setAccessible(true); return f.get(support);
    }

    private void set(String name, Object value) throws Exception {
        final Field f = GearS3DeviceSupport.class.getDeclaredField(name);
        f.setAccessible(true); f.set(support, value);
    }

    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        final Method m = GearS3DeviceSupport.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        synchronized (support) { m.invoke(support, args); }
    }

    private void invoke(String name) throws Exception { invoke(name, new Class<?>[0]); }

    private ExecutorService worker() throws Exception { return (ExecutorService) get("notificationImageExecutor"); }

    private NotificationSpec spec(int id) {
        final NotificationSpec spec = new NotificationSpec(id);
        spec.setKey("same-window"); spec.setSourceAppId("test.package");
        spec.setSourceName("Test"); spec.setTitle("Synthetic"); spec.setBody("Synthetic");
        return spec;
    }

    private void drainWorker() throws Exception { worker().submit(() -> {}).get(3, TimeUnit.SECONDS); }
    private void drainCallback() { shadowOf(Looper.getMainLooper()).idle(); }

    private void fail() throws Exception {
        invoke("fail", new Class<?>[]{String.class, Exception.class}, "synthetic failure", new IllegalArgumentException("synthetic"));
    }

    /** Runs the exact worker recovery method used by initializeDevice, without unrelated pairing bootstrap. */
    private void reconnect() throws Exception {
        set("sapDispatchGeneration", (long) get("sapDispatchGeneration") + 1);
        invoke("clearNotificationServiceConnection");
        invoke("ensureNotificationPreparationExecutor");
    }

    @Test public void preparationBeforeAndAfterSapFailureReconnectCompletes() throws Exception {
        support.onNotification(spec(1)); drainWorker(); drainCallback();
        assertEquals(1, ((Map<?, ?>) get("notifications")).size());
        verify(tx, times(1)).write(any(byte[].class));
        final ExecutorService old = worker();
        fail(); assertTrue(old.isShutdown());
        reconnect(); assertNotSame(old, worker()); assertFalse(worker().isShutdown());
        support.onNotification(spec(2)); drainWorker(); drainCallback();
        assertEquals(1, ((Map<?, ?>) get("notifications")).size());
        verify(tx, times(2)).write(any(byte[].class));
    }

    @Test public void postedOldGenerationCallbackCannotConsumeNewWindowToken() throws Exception {
        support.onNotification(spec(1)); drainWorker(); // Callback remains queued on main Handler.
        fail(); reconnect();
        support.onNotification(spec(2)); drainWorker();
        drainCallback();
        assertEquals(1, ((Map<?, ?>) get("notifications")).size());
        assertTrue(((Map<?, ?>) get("notificationPreparationTokens")).isEmpty());
        verify(tx, times(1)).write(any(byte[].class));
    }

    @Test public void ordinaryGenerationChangeKeepsLiveExecutorButRejectsOldCallback() throws Exception {
        final ExecutorService old = worker();
        support.onNotification(spec(1)); drainWorker();
        reconnect(); assertSame(old, worker());
        support.onNotification(spec(2)); drainWorker(); drainCallback();
        verify(tx, times(1)).write(any(byte[].class));

    }

    @Test public void supersededUpdateCannotPublishOrConsumeCurrentToken() throws Exception {
        support.onNotification(spec(1)); support.onNotification(spec(2));
        drainWorker(); drainCallback();
        verify(tx, times(1)).write(any(byte[].class));

    }

    @Test public void queuedOldWorkerJobIsDroppedAfterGenerationChange() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        worker().execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        support.onNotification(spec(1)); reconnect();
        support.onNotification(spec(2)); release.countDown();
        drainWorker(); drainCallback();
        verify(tx, times(1)).write(any(byte[].class));
    }

    @Test public void rejectedSubmissionDoesNotLeavePendingToken() throws Exception {
        worker().shutdownNow();
        support.onNotification(spec(1));
        assertTrue(((Map<?, ?>) get("notificationPreparationTokens")).isEmpty());
        assertTrue(((Map<?, ?>) get("notificationPreparingIds")).isEmpty());
        verify(tx, never()).write(any(byte[].class));
    }

    @Test public void failedWorkerCleansUpAndNextJobStillCompletesWithoutLoggingPrivateMessage() throws Exception {
        final Map<String, byte[]> failingCache = mock(Map.class);
        when(failingCache.get("test.package")).thenThrow(new IllegalStateException("PRIVATE_MEDIA_URI"))
                .thenReturn(new byte[]{1});
        set("applicationThumbnailCache", failingCache);
        support.onNotification(spec(1)); drainWorker(); drainCallback();

        assertTrue(((Map<?, ?>) get("notificationPreparationTokens")).isEmpty());

        final IllegalStateException privateError = new IllegalStateException("PRIVATE_MEDIA_URI");
        final Method redact = GearS3DeviceSupport.class.getDeclaredMethod("redactedDiagnosticException", Throwable.class);
        redact.setAccessible(true);
        final Throwable safeError = (Throwable) redact.invoke(null, privateError);
        assertFalse(safeError.getMessage().contains("PRIVATE_MEDIA_URI"));
        assertTrue(safeError.getMessage().contains(IllegalStateException.class.getName()));
        assertArrayEquals(privateError.getStackTrace(), safeError.getStackTrace());
        support.onNotification(spec(2)); drainWorker(); drainCallback();
        verify(tx, times(1)).write(any(byte[].class));
    }

    @Test public void disposalStopsWorkerAndCannotResurrectIt() throws Exception {
        final ExecutorService old = worker();
        support.dispose(); invoke("ensureNotificationPreparationExecutor");
        assertSame(old, worker()); assertTrue(old.isShutdown());
    }

    @Test public void malformedNotificationApplicationInputFailsLocallyAndWorkerStillPublishes() throws Exception {
        // Unknown application protocol inside a valid reliable API104 record.
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0, 77, 0),
                new byte[]{0, 1, 0, 0x70, 0, 0, 0});
        invoke("dispatchPostAuthRecord", new Class<?>[]{SapPostAuthRecord.class, long.class, long.class}, record, 0L, 1L);
        ((ExecutorService) get("sapApplicationExecutor")).submit(() -> {}).get(3, TimeUnit.SECONDS);
        assertEquals(1L, get("sapDispatchGeneration")); assertFalse(worker().isShutdown());
        support.onNotification(spec(1)); drainWorker(); drainCallback();
        assertEquals(1, ((Map<?, ?>) get("notifications")).size());
    }

    @Test public void malformedControlInputIdentifiesParserAndCanRecoverPreparationOnReconnect() throws Exception {
        final SapPostAuthRecord record = new SapPostAuthRecord(new SapTransportHeader(0, 0, 1023, 0), new byte[]{0x55});
        invoke("dispatchPostAuthRecord", new Class<?>[]{SapPostAuthRecord.class, long.class, long.class}, record, 0L, 1L);
        ((ExecutorService) get("sapApplicationExecutor")).submit(() -> {}).get(3, TimeUnit.SECONDS);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SapServiceConnectionCodec.parse(record.getBody()));
        assertEquals("Unknown SAP service-control message type 85", error.getMessage());
        assertEquals(SapServiceConnectionCodec.class.getName(), error.getStackTrace()[0].getClassName());
        assertEquals(2L, get("sapDispatchGeneration"));
        verify(support).disconnect();
        assertTrue(worker().isShutdown());
        reconnect(); support.onNotification(spec(1)); drainWorker(); drainCallback();
        assertEquals(1, ((Map<?, ?>) get("notifications")).size());
    }
}
