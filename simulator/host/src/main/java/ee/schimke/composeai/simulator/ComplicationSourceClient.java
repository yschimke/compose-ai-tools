package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * One complication data source ({@code ComplicationDataSourceService}), driven as a watch's
 * complication host drives it: bound by its {@code ACTION_COMPLICATION_UPDATE_REQUEST} action,
 * activated for one slot and type, then asked for an update, its answer arriving through an {@code
 * IComplicationManager} this class implements. The AIDL and the {@code ComplicationData} it carries
 * are the app's own classes, reached reflectively as {@link TileClient} does.
 */
final class ComplicationSourceClient {

  /** {@code ComplicationDataSourceService.ACTION_COMPLICATION_UPDATE_REQUEST}. */
  static final String UPDATE_ACTION =
      "android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST";

  private static final String COMPLICATIONS = "android.support.wearable.complications.";

  final ComponentName component;
  final int instanceId;
  final int type;
  private final Application app;
  private final Navigator navigator;
  private final Consumer<Object> onData;
  private Object provider;
  private IBinder manager;
  private int updates;

  ComplicationSourceClient(
      Application app,
      Navigator navigator,
      ComponentName component,
      int instanceId,
      int type,
      Consumer<Object> onData) {
    this.app = app;
    this.navigator = navigator;
    this.component = component;
    this.instanceId = instanceId;
    this.type = type;
    this.onData = onData;
  }

  int updates() {
    return updates;
  }

  /** Binds, activates the complication and requests its data. */
  void start() throws ReflectiveOperationException {
    IBinder binder = navigator.bindForHost(new Intent(UPDATE_ACTION).setComponent(component));
    if (binder == null) throw new IllegalStateException(component + " returned no binder");
    provider =
        load(COMPLICATIONS + "IComplicationProvider$Stub")
            .getMethod("asInterface", IBinder.class)
            .invoke(null, binder);
    manager = manager();
    Class<?> providerType = load(COMPLICATIONS + "IComplicationProvider");
    providerType
        .getMethod("onComplicationActivated", int.class, int.class, IBinder.class)
        .invoke(provider, instanceId, type, manager);
    update();
  }

  /** Asks for fresh data, as the system does when the source requests an update. */
  void update() throws ReflectiveOperationException {
    load(COMPLICATIONS + "IComplicationProvider")
        .getMethod("onUpdate", int.class, int.class, IBinder.class)
        .invoke(provider, instanceId, type, manager);
  }

  void release() {
    if (provider == null) return;
    try {
      load(COMPLICATIONS + "IComplicationProvider")
          .getMethod("onComplicationDeactivated", int.class)
          .invoke(provider, instanceId);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Already gone.
    }
    navigator.unbindService(new Intent(UPDATE_ACTION).setComponent(component));
    provider = null;
  }

  /**
   * The {@code IComplicationManager} the source answers through. The service reads it back with
   * {@code IComplicationManager.Stub.asInterface(binder)}, which returns the local interface
   * attached to the binder, so a proxy attached to a plain {@link Binder} is enough in process.
   */
  private IBinder manager() throws ClassNotFoundException {
    Class<?> type = load(COMPLICATIONS + "IComplicationManager");
    Binder binder = new Binder();
    InvocationHandler handler =
        (proxy, m, args) -> {
          switch (m.getName()) {
            case "updateComplicationData":
              updates++;
              onData.accept(args[1]);
              return null;
            case "asBinder":
              return binder;
            case "hashCode":
              return System.identityHashCode(proxy);
            case "equals":
              return proxy == args[0];
            case "toString":
              return "ComplicationSourceClient.manager";
            default:
              return null;
          }
        };
    Object manager = Proxy.newProxyInstance(app.getClassLoader(), new Class<?>[] {type}, handler);
    // The AIDL's descriptor is the interface's name.
    binder.attachInterface((IInterface) manager, type.getName());
    return binder;
  }

  /**
   * A complication's text as a host without the app's renderer shows it: its short text (with its
   * title), else its long text, else its type. [data] is the app's {@code ComplicationData}.
   */
  static String text(Object data, Resources resources) {
    if (data == null) return null;
    long now = System.currentTimeMillis();
    String shortText = textAt(data, "getShortText", resources, now);
    String title = textAt(data, "getShortTitle", resources, now);
    if (shortText != null) return title == null ? shortText : shortText + " " + title;
    String longText = textAt(data, "getLongText", resources, now);
    if (longText != null) return longText;
    try {
      Method m = data.getClass().getMethod("getType");
      return "type " + m.invoke(data);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return "?";
    }
  }

  private static String textAt(Object data, String getter, Resources resources, long now) {
    try {
      Method m = data.getClass().getMethod(getter);
      Object text = m.invoke(data);
      if (text == null) return null;
      Object value =
          text.getClass()
              .getMethod("getTextAt", Resources.class, long.class)
              .invoke(text, resources, now);
      return value == null || value.toString().isEmpty() ? null : value.toString();
    } catch (ReflectiveOperationException | RuntimeException e) {
      // The getter throws for a type that has no such field.
      return null;
    }
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }
}
