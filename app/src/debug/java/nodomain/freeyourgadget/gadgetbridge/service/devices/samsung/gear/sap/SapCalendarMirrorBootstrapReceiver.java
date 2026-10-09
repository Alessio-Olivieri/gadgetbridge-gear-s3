package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3Coordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearS3DeviceSupport;

/** Explicit shell-only, one-time mirror bootstrap. This receiver has no SAP sender. */
public final class SapCalendarMirrorBootstrapReceiver extends BroadcastReceiver {
    private static final String TAG = "GearS3CalendarBootstrap";
    private static final String INSPECT =
            "nodomain.freeyourgadget.gadgetbridge.debug.INSPECT_GEAR_S3_CALENDAR_MIRROR";
    private static final String BOOTSTRAP =
            "nodomain.freeyourgadget.gadgetbridge.debug.BOOTSTRAP_GEAR_S3_CALENDAR_MIRROR";
    private static final long CHALLENGE_MILLIS = 5 * 60_000L;
    // Bound for the explicit debug recovery operation; not a production sync default.
    private static final int VERIFIED_RANGE_COUNT = 7;
    private static final Map<String, Challenge> CHALLENGES = new ConcurrentHashMap<>();

    static String refusalLine(final SapCalendarBootstrap.Refusal refusal) {
        return refusal.diagnosticLine();
    }

    private static final class Challenge {
        final String fingerprint, ranges, zone;
        final int count;
        final long created;

        Challenge(final SapCalendarBootstrap.Plan plan) {
            fingerprint = plan.fingerprint;
            ranges = plan.ranges;
            zone = plan.phoneZone;
            count = plan.count();
            created = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !(INSPECT.equals(intent.getAction()) || BOOTSTRAP.equals(intent.getAction()))) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try { perform(intent); }
            finally { pending.finish(); }
        }, "GearS3CalendarDebugBootstrap").start();
    }

    private static void perform(final Intent intent) {
        final String address = intent.getStringExtra("device");
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            Log.e(TAG, "REFUSED invalid Gear address");
            return;
        }
        final GBDevice device = GBApplication.app().getDeviceManager().getDeviceByAddress(address);
        if (device == null || !(device.getDeviceCoordinator() instanceof GearS3Coordinator)) {
            Log.e(TAG, "REFUSED device is not a paired Gear S3");
            return;
        }
        final GearS3DeviceSupport support = GearS3DeviceSupport.calendarDebugSupport(device.getAddress());
        if (support == null) {
            Log.e(TAG, "REFUSED no active Gear S3 support instance; start Gadgetbridge connection first");
            return;
        }
        try {
            if (INSPECT.equals(intent.getAction())) {
                CHALLENGES.remove(device.getAddress());
                final SapCalendarBootstrap.Plan plan = support.inspectCalendarDebug(VERIFIED_RANGE_COUNT);
                CHALLENGES.put(device.getAddress(), new Challenge(plan));
                Log.i(TAG, "Calendar mirror INSPECT count=" + plan.count() + " fingerprint=" + plan.fingerprint
                        + " validForSeconds=300");
                return;
            }
            final Challenge challenge = CHALLENGES.remove(device.getAddress());
            final String expected = intent.getStringExtra("expected_fingerprint");
            final int count = intent.getIntExtra("expected_count", -1);
            if (challenge == null || SystemClock.elapsedRealtime() - challenge.created > CHALLENGE_MILLIS) {
                Log.e(TAG, "REFUSED challenge-missing-or-expired");
                return;
            }
            if (expected == null || !expected.matches("[0-9a-f]{64}")
                    || !expected.equals(challenge.fingerprint)) {
                Log.e(TAG, "REFUSED fingerprint-mismatch");
                return;
            }
            if (count != challenge.count) {
                Log.e(TAG, "REFUSED count-mismatch");
                return;
            }
            if (support.bootstrapCalendarDebug(expected, count, challenge.ranges, challenge.zone, VERIFIED_RANGE_COUNT)) {
                Log.i(TAG, "Calendar mirror BOOTSTRAP trusted=true count=" + count
                        + " fingerprint=" + expected + " SAP_TX=0");
            }
        } catch (final SapCalendarBootstrap.Refusal e) {
            Log.e(TAG, refusalLine(e));
        } catch (final Exception e) {
            // Never include exception text: provider errors could include private Calendar content.
            Log.e(TAG, "REFUSED Calendar diagnostic/bootstrap failed: " + e.getClass().getSimpleName());
        }
    }
}
