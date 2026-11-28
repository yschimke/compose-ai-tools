package ee.schimke.composeai.simulator.gms.maps;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Stateful simulation engine for Google Play services Maps ({@code GoogleMap}, {@code MapView},
 * {@code MapsInitializer}, and {@code maps-compose}).
 *
 * <p>Tracks camera position, map type, markers, polylines, and circles; computes Web-Mercator
 * screen projections for {@link SimulatedMapCanvasView}; and handles the {@code maps}
 * control-channel command.
 */
public final class GmsMaps {

  public static final int MAP_TYPE_NONE = 0;
  public static final int MAP_TYPE_NORMAL = 1;
  public static final int MAP_TYPE_SATELLITE = 2;
  public static final int MAP_TYPE_TERRAIN = 3;
  public static final int MAP_TYPE_HYBRID = 4;

  private static volatile GmsMaps active;

  private final Executor main;
  private final AtomicInteger nextId = new AtomicInteger(1);

  private volatile double cameraLat = 37.4220;
  private volatile double cameraLng = -122.0841;
  private volatile float cameraZoom = 12f;
  private volatile float cameraTilt = 0f;
  private volatile float cameraBearing = 0f;
  private volatile int mapType = MAP_TYPE_NORMAL;
  private volatile boolean myLocationEnabled = false;

  private final Map<String, MarkerRecord> markers =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<String, PolylineRecord> polylines =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<String, CircleRecord> circles =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private final List<Runnable> invalidateListeners = new CopyOnWriteArrayList<>();

  private volatile MapClickBridge mapClickBridge;
  private volatile MarkerClickBridge markerClickBridge;
  private volatile Runnable cameraIdleBridge;

  public interface MapClickBridge {
    void onMapClick(double lat, double lng);
  }

  public interface MarkerClickBridge {
    boolean onMarkerClick(MarkerRecord marker);
  }

  public static final class MarkerRecord {
    public final String id;
    public volatile double lat;
    public volatile double lng;
    public volatile String title;
    public volatile String snippet;
    public volatile boolean visible = true;

    public MarkerRecord(String id, double lat, double lng, String title, String snippet) {
      this.id = id;
      this.lat = lat;
      this.lng = lng;
      this.title = title;
      this.snippet = snippet;
    }
  }

  public static final class PolylineRecord {
    public final String id;
    public final List<double[]> points;
    public volatile int color;
    public volatile float width;
    public volatile boolean visible = true;

    public PolylineRecord(String id, List<double[]> points, int color, float width) {
      this.id = id;
      this.points = new ArrayList<>(points);
      this.color = color;
      this.width = width;
    }
  }

  public static final class CircleRecord {
    public final String id;
    public volatile double lat;
    public volatile double lng;
    public volatile double radiusMeters;
    public volatile int strokeColor;
    public volatile int fillColor;
    public volatile boolean visible = true;

    public CircleRecord(
        String id, double lat, double lng, double radiusMeters, int strokeColor, int fillColor) {
      this.id = id;
      this.lat = lat;
      this.lng = lng;
      this.radiusMeters = radiusMeters;
      this.strokeColor = strokeColor;
      this.fillColor = fillColor;
    }
  }

  GmsMaps(Executor main) {
    this.main = main;
  }

  public static GmsMaps install(Application app) {
    Handler handler = new Handler(Looper.getMainLooper());
    GmsMaps instance = new GmsMaps(handler::post);
    active = instance;
    return instance;
  }

  static GmsMaps installForTest(Executor main) {
    GmsMaps instance = new GmsMaps(main);
    active = instance;
    return instance;
  }

  public static GmsMaps current() {
    return active;
  }

  static void setCurrent(GmsMaps engine) {
    if (engine != null) active = engine;
  }

  /** Creates a fresh per-MapView engine sharing the current main executor. */
  static GmsMaps newInstance() {
    GmsMaps cur = active;
    Executor exec = cur != null ? cur.main : defaultMainExecutor();
    GmsMaps created = new GmsMaps(exec);
    active = created;
    return created;
  }

  public static void resetCurrent() {
    active = null;
  }

