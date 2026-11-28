package ee.schimke.composeai.simulator.firebase;

import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reflectively discovers initialized {@code FirebaseApp} instances in the simulated app's
 * classloader and wires each Firebase Android SDK to the configured Firebase Local Emulator Suite
 * via its official {@code useEmulator(host, port)} API.
 *
 * <p>Zero compile-time dependency on Firebase SDKs: works across Firebase Android BoM versions and
 * is a clean no-op when the app does not bundle a given Firebase product.
 */
public final class FirebaseEmulatorWiring {

  private static final String FIREBASE_APP = "com.google.firebase.FirebaseApp";

  private static final Map<FirebaseEmulatorConfig.Service, String> SDK_CLASSES;

  static {
    Map<FirebaseEmulatorConfig.Service, String> map = new LinkedHashMap<>();
    map.put(FirebaseEmulatorConfig.Service.AUTH, "com.google.firebase.auth.FirebaseAuth");
    map.put(
        FirebaseEmulatorConfig.Service.FIRESTORE,
        "com.google.firebase.firestore.FirebaseFirestore");
    map.put(
        FirebaseEmulatorConfig.Service.DATABASE, "com.google.firebase.database.FirebaseDatabase");
    map.put(
        FirebaseEmulatorConfig.Service.STORAGE, "com.google.firebase.storage.FirebaseStorage");
    map.put(
        FirebaseEmulatorConfig.Service.FUNCTIONS,
        "com.google.firebase.functions.FirebaseFunctions");
    SDK_CLASSES = Collections.unmodifiableMap(map);
  }

  private FirebaseEmulatorWiring() {}

  /** Reads {@code FirebaseOptions} from the app's default {@code FirebaseApp}, if initialized. */
  public static JSONObject readAppOptions(Context context, ClassLoader loader) {
    JSONObject out = new JSONObject();
    if (loader == null) return out;
    try {
      Class<?> appClass = Class.forName(FIREBASE_APP, false, loader);
      Object defaultApp = resolveDefaultApp(appClass, context);
      if (defaultApp == null) return out;
      Object options = appClass.getMethod("getOptions").invoke(defaultApp);
      if (options == null) return out;
      putIfNonNull(out, "projectId", invokeString(options, "getProjectId"));
      putIfNonNull(out, "applicationId", invokeString(options, "getApplicationId"));
      putIfNonNull(out, "databaseUrl", invokeString(options, "getDatabaseUrl"));
      putIfNonNull(out, "storageBucket", invokeString(options, "getStorageBucket"));
      putIfNonNull(out, "gcmSenderId", invokeString(options, "getGcmSenderId"));
    } catch (Throwable ignored) {
      // App does not bundle FirebaseApp or has not initialized it yet.
    }
    return out;
  }

  /**
   * Calls {@code useEmulator(host, port)} on every Firebase SDK bundled in {@code loader} that has
   * a matching endpoint in {@code config}.
   */
  public static JSONObject wireAll(
      Context context, ClassLoader loader, FirebaseEmulatorConfig config) {
    JSONObject results = new JSONObject();
    if (loader == null || config == null || config.services().isEmpty()) return results;

    Object defaultApp = null;
    Class<?> appClass = null;
    try {
      appClass = Class.forName(FIREBASE_APP, false, loader);
      defaultApp = resolveDefaultApp(appClass, context);
    } catch (Throwable ignored) {
      // No FirebaseApp in APK.
    }

    for (Map.Entry<FirebaseEmulatorConfig.Service, FirebaseEmulatorConfig.HostPort> entry :
        config.services().entrySet()) {
      FirebaseEmulatorConfig.Service service = entry.getKey();
      FirebaseEmulatorConfig.HostPort hp = entry.getValue();
      String className = SDK_CLASSES.get(service);
      if (className == null) {
        putStatus(results, service.key, "unsupportedAutoWire", hp.authority());
        continue;
      }
      wireSingle(loader, appClass, defaultApp, service, className, hp, results);
    }
    return results;
  }

  private static void wireSingle(
      ClassLoader loader,
      Class<?> appClass,
      Object defaultApp,
      FirebaseEmulatorConfig.Service service,
      String className,
      FirebaseEmulatorConfig.HostPort hp,
      JSONObject results) {
    try {
      Class<?> sdkClass = Class.forName(className, false, loader);
      Object instance = resolveInstance(sdkClass, appClass, defaultApp);
      if (instance == null) {
        putStatus(results, service.key, "notInitialized", hp.authority());
        return;
      }
      Method useEmulator = sdkClass.getMethod("useEmulator", String.class, int.class);
      useEmulator.invoke(instance, hp.host, hp.port);
      putStatus(results, service.key, "wired", hp.authority());
    } catch (ClassNotFoundException e) {
      putStatus(results, service.key, "notInApp", hp.authority());
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IllegalStateException) {
        // SDK throws IllegalStateException if useEmulator() was already called or instance started.
        putStatus(results, service.key, "alreadyConfigured", hp.authority());
      } else {
        putStatus(
            results,
            service.key,
            "error: " + (cause != null ? cause.getMessage() : e.getMessage()),
            hp.authority());
      }
    } catch (Throwable t) {
      putStatus(results, service.key, "error: " + t.getMessage(), hp.authority());
    }
  }

  private static Object resolveDefaultApp(Class<?> appClass, Context context) {
    try {
      return appClass.getMethod("getInstance").invoke(null);
    } catch (Throwable ignored) {
      if (context == null) return null;
      try {
        Object list = appClass.getMethod("getApps", Context.class).invoke(null, context);
        if (list instanceof List<?> && !((List<?>) list).isEmpty()) {
          return ((List<?>) list).get(0);
        }
      } catch (Throwable ignored2) {
        // Not initialized.
      }
      return null;
    }
  }

  private static Object resolveInstance(Class<?> sdkClass, Class<?> appClass, Object defaultApp)
      throws Exception {
    if (appClass != null && defaultApp != null) {
      try {
        Method withApp = sdkClass.getMethod("getInstance", appClass);
        return withApp.invoke(null, defaultApp);
      } catch (NoSuchMethodException ignored) {
        // Fall through to no-arg getInstance().
      }
    }
    return sdkClass.getMethod("getInstance").invoke(null);
  }

  private static String invokeString(Object target, String method) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      return v == null ? null : v.toString();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static void putIfNonNull(JSONObject obj, String key, String value) throws JSONException {
    if (value != null && !value.isEmpty()) obj.put(key, value);
  }

  private static void putStatus(JSONObject out, String key, String status, String target) {
    try {
      JSONObject entry = new JSONObject();
      entry.put("status", status);
      entry.put("target", target);
      out.put(key, entry);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
