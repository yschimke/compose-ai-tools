package ee.schimke.composeai.simulator.firebase;

import android.app.Application;
import java.io.IOException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Top-level Firebase coordinator owned by {@code Session}:
 *
 * <ul>
 *   <li>Installs {@link FirebaseMessagingHost} C2DM/FCM IPC interceptors;
 *   <li>Auto-discovers and wires a running Firebase Local Emulator Suite (when configured via
 *       {@code --firebase-emulator});
 *   <li>Handles the {@code firebase} control-channel command for emulator admin operations
 *       (clear/seed Firestore, Auth, Realtime Database), FCM token/push delivery, Remote Config
 *       overrides, Analytics event inspection, and Crashlytics inspection.
 * </ul>
 */
public final class FirebaseServices {

  private static final String DEFAULT_PROJECT_ID = "demo-simulator";
  private static volatile FirebaseServices current;

  private final Application app;
  private final FirebaseMessagingHost messaging;
  private final FirebaseRemoteConfigHost remoteConfig = new FirebaseRemoteConfigHost();
  private volatile FirebaseEmulatorConfig config;
  private volatile JSONObject lastWiring = new JSONObject();
  private volatile String lastDiscoveryError;
  private boolean installed;

  public FirebaseServices(Application app) {
    this.app = app;
    this.messaging = new FirebaseMessagingHost(app);
    this.config = FirebaseEmulatorConfig.fromSystemProperties();
  }

  /**
   * Installs FCM IPC hooks and wires any {@code --firebase-emulator} endpoints immediately after
   * manifest content providers ({@code FirebaseInitProvider}) and <em>before</em> {@code
   * Application.onCreate()}, so {@code useEmulator()} runs before app code starts Firestore/Auth
   * operations.
   */
  public static synchronized FirebaseServices installEarly(Application app) {
    FirebaseServices services = new FirebaseServices(app);
    services.install();
    current = services;
    return services;
  }

  /**
   * Returns the early-installed instance for {@code app} (re-wiring any SDK that was not yet
   * initialized before {@code Application.onCreate()}), or creates and installs a new one.
   */
  public static synchronized FirebaseServices forApp(Application app) {
    FirebaseServices existing = current;
    if (existing != null && existing.app == app) {
      existing.wireUninitializedIfNeeded();
      return existing;
    }
    FirebaseServices created = new FirebaseServices(app);
    created.install();
    current = created;
    return created;
  }

  /** Installs platform IPC hooks and wires any emulators configured at launch. */
  public synchronized void install() {
    if (installed) return;
    installed = true;
    messaging.install();
    if (!config.isEmpty()) {
      resolveAndWire(config);
    } else {
      FirebaseEmulatorWiring.setActiveFunctionsEndpoint(null);
    }
  }

  /**
   * Retries wiring only for services whose status was {@code notInitialized} before {@code
   * Application.onCreate()} (for apps that call {@code FirebaseApp.initializeApp()} manually).
   */
  private void wireUninitializedIfNeeded() {
    if (config.isEmpty()) return;
    boolean hasUninitialized = false;
    java.util.Iterator<String> keys = lastWiring.keys();
    while (keys.hasNext()) {
      JSONObject entry = lastWiring.optJSONObject(keys.next());
      if (entry != null && "notInitialized".equals(entry.optString("status"))) {
        hasUninitialized = true;
        break;
      }
    }
    if (!hasUninitialized) return;
    ClassLoader loader = app != null ? app.getClassLoader() : getClass().getClassLoader();
    JSONObject retry = FirebaseEmulatorWiring.wireAll(app, loader, config);
    java.util.Iterator<String> retryKeys = retry.keys();
    while (retryKeys.hasNext()) {
      String k = retryKeys.next();
      JSONObject prev = lastWiring.optJSONObject(k);
      if (prev == null || "notInitialized".equals(prev.optString("status"))) {
        try {
          lastWiring.put(k, retry.get(k));
        } catch (JSONException ignored) {
          // Keep previous entry.
        }
      }
    }
  }

  public FirebaseMessagingHost messaging() {
    return messaging;
  }

  public FirebaseRemoteConfigHost remoteConfig() {
    return remoteConfig;
  }

  public FirebaseEmulatorConfig config() {
    return config;
  }

