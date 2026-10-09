package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;

/** Latest normal Gadgetbridge weather input retained for a later Gear FETCH_REQ. */
public final class SapWeatherStateCache {
    private WeatherSpec latest;

    public void update(final WeatherSpec weather) {
        latest = weather;
    }

    public void clear() {
        latest = null;
    }

    public boolean hasWeather() {
        return SapWeatherCodec.hasUsableWeather(latest);
    }

    public SapWeatherCodec.EncodedWeatherEnvelope encode(final long sequence, final TimeZone timeZone) {
        return hasWeather()
                ? SapWeatherCodec.encodeWeatherEnvelope(sequence, latest, timeZone)
                : SapWeatherCodec.encodeEmptyWeatherEnvelope(sequence);
    }
}
