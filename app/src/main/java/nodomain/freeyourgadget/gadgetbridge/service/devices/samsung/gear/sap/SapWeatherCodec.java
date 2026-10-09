package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/** Bounded framing and weather codec for /system/weather2. */
public final class SapWeatherCodec {
    public static final int SDK_HEADER_UNCOMPRESSED = 0x00;
    public static final int SDK_HEADER_COMPRESSED = 0x10;
    public static final String WEATHER_REQUEST = "WEATHER_REQUEST";
    public static final String REVISION = "REVISION";
    public static final String SETTING_INFO = "SETTING_INFO";
    public static final String WEATHER_INFO = "WEATHER_INFO";
    public static final String WEATHER_ACK_SEQ = "WEATHER_ACK_SEQ";
    private static final int MAX_WEATHER_JSON_SIZE = 64 * 1024;
    private static final int MAX_DAILY_ENTRIES = 6;
    private static final int MAX_HOURLY_ENTRIES = 24;
    private SapWeatherCodec() { }

    public static WeatherRequest decodeRequest(final byte[] framed) {
        if (framed == null || framed.length < 2) throw new IllegalArgumentException("Truncated weather application frame");
        final int header = framed[0] & 0xff;
        if (header == SDK_HEADER_COMPRESSED) throw new IllegalArgumentException("Compressed weather frame is outside milestone 1");
        if (header != SDK_HEADER_UNCOMPRESSED) throw new IllegalArgumentException("Unsupported weather SDK header");
        final String json = new String(framed, 1, framed.length - 1, StandardCharsets.UTF_8);
        try {
            final JSONObject root = new JSONObject(json);
            final JSONObject request = root.optJSONObject(WEATHER_REQUEST);
            if (request == null) throw new IllegalArgumentException("Missing WEATHER_REQUEST");
            if (!root.has(REVISION)) throw new IllegalArgumentException("Missing REVISION");
            final int revision = root.getInt(REVISION);
            final Object rawSequence = root.opt(WEATHER_ACK_SEQ);
            if (!(rawSequence instanceof String) || ((String) rawSequence).isEmpty()) {
                throw new IllegalArgumentException("Missing or non-string WEATHER_ACK_SEQ");
            }
            final long sequence;
            try {
                sequence = decimalStringSequence((String) rawSequence);
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid WEATHER_ACK_SEQ", e);
            }
            return new WeatherRequest(topLevelKeys(root), revision, request.optInt("FETCH_REQ", 0),
                    request.optInt("UPDATED_BY_GEAR", 0), request.optInt("ADD_CURRENT_CITY", 0), sequence);
        } catch (final JSONException e) {
            throw new IllegalArgumentException("Invalid weather JSON", e);
        }
    }

    public static byte[] encodeAck(final long sequence) {
        try {
            final JSONObject ack = new JSONObject();
            ack.put(WEATHER_ACK_SEQ, sequence);
            final byte[] json = ack.toString().getBytes(StandardCharsets.UTF_8);
            final byte[] framed = new byte[json.length + 1];
            framed[0] = (byte) SDK_HEADER_UNCOMPRESSED;
            System.arraycopy(json, 0, framed, 1, json.length);
            return framed;
        } catch (final JSONException e) {
            throw new IllegalStateException("Could not encode weather ACK", e);
        }
    }

    public static boolean hasUsableWeather(final WeatherSpec weather) {
        return weather != null && weather.getLocation() != null && !weather.getLocation().trim().isEmpty()
                && weather.getTimestamp() > 0 && weather.getCurrentTemp() > 0;
    }

    public static EncodedWeatherEnvelope encodeEmptyWeatherEnvelope(final long sequence) {
        try {
            return encodeEnvelope(sequence, new JSONArray(), null, null, null);
        } catch (final JSONException e) {
            throw new IllegalStateException("Could not encode empty weather envelope", e);
        }
    }

    public static EncodedWeatherEnvelope encodeWeatherEnvelope(final long sequence, final WeatherSpec weather,
                                                                final TimeZone timeZone) {
        if (!hasUsableWeather(weather)) throw new IllegalArgumentException("WeatherSpec has no usable location/current conditions");
        if (timeZone == null) throw new IllegalArgumentException("Missing weather timezone");
        try {
            final JSONObject city = weatherInfo(weather, timeZone);
            final JSONArray weatherInfo = new JSONArray();
            weatherInfo.put(city);
            return encodeEnvelope(sequence, weatherInfo, city.getString("CITY_NAME"),
                    city.getDouble("CITY_TODAY_TEMP"), city.getInt("CITY_TODAY_ICON_NUM_CONVERTED"));
        } catch (final JSONException e) {
            throw new IllegalStateException("Could not encode WeatherSpec weather envelope", e);
        }
    }