  /** Resolves Hub discovery (if enabled) and wires all configured Firebase SDKs. */
  public JSONObject resolveAndWire(FirebaseEmulatorConfig requested) {
    FirebaseEmulatorConfig effective = requested == null ? FirebaseEmulatorConfig.empty() : requested;
    lastDiscoveryError = null;
    if (effective.discoverHub() && effective.hub() != null) {
      try {
        FirebaseEmulatorConfig discovered = FirebaseEmulatorClient.discoverHub(effective.hub());
        effective = effective.mergedWith(discovered);
      } catch (IOException e) {
        lastDiscoveryError = e.getMessage();
      }
    }
    this.config = effective;
    ClassLoader loader = app != null ? app.getClassLoader() : getClass().getClassLoader();
    this.lastWiring = FirebaseEmulatorWiring.wireAll(app, loader, effective);
    return statusJson();
  }

  /**
   * Dispatches a {@code firebase} control-channel command.
   *
   * <p>Supported {@code action} values (default {@code "status"}):
   *
   * <ul>
   *   <li>{@code status} — emulator config, wiring, app FirebaseOptions, FCM, Remote Config,
   *       Analytics &amp; Crashlytics summary
   *   <li>{@code connect} — connect/re-wire to Firebase Local Emulator Suite ({@code spec} or
   *       {@code hub} / {@code projectId})
   *   <li>{@code clear} — clear emulator data ({@code service}: {@code all|firestore|auth|database})
   *   <li>{@code authCreateUser}, {@code authListUsers}, {@code authOobCodes},
   *       {@code authVerificationCodes}
   *   <li>{@code firestoreGet}, {@code firestoreSet}, {@code firestoreDelete}
   *   <li>{@code databaseGet}, {@code databaseSet}
   *   <li>{@code fcmToken}, {@code fcmRotateToken}, {@code fcmSend}, {@code fcmMessages},
   *       {@code fcmClear}
   *   <li>{@code remoteConfigGet}, {@code remoteConfigSet}, {@code remoteConfigClear}
   *   <li>{@code analyticsEvents}, {@code analyticsClear}
   *   <li>{@code crashlytics}, {@code crashlyticsClear}
   * </ul>
   */
  public JSONObject handleCommand(JSONObject cmd) throws IOException, JSONException {
    String action = cmd.optString("action", "status").trim();
    if (action.isEmpty()) action = "status";
    ClassLoader loader = app != null ? app.getClassLoader() : getClass().getClassLoader();
    switch (action) {
      case "status":
        return statusJson();
      case "connect":
        return handleConnect(cmd);
      case "clear":
        return handleClear(cmd);
      case "authCreateUser":
        return FirebaseEmulatorClient.createAuthUser(
            requireService(FirebaseEmulatorConfig.Service.AUTH),
            resolveProjectId(cmd),
            optNonEmpty(cmd, "email"),
            optNonEmpty(cmd, "password"),
            optNonEmpty(cmd, "displayName"),
            optNonEmpty(cmd, "uid"));
      case "authListUsers":
        return FirebaseEmulatorClient.listAuthUsers(
            requireService(FirebaseEmulatorConfig.Service.AUTH), resolveProjectId(cmd));
      case "authOobCodes":
        return FirebaseEmulatorClient.listAuthOobCodes(
            requireService(FirebaseEmulatorConfig.Service.AUTH), resolveProjectId(cmd));
      case "authVerificationCodes":
        return FirebaseEmulatorClient.listAuthVerificationCodes(
            requireService(FirebaseEmulatorConfig.Service.AUTH), resolveProjectId(cmd));
      case "firestoreGet":
        return FirebaseEmulatorClient.getFirestoreDocument(
            requireService(FirebaseEmulatorConfig.Service.FIRESTORE),
            resolveProjectId(cmd),
            optNonEmpty(cmd, "databaseId"),
            requireString(cmd, "path"));
      case "firestoreSet":
        {
          JSONObject fields = cmd.optJSONObject("fields");
          if (fields == null) fields = cmd.optJSONObject("data");
          return FirebaseEmulatorClient.setFirestoreDocument(
              requireService(FirebaseEmulatorConfig.Service.FIRESTORE),
              resolveProjectId(cmd),
              optNonEmpty(cmd, "databaseId"),
              requireString(cmd, "path"),
              fields);
        }
      case "firestoreDelete":
        FirebaseEmulatorClient.deleteFirestoreDocument(
            requireService(FirebaseEmulatorConfig.Service.FIRESTORE),
            resolveProjectId(cmd),
            optNonEmpty(cmd, "databaseId"),
            requireString(cmd, "path"));
        return new JSONObject().put("deleted", requireString(cmd, "path"));
      case "databaseGet":
        {
          String raw =
              FirebaseEmulatorClient.getDatabase(
                  requireService(FirebaseEmulatorConfig.Service.DATABASE),
                  resolveNamespace(cmd),
                  cmd.optString("path", ""));
          return new JSONObject().put("path", cmd.optString("path", "")).put("raw", raw);
        }
      case "databaseSet":
        {
          Object val = cmd.has("value") ? cmd.get("value") : JSONObject.NULL;
          String json = toJsonLiteral(val);
          String raw =
              FirebaseEmulatorClient.setDatabase(
                  requireService(FirebaseEmulatorConfig.Service.DATABASE),
                  resolveNamespace(cmd),
                  cmd.optString("path", ""),
                  json);
          return new JSONObject().put("path", cmd.optString("path", "")).put("raw", raw);
        }
      case "fcmToken":
        return messaging.statusJson();
      case "fcmRotateToken":
        return messaging.rotateToken(optNonEmpty(cmd, "token"));
      case "fcmSend":
        return messaging.sendMessage(cmd);
      case "fcmMessages":
        return new JSONObject().put("messages", messaging.sentMessagesJson());
      case "fcmClear":
        messaging.clearHistory();
        return new JSONObject().put("cleared", true);
      case "remoteConfigGet":
        return remoteConfig.statusJson(null);
      case "remoteConfigSet":
        return remoteConfig.setValues(cmd.optJSONObject("values"), loader);
      case "remoteConfigClear":
        return remoteConfig.clear(loader);
      case "analyticsEvents":
        return FirebaseAnalyticsHost.toJson(optNonEmpty(cmd, "name"));
      case "analyticsClear":
        FirebaseAnalyticsHost.clear();
        return new JSONObject().put("cleared", true);
      case "crashlytics":
        return FirebaseCrashlyticsHost.toJson();
      case "crashlyticsClear":
        FirebaseCrashlyticsHost.clear();
        return new JSONObject().put("cleared", true);
      default:
        throw new IllegalArgumentException("unknown firebase action \"" + action + "\"");
    }
  }

