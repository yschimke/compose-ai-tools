package ee.schimke.composeai.simulator.firebase;

import static org.junit.Assert.assertEquals;

import java.util.EnumMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class FirebaseEmulatorWiringTest {

  @Test
  public void wiresReflectiveSdksAndReadsAppOptions() throws Exception {
    com.google.firebase.auth.FirebaseAuth.reset();
    com.google.firebase.firestore.FirebaseFirestore.reset();

    ClassLoader loader = getClass().getClassLoader();
    JSONObject opts = FirebaseEmulatorWiring.readAppOptions(null, loader);
    assertEquals("test-firebase-project", opts.getString("projectId"));
    assertEquals("1:123:android:abc", opts.getString("applicationId"));

    Map<FirebaseEmulatorConfig.Service, FirebaseEmulatorConfig.HostPort> services =
        new EnumMap<>(FirebaseEmulatorConfig.Service.class);
    services.put(
        FirebaseEmulatorConfig.Service.AUTH,
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 9099));
    services.put(
        FirebaseEmulatorConfig.Service.FIRESTORE,
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 8080));
    services.put(
        FirebaseEmulatorConfig.Service.STORAGE,
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 9199));
    FirebaseEmulatorConfig config =
        new FirebaseEmulatorConfig("test-firebase-project", null, false, services);

    JSONObject first = FirebaseEmulatorWiring.wireAll(null, loader, config);
    assertEquals("wired", first.getJSONObject("auth").getString("status"));
    assertEquals("127.0.0.1:9099", com.google.firebase.auth.FirebaseAuth.wiredTo);
    assertEquals("wired", first.getJSONObject("firestore").getString("status"));
    assertEquals("127.0.0.1:8080", com.google.firebase.firestore.FirebaseFirestore.wiredTo);
    assertEquals("notInApp", first.getJSONObject("storage").getString("status"));

    // Second call simulates an app that already called useEmulator() -> alreadyConfigured.
    JSONObject second = FirebaseEmulatorWiring.wireAll(null, loader, config);
    assertEquals("alreadyConfigured", second.getJSONObject("firestore").getString("status"));
  }

  @Test
  public void wiresRegionalFunctionsInstancesIdempotently() throws Exception {
    com.google.firebase.functions.FirebaseFunctions.reset();
    FirebaseEmulatorWiring.setActiveFunctionsEndpoint(null);

    Map<FirebaseEmulatorConfig.Service, FirebaseEmulatorConfig.HostPort> services =
        new EnumMap<>(FirebaseEmulatorConfig.Service.class);
    services.put(
        FirebaseEmulatorConfig.Service.FUNCTIONS,
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 5001));
    FirebaseEmulatorConfig config =
        new FirebaseEmulatorConfig("test-firebase-project", null, false, services);

    JSONObject res = FirebaseEmulatorWiring.wireAll(null, getClass().getClassLoader(), config);
    assertEquals("wired", res.getJSONObject("functions").getString("status"));

    com.google.firebase.functions.FirebaseFunctions eu =
        com.google.firebase.functions.FirebaseFunctions.getInstance(
            com.google.firebase.FirebaseApp.getInstance(), "europe-west1");
    FirebaseEmulatorWiring.wireFunctionsInstance(eu);
    FirebaseEmulatorWiring.wireFunctionsInstance(eu);

    assertEquals("127.0.0.1:5001", eu.wiredTo);
    assertEquals(1, eu.wireCalls);
  }
}