  static GmsMaps getOrCreate() {
    GmsMaps cur = active;
    if (cur != null) return cur;
    return newInstance();
  }

  private static Executor defaultMainExecutor() {
    try {
      Handler h = new Handler(Looper.getMainLooper());
      return h::post;
    } catch (RuntimeException e) {
      return Runnable::run;
    }
  }

  Executor mainExecutor() {
    return main;
  }

  void addInvalidateListener(Runnable listener) {
    invalidateListeners.add(listener);
  }

  void removeInvalidateListener(Runnable listener) {
    invalidateListeners.remove(listener);
  }

  void notifyChanged() {
    for (Runnable r : invalidateListeners) {
      main.execute(r);
    }
  }

  // ---- Camera & map state ----

  public double cameraLat() {
    return cameraLat;
  }

  public double cameraLng() {
    return cameraLng;
  }

  public float cameraZoom() {
    return cameraZoom;
  }

  public float cameraTilt() {
    return cameraTilt;
  }

  public float cameraBearing() {
    return cameraBearing;
  }

  public int mapType() {
    return mapType;
  }

  public void setMapType(int mapType) {
    this.mapType = mapType;
    notifyChanged();
  }

  public void setMyLocationEnabled(boolean enabled) {
    this.myLocationEnabled = enabled;
    notifyChanged();
  }

  public void setCamera(double lat, double lng, float zoom, float tilt, float bearing) {
    this.cameraLat = Math.max(-85.0511, Math.min(85.0511, lat));
    this.cameraLng = lng;
    this.cameraZoom = Math.max(0f, Math.min(21f, zoom));
    this.cameraTilt = tilt;
    this.cameraBearing = bearing;
    notifyChanged();
    Runnable idle = cameraIdleBridge;
    if (idle != null) main.execute(idle);
  }

  private volatile int viewportWidth = 1080;
  private volatile int viewportHeight = 1080;

  void setViewportSize(int width, int height) {
    if (width > 0) this.viewportWidth = width;
    if (height > 0) this.viewportHeight = height;
  }

  public void fitBounds(
      double swLat, double swLng, double neLat, double neLng, int width, int height, int padding) {
    double mySouth = mercatorY(swLat);
    double myNorth = mercatorY(neLat);
    double centerLat = inverseMercatorY((mySouth + myNorth) / 2.0);
    double centerLng =
        swLng <= neLng ? (swLng + neLng) / 2.0 : ((swLng + neLng + 360.0) / 2.0 + 180.0) % 360.0 - 180.0;
    int w = Math.max(1, (width > 0 ? width : viewportWidth) - 2 * Math.max(0, padding));
    int h = Math.max(1, (height > 0 ? height : viewportHeight) - 2 * Math.max(0, padding));
    double dx = Math.abs(mercatorX(neLng) - mercatorX(swLng));
    if (swLng > neLng) dx = 1.0 - dx;
    double dy = Math.abs(mySouth - myNorth);
    float zoomX = dx > 1e-9 ? (float) (Math.log(w / (256.0 * dx)) / Math.log(2.0)) : 21f;
    float zoomY = dy > 1e-9 ? (float) (Math.log(h / (256.0 * dy)) / Math.log(2.0)) : 21f;
    float zoom = Math.max(0f, Math.min(21f, Math.min(zoomX, zoomY)));
    setCamera(centerLat, centerLng, zoom, cameraTilt, cameraBearing);
  }

  public MarkerRecord addMarker(double lat, double lng, String title, String snippet) {
    return addMarker(lat, lng, title, snippet, true);
  }

  public MarkerRecord addMarker(
      double lat, double lng, String title, String snippet, boolean visible) {
    String id = "m" + nextId.getAndIncrement();
    MarkerRecord rec = new MarkerRecord(id, lat, lng, title, snippet);
    rec.visible = visible;
    markers.put(id, rec);
    notifyChanged();
    return rec;
  }

  public void removeMarker(String id) {
    if (markers.remove(id) != null) notifyChanged();
  }

