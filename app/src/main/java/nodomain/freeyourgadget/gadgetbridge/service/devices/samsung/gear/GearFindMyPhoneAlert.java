package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.FindPhoneSession;

/** Android adapter; playback stays in the existing FindPhoneActivity. */
final class GearFindMyPhoneAlert implements GearFindMyPhoneService.Alert {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GearFindMyPhoneAlert.class);
    private final Context context;
    private final GBDevice device;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String token;
    GearFindMyPhoneAlert(final Context context, final GBDevice device) {
        this.context = context.getApplicationContext();
        this.device = device;
    }
    @Override public synchronized boolean start(final Runnable accepted, final Runnable started, final Consumer<Boolean> finished) {
        token = FindPhoneSession.INSTANCE.begin(new FindPhoneSession.Listener() {
            @Override public void started() { main.post(started); }
            @Override public void finished(final boolean failed) { main.post(() -> finished.accept(failed)); }
        });
        if (token == null) {
            LOG.warn("Gear S3 FMP alert dispatch rejected: another Find Phone operation owns playback");
            return false;
        }
        final String requested = token;
        final boolean queued = main.post(() -> {
            if (!FindPhoneSession.INSTANCE.current(requested)) {
                LOG.info("Gear S3 FMP alert dispatch canceled before event delivery");
                return;
            }
            try {
                final GBDeviceEventFindPhone event = new GBDeviceEventFindPhone();
                event.event = GBDeviceEventFindPhone.Event.START;
                event.sessionToken = requested;
                LOG.info("Gear S3 FMP alert event dispatch: START");
                event.evaluate(context, device);
                if (FindPhoneSession.INSTANCE.current(requested)) accepted.run();
            } catch (final RuntimeException e) {
                LOG.warn("Gear S3 FMP alert event/launch failed: exception={}", e.getClass().getSimpleName(), e);
                FindPhoneSession.INSTANCE.finish(requested, true);
            }
        });
        LOG.info("Gear S3 FMP alert dispatch queued={}", queued);
        if (!queued) {
            FindPhoneSession.INSTANCE.cancel(requested);
            token = null;
        }
        return queued;
    }
    @Override public synchronized void stop() {
        final String canceled = token;
        token = null;
        if (canceled == null) return;
        FindPhoneSession.INSTANCE.cancel(canceled);
        main.post(() -> {
            GBDeviceEventFindPhone.cancelSessionNotification(context, canceled);
        });
    }
}
