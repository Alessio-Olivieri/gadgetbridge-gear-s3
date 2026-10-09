package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear.GearS3Coordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/** Shell-only debug import of Gear calendar windows into durable storage. */
public final class SapCalendarRangeImportReceiver extends BroadcastReceiver {
    private static final String TAG = "GearS3CalendarImport";
    private static final String ACTION =
            "nodomain.freeyourgadget.gadgetbridge.debug.IMPORT_GEAR_S3_CALENDAR_RANGES";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        final String address = intent.getStringExtra("device");
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            Log.e(TAG, "Rejected captured-range import: invalid Gear address");
            return;
        }
        final GBDevice device = GBApplication.app().getDeviceManager().getDeviceByAddress(address);
        if (device == null || !(device.getDeviceCoordinator() instanceof GearS3Coordinator)) {
            Log.e(TAG, "Rejected captured-range import: address is not a paired Gear S3");
            return;
        }
        if (!intent.hasExtra("zone") || !intent.hasExtra("maxEvents")
                || !intent.hasExtra("maxAllDay") || !intent.hasExtra("maxText")
                || !intent.hasExtra("reminders") || !intent.hasExtra("windows")) {
            Log.e(TAG, "Rejected captured-range import: missing explicit request options");
            return;
        }
        try {
            final String zone = intent.getStringExtra("zone");
            final String raw = intent.getStringExtra("windows");
            if (zone == null || raw == null) throw new IllegalArgumentException("Missing window or zone");
            final String[] entries = raw.split(",", -1);
            if (entries.length != 7) throw new IllegalArgumentException("Expected exactly seven captured windows");
            final List<SapCalendarCodec.RequestRange> ranges = new ArrayList<>();
            final List<String> zones = new ArrayList<>();
            long previousEnd = -1;
            for (final String entry : entries) {
                final String[] bounds = entry.split(":", -1);
                if (bounds.length != 2) throw new IllegalArgumentException("Invalid window pair");
                final long start = Long.parseLong(bounds[0]);
                final long end = Long.parseLong(bounds[1]);
                if (start <= previousEnd) throw new IllegalArgumentException("Windows overlap or are unordered");
                final JSONObject request = new JSONObject()
                        .put("startTime", start).put("endTime", end)
                        .put("maxEventCnt", intent.getIntExtra("maxEvents", -1))
                        .put("maxAlldayEventCnt", intent.getIntExtra("maxAllDay", -1))
                        .put("maxTextLength", intent.getIntExtra("maxText", -1))
                        .put("reminder", intent.getBooleanExtra("reminders", false) ? "on" : "off");
                ranges.add(SapCalendarCodec.parseRange(request));
                zones.add(zone);
                previousEnd = end;
            }
            final SapCalendarRangeStore store = new SapCalendarRangeStore(
                    GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()));
            if (!store.importObservedRanges(ranges, zones)) {
                Log.e(TAG, "Rejected captured-range import: existing windows/generation or invalid storage input");
                return;
            }
            Log.i(TAG, "Captured Gear calendar ranges imported: count=7 device=" + device.getAddress()
                    + " firstStart=" + ranges.get(0).start + " lastEnd=" + ranges.get(6).end);
        } catch (final JSONException | IllegalArgumentException e) {
            Log.e(TAG, "Rejected captured-range import: " + e.getMessage());
        }
    }
}
