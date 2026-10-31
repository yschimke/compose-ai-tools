package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBroadcastPendingResult;
import org.robolectric.shadows.ShadowBroadcastReceiver;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Broadcasts between the device's apps ({@code --app phone:a.apk --app phone:b.apk}), each in a
 * simulator of its own. What an app sends with {@code sendBroadcast}, {@code sendOrderedBroadcast}
 * or {@code sendStickyBroadcast} also goes over the {@link DeviceBus} to the device's other apps,
 * marshalled ({@link Parcels}) so its extras keep their types:
 *
 * <ul>
 *   <li>an implicit broadcast reaches every app's receivers registered at run time, except those
 *       registered {@code RECEIVER_NOT_EXPORTED}, and, as since API 26, none declared in a
 *       manifest;
 *   <li>one naming another app (its package or a component) goes to that app alone, to its
 *       exported manifest receivers too, and not to the sender's;
 *   <li>an ordered one goes to every app's receivers one at a time, highest priority first, each
 *       seeing the result code, data and extras the one before left, until one aborts it; the
 *       sender's result receiver then gets the final result;
 *   <li>a sticky one is kept by every app, for the receivers registered later, as well as
 *       delivered;
 *   <li>a receiver permission is checked against the receiving app's granted permissions;
 *   <li>an implicit {@code android.*} action is the system's own, which each app's simulator sends
 *       itself, so it is not passed on.
 * </ul>
 *
 * <p>Each app that gets one reports a {@code broadcast} event naming the sender. An ordered
 * broadcast with no receiver in another app keeps Robolectric's own delivery.
 */
final class DeviceBroadcasts {

  private static final String EVENT = "intent";
  private static final String ORDERED_TARGETS = "orderedTargets";
  private static final String ORDERED_DELIVER = "orderedDeliver";
  private static final long TIMEOUT_S = 10;

  private static volatile Application app;
  private static volatile Protocol protocol;

  /** Set while a broadcast from another app is delivered here, which is not sent on again. */
  private static boolean receiving;

  static void attach(Application app, Navigator navigator, Protocol protocol) {
    DeviceBroadcasts.app = app;
    DeviceBroadcasts.protocol = protocol;
    DeviceBus.onBroadcast(EVENT, DeviceBroadcasts::heard);
    DeviceBus.on(
        ORDERED_TARGETS,
        (from, body, reply) -> {
          JSONArray priorities = new JSONArray();
          Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
          if (intent != null && holds(Protocol.string(body, "permission"))) {
            for (Target target : targets(intent, body.optBoolean("named"))) {
              priorities.put(target.priority);
            }
          }
          reply.send(new Protocol.Json().put("priorities", priorities).object());
        });
    DeviceBus.on(
        ORDERED_DELIVER,
        (from, body, reply) -> {
          Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
          List<Target> targets = targets(intent, body.optBoolean("named"));
          int index = body.getInt("index");
          Result in =
              new Result(
                  body.getInt("code"),
                  Protocol.string(body, "data"),
                  Parcels.decode(Protocol.string(body, "extras"), Bundle.CREATOR),
                  false);
          if (index < targets.size() && !body.optBoolean("named")) {
            DeviceProviders.claim(from, intent);
          }
          Result out = index < targets.size() ? deliver(targets.get(index), intent, in, true) : in;
          event(from, intent, "ordered");
          reply.send(out.toJson());
        });
  }

  // -----------------------------------------------------------------------------------------------
  // Sending

  /** The other app [intent] names, this app's own package, or null when it names none. */
  private static String target(Intent intent) {
    ComponentName component = intent.getComponent();
    return component != null ? component.getPackageName() : intent.getPackage();
  }

