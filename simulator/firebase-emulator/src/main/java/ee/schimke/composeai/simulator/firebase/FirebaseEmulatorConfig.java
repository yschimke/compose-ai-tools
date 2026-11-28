package ee.schimke.composeai.simulator.firebase;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Configuration for connecting the simulated app to a real Firebase Local Emulator Suite
 * ({@code firebase emulators:start} or standalone {@code gcloud beta emulators firestore start}).
 *
 * <p>Parsed from the {@value #PROPERTY} system property (passed by {@code compose-preview
 * android-simulator --firebase-emulator ...}), from a {@code firebase.json} file's {@code
 * emulators} block, or from the Emulator Hub's {@code GET /emulators} JSON response.
 */
public final class FirebaseEmulatorConfig {

  /** System property carrying the serialized JSON emulator config from the CLI launcher. */
  public static final String PROPERTY = "composeai.simulator.firebase.emulators";

  /** System property carrying an explicit Firebase project ID override. */
  public static final String PROJECT_PROPERTY = "composeai.simulator.firebase.project";

  /** Default Firebase Emulator Hub host and port. */
  public static final String DEFAULT_HOST = "127.0.0.1";

  public static final int DEFAULT_HUB_PORT = 4400;

  /** Firebase products supported by the official Local Emulator Suite. */
  public enum Service {
    AUTH("auth", 9099),
    FIRESTORE("firestore", 8080),
    DATABASE("database", 9000),
    STORAGE("storage", 9199),
    FUNCTIONS("functions", 5001),
    DATACONNECT("dataconnect", 9399);

    public final String key;
    public final int defaultPort;

    Service(String key, int defaultPort) {
      this.key = key;
      this.defaultPort = defaultPort;
    }

    public static Service fromKey(String key) {
      if (key == null) return null;
      String normalized = key.trim().toLowerCase(Locale.ROOT);
      for (Service s : values()) {
        if (s.key.equals(normalized)) return s;
      }
      if ("rtdb".equals(normalized) || "realtime-database".equals(normalized)) return DATABASE;
      return null;
    }
  }

  /** Normalized host and port for a single emulator endpoint. */
  public static final class HostPort {
    public final String host;
    public final int port;

    public HostPort(String host, int port) {
      if (port <= 0 || port > 65535) {
        throw new IllegalArgumentException("invalid emulator port: " + port);
      }
      this.host = normalizeHost(host);
      this.port = port;
    }

    public String authority() {
      return host + ":" + port;
    }