  public PolylineRecord addPolyline(List<double[]> points, int color, float width) {
    String id = "p" + nextId.getAndIncrement();
    PolylineRecord rec = new PolylineRecord(id, points, color, width);
    polylines.put(id, rec);
    notifyChanged();
    return rec;
  }

  public void removePolyline(String id) {
    if (polylines.remove(id) != null) notifyChanged();
  }

  public CircleRecord addCircle(
      double lat, double lng, double radiusMeters, int strokeColor, int fillColor) {
    String id = "c" + nextId.getAndIncrement();
    CircleRecord rec = new CircleRecord(id, lat, lng, radiusMeters, strokeColor, fillColor);
    circles.put(id, rec);
    notifyChanged();
    return rec;
  }

  public void removeCircle(String id) {
    if (circles.remove(id) != null) notifyChanged();
  }

  public void clearOverlays() {
    markers.clear();
    polylines.clear();
    circles.clear();
    notifyChanged();
  }

  List<MarkerRecord> markersSnapshot() {
    synchronized (markers) {
      return new ArrayList<>(markers.values());
    }
  }

  List<PolylineRecord> polylinesSnapshot() {
    synchronized (polylines) {
      return new ArrayList<>(polylines.values());
    }
  }

  List<CircleRecord> circlesSnapshot() {
    synchronized (circles) {
      return new ArrayList<>(circles.values());
    }
  }

  void setMapClickBridge(MapClickBridge bridge) {
    this.mapClickBridge = bridge;
  }

  void setMarkerClickBridge(MarkerClickBridge bridge) {
    this.markerClickBridge = bridge;
  }

  void setCameraIdleBridge(Runnable bridge) {
    this.cameraIdleBridge = bridge;
  }

  public void dispatchMapClick(double lat, double lng) {
    MapClickBridge bridge = mapClickBridge;
    if (bridge != null) main.execute(() -> bridge.onMapClick(lat, lng));
  }

  public boolean dispatchMarkerClick(String idOrTitle) {
    MarkerRecord target = findMarker(idOrTitle);
    if (target == null) return false;
    MarkerClickBridge bridge = markerClickBridge;
    if (bridge != null) main.execute(() -> bridge.onMarkerClick(target));
    return true;
  }

  private MarkerRecord findMarker(String idOrTitle) {
    if (idOrTitle == null) return null;
    MarkerRecord byId = markers.get(idOrTitle);
    if (byId != null) return byId;
    synchronized (markers) {
      for (MarkerRecord m : markers.values()) {
        if (idOrTitle.equals(m.title)) return m;
      }
    }
    return null;
  }

  // ---- Web-Mercator projection helpers ----

  /** Projects (lat, lng) into pixel (x, y) within a viewport of (width, height). */
  public float[] latLngToScreen(double lat, double lng, int width, int height) {
    double scale = 256.0 * Math.pow(2.0, cameraZoom);
    double cx = mercatorX(cameraLng) * scale;
    double cy = mercatorY(cameraLat) * scale;
    double px = mercatorX(lng) * scale;
    double py = mercatorY(lat) * scale;
    double dx = px - cx;
    if (dx > scale / 2.0) dx -= scale;
    else if (dx < -scale / 2.0) dx += scale;
    double dy = py - cy;
    if (cameraBearing != 0f) {
      double rad = Math.toRadians(-cameraBearing);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double rx = dx * cos - dy * sin;
      double ry = dx * sin + dy * cos;
      dx = rx;
      dy = ry;
    }
    return new float[] {(float) (width / 2.0 + dx), (float) (height / 2.0 + dy)};
  }

  /** Unprojects pixel (x, y) within (width, height) back to (lat, lng). */
  public double[] screenToLatLng(float x, float y, int width, int height) {
    double scale = 256.0 * Math.pow(2.0, cameraZoom);
    double cx = mercatorX(cameraLng) * scale;
    double cy = mercatorY(cameraLat) * scale;
    double dx = x - width / 2.0;
    double dy = y - height / 2.0;
    if (cameraBearing != 0f) {
      double rad = Math.toRadians(cameraBearing);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double rx = dx * cos - dy * sin;
      double ry = dx * sin + dy * cos;
      dx = rx;
      dy = ry;
    }
    double nx = (cx + dx) / scale;
    double ny = (cy + dy) / scale;
    double lng = ((nx * 360.0) % 360.0 + 360.0) % 360.0 - 180.0;
    return new double[] {inverseMercatorY(ny), lng};
  }

