package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowSensorManager;

/**
 * Central state model for simulated Wear OS watch inputs: clock (pinned or scaled), ambient and
 * device display capabilities, health metrics, battery state, unread notifications, weather, and
 * wrist accelerometer angles.
 *
 * <p>Every renderer path ({@link WatchFaceClient} for {@code DeclarativeWatchFaceRuntime} and
 * {@code androidx.wear.watchface}, {@link DwfWatchFaceClient} for compiled Watch Face Format
 * RemoteCompose documents, and {@link WatchFaceView} for the built-in face) reads from one {@code
 * WatchState} instance so agent mutations apply uniformly.
 */
final class WatchState {

  static final class HourlyWeather {
    final int condition;
    final boolean isDay;
    final float temperature;
    final int uvIndex;

    HourlyWeather(int condition, boolean isDay, float temperature, int uvIndex) {
      this.condition = condition;
      this.isDay = isDay;
      this.temperature = temperature;
      this.uvIndex = uvIndex;
    }
  }

  static final class DailyWeather {
    final int conditionDay;
    final int conditionNight;
    final float temperatureHigh;
    final float temperatureLow;
    final int chanceOfPrecipitation;
    final int chanceOfPrecipitationNight;
    final int uvIndex;

    DailyWeather(
        int conditionDay,
        int conditionNight,
        float temperatureHigh,
        float temperatureLow,
        int chanceOfPrecipitation,
        int chanceOfPrecipitationNight,
        int uvIndex) {
      this.conditionDay = conditionDay;
      this.conditionNight = conditionNight;
      this.temperatureHigh = temperatureHigh;
      this.temperatureLow = temperatureLow;
      this.chanceOfPrecipitation = chanceOfPrecipitation;
      this.chanceOfPrecipitationNight = chanceOfPrecipitationNight;
      this.uvIndex = uvIndex;
    }
  }

  private static volatile WatchState active;

  private final Application app;
  private final Navigator navigator;
  private final SystemData systemData;
  private final List<Runnable> listeners = new ArrayList<>();

  // Clock
  private Long baseSimMillis;
  private long baseUptimeMillis;
  private double clockRate = 1.0;
  private boolean clockFrozen;
  private Boolean is24HourOverride;

  // Ambient / DeviceConfig
  private boolean ambient;
  private boolean burnIn = true;
  private boolean lowBit;

  // Battery & Notifications
  private int batteryPercent = 100;
  private boolean batteryCharging;
  private boolean batteryLow;
  private boolean powerSave;
  private int unreadNotifications;

  // Health extra
  private float stepGoal = 10_000f;

  // Accelerometer / tilt angles
  private float accelAngleX;
  private float accelAngleY;

  // Weather (seeded to match DwfExpressionCompiler defaults)
  private boolean weatherAvailable = true;
  private boolean weatherIsDay = true;
  private int weatherCondition = 1; // CLEAR
  private String weatherConditionName = "Clear";
  private float weatherTemperature = 21f;
  private float weatherTemperatureHigh = 24f;
  private float weatherTemperatureLow = 16f;
  private boolean explicitTempHigh;
  private boolean explicitTempLow;
  private boolean weatherCelsius = true;
  private int weatherPrecipitation = 10;
  private int weatherUvIndex = 3;
  private String weatherLastUpdated = "Updated just now";
  private final Map<Integer, HourlyWeather> hourlyWeather = new LinkedHashMap<>();
  private final Map<Integer, DailyWeather> dailyWeather = new LinkedHashMap<>();

  WatchState(Application app, SystemData systemData) {
    this(app, null, systemData);
  }

  WatchState(Application app, Navigator navigator, SystemData systemData) {
    this.app = app;
    this.navigator = navigator;
    this.systemData = systemData;
    active = this;
  }

  static WatchState current() {
    return active;
  }

  static void resetCurrent() {
    active = null;
  }

  static long currentTimeMillis() {
    WatchState ws = active;
    return ws != null ? ws.nowMillis() : System.currentTimeMillis();
  }

  void onChange(Runnable listener) {
    listeners.add(listener);
  }

  void notifyTimezoneChanged() {
    notifyChanged();
  }

