package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WatchStateTest {

  private WatchState state;
  private TimeZone originalTz;

  @Before
  public void setUp() {
    originalTz = TimeZone.getDefault();
    state = new WatchState(null, null);
  }

  @After
  public void tearDown() {
    TimeZone.setDefault(originalTz);
    WatchState.resetCurrent();
  }

  @Test
  public void clockCommandPinsIsoTimeFrozenRateTimezoneAndFormat() throws Exception {
    JSONObject reply =
        state.clockCommand(
            new JSONObject()
                .put("time", "2026-06-15T10:08:30Z")
                .put("frozen", true)
                .put("timeZone", "America/New_York")
                .put("is24Hour", true));
    assertTrue(state.isClockFrozen());
    assertEquals(1781518110000L, state.nowMillis());
    assertEquals("America/New_York", state.timeZone().getID());
    assertTrue(state.is24Hour());
    assertTrue(state.isDst());
    assertEquals(1781518110000L, reply.getLong("timeMillis"));

    // Frozen clock stays pinned even as time passes.
    assertEquals(1781518110000L, state.nowMillis());

    // HH:mm shorthand resolves in the configured watch timezone.
    state.clockCommand(new JSONObject().put("time", "22:45"));
    java.time.ZonedDateTime zdt =
        java.time.Instant.ofEpochMilli(state.nowMillis())
            .atZone(java.time.ZoneId.of("America/New_York"));
    assertEquals(22, zdt.getHour());
    assertEquals(45, zdt.getMinute());
  }

  @Test
  public void weatherCommandParsesConditionNamesCodesAndForecastArrays() throws Exception {
    JSONObject reply =
        state.weatherCommand(
            new JSONObject()
                .put("available", true)
                .put("condition", "HEAVY_RAIN")
                .put("temperature", 14.5)
                .put("temperatureHigh", 18.0)
                .put("temperatureLow", 11.0)
                .put("chanceOfPrecipitation", 85)
                .put("uvIndex", 3)
                .put("temperatureUnit", "C")
                .put(
                    "hours",
                    new JSONArray()
                        .put(new JSONObject().put("condition", "CLOUDY").put("temperature", 15.0))
                        .put(new JSONObject().put("condition", 1).put("temperature", 17.5)))
                .put(
                    "days",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("condition", "CLEAR")
                                .put("temperatureHigh", 21.0)
                                .put("temperatureLow", 12.0))));

    assertTrue(state.weatherAvailable());
    assertEquals(4, state.weatherCondition()); // HEAVY_RAIN -> 4
    assertEquals(14.5f, state.weatherTemperature(), 0.01f);
    assertEquals(18.0f, state.weatherTemperatureHigh(), 0.01f);
    assertEquals(11.0f, state.weatherTemperatureLow(), 0.01f);
    assertEquals(85, state.weatherPrecipitation());
    assertEquals(3, state.weatherUvIndex());
    assertTrue(state.weatherCelsius());
    assertEquals(2, state.hourlyWeather().size());
    assertEquals(2, state.hourlyWeather().get(Integer.valueOf(1)).condition); // CLOUDY -> 2
    assertEquals(1, state.hourlyWeather().get(Integer.valueOf(2)).condition);
    assertEquals(1, state.dailyWeather().size());
    assertEquals(21.0f, state.dailyWeather().get(Integer.valueOf(1)).temperatureHigh, 0.01f);
    assertEquals(4, reply.getInt("condition"));
  }

  @Test
  public void accelAndAmbientDisplayStateRoundTripCleanly() throws Exception {
    state.accelCommand(new JSONObject().put("x", 12.5).put("y", -7.25));
    assertEquals(12.5f, state.accelAngleX(), 0.001f);
    assertEquals(-7.25f, state.accelAngleY(), 0.001f);

    state.syncDisplay(true, true, false);
    assertTrue(state.ambient());
    assertTrue(state.burnIn());
    assertFalse(state.lowBit());

    JSONObject snapshot = state.toJson();
    assertEquals(true, snapshot.getBoolean("ambient"));
    assertEquals(true, snapshot.getBoolean("burnIn"));
    assertEquals(12.5, snapshot.getDouble("accelAngleX"), 0.01);
  }
}
