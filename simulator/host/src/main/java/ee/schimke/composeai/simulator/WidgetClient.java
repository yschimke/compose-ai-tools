package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.os.Binder;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One Wear Widget, as the watch's widget host drives it: binds the app's Glance Wear widget
 * service by {@code androidx.glance.wear.action.BIND_WIDGET_PROVIDER} and speaks its AIDL ({@code
 * IWearWidgetProvider}) in process, asking for a document of the container's size; the answer is
 * a Remote Compose document, played by the View-based {@code RemoteComposePlayer} (staged in
 * {@code lib-simulator/}; a Compose player would bring a second Compose onto the app's class
 * path). The player gets the watch's health data as the named values the widget reads ({@link
 * SystemData#widgetValues}), and again whenever {@code health} changes them.
 *
 * <p>The request and response are Wire protos, built and read with the app's own Glance Wear
 * classes, reflectively, as {@link TileClient} does for tiles.
 */
final class WidgetClient {

  interface Listener {
    void rendered(WidgetClient widget, View view, String error);
  }

  private static final String PARCEL = "androidx.glance.wear.parcel.";
  private static final String PROTO = "androidx.glance.wear.proto.";
  /** {@code ContainerInfo.CONTAINER_TYPE_LARGE} / {@code _SMALL}. */
  static final int LARGE = 1;

  static final int SMALL = 2;

  /**
   * The slot a watch's host gives a widget: its size, the padding it keeps inside, and the corner
   * radius it masks the widget's background to. The document paints its own background over the
   * whole slot (Glance Wear's container), so the host draws nothing around it. Values are Glance
   * Wear's own preview parameters for each host shape: a round watch's stadium (corners of 999dp,
   * clamped to a pill) and a square watch's squircle (26dp), each sized for a large screen
   * (227dp across) or a small one.
   */
  static final class Container {
    final int type;
    final float widthDp;
    final float heightDp;
    final float horizontalPaddingDp;
    final float verticalPaddingDp;
    final float cornerRadiusDp;

    Container(int type, float w, float h, float hPad, float vPad, float corner) {
      this.type = type;
      this.widthDp = w;
      this.heightDp = h;
      this.horizontalPaddingDp = hPad;
      this.verticalPaddingDp = vPad;
      this.cornerRadiusDp = corner;
    }

    static Container of(int type, boolean round, int screenWidthDp) {
      boolean large = screenWidthDp >= 210;
      if (round) {
        if (type == SMALL) {
          return large
              ? new Container(SMALL, 200, 60, 15, 8, 999)
              : new Container(SMALL, 182, 54, 13, 8, 999);
        }
        return large
            ? new Container(LARGE, 160, 136, 35, 16, 999)
            : new Container(LARGE, 150, 120, 29, 16, 999);
      }
      if (type == SMALL) {
        return large
            ? new Container(SMALL, 200, 60, 8, 8, 26)
            : new Container(SMALL, 166, 60, 8, 8, 26);
      }
      return large
          ? new Container(LARGE, 200, 108, 8, 8, 26)
          : new Container(LARGE, 166, 96, 8, 8, 26);
    }

    String name() {
      return type == SMALL ? "small" : "large";
    }
  }

  final ComponentName component;
  final int instanceId;
  private final Application app;
  private final Navigator navigator;
  private final SystemData systemData;
  private final Listener listener;
  private Object provider;
  private View player;
  private byte[] document;
  private String error;
  private Context context;
  private ViewGroup parent;
  private Container container = Container.of(LARGE, true, 227);

  WidgetClient(
      Application app,
      Navigator navigator,
      SystemData systemData,
      ComponentName component,
      int instanceId,
      Listener listener) {
    this.app = app;
    this.navigator = navigator;
    this.systemData = systemData;
    this.component = component;
    this.instanceId = instanceId;
    this.listener = listener;
    systemData.onChange(this::applySystemData);
  }

  View view() {
    return player;
  }

  String error() {
    return error;
  }

  /** Where the document plays: [parent], sized to [container]. */
  void attach(Context context, ViewGroup parent, Container container) {
    this.context = context;
    this.parent = parent;
    this.container = container;
  }

  Container container() {
    return container;
  }

  private Object provider() throws ReflectiveOperationException {
    if (provider == null) {
      IBinder binder =
          navigator.bindForHost(new Intent(TileHost.WIDGET_ACTION).setComponent(component));
      if (binder == null) throw new IllegalStateException(component + " returned no binder");
      provider =
          load(PARCEL + "IWearWidgetProvider$Stub")
              .getMethod("asInterface", IBinder.class)
              .invoke(null, binder);
    }
    return provider;
  }

  void release() {
    if (provider == null) return;
    navigator.unbindService(new Intent(TileHost.WIDGET_ACTION).setComponent(component));
    provider = null;
  }

