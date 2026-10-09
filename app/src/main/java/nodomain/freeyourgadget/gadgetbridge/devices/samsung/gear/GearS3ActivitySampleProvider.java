package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/** Persistent activity provider for Samsung Gear S3 CommonSync step intervals. */
public class GearS3ActivitySampleProvider extends AbstractSampleProvider<GearS3ActivitySample> {
    private static final Logger LOG = LoggerFactory.getLogger(GearS3ActivitySampleProvider.class);

    public GearS3ActivitySampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<GearS3ActivitySample, ?> getSampleDao() {
        return getSession().getGearS3ActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return GearS3ActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return GearS3ActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return GearS3ActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(final int rawType) {
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(final ActivityKind activityKind) {
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(final int rawIntensity) {
        return rawIntensity == ActivitySample.NOT_MEASURED ? 0.0f : rawIntensity / 255.0f;
    }

    @Override
    public GearS3ActivitySample createActivitySample() {
        return new GearS3ActivitySample();
    }

    @NonNull
    @Override
    protected List<GearS3ActivitySample> getGBActivitySamples(final int timestampFrom,
                                                               final int timestampTo) {
        final List<GearS3ActivitySample> steps = super.getGBActivitySamples(timestampFrom, timestampTo);
        final GearS3HeartRateSampleProvider heartRateProvider =
                new GearS3HeartRateSampleProvider(getDevice(), getSession());
        final List<GearS3HeartRateSample> heartRates = heartRateProvider.getAllSamples(
                timestampFrom * 1000L, timestampTo * 1000L + 999L);
        final List<GearS3ActivitySample> combined = mergeForCharts(steps, heartRates);
        if (!heartRates.isEmpty()) {
            LOG.debug("Gear S3 heart-rate chart overlay: fromTs={} toTs={} stepSamples={} heartRateRecords={} chartSamples={}",
                    timestampFrom, timestampTo, steps.size(), heartRates.size(), combined.size());
        }
        return combined;
    }

    /** Merge only detached chart samples; step rows in the database stay unchanged. */
    @NonNull
    List<GearS3ActivitySample> mergeForCharts(
            @NonNull final List<GearS3ActivitySample> steps,
            @NonNull final List<GearS3HeartRateSample> heartRates) {
        final Map<Integer, GearS3ActivitySample> bySecond = new TreeMap<>();
        for (final GearS3ActivitySample step : steps) bySecond.put(step.getTimestamp(), step);
        final List<GearS3HeartRateSample> sorted = new ArrayList<>(heartRates);
        sorted.sort(Comparator.comparingLong(GearS3HeartRateSample::getTimestamp)
                .thenComparing(GearS3HeartRateSample::getDatauuid));
        for (final GearS3HeartRateSample heartRate : sorted) {
            final int second = (int) (heartRate.getTimestamp() / 1000L);
            GearS3ActivitySample sample = bySecond.get(second);
            if (!(sample instanceof ChartSample)) {
                sample = sample == null ? new ChartSample() : new ChartSample(sample);
                sample.setTimestamp(second);
                bySecond.put(second, sample);
            }
            sample.setHeartRate(heartRate.getHeartRate());
        }
        final List<GearS3ActivitySample> combined = new ArrayList<>(bySecond.values());
        for (final GearS3ActivitySample sample : combined) sample.setProvider(this);
        return combined;
    }

    /** The generated step entity has no heart-rate field; this copy exists only for chart reads. */
    private static final class ChartSample extends GearS3ActivitySample {
        private int heartRate = ActivitySample.NOT_MEASURED;

        private ChartSample() {
            setRawKind(ActivityKind.UNKNOWN.getCode());
            setRawIntensity(ActivitySample.NOT_MEASURED);
            setSteps(ActivitySample.NOT_MEASURED);
            setDistanceCm(ActivitySample.NOT_MEASURED);
            setActiveCalories(ActivitySample.NOT_MEASURED);
        }

        private ChartSample(final GearS3ActivitySample step) {
            super(step.getTimestamp(), step.getDeviceId(), step.getUserId(), step.getDatauuid(),
                    step.getSteps(), step.getRawKind(), step.getRawIntensity(), step.getDistanceCm(),
                    step.getActiveCalories(), step.getStartTimeMs(), step.getEndTimeMs(), step.getCount(),
                    step.getWalkStep(), step.getRunStep(), step.getDistance(), step.getCalorie(),
                    step.getDuration(), step.getSpeed());
        }

        @Override
        public int getHeartRate() {
            return heartRate;
        }

        @Override
        public void setHeartRate(final int heartRate) {
            this.heartRate = heartRate;
        }
    }
}
