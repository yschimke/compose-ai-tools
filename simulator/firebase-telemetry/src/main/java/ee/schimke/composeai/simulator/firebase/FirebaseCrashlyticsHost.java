package ee.schimke.composeai.simulator.firebase;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * In-memory capture store for Firebase Crashlytics non-fatal exceptions, logs, custom keys, and
 * user ID, populated by {@link
 * ee.schimke.composeai.simulator.shadows.firebase.ShadowFirebaseCrashlytics} and inspected via the
 * simulator's {@code firebase} control command.
 */
public final class FirebaseCrashlyticsHost {

  private static final int MAX_LOGS = 500;
  private static final int MAX_EXCEPTIONS = 100;

  public static final class RecordedException {
    public final String className;
    public final String message;
    public final String stackTrace;
    public final long timestampMs;

    RecordedException(String className, String message, String stackTrace, long timestampMs) {
      this.className = className;
      this.message = message;
      this.stackTrace = stackTrace;
      this.timestampMs = timestampMs;
    }

    public JSONObject toJson() {
      JSONObject obj = new JSONObject();
      try {
        obj.put("className", className);
        if (message != null) obj.put("message", message);
        obj.put("stackTrace", stackTrace);
        obj.put("timestampMs", timestampMs);
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      return obj;
    }
  }

  private static final List<RecordedException> exceptions = new CopyOnWriteArrayList<>();
  private static final List<String> logs = new CopyOnWriteArrayList<>();
  private static final Map<String, Object> customKeys = new ConcurrentHashMap<>();
  private static volatile String userId;
  private static volatile boolean collectionEnabled = true;

  private FirebaseCrashlyticsHost() {}

  public static void recordException(Throwable throwable) {
    if (throwable == null) return;
    StringWriter sw = new StringWriter();
    throwable.printStackTrace(new PrintWriter(sw));
    exceptions.add(
        new RecordedException(
            throwable.getClass().getName(),
            throwable.getMessage(),
            sw.toString(),
            System.currentTimeMillis()));
    while (exceptions.size() > MAX_EXCEPTIONS) exceptions.remove(0);
  }

  public static void log(String message) {
    if (message == null) return;
    logs.add(message);
    while (logs.size() > MAX_LOGS) logs.remove(0);
  }

  public static void setCustomKey(String key, Object value) {
    if (key == null) return;
    if (value == null) {
      customKeys.remove(key);
    } else {
      customKeys.put(key, value);
    }
  }

  public static void setUserId(String id) {
    userId = id;
  }

  public static void setCollectionEnabled(boolean enabled) {
    collectionEnabled = enabled;
  }

  public static List<RecordedException> exceptions() {
    return new ArrayList<>(exceptions);
  }

  public static List<String> logs() {
    return new ArrayList<>(logs);
  }

  public static Map<String, Object> customKeys() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(customKeys));
  }

  public static String userId() {
    return userId;
  }

  public static void clear() {
    exceptions.clear();
    logs.clear();
    customKeys.clear();
    userId = null;
    collectionEnabled = true;
  }

  public static JSONObject toJson() {
    JSONObject out = new JSONObject();
    try {
      JSONArray exArr = new JSONArray();
      for (RecordedException ex : exceptions) exArr.put(ex.toJson());
      out.put("exceptions", exArr);
      out.put("exceptionCount", exArr.length());

      JSONArray logArr = new JSONArray();
      for (String line : logs) logArr.put(line);
      out.put("logs", logArr);

      JSONObject keys = new JSONObject();
      for (Map.Entry<String, Object> entry : customKeys.entrySet()) {
        keys.put(entry.getKey(), entry.getValue());
      }
      out.put("customKeys", keys);
      if (userId != null) out.put("userId", userId);
      out.put("collectionEnabled", collectionEnabled);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }
}