  /**
   * Whether [intent], sent by this app, goes to the device's other apps: not one naming this app or
   * one not on the device, and not the system's own implicit broadcasts (TIME_TICK,
   * CONNECTIVITY_CHANGE, ...), which each app's simulator sends itself: only the system may send a
   * protected android.* action.
   */
  private static boolean crosses(Intent intent) {
    Application self = app;
    if (self == null || receiving || !DeviceBus.enabled()) return false;
    String target = target(intent);
    if (target != null) return !target.equals(self.getPackageName()) && DevicePeers.isPeer(target);
    String action = intent.getAction();
    return action == null || !action.startsWith("android.");
  }

  /** The URIs [intent] grants, the apps it reaches may now open ({@link DeviceProviders}). */
  private static void grant(Intent intent) {
    String target = target(intent);
    if (target != null) DeviceProviders.grantFrom(intent, target);
    else DeviceProviders.offer(intent);
  }

  /**
   * The app's {@code sendBroadcast} of [intent], before its own delivery: sent on to the device's
   * other apps. True when it names another app, which then has it alone, so it is not delivered
   * here.
   */
  static boolean send(Intent intent, String receiverPermission) {
    if (!crosses(intent)) return false;
    grant(intent);
    DeviceBus.broadcast(
        EVENT,
        new Protocol.Json()
            .put("to", target(intent))
            .put("intent", Parcels.encode(intent))
            .put("permission", receiverPermission)
            .object());
    return target(intent) != null;
  }

  /** The app's {@code sendStickyBroadcast} of [intent]: every other app keeps it too. */
  static void sendSticky(Intent intent) {
    if (!crosses(intent) || target(intent) != null) return;
    DeviceBus.broadcast(
        EVENT,
        new Protocol.Json().put("intent", Parcels.encode(intent)).put("sticky", true).object());
  }

