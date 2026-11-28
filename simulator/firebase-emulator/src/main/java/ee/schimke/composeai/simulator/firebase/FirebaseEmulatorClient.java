package ee.schimke.composeai.simulator.firebase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure-JVM HTTP client for the real Firebase Local Emulator Suite REST APIs:
 *
 * <ul>
 *   <li>Emulator Hub discovery ({@code GET /emulators})
 *   <li>Cloud Firestore Emulator admin clear and document GET/PATCH/DELETE
 *   <li>Authentication Emulator admin clear, create user, list users, OOB &amp; SMS codes
 *   <li>Realtime Database Emulator admin clear and JSON GET/PUT
 * </ul>
 *
 * <p>Uses {@code Authorization: Bearer owner} (the official Firebase Emulator admin bypass token)
 * so tests and agents can seed and inspect state regardless of Security Rules.
 */
public final class FirebaseEmulatorClient {

  private static final int CONNECT_TIMEOUT_MS = 3_000;
  private static final int READ_TIMEOUT_MS = 10_000;
  private static final String OWNER_AUTH = "Bearer owner";

  private FirebaseEmulatorClient() {}

  /** Queries the Firebase Emulator Hub ({@code GET /emulators}) for all running emulators. */
  public static FirebaseEmulatorConfig discoverHub(FirebaseEmulatorConfig.HostPort hub)
      throws IOException {
    String url = hub.httpOrigin() + "/emulators";
    Response response = request("GET", url, null, false);
    if (response.status < 200 || response.status >= 300) {
      throw new IOException(
          "Firebase Emulator Hub at "
              + url
              + " returned HTTP "
              + response.status
              + ": "
              + response.body);
    }
    try {
      return FirebaseEmulatorConfig.fromHubResponse(new JSONObject(response.body), hub);
    } catch (JSONException e) {
      throw new IOException("invalid JSON from Firebase Emulator Hub at " + url, e);
    }
  }

  /** Clears all documents in the Cloud Firestore Emulator for {@code projectId}. */
  public static void clearFirestore(
      FirebaseEmulatorConfig.HostPort firestore, String projectId, String databaseId)
      throws IOException {
    String db = (databaseId == null || databaseId.isEmpty()) ? "(default)" : databaseId;
    String url =
        firestore.httpOrigin()
            + "/emulator/v1/projects/"
            + encodeSegment(projectId)
            + "/databases/"
            + encodeSegment(db)
            + "/documents";
    Response response = request("DELETE", url, null, true);
    requireSuccess("clearFirestore", url, response);
  }

  /** Reads a single Firestore document as raw Firestore REST JSON. */
  public static JSONObject getFirestoreDocument(
      FirebaseEmulatorConfig.HostPort firestore,
      String projectId,
      String databaseId,
      String documentPath)
      throws IOException {
    String db = (databaseId == null || databaseId.isEmpty()) ? "(default)" : databaseId;
    String path = stripLeadingSlash(documentPath);
    String url =
        firestore.httpOrigin()
            + "/v1/projects/"
            + encodeSegment(projectId)
            + "/databases/"
            + encodeSegment(db)
            + "/documents/"
            + path;
    Response response = request("GET", url, null, true);
    requireSuccess("getFirestoreDocument", url, response);
    return parseJsonObject(url, response.body);
  }

  /**
   * Creates or overwrites a Firestore document at {@code documentPath}. {@code body} may either
   * already be a Firestore REST payload ({@code {"fields": {...}}}) or plain JSON fields, in which
   * case simple values are wrapped as Firestore typed values automatically.
   */
  public static JSONObject setFirestoreDocument(
      FirebaseEmulatorConfig.HostPort firestore,
      String projectId,
      String databaseId,
      String documentPath,
      JSONObject body)
      throws IOException {
    String db = (databaseId == null || databaseId.isEmpty()) ? "(default)" : databaseId;
    String path = stripLeadingSlash(documentPath);
    String url =
        firestore.httpOrigin()
            + "/v1/projects/"
            + encodeSegment(projectId)
            + "/databases/"
            + encodeSegment(db)
            + "/documents/"
            + path;
    JSONObject payload = toFirestorePayload(body);
    Response response = request("PATCH", url, payload.toString(), true);
    requireSuccess("setFirestoreDocument", url, response);
    return parseJsonObject(url, response.body);
  }