    private static EncodedWeatherEnvelope encodeEnvelope(final long sequence, final JSONArray weatherInfo,
                                                          final String cityName, final Double currentTemperature,
                                                          final Integer convertedIcon) throws JSONException {
        final JSONObject envelope = new JSONObject();
        envelope.put(SETTING_INFO, settings());
        envelope.put(WEATHER_INFO, weatherInfo);
        envelope.put(WEATHER_ACK_SEQ, sequence);
        final byte[] clear = envelope.toString(3).getBytes(StandardCharsets.UTF_8);
        final byte[] compressed = zlib(clear);
        final byte[] framed = new byte[compressed.length + 1];
        framed[0] = (byte) SDK_HEADER_COMPRESSED;
        System.arraycopy(compressed, 0, framed, 1, compressed.length);
        return new EncodedWeatherEnvelope(framed, clear.length, true, envelope,
                weatherInfo.length(), cityName, currentTemperature, convertedIcon);
    }

    private static JSONObject settings() throws JSONException {
        final JSONObject settings = new JSONObject();
        settings.put("SETTING_TEMP_SCALE", 1);
        settings.put("SETTING_AUTO_REFRESH_TIME", 0);
        settings.put("SETTING_AUTO_REF_NEXT_TIME", 0);
        settings.put("SETTING_AUTO_SCROLL", 0);
        settings.put("SETTING_REFRESH_ENTERIN", 0);
        settings.put("SETTING_CHECK_CURRENT_CITY_LOCATION", 0);
        settings.put("SETTING_CURRENT_LOCATION_ERROR", 0);
        settings.put("SETTING_CP_TYPE", "TWC");
        settings.put("SETTING_SHOW_USE_LOCATION_POPUP", 0);
        settings.put("SETTING_NOTIFICATION", 1);
        settings.put("SETTING_NOTIFICATION_SET_TIME", 0);
        settings.put("SETTING_NOTIFICATION_POPUP", 0);
        settings.put("SETTING_DAEMON_TYPE", "RI_DAEMON");
        settings.put("SETTING_GEAR_WEATHER_MODE", 0);
        settings.put("IS_SUPPORT_BMANUAL_REFRESH", true);
        settings.put("SETTING_WCN_API_VERSION", "2");
        settings.put("SETTING_WNI_API_VERSION", "2");
        settings.put("SETTING_WJP_API_VERSION", "3");
        settings.put("SETTING_HUA_API_VERSION", "1");
        settings.put("SETTING_ANDROID_API_LEVEL", 37);
        return settings;
    }

    private static JSONObject weatherInfo(final WeatherSpec weather, final TimeZone timeZone) throws JSONException {
        final long updateMillis = weather.getTimestamp() * 1000L;
        final int convertedIcon = SapWeatherIconMapper.toSamsungConvertedIcon(weather.getCurrentConditionCode());
        final JSONObject city = new JSONObject();
        final String cityName = weather.getLocation().trim();
        city.put("CITY_NAME", cityName);
        city.put("CITY_STATE", "");
        city.put("CITY_LOC", stableCityLocationKey(cityName));
        city.put("CITY_TIMEZONE", samsungTimeZone(timeZone, updateMillis));
        city.put("CITY_THEME_CODE", " ");
        city.put("CITY_SUMMER_TIME", timeZone.inDaylightTime(new Date(updateMillis)) ? 1 : 0);
        city.put("CITY_REAL_LOCATION", cityName);
        city.put("CITY_TODAY_DATE", String.valueOf(updateMillis));
        city.put("CITY_TODAY_TEMP", celsius(weather.getCurrentTemp()));
        city.put("CITY_TODAY_HIGH_TEMP", celsiusOrCurrent(weather.getTodayMaxTemp(), weather.getCurrentTemp()));
        city.put("CITY_TODAY_LOW_TEMP", celsiusOrCurrent(weather.getTodayMinTemp(), weather.getCurrentTemp()));
        // CITY_TODAY_ICON_NUM is provider-native and intentionally omitted.
        city.put("CITY_TODAY_ICON_NUM_CONVERTED", convertedIcon);
        city.put("CITY_TODAY_WEATHER_TEXT", weather.getCurrentCondition() == null ? "" : weather.getCurrentCondition());
        if (weather.getCurrentHumidity() > 0) city.put("CITY_TODAY_HUMIDITY", weather.getCurrentHumidity());
        if (weather.getFeelsLikeTemp() > 0) city.put("CITY_TODAY_REALFELL", celsius(weather.getFeelsLikeTemp()));
        if (weather.getUvIndex() > 0f) city.put("TODAY_UV_INDEX", weather.getUvIndex());
        city.put("CITY_PROBABILITY_OF_PRECIPITATION", boundedPercent(weather.getPrecipProbability()));
        if (weather.getSunRise() > 0) city.put("CITY_TODAY_SUNRISE_TIME", formatLocalTime(weather.getSunRise() * 1000L, timeZone));
        if (weather.getSunSet() > 0) city.put("CITY_TODAY_SUNSET_TIME", formatLocalTime(weather.getSunSet() * 1000L, timeZone));
        city.put("CITY_TEMP_SCALE", 1);
        city.put("CITY_UPDATE_DATE", updateMillis);
        city.put("CITY_ORDER_NUMBER", 0);
        city.put("CITY_NAME_ENG", cityName);
        city.put("CITY_STATE_ENG", "");
        final JSONArray daily = dailyWeatherInfo(weather, timeZone, updateMillis, city);
        city.put("DAY", daily);
        city.put("DAY_INFO_SIZE", daily.length());
        if (daily.length() > 0) city.put("DAY_INFO_TEMP_SCALE", 1);
        final JSONArray hourly = new JSONArray();
        appendHourlyWeatherInfo(weather, timeZone, convertedIcon, hourly);
        city.put("HOUR", hourly);
        city.put("HOUR_INFO_SIZE", hourly.length());
        if (hourly.length() > 0) city.put("HOUR_INFO_TEMP_SCALE", 1);
        return city;
    }

