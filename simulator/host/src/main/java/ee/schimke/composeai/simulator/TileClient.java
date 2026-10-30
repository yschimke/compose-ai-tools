package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * One tile, as a watch's tile host drives it: binds the app's {@code TileService} by its {@code
 * BIND_TILE_PROVIDER} action and speaks its AIDL ({@code TileProvider}) in process — the tile
 * request with the watch's device parameters, the resources request, and the add / enter / leave /
 * remove events — then draws the returned layout with the protolayout tile renderer.
 *
 * <p>Everything crosses as the AIDL's proto bytes, built and read with the app's own tiles and
 * protolayout classes, reflectively: this class links against neither, so it works with whatever
 * version the app ships. The renderer ({@code tiles-renderer}, staged in {@code lib-simulator/})
 * resolves to the app's protolayout in the same class loader. Its health data comes from {@link
 * SystemData}; a {@code LoadAction} click requests the tile again with the clicked state, and a
 * {@code LaunchAction} starts its activity through the app's context, which {@link Navigator}
 * picks up.
 *
 * <p>Requests are answered on the main looper, between cycles, so a result lands shortly after the
 * call; {@link Listener} hears it.
 */
final class TileClient {

  interface Listener {
    /** The tile was drawn into [view], or failed with [error]. */
    void rendered(TileClient tile, View view, String error);
  }

  private static final String TILES = "androidx.wear.tiles.";
  private static final String PROTOLAYOUT = "androidx.wear.protolayout.";
  /** {@code ProtoParcelable.VERSION_PROTOBUF}. */
  private static final int PROTOBUF = 1;

  final ComponentName component;
  final int tileId;
  private final Application app;
  private final Navigator navigator;
  private final SystemData systemData;
  private final Listener listener;
  private Object provider;
  private Object layout;
  private Object resources;
  private String resourcesVersion;
  private Object state;
  private View view;
  private String error;
  private int requests;

  TileClient(
      Application app,
      Navigator navigator,
      SystemData systemData,
      ComponentName component,
      int tileId,
      Listener listener) {
    this.app = app;
    this.navigator = navigator;
    this.systemData = systemData;
    this.component = component;
    this.tileId = tileId;
    this.listener = listener;
  }

  View view() {
    return view;
  }

  String error() {
    return error;
  }

  int requests() {
    return requests;
  }

  // ---------------------------------------------------------------------------------------------
  // The AIDL

  private Object provider() throws ReflectiveOperationException {
    if (provider == null) {
      IBinder binder =
          navigator.bindForHost(
              new Intent(TileHost.TILE_ACTION).setComponent(component));
      if (binder == null) throw new IllegalStateException(component + " returned no binder");
      provider =
          load(TILES + "TileProvider$Stub")
              .getMethod("asInterface", IBinder.class)
              .invoke(null, binder);
    }
    return provider;
  }

  /** Lets the service go, as removing the tile does. */
  void release() {
    if (provider == null) return;
    try {
      event("TileRemoveEvent", "onTileRemoveEvent");
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Already gone.
    }
    navigator.unbindService(new Intent(TileHost.TILE_ACTION).setComponent(component));
    provider = null;
  }

  /** {@code onTileAddEvent} / {@code onTileEnterEvent} / ...: [event] is the builder's name. */
  void event(String event, String method) throws ReflectiveOperationException {
    Object builder = load(TILES + "EventBuilders$" + event + "$Builder").getConstructor().newInstance();
    call(builder, "setTileId", tileId);
    byte[] bytes = bytes(call(builder, "build"));
    Class<?> dataType = load(TILES + event + "Data");
    Object data = dataType.getConstructor(byte[].class, int.class).newInstance(bytes, PROTOBUF);
    Object p = provider();
    load(TILES + "TileProvider").getMethod(method, dataType).invoke(p, data);
  }