  private static double mercatorX(double lng) {
    return (lng + 180.0) / 360.0;
  }

  private static double mercatorY(double lat) {
    double clamped = Math.max(-85.0511, Math.min(85.0511, lat));
    double rad = Math.toRadians(clamped);
    return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0;
  }

  private static double inverseMercatorY(double ny) {
    double latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * ny)));
    return Math.toDegrees(latRad);
  }

  // ---- Control-channel command ("maps") ----

  public JSONObject command(JSONObject c) throws JSONException {
    String action = c.optString("action", "");
    switch (action) {
      case "clear":
        clearOverlays();
        setCamera(37.4220, -122.0841, 12f, 0f, 0f);
        mapType = MAP_TYPE_NORMAL;
        return describe();
      case "tap":
        {
          double lat = c.optDouble("lat", cameraLat);
          double lng = c.has("lng") ? c.getDouble("lng") : c.optDouble("lon", cameraLng);
          dispatchMapClick(lat, lng);
          return describe();
        }
      case "tapMarker":
        {
          String key = c.has("id") ? c.getString("id") : c.getString("title");
          boolean matched = dispatchMarkerClick(key);
          JSONObject out = describe();
          out.put("markerTapped", matched);
          return out;
        }
      default:
        break;
    }
    if (c.has("lat") || c.has("lng") || c.has("lon") || c.has("zoom")) {
      double lat = c.optDouble("lat", cameraLat);
      double lng = c.has("lng") ? c.getDouble("lng") : c.optDouble("lon", cameraLng);
      float zoom = (float) c.optDouble("zoom", cameraZoom);
      float tilt = (float) c.optDouble("tilt", cameraTilt);
      float bearing = (float) c.optDouble("bearing", cameraBearing);
      setCamera(lat, lng, zoom, tilt, bearing);
    }
    if (c.has("mapType")) {
      setMapType(parseMapType(c.getString("mapType")));
    }
    return describe();
  }

  public JSONObject describe() throws JSONException {
    JSONObject out = new JSONObject();
    JSONObject cam = new JSONObject();
    cam.put("lat", cameraLat);
    cam.put("lng", cameraLng);
    cam.put("zoom", cameraZoom);
    cam.put("tilt", cameraTilt);
    cam.put("bearing", cameraBearing);
    out.put("camera", cam);
    out.put("mapType", mapTypeName(mapType));
    out.put("myLocationEnabled", myLocationEnabled);

    JSONArray mArr = new JSONArray();
    for (MarkerRecord m : markersSnapshot()) {
      JSONObject mj = new JSONObject();
      mj.put("id", m.id);
      mj.put("lat", m.lat);
      mj.put("lng", m.lng);
      if (m.title != null) mj.put("title", m.title);
      if (m.snippet != null) mj.put("snippet", m.snippet);
      mj.put("visible", m.visible);
      mArr.put(mj);
    }
    out.put("markers", mArr);
    out.put("polylineCount", polylines.size());
    out.put("circleCount", circles.size());
    return out;
  }

  static int parseMapType(String name) {
    switch (name.trim().toLowerCase(Locale.ROOT)) {
      case "none":
        return MAP_TYPE_NONE;
      case "satellite":
        return MAP_TYPE_SATELLITE;
      case "terrain":
        return MAP_TYPE_TERRAIN;
      case "hybrid":
        return MAP_TYPE_HYBRID;
      default:
        return MAP_TYPE_NORMAL;
    }
  }

  static String mapTypeName(int type) {
    switch (type) {
      case MAP_TYPE_NONE:
        return "none";
      case MAP_TYPE_SATELLITE:
        return "satellite";
      case MAP_TYPE_TERRAIN:
        return "terrain";
      case MAP_TYPE_HYBRID:
        return "hybrid";
      default:
        return "normal";
    }
  }
}
