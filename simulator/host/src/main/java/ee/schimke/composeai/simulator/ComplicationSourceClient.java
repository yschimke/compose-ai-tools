package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcelable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * One complication data source ({@code ComplicationDataSourceService}), driven as a watch's
 * complication host drives it: bound by its {@code ACTION_COMPLICATION_UPDATE_REQUEST} action,
 * activated for one slot and type, then asked for an update, its answer arriving through an {@code
 * IComplicationManager} this class implements. Supports both in-process services and cross-JVM
 * {@code --install} peer Wear apps over {@link DeviceBus}.
 */
final class ComplicationSourceClient {

  /** {@code ComplicationDataSourceService.ACTION_COMPLICATION_UPDATE_REQUEST}. */
  static final String UPDATE_ACTION =
      "android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST";

  static final String METADATA_UPDATE_PERIOD_SECONDS =
      "android.support.wearable.complications.UPDATE_PERIOD_SECONDS";

  private static final String COMPLICATIONS = "android.support.wearable.complications.";

  private static final String BUS_ACTIVATE = "complicationActivate";
  private static final String BUS_UPDATE = "complicationUpdate";
  private static final String BUS_DEACTIVATE = "complicationDeactivate";
  private static final String BUS_DATA = "complicationData";
  private static final String BUS_TAP = "complicationTap";
  private static final long BUS_TIMEOUT_S = 10;

  private static final AtomicLong nextRemoteId = new AtomicLong(1);
  private static final Map<Long, ComplicationSourceClient> activeRemotes =
      new ConcurrentHashMap<>();
  private static final Map<String, RemoteServed> servedOnPeer = new ConcurrentHashMap<>();

  private static final class RemoteServed {
    final String hostPkg;
    final long id;
    final Intent intent;
    final int instanceId;
    final int type;
    Object provider;
    IBinder manager;
    volatile Object lastData;

    RemoteServed(String hostPkg, long id, Intent intent, int instanceId, int type) {
      this.hostPkg = hostPkg;
      this.id = id;
      this.intent = intent;
      this.instanceId = instanceId;
      this.type = type;
    }
  }

