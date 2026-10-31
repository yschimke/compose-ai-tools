package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/**
 * Services between the device's apps ({@code --app phone:a.apk --app phone:b.apk}), each in a
 * simulator of its own. An app's {@code startService}, {@code startForegroundService}, {@code
 * stopService}, {@code bindService} and {@code unbindService} naming another app's service go over
 * the {@link DeviceBus} to that app, whose simulator runs the service:
 *
 * <ul>
 *   <li>a start or stop is the owning app's own, so the service is created, gets {@code
 *       onStartCommand} and is destroyed there as it would be for that app;
 *   <li>a bind makes the owning app bind the service, and hands the caller's connection a binder
 *       that carries each {@code transact} to the service's: the call's {@link Parcel} is
 *       marshalled, the service's binder runs it on the owning app's main thread, and its reply
 *       comes back. AIDL interfaces and {@code Messenger}s work so, with their arguments as plain
 *       data; a binder object inside a call (a callback, a {@code replyTo} messenger) or a file
 *       descriptor does not cross.
 * </ul>
 *
 * <p>The caller waits up to ten seconds for each answer; a service whose app is gone fails its
 * calls with {@link DeadObjectException}.
 */
final class DeviceServices {

  private static final String START = "serviceStart";
  private static final String STOP = "serviceStop";
  private static final String BIND = "serviceBind";
  private static final String UNBIND = "serviceUnbind";
  private static final String TRANSACT = "serviceTransact";
  private static final long TIMEOUT_S = 10;

  private static volatile Application app;

  /** This app's connections to other apps' services: the owning app and the binding there. */
  private static final Map<ServiceConnection, Binding> outgoing = new ConcurrentHashMap<>();

  private static final AtomicLong nextBinding = new AtomicLong(1);

  /** The bindings other apps made to this app's services, by their id. */
  private static final Map<String, Served> served = new ConcurrentHashMap<>();

  private static final class Binding {
    final String owner;
    final long id;

    Binding(String owner, long id) {
      this.owner = owner;
      this.id = id;
    }
  }

  private static final class Served {
    final ServiceConnection connection;
    final Intent intent;
    volatile IBinder binder;

