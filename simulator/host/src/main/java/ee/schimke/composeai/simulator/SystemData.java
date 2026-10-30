package ee.schimke.composeai.simulator;

import android.app.Application;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The watch's system data that tiles and widgets bind to without asking the app: Wear Health
 * Services' heart rate and the daily step, distance, calorie and floor counts. On a watch the tile
 * renderer reads them from the platform's sensor gateway; here they are values the agent sets
 * with {@code health}, delivered to every renderer as a protolayout {@code PlatformDataProvider}
 * for the {@code PlatformHealthSources.Keys}, so a tile's {@code heartRateBpm()} text and a step
 * count arc update as they would, and to every Wear Widget's Remote Compose player as the named
 * values Glance Wear's {@code HealthData} reads ({@link #widgetValues}).
 *
 * <p>The provider is a {@link Proxy} of the renderer's interface, loaded through the sandbox's
 * class loader: nothing here links against protolayout, whose version is the app's.
 */
final class SystemData {

  private static final String PIPELINE = "androidx.wear.protolayout.expression.pipeline.";
  private static final String EXPRESSION = "androidx.wear.protolayout.expression.";

  private final Application app;
  /** The current values by key name ({@code HEART_RATE_BPM}, ...), as JSON numbers. */
  private final Map<String, Number> values = new LinkedHashMap<>();
  /** Every renderer's receiver, with the executor it asked to be called on. */
  private final List<Object[]> receivers = new ArrayList<>();
  /** Widget players, told to read {@link #widgetValues} again after a change. */
  private final List<Runnable> listeners = new ArrayList<>();

  SystemData(Application app) {
    this.app = app;
    values.put("HEART_RATE_BPM", 72f);
    values.put("HEART_RATE_ACCURACY", 3);
    values.put("DAILY_STEPS", 4200);
    values.put("DAILY_DISTANCE_METERS", 3150f);
    values.put("DAILY_CALORIES", 180f);
    values.put("DAILY_FLOORS", 4f);
  }

  /** {@code health}: sets any of {@code heartRate}, {@code steps}, ... and returns them all. */
  JSONObject command(JSONObject c) throws JSONException {
    if (c.has("heartRate")) values.put("HEART_RATE_BPM", (float) c.getDouble("heartRate"));
    if (c.has("heartRateAccuracy")) {
      values.put("HEART_RATE_ACCURACY", accuracy(c.getString("heartRateAccuracy")));
    }
    if (c.has("steps")) values.put("DAILY_STEPS", c.getInt("steps"));
    if (c.has("distanceMeters")) {
      values.put("DAILY_DISTANCE_METERS", (float) c.getDouble("distanceMeters"));
    }
    if (c.has("calories")) values.put("DAILY_CALORIES", (float) c.getDouble("calories"));
    if (c.has("floors")) values.put("DAILY_FLOORS", (float) c.getDouble("floors"));
    publish();
    return describe();
  }

  JSONObject describe() {
    return new Protocol.Json()
        .put("heartRate", values.get("HEART_RATE_BPM"))
        .put("heartRateAccuracy", accuracyName(values.get("HEART_RATE_ACCURACY").intValue()))
        .put("steps", values.get("DAILY_STEPS"))
        .put("distanceMeters", values.get("DAILY_DISTANCE_METERS"))
        .put("calories", values.get("DAILY_CALORIES"))
        .put("floors", values.get("DAILY_FLOORS"))
        .put("receivers", receivers.size() + listeners.size())
        .object();
  }

  private static int accuracy(String name) {
    switch (name.toLowerCase(java.util.Locale.ROOT)) {
      case "unknown":
        return 0;
      case "unreliable":
      case "no_contact":
        return 1;
      case "low":
        return 2;
      case "medium":
        return 3;
      case "high":
        return 4;
      default:
        return Integer.parseInt(name);
    }
  }

  private static String accuracyName(int value) {
    String[] names = {"unknown", "unreliable", "low", "medium", "high"};
    return value >= 0 && value < names.length ? names[value] : Integer.toString(value);
  }

