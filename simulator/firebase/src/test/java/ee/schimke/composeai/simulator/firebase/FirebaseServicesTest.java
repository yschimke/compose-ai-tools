package ee.schimke.composeai.simulator.firebase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class FirebaseServicesTest {

  @Before
  public void reset() {
    FirebaseRemoteConfigHost.reset();
    FirebaseAnalyticsHost.clear();
    FirebaseCrashlyticsHost.clear();
  }

  @Test
  public void handlesControlChannelCommandsAcrossAllSubsystems() throws Exception {
    FirebaseServices services = new FirebaseServices(null);

    JSONObject status = services.handleCommand(new JSONObject().put("action", "status"));
    assertEquals("demo-simulator", status.getString("projectId"));
    assertTrue(status.getJSONObject("fcm").getString("token").startsWith("sim-fcm-token:"));

    JSONObject rotated =
        services.handleCommand(
            new JSONObject().put("action", "fcmRotateToken").put("token", "tok-99"));
    assertEquals("tok-99", rotated.getString("token"));

    JSONObject rc =
        services.handleCommand(
            new JSONObject()
                .put("action", "remoteConfigSet")
                .put("values", new JSONObject().put("promo_enabled", true)));
    assertEquals(1, rc.getInt("count"));

    FirebaseAnalyticsHost.recordEvent("app_open", java.util.Map.of("source", "push"));
    JSONObject analytics =
        services.handleCommand(new JSONObject().put("action", "analyticsEvents"));
    assertEquals(1, analytics.getInt("count"));

    FirebaseCrashlyticsHost.log("step 1");
    JSONObject crash = services.handleCommand(new JSONObject().put("action", "crashlytics"));
    assertEquals(1, crash.getJSONArray("logs").length());
  }

  @Test
  public void derivesNamespaceFromDatabaseUrl() {
    assertEquals(
        "my-project",
        FirebaseServices.namespaceFromDatabaseUrl("https://my-project.firebaseio.com"));
    assertEquals(
        "my-project-default-rtdb",
        FirebaseServices.namespaceFromDatabaseUrl(
            "https://my-project-default-rtdb.europe-west1.firebasedatabase.app"));
    assertEquals(
        "custom-ns",
        FirebaseServices.namespaceFromDatabaseUrl("http://127.0.0.1:9000/?ns=custom-ns"));
    assertEquals(null, FirebaseServices.namespaceFromDatabaseUrl("http://127.0.0.1:9000"));
  }
}
