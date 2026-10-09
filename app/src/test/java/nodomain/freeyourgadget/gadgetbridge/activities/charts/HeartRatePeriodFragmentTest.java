package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineDataSet;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HeartRatePeriodFragmentTest extends TestBase {
    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");

    private static Date localNoon(final int year, final int month, final int day) {
        final Calendar calendar = Calendar.getInstance(ROME);
        calendar.clear();
        calendar.set(year, month, day, 12, 0, 0);
        return calendar.getTime();
    }

    private static ActivitySample sample(final int timestamp, final int heartRate, final int steps) {
        final ActivitySample sample = mock(ActivitySample.class);
        when(sample.getTimestamp()).thenReturn(timestamp);
        when(sample.getHeartRate()).thenReturn(heartRate);
        when(sample.getSteps()).thenReturn(steps);
        return sample;
    }

    @Test
    public void localDayBoundsUseTheSelectedDateAndRespectDaylightSaving() {
        final Pair<Integer, Integer> spring = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.MARCH, 29), 1, ROME);
        final Pair<Integer, Integer> autumn = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.OCTOBER, 25), 1, ROME);
        final Pair<Integer, Integer> springWeek = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.MARCH, 29), 7, ROME);
        final Pair<Integer, Integer> previous = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.MARCH, 28), 1, ROME);

        assertEquals(23 * 3600, spring.getRight() - spring.getLeft() + 1);
        assertEquals(25 * 3600, autumn.getRight() - autumn.getLeft() + 1);
        assertEquals(7 * 24 * 3600 - 3600, springWeek.getRight() - springWeek.getLeft() + 1);
        assertEquals(spring.getLeft().intValue(), previous.getRight() + 1);
    }

    @Test
    public void oneHeartRateWithoutStepsIsPlottedAtLocalTimeWithAVisibleMarker() {
        final int dayStart = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.SEPTEMBER, 23), 1, ROME).getLeft();
        final ActivitySample stepsOnly = sample(dayStart + 1800, ActivitySample.NOT_MEASURED, 12);
        final ActivitySample heartRateOnly = sample(dayStart + 12 * 3600, 73, ActivitySample.NOT_MEASURED);

        final List<List<Entry>> segments = HeartRatePeriodFragment.oneDaySegments(
                Arrays.asList(stepsOnly, heartRateOnly), dayStart, HeartRateUtils.getInstance());
        assertEquals(1, segments.size());
        assertEquals(1, segments.get(0).size());
        assertEquals(12 * 3600f, segments.get(0).get(0).getX(), 0f);
        assertEquals(73f, segments.get(0).get(0).getY(), 0f);

        final HeartRatePeriodFragment.HeartRateData summary = HeartRatePeriodFragment.summarizeDay(
                Arrays.asList(stepsOnly, heartRateOnly), -1, HeartRateUtils.getInstance());
        assertEquals(73, summary.average);
        assertEquals(73, summary.minimum);
        assertEquals(73, summary.maximum);

        final LineDataSet dataSet = new HeartRatePeriodFragment()
                .createHeartRateDataSet(segments.get(0), 0xff123456);
        assertTrue(dataSet.isDrawCirclesEnabled());
        assertEquals(1, dataSet.getEntryCount());
    }

    @Test
    public void separatedSpotReadingsStaySeparateWithoutFabricatedPoints() {
        final int dayStart = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.SEPTEMBER, 23), 1, ROME).getLeft();
        final List<List<Entry>> segments = HeartRatePeriodFragment.oneDaySegments(
                Arrays.asList(sample(dayStart + 3600, 72, ActivitySample.NOT_MEASURED),
                        sample(dayStart + 7200, 77, ActivitySample.NOT_MEASURED)),
                dayStart, HeartRateUtils.getInstance());

        assertEquals(2, segments.size());
        assertEquals(1, segments.get(0).size());
        assertEquals(1, segments.get(1).size());
    }

    @Test
    public void stepOnlySampleBetweenHeartRateReadingsDoesNotSplitTheirLine() {
        final int dayStart = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.SEPTEMBER, 23), 1, ROME).getLeft();
        final List<List<Entry>> segments = HeartRatePeriodFragment.oneDaySegments(
                Arrays.asList(sample(dayStart + 3600, 72, ActivitySample.NOT_MEASURED),
                        sample(dayStart + 3630, ActivitySample.NOT_MEASURED, 20),
                        sample(dayStart + 3660, 77, ActivitySample.NOT_MEASURED)),
                dayStart, HeartRateUtils.getInstance());

        assertEquals(1, segments.size());
        assertEquals(2, segments.get(0).size());
    }

    @Test
    public void deviceSpecificHeartRateGapControlsLineSegmentation() {
        final int dayStart = HeartRatePeriodFragment.getLocalDayRange(
                localNoon(2026, Calendar.SEPTEMBER, 23), 1, ROME).getLeft();
        final List<ActivitySample> samples = Arrays.asList(
                sample(dayStart + 3600, 72, ActivitySample.NOT_MEASURED),
                sample(dayStart + 3780, 77, ActivitySample.NOT_MEASURED));

        assertEquals(2, HeartRatePeriodFragment.oneDaySegments(
                samples, dayStart, HeartRateUtils.getInstance(), 1).size());
        assertEquals(1, HeartRatePeriodFragment.oneDaySegments(
                samples, dayStart, HeartRateUtils.getInstance(), 10).size());
    }
}