    Served(ServiceConnection connection, Intent intent) {
      this.connection = connection;
      this.intent = intent;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The calling app

  /** The other app of the device whose service [service] names, or null. */
  static String ownerOf(Intent service) {
    if (app == null || !DeviceBus.enabled() || service == null) return null;
    ComponentName component = service.getComponent();
    String pkg = component != null ? component.getPackageName() : service.getPackage();
    return pkg != null && !pkg.equals(app.getPackageName()) && DevicePeers.isPeer(pkg) ? pkg : null;
  }

  /** {@code startService} / {@code startForegroundService} of another app's service. */
  static ComponentName start(Intent service, boolean foreground) {
    JSONObject answer =
        ask(
            ownerOf(service),
            START,
            new Protocol.Json()
                .put("intent", Parcels.encode(service))
                .put("foreground", foreground)
                .object());
    String component = answer == null ? null : Protocol.string(answer, "component");
    return component == null ? null : ComponentName.unflattenFromString(component);
  }

  /** {@code stopService} of another app's service. */
  static boolean stop(Intent service) {
    JSONObject answer =
        ask(ownerOf(service), STOP, new Protocol.Json().put("intent", Parcels.encode(service)).object());
    return answer != null && answer.optBoolean("stopped");
  }

  /**
   * {@code bindService} to another app's service: true at once, as a device answers, and the
   * [connection] hears of it on [executor] (the main thread when null) once the owning app has
   * bound it.
   */
  static boolean bind(
      Intent service, ServiceConnection connection, int flags, Executor executor) {
    String owner = ownerOf(service);
    long id = nextBinding.getAndIncrement();
    outgoing.put(connection, new Binding(owner, id));
    Executor deliver =
        executor != null ? executor : new Handler(Looper.getMainLooper())::post;
    DeviceBus.request(
            owner,
            BIND,
            new Protocol.Json()
                .put("id", id)
                .put("intent", Parcels.encode(service))
                .put("flags", flags)
                .object())
        .thenAccept(
            answer -> {
              String name = Protocol.string(answer, "component");
              if (name == null) return;
              ComponentName component = ComponentName.unflattenFromString(name);
              deliver.execute(
                  () -> {
                    if (!outgoing.containsKey(connection)) return;
                    if (answer.optBoolean("nullBinding")) {
                      connection.onNullBinding(component);
                    } else {
                      connection.onServiceConnected(component, new Remote(owner, id));
                    }
                  });
            });
    return true;
  }

  /** {@code unbindService}: true when [connection] was bound to another app's service. */
  static boolean unbind(ServiceConnection connection) {
    Binding binding = outgoing.remove(connection);
    if (binding == null) return false;
    DeviceBus.request(binding.owner, UNBIND, new Protocol.Json().put("id", binding.id).object());
    return true;
  }

  /**
   * Another app's service binder, as this app holds it: each {@code transact} runs on the service's
   * own binder in its app, its {@link Parcel}s marshalled each way.
   */
  static final class Remote extends Binder {
    private final String owner;
    private final long id;

    Remote(String owner, long id) {
      this.owner = owner;
      this.id = id;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      JSONObject answer;
      try {
        answer =
            DeviceBus.request(
                    owner,
                    TRANSACT,
                    new Protocol.Json()
                        .put("id", id)
                        .put("code", code)
                        .put("flags", flags)
                        .put("data", Base64.getEncoder().encodeToString(data.marshall()))
                        .object())
                .get(TIMEOUT_S, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DeadObjectException(e.toString());
      } catch (ExecutionException | TimeoutException e) {
        throw new DeadObjectException(owner + " did not answer: " + e);
      }
      String error = Protocol.string(answer, "busError");
      if (error != null) throw new DeadObjectException(error);
      String bytes = Protocol.string(answer, "reply");
      if (reply != null && bytes != null) {
        byte[] raw = Base64.getDecoder().decode(bytes);
        reply.unmarshall(raw, 0, raw.length);
        reply.setDataPosition(0);
      }
      return answer.optBoolean("handled", true);
    }
  }

  private static JSONObject ask(String owner, String op, JSONObject body) {
    try {
      JSONObject answer = DeviceBus.request(owner, op, body).get(TIMEOUT_S, TimeUnit.SECONDS);
      String error = Protocol.string(answer, "busError");
      if (error != null) throw new IllegalStateException(error);
      return answer;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException(owner + " did not answer " + op + ": " + e, e);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The app whose service it is

  /** Runs the other apps' starts, stops, binds and calls of this app's services. */
  static void serve(Application app) {
    DeviceServices.app = app;
    DeviceBus.on(
        START,
        (from, body, reply) -> {
          Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
          ComponentName started =
              body.optBoolean("foreground")
                  ? app.startForegroundService(intent)
                  : app.startService(intent);
          reply.send(
              new Protocol.Json()
                  .put("component", started == null ? null : started.flattenToString())
                  .object());
        });
    DeviceBus.on(
        STOP,
        (from, body, reply) -> {
          Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
          // Whether it was running, which is what a device answers; Robolectric answers false.
          Navigator navigator = Navigator.current();
          boolean running = navigator != null && navigator.isStarted(intent);
          boolean stopped = app.stopService(intent);
          reply.send(new Protocol.Json().put("stopped", running || stopped).object());
        });
    DeviceBus.on(
        BIND,
        (from, body, reply) -> {
          String key = from + "/" + body.getLong("id");
          Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
          boolean[] answered = {false};
          ServiceConnection connection =
              new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                  Served binding = served.get(key);
                  if (binding != null) binding.binder = binder;
                  if (answered[0]) return;
                  answered[0] = true;
                  reply.send(new Protocol.Json().put("component", name.flattenToString()).object());
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                  Served binding = served.get(key);
                  if (binding != null) binding.binder = null;
                }

                @Override
                public void onNullBinding(ComponentName name) {
                  if (answered[0]) return;
                  answered[0] = true;
                  reply.send(
                      new Protocol.Json()
                          .put("component", name.flattenToString())
                          .put("nullBinding", true)
                          .object());
                }
              };
          served.put(key, new Served(connection, intent));
          if (!app.bindService(intent, connection, body.optInt("flags"))) {
            served.remove(key);
            reply.send(DeviceBus.error("no service of " + app.getPackageName() + " for " + intent));
          }
        });
    DeviceBus.on(
        UNBIND,
        (from, body, reply) -> {
          Served binding = served.remove(from + "/" + body.getLong("id"));
          if (binding != null) {
            app.unbindService(binding.connection);
            // The service's last binding gone: onUnbind, and destroyed unless it was started.
            Navigator navigator = Navigator.current();
            if (navigator != null && binding.binder != null) navigator.unbindService(binding.intent);
          }
          reply.send(new JSONObject());
        });
    DeviceBus.on(
        TRANSACT,
        (from, body, reply) -> {
          Served binding = served.get(from + "/" + body.getLong("id"));
          IBinder binder = binding == null ? null : binding.binder;
          if (binder == null) {
            reply.send(DeviceBus.error("the service is not bound"));
            return;
          }
          byte[] raw = Base64.getDecoder().decode(body.getString("data"));
          Parcel data = Parcel.obtain();
          Parcel out = Parcel.obtain();
          try {
            data.unmarshall(raw, 0, raw.length);
            data.setDataPosition(0);
            boolean handled = binder.transact(body.getInt("code"), data, out, body.optInt("flags"));
            reply.send(
                new Protocol.Json()
                    .put("handled", handled)
                    .put("reply", Base64.getEncoder().encodeToString(out.marshall()))
                    .object());
          } finally {
            data.recycle();
            out.recycle();
          }
        });
  }

  private DeviceServices() {}
}
