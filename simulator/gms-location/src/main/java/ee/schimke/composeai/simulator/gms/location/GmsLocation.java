package ee.schimke.composeai.simulator.gms.location;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import ee.schimke.composeai.simulator.PlayServices;
import ee.schimke.composeai.simulator.SimulatorHooks;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Stateful simulation engine for Google Play services Location ({@code FusedLocationProviderClient},
 * {@code GeofencingClient}, {@code SettingsClient}, and {@code ActivityRecognitionClient}).
 *
 * <p>Installed into the simulator session by {@link #install(Application)} and wired to {@link
 * SimulatorHooks#setLocationObserver} so the existing {@code location} command automatically feeds
 * GMS fused location listeners and evaluates registered geofences.
 */
public final class GmsLocation {

  public static final int GEOFENCE_TRANSITION_ENTER = 1;
  public static final int GEOFENCE_TRANSITION_EXIT = 2;
  public static final int GEOFENCE_TRANSITION_DWELL = 4;

  // DetectedActivity constants matching com.google.android.gms.location.DetectedActivity.
  public static final int ACTIVITY_IN_VEHICLE = 0;
  public static final int ACTIVITY_ON_BICYCLE = 1;
  public static final int ACTIVITY_ON_FOOT = 2;
  public static final int ACTIVITY_STILL = 3;
  public static final int ACTIVITY_UNKNOWN = 4;
  public static final int ACTIVITY_TILTING = 5;
  public static final int ACTIVITY_WALKING = 7;
  public static final int ACTIVITY_RUNNING = 8;

  private static volatile GmsLocation active;

  private final Context context;
  private final Executor main;

  private volatile Location lastLocation;
  private volatile boolean locationAvailable = true;
  private volatile boolean settingsEnabled = true;
  private volatile boolean mockMode = false;
  private volatile Location mockLocation;

  private volatile int currentActivityType = ACTIVITY_STILL;
  private volatile int currentActivityConfidence = 100;

  private final List<CallbackEntry> locationCallbacks = new CopyOnWriteArrayList<>();
  private final List<ListenerEntry> locationListeners = new CopyOnWriteArrayList<>();
  private final List<PendingIntent> locationPendingIntents = new CopyOnWriteArrayList<>();
  private final Map<String, GeofenceEntry> geofences =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private final List<PendingIntent> activityUpdateIntents = new CopyOnWriteArrayList<>();
  private final List<PendingIntent> activityTransitionIntents = new CopyOnWriteArrayList<>();

  static final class CallbackEntry {
    final Object callback;
    final Executor executor;

    CallbackEntry(Object callback, Executor executor) {
      this.callback = callback;
      this.executor = executor;
    }
  }

  static final class ListenerEntry {
    final Object listener;
    final Executor executor;

    ListenerEntry(Object listener, Executor executor) {
      this.listener = listener;
      this.executor = executor;
    }
  }

  static final class GeofenceEntry {
    final String requestId;
    final double latitude;
    final double longitude;
    final float radiusMeters;
    final int transitionTypes;
    final PendingIntent pendingIntent;
    boolean inside;

    GeofenceEntry(
        String requestId,
        double latitude,
        double longitude,
        float radiusMeters,
        int transitionTypes,
        PendingIntent pendingIntent) {
      this.requestId = requestId;
      this.latitude = latitude;
      this.longitude = longitude;
      this.radiusMeters = radiusMeters;
      this.transitionTypes = transitionTypes;
      this.pendingIntent = pendingIntent;
    }
  }

  GmsLocation(Context context, Executor main) {
    this.context = context;
    this.main = main;
  }

  /** Installs a fresh {@link GmsLocation} instance for the running simulator session. */
  public static GmsLocation install(Application app) {
    Handler handler = new Handler(Looper.getMainLooper());
    GmsLocation instance = new GmsLocation(app, handler::post);
    active = instance;
    SimulatorHooks.setLocationObserver(instance::onLocationUpdate);
    PlayServices.registerSimulatedApi(GmsLocationClients::isLocationClient);
    return instance;
  }

  /** Installs an instance with a custom main executor (for JVM unit tests). */
  static GmsLocation installForTest(Context context, Executor main) {
    GmsLocation instance = new GmsLocation(context, main);
    active = instance;
    SimulatorHooks.setLocationObserver(instance::onLocationUpdate);
    PlayServices.registerSimulatedApi(GmsLocationClients::isLocationClient);
    return instance;
  }

  public static GmsLocation current() {
    return active;
  }

  public static void resetCurrent() {
    active = null;
    SimulatorHooks.setLocationObserver(null);
  }

  static GmsLocation getOrCreate(Context context) {
    GmsLocation cur = active;
    if (cur != null) return cur;
    Executor exec;
    try {
      Handler h = new Handler(Looper.getMainLooper());
      exec = h::post;
    } catch (RuntimeException e) {
      exec = Runnable::run;
    }
    GmsLocation created = new GmsLocation(context, exec);
    active = created;
    PlayServices.registerSimulatedApi(GmsLocationClients::isLocationClient);
    return created;
  }

  Executor mainExecutor() {
    return main;
  }

  Context context() {
    return context;
  }

  // ---- FusedLocationProviderClient state & delivery ----

  Location effectiveLastLocation() {
    if (!locationAvailable) return null;
    return mockMode && mockLocation != null ? mockLocation : lastLocation;
  }

  boolean isLocationAvailable() {
    return locationAvailable;
  }

  boolean isSettingsEnabled() {
    return settingsEnabled;
  }

  void setMockMode(boolean enabled) {
    this.mockMode = enabled;
    if (!enabled) this.mockLocation = null;
  }

  void setMockLocation(Location location) {
    this.mockLocation = location;
    if (mockMode && location != null) {
      deliverLocation(location);
    }
  }

  void registerLocationCallback(Object callback, Executor executor) {
    if (callback == null) return;
    unregisterLocationCallback(callback);
    locationCallbacks.add(new CallbackEntry(callback, executor != null ? executor : main));
    // Deliver current availability and last location if already known.
    Location current = effectiveLastLocation();
    if (current != null) {
      deliverToCallback(
          locationCallbacks.get(locationCallbacks.size() - 1), current, locationAvailable);
    }
  }

  void unregisterLocationCallback(Object callback) {
    locationCallbacks.removeIf(entry -> entry.callback == callback);
  }

  void registerLocationListener(Object listener, Executor executor) {
    if (listener == null) return;
    unregisterLocationListener(listener);
    locationListeners.add(new ListenerEntry(listener, executor != null ? executor : main));
  }

  void unregisterLocationListener(Object listener) {
    locationListeners.removeIf(entry -> entry.listener == listener);
  }

  void registerLocationPendingIntent(PendingIntent pendingIntent) {
    if (pendingIntent != null && !locationPendingIntents.contains(pendingIntent)) {
      locationPendingIntents.add(pendingIntent);
    }
  }

  void unregisterLocationPendingIntent(PendingIntent pendingIntent) {
    locationPendingIntents.remove(pendingIntent);
  }

  void flushLocations() {
    Location current = effectiveLastLocation();
    if (current != null) {
      deliverLocation(current);
    }
  }

  /** Receives a simulated location fix (from {@code location} or {@code gmsLocation} commands). */
  public void onLocationUpdate(Location fix) {
    if (fix == null) return;
    this.lastLocation = fix;
    if (!mockMode && locationAvailable) {
      deliverLocation(fix);
    }
    evaluateGeofences(fix);
  }

  public void setLocationAvailable(boolean available) {
    boolean changed = this.locationAvailable != available;
    this.locationAvailable = available;
    if (changed) {
      for (CallbackEntry entry : locationCallbacks) {
        ClassLoader loader = entry.callback.getClass().getClassLoader();
        Object availObj = createLocationAvailability(loader, available);
        if (availObj != null) {
          entry.executor.execute(() -> invokeOnLocationAvailability(entry.callback, availObj));
        }
      }
    }
  }

  public void setSettingsEnabled(boolean enabled) {
    this.settingsEnabled = enabled;
  }

  private void deliverLocation(Location fix) {
    for (CallbackEntry entry : locationCallbacks) {
      deliverToCallback(entry, fix, locationAvailable);
    }
    for (ListenerEntry entry : locationListeners) {
      entry.executor.execute(() -> invokeOnLocationChanged(entry.listener, fix));
    }
    for (PendingIntent pi : locationPendingIntents) {
      Intent fill = new Intent();
      fill.putExtra("com.google.android.gms.location.EXTRA_LOCATION_RESULT_BYTES", fix);
      sendPendingIntent(pi, fill);
    }
  }

  private void deliverToCallback(CallbackEntry entry, Location fix, boolean available) {
    ClassLoader loader = entry.callback.getClass().getClassLoader();
    Object result = createLocationResult(loader, fix);
    if (result != null) {
      entry.executor.execute(() -> invokeOnLocationResult(entry.callback, result));
    }
  }

  // ---- GeofencingClient state & evaluation ----

  void addGeofences(Object geofencingRequest, PendingIntent pendingIntent) {
    if (geofencingRequest == null || pendingIntent == null) return;
    List<?> list = extractGeofences(geofencingRequest);
    for (Object gf : list) {
      GeofenceEntry entry = toGeofenceEntry(gf, pendingIntent);
      if (entry != null) {
        geofences.put(entry.requestId, entry);
      }
    }
    Location current = effectiveLastLocation();
    if (current != null) evaluateGeofences(current);
  }

  void removeGeofencesByIds(List<String> ids) {
    if (ids == null) return;
    for (String id : ids) geofences.remove(id);
  }

  void removeGeofencesByPendingIntent(PendingIntent pendingIntent) {
    if (pendingIntent == null) return;
    synchronized (geofences) {
      Iterator<Map.Entry<String, GeofenceEntry>> it = geofences.entrySet().iterator();
      while (it.hasNext()) {
        if (pendingIntent.equals(it.next().getValue().pendingIntent)) {
          it.remove();
        }
      }
    }
  }

  private void evaluateGeofences(Location fix) {
    List<GeofenceEntry> snapshot;
    synchronized (geofences) {
      snapshot = new ArrayList<>(geofences.values());
    }
    for (GeofenceEntry gf : snapshot) {
      double distMeters =
          haversineMeters(fix.getLatitude(), fix.getLongitude(), gf.latitude, gf.longitude);
      boolean nowInside = distMeters <= gf.radiusMeters;
      if (nowInside && !gf.inside) {
        gf.inside = true;
        if ((gf.transitionTypes & GEOFENCE_TRANSITION_ENTER) != 0) {
          fireGeofenceTransition(gf, GEOFENCE_TRANSITION_ENTER, fix);
        }
      } else if (!nowInside && gf.inside) {
        gf.inside = false;
        if ((gf.transitionTypes & GEOFENCE_TRANSITION_EXIT) != 0) {
          fireGeofenceTransition(gf, GEOFENCE_TRANSITION_EXIT, fix);
        }
      }
    }
  }

  public boolean triggerGeofence(String requestId, int transitionType) {
    GeofenceEntry gf = geofences.get(requestId);
    if (gf == null) return false;
    if (transitionType == GEOFENCE_TRANSITION_ENTER) gf.inside = true;
    else if (transitionType == GEOFENCE_TRANSITION_EXIT) gf.inside = false;
    fireGeofenceTransition(gf, transitionType, effectiveLastLocation());
    return true;
  }

  private void fireGeofenceTransition(GeofenceEntry gf, int transitionType, Location triggering) {
    Intent fill = new Intent();
    fill.putExtra("com.google.android.location.intent.extra.transition", transitionType);
    fill.putExtra("com.google.android.location.intent.extra.geofence_request_id", gf.requestId);
    if (triggering != null) {
      fill.putExtra("com.google.android.location.intent.extra.triggering_location", triggering);
    }
    sendPendingIntent(gf.pendingIntent, fill);
  }

  // ---- ActivityRecognitionClient state ----

  void registerActivityUpdates(PendingIntent pendingIntent) {
    if (pendingIntent != null && !activityUpdateIntents.contains(pendingIntent)) {
      activityUpdateIntents.add(pendingIntent);
    }
  }

  void unregisterActivityUpdates(PendingIntent pendingIntent) {
    activityUpdateIntents.remove(pendingIntent);
  }

  void registerActivityTransitions(PendingIntent pendingIntent) {
    if (pendingIntent != null && !activityTransitionIntents.contains(pendingIntent)) {
      activityTransitionIntents.add(pendingIntent);
    }
  }

  void unregisterActivityTransitions(PendingIntent pendingIntent) {
    activityTransitionIntents.remove(pendingIntent);
  }

  public void setActivity(int activityType, int confidence) {
    int prev = this.currentActivityType;
    this.currentActivityType = activityType;
    this.currentActivityConfidence = Math.max(0, Math.min(100, confidence));
    for (PendingIntent pi : activityUpdateIntents) {
      Intent fill = new Intent();
      fill.putExtra("com.google.android.location.internal.EXTRA_ACTIVITY_TYPE", activityType);
      fill.putExtra(
          "com.google.android.location.internal.EXTRA_ACTIVITY_CONFIDENCE",
          this.currentActivityConfidence);
      sendPendingIntent(pi, fill);
    }
    if (prev != activityType) {
      for (PendingIntent pi : activityTransitionIntents) {
        Intent fill = new Intent();
        fill.putExtra("com.google.android.location.internal.EXTRA_PREV_ACTIVITY_TYPE", prev);
        fill.putExtra("com.google.android.location.internal.EXTRA_ACTIVITY_TYPE", activityType);
        sendPendingIntent(pi, fill);
      }
    }
  }

  // ---- Control-channel command ("gmsLocation") ----

  public JSONObject command(JSONObject c) throws JSONException {
    if ("clear".equals(c.optString("action"))) {
      lastLocation = null;
      mockLocation = null;
      mockMode = false;
      locationAvailable = true;
      settingsEnabled = true;
      currentActivityType = ACTIVITY_STILL;
      currentActivityConfidence = 100;
      geofences.clear();
      return describe();
    }
    if (c.has("available")) {
      setLocationAvailable(c.getBoolean("available"));
    }
    if (c.has("settingsEnabled")) {
      setSettingsEnabled(c.getBoolean("settingsEnabled"));
    }
    if (c.has("lat") && (c.has("lng") || c.has("lon"))) {
      Location fix = new Location("fused");
      fix.setLatitude(c.getDouble("lat"));
      fix.setLongitude(c.has("lng") ? c.getDouble("lng") : c.getDouble("lon"));
      fix.setAccuracy((float) c.optDouble("accuracy", 5.0));
      if (c.has("altitude")) fix.setAltitude(c.getDouble("altitude"));
      if (c.has("bearing")) fix.setBearing((float) c.getDouble("bearing"));
      if (c.has("speed")) fix.setSpeed((float) c.getDouble("speed"));
      fix.setTime(SimulatorHooks.currentTimeMillis());
      onLocationUpdate(fix);
    }
    if (c.has("activity")) {
      int type = parseActivity(c.getString("activity"));
      int conf = c.optInt("confidence", 100);
      setActivity(type, conf);
    }
    if (c.has("geofence")) {
      String id = c.getString("geofence");
      int transition = parseTransition(c.optString("transition", "enter"));
      boolean matched = triggerGeofence(id, transition);
      JSONObject out = describe();
      out.put("geofenceTriggered", matched);
      return out;
    }
    return describe();
  }

  public JSONObject describe() throws JSONException {
    JSONObject out = new JSONObject();
    out.put("available", locationAvailable);
    out.put("settingsEnabled", settingsEnabled);
    out.put("mockMode", mockMode);
    Location loc = effectiveLastLocation();
    if (loc != null) {
      JSONObject locJson = new JSONObject();
      locJson.put("lat", loc.getLatitude());
      locJson.put("lng", loc.getLongitude());
      locJson.put("accuracy", loc.getAccuracy());
      out.put("lastLocation", locJson);
    }
    out.put("activity", activityName(currentActivityType));
    out.put("activityConfidence", currentActivityConfidence);
    out.put(
        "listeners",
        locationCallbacks.size() + locationListeners.size() + locationPendingIntents.size());
    JSONArray gfArray = new JSONArray();
    synchronized (geofences) {
      for (GeofenceEntry gf : geofences.values()) {
        JSONObject g = new JSONObject();
        g.put("id", gf.requestId);
        g.put("lat", gf.latitude);
        g.put("lng", gf.longitude);
        g.put("radiusMeters", gf.radiusMeters);
        g.put("inside", gf.inside);
        gfArray.put(g);
      }
    }
    out.put("geofences", gfArray);
    return out;
  }

  // ---- Helpers ----

  private void sendPendingIntent(PendingIntent pi, Intent fill) {
    if (pi == null) return;
    main.execute(
        () -> {
          try {
            pi.send(context, 0, fill);
          } catch (PendingIntent.CanceledException | RuntimeException ignored) {
          }
        });
  }

  static Object createLocationResult(ClassLoader loader, Location location) {
    try {
      Class<?> cls =
          Class.forName("com.google.android.gms.location.LocationResult", false, loader);
      Method create = cls.getMethod("create", List.class);
      return create.invoke(null, Collections.singletonList(location));
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  static Object createLocationAvailability(ClassLoader loader, boolean available) {
    try {
      Class<?> cls =
          Class.forName("com.google.android.gms.location.LocationAvailability", false, loader);
      // Try constructor(int locationStatus, int cellStatus, int wifiStatus, long elapsedRealtimeNs,
      // NetworkLocationStatus[]) or (int, int, int, long, ...) — status 0 = available, 1000 = not.
      int status = available ? 0 : 1000;
      for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
        Class<?>[] p = ctor.getParameterTypes();
        if (p.length == 5
            && p[0] == int.class
            && p[1] == int.class
            && p[2] == int.class
            && p[3] == long.class) {
          ctor.setAccessible(true);
          return ctor.newInstance(status, available ? 1 : 0, available ? 1 : 0, 0L, null);
        }
        if (p.length == 1 && p[0] == boolean.class) {
          ctor.setAccessible(true);
          return ctor.newInstance(available);
        }
      }
      return null;
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  static Object createLocationSettingsResponse(ClassLoader loader, boolean enabled) {
    try {
      Class<?> statesCls =
          Class.forName("com.google.android.gms.location.LocationSettingsStates", false, loader);
      Constructor<?> statesCtor =
          statesCls.getDeclaredConstructor(
              boolean.class,
              boolean.class,
              boolean.class,
              boolean.class,
              boolean.class,
              boolean.class);
      statesCtor.setAccessible(true);
      Object states = statesCtor.newInstance(enabled, enabled, enabled, true, true, true);

      Class<?> statusCls =
          Class.forName("com.google.android.gms.common.api.Status", false, loader);
      Constructor<?> statusCtor = statusCls.getDeclaredConstructor(int.class);
      statusCtor.setAccessible(true);
      Object status = statusCtor.newInstance(0);

      Class<?> resultCls =
          Class.forName("com.google.android.gms.location.LocationSettingsResult", false, loader);
      Constructor<?> resultCtor = resultCls.getDeclaredConstructor(statusCls, statesCls);
      resultCtor.setAccessible(true);
      Object result = resultCtor.newInstance(status, states);

      Class<?> respCls =
          Class.forName("com.google.android.gms.location.LocationSettingsResponse", false, loader);
      Constructor<?> respCtor = respCls.getDeclaredConstructor(resultCls);
      respCtor.setAccessible(true);
      return respCtor.newInstance(result);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static void invokeOnLocationResult(Object callback, Object result) {
    try {
      for (Method m : callback.getClass().getMethods()) {
        if ("onLocationResult".equals(m.getName()) && m.getParameterCount() == 1) {
          m.invoke(callback, result);
          return;
        }
      }
    } catch (ReflectiveOperationException ignored) {
    }
  }

  private static void invokeOnLocationAvailability(Object callback, Object availability) {
    try {
      for (Method m : callback.getClass().getMethods()) {
        if ("onLocationAvailability".equals(m.getName()) && m.getParameterCount() == 1) {
          m.invoke(callback, availability);
          return;
        }
      }
    } catch (ReflectiveOperationException ignored) {
    }
  }

  private static void invokeOnLocationChanged(Object listener, Location fix) {
    try {
      Method m = listener.getClass().getMethod("onLocationChanged", Location.class);
      m.invoke(listener, fix);
    } catch (ReflectiveOperationException ignored) {
    }
  }

  private static List<?> extractGeofences(Object request) {
    try {
      Method m = request.getClass().getMethod("getGeofences");
      Object res = m.invoke(request);
      if (res instanceof List<?>) return (List<?>) res;
    } catch (ReflectiveOperationException ignored) {
    }
    return Collections.emptyList();
  }

  private static GeofenceEntry toGeofenceEntry(Object gf, PendingIntent pi) {
    if (gf == null) return null;
    String id = readString(gf, "getRequestId", "zza");
    if (id == null) return null;
    double lat = readDouble(gf, "getLatitude", "zzd", 0.0);
    double lng = readDouble(gf, "getLongitude", "zze", 0.0);
    float radius = (float) readDouble(gf, "getRadius", "zzf", 100.0);
    int transitions =
        readInt(gf, "getTransitionTypes", "zzg", GEOFENCE_TRANSITION_ENTER | GEOFENCE_TRANSITION_EXIT);
    return new GeofenceEntry(id, lat, lng, radius, transitions, pi);
  }

  private static String readString(Object target, String method, String field) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof String) return (String) v;
    } catch (ReflectiveOperationException ignored) {
    }
    try {
      Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      Object v = f.get(target);
      if (v instanceof String) return (String) v;
    } catch (ReflectiveOperationException ignored) {
    }
    return null;
  }

  private static double readDouble(Object target, String method, String field, double fallback) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof Number) return ((Number) v).doubleValue();
    } catch (ReflectiveOperationException ignored) {
    }
    try {
      Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      Object v = f.get(target);
      if (v instanceof Number) return ((Number) v).doubleValue();
    } catch (ReflectiveOperationException ignored) {
    }
    return fallback;
  }

  private static int readInt(Object target, String method, String field, int fallback) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof Number) return ((Number) v).intValue();
    } catch (ReflectiveOperationException ignored) {
    }
    try {
      Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      Object v = f.get(target);
      if (v instanceof Number) return ((Number) v).intValue();
    } catch (ReflectiveOperationException ignored) {
    }
    return fallback;
  }

  static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double r = 6_371_000.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  static int parseActivity(String name) {
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "IN_VEHICLE":
      case "VEHICLE":
      case "DRIVING":
        return ACTIVITY_IN_VEHICLE;
      case "ON_BICYCLE":
      case "BICYCLE":
      case "CYCLING":
        return ACTIVITY_ON_BICYCLE;
      case "ON_FOOT":
        return ACTIVITY_ON_FOOT;
      case "STILL":
        return ACTIVITY_STILL;
      case "TILTING":
        return ACTIVITY_TILTING;
      case "WALKING":
        return ACTIVITY_WALKING;
      case "RUNNING":
        return ACTIVITY_RUNNING;
      default:
        return ACTIVITY_UNKNOWN;
    }
  }

  static String activityName(int type) {
    switch (type) {
      case ACTIVITY_IN_VEHICLE:
        return "IN_VEHICLE";
      case ACTIVITY_ON_BICYCLE:
        return "ON_BICYCLE";
      case ACTIVITY_ON_FOOT:
        return "ON_FOOT";
      case ACTIVITY_STILL:
        return "STILL";
      case ACTIVITY_TILTING:
        return "TILTING";
      case ACTIVITY_WALKING:
        return "WALKING";
      case ACTIVITY_RUNNING:
        return "RUNNING";
      default:
        return "UNKNOWN";
    }
  }

  static int parseTransition(String name) {
    switch (name.trim().toLowerCase(Locale.ROOT)) {
      case "exit":
        return GEOFENCE_TRANSITION_EXIT;
      case "dwell":
        return GEOFENCE_TRANSITION_DWELL;
      default:
        return GEOFENCE_TRANSITION_ENTER;
    }
  }
}