  private JSONObject handleConnect(JSONObject cmd) {
    String spec = optNonEmpty(cmd, "spec");
    String projectId = optNonEmpty(cmd, "projectId");
    if (spec == null) {
      String hub = optNonEmpty(cmd, "hub");
      spec = hub != null ? "hub=" + hub : "auto";
    }
    FirebaseEmulatorConfig next = FirebaseEmulatorConfig.parseSpec(spec, projectId);
    return resolveAndWire(next);
  }

  private JSONObject handleClear(JSONObject cmd) throws IOException, JSONException {
    String target = cmd.optString("service", "all").trim().toLowerCase(Locale.ROOT);
    String projectId = resolveProjectId(cmd);
    JSONObject cleared = new JSONObject();
    boolean all = "all".equals(target);
    if (all || "firestore".equals(target)) {
      FirebaseEmulatorConfig.HostPort hp = config.service(FirebaseEmulatorConfig.Service.FIRESTORE);
      if (hp != null) {
        FirebaseEmulatorClient.clearFirestore(hp, projectId, optNonEmpty(cmd, "databaseId"));
        cleared.put("firestore", true);
      } else if (!all) {
        throw new IllegalStateException("Firestore emulator is not configured");
      }
    }
    if (all || "auth".equals(target)) {
      FirebaseEmulatorConfig.HostPort hp = config.service(FirebaseEmulatorConfig.Service.AUTH);
      if (hp != null) {
        FirebaseEmulatorClient.clearAuth(hp, projectId);
        cleared.put("auth", true);
      } else if (!all) {
        throw new IllegalStateException("Auth emulator is not configured");
      }
    }
    if (all || "database".equals(target) || "rtdb".equals(target)) {
      FirebaseEmulatorConfig.HostPort hp = config.service(FirebaseEmulatorConfig.Service.DATABASE);
      if (hp != null) {
        FirebaseEmulatorClient.clearDatabase(hp, resolveNamespace(cmd));
        cleared.put("database", true);
      } else if (!all) {
        throw new IllegalStateException("Realtime Database emulator is not configured");
      }
    }
    return new JSONObject().put("projectId", projectId).put("cleared", cleared);
  }