  /**
   * The app's {@code sendOrderedBroadcast}: when a receiver in another app gets it too, the whole
   * chain runs here, on the main thread, every app's receivers in priority order, and true is
   * returned; false leaves it to Robolectric's own delivery.
   */
  static boolean sendOrdered(
      Intent intent,
      String receiverPermission,
      BroadcastReceiver resultReceiver,
      Handler scheduler,
      int initialCode,
      String initialData,
      Bundle initialExtras) {
    if (!crosses(intent)) return false;
    String target = target(intent);
    boolean named = target != null;
    List<String> apps = named ? List.of(target) : DevicePeers.packages();
    String encoded = Parcels.encode(intent);
    List<Step> chain = new ArrayList<>();
    if (!named) {
      List<Target> local = targets(intent, false, true);
      for (int i = 0; i < local.size(); i++) chain.add(new Step(null, i, local.get(i).priority));
    }
    boolean remote = false;
    for (String other : apps) {
      JSONObject answer =
          ask(
              other,
              ORDERED_TARGETS,
              new Protocol.Json()
                  .put("intent", encoded)
                  .put("named", named)
                  .put("permission", receiverPermission)
                  .object());
      JSONArray priorities = answer == null ? null : answer.optJSONArray("priorities");
      if (priorities == null) continue;
      for (int i = 0; i < priorities.length(); i++) {
        chain.add(new Step(other, i, priorities.optInt(i)));
        remote = true;
      }
    }
    if (!remote && !named) return false;
    grant(intent);
    // Highest priority first; among equals, the order they were gathered in.
    chain.sort(Comparator.comparingInt((Step s) -> s.priority).reversed());
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              Result result = new Result(initialCode, initialData, initialExtras, false);
              List<Target> local = named ? List.of() : targets(intent, false, true);
              for (Step step : chain) {
                if (step.app == null && step.index >= local.size()) continue;
                result =
                    step.app == null
                        ? deliver(local.get(step.index), intent, result, true)
                        : deliverRemote(step, encoded, named, result);
                if (result.abort) break;
              }
              if (resultReceiver == null) return;
              Result last = result;
              Target finalTarget = new Target(0, resultReceiver, app, null);
              Runnable deliverFinal = () -> deliver(finalTarget, intent, last, true);
              if (scheduler != null) scheduler.post(deliverFinal);
              else deliverFinal.run();
            });
    return true;
  }

  private static Result deliverRemote(Step step, String encoded, boolean named, Result in) {
    JSONObject answer =
        ask(
            step.app,
            ORDERED_DELIVER,
            new Protocol.Json()
                .put("intent", encoded)
                .put("named", named)
                .put("index", step.index)
                .put("code", in.code)
                .put("data", in.data)
                .put("extras", in.extras == null ? null : Parcels.encode(in.extras))
                .object());
    if (answer == null || answer.has("busError")) return in;
    return new Result(
        answer.optInt("code", in.code),
        Protocol.string(answer, "data"),
        Parcels.decode(Protocol.string(answer, "extras"), Bundle.CREATOR),
        answer.optBoolean("abort"));
  }

  private static JSONObject ask(String to, String op, JSONObject body) {
    try {
      return DeviceBus.request(to, op, body).get(TIMEOUT_S, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException e) {
      return null;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Receiving

  private static void heard(String from, JSONObject body) {
    Application self = app;
    String to = Protocol.string(body, "to");
    if (self == null || (to != null && !to.equals(self.getPackageName()))) return;
    if (!holds(Protocol.string(body, "permission"))) return;
    Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
    if (intent == null) return;
    if (body.optBoolean("sticky")) {
      receiving = true;
      try {
        // Kept for the receivers registered later, and delivered to those registered now.
        self.sendStickyBroadcast(intent);
      } finally {
        receiving = false;
      }
      event(from, intent, "sticky");
      return;
    }
    List<Target> targets = targets(intent, to != null);
    // The URIs an implicit broadcast grants are this app's to open when a receiver of its gets it.
    if (to == null && !targets.isEmpty()) DeviceProviders.claim(from, intent);
    JSONArray delivered = new JSONArray();
    for (Target target : targets) {
      deliver(target, intent, new Result(0, null, null, false), false);
      delivered.put(target.name());
    }
    protocol.event(
        "broadcast",
        new Protocol.Json()
            .put("from", from)
            .put("intent", Intents.toJson(intent))
            .put("delivered", delivered)
            .object());
  }

  private static void event(String from, Intent intent, String kind) {
    protocol.event(
        "broadcast",
        new Protocol.Json()
            .put("from", from)
            .put("intent", Intents.toJson(intent))
            .put("kind", kind)
            .object());
  }

  /** Whether this app holds [permission], a receiver permission the sender named; null holds. */
  private static boolean holds(String permission) {
    return permission == null
        || app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  /** One of this app's receivers that another app's broadcast reaches. */
  private static final class Target {
    final int priority;
    final BroadcastReceiver runtime;
    final Context context;
    final String manifest;

    Target(int priority, BroadcastReceiver runtime, Context context, String manifest) {
      this.priority = priority;
      this.runtime = runtime;
      this.context = context;
      this.manifest = manifest;
    }

    String name() {
      return runtime != null ? runtime.getClass().getName() : manifest;
    }
  }

  /**
   * This app's receivers of [intent] from another app, highest priority first: those registered at
   * run time and exported, and, when it [named] this app, its exported manifest receivers. One
   * naming a component reaches that receiver alone, which is a manifest one.
   */
  private static List<Target> targets(Intent intent, boolean named) {
    return targets(intent, named, false);
  }

  /**
   * As {@link #targets(Intent, boolean)}; [own] is this app's own broadcast, which its receivers
   * registered {@code RECEIVER_NOT_EXPORTED} get too.
   */
  private static List<Target> targets(Intent intent, boolean named, boolean own) {
    List<Target> out = new ArrayList<>();
    ComponentName component = intent.getComponent();
    ShadowApplication shadow = Shadow.extract(app);
    for (ShadowApplication.Wrapper wrapper : shadow.getRegisteredReceivers()) {
      if (component != null) break;
      if (!own && (wrapper.flags & Context.RECEIVER_NOT_EXPORTED) != 0) continue;
      if (wrapper.intentFilter.match(app.getContentResolver(), intent, false, "simulator") < 0) {
        continue;
      }
      out.add(
          new Target(
              wrapper.intentFilter.getPriority(),
              wrapper.broadcastReceiver,
              wrapper.context,
              null));
    }
    if (named) {
      for (ResolveInfo info : app.getPackageManager().queryBroadcastReceivers(intent, 0)) {
        if (info.activityInfo == null
            || !app.getPackageName().equals(info.activityInfo.packageName)
            || !info.activityInfo.exported
            || (component != null && !component.getClassName().equals(info.activityInfo.name))) {
          continue;
        }
        out.add(new Target(info.priority, null, app, info.activityInfo.name));
      }
    }
    out.sort(Comparator.comparingInt((Target t) -> t.priority).reversed());
    return out;
  }

  /** A receiver's result, as an ordered broadcast passes it on. */
  private static final class Result {
    final int code;
    final String data;
    final Bundle extras;
    final boolean abort;

    Result(int code, String data, Bundle extras, boolean abort) {
      this.code = code;
      this.data = data;
      this.extras = extras;
      this.abort = abort;
    }

    JSONObject toJson() {
      return new Protocol.Json()
          .put("code", code)
          .put("data", data)
          .put("extras", extras == null ? null : Parcels.encode(extras))
          .put("abort", abort)
          .object();
    }
  }

  /** One step of an ordered broadcast: a receiver of [app] (null: this one), by its index. */
  private static final class Step {
    final String app;
    final int index;
    final int priority;

    Step(String app, int index, int priority) {
      this.app = app;
      this.index = index;
      this.priority = priority;
    }
  }

  /**
   * Delivers [intent] to [target] on this thread with [in] as its pending result, as the system
   * delivers each step of a broadcast; returns what the receiver left for the next.
   */
  private static Result deliver(Target target, Intent intent, Result in, boolean ordered) {
    BroadcastReceiver receiver = target.runtime;
    if (receiver == null) {
      try {
        receiver =
            AppComponents.factory(app)
                .instantiateReceiver(app.getClassLoader(), target.manifest, intent);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot create " + target.manifest, e);
      }
    }
    BroadcastReceiver.PendingResult pending =
        ReflectionHelpers.callStaticMethod(
            ShadowBroadcastPendingResult.class,
            "create",
            ClassParameter.from(int.class, in.code),
            ClassParameter.from(String.class, in.data),
            ClassParameter.from(Bundle.class, in.extras),
            ClassParameter.from(boolean.class, ordered));
    // Hidden: BroadcastReceiver.setPendingResult.
    ReflectionHelpers.callInstanceMethod(
        receiver,
        "setPendingResult",
        ClassParameter.from(BroadcastReceiver.PendingResult.class, pending));
    // Robolectric's receiver keeps abortBroadcast() in a flag of its own, which this hands it.
    java.util.concurrent.atomic.AtomicBoolean abort = new java.util.concurrent.atomic.AtomicBoolean();
    ShadowBroadcastReceiver shadow = Shadow.extract(receiver);
    // The receiver's own sends from onReceive are its app's, and go on to the other apps as such.
    shadow.onReceive(target.context, new Intent(intent), abort);
    Result out =
        ordered
            ? new Result(
                receiver.getResultCode(),
                receiver.getResultData(),
                receiver.getResultExtras(false),
                abort.get() || receiver.getAbortBroadcast())
            : in;
    if (target.runtime != null) {
      ReflectionHelpers.callInstanceMethod(
          receiver,
          "setPendingResult",
          ClassParameter.from(BroadcastReceiver.PendingResult.class, null));
    }
    return out;
  }

  private DeviceBroadcasts() {}
}