  /** Deletes a single Firestore document at {@code documentPath}. */
  public static void deleteFirestoreDocument(
      FirebaseEmulatorConfig.HostPort firestore,
      String projectId,
      String databaseId,
      String documentPath)
      throws IOException {
    String db = (databaseId == null || databaseId.isEmpty()) ? "(default)" : databaseId;
    String path = stripLeadingSlash(documentPath);
    String url =
        firestore.httpOrigin()
            + "/v1/projects/"
            + encodeSegment(projectId)
            + "/databases/"
            + encodeSegment(db)
            + "/documents/"
            + path;
    Response response = request("DELETE", url, null, true);
    requireSuccess("deleteFirestoreDocument", url, response);
  }

  /** Clears all user accounts in the Firebase Authentication Emulator. */
  public static void clearAuth(FirebaseEmulatorConfig.HostPort auth, String projectId)
      throws IOException {
    String url =
        auth.httpOrigin() + "/emulator/v1/projects/" + encodeSegment(projectId) + "/accounts";
    Response response = request("DELETE", url, null, true);
    requireSuccess("clearAuth", url, response);
  }

  /** Creates a user account in the Firebase Authentication Emulator (admin endpoint). */
  public static JSONObject createAuthUser(
      FirebaseEmulatorConfig.HostPort auth,
      String projectId,
      String email,
      String password,
      String displayName,
      String uid)
      throws IOException {
    String url =
        auth.httpOrigin()
            + "/identitytoolkit.googleapis.com/v1/projects/"
            + encodeSegment(projectId)
            + "/accounts";
    JSONObject body = new JSONObject();
    try {
      if (email != null) body.put("email", email);
      if (password != null) body.put("password", password);
      if (displayName != null) body.put("displayName", displayName);
      if (uid != null) body.put("localId", uid);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    Response response = request("POST", url, body.toString(), true);
    requireSuccess("createAuthUser", url, response);
    return parseJsonObject(url, response.body);
  }

  /** Lists accounts registered in the Firebase Authentication Emulator. */
  public static JSONObject listAuthUsers(FirebaseEmulatorConfig.HostPort auth, String projectId)
      throws IOException {
    String url =
        auth.httpOrigin()
            + "/identitytoolkit.googleapis.com/v1/projects/"
            + encodeSegment(projectId)
            + "/accounts:query";
    Response response = request("POST", url, "{}", true);
    requireSuccess("listAuthUsers", url, response);
    return parseJsonObject(url, response.body);
  }

  /** Lists pending email verification / password reset OOB codes from the Auth Emulator. */
  public static JSONObject listAuthOobCodes(FirebaseEmulatorConfig.HostPort auth, String projectId)
      throws IOException {
    String url =
        auth.httpOrigin() + "/emulator/v1/projects/" + encodeSegment(projectId) + "/oobCodes";
    Response response = request("GET", url, null, true);
    requireSuccess("listAuthOobCodes", url, response);
    return parseJsonObject(url, response.body);
  }

  /** Lists pending SMS phone verification codes from the Auth Emulator. */
  public static JSONObject listAuthVerificationCodes(
      FirebaseEmulatorConfig.HostPort auth, String projectId) throws IOException {
    String url =
        auth.httpOrigin()
            + "/emulator/v1/projects/"
            + encodeSegment(projectId)
            + "/verificationCodes";
    Response response = request("GET", url, null, true);
    requireSuccess("listAuthVerificationCodes", url, response);
    return parseJsonObject(url, response.body);
  }

  /** Reads JSON at {@code path} from the Realtime Database Emulator. */
  public static String getDatabase(
      FirebaseEmulatorConfig.HostPort database, String namespace, String path) throws IOException {
    String cleanPath = stripLeadingSlash(path == null ? "" : path);
    String url =
        database.httpOrigin()
            + "/"
            + cleanPath
            + ".json?ns="
            + encodeSegment(namespace);
    Response response = request("GET", url, null, true);
    requireSuccess("getDatabase", url, response);
    return response.body;
  }

  /** Writes JSON at {@code path} in the Realtime Database Emulator. */
  public static String setDatabase(
      FirebaseEmulatorConfig.HostPort database, String namespace, String path, String jsonValue)
      throws IOException {
    String cleanPath = stripLeadingSlash(path == null ? "" : path);
    String url =
        database.httpOrigin()
            + "/"
            + cleanPath
            + ".json?ns="
            + encodeSegment(namespace);
    Response response = request("PUT", url, jsonValue, true);
    requireSuccess("setDatabase", url, response);
    return response.body;
  }

  /** Clears the Realtime Database Emulator namespace. */
  public static void clearDatabase(FirebaseEmulatorConfig.HostPort database, String namespace)
      throws IOException {
    String url = database.httpOrigin() + "/.json?ns=" + encodeSegment(namespace);
    Response response = request("DELETE", url, null, true);
    requireSuccess("clearDatabase", url, response);
  }

  /** Wraps plain JSON values into Firestore REST {@code {"fields": ...}} format if needed. */
  static JSONObject toFirestorePayload(JSONObject input) {
    if (input == null) {
      JSONObject empty = new JSONObject();
      try {
        empty.put("fields", new JSONObject());
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      return empty;
    }
    if (input.has("fields") && input.optJSONObject("fields") != null && input.length() == 1) {
      return input;
    }
    try {
      JSONObject fields = new JSONObject();
      java.util.Iterator<String> keys = input.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        fields.put(key, toFirestoreValue(input.get(key)));
      }
      JSONObject payload = new JSONObject();
      payload.put("fields", fields);
      return payload;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JSONObject toFirestoreValue(Object value) throws JSONException {
    JSONObject wrapped = new JSONObject();
    if (value == null || value == JSONObject.NULL) {
      wrapped.put("nullValue", JSONObject.NULL);
    } else if (value instanceof Boolean) {
      wrapped.put("booleanValue", value);
    } else if (value instanceof Integer || value instanceof Long) {
      wrapped.put("integerValue", String.valueOf(((Number) value).longValue()));
    } else if (value instanceof Number) {
      wrapped.put("doubleValue", ((Number) value).doubleValue());
    } else if (value instanceof String) {
      wrapped.put("stringValue", value);
    } else if (value instanceof org.json.JSONArray) {
      org.json.JSONArray src = (org.json.JSONArray) value;
      org.json.JSONArray dst = new org.json.JSONArray();
      for (int i = 0; i < src.length(); i++) {
        dst.put(toFirestoreValue(src.get(i)));
      }
      JSONObject arrayVal = new JSONObject();
      arrayVal.put("values", dst);
      wrapped.put("arrayValue", arrayVal);
    } else if (value instanceof JSONObject) {
      JSONObject src = (JSONObject) value;
      JSONObject fields = new JSONObject();
      java.util.Iterator<String> keys = src.keys();
      while (keys.hasNext()) {
        String k = keys.next();
        fields.put(k, toFirestoreValue(src.get(k)));
      }
      JSONObject mapVal = new JSONObject();
      mapVal.put("fields", fields);
      wrapped.put("mapValue", mapVal);
    } else {
      wrapped.put("stringValue", String.valueOf(value));
    }
    return wrapped;
  }

  private static final class Response {
    final int status;
    final String body;

    Response(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  private static Response request(
      String method, String urlString, String jsonBody, boolean ownerAuth) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    // HttpURLConnection rejects "PATCH" on some JDKs unless overridden via X-HTTP-Method-Override
    // or reflection; for local emulator HTTP servers, use POST + X-HTTP-Method-Override only if
    // setRequestMethod("PATCH") throws ProtocolException.
    try {
      conn.setRequestMethod(method);
    } catch (java.net.ProtocolException e) {
      conn.setRequestMethod("POST");
      conn.setRequestProperty("X-HTTP-Method-Override", method);
    }
    if (ownerAuth) {
      conn.setRequestProperty("Authorization", OWNER_AUTH);
    }
    if (jsonBody != null) {
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
      try (OutputStream out = conn.getOutputStream()) {
        out.write(bytes);
      }
    }
    int status = conn.getResponseCode();
    InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    String body = stream == null ? "" : readAll(stream);
    conn.disconnect();
    return new Response(status, body);
  }

  private static String readAll(InputStream in) throws IOException {
    try (InputStream stream = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = stream.read(buf)) >= 0) {
        out.write(buf, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static void requireSuccess(String op, String url, Response response) throws IOException {
    if (response.status < 200 || response.status >= 300) {
      throw new IOException(
          op + " (" + url + ") failed with HTTP " + response.status + ": " + response.body);
    }
  }

  private static JSONObject parseJsonObject(String url, String body) throws IOException {
    if (body == null || body.trim().isEmpty()) return new JSONObject();
    try {
      return new JSONObject(body);
    } catch (JSONException e) {
      throw new IOException("invalid JSON from " + url + ": " + body, e);
    }
  }

  private static String stripLeadingSlash(String path) {
    String p = path;
    while (p.startsWith("/")) p = p.substring(1);
    return p;
  }

  private static String encodeSegment(String s) {
    try {
      return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
