package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GearS3HeartRateImporterTest extends TestBase {
    private static final long TIME_MS = 1790176980123L;
    private GBDevice device;
    private GearS3HeartRateSampleProvider heartRateProvider;
    private GearS3ActivitySampleProvider activityProvider;

    @Before
    public void setUpProviders() {
        device = createDummyGDevice("00:00:00:00:00:31");
        DBHelper.getDevice(device, daoSession);
        heartRateProvider = new GearS3HeartRateSampleProvider(device, daoSession);
        activityProvider = new GearS3ActivitySampleProvider(device, daoSession);
    }

    private static SapPedometerJsonProbe.HeartRateRecords parse(final int firstRate)
            throws org.json.JSONException {
        final JSONArray records = new JSONArray()
                .put(new JSONObject().put("datauuid", "synthetic-hr-a")
                        .put("start_time", TIME_MS).put("end_time", TIME_MS + 1000)
                        .put("heart_rate", firstRate))
                .put(new JSONObject().put("datauuid", "synthetic-hr-b")
                        .put("start_time", TIME_MS).put("end_time", TIME_MS + 2000)
                        .put("heart_rate", 81));
        final String body = new JSONArray().put(new JSONObject()
                .put("com.samsung.shealth.tracker.heart_rate", records)).toString();
        final SapPedometerJsonProbe.Result parsed = SapPedometerJsonProbe.parse(new JSONObject()
                .put("message", "REQUEST").put("body", body).toString()
                .getBytes(StandardCharsets.UTF_8));
        return SapPedometerJsonProbe.parseHeartRateRecords(parsed);
    }

    @Test
    public void sourceUuidUpsertKeepsDistinctRowsAtTheSameMillisecond() throws Exception {
        final List<GearS3HeartRateSample> first = GearS3HeartRateImporter.toSamples(parse(72).getRecords());
        assertTrue(heartRateProvider.persistSamples(first, getContext()));
        assertEquals(2, heartRateProvider.getAllSamples(TIME_MS, TIME_MS).size());

        final List<GearS3HeartRateSample> replay = GearS3HeartRateImporter.toSamples(parse(73).getRecords());
        assertTrue(heartRateProvider.persistSamples(replay, getContext()));
        final List<GearS3HeartRateSample> stored = heartRateProvider.getAllSamples(TIME_MS, TIME_MS);
        assertEquals(2, stored.size());
        assertEquals(73, stored.stream().filter(s -> "synthetic-hr-a".equals(s.getDatauuid()))
                .findFirst().get().getHeartRate());
    }

    @Test
    public void theSameSourceUuidOnAnotherDeviceDoesNotReplaceThisDevice() throws Exception {
        assertTrue(heartRateProvider.persistSamples(
                GearS3HeartRateImporter.toSamples(parse(72).getRecords()), getContext()));
        final GBDevice other = createDummyGDevice("00:00:00:00:00:32");
        DBHelper.getDevice(other, daoSession);
        final GearS3HeartRateSampleProvider otherProvider =
                new GearS3HeartRateSampleProvider(other, daoSession);
        assertTrue(otherProvider.persistSamples(
                GearS3HeartRateImporter.toSamples(parse(73).getRecords()), getContext()));
        assertEquals(2, heartRateProvider.getAllSamples(TIME_MS, TIME_MS).size());
        assertEquals(2, otherProvider.getAllSamples(TIME_MS, TIME_MS).size());
        assertEquals(4, daoSession.getGearS3HeartRateSampleDao().count());
    }

    @Test
    public void chartOverlayRetainsStepsAndAddsHeartRateWithoutWritingStepRows() throws Exception {
        final GearS3ActivitySample step = new GearS3ActivitySample();
        step.setDatauuid("synthetic-step-a");
        step.setTimestamp((int) (TIME_MS / 1000L));
        step.setSteps(13);
        assertTrue(activityProvider.persistSamples(Arrays.asList(step), getContext()));
        assertTrue(heartRateProvider.persistSamples(
                GearS3HeartRateImporter.toSamples(parse(72).getRecords()), getContext()));

        final int second = (int) (TIME_MS / 1000L);
        final List<GearS3ActivitySample> chart = activityProvider.getAllActivitySamples(second, second);
        assertEquals(1, chart.size());
        assertEquals(13, chart.get(0).getSteps());
        assertEquals(81, chart.get(0).getHeartRate());
        assertEquals(ActivitySample.NOT_MEASURED,
                daoSession.getGearS3ActivitySampleDao().loadAll().get(0).getHeartRate());
    }

    @Test
    public void heartRateOnlyChartSampleHasNoInventedSteps() throws Exception {
        assertTrue(heartRateProvider.persistSamples(
                GearS3HeartRateImporter.toSamples(parse(72).getRecords()), getContext()));
        final Calendar localDay = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"));
        localDay.setTimeInMillis(TIME_MS);
        localDay.set(Calendar.HOUR_OF_DAY, 0);
        localDay.set(Calendar.MINUTE, 0);
        localDay.set(Calendar.SECOND, 0);
        localDay.set(Calendar.MILLISECOND, 0);
        final int startTs = (int) (localDay.getTimeInMillis() / 1000L);
        localDay.add(Calendar.DATE, 1);
        final int endTs = (int) (localDay.getTimeInMillis() / 1000L) - 1;
        final List<GearS3ActivitySample> chart = activityProvider.getAllActivitySamplesHighRes(startTs, endTs);
        assertEquals(1, chart.size());
        assertEquals(81, chart.get(0).getHeartRate());
        assertEquals(ActivitySample.NOT_MEASURED, chart.get(0).getSteps());
        assertEquals(0, daoSession.getGearS3ActivitySampleDao().count());
    }
}