    public String httpOrigin() {
      return "http://" + host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof HostPort)) return false;
      HostPort other = (HostPort) o;
      return port == other.port && host.equals(other.host);
    }

    @Override
    public int hashCode() {
      return Objects.hash(host, port);
    }

    @Override
    public String toString() {
      return authority();
    }
  }

  private final String projectId;
  private final HostPort hub;
  private final boolean discoverHub;
  private final Map<Service, HostPort> services;

  public FirebaseEmulatorConfig(
      String projectId, HostPort hub, boolean discoverHub, Map<Service, HostPort> services) {
    this.projectId = projectId == null || projectId.trim().isEmpty() ? null : projectId.trim();
    this.hub = hub;
    this.discoverHub = discoverHub;
    EnumMap<Service, HostPort> copy = new EnumMap<>(Service.class);
    if (services != null) copy.putAll(services);
    this.services = Collections.unmodifiableMap(copy);
  }

  public static FirebaseEmulatorConfig empty() {
    return new FirebaseEmulatorConfig(null, null, false, Collections.emptyMap());
  }

  public String projectId() {
    return projectId;
  }

  public HostPort hub() {
    return hub;
  }

  public boolean discoverHub() {
    return discoverHub;
  }

  public Map<Service, HostPort> services() {
    return services;
  }

  public HostPort service(Service service) {
    return services.get(service);
  }

  public boolean isEmpty() {
    return !discoverHub && services.isEmpty();
  }

  /** Merges discovered/override services on top of this config without losing explicit overrides. */
  public FirebaseEmulatorConfig mergedWith(FirebaseEmulatorConfig discovered) {
    if (discovered == null) return this;
    EnumMap<Service, HostPort> merged = new EnumMap<>(Service.class);
    merged.putAll(discovered.services);
    merged.putAll(this.services);
    String effectiveProject = this.projectId != null ? this.projectId : discovered.projectId;
    HostPort effectiveHub = this.hub != null ? this.hub : discovered.hub;
    return new FirebaseEmulatorConfig(
        effectiveProject, effectiveHub, this.discoverHub || discovered.discoverHub, merged);
  }

  public JSONObject toJson() {
    JSONObject root = new JSONObject();
    try {
      if (projectId != null) root.put("projectId", projectId);
      if (hub != null) {
        JSONObject h = new JSONObject();
        h.put("host", hub.host);
        h.put("port", hub.port);
        root.put("hub", h);
      }
      if (discoverHub) root.put("discoverHub", true);
      JSONObject svcs = new JSONObject();
      for (Map.Entry<Service, HostPort> entry : services.entrySet()) {
        JSONObject hp = new JSONObject();
        hp.put("host", entry.getValue().host);
        hp.put("port", entry.getValue().port);
        svcs.put(entry.getKey().key, hp);
      }
      root.put("services", svcs);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return root;
  }

  public static FirebaseEmulatorConfig fromJson(JSONObject root) {
    if (root == null) return empty();
    String projectId = root.optString("projectId", null);
    HostPort hub = parseHostPortObject(root.optJSONObject("hub"), DEFAULT_HUB_PORT);
    boolean discover = root.optBoolean("discoverHub", false);
    EnumMap<Service, HostPort> map = new EnumMap<>(Service.class);
    JSONObject svcs = root.optJSONObject("services");
    if (svcs != null) {
      for (Service s : Service.values()) {
        HostPort hp = parseHostPortObject(svcs.optJSONObject(s.key), s.defaultPort);
        if (hp != null) map.put(s, hp);
      }
    }
    return new FirebaseEmulatorConfig(projectId, hub, discover, map);
  }

  /**
   * Parses a {@code firebase.json} document's {@code "emulators"} object.
   *
   * <p>Example:
   *
   * <pre>{@code
   * {
   *   "emulators": {
   *     "auth": { "port": 9099 },
   *     "firestore": { "host": "127.0.0.1", "port": 8080 },
   *     "hub": { "port": 4400 }
   *   }
   * }
   * }</pre>
   */
  public static FirebaseEmulatorConfig fromFirebaseJson(JSONObject root, String projectId) {
    if (root == null) return empty();
    JSONObject emulators = root.optJSONObject("emulators");
    if (emulators == null) return new FirebaseEmulatorConfig(projectId, null, false, null);
    HostPort hub = parseHostPortObject(emulators.optJSONObject("hub"), DEFAULT_HUB_PORT);
    if (hub == null) hub = new HostPort(DEFAULT_HOST, DEFAULT_HUB_PORT);
    EnumMap<Service, HostPort> map = new EnumMap<>(Service.class);
    for (Service s : Service.values()) {
      JSONObject entry = emulators.optJSONObject(s.key);
      if (entry != null && entry.optBoolean("enabled", true)) {
        String host = entry.optString("host", DEFAULT_HOST);
        int port = entry.optInt("port", s.defaultPort);
        map.put(s, new HostPort(host, port));
      }
    }
    return new FirebaseEmulatorConfig(projectId, hub, false, map);
  }

  /**
   * Parses the Firebase Emulator Hub {@code GET /emulators} JSON response:
   * {@code {"auth":{"name":"auth","host":"127.0.0.1","port":9099}, ...}}.
   */
  public static FirebaseEmulatorConfig fromHubResponse(JSONObject hubJson, HostPort hubAddress) {
    if (hubJson == null) return empty();
    EnumMap<Service, HostPort> map = new EnumMap<>(Service.class);
    for (Service s : Service.values()) {
      JSONObject entry = hubJson.optJSONObject(s.key);
      if (entry == null) continue;
      int port = entry.optInt("port", -1);
      if (port <= 0) continue;
      String host = entry.optString("host", hubAddress != null ? hubAddress.host : DEFAULT_HOST);
      map.put(s, new HostPort(host, port));
    }
    return new FirebaseEmulatorConfig(null, hubAddress, false, map);
  }

  /**
   * Parses a CLI/control spec string:
   *
   * <ul>
   *   <li>{@code auto} — discover running emulators from the Hub at {@code 127.0.0.1:4400};
   *   <li>{@code hub=host:port} — discover from a custom Hub address;
   *   <li>{@code auth=127.0.0.1:9099,firestore=8080,...} — explicit service endpoints (host
   *       defaults to {@code 127.0.0.1});
   *   <li>a JSON object string (as serialized by {@link #toJson()}).
   * </ul>
   */
  public static FirebaseEmulatorConfig parseSpec(String spec, String projectId) {
    if (spec == null) return empty();
    String trimmed = spec.trim();
    if (trimmed.isEmpty()) return empty();
    if (trimmed.startsWith("{")) {
      try {
        JSONObject obj = new JSONObject(trimmed);
        FirebaseEmulatorConfig parsed =
            obj.has("emulators") ? fromFirebaseJson(obj, projectId) : fromJson(obj);
        if (projectId != null && parsed.projectId() == null) {
          return new FirebaseEmulatorConfig(
              projectId, parsed.hub(), parsed.discoverHub(), parsed.services());
        }
        return parsed;
      } catch (JSONException e) {
        throw new IllegalArgumentException("invalid Firebase emulator JSON: " + e.getMessage(), e);
      }
    }
    if ("auto".equalsIgnoreCase(trimmed)) {
      return new FirebaseEmulatorConfig(
          projectId, new HostPort(DEFAULT_HOST, DEFAULT_HUB_PORT), true, Collections.emptyMap());
    }
    HostPort hub = null;
    boolean discover = false;
    EnumMap<Service, HostPort> map = new EnumMap<>(Service.class);
    for (String token : trimmed.split(",")) {
      String part = token.trim();
      if (part.isEmpty()) continue;
      if ("auto".equalsIgnoreCase(part)) {
        if (hub == null) hub = new HostPort(DEFAULT_HOST, DEFAULT_HUB_PORT);
        discover = true;
        continue;
      }
      int eq = part.indexOf('=');
      if (eq <= 0 || eq == part.length() - 1) {
        throw new IllegalArgumentException(
            "expected service=host:port (or service=port), got \"" + part + "\"");
      }
      String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String value = part.substring(eq + 1).trim();
      if ("hub".equals(key)) {
        hub = parseHostPortString(value, DEFAULT_HUB_PORT);
        discover = true;
      } else {
        Service service = Service.fromKey(key);
        if (service == null) {
          throw new IllegalArgumentException(
              "unknown Firebase emulator service \""
                  + key
                  + "\" (expected hub, auth, firestore, database, storage, functions, dataconnect)");
        }
        map.put(service, parseHostPortString(value, service.defaultPort));
      }
    }
    return new FirebaseEmulatorConfig(projectId, hub, discover, map);
  }

  /** Reads the launch configuration from JVM system properties, or {@link #empty()} when unset. */
  public static FirebaseEmulatorConfig fromSystemProperties() {
    String spec = System.getProperty(PROPERTY);
    String project = System.getProperty(PROJECT_PROPERTY);
    if (spec == null || spec.trim().isEmpty()) {
      return project == null || project.trim().isEmpty()
          ? empty()
          : new FirebaseEmulatorConfig(project, null, false, Collections.emptyMap());
    }
    return parseSpec(spec, project);
  }

  public static HostPort parseHostPortString(String value, int defaultPort) {
    String v = value.trim();
    if (v.startsWith("http://")) v = v.substring("http://".length());
    if (v.startsWith("https://")) v = v.substring("https://".length());
    int slash = v.indexOf('/');
    if (slash >= 0) v = v.substring(0, slash);
    int colon = v.lastIndexOf(':');
    if (colon < 0) {
      // Bare port number ("8080") or bare hostname ("localhost").
      try {
        int port = Integer.parseInt(v);
        return new HostPort(DEFAULT_HOST, port);
      } catch (NumberFormatException ignored) {
        return new HostPort(v, defaultPort);
      }
    }
    String host = v.substring(0, colon).trim();
    int port = Integer.parseInt(v.substring(colon + 1).trim());
    return new HostPort(host.isEmpty() ? DEFAULT_HOST : host, port);
  }

  private static HostPort parseHostPortObject(JSONObject obj, int defaultPort) {
    if (obj == null) return null;
    int port = obj.optInt("port", defaultPort);
    String host = obj.optString("host", DEFAULT_HOST);
    return new HostPort(host, port);
  }

  /** Normalizes wildcard / AVD alias hosts to host loopback {@code 127.0.0.1}. */
  static String normalizeHost(String host) {
    if (host == null) return DEFAULT_HOST;
    String trimmed = host.trim();
    if (trimmed.isEmpty()
        || "0.0.0.0".equals(trimmed)
        || "::".equals(trimmed)
        || "[::]".equals(trimmed)
        || "10.0.2.2".equals(trimmed)) {
      return DEFAULT_HOST;
    }
    return trimmed;
  }
}
