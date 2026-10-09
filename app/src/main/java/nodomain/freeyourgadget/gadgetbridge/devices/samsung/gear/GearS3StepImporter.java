package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;

/** Converts validated Samsung step intervals into persistent Gadgetbridge activity samples. */
public final class GearS3StepImporter {
    private GearS3StepImporter() {
    }

    public static int toActivityTimestampSeconds(final long startTimeMs) {
        return (int) (startTimeMs / 1000L);
    }

    @NonNull
    public static List<GearS3ActivitySample> toSamples(
            @NonNull final List<SapPedometerJsonProbe.PedometerStepRecord> records) {
        if (records.isEmpty()) return Collections.emptyList();
        final List<GearS3ActivitySample> samples = new ArrayList<>(records.size());
        for (final SapPedometerJsonProbe.PedometerStepRecord record : records) {
            final GearS3ActivitySample sample = new GearS3ActivitySample();
            sample.setDatauuid(record.getDatauuid());
            sample.setTimestamp(toActivityTimestampSeconds(record.getStartTimeMs()));
            sample.setStartTimeMs(record.getStartTimeMs());
            sample.setEndTimeMs(record.getEndTimeMs());
            sample.setCount(record.getCount());
            sample.setWalkStep(record.getWalkStep());
            sample.setRunStep(record.getRunStep());
            sample.setDistance(record.getDistance());
            sample.setCalorie(record.getCalorie());
            sample.setDuration(record.getDuration());
            sample.setSpeed(record.getSpeed());
            sample.setSteps(record.getCount());
            sample.setDistanceCm(Math.round(record.getDistance() * 100.0f));
            sample.setActiveCalories(Math.round(record.getCalorie()));
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            sample.setRawIntensity(ActivitySample.NOT_MEASURED);
            samples.add(sample);
        }
        return samples;
    }

    public static boolean persist(@NonNull final GBDevice device,
                                  @NonNull final List<SapPedometerJsonProbe.PedometerStepRecord> records,
                                  @Nullable final Context context) {
        if (records.isEmpty()) return true;
        try (DBHandler db = GBApplication.acquireDB()) {
            final GearS3ActivitySampleProvider provider =
                    new GearS3ActivitySampleProvider(device, db.getDaoSession());
            return provider.persistSamples(toSamples(records), context);
        } catch (final Exception e) {
            return false;
        }
    }
}