  public JSONObject statusJson() {
    ClassLoader loader = app != null ? app.getClassLoader() : getClass().getClassLoader();
    JSONObject appOptions = FirebaseEmulatorWiring.readAppOptions(app, loader);
    JSONObject out = new JSONObject();
    try {
      out.put("projectId", resolveProjectId(null));
      out.put("emulators", config.toJson());
      out.put("wiring", lastWiring);
      if (lastDiscoveryError != null) out.put("discoveryError", lastDiscoveryError);
      if (appOptions.length() > 0) out.put("appOptions", appOptions);
      out.put("fcm", messaging.statusJson());
      out.put("remoteConfig", remoteConfig.statusJson(null));
      out.put("analyticsEventCount", FirebaseAnalyticsHost.events().size());
      out.put("crashlyticsExceptionCount", FirebaseCrashlyticsHost.exceptions().size());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private FirebaseEmulatorConfig.HostPort requireService(FirebaseEmulatorConfig.Service service) {
    FirebaseEmulatorConfig.HostPort hp = config.service(service);
    if (hp == null) {
      throw new IllegalStateException(
          "Firebase "
              + service.key
              + " emulator is not configured; launch with --firebase-emulator or call"
              + " {\"cmd\":\"firebase\",\"action\":\"connect\"}");
    }
    return hp;
  }

  private String resolveProjectId(JSONObject cmd) {
    String explicit = cmd != null ? optNonEmpty(cmd, "projectId") : null;
    if (explicit != null) return explicit;
    if (config.projectId() != null) return config.projectId();
    ClassLoader loader = app != null ? app.getClassLoader() : getClass().getClassLoader();
    JSONObject opts = FirebaseEmulatorWiring.readAppOptions(app, loader);
    String fromApp = opts.optString("projectId", null);
    if (fromApp != null && !fromApp.isEmpty()) return fromApp;
    return DEFAULT_PROJECT_ID;
  }

  private String resolveNamespace(JSONObject cmd) {
    String explicit = cmd != null ? optNonEmpty(cmd, "namespace") : null;
    if (explicit != null) return explicit;
    ClassLoader loader = app != null ? app.getClassLoader() : getClass().getClassLoader();
    JSONObject opts = FirebaseEmulatorWiring.readAppOptions(app, loader);
    String fromUrl = namespaceFromDatabaseUrl(opts.optString("databaseUrl", null));
    if (fromUrl != null) return fromUrl;
    return resolveProjectId(cmd) + "-default-rtdb";
  }

  /**
   * Derives the Realtime Database namespace from a Firebase database URL, matching {@code
   * FirebaseDatabase}'s own URL parser (e.g. {@code https://my-project.firebaseio.com} → {@code
   * my-project}, {@code http://127.0.0.1:9000/?ns=my-ns} → {@code my-ns}).
   */
  static String namespaceFromDatabaseUrl(String databaseUrl) {
    if (databaseUrl == null || databaseUrl.trim().isEmpty()) return null;
    try {
      java.net.URI uri = java.net.URI.create(databaseUrl.trim());
      String query = uri.getRawQuery();
      if (query != null) {
        for (String param : query.split("&")) {
          if (param.startsWith("ns=") && param.length() > 3) {
            return java.net.URLDecoder.decode(
                param.substring(3), java.nio.charset.StandardCharsets.UTF_8.name());
          }
        }
      }
      String host = uri.getHost();
      if (host != null && !host.isEmpty()) {
        int dot = host.indexOf('.');
        if (dot > 0) {
          String sub = host.substring(0, dot);
          if (!"127".equals(sub) && !"localhost".equalsIgnoreCase(sub)) {
            return sub;
          }
        }
      }
    } catch (Throwable ignored) {
      // Fall back to projectId-default-rtdb.
    }
    return null;
  }

  private static String requireString(JSONObject obj, String key) {
    String v = optNonEmpty(obj, key);
    if (v == null) throw new IllegalArgumentException("missing required \"" + key + "\"");
    return v;
  }

  private static String optNonEmpty(JSONObject obj, String key) {
    if (obj == null || !obj.has(key) || obj.isNull(key)) return null;
    String v = obj.optString(key, "").trim();
    return v.isEmpty() ? null : v;
  }

  private static String toJsonLiteral(Object val) {
    if (val == null || val == JSONObject.NULL) return "null";
    if (val instanceof JSONObject || val instanceof org.json.JSONArray) return val.toString();
    if (val instanceof Boolean || val instanceof Number) return String.valueOf(val);
    return JSONObject.quote(String.valueOf(val));
  }
}