  /** Asks the widget for its document; the answer arrives on the main looper. */
  void request() {
    try {
      float density = app.getResources().getDisplayMetrics().density;
      Class<?> requestType = load(PROTO + "WearWidgetRequestProto");
      Class<?> byteString = load("okio.ByteString");
      Object empty = byteString.getField("EMPTY").get(null);
      // What the player can draw: its Remote Compose core's Wear Widgets profile at its document
      // level, which is the profile the widget's document declares and the player reads it with.
      // The document keeps only operations the host lists and folds the rest (named values among
      // them) into constants; listing more (AndroidX's CORE_TEXT) gets a document the reader
      // refuses.
      Class<?> core = load("androidx.compose.remote.core.CoreDocument");
      Class<?> profiles = load("androidx.compose.remote.core.RcProfiles");
      int apiLevel = core.getField("DOCUMENT_API_LEVEL").getInt(null);
      int profile = profiles.getField("PROFILE_WEAR_WIDGETS").getInt(null);
      Object known =
          load("androidx.compose.remote.core.Operations")
              .getMethod("getOperations", int.class, int.class)
              .invoke(null, apiLevel, profile);
      // The renderer version is on the watch renderer's scale (Glance's safe fallback is 1.6.0): an
      // older one gets a document without named values. The newest the app's library knows.
      int[] version = rendererVersion();
      Class<?> operation = load(PROTO + "PlayerOperation");
      List<Object> operations = new ArrayList<>();
      for (Object code : (java.util.Set<?>) known.getClass().getMethod("keySet").invoke(known)) {
        operations.add(
            operation.getConstructor(int.class, byteString).newInstance((Integer) code, empty));
      }
      Object request =
          requestType
              .getConstructor(
                  int.class, String.class, float.class, float.class, int.class, float.class,
                  float.class, float.class, int.class, int.class, int.class, List.class,
                  byteString)
              .newInstance(
                  instanceId,
                  "simulator",
                  container.widthDp,
                  container.heightDp,
                  container.type,
                  container.horizontalPaddingDp,
                  container.verticalPaddingDp,
                  container.cornerRadiusDp,
                  version[0],
                  version[1],
                  version[2],
                  operations,
                  empty);
      byte[] payload = encode(requestType, request);
      Class<?> parcelType = load(PARCEL + "WearWidgetRequestParcel");
      Object parcel = parcelType.getConstructor().newInstance();
      parcelType.getField("payload").set(parcel, payload);
      Class<?> callbackType = load(PARCEL + "IWearWidgetCallback");
      Object callback = callback(callbackType, this::content);
      load(PARCEL + "IWearWidgetProvider")
          .getMethod("onWidgetRequest", parcelType, callbackType)
          .invoke(provider(), parcel, callback);
    } catch (ReflectiveOperationException | RuntimeException e) {
      fail(e);
    }
  }

  /** {@code RendererVersion.MAX_RENDERER_VERSION} of the app's Glance Wear, else 1.6.0. */
  private int[] rendererVersion() {
    try {
      Class<?> type = load("androidx.glance.wear.core.RendererVersion");
      java.lang.reflect.Method max = type.getMethod("access$getMAX_RENDERER_VERSION$cp");
      Object v = max.invoke(null);
      return new int[] {
        (Integer) type.getMethod("getMajor").invoke(v),
        (Integer) type.getMethod("getMinor").invoke(v),
        (Integer) type.getMethod("getRevision").invoke(v)
      };
    } catch (ReflectiveOperationException | RuntimeException e) {
      return new int[] {1, 6, 0};
    }
  }

  private void content(Object rawContent) {
    try {
      byte[] payload = (byte[]) rawContent.getClass().getField("payload").get(rawContent);
      Class<?> contentType = load(PROTO + "WearWidgetRawContentProto");
      Object adapter = contentType.getField("ADAPTER").get(null);
      Object proto = adapter.getClass().getMethod("decode", byte[].class).invoke(adapter, payload);
      Object document = contentType.getMethod("getRc_document").invoke(proto);
      byte[] bytes = (byte[]) document.getClass().getMethod("toByteArray").invoke(document);
      play(bytes);
    } catch (ReflectiveOperationException | RuntimeException e) {
      fail(e);
    }
  }