  /** Requests the tile (again), with the clicked state when a {@code LoadAction} sent one. */
  void request() {
    try {
      Object builder = load(TILES + "RequestBuilders$TileRequest$Builder").getConstructor().newInstance();
      call(builder, "setTileId", tileId);
      call(builder, "setDeviceConfiguration", deviceParameters());
      if (state != null) call(builder, "setCurrentState", state);
      byte[] bytes = bytes(call(builder, "build"));
      Class<?> dataType = load(TILES + "TileRequestData");
      Object data = dataType.getConstructor(byte[].class, int.class).newInstance(bytes, PROTOBUF);
      Class<?> callbackType = load(TILES + "TileCallback");
      Object callback = callback(callbackType, "updateTileData", this::tileData);
      Object p = provider();
      requests++;
      load(TILES + "TileProvider")
          .getMethod("onTileRequest", int.class, dataType, callbackType)
          .invoke(p, tileId, data, callback);
    } catch (ReflectiveOperationException | RuntimeException e) {
      fail(e);
    }
  }

  private void tileData(Object tileData) {
    try {
      byte[] contents = (byte[]) call(tileData, "getContents");
      Class<?> protoType = load(TILES + "proto.TileProto$Tile");
      Object proto = protoType.getMethod("parseFrom", byte[].class).invoke(null, (Object) contents);
      Object tile = load(TILES + "TileBuilders$Tile").getMethod("fromProto", protoType).invoke(null, proto);
      Object timeline = call(tile, "getTileTimeline");
      if (timeline == null) throw new IllegalStateException("the tile has no timeline");
      java.util.List<?> entries = (java.util.List<?>) call(timeline, "getTimelineEntries");
      if (entries.isEmpty()) throw new IllegalStateException("the tile's timeline is empty");
      layout = call(entries.get(0), "getLayout");
      Object tileState = call(tile, "getState");
      if (tileState != null) state = tileState;
      String version = (String) call(tile, "getResourcesVersion");
      if (resources == null || !java.util.Objects.equals(version, resourcesVersion)) {
        requestResources(version);
      } else {
        render();
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      fail(e);
    }
  }

  private void requestResources(String version) throws ReflectiveOperationException {
    resourcesVersion = version;
    Object builder =
        load(TILES + "RequestBuilders$ResourcesRequest$Builder").getConstructor().newInstance();
    call(builder, "setTileId", tileId);
    call(builder, "setVersion", version == null ? "" : version);
    call(builder, "setDeviceConfiguration", deviceParameters());
    byte[] bytes = bytes(call(builder, "build"));
    Class<?> dataType = load(TILES + "ResourcesRequestData");
    Object data = dataType.getConstructor(byte[].class, int.class).newInstance(bytes, PROTOBUF);
    Class<?> callbackType = load(TILES + "ResourcesCallback");
    Object callback = callback(callbackType, "updateResources", this::resourcesData);
    Object p = provider();
    load(TILES + "TileProvider")
        .getMethod("onResourcesRequest", int.class, dataType, callbackType)
        .invoke(p, tileId, data, callback);
  }

  private void resourcesData(Object resourcesData) {
    try {
      byte[] contents = (byte[]) call(resourcesData, "getContents");
      Class<?> protoType = load(PROTOLAYOUT + "proto.ResourceProto$Resources");
      Object proto = protoType.getMethod("parseFrom", byte[].class).invoke(null, (Object) contents);
      resources =
          load(PROTOLAYOUT + "ResourceBuilders$Resources")
              .getMethod("fromProto", protoType)
              .invoke(null, proto);
      render();
    } catch (ReflectiveOperationException | RuntimeException e) {
      fail(e);
    }
  }

  /** The watch the request describes: the display's size and density, round, Wear OS. */
  private Object deviceParameters() throws ReflectiveOperationException {
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    Object builder =
        load(PROTOLAYOUT + "DeviceParametersBuilders$DeviceParameters$Builder")
            .getConstructor()
            .newInstance();
    call(builder, "setScreenWidthDp", Math.round(metrics.widthPixels / metrics.density));
    call(builder, "setScreenHeightDp", Math.round(metrics.heightPixels / metrics.density));
    call(builder, "setScreenDensity", metrics.density);
    call(builder, "setFontScale", app.getResources().getConfiguration().fontScale);
    // SCREEN_SHAPE_ROUND / _RECT, DEVICE_PLATFORM_WEAR_OS.
    call(builder, "setScreenShape", FormFactor.isRound() ? 1 : 2);
    call(builder, "setDevicePlatform", 1);
    return call(builder, "build");
  }

  // ---------------------------------------------------------------------------------------------
  // The renderer

  private Context context;
  private ViewGroup parent;

  /** Where the next render goes; the view is added to [parent]. */
  void attach(Context context, ViewGroup parent) {
    this.context = context;
    this.parent = parent;
  }

  private void render() throws ReflectiveOperationException {
    if (context == null || parent == null) return;
    Executor direct = Runnable::run;
    Consumer<Object> onLoad =
        clicked -> {
          state = clicked;
          request();
        };
    Class<?> rendererType = load(TILES + "renderer.TileRenderer");
    Class<?> builderType = load(TILES + "renderer.TileRenderer$Builder");
    Object builder =
        builderType
            .getConstructor(Context.class, Executor.class, Consumer.class)
            .newInstance(context, direct, onLoad);
    if (systemData.available()) {
      Class<?> providerType =
          load(PROTOLAYOUT + "expression.pipeline.PlatformDataProvider");
      Object keys = systemData.keys();
      builderType
          .getMethod("addPlatformDataProvider", providerType, keys.getClass())
          .invoke(builder, systemData.provider(), keys);
    }
    Object renderer = call(builder, "build");
    parent.removeAllViews();
    Future<?> future =
        (Future<?>)
            rendererType
                .getMethod(
                    "inflateAsync",
                    load(PROTOLAYOUT + "LayoutElementBuilders$Layout"),
                    load(PROTOLAYOUT + "ResourceBuilders$Resources"),
                    ViewGroup.class)
                .invoke(renderer, layout, resources, parent);
    Runnable done =
        () -> {
          try {
            View inflated = (View) future.get();
            if (inflated.getParent() == null) parent.addView(inflated);
            view = inflated;
            error = null;
            listener.rendered(this, inflated, null);
          } catch (Exception e) {
            fail(e);
          }
        };
    load("com.google.common.util.concurrent.ListenableFuture")
        .getMethod("addListener", Runnable.class, Executor.class)
        .invoke(future, done, direct);
  }

  private void fail(Throwable t) {
    Throwable root = t;
    while ((root instanceof java.lang.reflect.InvocationTargetException
            || root instanceof java.util.concurrent.ExecutionException)
        && root.getCause() != null) {
      root = root.getCause();
    }
    error = String.valueOf(root);
    System.err.println("compose-preview launch: tile " + component.getClassName() + ": " + error);
    root.printStackTrace();
    listener.rendered(this, null, error);
  }

  // ---------------------------------------------------------------------------------------------

  /** An AIDL callback ([type], an {@code IInterface}) whose one method hands its argument on. */
  private Object callback(Class<?> type, String method, Consumer<Object> onResult) {
    Binder binder = new Binder();
    InvocationHandler handler =
        (proxy, m, args) -> {
          if (m.getName().equals(method)) {
            onResult.accept(args[0]);
            return null;
          }
          switch (m.getName()) {
            case "asBinder":
              return binder;
            case "hashCode":
              return System.identityHashCode(proxy);
            case "equals":
              return proxy == args[0];
            case "toString":
              return "TileClient." + method;
            default:
              return null;
          }
        };
    return Proxy.newProxyInstance(app.getClassLoader(), new Class<?>[] {type}, handler);
  }

  /** {@code builder.toProto().toByteArray()}. */
  private static byte[] bytes(Object built) throws ReflectiveOperationException {
    return (byte[]) call(call(built, "toProto"), "toByteArray");
  }

  /** Calls the one public method [name] that takes [args.length] arguments. */
  private static Object call(Object target, String name, Object... args)
      throws ReflectiveOperationException {
    for (Method m : target.getClass().getMethods()) {
      if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
      Class<?>[] types = m.getParameterTypes();
      boolean fits = true;
      for (int i = 0; i < types.length; i++) {
        if (args[i] != null && !wrap(types[i]).isInstance(args[i])) fits = false;
      }
      if (!fits) continue;
      m.setAccessible(true);
      return m.invoke(target, args);
    }
    throw new NoSuchMethodException(target.getClass().getName() + "." + name);
  }

  private static Class<?> wrap(Class<?> type) {
    if (!type.isPrimitive()) return type;
    if (type == int.class) return Integer.class;
    if (type == float.class) return Float.class;
    if (type == long.class) return Long.class;
    if (type == boolean.class) return Boolean.class;
    if (type == double.class) return Double.class;
    return type;
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }
}
