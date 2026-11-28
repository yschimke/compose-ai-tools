package ee.schimke.composeai.simulator.gms.maps;

import android.content.Context;
import android.view.ViewGroup;
import ee.schimke.composeai.simulator.PlayServices;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Reflection & dynamic-proxy bridge between Play services' {@code GoogleMap} / {@code MapView} /
 * {@code MapsInitializer} / {@code CameraUpdateFactory} and per-view {@link GmsMaps} engines.
 */
public final class GmsMapsDelegates {

  private static final String GOOGLE_MAP = "com.google.android.gms.maps.GoogleMap";
  private static final String CAMERA_UPDATE = "com.google.android.gms.maps.CameraUpdate";
  private static final String IOBJECT_WRAPPER = "com.google.android.gms.dynamic.IObjectWrapper";
  private static final String IGOOGLE_MAP_DELEGATE =
      "com.google.android.gms.maps.internal.IGoogleMapDelegate";
  private static final String IUI_SETTINGS_DELEGATE =
      "com.google.android.gms.maps.internal.IUiSettingsDelegate";
  private static final String LAT_LNG = "com.google.android.gms.maps.model.LatLng";
  private static final String CAMERA_POSITION = "com.google.android.gms.maps.model.CameraPosition";

  private static final Map<ViewGroup, GmsMaps> enginesByView =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<Object, Consumer<GmsMaps>> cameraUpdates =
      Collections.synchronizedMap(new WeakHashMap<>());

  private GmsMapsDelegates() {}

  /** Returns the dedicated {@link GmsMaps} engine for {@code mapView}, creating one if needed. */
  static GmsMaps engineFor(ViewGroup mapView) {
    if (mapView == null) return GmsMaps.getOrCreate();
    synchronized (enginesByView) {
      GmsMaps engine = enginesByView.get(mapView);
      if (engine == null) {
        engine = GmsMaps.newInstance();
        enginesByView.put(mapView, engine);
      } else {
        GmsMaps.setCurrent(engine);
      }
      return engine;
    }
  }

  /** Answers {@code MapsInitializer.initialize} and invokes {@code callback} if provided. */
  public static int initialize(Context context, Object callback) {
    int status = PlayServices.status(0);
    GmsMaps engine = GmsMaps.getOrCreate();
    if (status == PlayServices.SUCCESS && callback != null) {
      ClassLoader loader = callback.getClass().getClassLoader();
      engine
          .mainExecutor()
          .execute(
              () -> {
                try {
                  Class<?> rendererCls =
                      Class.forName(
                          "com.google.android.gms.maps.MapsInitializer$Renderer", false, loader);
                  Object latest = Enum.valueOf(rendererCls.asSubclass(Enum.class), "LATEST");
                  callback
                      .getClass()
                      .getMethod("onMapsSdkInitialized", rendererCls)
                      .invoke(callback, latest);
                } catch (ReflectiveOperationException ignored) {
                }
              });
    }
    return status;
  }

  /** Attaches a {@link SimulatedMapCanvasView} inside a shadowed {@code MapView}. */
  public static void attachCanvas(ViewGroup mapView) {
    if (mapView == null) return;
    GmsMaps engine = engineFor(mapView);
    for (int i = 0; i < mapView.getChildCount(); i++) {
      if (mapView.getChildAt(i) instanceof SimulatedMapCanvasView) return;
    }
    SimulatedMapCanvasView canvasView = new SimulatedMapCanvasView(mapView.getContext(), engine);
    canvasView.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    mapView.addView(canvasView);
  }

  /** Dispatches {@code OnMapReadyCallback.onMapReady(GoogleMap)} on the main executor. */
  public static void getMapAsync(ViewGroup mapView, Object callback) {
    if (callback == null) return;
    GmsMaps engine = engineFor(mapView);
    ClassLoader loader =
        mapView != null && mapView.getContext() != null
            ? mapView.getContext().getClassLoader()
            : callback.getClass().getClassLoader();
    engine
        .mainExecutor()
        .execute(
            () -> {
              Object googleMap = createGoogleMap(engine, loader);
              if (googleMap == null) return;
              try {
                for (Method m : callback.getClass().getMethods()) {
                  if ("onMapReady".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(callback, googleMap);
                    return;
                  }
                }
              } catch (ReflectiveOperationException ignored) {
              }
            });
  }

