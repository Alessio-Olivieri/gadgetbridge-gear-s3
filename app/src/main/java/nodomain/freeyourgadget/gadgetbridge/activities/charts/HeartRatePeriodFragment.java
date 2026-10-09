package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_BPM;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileGridUtilKt;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.TimeWeightedAverageAccumulator;

public class HeartRatePeriodFragment extends AbstractChartFragment<HeartRatePeriodFragment.HeartRatePeriodData> {

    protected static final Logger LOG = LoggerFactory.getLogger(HeartRatePeriodFragment.class);

    static int DATA_INVALID = -1;

    protected int HEARTRATE_COLOR;
    protected int HEARTRATE_MIN_COLOR;
    protected int HEARTRATE_RESTING_COLOR;
    protected int HEARTRATE_MAX_COLOR;
    protected int CHART_TEXT_COLOR;
    protected int BACKGROUND_COLOR;
    protected int DESCRIPTION_COLOR;
    protected int LEGEND_TEXT_COLOR;
    protected int TEXT_COLOR;

    private TextView mDateView;
    private LinearLayout hrStatsContainer;
    private LineChart hrLineChart;
    private int TOTAL_DAYS;

    @Override
    protected boolean isSingleDay() {
        return false;
    }

    public static HeartRatePeriodFragment newInstance(int totalDays) {
        HeartRatePeriodFragment fragmentFirst = new HeartRatePeriodFragment();
        Bundle args = new Bundle();
        args.putInt("totalDays", totalDays);
        fragmentFirst.setArguments(args);
        return fragmentFirst;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TOTAL_DAYS = getArguments() != null ? getArguments().getInt("totalDays") : 0;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_heart_rate, container, false);

        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> getChartsHost().enableSwipeRefresh(scrollY == 0));

        mDateView = rootView.findViewById(R.id.hr_date_view);
        hrLineChart = rootView.findViewById(R.id.heart_rate_line_chart);
        hrStatsContainer = rootView.findViewById(R.id.hr_stats_container);

        setupChart();
        refresh();
        setupLegend(hrLineChart);

        return rootView;
    }

    public boolean supportsHeartRateRestingMeasurement() {
        final GBDevice device = getChartsHost().getDevice();
        return device.getDeviceCoordinator().supportsHeartRateRestingMeasurement(device);
    }

    protected List<? extends AbstractActivitySample> getActivitySamples(DBHandler db, GBDevice device, int tsFrom, int tsTo) {
        SampleProvider<? extends ActivitySample> provider = device.getDeviceCoordinator().getSampleProvider(device, db.getDaoSession());
        return provider.getAllActivitySamplesHighRes(tsFrom, tsTo);
    }

    @Override
    public String getTitle() {
        return getString(R.string.heart_rate);
    }

    @Override
    protected void init() {
        Prefs prefs = GBApplication.getPrefs();
        CHART_TEXT_COLOR = GBApplication.getSecondaryTextColor(requireContext());
        DESCRIPTION_COLOR = LEGEND_TEXT_COLOR = TEXT_COLOR = GBApplication.getTextColor(requireContext());
        if (prefs.getBoolean("chart_heartrate_color", false)) {
            HEARTRATE_COLOR = ContextCompat.getColor(requireContext(), R.color.chart_heartrate_alternative);
        } else {
            HEARTRATE_COLOR = ContextCompat.getColor(requireContext(), R.color.chart_heartrate);
        }
        HEARTRATE_MIN_COLOR = ContextCompat.getColor(requireContext(), R.color.chart_heartrate_minimum);
        HEARTRATE_MAX_COLOR = ContextCompat.getColor(requireContext(), R.color.chart_heartrate_maximum);
        HEARTRATE_RESTING_COLOR = ContextCompat.getColor(requireContext(), R.color.chart_heartrate_resting);
    }

    private HeartRateData fetchHeartRateDataForDay(DBHandler db, GBDevice device, int startTs, int endTs) {
        List<? extends ActivitySample> samples = getActivitySamples(db, device, startTs, endTs);

        int restingHeartRate = DATA_INVALID;
        if (supportsHeartRateRestingMeasurement()) {
            restingHeartRate = device.getDeviceCoordinator()
                    .getHeartRateRestingSampleProvider(device, db.getDaoSession())
                    .getAllSamples(startTs * 1000L, endTs * 1000L)
                    .stream()
                    .max(Comparator.comparingLong(HeartRateSample::getTimestamp))
                    .map(HeartRateSample::getHeartRate)
                    .orElse(DATA_INVALID);
        }

        return summarizeDay(samples, restingHeartRate, HeartRateUtils.getInstance(),
                device.getDeviceCoordinator().getMaxHeartRateMeasurementsGapMinutes(device));
    }

    static HeartRateData summarizeDay(final List<? extends ActivitySample> samples,
                                      final int restingHeartRate,
                                      final HeartRateUtils heartRateUtils) {
        return summarizeDay(samples, restingHeartRate, heartRateUtils,
                HeartRateUtils.MAX_HR_MEASUREMENTS_GAP_MINUTES);
    }

    static HeartRateData summarizeDay(final List<? extends ActivitySample> samples,
                                      final int restingHeartRate,
                                      final HeartRateUtils heartRateUtils,
                                      final int maxHRGapMinutes) {
        final TimeWeightedAverageAccumulator accumulator = new TimeWeightedAverageAccumulator(60 * maxHRGapMinutes, 60);
        int validSamples = 0;
        for (final ActivitySample sample : samples) {
            if (heartRateUtils.isValidHeartRateValue(sample.getHeartRate())) {
                accumulator.add(sample.getTimestamp(), sample.getHeartRate());
                validSamples++;
            }
        }

        // The accumulator's count tracks intervals after the first reading, not readings.
        final int average = validSamples > 0 ? (int) Math.round(accumulator.getAverage()) : DATA_INVALID;
        final int minimum = validSamples > 0 ? (int) Math.round(accumulator.getMin()) : DATA_INVALID;
        final int maximum = validSamples > 0 ? (int) Math.round(accumulator.getMax()) : DATA_INVALID;

        return new HeartRateData(samples, restingHeartRate, average, minimum, maximum);
    }

    @Override
    protected HeartRatePeriodData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        Pair<Integer, Integer> startAndEndTs = getStartAndEndTS();
        final int startTs = startAndEndTs.getKey();

        List<HeartRateData> result = new ArrayList<>();
        final Calendar day = Calendar.getInstance();
        day.setTimeInMillis(startTs * 1000L);
        for (int i = 0; i < TOTAL_DAYS; i++) {
            final int dayStartTs = (int) (day.getTimeInMillis() / 1000L);
            day.add(Calendar.DATE, 1);
            final int dayEndTs = (int) (day.getTimeInMillis() / 1000L) - 1;
            HeartRateData dayData = fetchHeartRateDataForDay(db, device, dayStartTs, dayEndTs);
            result.add(dayData);
        }
        return new HeartRatePeriodData(result);
    }

    @Override
    protected void renderCharts() {
        hrLineChart.invalidate();
    }

    private void setupChart() {
        hrLineChart.setBackgroundColor(BACKGROUND_COLOR);
        hrLineChart.getDescription().setTextColor(DESCRIPTION_COLOR);
        hrLineChart.getDescription().setEnabled(false);

        XAxis x = hrLineChart.getXAxis();
        x.setDrawLabelsEnabled(true);
        x.setDrawGridLinesEnabled(false);
        x.setEnabled(true);
        x.setTextColor(CHART_TEXT_COLOR);
        x.setDrawLimitLinesBehindDataEnabled(true);
        x.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis yAxisLeft = hrLineChart.getAxisLeft();
        yAxisLeft.setEnabled(true);
        YAxis yAxisRight = hrLineChart.getAxisRight();
        yAxisRight.setDrawLabelsEnabled(true);

        YAxis[] yAxisArr = {yAxisLeft, yAxisRight};
        for (YAxis y : yAxisArr) {
            y.setAxisMaximum(HeartRateUtils.getInstance().getMaxHeartRate());
            y.setAxisMinimum(HeartRateUtils.getInstance().getMinHeartRate());
            y.setDrawGridLinesEnabled(false);
            y.setDrawTopYLabelEntryEnabled(true);
            y.setTextColor(CHART_TEXT_COLOR);
        }

        refresh();
    }

    @Override
    protected void setupLegend(Chart<?> chart) {
        List<LegendEntry> legendEntries = new ArrayList<>(4);

        if (TOTAL_DAYS == 1) {
            LegendEntry hrEntry = new LegendEntry();
            hrEntry.setLabel(getTitle());
            hrEntry.setFormColor(HEARTRATE_COLOR);
            legendEntries.add(hrEntry);
        } else {
            LegendEntry hrMinEntry = new LegendEntry();
            hrMinEntry.setLabel(getString(R.string.hr_minimum));
            hrMinEntry.setFormColor(HEARTRATE_MIN_COLOR);
            legendEntries.add(hrMinEntry);
        }

        if (supportsHeartRateRestingMeasurement() && TOTAL_DAYS != 1) {
            LegendEntry hrRestingEntry = new LegendEntry();
            hrRestingEntry.setLabel(getString(R.string.hr_resting));
            hrRestingEntry.setFormColor(HEARTRATE_RESTING_COLOR);
            legendEntries.add(hrRestingEntry);
        }

        if (GBApplication.getPrefs().getBoolean("charts_show_average", true)) {
            LegendEntry hrAverageEntry = new LegendEntry();
            hrAverageEntry.setLabel(getString(R.string.hr_average));
            hrAverageEntry.setFormColor(TOTAL_DAYS != 1 ? HEARTRATE_COLOR : Color.RED);
            legendEntries.add(hrAverageEntry);
        }

        if (TOTAL_DAYS != 1) {
            LegendEntry hrMaxEntry = new LegendEntry();
            hrMaxEntry.setLabel(getString(R.string.hr_maximum));
            hrMaxEntry.setFormColor(HEARTRATE_MAX_COLOR);
            legendEntries.add(hrMaxEntry);
        }

        chart.getLegend().setEntries(legendEntries);
        chart.getLegend().setTextColor(LEGEND_TEXT_COLOR);
        chart.getLegend().setWordWrapEnabled(true);
    }

    protected LineDataSet createHeartRateDataSet(final List<Entry> values, int color) {
        LineDataSet dataSet = new LineDataSet(values, "Heart Rate");
        dataSet.setLineWidth(1.5f);
        dataSet.setMode(LineDataSet.Mode.HORIZONTAL_BEZIER);
        dataSet.setCubicIntensity(0.1f);
        // A single spot reading has no line segment; show its actual point.
        dataSet.setDrawCirclesEnabled(values.size() == 1);
        dataSet.setCircleColor(color);
        dataSet.setDrawCircleHoleEnabled(false);
        dataSet.setDrawValuesEnabled(true);
        dataSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        dataSet.setColor(color);
        dataSet.setValueTextColor(TEXT_COLOR);
        dataSet.setValueTextSize(10f);
        return dataSet;
    }

    private Pair<Integer, Integer> getStartAndEndTS() {
        return getLocalDayRange(getEndDate(), TOTAL_DAYS, TimeZone.getDefault());
    }

    static Pair<Integer, Integer> getLocalDayRange(final Date selectedDate, final int totalDays,
                                                    final TimeZone timeZone) {
        final Calendar selectedDay = Calendar.getInstance(timeZone);
        selectedDay.setTime(selectedDate);
        selectedDay.set(Calendar.HOUR_OF_DAY, 0);
        selectedDay.set(Calendar.MINUTE, 0);
        selectedDay.set(Calendar.SECOND, 0);
        selectedDay.set(Calendar.MILLISECOND, 0);
        final Calendar firstDay = (Calendar) selectedDay.clone();
        firstDay.add(Calendar.DATE, 1 - totalDays);
        selectedDay.add(Calendar.DATE, 1);
        return Pair.of((int) (firstDay.getTimeInMillis() / 1000L),
                (int) (selectedDay.getTimeInMillis() / 1000L) - 1);
    }

    private void setStatistics(int average, int minimum, int maximum, int resting) {
        hrStatsContainer.removeAllViews();

        final WorkoutValueFormatter workoutValueFormatter = new WorkoutValueFormatter();
        final List<StatTileData> stats = new ArrayList<>();

        stats.add(new StatTileData(
                minimum > 0 ? workoutValueFormatter.formatValue(minimum, UNIT_BPM) : getString(R.string.stats_empty_value),
                getString(R.string.hr_minimum)
        ));

        stats.add(new StatTileData(
                maximum > 0 ? workoutValueFormatter.formatValue(maximum, UNIT_BPM) : getString(R.string.stats_empty_value),
                getString(R.string.hr_maximum)
        ));

        stats.add(new StatTileData(
                average > 0 ? workoutValueFormatter.formatValue(average, UNIT_BPM) : getString(R.string.stats_empty_value),
                getString(R.string.hr_average)
        ));

        if (supportsHeartRateRestingMeasurement()) {
            stats.add(new StatTileData(
                    resting > 0 ? workoutValueFormatter.formatValue(resting, UNIT_BPM) : getString(R.string.stats_empty_value),
                    getString(R.string.hr_resting)
            ));
        }

        StatTileGridUtilKt.addStatTileGrid(hrStatsContainer, requireContext(), stats, 0);

        if (minimum > 0) {
            hrLineChart.getAxisLeft().setAxisMinimum(Math.max(minimum - 30, 0));
            hrLineChart.getAxisRight().setAxisMinimum(Math.max(minimum - 30, 0));
        }
        if (maximum > 0) {
            hrLineChart.getAxisLeft().setAxisMaximum(maximum + 30);
            hrLineChart.getAxisRight().setAxisMaximum(maximum + 30);
        }
    }

    @Override
    protected void updateChartsnUIThread(HeartRatePeriodData data) {
        Pair<Integer, Integer> startAndEndTs = getStartAndEndTS();
        final int startTs = startAndEndTs.getKey();
        final int endTs = startAndEndTs.getValue();

        //Date date = new Date((long) endTs * 1000);
        mDateView.setText(DateTimeUtils.formatDaysUntil(TOTAL_DAYS, getTSEnd()));
        final XAxis x = hrLineChart.getXAxis();
        if (TOTAL_DAYS == 1) {
            setOneDayData(data.samples.get(0), startTs, endTs);
            x.setAxisMinimum(0f);
            x.setAxisMaximum(endTs - startTs + 1f);
        } else {
            setMultipleDaysData(data, startTs, endTs);
            x.setAxisMinimum(0);
            // If the timestamp is used as XAxis, the chart library formats
            // the labels not at 0:00, which causes a shift in the labels
            x.setAxisMaximum(TOTAL_DAYS - 1);
        }
    }

    private void setOneDayData(HeartRateData data, int startTs, int endTs) {
        Date date = new Date((long) endTs * 1000);
        String formattedDate = new SimpleDateFormat("E, MMM dd").format(date);
        mDateView.setText(formattedDate);

        final TimestampTranslation tsTranslation = new TimestampTranslation();
        tsTranslation.shorten(startTs);

        final List<ILineDataSet<?>> lineDataSets = new ArrayList<>();
        final GBDevice device = getChartsHost().getDevice();
        final List<List<Entry>> segments = oneDaySegments(data.samples, startTs,
                HeartRateUtils.getInstance(),
                device.getDeviceCoordinator().getMaxHeartRateMeasurementsGapMinutes(device));
        int plottedReadings = 0;
        int singletonSegments = 0;
        for (final List<Entry> segment : segments) {
            lineDataSets.add(createHeartRateDataSet(segment, HEARTRATE_COLOR));
            plottedReadings += segment.size();
            if (segment.size() == 1) singletonSegments++;
        }
        LOG.debug("Heart-rate Day chart: startTs={} endTs={} activitySamples={} plottedReadings={} segments={} singletonSegments={}",
                startTs, endTs, data.samples.size(), plottedReadings, segments.size(), singletonSegments);
        hrLineChart.getXAxis().setValueFormatter(new SampleXLabelFormatter(tsTranslation, "HH:mm"));

        setStatistics(data.average, data.minimum, data.maximum, data.restingHeartRate);

        hrLineChart.setData(new LineData(lineDataSets));
        hrLineChart.getAxisLeft().removeAllLimitLines();

        if (data.average > 0 && GBApplication.getPrefs().getBoolean("charts_show_average", true)) {
            final LimitLine averageLine = new LimitLine(data.average, "");
            averageLine.setLineWidth(1.5f);
            averageLine.enableDashedLine(15f, 10f, 0f);
            averageLine.setLineColor(Color.RED);
            hrLineChart.getAxisLeft().addLimitLine(averageLine);
        }

        //if (data.restingHeartRate > 0) {
        //    final LimitLine restingLine = new LimitLine(data.restingHeartRate);
        //    restingLine.setLineWidth(1.5f);
        //    restingLine.enableDashedLine(15f, 10f, 0f);
        //    restingLine.setLineColor(HEARTRATE_RESTING_COLOR);
        //    hrLineChart.getAxisLeft().addLimitLine(restingLine);
        //}
    }

    static List<List<Entry>> oneDaySegments(final List<? extends ActivitySample> samples,
                                             final int dayStartTs,
                                             final HeartRateUtils heartRateUtils) {
        return oneDaySegments(samples, dayStartTs, heartRateUtils,
                HeartRateUtils.MAX_HR_MEASUREMENTS_GAP_MINUTES);
    }

    static List<List<Entry>> oneDaySegments(final List<? extends ActivitySample> samples,
                                             final int dayStartTs,
                                             final HeartRateUtils heartRateUtils,
                                             final int maxHRGapMinutes) {
        final List<List<Entry>> segments = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        int lastTs = 0;
        for (final ActivitySample sample : samples) {
            if (!heartRateUtils.isValidHeartRateValue(sample.getHeartRate())) continue;
            final int ts = sample.getTimestamp();
            if (lastTs != 0 && ts - lastTs > 60 * maxHRGapMinutes) {
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(new Entry<>(ts - dayStartTs, sample.getHeartRate(), null, null));
            lastTs = ts;
        }
        if (!current.isEmpty()) segments.add(current);
        return segments;
    }

    private void setMultipleDaysData(HeartRatePeriodData data, int startTs, int endTs) {
        List<HeartRateData> samples = data.samples;
        final Accumulator avgAccumulator = new Accumulator();
        final Accumulator minAccumulator = new Accumulator();
        final Accumulator maxAccumulator = new Accumulator();
        final Accumulator restingAccumulator = new Accumulator();

        final ArrayList<Entry> avgLineData = new ArrayList<>();
        final ArrayList<Entry> minLineData = new ArrayList<>();
        final ArrayList<Entry> maxLineData = new ArrayList<>();
        final ArrayList<Entry> restingLineData = new ArrayList<>();

        for (int i = 0; i < samples.size(); i++) {
            final HeartRateData hrData = samples.get(i);
            if (hrData.average > 0) {
                avgAccumulator.add(hrData.average);
                avgLineData.add(new Entry<>(i, hrData.average, null, null));
            }
            if (hrData.minimum > 0) {
                minAccumulator.add(hrData.minimum);
                minLineData.add(new Entry<>(i, hrData.minimum, null, null));
            }
            if (hrData.maximum > 0) {
                maxAccumulator.add(hrData.maximum);
                maxLineData.add(new Entry<>(i, hrData.maximum, null, null));
            }
            if (hrData.restingHeartRate > 0) {
                restingAccumulator.add(hrData.restingHeartRate);
                restingLineData.add(new Entry<>(i, hrData.restingHeartRate, null, null));
            }
        }

        final String fmt = TOTAL_DAYS == 7 ? "EEE" : "dd";
        SimpleDateFormat formatDay = new SimpleDateFormat(fmt, Locale.getDefault());
        IAxisValueFormatter formatter = (value, axis) -> {
            final Calendar labelDay = Calendar.getInstance();
            labelDay.setTimeInMillis(startTs * 1000L);
            labelDay.add(Calendar.DATE, (int) value);
            return formatDay.format(labelDay.getTime());
        };
        hrLineChart.getXAxis().setValueFormatter(formatter);

        final int average = avgAccumulator.getCount() > 0 ? (int) Math.round(avgAccumulator.getAverage()) : DATA_INVALID;
        final int minimum = minAccumulator.getCount() > 0 ? (int) Math.round(minAccumulator.getMin()) : DATA_INVALID;
        final int maximum = maxAccumulator.getCount() > 0 ? (int) Math.round(maxAccumulator.getMax()) : DATA_INVALID;
        final int restingAvg = restingAccumulator.getCount() > 0 ? (int) Math.round(restingAccumulator.getAverage()) : DATA_INVALID;
        setStatistics(average, minimum, maximum, restingAvg);

        List<ILineDataSet<?>> dataSets = new ArrayList<>();
        if (GBApplication.getPrefs().getBoolean("charts_show_average", true)) {
            dataSets.add(createHeartRateDataSet(avgLineData, HEARTRATE_COLOR));
        }
        dataSets.add(createHeartRateDataSet(minLineData, HEARTRATE_MIN_COLOR));
        dataSets.add(createHeartRateDataSet(maxLineData, HEARTRATE_MAX_COLOR));
        dataSets.add(createHeartRateDataSet(restingLineData, HEARTRATE_RESTING_COLOR));

        hrLineChart.setData(new LineData(dataSets));
    }

    protected static class HeartRatePeriodData extends ChartsData {
        public List<HeartRateData> samples;

        protected HeartRatePeriodData(List<HeartRateData> samples) {
            this.samples = samples;
        }
    }

    protected static class HeartRateData extends ChartsData {
        public List<? extends ActivitySample> samples;
        public int restingHeartRate;
        public int average;
        public int minimum;
        public int maximum;

        protected HeartRateData(List<? extends ActivitySample> samples, int restingHeartRate, int average, int minimum, int maximum) {
            this.samples = samples;
            this.restingHeartRate = restingHeartRate;
            this.average = average;
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }
}
