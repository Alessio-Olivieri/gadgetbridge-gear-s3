package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.entities.GearS3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.SapPedometerJsonProbe;

import static org.junit.Assert.assertEquals;

public class GearS3StepImporterTest {
    @Test
    public void convertsSamsungMillisecondsToActivitySeconds() {
        assertEquals(1790176980, GearS3StepImporter.toActivityTimestampSeconds(1790176980000L));
    }

    @Test
    public void repeatedImportUsesTheSameActivityTimestamp() {
        final long startTimeMs = 1790176980000L;
        assertEquals(GearS3StepImporter.toActivityTimestampSeconds(startTimeMs),
                GearS3StepImporter.toActivityTimestampSeconds(startTimeMs));
    }

    @Test
    public void repeatedImportRetainsTheSameSourceUuidAndStepInterval() throws org.json.JSONException {
        final JSONObject row = new JSONObject()
                .put("datauuid", "uuid-1")
                .put("start_time", 1790176980000L)
                .put("end_time", 1790177039999L)
                .put("count", 13)
                .put("walk_step", 13)
                .put("run_step", 0)
                .put("distance", 9.09)
                .put("calorie", 0.4)
                .put("duration", 8844)
                .put("speed", 1.0277778);
        final String body = new JSONArray()
                .put(new JSONObject().put("pedometer_step_count", new JSONArray().put(row)))
                .toString();
        final SapPedometerJsonProbe.Result parsed = SapPedometerJsonProbe.parse(new JSONObject()
                .put("message", "REQUEST").put("body", body).toString()
                .getBytes(StandardCharsets.UTF_8));
        final SapPedometerJsonProbe.PedometerStepRecords records =
                SapPedometerJsonProbe.parsePedometerStepRecords(parsed);
        final List<GearS3ActivitySample> first = GearS3StepImporter.toSamples(records.getRecords());
        final List<GearS3ActivitySample> second = GearS3StepImporter.toSamples(records.getRecords());
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(first.get(0).getDatauuid(), second.get(0).getDatauuid());
        assertEquals(first.get(0).getSteps(), second.get(0).getSteps());
        assertEquals(first.get(0).getTimestamp(), second.get(0).getTimestamp());
    }
}