  // ---- CameraUpdateFactory helpers ----

  public static Object cameraUpdateNewLatLng(Object latLng) {
    if (latLng == null) return null;
    double[] ll = readLatLng(latLng);
    return wrapCameraUpdate(
        latLng.getClass().getClassLoader(),
        e -> e.setCamera(ll[0], ll[1], e.cameraZoom(), e.cameraTilt(), e.cameraBearing()));
  }

  public static Object cameraUpdateNewLatLngZoom(Object latLng, float zoom) {
    if (latLng == null) return null;
    double[] ll = readLatLng(latLng);
    return wrapCameraUpdate(
        latLng.getClass().getClassLoader(),
        e -> e.setCamera(ll[0], ll[1], zoom, e.cameraTilt(), e.cameraBearing()));
  }

  public static Object cameraUpdateNewCameraPosition(Object cameraPosition) {
    if (cameraPosition == null) return null;
    ClassLoader loader = cameraPosition.getClass().getClassLoader();
    double[] ll = readLatLng(readField(cameraPosition, "target"));
    float zoom = readFloat(readField(cameraPosition, "zoom"), 12f);
    float tilt = readFloat(readField(cameraPosition, "tilt"), 0f);
    float bearing = readFloat(readField(cameraPosition, "bearing"), 0f);
    return wrapCameraUpdate(loader, e -> e.setCamera(ll[0], ll[1], zoom, tilt, bearing));
  }

  public static Object cameraUpdateNewLatLngBounds(Object bounds) {
    if (bounds == null) return null;
    ClassLoader loader = bounds.getClass().getClassLoader();
    Object center = invokeNoArg(bounds, "getCenter");
    double[] ll = readLatLng(center);
    return wrapCameraUpdate(
        loader, e -> e.setCamera(ll[0], ll[1], e.cameraZoom(), e.cameraTilt(), e.cameraBearing()));
  }

  public static Object cameraUpdateZoomTo(ClassLoader loader, float zoom) {
    return wrapCameraUpdate(
        loader,
        e -> e.setCamera(e.cameraLat(), e.cameraLng(), zoom, e.cameraTilt(), e.cameraBearing()));
  }

  public static Object cameraUpdateZoomBy(ClassLoader loader, float delta) {
    return wrapCameraUpdate(
        loader,
        e ->
            e.setCamera(
                e.cameraLat(),
                e.cameraLng(),
                e.cameraZoom() + delta,
                e.cameraTilt(),
                e.cameraBearing()));
  }

