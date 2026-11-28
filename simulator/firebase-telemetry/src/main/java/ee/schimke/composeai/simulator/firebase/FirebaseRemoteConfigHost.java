package ee.schimke.composeai.simulator.firebase;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Local simulator backing for Firebase Remote Config, which has no official Firebase Local
 * Emulator.
 *
 * <p>Holds simulator-controlled key/value overrides that take precedence over activated, fetched,
 * and default Remote Config values via {@link
 * ee.schimke.composeai.simulator.shadows.firebase.ShadowFirebaseRemoteConfig}, and also syncs
 * defaults reflectively into {@code FirebaseRemoteConfig.setDefaultsAsync(Map)} as a fallback.
 */
public final class FirebaseRemoteConfigHost {

  private static final String REMOTE_CONFIG_CLASS =
      "com.google.firebase.remoteconfig.FirebaseRemoteConfig";

  private static final Map<String, Object> overrides = new ConcurrentHashMap<>();

  public static boolean hasOverride(String key) {
    return key != null && overrides.containsKey(key);
  }

  public static Object overrideValue(String key) {
    return key == null ? null : overrides.get(key);
  }

  public static String getStringOverride(String key) {
    Object val = overrideValue(key);
    return val == null ? "" : String.valueOf(val);
  }

  public static boolean getBooleanOverride(String key) {
    Object val = overrideValue(key);
    if (val instanceof Boolean) return (Boolean) val;
    if (val instanceof Number) return ((Number) val).doubleValue() != 0.0;
    if (val == null) return false;
    String s = String.valueOf(val).trim().toLowerCase(Locale.ROOT);
    return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s) || "on".equals(s);
  }

  public static long getLongOverride(String key) {
    Object val = overrideValue(key);
    if (val instanceof Number) return ((Number) val).longValue();
    if (val == null) return 0L;
    try {
      return Long.parseLong(String.valueOf(val).trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  public static double getDoubleOverride(String key) {
    Object val = overrideValue(key);
    if (val instanceof Number) return ((Number) val).doubleValue();
    if (val == null) return 0.0;
    try {
      return Double.parseDouble(String.valueOf(val).trim());
    } catch (NumberFormatException e) {
      return 0.0;
    }
  }

  public static Set<String> keysByPrefix(String prefix) {
    String p = prefix == null ? "" : prefix;
    Set<String> out = new LinkedHashSet<>();
    for (String key : overrides.keySet()) {
      if (key.startsWith(p)) out.add(key);
    }
    return out;
  }

  public static void reset() {
    overrides.clear();
  }

  public Map<String, Object> values() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
  }

  /**
   * Merges {@code updates} into the simulator Remote Config override store (evaluated above
   * activated/fetched/default values by {@code ShadowFirebaseRemoteConfig}) and also syncs defaults
   * into the SDK if present in {@code loader}.
   */
  public JSONObject setValues(JSONObject updates, ClassLoader loader) {
    if (updates != null) {
      Iterator<String> keys = updates.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object val = updates.opt(key);
        if (val == null || val == JSONObject.NULL) {
          overrides.remove(key);
        } else {
          overrides.put(key, val);
        }
      }
    }
    String sdkStatus = applyToSdk(loader);
    return statusJson(sdkStatus);
  }

  public JSONObject clear(ClassLoader loader) {
    overrides.clear();
    String sdkStatus = applyToSdk(loader);
    return statusJson(sdkStatus);
  }

  public JSONObject statusJson(String sdkStatus) {
    JSONObject out = new JSONObject();
    try {
      JSONObject map = new JSONObject();
      for (Map.Entry<String, Object> e : overrides.entrySet()) {
        map.put(e.getKey(), e.getValue());
      }
      out.put("values", map);
      out.put("count", overrides.size());
      out.put("precedence", "overrideAboveActivated");
      if (sdkStatus != null) out.put("sdkStatus", sdkStatus);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  String applyToSdk(ClassLoader loader) {
    if (loader == null) return "noClassLoader";
    try {
      Class.forName(REMOTE_CONFIG_CLASS, false, loader);
      // ShadowFirebaseRemoteConfig evaluates simulator overrides above activated, fetched, and
      // default values without mutating the SDK's persisted defaults container.
      return "shadowActive";
    } catch (ClassNotFoundException e) {
      return "notInApp";
    }
  }
}