  private void notifyChanged() {
    for (Runnable listener : new ArrayList<>(listeners)) {
      listener.run();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Clock

  private static long safeUptimeMillis() {
    try {
      return SystemClock.uptimeMillis();
    } catch (Throwable ignored) {
      return System.nanoTime() / 1_000_000L;
    }
  }

  long nowMillis() {
    if (baseSimMillis == null) {
      return System.currentTimeMillis();
    }
    if (clockFrozen || clockRate == 0.0) {
      return baseSimMillis.longValue();
    }
    long elapsedUptime = Math.max(0L, safeUptimeMillis() - baseUptimeMillis);
    return baseSimMillis.longValue() + Math.round(elapsedUptime * clockRate);
  }

  boolean isClockPinnedOrScaled() {
    return baseSimMillis != null;
  }

  boolean isClockFrozen() {
    return baseSimMillis != null && (clockFrozen || clockRate == 0.0);
  }

  double clockRate() {
    return clockRate;
  }

  void pinTimeMillis(Long timeMillis) {
    if (timeMillis == null) {
      baseSimMillis = null;
      clockFrozen = false;
      clockRate = 1.0;
    } else {
      baseSimMillis = timeMillis;
      baseUptimeMillis = safeUptimeMillis();
      clockFrozen = true;
    }
  }

  TimeZone timeZone() {
    return TimeZone.getDefault();
  }

  ZoneId zoneId() {
    return timeZone().toZoneId();
  }

  boolean isDst() {
    return timeZone().inDaylightTime(new Date(nowMillis()));
  }

  boolean is24Hour() {
    if (is24HourOverride != null) return is24HourOverride.booleanValue();
    if (app == null) return true;
    String setting =
        Settings.System.getString(app.getContentResolver(), Settings.System.TIME_12_24);
    return setting == null || !"12".equals(setting);
  }

  JSONObject clockCommand(JSONObject c) throws JSONException {
    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      host.clearFixedTimeOverride();
    }
    if (c.optBoolean("reset", false)) {
      baseSimMillis = null;
      clockFrozen = false;
      clockRate = 1.0;
      is24HourOverride = null;
      notifyChanged();
      return describeClock();
    }
    TimeZone changedZone = null;
    if (c.has("timeZone") && !c.isNull("timeZone")) {
      changedZone = parseTimezone(c.getString("timeZone"));
      TimeZone.setDefault(changedZone);
    }
    Boolean changed24Hour = null;
    if (c.has("is24Hour") && !c.isNull("is24Hour")) {
      boolean v = c.getBoolean("is24Hour");
      is24HourOverride = Boolean.valueOf(v);
      changed24Hour = is24HourOverride;
      if (app != null) {
        Settings.System.putString(
            app.getContentResolver(), Settings.System.TIME_12_24, v ? "24" : "12");
      }
    }
    Long resolvedMillis = null;
    if (c.has("timeMillis")) {
      if (c.isNull("timeMillis")) {
        baseSimMillis = null;
        clockFrozen = false;
      } else {
        resolvedMillis = Long.valueOf(c.getLong("timeMillis"));
      }
    } else if (c.has("time") && !c.isNull("time")) {
      resolvedMillis = Long.valueOf(parseTimeSpec(c.getString("time"), nowMillis(), zoneId()));
    } else if (c.has("hour") || c.has("minute") || c.has("second")) {
      ZonedDateTime cur = Instant.ofEpochMilli(nowMillis()).atZone(zoneId());
      int h = c.optInt("hour", cur.getHour());
      int m = c.optInt("minute", cur.getMinute());
      int s = c.optInt("second", cur.getSecond());
      int ms = c.optInt("millisecond", 0);
      resolvedMillis =
          Long.valueOf(
              cur.withHour(h)
                  .withMinute(m)
                  .withSecond(s)
                  .withNano(ms * 1_000_000)
                  .toInstant()
                  .toEpochMilli());
    }
    if (resolvedMillis != null) {
      baseSimMillis = resolvedMillis;
      baseUptimeMillis = safeUptimeMillis();
      if (!c.has("frozen") && !c.has("rate")) {
        clockFrozen = true;
      }
    }
    if (c.has("rate") && !c.isNull("rate")) {
      double newRate = c.getDouble("rate");
      if (newRate < 0) throw new IllegalArgumentException("clock rate must be >= 0");
      long current = nowMillis();
      baseSimMillis = Long.valueOf(current);
      baseUptimeMillis = safeUptimeMillis();
      clockRate = newRate;
      if (!c.has("frozen")) {
        clockFrozen = (newRate == 0.0);
      }
    }
    if (c.has("frozen") && !c.isNull("frozen")) {
      long current = nowMillis();
      baseSimMillis = Long.valueOf(current);
      baseUptimeMillis = safeUptimeMillis();
      clockFrozen = c.getBoolean("frozen");
    }
    notifyChanged();
    if (changedZone != null && (navigator != null || app != null)) {
      DeviceState.broadcastTimezoneChanged(changedZone, app, navigator);
    }
    if (changed24Hour != null && (navigator != null || app != null)) {
      Intent timeChanged =
          new Intent(Intent.ACTION_TIME_CHANGED)
              .putExtra(
                  "android.intent.extra.TIME_PREF_24_HOUR_FORMAT",
                  changed24Hour.booleanValue() ? 1 : 0);
      if (navigator != null) {
        navigator.broadcast(timeChanged);
      } else {
        app.sendBroadcast(timeChanged);
      }
    }
    return describeClock();
  }

  static TimeZone parseTimezone(String id) {
    TimeZone zone = TimeZone.getTimeZone(id);
    if (!zone.getID().equals(id) && !"GMT".equals(id) && !"UTC".equals(id)) {
      throw new IllegalArgumentException("unknown time zone " + id);
    }
    return zone;
  }

  static long parseTimeSpec(String raw, long referenceMillis, ZoneId zone) {
    String s = raw.trim();
    try {
      return Instant.parse(s).toEpochMilli();
    } catch (RuntimeException ignored) {
    }
    try {
      return OffsetDateTime.parse(s).toInstant().toEpochMilli();
    } catch (RuntimeException ignored) {
    }
    try {
      LocalTime lt = LocalTime.parse(s);
      LocalDate date = Instant.ofEpochMilli(referenceMillis).atZone(zone).toLocalDate();
      return ZonedDateTime.of(date, lt, zone).toInstant().toEpochMilli();
    } catch (RuntimeException ignored) {
    }
    throw new IllegalArgumentException(
        "could not parse time '" + raw + "' (use ISO-8601 instant or HH:mm[:ss])");
  }

  JSONObject describeClock() {
    long now = nowMillis();
    ZonedDateTime zdt = Instant.ofEpochMilli(now).atZone(zoneId());
    return new Protocol.Json()
        .put("timeMillis", now)
        .put("iso", zdt.toOffsetDateTime().toString())
        .put("timeZone", timeZone().getID())
        .put("is24Hour", is24Hour())
        .put("isDst", isDst())
        .put("pinned", baseSimMillis != null)
        .put("frozen", isClockFrozen())
        .put("rate", clockRate)
        .object();
  }

  // ---------------------------------------------------------------------------------------------
  // Display / Battery / Notifications / Health / Sensors

  void syncDisplay(boolean ambient, boolean burnIn, boolean lowBit) {
    boolean changed =
        this.ambient != ambient || this.burnIn != burnIn || this.lowBit != lowBit;
    this.ambient = ambient;
    this.burnIn = burnIn;
    this.lowBit = lowBit;
    if (changed) notifyChanged();
  }

  boolean ambient() {
    return ambient;
  }

  boolean burnIn() {
    return burnIn;
  }

  boolean lowBit() {
    return lowBit;
  }

  void syncBattery(int level, boolean charging, boolean low, boolean powerSave) {
    int clamped = Math.max(0, Math.min(100, level));
    boolean effectiveLow = low || (!charging && clamped <= 15);
    boolean changed =
        this.batteryPercent != clamped
            || this.batteryCharging != charging
            || this.batteryLow != effectiveLow
            || this.powerSave != powerSave;
    this.batteryPercent = clamped;
    this.batteryCharging = charging;
    this.batteryLow = effectiveLow;
    this.powerSave = powerSave;
    if (changed) notifyChanged();
  }

  int batteryPercent() {
    return batteryPercent;
  }

  boolean batteryCharging() {
    return batteryCharging;
  }

  boolean batteryLow() {
    return batteryLow;
  }

  boolean powerSave() {
    return powerSave;
  }

  void syncNotifications(int count) {
    int clamped = Math.max(0, count);
    if (this.unreadNotifications == clamped) return;
    this.unreadNotifications = clamped;
    notifyChanged();
  }

  int unreadNotifications() {
    return unreadNotifications;
  }

  void setStepGoal(float goal) {
    if (goal > 0f) {
      this.stepGoal = goal;
    }
  }

  float stepGoal() {
    return stepGoal;
  }

  float heartRate() {
    return systemData == null ? 72f : systemData.heartRate();
  }

  int steps() {
    return systemData == null ? 4200 : systemData.steps();
  }

  float distanceMeters() {
    return systemData == null ? 3150f : systemData.distanceMeters();
  }

  float calories() {
    return systemData == null ? 180f : systemData.calories();
  }

  float floors() {
    return systemData == null ? 4f : systemData.floors();
  }

  void onHealthChanged() {
    deliverHealthSensorEvents();
    notifyChanged();
  }

  private void deliverHealthSensorEvents() {
    if (app == null) return;
    try {
      SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
      if (sm == null) return;
      ShadowSensorManager shadow = Shadow.extract(sm);
      deliverSensorValue(sm, shadow, Sensor.TYPE_HEART_RATE, heartRate());
      deliverSensorValue(sm, shadow, Sensor.TYPE_STEP_COUNTER, (float) steps());
    } catch (Throwable ignored) {
      // Best effort: apps without registered sensor listeners ignore this.
    }
  }

  private static void deliverSensorValue(
      SensorManager sm, ShadowSensorManager shadow, int sensorType, float value) {
    Sensor sensor = sm.getDefaultSensor(sensorType);
    if (sensor == null) return;
    List<SensorEventListener> registered = shadow.getListeners();
    if (registered == null || registered.isEmpty()) return;
    SensorEvent event =
        SensorEventBuilder.newBuilder()
            .setSensor(sensor)
            .setTimestamp(SystemClock.elapsedRealtimeNanos())
            .setValues(new float[] {value})
            .build();
    shadow.sendSensorEventToListeners(event);
  }

  // ---------------------------------------------------------------------------------------------
  // Accelerometer & Weather

  float accelAngleX() {
    return accelAngleX;
  }

  float accelAngleY() {
    return accelAngleY;
  }

  JSONObject accelCommand(JSONObject c) throws JSONException {
    if (c.has("x")) accelAngleX = (float) c.getDouble("x");
    else if (c.has("angleX")) accelAngleX = (float) c.getDouble("angleX");
    else if (c.has("tiltX")) accelAngleX = (float) c.getDouble("tiltX");

    if (c.has("y")) accelAngleY = (float) c.getDouble("y");
    else if (c.has("angleY")) accelAngleY = (float) c.getDouble("angleY");
    else if (c.has("tiltY")) accelAngleY = (float) c.getDouble("tiltY");

    notifyChanged();
    return new Protocol.Json().put("angleX", accelAngleX).put("angleY", accelAngleY).object();
  }

  boolean weatherAvailable() {
    return weatherAvailable;
  }

  boolean weatherIsDay() {
    return weatherIsDay;
  }

  int weatherCondition() {
    return weatherCondition;
  }

  String weatherConditionName() {
    return weatherConditionName;
  }

  float weatherTemperature() {
    return weatherTemperature;
  }

  float weatherTemperatureHigh() {
    return weatherTemperatureHigh;
  }

  float weatherTemperatureLow() {
    return weatherTemperatureLow;
  }

  boolean weatherCelsius() {
    return weatherCelsius;
  }

  int weatherPrecipitation() {
    return weatherPrecipitation;
  }

  int weatherUvIndex() {
    return weatherUvIndex;
  }

  String weatherLastUpdated() {
    return weatherLastUpdated;
  }

  Map<Integer, HourlyWeather> hourlyWeather() {
    return hourlyWeather;
  }

  Map<Integer, DailyWeather> dailyWeather() {
    return dailyWeather;
  }

  JSONObject weatherCommand(JSONObject c) throws JSONException {
    if (c.has("available")) weatherAvailable = c.getBoolean("available");
    if (c.has("isDay")) weatherIsDay = c.getBoolean("isDay");
    if (c.has("condition") && !c.isNull("condition")) {
      Object raw = c.get("condition");
      weatherCondition = parseWeatherCondition(raw);
      if (!c.has("conditionName")) {
        weatherConditionName = defaultConditionName(weatherCondition);
      }
    }
    if (c.has("conditionName") && !c.isNull("conditionName")) {
      weatherConditionName = c.getString("conditionName");
    }
    boolean tempChanged = false;
    if (c.has("temperature")) {
      weatherTemperature = (float) c.getDouble("temperature");
      tempChanged = true;
    } else if (c.has("tempC")) {
      weatherTemperature = (float) c.getDouble("tempC");
      weatherCelsius = true;
      tempChanged = true;
    } else if (c.has("tempF")) {
      weatherTemperature = (float) c.getDouble("tempF");
      weatherCelsius = false;
      tempChanged = true;
    }
    if (c.has("temperatureHigh")) {
      weatherTemperatureHigh = (float) c.getDouble("temperatureHigh");
      explicitTempHigh = true;
    } else if (c.has("high")) {
      weatherTemperatureHigh = (float) c.getDouble("high");
      explicitTempHigh = true;
    } else if (tempChanged && !explicitTempHigh) {
      weatherTemperatureHigh = weatherTemperature + 3f;
    }
    if (c.has("temperatureLow")) {
      weatherTemperatureLow = (float) c.getDouble("temperatureLow");
      explicitTempLow = true;
    } else if (c.has("low")) {
      weatherTemperatureLow = (float) c.getDouble("low");
      explicitTempLow = true;
    } else if (tempChanged && !explicitTempLow) {
      weatherTemperatureLow = weatherTemperature - 6f;
    }

    if (c.has("temperatureUnit") && !c.isNull("temperatureUnit")) {
      Object u = c.get("temperatureUnit");
      if (u instanceof Number) {
        weatherCelsius = ((Number) u).intValue() != 0;
      } else {
        String us = u.toString().trim().toUpperCase(Locale.ROOT);
        weatherCelsius = !us.startsWith("F") && !"0".equals(us);
      }
    }
    if (c.has("chanceOfPrecipitation")) {
      weatherPrecipitation = c.getInt("chanceOfPrecipitation");
    }
    if (c.has("uvIndex")) weatherUvIndex = c.getInt("uvIndex");
    if (c.has("lastUpdatedText") && !c.isNull("lastUpdatedText")) {
      weatherLastUpdated = c.getString("lastUpdatedText");
    }

    JSONArray hours = c.optJSONArray("hours");
    if (hours == null) hours = c.optJSONArray("hourly");
    if (hours != null) {
      hourlyWeather.clear();
      for (int i = 0; i < hours.length(); i++) {
        JSONObject h = hours.getJSONObject(i);
        int offset = h.optInt("hour", i + 1);
        int cond = h.has("condition") ? parseWeatherCondition(h.get("condition")) : weatherCondition;
        boolean day = h.optBoolean("isDay", weatherIsDay);
        float temp = (float) h.optDouble("temperature", weatherTemperature);
        int uv = h.optInt("uvIndex", weatherUvIndex);
        hourlyWeather.put(Integer.valueOf(offset), new HourlyWeather(cond, day, temp, uv));
      }
    }

    JSONArray days = c.optJSONArray("days");
    if (days == null) days = c.optJSONArray("daily");
    if (days != null) {
      dailyWeather.clear();
      for (int i = 0; i < days.length(); i++) {
        JSONObject d = days.getJSONObject(i);
        int offset = d.optInt("day", i + 1);
        int condDay =
            d.has("conditionDay")
                ? parseWeatherCondition(d.get("conditionDay"))
                : (d.has("condition") ? parseWeatherCondition(d.get("condition")) : weatherCondition);
        int condNight =
            d.has("conditionNight") ? parseWeatherCondition(d.get("conditionNight")) : condDay;
        float high =
            (float) d.optDouble("temperatureHigh", d.optDouble("high", weatherTemperatureHigh));
        float low =
            (float) d.optDouble("temperatureLow", d.optDouble("low", weatherTemperatureLow));
        int precip = d.optInt("chanceOfPrecipitation", weatherPrecipitation);
        int precipNight = d.optInt("chanceOfPrecipitationNight", precip);
        int uv = d.optInt("uvIndex", weatherUvIndex);
        dailyWeather.put(
            Integer.valueOf(offset),
            new DailyWeather(condDay, condNight, high, low, precip, precipNight, uv));
      }
    }

    notifyChanged();
    return describeWeather();
  }

  static int parseWeatherCondition(Object raw) {
    if (raw instanceof Number) return ((Number) raw).intValue();
    String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    switch (s) {
      case "UNKNOWN":
        return 0;
      case "CLEAR":
      case "FAIR":
        return 1;
      case "CLOUDY":
        return 2;
      case "FOG":
        return 3;
      case "HEAVY_RAIN":
        return 4;
      case "HEAVY_SNOW":
        return 5;
      case "RAIN":
        return 6;
      case "SNOW":
        return 7;
      case "SUNNY":
        return 8;
      case "THUNDERSTORM":
        return 9;
      case "SLEET":
        return 10;
      case "LIGHT_SNOW":
        return 11;
      case "LIGHT_RAIN":
        return 12;
      case "MIST":
        return 13;
      case "PARTLY_CLOUDY":
        return 14;
      case "WINDY":
        return 15;
      default:
        try {
          return Integer.parseInt(s);
        } catch (NumberFormatException e) {
          return 0;
        }
    }
  }

  static String defaultConditionName(int code) {
    switch (code) {
      case 1:
        return "Clear";
      case 2:
        return "Cloudy";
      case 3:
        return "Fog";
      case 4:
        return "Heavy rain";
      case 5:
        return "Heavy snow";
      case 6:
        return "Rain";
      case 7:
        return "Snow";
      case 8:
        return "Sunny";
      case 9:
        return "Thunderstorm";
      case 10:
        return "Sleet";
      case 11:
        return "Light snow";
      case 12:
        return "Light rain";
      case 13:
        return "Mist";
      case 14:
        return "Partly cloudy";
      case 15:
        return "Windy";
      default:
        return "Unknown";
    }
  }

  JSONObject describeWeather() {
    return new Protocol.Json()
        .put("available", weatherAvailable)
        .put("isDay", weatherIsDay)
        .put("condition", weatherCondition)
        .put("conditionName", weatherConditionName)
        .put("temperature", weatherTemperature)
        .put("temperatureHigh", weatherTemperatureHigh)
        .put("temperatureLow", weatherTemperatureLow)
        .put("temperatureUnit", weatherCelsius ? "C" : "F")
        .put("chanceOfPrecipitation", weatherPrecipitation)
        .put("uvIndex", weatherUvIndex)
        .put("lastUpdatedText", weatherLastUpdated)
        .put("hourlyCount", hourlyWeather.size())
        .put("dailyCount", dailyWeather.size())
        .object();
  }

  JSONObject toJson() {
    return new Protocol.Json()
        .put("clock", describeClock())
        .put("ambient", ambient)
        .put("burnIn", burnIn)
        .put("lowBit", lowBit)
        .put("heartRate", heartRate())
        .put("steps", steps())
        .put("stepGoal", stepGoal)
        .put("distanceMeters", distanceMeters())
        .put("calories", calories())
        .put("floors", floors())
        .put("batteryPercent", batteryPercent)
        .put("batteryCharging", batteryCharging)
        .put("batteryLow", batteryLow)
        .put("powerSave", powerSave)
        .put("unreadNotifications", unreadNotifications)
        .put("accelAngleX", accelAngleX)
        .put("accelAngleY", accelAngleY)
        .put("weather", describeWeather())
        .object();
  }
}