  private void play(byte[] document) throws ReflectiveOperationException {
    if (context == null || parent == null) return;
    Class<?> playerType = load("androidx.compose.remote.player.view.RemoteComposePlayer");
    View view = (View) playerType.getConstructor(Context.class).newInstance(context);
    this.document = document;
    player = view;
    playerType.getMethod("setDocument", byte[].class).invoke(view, (Object) document);
    // An override reaches only the names the document has registered, which it does as it first
    // draws: the values go in after that first frame, then on each change.
    view.getViewTreeObserver()
        .addOnDrawListener(
            new android.view.ViewTreeObserver.OnDrawListener() {
              private boolean applied;

              @Override
              public void onDraw() {
                if (applied) return;
                applied = true;
                view.post(WidgetClient.this::applySystemData);
              }
            });
    // The host masks the widget to its container's shape; 999dp corners clamp to a pill.
    float radius = container.cornerRadiusDp * app.getResources().getDisplayMetrics().density;
    view.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View v, Outline outline) {
            outline.setRoundRect(
                0, 0, v.getWidth(), v.getHeight(), Math.min(radius, v.getHeight() / 2f));
          }
        });
    view.setClipToOutline(true);
    parent.removeAllViews();
    parent.addView(
        view,
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    error = null;
    listener.rendered(this, view, null);
  }

  /**
   * Registers the document's named variables with the player's context. The player only finds
   * those at the document's top level, and Glance Wear writes them inside the layout, beside the
   * text that shows them; without this an override of {@code USER:metrics.heart_rate} has no
   * variable to reach.
   */
  private void registerNames() throws ReflectiveOperationException {
    Object context = null;
    for (Class<?> k = player.getClass(); k != null && context == null; k = k.getSuperclass()) {
      for (Field field : k.getDeclaredFields()) {
        if (!field.getType().getName().endsWith(".RemoteComposeView")) continue;
        field.setAccessible(true);
        Object view = field.get(player);
        Field arContext = view.getClass().getDeclaredField("mARContext");
        arContext.setAccessible(true);
        context = arContext.get(view);
      }
    }
    if (context == null) return;
    Class<?> bufferType = load("androidx.compose.remote.core.RemoteComposeBuffer");
    Object buffer =
        bufferType
            .getMethod("fromInputStream", java.io.InputStream.class)
            .invoke(null, new java.io.ByteArrayInputStream(document));
    ArrayList<Object> ops = new ArrayList<>();
    bufferType.getMethod("inflateFromBuffer", ArrayList.class).invoke(buffer, ops);
    Class<?> named = load("androidx.compose.remote.core.operations.NamedVariable");
    java.lang.reflect.Method register =
        load("androidx.compose.remote.core.RemoteContext")
            .getMethod("loadVariableName", String.class, int.class, int.class);
    for (Object op : ops) {
      if (!named.isInstance(op)) continue;
      register.invoke(
          context,
          named.getField("mVarName").get(op),
          named.getField("mVarId").getInt(op),
          named.getField("mVarType").getInt(op));
    }
  }

  /** Hands the health values to the player, under the names Glance Wear's widgets read. */
  private void applySystemData() {
    if (player == null) return;
    try {
      registerNames();
    } catch (ReflectiveOperationException | RuntimeException e) {
      // The player's internals moved: the widget shows its defaults.
    }
    for (Map.Entry<String, Number> e : systemData.widgetValues().entrySet()) {
      boolean whole = e.getValue() instanceof Integer;
      try {
        if (whole) {
          player.getClass().getMethod("setUserLocalInt", String.class, int.class)
              .invoke(player, e.getKey(), e.getValue().intValue());
        } else {
          player.getClass().getMethod("setUserLocalFloat", String.class, float.class)
              .invoke(player, e.getKey(), e.getValue().floatValue());
        }
      } catch (ReflectiveOperationException | RuntimeException ex) {
        // A player without that setter: the widget shows its default.
      }
    }
    player.invalidate();
  }

  private void fail(Throwable t) {
    Throwable root = t;
    while (root instanceof java.lang.reflect.InvocationTargetException && root.getCause() != null) {
      root = root.getCause();
    }
    error = String.valueOf(root);
    System.err.println("compose-preview launch: widget " + component.getClassName() + ": " + error);
    root.printStackTrace();
    listener.rendered(this, null, error);
  }

  /** {@code Proto.ADAPTER.encode(proto)}. */
  private static byte[] encode(Class<?> type, Object proto) throws ReflectiveOperationException {
    Object adapter = type.getField("ADAPTER").get(null);
    return (byte[]) adapter.getClass().getMethod("encode", Object.class).invoke(adapter, proto);
  }

  /** The AIDL callback: content goes to [onContent]; the interface version is the AIDL's. */
  private Object callback(Class<?> type, Consumer<Object> onContent) {
    Binder binder = new Binder();
    InvocationHandler handler =
        (proxy, m, args) -> {
          switch (m.getName()) {
            case "updateWidgetContent":
              onContent.accept(args[0]);
              return null;
            case "getInterfaceVersion":
              {
                try {
                  Field version = type.getField("VERSION");
                  return version.getInt(null);
                } catch (ReflectiveOperationException e) {
                  return 1;
                }
              }
            case "asBinder":
              return binder;
            case "hashCode":
              return System.identityHashCode(proxy);
            case "equals":
              return proxy == args[0];
            case "toString":
              return "WidgetClient.callback";
            default:
              return null;
          }
        };
    return Proxy.newProxyInstance(app.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }
}
