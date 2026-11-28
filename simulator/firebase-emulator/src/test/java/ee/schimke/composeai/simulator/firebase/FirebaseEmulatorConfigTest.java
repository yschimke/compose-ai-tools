package ee.schimke.composeai.simulator.firebase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class FirebaseEmulatorConfigTest {

  @Test
  public void parsesAutoAndHubSpecs() {
    FirebaseEmulatorConfig auto = FirebaseEmulatorConfig.parseSpec("auto", "my-project");
    assertTrue(auto.discoverHub());
    assertEquals("my-project", auto.projectId());
    assertEquals(new FirebaseEmulatorConfig.HostPort("127.0.0.1", 4400), auto.hub());
    assertTrue(auto.services().isEmpty());

    FirebaseEmulatorConfig hub = FirebaseEmulatorConfig.parseSpec("hub=localhost:4500", null);
    assertTrue(hub.discoverHub());
    assertEquals(new FirebaseEmulatorConfig.HostPort("localhost", 4500), hub.hub());
  }

  @Test
  public void parsesExplicitServiceListAndNormalizesHosts() {
    FirebaseEmulatorConfig config =
        FirebaseEmulatorConfig.parseSpec(
            "auth=10.0.2.2:9099,firestore=8080,rtdb=0.0.0.0:9000,storage=localhost:9199,functions=5001",
            "demo-app");
    assertFalse(config.discoverHub());
    assertEquals("demo-app", config.projectId());
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 9099),
        config.service(FirebaseEmulatorConfig.Service.AUTH));
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 8080),
        config.service(FirebaseEmulatorConfig.Service.FIRESTORE));
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 9000),
        config.service(FirebaseEmulatorConfig.Service.DATABASE));
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("localhost", 9199),
        config.service(FirebaseEmulatorConfig.Service.STORAGE));
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 5001),
        config.service(FirebaseEmulatorConfig.Service.FUNCTIONS));
  }

  @Test
  public void parsesFirebaseJsonAndHubResponseAndRoundTripsJson() throws Exception {
    JSONObject firebaseJson =
        new JSONObject(
            "{"
                + "\"emulators\":{"
                + "  \"hub\":{\"port\":4411},"
                + "  \"auth\":{\"host\":\"0.0.0.0\",\"port\":9099},"
                + "  \"firestore\":{\"port\":8081},"
                + "  \"storage\":{\"enabled\":false,\"port\":9199}"
                + "}"
                + "}");
    FirebaseEmulatorConfig parsed =
        FirebaseEmulatorConfig.fromFirebaseJson(firebaseJson, "demo-proj");
    assertEquals(new FirebaseEmulatorConfig.HostPort("127.0.0.1", 4411), parsed.hub());
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 9099),
        parsed.service(FirebaseEmulatorConfig.Service.AUTH));
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 8081),
        parsed.service(FirebaseEmulatorConfig.Service.FIRESTORE));
    assertNull(parsed.service(FirebaseEmulatorConfig.Service.STORAGE));

    FirebaseEmulatorConfig roundTripped = FirebaseEmulatorConfig.fromJson(parsed.toJson());
    assertEquals(parsed.projectId(), roundTripped.projectId());
    assertEquals(parsed.hub(), roundTripped.hub());
    assertEquals(parsed.services(), roundTripped.services());

    JSONObject hubResp =
        new JSONObject(
            "{"
                + "\"auth\":{\"name\":\"auth\",\"host\":\"127.0.0.1\",\"port\":9099},"
                + "\"database\":{\"name\":\"database\",\"host\":\"::\",\"port\":9000}"
                + "}");
    FirebaseEmulatorConfig discovered =
        FirebaseEmulatorConfig.fromHubResponse(
            hubResp, new FirebaseEmulatorConfig.HostPort("127.0.0.1", 4400));
    assertNotNull(discovered.service(FirebaseEmulatorConfig.Service.AUTH));
    assertEquals(
        new FirebaseEmulatorConfig.HostPort("127.0.0.1", 9000),
        discovered.service(FirebaseEmulatorConfig.Service.DATABASE));
  }

  @Test
  public void rejectsUnknownService() {
    try {
      FirebaseEmulatorConfig.parseSpec("bogus=1234", null);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("bogus"));
    }
  }
}