  /** Whether the app carries the protolayout health keys this needs. */
  boolean available() {
    try {
      Class.forName(EXPRESSION + "PlatformHealthSources$Keys", false, app.getClassLoader());
      Class.forName(PIPELINE + "PlatformDataProvider", false, app.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** The {@code PlatformDataKey}s this provides, as the renderer's builder takes them. */
  Object keys() throws ReflectiveOperationException {
    Class<?> keys = load(EXPRESSION + "PlatformHealthSources$Keys");
    Class<?> keyType = load(EXPRESSION + "PlatformDataKey");
    Object array = java.lang.reflect.Array.newInstance(keyType, values.size());
    int i = 0;
    for (String name : values.keySet()) {
      java.lang.reflect.Array.set(array, i++, keys.getField(name).get(null));
    }
    return array;
  }

  /** A new {@code PlatformDataProvider} for one renderer. */
  Object provider() throws ReflectiveOperationException {
    Class<?> type = load(PIPELINE + "PlatformDataProvider");
    InvocationHandler handler =
        new InvocationHandler() {
          private Object[] registration;

          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
              case "setReceiver":
                registration = new Object[] {args[0], args[1]};
                receivers.add(registration);
                send(registration);
                return null;
              case "clearReceiver":
                receivers.remove(registration);
                registration = null;
                return null;
              case "hashCode":
                return System.identityHashCode(proxy);
              case "equals":
                return proxy == args[0];
              case "toString":
                return "SystemData.provider";
              default:
                return null;
            }
          }
        };
    return Proxy.newProxyInstance(app.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private void publish() {
    for (Object[] registration : new ArrayList<>(receivers)) send(registration);
    for (Runnable listener : new ArrayList<>(listeners)) listener.run();
  }

  void onChange(Runnable listener) {
    listeners.add(listener);
  }

  /**
   * The same values under the Remote Compose names a Glance Wear widget reads them by ({@code
   * androidx.glance.wear.health.HealthData}): floats, ints, and the {@code *_available} flags as 1.
   */
  Map<String, Number> widgetValues() {
    Map<String, Number> named = new LinkedHashMap<>();
    named.put("metrics.heart_rate", values.get("HEART_RATE_BPM").floatValue());
    named.put("metrics.heart_rate_accuracy", values.get("HEART_RATE_ACCURACY").intValue());
    named.put("metrics.day.steps", values.get("DAILY_STEPS").intValue());
    named.put("metrics.day.calories", values.get("DAILY_CALORIES").floatValue());
    named.put("metrics.day.distance", values.get("DAILY_DISTANCE_METERS").floatValue());
    named.put("metrics.day.flights_of_stairs", values.get("DAILY_FLOORS").floatValue());
    for (String metric : new String[] {"steps", "calories", "distance", "flights_of_stairs"}) {
      named.put("metrics.day." + metric + "_available", 1);
    }
    return named;
  }

  /** Hands the current values to one receiver, on the executor it gave. */
  private void send(Object[] registration) {
    Executor executor = (Executor) registration[0];
    Object receiver = registration[1];
    Object data;
    try {
      data = platformDataValues();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot build the health values", e);
    }
    executor.execute(
        () -> {
          try {
            load(PIPELINE + "PlatformDataReceiver")
                .getMethod("onData", load(EXPRESSION + "PlatformDataValues"))
                .invoke(receiver, data);
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot deliver the health values", e);
          }
        });
  }

  private Object platformDataValues() throws ReflectiveOperationException {
    Class<?> keys = load(EXPRESSION + "PlatformHealthSources$Keys");
    Class<?> keyType = load(EXPRESSION + "PlatformDataKey");
    Class<?> dataValue = load(EXPRESSION + "DynamicDataBuilders$DynamicDataValue");
    Class<?> builderType = load(EXPRESSION + "PlatformDataValues$Builder");
    Object builder = builderType.getConstructor().newInstance();
    Method put = builderType.getMethod("put", keyType, dataValue);
    for (Map.Entry<String, Number> e : values.entrySet()) {
      Object value;
      if (e.getKey().equals("HEART_RATE_ACCURACY")) {
        value =
            load(EXPRESSION + "PlatformHealthSources$DynamicHeartRateAccuracy")
                .getMethod("dynamicDataValueOf", int.class)
                .invoke(null, e.getValue().intValue());
      } else if (e.getValue() instanceof Integer) {
        value = dataValue.getMethod("fromInt", int.class).invoke(null, e.getValue().intValue());
      } else {
        value =
            dataValue.getMethod("fromFloat", float.class).invoke(null, e.getValue().floatValue());
      }
      put.invoke(builder, keys.getField(e.getKey()).get(null), value);
    }
    return builderType.getMethod("build").invoke(builder);
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }
}
