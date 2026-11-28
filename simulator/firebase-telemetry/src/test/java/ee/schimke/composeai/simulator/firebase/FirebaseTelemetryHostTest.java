package ee.schimke.composeai.simulator.firebase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class FirebaseTelemetryHostTest {

  @Before
  public void reset() {
    FirebaseRemoteConfigHost.reset();
    FirebaseAnalyticsHost.clear();
    FirebaseCrashlyticsHost.clear();
  }

  @Test
  public void remoteConfigStoresUpdatesAndClears() throws Exception {
    FirebaseRemoteConfigHost host = new FirebaseRemoteConfigHost();
    JSONObject res =
        host.setValues(
            new JSONObject()
                .put("dark_mode", true)
                .put("max_items", 25)
                .put("ratio", 1.5)
                .put("banner", "Hi"),
            getClass().getClassLoader());
    assertEquals(4, res.getInt("count"));
    assertEquals("overrideAboveActivated", res.getString("precedence"));
    assertEquals("notInApp", res.getString("sdkStatus"));
    assertTrue(FirebaseRemoteConfigHost.hasOverride("dark_mode"));
    assertTrue(FirebaseRemoteConfigHost.getBooleanOverride("dark_mode"));
    assertEquals(25L, FirebaseRemoteConfigHost.getLongOverride("max_items"));
    assertEquals(1.5, FirebaseRemoteConfigHost.getDoubleOverride("ratio"), 0.0001);
    assertEquals("Hi", FirebaseRemoteConfigHost.getStringOverride("banner"));
    assertTrue(FirebaseRemoteConfigHost.keysByPrefix("max_").contains("max_items"));

    host.setValues(new JSONObject().put("banner", JSONObject.NULL), getClass().getClassLoader());
    assertFalse(FirebaseRemoteConfigHost.hasOverride("banner"));
    assertEquals(3, host.values().size());

    host.clear(getClass().getClassLoader());
    assertTrue(host.values().isEmpty());
  }

  @Test
  public void analyticsCapturesAndFiltersEventsAndProperties() throws Exception {
    FirebaseAnalyticsHost.setUserId("user-42");
    FirebaseAnalyticsHost.setUserProperty("plan", "pro");
    FirebaseAnalyticsHost.recordEvent("screen_view", Map.of("screen_name", "home"));
    FirebaseAnalyticsHost.recordEvent("purchase", Map.of("value", 9.99));

    JSONObject all = FirebaseAnalyticsHost.toJson(null);
    assertEquals(2, all.getInt("count"));
    assertEquals("user-42", all.getString("userId"));
    assertEquals("pro", all.getJSONObject("userProperties").getString("plan"));

    JSONObject filtered = FirebaseAnalyticsHost.toJson("purchase");
    assertEquals(1, filtered.getInt("count"));
    assertEquals(2, filtered.getInt("totalCount"));
    assertEquals("purchase", filtered.getJSONArray("events").getJSONObject(0).getString("name"));

    FirebaseAnalyticsHost.clear();
    assertNull(FirebaseAnalyticsHost.userId());
    assertTrue(FirebaseAnalyticsHost.events().isEmpty());
  }

  @Test
  public void crashlyticsCapturesExceptionsLogsAndKeys() throws Exception {
    FirebaseCrashlyticsHost.setUserId("u-7");
    FirebaseCrashlyticsHost.setCustomKey("build_flavor", "demo");
    FirebaseCrashlyticsHost.log("opened checkout");
    FirebaseCrashlyticsHost.recordException(new IllegalStateException("cart empty"));

    JSONObject json = FirebaseCrashlyticsHost.toJson();
    assertEquals("u-7", json.getString("userId"));
    assertEquals("demo", json.getJSONObject("customKeys").getString("build_flavor"));
    assertEquals(1, json.getJSONArray("logs").length());
    assertEquals(1, json.getInt("exceptionCount"));
    assertEquals(
        "java.lang.IllegalStateException",
        json.getJSONArray("exceptions").getJSONObject(0).getString("className"));
    assertEquals(
        "cart empty", json.getJSONArray("exceptions").getJSONObject(0).getString("message"));

    FirebaseCrashlyticsHost.clear();
    assertEquals(0, FirebaseCrashlyticsHost.toJson().getInt("exceptionCount"));
  }
}
