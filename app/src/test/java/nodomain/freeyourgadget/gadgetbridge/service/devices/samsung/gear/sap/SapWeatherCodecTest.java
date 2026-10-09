package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class SapWeatherCodecTest {
    private static final String REQUEST = "{\"WEATHER_REQUEST\":{\"FETCH_REQ\":1,\"UPDATED_BY_GEAR\":0,\"ADD_CURRENT_CITY\":0},\"REVISION\":1,\"WEATHER_ACK_SEQ\":\"1790075488\"}";

    @Test
    public void decodesUncompressedWeatherRequestAndBoundedFields() {
        final SapWeatherCodec.WeatherRequest request = SapWeatherCodec.decodeRequest(frame(REQUEST));

        assertEquals(1, request.getRevision());
        assertEquals(1, request.getFetchRequest());
        assertEquals(0, request.getUpdatedByGear());
        assertEquals(0, request.getAddCurrentCity());
        assertEquals(1790075488L, request.getAckSequence());
        assertTrue(request.getTopLevelKeys().contains("WEATHER_REQUEST"));
        assertTrue(request.getTopLevelKeys().contains("REVISION"));
        assertTrue(request.getTopLevelKeys().contains("WEATHER_ACK_SEQ"));
    }

    @Test
    public void serializesNumericAckFromNumericStringSequence() throws Exception {
        final SapWeatherCodec.WeatherRequest request = SapWeatherCodec.decodeRequest(frame(REQUEST));
        final byte[] encoded = SapWeatherCodec.encodeAck(request.getAckSequence());

        assertEquals(SapWeatherCodec.SDK_HEADER_UNCOMPRESSED, encoded[0] & 0xff);
        final JSONObject ack = new JSONObject(new String(encoded, 1, encoded.length - 1, StandardCharsets.UTF_8));
        assertEquals(1790075488L, ack.getLong("WEATHER_ACK_SEQ"));
        assertTrue(ack.get("WEATHER_ACK_SEQ") instanceof Number);
    }

    @Test
    public void rejectsMissingOrInvalidSequence() {
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeRequest(frame(
                "{\"WEATHER_REQUEST\":{},\"REVISION\":1}")));
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeRequest(frame(
                "{\"WEATHER_REQUEST\":{},\"REVISION\":1,\"WEATHER_ACK_SEQ\":\"not-a-number\"}")));
    }

    @Test
    public void rejectsCompressedHeaderOutsideMilestone() {
        final byte[] framed = frame(REQUEST);
        framed[0] = (byte) SapWeatherCodec.SDK_HEADER_COMPRESSED;
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeRequest(framed));
    }

    @Test
    public void serializesOneWeatherSpecInfoEntry() {
        final SapWeatherCodec.EncodedWeatherEnvelope encoded = encodeWeather(1790075499784L);
        final SapWeatherCodec.WeatherEnvelope envelope = SapWeatherCodec.decodeEnvelope(encoded.getFramed());

        assertEquals(SapWeatherCodec.SDK_HEADER_COMPRESSED, encoded.getFramed()[0] & 0xff);
        assertTrue(encoded.isCompressed());
        assertEquals(1, envelope.getWeatherInfoCount());
        assertEquals("Test City", envelope.getFirstCityName());
        assertEquals(20.0d, envelope.getFirstCurrentTemperature(), 0.0d);
        assertEquals("Clear", envelope.getFirstWeatherText());
        assertEquals(Integer.valueOf(0), envelope.getFirstConvertedIcon());
        assertEquals("GMT+01:00", envelope.getFirstCityTimeZone());
        assertEquals(1790075499784L, envelope.getAckSequence());
        assertTrue(envelope.getTopLevelKeys().contains("SETTING_INFO"));
        assertTrue(envelope.getTopLevelKeys().contains("WEATHER_INFO"));
        assertTrue(envelope.getTopLevelKeys().contains("WEATHER_ACK_SEQ"));
        assertEquals(0, envelope.getSettingAutoRefreshTime());
        assertEquals(0L, envelope.getSettingAutoRefNextTime());
        assertEquals(20, envelope.getSettingKeys().size());
        assertTrue(envelope.getSettingKeys().contains("SETTING_TEMP_SCALE"));
        assertTrue(envelope.getSettingKeys().contains("SETTING_CP_TYPE"));
    }

    @Test
    public void serializesWeatherSpecHourlyData() {
        final SapWeatherCodec.WeatherEnvelope envelope = SapWeatherCodec.decodeEnvelope(
                encodeWeather(1790075499784L).getFramed());
        final SapWeatherCodec.HourlyWeatherInfo hour = envelope.getFirstHour();

        assertEquals(1, envelope.getFirstCityHourlyCount());
        assertEquals(Integer.valueOf(1), envelope.getFirstCityDeclaredHourlyCount());
        assertEquals(Integer.valueOf(1), envelope.getFirstCityHourlyTempScale());
        assertEquals(9, hour.getKeys().size());
        assertTrue(hour.getKeys().contains("HOUR_INFO_DATE"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_HOUR"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_TEMP"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_ICON_NUM"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_ICON_NUM_CONVERTED"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_RAIN_FORECAST"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_WIND_SPEED"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_HIGH_TEMP"));
        assertTrue(hour.getKeys().contains("HOUR_INFO_LOW_TEMP"));
        assertEquals("20260101", hour.getDate());
        assertEquals("13", hour.getHour());
        assertEquals(Integer.valueOf(22), hour.getTemperature());
        assertEquals(Integer.valueOf(11), hour.getNativeIcon());
        assertEquals(Integer.valueOf(4), hour.getConvertedIcon());
        assertEquals(Integer.valueOf(50), hour.getRainForecast());
        assertEquals(Integer.valueOf(12), hour.getWindSpeed());
        assertEquals(22.0d, hour.getHighTemperature(), 0.0d);
        assertEquals(18.0d, hour.getLowTemperature(), 0.0d);
    }

    @Test
    public void serializesWeatherSpecDailyData() {
        final SapWeatherCodec.WeatherEnvelope envelope = SapWeatherCodec.decodeEnvelope(
                encodeWeather(1790075499784L).getFramed());
        final SapWeatherCodec.DailyWeatherInfo day = envelope.getFirstDay();

        assertEquals(2, envelope.getFirstCityDailyCount());
        assertEquals(Integer.valueOf(2), envelope.getFirstCityDeclaredDailyCount());
        assertEquals("20260102", day.getDate());
        assertEquals(Integer.valueOf(28), day.getNativeIcon());
        assertEquals(Integer.valueOf(1), day.getConvertedIcon());
        assertEquals(23.0d, day.getHighTemperature(), 0.0d);
        assertEquals(15.0d, day.getLowTemperature(), 0.0d);
        assertEquals("20260102", envelope.getFirstFlattenedDayDate());
        assertEquals(23.0d, envelope.getFirstFlattenedDayHighTemperature(), 0.0d);
        assertEquals(15.0d, envelope.getFirstFlattenedDayLowTemperature(), 0.0d);
        assertEquals(Integer.valueOf(30), envelope.getFirstPrecipitationProbability());
        assertTrue(day.getKeys().contains("DAY_INFO_DATE"));
        assertTrue(day.getKeys().contains("DAY_INFO_HIGH_TEMP"));
        assertTrue(day.getKeys().contains("DAY_INFO_LOW_TEMP"));
        assertTrue(!day.getKeys().contains("DAY_INFO_TEMP"));
    }

    @Test
    public void serializesWeatherSpecSunriseAndSunsetInLocalTime() {
        final WeatherSpec weather = weather();
        weather.setSunRise(1767247200);
        weather.setSunSet(1767290400);
        final SapWeatherCodec.WeatherEnvelope envelope = SapWeatherCodec.decodeEnvelope(
                SapWeatherCodec.encodeWeatherEnvelope(10L, weather, TimeZone.getTimeZone("GMT+01:00")).getFramed());

        assertEquals("07:00", envelope.getFirstSunrise());
        assertEquals("19:00", envelope.getFirstSunset());
    }

    @Test
    public void serializesMultipleRealHourlyEntriesAndPreservesMissingOptionalValues() {
        final WeatherSpec weather = weather();
        final WeatherSpec.Hourly second = new WeatherSpec.Hourly();
        second.setTimestamp(1767272400);
        second.setTemp(294);
        second.setConditionCode(801);
        second.setPrecipProbability(10);
        second.setWindSpeed(5.0f);
        weather.getHourly().add(second);

        final SapWeatherCodec.WeatherEnvelope envelope = SapWeatherCodec.decodeEnvelope(
                SapWeatherCodec.encodeWeatherEnvelope(10L, weather, TimeZone.getTimeZone("GMT+01:00")).getFramed());

        assertEquals(2, envelope.getFirstCityHourlyCount());
        assertEquals(Integer.valueOf(2), envelope.getFirstCityDeclaredHourlyCount());
        assertEquals(null, envelope.getFirstSunrise());
        assertEquals(null, envelope.getFirstSunset());
        assertEquals(null, envelope.getFirstDay().getTemperature());
    }

    @Test
    public void preservesWeatherSpecCityKeyUpdateDateAndAckSequence() {
        final long sequence = 1790075499784L;
        final SapWeatherCodec.EncodedWeatherEnvelope encoded = encodeWeather(sequence);
        final SapWeatherCodec.WeatherEnvelope decoded = SapWeatherCodec.decodeEnvelope(encoded.getFramed());

        assertEquals(sequence, decoded.getAckSequence());
        assertEquals("Test City", encoded.getCityName());
        assertEquals(20.0d, encoded.getCurrentTemperature(), 0.0d);
        assertEquals(Integer.valueOf(0), encoded.getConvertedIcon());
        assertTrue(decoded.getFirstCityLocationKey().startsWith("gb:"));
        assertTrue(!"current".equals(decoded.getFirstCityLocationKey()));
        assertEquals(decoded.getFirstCityLocationKey(), SapWeatherCodec.decodeEnvelope(
                encodeWeather(sequence + 1).getFramed()).getFirstCityLocationKey());
        assertEquals(1767268800000L, decoded.getFirstCityUpdateDate().longValue());
        assertTrue(encoded.getClearJsonSize() > encoded.getEncodedPayloadSize());
    }

    @Test
    public void roundTripsZlibSdkFraming() {
        final SapWeatherCodec.EncodedWeatherEnvelope encoded = encodeWeather(1790075499784L);

        final SapWeatherCodec.WeatherEnvelope decoded = SapWeatherCodec.decodeEnvelope(encoded.getFramed());

        assertEquals(1, decoded.getWeatherInfoCount());
        assertEquals(3, decoded.getTopLevelKeys().size());
    }

    @Test
    public void fallsBackToCurrentObservationWhenNoHourlyForecastExists() {
        final WeatherSpec weather = weather();
        weather.getHourly().clear();
        final SapWeatherCodec.WeatherEnvelope envelope = SapWeatherCodec.decodeEnvelope(
                SapWeatherCodec.encodeWeatherEnvelope(10L, weather, TimeZone.getTimeZone("GMT+01:00")).getFramed());

        assertEquals(1, envelope.getFirstCityHourlyCount());
        assertEquals(Integer.valueOf(20), envelope.getFirstHour().getTemperature());
        assertEquals(Integer.valueOf(0), envelope.getFirstHour().getConvertedIcon());
    }

    @Test
    public void parsesWatchAcknowledgement() {
        final SapWeatherCodec.WeatherAcknowledgement acknowledgement = SapWeatherCodec.decodeAcknowledgement(
                SapWeatherCodec.encodeAck(1790075499784L));

        assertEquals(1790075499784L, acknowledgement.getAckSequence());
        assertTrue(acknowledgement.getTopLevelKeys().contains("WEATHER_ACK_SEQ"));
    }

    @Test
    public void parsesStringWatchAcknowledgementAndMatchesPendingSequence() {
        final long pendingSequence = 1790621417650L;
        final SapWeatherCodec.WeatherAcknowledgement acknowledgement = SapWeatherCodec.decodeAcknowledgement(
                frame("{\"WEATHER_ACK_SEQ\":\"1790621417650\"}"));

        assertEquals(pendingSequence, acknowledgement.getAckSequence());
    }

    @Test
    public void parsesNumericWatchAcknowledgement() {
        final SapWeatherCodec.WeatherAcknowledgement acknowledgement = SapWeatherCodec.decodeAcknowledgement(
                frame("{\"WEATHER_ACK_SEQ\":1790621417650}"));

        assertEquals(1790621417650L, acknowledgement.getAckSequence());
    }

    @Test
    public void rejectsMalformedOrOverflowingWatchAcknowledgement() {
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeAcknowledgement(
                frame("{\"WEATHER_ACK_SEQ\":\"not-a-number\"}")));
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeAcknowledgement(
                frame("{\"WEATHER_ACK_SEQ\":\"9223372036854775808\"}")));
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeAcknowledgement(
                frame("{\"WEATHER_ACK_SEQ\":null}")));
        assertThrows(IllegalArgumentException.class, () -> SapWeatherCodec.decodeAcknowledgement(
                frame("{\"WEATHER_ACK_SEQ\":\"\"}")));
    }

    @Test
    public void mapsOpenWeatherMapConditionsToSamsungConvertedIcons() {
        assertEquals(0, SapWeatherIconMapper.toSamsungConvertedIcon(800));
        assertEquals(1, SapWeatherIconMapper.toSamsungConvertedIcon(801));
        assertEquals(4, SapWeatherIconMapper.toSamsungConvertedIcon(500));
        assertEquals(8, SapWeatherIconMapper.toSamsungConvertedIcon(211));
        assertEquals(13, SapWeatherIconMapper.toSamsungConvertedIcon(601));
        assertEquals(3, SapWeatherIconMapper.toSamsungConvertedIcon(741));
        assertEquals(23, SapWeatherIconMapper.toSamsungConvertedIcon(781));
        assertEquals(32, SapWeatherIconMapper.toTWCNativeIcon(0));
    }

    @Test
    public void cachedWeatherEncodesOnLaterFetchAndNoWeatherUsesEmptyEnvelope() {
        final SapWeatherStateCache cache = new SapWeatherStateCache();
        cache.update(weather());
        assertEquals(1, SapWeatherCodec.decodeEnvelope(cache.encode(101L, TimeZone.getTimeZone("GMT+01:00")).getFramed()).getWeatherInfoCount());

        cache.clear();
        assertEquals(0, SapWeatherCodec.decodeEnvelope(cache.encode(102L, TimeZone.getTimeZone("GMT+01:00")).getFramed()).getWeatherInfoCount());
    }

    private static SapWeatherCodec.EncodedWeatherEnvelope encodeWeather(final long sequence) {
        return SapWeatherCodec.encodeWeatherEnvelope(sequence, weather(), TimeZone.getTimeZone("GMT+01:00"));
    }

    private static WeatherSpec weather() {
        final WeatherSpec weather = new WeatherSpec();
        weather.setLocation("Test City");
        weather.setTimestamp(1767268800);
        weather.setCurrentTemp(293);
        weather.setTodayMaxTemp(295);
        weather.setTodayMinTemp(291);
        weather.setCurrentConditionCode(800);
        weather.setCurrentCondition("Clear");
        weather.setPrecipProbability(30);
        weather.setWindSpeed(7.0f);
        final WeatherSpec.Hourly hourly = new WeatherSpec.Hourly();
        hourly.setTimestamp(1767268800);
        hourly.setTemp(295);
        hourly.setConditionCode(500);
        hourly.setPrecipProbability(50);
        hourly.setWindSpeed(12.0f);
        weather.getHourly().add(hourly);
        final WeatherSpec.Daily tomorrow = new WeatherSpec.Daily();
        tomorrow.setMinTemp(288);
        tomorrow.setMaxTemp(296);
        tomorrow.setConditionCode(801);
        weather.getForecasts().add(tomorrow);
        final WeatherSpec.Daily followingDay = new WeatherSpec.Daily();
        followingDay.setMinTemp(290);
        followingDay.setMaxTemp(298);
        followingDay.setConditionCode(500);
        weather.getForecasts().add(followingDay);
        return weather;
    }

    private static byte[] frame(final String json) {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        final byte[] framed = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, framed, 1, bytes.length);
        return framed;
    }
}
