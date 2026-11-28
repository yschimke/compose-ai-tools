package ee.schimke.composeai.simulator.firebase;

import android.os.Bundle;
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
 * In-memory capture store for Firebase Analytics events, user properties, and user ID, populated by
 * {@link ee.schimke.composeai.simulator.shadows.firebase.ShadowFirebaseAnalytics} and inspected via
 * the simulator's {@code firebase} control command.
 */
public final class FirebaseAnalyticsHost {

  private static final int MAX_EVENTS = 500;

  public static final class Event {
    public final String name;
    public final Map<String, Object> params;
    public final long timestampMs;

    Event(String name, Map<String, Object> params, long timestampMs) {
      this.name = name;
      this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
      this.timestampMs = timestampMs;
    }

    public JSONObject toJson() {
      JSONObject obj = new JSONObject();
      try {
        obj.put("name", name);
        obj.put("timestampMs", timestampMs);
        JSONObject p = new JSONObject();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
          p.put(entry.getKey(), entry.getValue());
        }
        obj.put("params", p);
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      return obj;
    }
  }

  private static final List<Event> events = new CopyOnWriteArrayList<>();
  private static final Map<String, String> userProperties = new ConcurrentHashMap<>();
  private static volatile String userId;
  private static volatile boolean collectionEnabled = true;

  private FirebaseAnalyticsHost() {}

  public static void recordEvent(String name, Bundle bundle) {
    Map<String, Object> params = new LinkedHashMap<>();
    if (bundle != null) {
      for (String key : bundle.keySet()) {
        Object val = bundle.get(key);
        if (val != null) params.put(key, normalizeValue(val));
      }
    }
    recordEvent(name, params);
  }

  public static void recordEvent(String name, Map<String, Object> params) {
    if (name == null) return;
    events.add(
        new Event(
            name,
            params == null ? Collections.emptyMap() : params,
            System.currentTimeMillis()));
    while (events.size() > MAX_EVENTS) events.remove(0);
  }

  public static void setUserProperty(String name, String value) {
    if (name == null) return;
    if (value == null) {
      userProperties.remove(name);
    } else {
      userProperties.put(name, value);
    }
  }

  public static void setUserId(String id) {
    userId = id;
  }

  public static void setCollectionEnabled(boolean enabled) {
    collectionEnabled = enabled;
  }

  public static List<Event> events() {
    return new ArrayList<>(events);
  }

  public static Map<String, String> userProperties() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
  }

  public static String userId() {
    return userId;
  }

  public static boolean collectionEnabled() {
    return collectionEnabled;
  }

  public static void clear() {
    events.clear();
    userProperties.clear();
    userId = null;
    collectionEnabled = true;
  }

  public static JSONObject toJson(String nameFilter) {
    JSONObject out = new JSONObject();
    try {
      JSONArray arr = new JSONArray();
      for (Event e : events) {
        if (nameFilter == null || nameFilter.isEmpty() || nameFilter.equals(e.name)) {
          arr.put(e.toJson());
        }
      }
      out.put("events", arr);
      out.put("count", arr.length());
      out.put("totalCount", events.size());
      out.put("collectionEnabled", collectionEnabled);
      if (userId != null) out.put("userId", userId);
      JSONObject props = new JSONObject();
      for (Map.Entry<String, String> entry : userProperties.entrySet()) {
        props.put(entry.getKey(), entry.getValue());
      }
      out.put("userProperties", props);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static Object normalizeValue(Object val) {
    if (val instanceof String || val instanceof Number || val instanceof Boolean) {
      return val;
    }
    return String.valueOf(val);
  }
}
