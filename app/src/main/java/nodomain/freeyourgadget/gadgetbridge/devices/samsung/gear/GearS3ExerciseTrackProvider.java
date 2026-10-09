package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;

/** Workout-only measured points; ordinary tracker HR rows are intentionally excluded. */
public final class GearS3ExerciseTrackProvider implements ActivityTrackProvider {
    private static final Logger LOG = LoggerFactory.getLogger(GearS3ExerciseTrackProvider.class);

    @Nullable
    @Override
    public ActivityTrack getActivityTrack(@NonNull final BaseActivitySummary summary) {
        final JSONObject stored = GearS3ExerciseImporter.storedData(summary);
        if (stored == null) return null;
        final JSONArray points = stored.optJSONArray("heart_rate");
        if (points == null || points.length() == 0) return null;
        final ActivityTrack track = new ActivityTrack();
        track.setBaseTime(summary.getStartTime());
        track.setDevice(summary.getDevice());
        track.setUser(summary.getUser());
        for (int i = 0; i < points.length(); i++) {
            final JSONArray pair = points.optJSONArray(i);
            if (pair == null || pair.length() != 2) continue;
            final Object timeValue = pair.opt(0);
            final Object bpmValue = pair.opt(1);
            if (!(timeValue instanceof Number) || !(bpmValue instanceof Number)) continue;
            final long time = ((Number) timeValue).longValue();
            final double exactBpm = ((Number) bpmValue).doubleValue();
            final int bpm = (int) Math.round(exactBpm);
            if (time < summary.getStartTime().getTime() || time > summary.getEndTime().getTime()
                    || !Double.isFinite(exactBpm) || exactBpm < 1 || exactBpm > 250) continue;
            final ActivityPoint point = new ActivityPoint(new Date(time));
            point.setHeartRate(bpm);
            track.addTrackPoint(point);
        }
        final int pointCount = track.getAllPoints().size();
        LOG.info("Gear S3 workout track provider: heartRatePoints={}", pointCount);
        return pointCount == 0 ? null : track;
    }
}