    private static JSONArray dailyWeatherInfo(final WeatherSpec weather, final TimeZone timeZone,
                                              final long updateMillis, final JSONObject city) throws JSONException {
        final JSONArray daily = new JSONArray();
        final String[] prefixes = {"CITY_ONEDAY_", "CITY_TWODAY_", "CITY_THREEDAY_",
                "CITY_FOURDAY_", "CITY_FIVEDAY_", "CITY_SIXDAY_"};
        final int count = Math.min(weather.getForecasts().size(), MAX_DAILY_ENTRIES);
        for (int index = 0; index < count; index++) {
            final WeatherSpec.Daily forecast = weather.getForecasts().get(index);
            if (forecast == null || (forecast.getMinTemp() <= 0 && forecast.getMaxTemp() <= 0)) continue;
            final String date = formatDate(addDays(updateMillis, timeZone, index + 1), timeZone);
            final JSONObject day = new JSONObject();
            day.put("DAY_INFO_DATE", date);
            if (forecast.getConditionCode() > 0) {
                final int convertedIcon = SapWeatherIconMapper.toSamsungConvertedIcon(forecast.getConditionCode());
                final int nativeIcon = SapWeatherIconMapper.toTWCNativeIcon(convertedIcon);
                day.put("DAY_INFO_ICON_NUM", nativeIcon);
                day.put("DAY_INFO_ICON_NUM_CONVERTED", convertedIcon);
                city.put(prefixes[index] + "ICON_NUM", nativeIcon);
                city.put(prefixes[index] + "ICON_NUM_CONVERTED", convertedIcon);
            }
            if (forecast.getMaxTemp() > 0) day.put("DAY_INFO_HIGH_TEMP", celsius(forecast.getMaxTemp()));
            if (forecast.getMinTemp() > 0) day.put("DAY_INFO_LOW_TEMP", celsius(forecast.getMinTemp()));
            daily.put(day);

            final String prefix = prefixes[index];
            if (forecast.getMaxTemp() > 0) city.put(prefix + "HIGH_TEMP", celsius(forecast.getMaxTemp()));
            if (forecast.getMinTemp() > 0) city.put(prefix + "LOW_TEMP", celsius(forecast.getMinTemp()));
            city.put(prefix + "DATE", date);
        }
        return daily;
    }

    private static void appendHourlyWeatherInfo(final WeatherSpec weather, final TimeZone timeZone,
                                                final int currentConvertedIcon, final JSONArray output) throws JSONException {
        int emitted = 0;
        for (final WeatherSpec.Hourly hourly : weather.getHourly()) {
            if (hourly == null || hourly.getTimestamp() <= 0 || hourly.getTemp() <= 0) continue;
            output.put(hourlyWeatherInfo(weather, hourly, timeZone));
            if (++emitted >= MAX_HOURLY_ENTRIES) break;
        }
        if (emitted == 0) output.put(hourlyWeatherInfo(weather, null, timeZone, currentConvertedIcon));
    }

    private static JSONObject hourlyWeatherInfo(final WeatherSpec weather, final WeatherSpec.Hourly selected,
                                                final TimeZone timeZone) throws JSONException {
        return hourlyWeatherInfo(weather, selected, timeZone,
                SapWeatherIconMapper.toSamsungConvertedIcon(selected.getConditionCode()));
    }

