package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearS3DeviceSupport;

/** Manual shell-only empty-watch recovery, compiled and registered in debug builds only. */
public final class SapCalendarEmptyMirrorBootstrapReceiver extends BroadcastReceiver {
    private static final String PREFIX = "nodomain.freeyourgadget.gadgetbridge.debug.";
    private static final String INSPECT = PREFIX + "INSPECT_GEAR_S3_EMPTY_CALENDAR_MIRROR";
    private static final String BOOTSTRAP = PREFIX + "BOOTSTRAP_GEAR_S3_EMPTY_CALENDAR_MIRROR";
    private static final String TAG = "GearS3CalendarEmptyBootstrap";
    private static final Map<String, Challenge> CHALLENGES = new ConcurrentHashMap<>();

    private static final class Challenge {
        final String token = UUID.randomUUID().toString();
        final long created = SystemClock.elapsedRealtime();
        final SapCalendarBootstrap.Plan plan;
        Challenge(final SapCalendarBootstrap.Plan plan) { this.plan = plan; }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !(INSPECT.equals(intent.getAction()) || BOOTSTRAP.equals(intent.getAction()))) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try { perform(intent); }
            finally { pending.finish(); }
        }, "GearS3CalendarEmptyBootstrap").start();
    }

    private static void perform(final Intent intent) {
        try {
            final String address = intent.getStringExtra("device");
            if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
                throw new SapCalendarBootstrap.Refusal("invalid-device");
            }
            final String key = address.toUpperCase(java.util.Locale.ROOT);
            final GearS3DeviceSupport support = GearS3DeviceSupport.calendarDebugSupport(key);
            if (support == null) throw new SapCalendarBootstrap.Refusal("support-unavailable");
            if (INSPECT.equals(intent.getAction())) {
                CHALLENGES.remove(key);
                final Challenge challenge = new Challenge(support.inspectEmptyCalendarDebug());
                CHALLENGES.put(key, challenge);
                Log.i(TAG, "EMPTY_INSPECT challenge=" + challenge.token + " validForSeconds=300 SAP_TX=0");
                return;
            }
            final Challenge challenge = CHALLENGES.remove(key);
            if (challenge == null || SystemClock.elapsedRealtime() - challenge.created > 300_000L) {
                throw new SapCalendarBootstrap.Refusal("challenge-missing-or-expired");
            }
            if (!challenge.token.equals(intent.getStringExtra("challenge"))) {
                throw new SapCalendarBootstrap.Refusal("challenge-mismatch");
            }
            support.bootstrapEmptyCalendarDebug(intent.getStringExtra("watch_database_sha256"),
                    intent.getIntExtra("watch_count", -1), intent.getLongExtra("watch_sequence", -1),
                    intent.getLongExtra("verified_at_ms", -1), challenge.plan.ranges, challenge.plan.phoneZone);
            Log.i(TAG, "EMPTY_BOOTSTRAP trusted=true count=0 sequence=0 SAP_TX=0");
        } catch (final SapCalendarBootstrap.Refusal e) {
            Log.e(TAG, e.diagnosticLine());
        } catch (final Exception e) {
            Log.e(TAG, "REFUSED diagnostic-failed type=" + e.getClass().getSimpleName());
        }
    }
}
