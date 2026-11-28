package ee.schimke.composeai.simulator.firebase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FirebaseEmulatorClientTest {

  private HttpServer server;
  private FirebaseEmulatorConfig.HostPort endpoint;
  private final List<String> seenRequests = new ArrayList<>();
  private final List<String> seenAuthHeaders = new ArrayList<>();

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String method = exchange.getRequestMethod();
          String override = exchange.getRequestHeaders().getFirst("X-HTTP-Method-Override");
          if (override != null) method = override;
          String path = exchange.getRequestURI().toString();
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          seenRequests.add(method + " " + path);
          if (auth != null) seenAuthHeaders.add(auth);
          byte[] reqBody = exchange.getRequestBody().readAllBytes();
          String bodyText = new String(reqBody, StandardCharsets.UTF_8);

          String response;
          if ("GET".equals(method) && "/emulators".equals(path)) {
            response =
                "{\"auth\":{\"host\":\"127.0.0.1\",\"port\":9099},"
                    + "\"firestore\":{\"host\":\"127.0.0.1\",\"port\":8080}}";
          } else if (path.contains("/accounts:query")) {
            response = "{\"userInfo\":[{\"localId\":\"u1\",\"email\":\"a@b.com\"}]}";
          } else if (path.endsWith("/accounts") && "POST".equals(method)) {
            response = "{\"localId\":\"u1\",\"email\":\"a@b.com\"}";
          } else if (path.endsWith("/oobCodes")) {
            response = "{\"oobCodes\":[{\"oobCode\":\"code-123\"}]}";
          } else if (path.endsWith("/verificationCodes")) {
            response = "{\"verificationCodes\":[{\"code\":\"654321\"}]}";
          } else if (path.contains("/documents/users/alice")) {
            response = bodyText.isEmpty() ? "{\"name\":\"users/alice\",\"fields\":{}}" : bodyText;
          } else if (path.contains(".json")) {
            response = bodyText.isEmpty() ? "{\"hello\":\"world\"}" : bodyText;
          } else {
            response = "{}";
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    endpoint = new FirebaseEmulatorConfig.HostPort("127.0.0.1", server.getAddress().getPort());
  }

  @After
  public void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  public void discoversHubAndExercisesRestAdminEndpoints() throws Exception {
    FirebaseEmulatorConfig discovered = FirebaseEmulatorClient.discoverHub(endpoint);
    assertEquals(9099, discovered.service(FirebaseEmulatorConfig.Service.AUTH).port);
    assertEquals(8080, discovered.service(FirebaseEmulatorConfig.Service.FIRESTORE).port);

    // Firestore clear, set, get, delete
    FirebaseEmulatorClient.clearFirestore(endpoint, "demo-app", null);
    JSONObject written =
        FirebaseEmulatorClient.setFirestoreDocument(
            endpoint,
            "demo-app",
            null,
            "users/alice",
            new JSONObject().put("name", "Alice").put("age", 30).put("active", true));
    JSONObject fields = written.getJSONObject("fields");
    assertEquals("Alice", fields.getJSONObject("name").getString("stringValue"));
    assertEquals("30", fields.getJSONObject("age").getString("integerValue"));
    assertTrue(fields.getJSONObject("active").getBoolean("booleanValue"));

    JSONObject doc =
        FirebaseEmulatorClient.getFirestoreDocument(endpoint, "demo-app", null, "users/alice");
    assertNotNull(doc);
    FirebaseEmulatorClient.deleteFirestoreDocument(endpoint, "demo-app", null, "users/alice");

    // Auth clear, create, list, oobCodes, verificationCodes
    FirebaseEmulatorClient.clearAuth(endpoint, "demo-app");
    JSONObject created =
        FirebaseEmulatorClient.createAuthUser(
            endpoint, "demo-app", "a@b.com", "secret", "Alice", "u1");
    assertEquals("u1", created.getString("localId"));
    assertEquals(
        1,
        FirebaseEmulatorClient.listAuthUsers(endpoint, "demo-app")
            .getJSONArray("userInfo")
            .length());
    assertEquals(
        "code-123",
        FirebaseEmulatorClient.listAuthOobCodes(endpoint, "demo-app")
            .getJSONArray("oobCodes")
            .getJSONObject(0)
            .getString("oobCode"));
    assertEquals(
        "654321",
        FirebaseEmulatorClient.listAuthVerificationCodes(endpoint, "demo-app")
            .getJSONArray("verificationCodes")
            .getJSONObject(0)
            .getString("code"));

    // Realtime Database set, get, clear
    assertEquals(
        "{\"count\":1}",
        FirebaseEmulatorClient.setDatabase(endpoint, "demo-app-default-rtdb", "stats", "{\"count\":1}"));
    assertEquals(
        "{\"hello\":\"world\"}",
        FirebaseEmulatorClient.getDatabase(endpoint, "demo-app-default-rtdb", "stats"));
    FirebaseEmulatorClient.clearDatabase(endpoint, "demo-app-default-rtdb");

    assertTrue(seenAuthHeaders.stream().allMatch("Bearer owner"::equals));
    assertTrue(
        seenRequests.contains("DELETE /emulator/v1/projects/demo-app/databases/%28default%29/documents"));
    assertTrue(seenRequests.contains("DELETE /emulator/v1/projects/demo-app/accounts"));
    assertTrue(seenRequests.contains("DELETE /.json?ns=demo-app-default-rtdb"));
  }
}