    private static JSONObject hourlyWeatherInfo(final WeatherSpec weather, final WeatherSpec.Hourly selected,
                                                final TimeZone timeZone, final int currentConvertedIcon) throws JSONException {
        final long timestamp = (selected == null ? weather.getTimestamp() : selected.getTimestamp()) * 1000L;
        final int temperatureKelvin = selected == null ? weather.getCurrentTemp() : selected.getTemp();
        final int convertedIcon = selected == null ? currentConvertedIcon :
                SapWeatherIconMapper.toSamsungConvertedIcon(selected.getConditionCode());
        final int rainForecast = selected == null ? weather.getPrecipProbability() : selected.getPrecipProbability();
        final float windSpeed = selected == null ? weather.getWindSpeed() : selected.getWindSpeed();
        final Calendar calendar = Calendar.getInstance(timeZone, Locale.ROOT);
        calendar.setTimeInMillis(timestamp);
        final JSONObject hour = new JSONObject();
        hour.put("HOUR_INFO_DATE", String.format(Locale.ROOT, "%04d%02d%02d", calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)));
        hour.put("HOUR_INFO_HOUR", String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY)));
        hour.put("HOUR_INFO_TEMP", (int) Math.round(celsius(temperatureKelvin)));
        hour.put("HOUR_INFO_ICON_NUM", SapWeatherIconMapper.toTWCNativeIcon(convertedIcon));
        hour.put("HOUR_INFO_ICON_NUM_CONVERTED", convertedIcon);
        hour.put("HOUR_INFO_RAIN_FORECAST", boundedPercent(rainForecast));
        hour.put("HOUR_INFO_WIND_SPEED", Math.max(0, Math.round(windSpeed)));
        hour.put("HOUR_INFO_HIGH_TEMP", celsiusOrCurrent(weather.getTodayMaxTemp(), temperatureKelvin));
        hour.put("HOUR_INFO_LOW_TEMP", celsiusOrCurrent(weather.getTodayMinTemp(), temperatureKelvin));
        return hour;
    }

    private static double celsius(final int kelvin) { return kelvin - 273.0d; }
    private static double celsiusOrCurrent(final int kelvin, final int currentKelvin) {
        return celsius(kelvin > 0 ? kelvin : currentKelvin);
    }
    private static int boundedPercent(final int value) { return Math.max(0, Math.min(100, value)); }

    private static long addDays(final long timestamp, final TimeZone timeZone, final int days) {
        final Calendar calendar = Calendar.getInstance(timeZone, Locale.ROOT);
        calendar.setTimeInMillis(timestamp);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return calendar.getTimeInMillis();
    }

    private static String formatDate(final long timestamp, final TimeZone timeZone) {
        final Calendar calendar = Calendar.getInstance(timeZone, Locale.ROOT);
        calendar.setTimeInMillis(timestamp);
        return String.format(Locale.ROOT, "%04d%02d%02d", calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String formatLocalTime(final long timestamp, final TimeZone timeZone) {
        final Calendar calendar = Calendar.getInstance(timeZone, Locale.ROOT);
        calendar.setTimeInMillis(timestamp);
        return String.format(Locale.ROOT, "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE));
    }

    private static String samsungTimeZone(final TimeZone timeZone, final long timestamp) {
        final int totalMinutes = timeZone.getOffset(timestamp) / 60000;
        return String.format(Locale.ROOT, "GMT%c%02d:%02d", totalMinutes < 0 ? '-' : '+',
                Math.abs(totalMinutes) / 60, Math.abs(totalMinutes) % 60);
    }

    private static String stableCityLocationKey(final String cityName) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(cityName.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            final StringBuilder key = new StringBuilder("gb:");
            for (final byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return key.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable for weather city key", e);
        }
    }

    public static WeatherEnvelope decodeEnvelope(final byte[] framed) {
        final JSONObject root = decodeJson(framed);
        final JSONObject settings = root.optJSONObject(SETTING_INFO);
        final JSONArray weather = root.optJSONArray(WEATHER_INFO);
        if (settings == null || weather == null) throw new IllegalArgumentException("Missing weather envelope fields");
        final JSONObject city = weather.length() == 0 ? null : weather.optJSONObject(0);
        final JSONArray daily = city == null ? null : city.optJSONArray("DAY");
        final JSONObject day = daily == null || daily.length() == 0 ? null : daily.optJSONObject(0);
        final JSONArray hourly = city == null ? null : city.optJSONArray("HOUR");
        final JSONObject hour = hourly == null || hourly.length() == 0 ? null : hourly.optJSONObject(0);
        return new WeatherEnvelope(topLevelKeys(root), topLevelKeys(settings),
                settings.optInt("SETTING_AUTO_REFRESH_TIME", -1),
                settings.optLong("SETTING_AUTO_REF_NEXT_TIME", Long.MIN_VALUE),
                weather.length(), numericSequence(root),
                city == null ? null : city.optString("CITY_NAME", null),
                city == null ? null : city.optString("CITY_LOC", null),
                city == null ? null : city.has("CITY_TODAY_TEMP") ? city.optDouble("CITY_TODAY_TEMP") : null,
                city == null || !city.has("CITY_UPDATE_DATE") ? null : city.optLong("CITY_UPDATE_DATE"),
                city == null ? null : city.optString("CITY_TODAY_WEATHER_TEXT", null),
                city == null ? null : city.has("CITY_TODAY_ICON_NUM_CONVERTED")
                        ? city.optInt("CITY_TODAY_ICON_NUM_CONVERTED") : null,
                city == null ? null : city.optString("CITY_TIMEZONE", null),
                city == null ? null : city.optString("CITY_TODAY_SUNRISE_TIME", null),
                city == null ? null : city.optString("CITY_TODAY_SUNSET_TIME", null),
                city == null || !city.has("CITY_PROBABILITY_OF_PRECIPITATION")
                        ? null : city.optInt("CITY_PROBABILITY_OF_PRECIPITATION"),
                daily == null ? 0 : daily.length(),
                city == null || !city.has("DAY_INFO_SIZE") ? null : city.optInt("DAY_INFO_SIZE"),
                day == null ? null : new DailyWeatherInfo(topLevelKeys(day),
                        day.optString("DAY_INFO_DATE", null),
                        day.has("DAY_INFO_TEMP") ? day.optDouble("DAY_INFO_TEMP") : null,
                        day.has("DAY_INFO_ICON_NUM") ? day.optInt("DAY_INFO_ICON_NUM") : null,
                        day.has("DAY_INFO_ICON_NUM_CONVERTED") ? day.optInt("DAY_INFO_ICON_NUM_CONVERTED") : null,
                        day.has("DAY_INFO_HIGH_TEMP") ? day.optDouble("DAY_INFO_HIGH_TEMP") : null,
                        day.has("DAY_INFO_LOW_TEMP") ? day.optDouble("DAY_INFO_LOW_TEMP") : null),
                city == null ? null : city.optString("CITY_ONEDAY_DATE", null),
                city == null || !city.has("CITY_ONEDAY_HIGH_TEMP") ? null : city.optDouble("CITY_ONEDAY_HIGH_TEMP"),
                city == null || !city.has("CITY_ONEDAY_LOW_TEMP") ? null : city.optDouble("CITY_ONEDAY_LOW_TEMP"),
                hourly == null ? 0 : hourly.length(),
                city == null || !city.has("HOUR_INFO_SIZE") ? null : city.optInt("HOUR_INFO_SIZE"),
                city == null || !city.has("HOUR_INFO_TEMP_SCALE") ? null : city.optInt("HOUR_INFO_TEMP_SCALE"),
                hour == null ? null : new HourlyWeatherInfo(topLevelKeys(hour),
                        hour.optString("HOUR_INFO_DATE", null), hour.optString("HOUR_INFO_HOUR", null),
                        hour.has("HOUR_INFO_TEMP") ? hour.optInt("HOUR_INFO_TEMP") : null,
                        hour.has("HOUR_INFO_ICON_NUM") ? hour.optInt("HOUR_INFO_ICON_NUM") : null,
                        hour.has("HOUR_INFO_ICON_NUM_CONVERTED") ? hour.optInt("HOUR_INFO_ICON_NUM_CONVERTED") : null,
                        hour.has("HOUR_INFO_RAIN_FORECAST") ? hour.optInt("HOUR_INFO_RAIN_FORECAST") : null,
                        hour.has("HOUR_INFO_WIND_SPEED") ? hour.optInt("HOUR_INFO_WIND_SPEED") : null,
                        hour.has("HOUR_INFO_HIGH_TEMP") ? hour.optDouble("HOUR_INFO_HIGH_TEMP") : null,
                        hour.has("HOUR_INFO_LOW_TEMP") ? hour.optDouble("HOUR_INFO_LOW_TEMP") : null));
    }

    public static WeatherAcknowledgement decodeAcknowledgement(final byte[] framed) {
        final JSONObject root = decodeJson(framed);
        if (root.has(WEATHER_REQUEST) || root.has(SETTING_INFO) || root.has(WEATHER_INFO)) {
            throw new IllegalArgumentException("Not a weather acknowledgement");
        }
        return new WeatherAcknowledgement(topLevelKeys(root), numericSequence(root));
    }

    private static JSONObject decodeJson(final byte[] framed) {
        if (framed == null || framed.length < 2) throw new IllegalArgumentException("Truncated weather application frame");
        final int header = framed[0] & 0xff;
        final byte[] json;
        if (header == SDK_HEADER_UNCOMPRESSED) {
            json = new byte[framed.length - 1];
            System.arraycopy(framed, 1, json, 0, json.length);
        } else if (header == SDK_HEADER_COMPRESSED) {
            json = inflate(framed, 1, framed.length - 1);
        } else {
            throw new IllegalArgumentException("Unsupported weather SDK header");
        }
        try {
            return new JSONObject(new String(json, StandardCharsets.UTF_8));
        } catch (final JSONException e) {
            throw new IllegalArgumentException("Invalid weather JSON", e);
        }
    }

    private static long numericSequence(final JSONObject root) {
        final Object rawSequence = root.opt(WEATHER_ACK_SEQ);
        if (rawSequence instanceof String) return decimalStringSequence((String) rawSequence);
        if (!(rawSequence instanceof Number)) throw new IllegalArgumentException("Missing or non-numeric WEATHER_ACK_SEQ");
        try {
            return new BigDecimal(rawSequence.toString()).longValueExact();
        } catch (final NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid WEATHER_ACK_SEQ", e);
        }
    }

    private static long decimalStringSequence(final String rawSequence) {
        if (rawSequence == null || rawSequence.isEmpty() || !rawSequence.matches("[+-]?[0-9]+")) {
            throw new IllegalArgumentException("Missing or non-decimal WEATHER_ACK_SEQ");
        }
        try {
            return Long.parseLong(rawSequence);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid WEATHER_ACK_SEQ", e);
        }
    }

    private static byte[] zlib(final byte[] input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(input);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("Unable to compress weather JSON", e);
        }
        return output.toByteArray();
    }

    private static byte[] inflate(final byte[] framed, final int offset, final int length) {
        final Inflater inflater = new Inflater();
        inflater.setInput(framed, offset, length);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                final int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (output.size() + count > MAX_WEATHER_JSON_SIZE) throw new IllegalArgumentException("Weather JSON exceeds bound");
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IllegalArgumentException("Incomplete weather zlib stream");
                } else {
                    throw new IllegalArgumentException("Stalled weather zlib stream");
                }
            }
            return output.toByteArray();
        } catch (final java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("Malformed weather zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    private static Set<String> topLevelKeys(final JSONObject root) {
        final Set<String> keys = new TreeSet<>();
        final Iterator<String> iterator = root.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return Collections.unmodifiableSet(keys);
    }

    public static final class WeatherRequest {
        private final Set<String> topLevelKeys;
        private final int revision;
        private final int fetchRequest;
        private final int updatedByGear;
        private final int addCurrentCity;
        private final long ackSequence;

        private WeatherRequest(final Set<String> topLevelKeys, final int revision, final int fetchRequest,
                               final int updatedByGear, final int addCurrentCity, final long ackSequence) {
            this.topLevelKeys = topLevelKeys;
            this.revision = revision;
            this.fetchRequest = fetchRequest;
            this.updatedByGear = updatedByGear;
            this.addCurrentCity = addCurrentCity;
            this.ackSequence = ackSequence;
        }

        public Set<String> getTopLevelKeys() { return topLevelKeys; }
        public int getRevision() { return revision; }
        public int getFetchRequest() { return fetchRequest; }
        public int getUpdatedByGear() { return updatedByGear; }
        public int getAddCurrentCity() { return addCurrentCity; }
        public long getAckSequence() { return ackSequence; }
    }

    public static final class EncodedWeatherEnvelope {
        private final byte[] framed;
        private final int clearJsonSize;
        private final boolean compressed;
        private final Set<String> topLevelKeys;
        private final int weatherInfoCount;
        private final String cityName;
        private final Double currentTemperature;
        private final Integer convertedIcon;

        private EncodedWeatherEnvelope(final byte[] framed, final int clearJsonSize,
                                       final boolean compressed, final JSONObject envelope,
                                       final int weatherInfoCount, final String cityName,
                                       final Double currentTemperature, final Integer convertedIcon) {
            this.framed = framed.clone();
            this.clearJsonSize = clearJsonSize;
            this.compressed = compressed;
            this.topLevelKeys = topLevelKeys(envelope);
            this.weatherInfoCount = weatherInfoCount;
            this.cityName = cityName;
            this.currentTemperature = currentTemperature;
            this.convertedIcon = convertedIcon;
        }

        public byte[] getFramed() { return framed.clone(); }
        public int getClearJsonSize() { return clearJsonSize; }
        public boolean isCompressed() { return compressed; }
        public int getEncodedPayloadSize() { return framed.length; }
        public Set<String> getTopLevelKeys() { return topLevelKeys; }
        public int getWeatherInfoCount() { return weatherInfoCount; }
        public String getCityName() { return cityName; }
        public Double getCurrentTemperature() { return currentTemperature; }
        public Integer getConvertedIcon() { return convertedIcon; }
    }

    public static final class WeatherEnvelope {
        private final Set<String> topLevelKeys;
        private final Set<String> settingKeys;
        private final int settingAutoRefreshTime;
        private final long settingAutoRefNextTime;
        private final int weatherInfoCount;
        private final long ackSequence;
        private final String firstCityName;
        private final String firstCityLocationKey;
        private final Double firstCurrentTemperature;
        private final Long firstCityUpdateDate;
        private final String firstWeatherText;
        private final Integer firstConvertedIcon;
        private final String firstCityTimeZone;
        private final String firstSunrise;
        private final String firstSunset;
        private final Integer firstPrecipitationProbability;
        private final int firstCityDailyCount;
        private final Integer firstCityDeclaredDailyCount;
        private final DailyWeatherInfo firstDay;
        private final String firstFlattenedDayDate;
        private final Double firstFlattenedDayHighTemperature;
        private final Double firstFlattenedDayLowTemperature;
        private final int firstCityHourlyCount;
        private final Integer firstCityDeclaredHourlyCount;
        private final Integer firstCityHourlyTempScale;
        private final HourlyWeatherInfo firstHour;

        private WeatherEnvelope(final Set<String> topLevelKeys, final Set<String> settingKeys,
                                final int settingAutoRefreshTime, final long settingAutoRefNextTime,
                                final int weatherInfoCount, final long ackSequence,
                                final String firstCityName, final String firstCityLocationKey,
                                final Double firstCurrentTemperature, final Long firstCityUpdateDate,
                                final String firstWeatherText, final Integer firstConvertedIcon,
                                final String firstCityTimeZone, final String firstSunrise,
                                final String firstSunset, final Integer firstPrecipitationProbability,
                                final int firstCityDailyCount,
                                final Integer firstCityDeclaredDailyCount, final DailyWeatherInfo firstDay,
                                final String firstFlattenedDayDate,
                                final Double firstFlattenedDayHighTemperature,
                                final Double firstFlattenedDayLowTemperature,
                                final int firstCityHourlyCount,
                                final Integer firstCityDeclaredHourlyCount, final Integer firstCityHourlyTempScale,
                                final HourlyWeatherInfo firstHour) {
            this.topLevelKeys = topLevelKeys;
            this.settingKeys = settingKeys;
            this.settingAutoRefreshTime = settingAutoRefreshTime;
            this.settingAutoRefNextTime = settingAutoRefNextTime;
            this.weatherInfoCount = weatherInfoCount;
            this.ackSequence = ackSequence;
            this.firstCityName = firstCityName;
            this.firstCityLocationKey = firstCityLocationKey;
            this.firstCurrentTemperature = firstCurrentTemperature;
            this.firstCityUpdateDate = firstCityUpdateDate;
            this.firstWeatherText = firstWeatherText;
            this.firstConvertedIcon = firstConvertedIcon;
            this.firstCityTimeZone = firstCityTimeZone;
            this.firstSunrise = firstSunrise;
            this.firstSunset = firstSunset;
            this.firstPrecipitationProbability = firstPrecipitationProbability;
            this.firstCityDailyCount = firstCityDailyCount;
            this.firstCityDeclaredDailyCount = firstCityDeclaredDailyCount;
            this.firstDay = firstDay;
            this.firstFlattenedDayDate = firstFlattenedDayDate;
            this.firstFlattenedDayHighTemperature = firstFlattenedDayHighTemperature;
            this.firstFlattenedDayLowTemperature = firstFlattenedDayLowTemperature;
            this.firstCityHourlyCount = firstCityHourlyCount;
            this.firstCityDeclaredHourlyCount = firstCityDeclaredHourlyCount;
            this.firstCityHourlyTempScale = firstCityHourlyTempScale;
            this.firstHour = firstHour;
        }

        public Set<String> getTopLevelKeys() { return topLevelKeys; }
        public Set<String> getSettingKeys() { return settingKeys; }
        public int getSettingAutoRefreshTime() { return settingAutoRefreshTime; }
        public long getSettingAutoRefNextTime() { return settingAutoRefNextTime; }
        public int getWeatherInfoCount() { return weatherInfoCount; }
        public long getAckSequence() { return ackSequence; }
        public String getFirstCityName() { return firstCityName; }
        public String getFirstCityLocationKey() { return firstCityLocationKey; }
        public Double getFirstCurrentTemperature() { return firstCurrentTemperature; }
        public Long getFirstCityUpdateDate() { return firstCityUpdateDate; }
        public String getFirstWeatherText() { return firstWeatherText; }
        public Integer getFirstConvertedIcon() { return firstConvertedIcon; }
        public String getFirstCityTimeZone() { return firstCityTimeZone; }
        public String getFirstSunrise() { return firstSunrise; }
        public String getFirstSunset() { return firstSunset; }
        public Integer getFirstPrecipitationProbability() { return firstPrecipitationProbability; }
        public int getFirstCityDailyCount() { return firstCityDailyCount; }
        public Integer getFirstCityDeclaredDailyCount() { return firstCityDeclaredDailyCount; }
        public DailyWeatherInfo getFirstDay() { return firstDay; }
        public String getFirstFlattenedDayDate() { return firstFlattenedDayDate; }
        public Double getFirstFlattenedDayHighTemperature() { return firstFlattenedDayHighTemperature; }
        public Double getFirstFlattenedDayLowTemperature() { return firstFlattenedDayLowTemperature; }
        public int getFirstCityHourlyCount() { return firstCityHourlyCount; }
        public Integer getFirstCityDeclaredHourlyCount() { return firstCityDeclaredHourlyCount; }
        public Integer getFirstCityHourlyTempScale() { return firstCityHourlyTempScale; }
        public HourlyWeatherInfo getFirstHour() { return firstHour; }
    }

    public static final class DailyWeatherInfo {
        private final Set<String> keys;
        private final String date;
        private final Double temperature;
        private final Integer nativeIcon;
        private final Integer convertedIcon;
        private final Double highTemperature;
        private final Double lowTemperature;

        private DailyWeatherInfo(final Set<String> keys, final String date, final Double temperature,
                                 final Integer nativeIcon, final Integer convertedIcon,
                                 final Double highTemperature, final Double lowTemperature) {
            this.keys = keys;
            this.date = date;
            this.temperature = temperature;
            this.nativeIcon = nativeIcon;
            this.convertedIcon = convertedIcon;
            this.highTemperature = highTemperature;
            this.lowTemperature = lowTemperature;
        }

        public Set<String> getKeys() { return keys; }
        public String getDate() { return date; }
        public Double getTemperature() { return temperature; }
        public Integer getNativeIcon() { return nativeIcon; }
        public Integer getConvertedIcon() { return convertedIcon; }
        public Double getHighTemperature() { return highTemperature; }
        public Double getLowTemperature() { return lowTemperature; }
    }

    public static final class HourlyWeatherInfo {
        private final Set<String> keys;
        private final String date;
        private final String hour;
        private final Integer temperature;
        private final Integer nativeIcon;
        private final Integer convertedIcon;
        private final Integer rainForecast;
        private final Integer windSpeed;
        private final Double highTemperature;
        private final Double lowTemperature;

        private HourlyWeatherInfo(final Set<String> keys, final String date, final String hour,
                                  final Integer temperature, final Integer nativeIcon,
                                  final Integer convertedIcon, final Integer rainForecast,
                                  final Integer windSpeed, final Double highTemperature,
                                  final Double lowTemperature) {
            this.keys = keys;
            this.date = date;
            this.hour = hour;
            this.temperature = temperature;
            this.nativeIcon = nativeIcon;
            this.convertedIcon = convertedIcon;
            this.rainForecast = rainForecast;
            this.windSpeed = windSpeed;
            this.highTemperature = highTemperature;
            this.lowTemperature = lowTemperature;
        }

        public Set<String> getKeys() { return keys; }
        public String getDate() { return date; }
        public String getHour() { return hour; }
        public Integer getTemperature() { return temperature; }
        public Integer getNativeIcon() { return nativeIcon; }
        public Integer getConvertedIcon() { return convertedIcon; }
        public Integer getRainForecast() { return rainForecast; }
        public Integer getWindSpeed() { return windSpeed; }
        public Double getHighTemperature() { return highTemperature; }
        public Double getLowTemperature() { return lowTemperature; }
    }

    public static final class WeatherAcknowledgement {
        private final Set<String> topLevelKeys;
        private final long ackSequence;

        private WeatherAcknowledgement(final Set<String> topLevelKeys, final long ackSequence) {
            this.topLevelKeys = topLevelKeys;
            this.ackSequence = ackSequence;
        }

        public Set<String> getTopLevelKeys() { return topLevelKeys; }
        public long getAckSequence() { return ackSequence; }
    }
}
