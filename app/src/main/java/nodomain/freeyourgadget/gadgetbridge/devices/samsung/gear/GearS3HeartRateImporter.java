package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;

/** Converts validated CommonSync heart-rate records into source-keyed history. */
public final class GearS3HeartRateImporter {
    private GearS3HeartRateImporter() {
    }

    @NonNull
    public static List<GearS3HeartRateSample> toSamples(
            @NonNull final List<SapPedometerJsonProbe.HeartRateRecord> records) {
        final List<GearS3HeartRateSample> samples = new ArrayList<>(records.size());
        for (final SapPedometerJsonProbe.HeartRateRecord record : records) {
            final GearS3HeartRateSample sample = new GearS3HeartRateSample();
            sample.setDatauuid(record.getDatauuid());
            sample.setTimestamp(record.getStartTimeMs());
            sample.setEndTimeMs(record.getEndTimeMs());
            sample.setHeartRate(record.getHeartRate());
            samples.add(sample);
        }
        return samples;
    }

    public static boolean persist(@NonNull final GBDevice device,
                                  @NonNull final List<SapPedometerJsonProbe.HeartRateRecord> records,
                                  @Nullable final Context context) {
        if (records.isEmpty()) return true;
        try (DBHandler db = GBApplication.acquireDB()) {
            final GearS3HeartRateSampleProvider provider =
                    new GearS3HeartRateSampleProvider(device, db.getDaoSession());
            return provider.persistSamples(toSamples(records), context);
        } catch (final Exception e) {
            return false;
        }
    }
}