  static Object wrapCameraUpdate(ClassLoader loader, Consumer<GmsMaps> action) {
    try {
      Class<?> wrapperIface = Class.forName(IOBJECT_WRAPPER, false, loader);
      Object wrapper =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {wrapperIface},
              (p, m, a) -> {
                if ("equals".equals(m.getName()) && a != null && a.length == 1) return p == a[0];
                if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                if ("toString".equals(m.getName())) return "SimulatedCameraUpdateWrapper";
                return defaultReturn(m.getReturnType());
              });
      cameraUpdates.put(wrapper, action);
      Class<?> updateCls = Class.forName(CAMERA_UPDATE, false, loader);
      Constructor<?> ctor = updateCls.getDeclaredConstructor(wrapperIface);
      ctor.setAccessible(true);
      return ctor.newInstance(wrapper);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Constructs a {@code GoogleMap} backed by a simulated {@code IGoogleMapDelegate} proxy. */
  public static Object createGoogleMap(GmsMaps engine, ClassLoader loader) {
    try {
      Class<?> delegateIface = Class.forName(IGOOGLE_MAP_DELEGATE, false, loader);
      Object delegate =
          Proxy.newProxyInstance(
              loader, new Class<?>[] {delegateIface}, new GoogleMapDelegateHandler(engine, loader));
      Class<?> mapCls = Class.forName(GOOGLE_MAP, false, loader);
      Constructor<?> ctor = mapCls.getDeclaredConstructor(delegateIface);
      ctor.setAccessible(true);
      return ctor.newInstance(delegate);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static final class GoogleMapDelegateHandler implements InvocationHandler {
    private final GmsMaps engine;
    private final ClassLoader loader;
    private final Object uiSettingsProxy;
    private final Map<String, Object> markerDelegates =
        Collections.synchronizedMap(new HashMap<>());

    GoogleMapDelegateHandler(GmsMaps engine, ClassLoader loader) {
      this.engine = engine;
      this.loader = loader;
      this.uiSettingsProxy = createUiSettingsProxy(loader);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();
      Object[] safeArgs = args == null ? new Object[0] : args;
      switch (name) {
        case "toString":
          return "SimulatedIGoogleMapDelegate";
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return safeArgs.length == 1 && proxy == safeArgs[0];
        case "getCameraPosition":
          return buildCameraPosition(loader, engine);
        case "moveCamera":
        case "animateCamera":
        case "animateCameraWithCallback":
        case "animateCameraWithDurationAndCallback":
          handleCameraUpdate(safeArgs);
          return null;
        case "getMapType":
          return engine.mapType();
        case "setMapType":
          if (safeArgs.length > 0 && safeArgs[0] instanceof Integer) {
            engine.setMapType((Integer) safeArgs[0]);
          }
          return null;
        case "setMyLocationEnabled":
          if (safeArgs.length > 0 && safeArgs[0] instanceof Boolean) {
            engine.setMyLocationEnabled((Boolean) safeArgs[0]);
          }
          return null;
        case "getUiSettings":
          return uiSettingsProxy;
        case "addMarker":
          return handleAddMarker(method.getReturnType(), safeArgs);
        case "addPolyline":
          return handleAddPolyline(method.getReturnType(), safeArgs);
        case "addCircle":
          return handleAddCircle(method.getReturnType(), safeArgs);
        case "clear":
          markerDelegates.clear();
          engine.clearOverlays();
          return null;
        case "setOnMapClickListener":
          handleSetMapClickListener(safeArgs.length > 0 ? safeArgs[0] : null);
          return null;
        case "setOnMarkerClickListener":
          handleSetMarkerClickListener(safeArgs.length > 0 ? safeArgs[0] : null);
          return null;
        case "setOnCameraIdleListener":
          handleSetCameraIdleListener(safeArgs.length > 0 ? safeArgs[0] : null);
          return null;
        default:
          return defaultReturn(method.getReturnType());
      }
    }

    private void handleCameraUpdate(Object[] args) {
      if (args.length == 0 || args[0] == null) return;
      Consumer<GmsMaps> action = cameraUpdates.get(args[0]);
      if (action != null) {
        action.accept(engine);
      }
      // Invoke CancelableCallback.onFinish() if supplied (last argument).
      Object last = args[args.length - 1];
      if (last != null && last != args[0]) {
        for (Method m : last.getClass().getMethods()) {
          if (("onFinish".equals(m.getName()) || "zzc".equals(m.getName()))
              && m.getParameterCount() == 0) {
            try {
              m.setAccessible(true);
              m.invoke(last);
              return;
            } catch (ReflectiveOperationException ignored) {
            }
          }
        }
      }
    }

    private Object handleAddMarker(Class<?> returnType, Object[] args) {
      if (args.length == 0 || args[0] == null) return null;
      Object opts = args[0];
      double[] ll = readLatLng(invokeNoArg(opts, "getPosition"));
      String title = (String) invokeNoArg(opts, "getTitle");
      String snippet = (String) invokeNoArg(opts, "getSnippet");
      GmsMaps.MarkerRecord rec = engine.addMarker(ll[0], ll[1], title, snippet);
      if (!returnType.isInterface()) return null;
      Object markerProxy =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {returnType},
              (p, m, a) -> {
                switch (m.getName()) {
                  case "getId":
                    return rec.id;
                  case "getTitle":
                    return rec.title;
                  case "setTitle":
                    if (a != null && a.length > 0) rec.title = (String) a[0];
                    engine.notifyChanged();
                    return null;
                  case "getSnippet":
                    return rec.snippet;
                  case "getPosition":
                    return buildLatLng(loader, rec.lat, rec.lng);
                  case "setPosition":
                    if (a != null && a.length > 0) {
                      double[] pos = readLatLng(a[0]);
                      rec.lat = pos[0];
                      rec.lng = pos[1];
                      engine.notifyChanged();
                    }
                    return null;
                  case "isVisible":
                    return rec.visible;
                  case "setVisible":
                    if (a != null && a.length > 0 && a[0] instanceof Boolean) {
                      rec.visible = (Boolean) a[0];
                      engine.notifyChanged();
                    }
                    return null;
                  case "remove":
                    markerDelegates.remove(rec.id);
                    engine.removeMarker(rec.id);
                    return null;
                  case "hashCode":
                    return rec.id.hashCode();
                  case "equals":
                    return a != null && a.length == 1 && p == a[0];
                  case "toString":
                    return "SimulatedMarker(" + rec.id + ")";
                  default:
                    return defaultReturn(m.getReturnType());
                }
              });
      markerDelegates.put(rec.id, markerProxy);
      return markerProxy;
    }

    private Object handleAddPolyline(Class<?> returnType, Object[] args) {
      if (args.length == 0 || args[0] == null) return null;
      Object opts = args[0];
      List<double[]> pts = new ArrayList<>();
      Object rawPts = invokeNoArg(opts, "getPoints");
      if (rawPts instanceof List<?>) {
        for (Object item : (List<?>) rawPts) pts.add(readLatLng(item));
      }
      int color = readInt(invokeNoArg(opts, "getColor"), 0xFF1A73E8);
      float width = readFloat(invokeNoArg(opts, "getWidth"), 6f);
      GmsMaps.PolylineRecord rec = engine.addPolyline(pts, color, width);
      if (!returnType.isInterface()) return null;
      return Proxy.newProxyInstance(
          loader,
          new Class<?>[] {returnType},
          (p, m, a) -> {
            switch (m.getName()) {
              case "getId":
                return rec.id;
              case "remove":
                engine.removePolyline(rec.id);
                return null;
              case "isVisible":
                return rec.visible;
              case "setVisible":
                if (a != null && a.length > 0 && a[0] instanceof Boolean) {
                  rec.visible = (Boolean) a[0];
                  engine.notifyChanged();
                }
                return null;
              default:
                return defaultReturn(m.getReturnType());
            }
          });
    }

    private Object handleAddCircle(Class<?> returnType, Object[] args) {
      if (args.length == 0 || args[0] == null) return null;
      Object opts = args[0];
      double[] ll = readLatLng(invokeNoArg(opts, "getCenter"));
      double radius = readDouble(invokeNoArg(opts, "getRadius"), 100.0);
      int stroke = readInt(invokeNoArg(opts, "getStrokeColor"), 0xFF1A73E8);
      int fill = readInt(invokeNoArg(opts, "getFillColor"), 0x331A73E8);
      GmsMaps.CircleRecord rec = engine.addCircle(ll[0], ll[1], radius, stroke, fill);
      if (!returnType.isInterface()) return null;
      return Proxy.newProxyInstance(
          loader,
          new Class<?>[] {returnType},
          (p, m, a) -> {
            switch (m.getName()) {
              case "getId":
                return rec.id;
              case "remove":
                engine.removeCircle(rec.id);
                return null;
              default:
                return defaultReturn(m.getReturnType());
            }
          });
    }

    private void handleSetMapClickListener(Object listener) {
      if (listener == null) {
        engine.setMapClickBridge(null);
        return;
      }
      engine.setMapClickBridge(
          (lat, lng) -> {
            Object ll = buildLatLng(loader, lat, lng);
            if (ll == null) return;
            for (Method m : listener.getClass().getMethods()) {
              if (m.getParameterCount() == 1
                  && LAT_LNG.equals(m.getParameterTypes()[0].getName())) {
                try {
                  m.setAccessible(true);
                  m.invoke(listener, ll);
                  return;
                } catch (ReflectiveOperationException ignored) {
                }
              }
            }
          });
    }

    private void handleSetMarkerClickListener(Object listener) {
      if (listener == null) {
        engine.setMarkerClickBridge(null);
        return;
      }
      engine.setMarkerClickBridge(
          marker -> {
            Object markerDelegate = markerDelegates.get(marker.id);
            if (markerDelegate == null) return false;
            for (Method m : listener.getClass().getMethods()) {
              if (m.getParameterCount() == 1
                  && m.getParameterTypes()[0].isInstance(markerDelegate)
                  && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                try {
                  m.setAccessible(true);
                  Object result = m.invoke(listener, markerDelegate);
                  return Boolean.TRUE.equals(result);
                } catch (ReflectiveOperationException ignored) {
                }
              }
            }
            return false;
          });
    }

    private void handleSetCameraIdleListener(Object listener) {
      if (listener == null) {
        engine.setCameraIdleBridge(null);
        return;
      }
      engine.setCameraIdleBridge(
          () -> {
            for (Method m : listener.getClass().getMethods()) {
              if (m.getParameterCount() == 0
                  && ("onCameraIdle".equals(m.getName()) || "zzb".equals(m.getName()))) {
                try {
                  m.setAccessible(true);
                  m.invoke(listener);
                  return;
                } catch (ReflectiveOperationException ignored) {
                }
              }
            }
          });
    }
  }

  private static Object createUiSettingsProxy(ClassLoader loader) {
    try {
      Class<?> iface = Class.forName(IUI_SETTINGS_DELEGATE, false, loader);
      if (!iface.isInterface()) return null;
      return Proxy.newProxyInstance(
          loader, new Class<?>[] {iface}, (p, m, a) -> defaultReturn(m.getReturnType()));
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private static Object buildLatLng(ClassLoader loader, double lat, double lng) {
    try {
      Class<?> cls = Class.forName(LAT_LNG, false, loader);
      Constructor<?> ctor = cls.getDeclaredConstructor(double.class, double.class);
      ctor.setAccessible(true);
      return ctor.newInstance(lat, lng);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static Object buildCameraPosition(ClassLoader loader, GmsMaps engine) {
    try {
      Object target = buildLatLng(loader, engine.cameraLat(), engine.cameraLng());
      if (target == null) return null;
      Class<?> cls = Class.forName(CAMERA_POSITION, false, loader);
      Constructor<?> ctor =
          cls.getDeclaredConstructor(target.getClass(), float.class, float.class, float.class);
      ctor.setAccessible(true);
      return ctor.newInstance(
          target, engine.cameraZoom(), engine.cameraTilt(), engine.cameraBearing());
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static double[] readLatLng(Object latLng) {
    if (latLng == null) return new double[] {0.0, 0.0};
    try {
      Field latF = latLng.getClass().getField("latitude");
      Field lngF = latLng.getClass().getField("longitude");
      return new double[] {latF.getDouble(latLng), lngF.getDouble(latLng)};
    } catch (ReflectiveOperationException e) {
      return new double[] {0.0, 0.0};
    }
  }

  private static Object readField(Object target, String fieldName) {
    if (target == null) return null;
    try {
      Field f = target.getClass().getField(fieldName);
      f.setAccessible(true);
      return f.get(target);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static Object invokeNoArg(Object target, String method) {
    if (target == null) return null;
    try {
      Method m = target.getClass().getMethod(method);
      m.setAccessible(true);
      return m.invoke(target);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static int readInt(Object v, int fallback) {
    return v instanceof Number ? ((Number) v).intValue() : fallback;
  }

  private static float readFloat(Object v, float fallback) {
    return v instanceof Number ? ((Number) v).floatValue() : fallback;
  }

  private static double readDouble(Object v, double fallback) {
    return v instanceof Number ? ((Number) v).doubleValue() : fallback;
  }

  private static Object defaultReturn(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    if (type == double.class) return 0.0;
    return null;
  }
}