  final ComponentName component;
  final int instanceId;
  final int type;
  private final Application app;
  private final Navigator navigator;
  private final Consumer<Object> onData;
  private final int updatePeriodSeconds;
  private Object provider;
  private IBinder manager;
  private String remoteOwner;
  private long remoteId;
  private int updates;
  private long lastUpdateMillis;
  private volatile boolean remoteHasTapAction;

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
    this.updatePeriodSeconds = readUpdatePeriodSeconds(app, component);
  }

  int updates() {
    return updates;
  }

  int updatePeriodSeconds() {
    return updatePeriodSeconds;
  }

  long lastUpdateMillis() {
    return lastUpdateMillis;
  }

  boolean isRemote() {
    return remoteOwner != null;
  }

  boolean remoteHasTapAction() {
    return remoteHasTapAction;
  }

  private static int readUpdatePeriodSeconds(Application app, ComponentName component) {
    if (app == null || component == null) return 0;
    try {
      ServiceInfo info =
          app.getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
      if (info != null && info.metaData != null) {
        return Math.max(0, info.metaData.getInt(METADATA_UPDATE_PERIOD_SECONDS, 0));
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
    }
    return 0;
  }

  /** Binds, activates the complication and requests its data. */
  void start() throws ReflectiveOperationException {
    Intent serviceIntent = new Intent(UPDATE_ACTION).setComponent(component);
    String owner = DeviceServices.ownerOf(serviceIntent);
    if (owner != null) {
      startRemote(owner);
      return;
    }
    IBinder binder = navigator.bindForHost(serviceIntent);
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

  private void startRemote(String owner) {
    this.remoteOwner = owner;
    this.remoteId = nextRemoteId.getAndIncrement();
    activeRemotes.put(remoteId, this);
    JSONObject reply =
        askBus(
            owner,
            BUS_ACTIVATE,
            new Protocol.Json()
                .put("id", remoteId)
                .put("component", component.flattenToString())
                .put("instanceId", instanceId)
                .put("type", type)
                .object());
    if (reply != null && reply.has("busError")) {
      activeRemotes.remove(remoteId);
      throw new IllegalStateException(Protocol.string(reply, "busError"));
    }
    updateRemote();
  }

  /** Asks for fresh data, as the system does when the source requests an update. */
  void update() throws ReflectiveOperationException {
    lastUpdateMillis = WatchState.currentTimeMillis();
    if (remoteOwner != null) {
      updateRemote();
      return;
    }
    if (provider == null) return;
    load(COMPLICATIONS + "IComplicationProvider")
        .getMethod("onUpdate", int.class, int.class, IBinder.class)
        .invoke(provider, instanceId, type, manager);
  }

  private void updateRemote() {
    if (remoteOwner == null) return;
    lastUpdateMillis = WatchState.currentTimeMillis();
    askBus(remoteOwner, BUS_UPDATE, new Protocol.Json().put("id", remoteId).object());
  }

  /** Fires the remote complication's {@code tapAction} inside the peer JVM that owns it. */
  boolean sendRemoteTap() {
    if (remoteOwner == null) return false;
    JSONObject reply =
        askBus(remoteOwner, BUS_TAP, new Protocol.Json().put("id", remoteId).object());
    return reply != null && reply.optBoolean("tapped", false);
  }

  void release() {
    if (remoteOwner != null) {
      activeRemotes.remove(remoteId);
      String owner = remoteOwner;
      long id = remoteId;
      remoteOwner = null;
      DeviceBus.request(owner, BUS_DEACTIVATE, new Protocol.Json().put("id", id).object());
      return;
    }
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
   * Registers {@link DeviceBus} handlers so this app can both host complications from peer {@code
   * --install} Wear apps and serve its own {@code ComplicationDataSourceService}s to a peer watch
   * face host.
   */
  static void serveOnBus(Application app) {
    DeviceBus.on(
        BUS_DATA,
        (from, body, reply) -> {
          long id = body.getLong("id");
          ComplicationSourceClient client = activeRemotes.get(id);
          if (client != null) {
            client.updates++;
            client.lastUpdateMillis = WatchState.currentTimeMillis();
            JSONObject summary = body.optJSONObject("summary");
            client.remoteHasTapAction = summary != null && summary.optBoolean("hasTapAction");
            String encoded = Protocol.string(body, "data");
            Parcelable decoded =
                encoded != null
                    ? ComplicationParcels.decodeParcelable(app.getClassLoader(), encoded)
                    : null;
            Object delivered = decoded != null ? decoded : summary;
            client.onData.accept(delivered);
          }
          reply.send(new JSONObject());
        });
    DeviceBus.on(
        BUS_ACTIVATE,
        (from, body, reply) -> {
          long id = body.getLong("id");
          ComponentName comp = ComponentName.unflattenFromString(body.getString("component"));
          int instanceId = body.getInt("instanceId");
          int wireType = body.getInt("type");
          Navigator nav = Navigator.current();
          if (nav == null || comp == null) {
            reply.send(DeviceBus.error("navigator not ready on " + app.getPackageName()));
            return;
          }
          Intent serviceIntent = new Intent(UPDATE_ACTION).setComponent(comp);
          IBinder binder = nav.bindForHost(serviceIntent);
          if (binder == null) {
            reply.send(DeviceBus.error(comp + " returned no binder"));
            return;
          }
          RemoteServed served = new RemoteServed(from, id, serviceIntent, instanceId, wireType);
          ClassLoader cl = app.getClassLoader();
          Object remoteProvider =
              Class.forName(COMPLICATIONS + "IComplicationProvider$Stub", true, cl)
                  .getMethod("asInterface", IBinder.class)
                  .invoke(null, binder);
          IBinder remoteManager =
              createManagerBinder(
                  cl,
                  data -> {
                    served.lastData = data;
                    String encoded =
                        data instanceof Parcelable ? Parcels.encode((Parcelable) data) : null;
                    JSONObject summary =
                        ComplicationParcels.summarizeToJson(
                            data, app.getResources(), WatchState.currentTimeMillis(), instanceId);
                    DeviceBus.request(
                        from,
                        BUS_DATA,
                        new Protocol.Json()
                            .put("id", id)
                            .put("data", encoded)
                            .put("summary", summary)
                            .object());
                  });
          served.provider = remoteProvider;
          served.manager = remoteManager;
          servedOnPeer.put(from + "/" + id, served);
          Class.forName(COMPLICATIONS + "IComplicationProvider", true, cl)
              .getMethod("onComplicationActivated", int.class, int.class, IBinder.class)
              .invoke(remoteProvider, instanceId, wireType, remoteManager);
          reply.send(new JSONObject());
        });
    DeviceBus.on(
        BUS_UPDATE,
        (from, body, reply) -> {
          RemoteServed served = servedOnPeer.get(from + "/" + body.getLong("id"));
          if (served == null || served.provider == null) {
            reply.send(DeviceBus.error("complication not active"));
            return;
          }
          Class.forName(COMPLICATIONS + "IComplicationProvider", true, app.getClassLoader())
              .getMethod("onUpdate", int.class, int.class, IBinder.class)
              .invoke(served.provider, served.instanceId, served.type, served.manager);
          reply.send(new JSONObject());
        });
    DeviceBus.on(
        BUS_DEACTIVATE,
        (from, body, reply) -> {
          RemoteServed served = servedOnPeer.remove(from + "/" + body.getLong("id"));
          if (served != null && served.provider != null) {
            try {
              Class.forName(COMPLICATIONS + "IComplicationProvider", true, app.getClassLoader())
                  .getMethod("onComplicationDeactivated", int.class)
                  .invoke(served.provider, served.instanceId);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            Navigator nav = Navigator.current();
            if (nav != null) nav.unbindService(served.intent);
          }
          reply.send(new JSONObject());
        });
    DeviceBus.on(
        BUS_TAP,
        (from, body, reply) -> {
          RemoteServed served = servedOnPeer.get(from + "/" + body.getLong("id"));
          boolean tapped = false;
          if (served != null && served.lastData != null) {
            Object effective =
                ComplicationParcels.activeTimelineEntry(served.lastData, WatchState.currentTimeMillis());
            PendingIntent pi = ComplicationParcels.tapAction(effective);
            if (pi == null) pi = ComplicationParcels.tapAction(served.lastData);
            Navigator nav = Navigator.current();
            if (pi != null && nav != null) {
              PendingIntents.send(pi, null, nav);
              tapped = true;
            }
          }
          reply.send(new Protocol.Json().put("tapped", tapped).object());
        });
    DeviceBus.onBroadcast(
        WatchFaceHost.BUS_REQUEST_UPDATE,
        (from, body) -> {
          WatchFaceHost host = WatchFaceHost.current();
          if (host != null) {
            host.onRemoteUpdateRequested(body);
          }
        });
  }

  private static JSONObject askBus(String to, String op, JSONObject body) {
    try {
      return DeviceBus.request(to, op, body).get(BUS_TIMEOUT_S, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException(to + " did not answer " + op + ": " + e, e);
    }
  }

  /**
   * The {@code IComplicationManager} the source answers through. The service reads it back with
   * {@code IComplicationManager.Stub.asInterface(binder)}, which returns the local interface
   * attached to the binder, so a proxy attached to a plain {@link Binder} is enough in process.
   */
  private IBinder manager() throws ClassNotFoundException {
    return createManagerBinder(
        app.getClassLoader(),
        data -> {
          updates++;
          lastUpdateMillis = WatchState.currentTimeMillis();
          onData.accept(data);
        });
  }

  private static IBinder createManagerBinder(ClassLoader classLoader, Consumer<Object> callback)
      throws ClassNotFoundException {
    Class<?> type = Class.forName(COMPLICATIONS + "IComplicationManager", true, classLoader);
    Binder binder = new Binder();
    InvocationHandler handler =
        (proxy, m, args) -> {
          switch (m.getName()) {
            case "updateComplicationData":
              callback.accept(args[1]);
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
    Object manager = Proxy.newProxyInstance(classLoader, new Class<?>[] {type}, handler);
    // The AIDL's descriptor is the interface's name.
    binder.attachInterface((IInterface) manager, type.getName());
    return binder;
  }

  /**
   * A complication's text as a host without the app's renderer shows it: its short text (with its
   * title), else its long text, else its type. [data] is the app's {@code ComplicationData} or a
   * cross-JVM JSON summary.
   */
  static String text(Object data, Resources resources) {
    return text(data, resources, WatchState.currentTimeMillis());
  }

  static String text(Object data, Resources resources, long now) {
    if (data == null) return null;
    if (data instanceof JSONObject) {
      JSONObject json = (JSONObject) data;
      String t = json.isNull("text") ? null : json.optString("text", null);
      String title = json.isNull("title") ? null : json.optString("title", null);
      if (t != null && !t.isEmpty()) {
        return title == null || title.isEmpty() ? t : t + " " + title;
      }
      if (json.has("value")) return String.valueOf(json.opt("value"));
      return "type " + json.optInt("type", 0);
    }
    Object effective = ComplicationParcels.activeTimelineEntry(data, now);
    String shortText = textAt(effective, "getShortText", resources, now);
    String title = textAt(effective, "getShortTitle", resources, now);
    if (shortText != null) return title == null ? shortText : shortText + " " + title;
    String longText = textAt(effective, "getLongText", resources, now);
    if (longText != null) return longText;
    try {
      Method m = effective.getClass().getMethod("getType");
      return "type " + m.invoke(effective);
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
