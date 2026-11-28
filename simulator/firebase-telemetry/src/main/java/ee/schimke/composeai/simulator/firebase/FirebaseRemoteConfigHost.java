package ee.schimke.composeai.simulator.firebase;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Local simulator backing for Firebase Remote Config, which has no official Firebase Local
 * Emulator.
 *
 * <p>Holds simulator-controlled key/value overrides and reflectively applies them to the app's
 * {@code com.google.firebase.remoteconfig.FirebaseRemoteConfig} instance via {@code
 * setDefaultsAsync(Map<String, Object>)} so feature flags can be toggled dynamically from the
 * control channel without cloud access.
 */
public final class FirebaseRemoteConfigHost {

  private static final String REMOTE_CONFIG_CLASS =
      "com.google.firebase.remoteconfig.FirebaseRemoteConfig";

  private final Map<String, Object> values = new ConcurrentHashMap<>();

  public Map<String, Object> values() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /**
   * Merges {@code updates} into the simulator Remote Config store and attempts to push them into
   * the app's {@code FirebaseRemoteConfig} instance if present in {@code loader}.
   */
  public JSONObject setValues(JSONObject updates, ClassLoader loader) {
    if (updates != null) {
      Iterator<String> keys = updates.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object val = updates.opt(key);
        if (val == null || val == JSONObject.NULL) {
          values.remove(key);
        } else {
          values.put(key, val);
        }
      }
    }
    String sdkStatus = applyToSdk(loader);
    return statusJson(sdkStatus);
  }

  public JSONObject clear(ClassLoader loader) {
    values.clear();
    String sdkStatus = applyToSdk(loader);
    return statusJson(sdkStatus);
  }

  public JSONObject statusJson(String sdkStatus) {
    JSONObject out = new JSONObject();
    try {
      JSONObject map = new JSONObject();
      for (Map.Entry<String, Object> e : values.entrySet()) {
        map.put(e.getKey(), e.getValue());
      }
      out.put("values", map);
      out.put("count", values.size());
      if (sdkStatus != null) out.put("sdkStatus", sdkStatus);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  String applyToSdk(ClassLoader loader) {
    if (loader == null) return "noClassLoader";
    try {
      Class<?> cls = Class.forName(REMOTE_CONFIG_CLASS, false, loader);
      Object instance = cls.getMethod("getInstance").invoke(null);
      if (instance == null) return "notInitialized";
      Method setDefaults = cls.getMethod("setDefaultsAsync", Map.class);
      setDefaults.invoke(instance, new LinkedHashMap<>(values));
      return "applied";
    } catch (ClassNotFoundException e) {
      return "notInApp";
    } catch (Throwable t) {
      Throwable cause = t.getCause() != null ? t.getCause() : t;
      return "error: " + cause.getMessage();
    }
  }
}
