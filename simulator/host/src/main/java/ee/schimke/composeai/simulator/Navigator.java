package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowService;

/**
 * Makes the app's own component traffic happen, which Robolectric otherwise only records.
 *
 * <p>Under Robolectric {@code startActivity}, {@code startService} and {@code stopService} are
 * logged on the shadow instrumentation and nothing runs. Each simulator cycle, {@link #pump()}
 * drains those logs and does what the system would: launches activities onto a back stack (pausing
 * and stopping the one below, delivering {@code setResult} to a {@code startActivityForResult}
 * caller when one finishes), and creates, starts and destroys services. The same entry points serve
 * the control channel's {@code startActivity}, {@code startService}, {@code bindService} and {@code
 * broadcast} commands, so a command and the app's own call behave alike. Content providers are
 * created earlier, before {@code Application.onCreate()}, by {@link Providers}.
 *
 * <p>All of it runs on the main thread, inside the sandbox.
 */
final class Navigator {

  /** One activity on the back stack. */
  static final class Entry {
    final ActivityController<? extends Activity> controller;
    /** The intent as the caller started it, before resolution. */
    final Intent request;
    final Entry caller;
    final int requestCode;
    /** Paused and stopped because another activity went on top of it. */
    boolean stopped;

    Entry(
        ActivityController<? extends Activity> controller,
        Intent request,
        Entry caller,
        int requestCode) {
      this.controller = controller;
      this.request = request;
      this.caller = caller;
      this.requestCode = requestCode;
    }

    Activity activity() {
      return controller.get();
    }
  }

  /** A running service and how it is held. */
  static final class Running {
    final ServiceController<? extends Service> controller;
    /** What {@code onBind} returned, once something bound it. */
    IBinder binder;
    boolean started;
    int bindings;
    int lastStartId;

    Running(ServiceController<? extends Service> controller) {
      this.controller = controller;
    }
  }

  private final Application app;
  private final ShadowApplication shadowApp;
  private final Protocol protocol;
  private final Deque<Entry> stack = new ArrayDeque<>();
  private final Map<ComponentName, Running> services = new LinkedHashMap<>();

  /** Intents the app bound through a {@link LazyBinder}, which Robolectric also logs as starts. */
  private final Set<Intent.FilterComparison> boundByApp = new HashSet<>();

  /** The session's navigator, which {@link LazyBinder}s bound during startup resolve to. */
  private static volatile Navigator current;

  Navigator(Application app, Protocol protocol) {
    this.app = app;
    this.shadowApp = Shadow.extract(app);
    this.protocol = protocol;
    current = this;
  }

  Entry top() {
    return stack.peek();
  }

  List<Entry> backStack() {
    return new ArrayList<>(stack);
  }

  /** Drains what the app started or stopped since the last cycle, and reaps finished components. */
  void pump() {
    Intent intent;
    while ((intent = shadowApp.getNextStartedActivity()) != null) {
      ShadowActivity.IntentForResult forResult = takeForResult(intent);
      try {
        startActivity(intent, forResult == null ? -1 : forResult.requestCode);
      } catch (RuntimeException e) {
        protocol.event(
            "error",
            new Protocol.Json()
                .put("kind", "startActivity")
                .put("intent", Intents.toJson(intent))
                .put("message", String.valueOf(e))
                .object());
      }
    }
    while ((intent = shadowApp.getNextStartedService()) != null) {
      // Robolectric logs a bindService as a start too; a bind is not a start.
      // Package-qualified or not, as both are registered for binding.
      if (boundByApp.remove(new Intent.FilterComparison(new Intent(intent).setPackage(null)))) {
        continue;
      }
      try {
        startService(intent);
      } catch (RuntimeException e) {
        protocol.event(
            "error",
            new Protocol.Json()
                .put("kind", "startService")
                .put("intent", Intents.toJson(intent))
                .put("message", String.valueOf(e))
                .object());
      }
    }
    while ((intent = shadowApp.getNextStoppedService()) != null) {
      stopService(intent);
    }
    reapFinishedActivities();
    reapStoppedServices();
  }

  // ---------------------------------------------------------------------------------------------
  // Activities

  /**
   * Launches [intent] on top of the back stack. Honors {@code CLEAR_TASK}, {@code CLEAR_TOP} and
   * {@code SINGLE_TOP}; everything else starts a new instance.
   */
  JSONObject startActivity(Intent intent, int requestCode) {
    ComponentName component = resolveActivity(intent);
    ComponentName implementation = targetOfAlias(component);
    Class<? extends Activity> type = loadClass(implementation.getClassName(), Activity.class);
    Intent launch = new Intent(intent).setComponent(component);
    int flags = intent.getFlags();

    if ((flags & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0) {
      while (!stack.isEmpty()) destroy(stack.pop());
    } else if ((flags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0 && contains(type)) {
      while (stack.peek().activity().getClass() != type) destroy(stack.pop());
      if ((flags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) {
        Entry target = stack.peek();
        deliverNewIntent(target, launch);
        protocol.event("activity", describe(target, "newIntent"));
        return describe(target, "newIntent");
      }
      destroy(stack.pop());
    }
    Entry below = stack.peek();
    if (below != null
        && (flags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
        && below.activity().getClass() == type) {
      deliverNewIntent(below, launch);
      protocol.event("activity", describe(below, "newIntent"));
      return describe(below, "newIntent");
    }

    // A `below` that is already stopped was uncovered by CLEAR_TOP and is not on screen.
    boolean coverBelow = below != null && !below.stopped;
    if (coverBelow) below.controller.pause();
    ActivityController<? extends Activity> controller = Robolectric.buildActivity(type, launch);
    controller.setup();
    if (coverBelow) {
      below.controller.saveInstanceState(new Bundle()).stop();
      below.stopped = true;
    }
    Entry entry = new Entry(controller, intent, below, requestCode);
    stack.push(entry);
    protocol.event("activity", describe(entry, "resumed"));
    return describe(entry, "resumed");
  }

  /** Finishes the top activity the way {@code Activity.finish()} would, without a back press. */
  JSONObject finishTop() {
    Entry top = stack.peek();
    if (top == null) throw new IllegalStateException("no activity is running");
    top.activity().finish();
    reapFinishedActivities();
    Entry now = stack.peek();
    return new Protocol.Json()
        .put("finished", top.activity().getClass().getName())
        .put("top", now == null ? null : now.activity().getClass().getName())
        .object();
  }

  private void reapFinishedActivities() {
    Entry topBefore = stack.peek();
    List<Entry> finishing = new ArrayList<>();
    for (Entry entry : stack) if (entry.activity().isFinishing()) finishing.add(entry);
    if (finishing.isEmpty()) return;
    for (Entry entry : finishing) {
      stack.remove(entry);
      if (entry.caller != null && entry.requestCode >= 0 && stack.contains(entry.caller)) {
        // Delivered before the caller resumes, as `onActivityResult` precedes `onResume`.
        // Robolectric finds the request code by the intent the caller started.
        ShadowActivity shadow = Shadow.extract(entry.activity());
        ShadowActivity caller = Shadow.extract(entry.caller.activity());
        caller.receiveResult(entry.request, shadow.getResultCode(), shadow.getResultIntent());
      }
      destroy(entry);
      protocol.event("activity", describe(entry, "destroyed"));
    }
    if (finishing.contains(topBefore)) {
      Entry resumed = stack.peek();
      if (resumed != null) {
        if (resumed.stopped) bringBack(resumed);
        protocol.event("activity", describe(resumed, "resumed"));
      } else {
        protocol.event("activity", new Protocol.Json().put("state", "empty").object());
      }
    }
  }

  /**
   * {@code onNewIntent} for an activity already on the stack: a resumed one is paused around it,
   * a stopped one (uncovered by CLEAR_TOP) is restarted first and comes back on screen, as the
   * system orders it.
   */
  private void deliverNewIntent(Entry entry, Intent intent) {
    if (entry.stopped) {
      entry.controller.restart();
      entry.controller.newIntent(intent);
      entry.controller.resume().visible();
      entry.stopped = false;
    } else {
      entry.controller.pause();
      entry.controller.newIntent(intent);
      entry.controller.resume();
    }
  }

  /** Restarts and resumes a stopped activity that is now on top again. */
  private void bringBack(Entry entry) {
    entry.controller.restart().resume().visible();
    entry.stopped = false;
  }

  /**
   * The activity class behind [component]: itself, or the {@code targetActivity} of an {@code
   * <activity-alias>}, which has no class of its own. The intent keeps the alias, as on a device.
   */
  private ComponentName targetOfAlias(ComponentName component) {
    try {
      ActivityInfo info = app.getPackageManager().getActivityInfo(component, 0);
      if (info.targetActivity != null) {
        return new ComponentName(component.getPackageName(), info.targetActivity);
      }
    } catch (PackageManager.NameNotFoundException e) {
      // Not in the manifest: load it by its own name, and let that fail if it must.
    }
    return component;
  }

  private void destroy(Entry entry) {
    Activity activity = entry.activity();
    if (activity.isDestroyed()) return;
    // Walk forward through whatever lifecycle the activity is in; each step is a no-op guard in
    // Robolectric if it is already past it.
    try {
      entry.controller.pause();
    } catch (RuntimeException ignored) {
      // Already paused.
    }
    try {
      entry.controller.stop();
    } catch (RuntimeException ignored) {
      // Already stopped.
    }
    entry.controller.destroy();
  }

  private boolean contains(Class<?> type) {
    for (Entry entry : stack) if (entry.activity().getClass() == type) return true;
    return false;
  }

  private ShadowActivity.IntentForResult takeForResult(Intent intent) {
    ShadowActivity.IntentForResult next = shadowApp.peekNextStartedActivityForResult();
    if (next != null && (next.intent == intent || next.intent.filterEquals(intent))) {
      return shadowApp.getNextStartedActivityForResult();
    }
    return null;
  }

  private ComponentName resolveActivity(Intent intent) {
    if (intent.getComponent() != null) return intent.getComponent();
    ResolveInfo info = app.getPackageManager().resolveActivity(intent, 0);
    if (info == null || info.activityInfo == null) {
      throw new IllegalArgumentException("no activity handles " + intent);
    }
    return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
  }

  JSONObject describe(Entry entry, String state) {
    Activity activity = entry.activity();
    return new Protocol.Json()
        .put("state", state)
        .put("activity", activity.getClass().getName())
        .put("intent", Intents.toJson(activity.getIntent()))
        .put("title", activity.getTitle() == null ? null : activity.getTitle().toString())
        .object();
  }

  JSONArray describeStack() {
    JSONArray array = new JSONArray();
    for (Entry entry : stack) {
      array.put(describe(entry, entry.stopped ? "stopped" : "resumed"));
    }
    return array;
  }

  // ---------------------------------------------------------------------------------------------
  // Services

  JSONObject startService(Intent intent) {
    ComponentName component = resolveService(intent);
    Running running = runningOrCreate(component, intent);
    running.started = true;
    running.lastStartId++;
    running.controller.withIntent(intent).startCommand(0, running.lastStartId);
    protocol.event("service", describe(component, running, "started"));
    return describe(component, running, "started");
  }

  JSONObject stopService(Intent intent) {
    ComponentName component = resolveService(intent);
    Running running = services.get(component);
    if (running == null) return new Protocol.Json().put("running", false).object();
    running.started = false;
    if (running.bindings == 0) {
      services.remove(component);
      running.controller.destroy();
      protocol.event("service", describe(component, running, "destroyed"));
      return describe(component, running, "destroyed");
    }
    return describe(component, running, "bound");
  }

  /**
   * Makes the app's own {@code bindService} connect. Robolectric answers a bind with whatever
   * binder was registered for that exact intent, else null, and Firebase Sessions among others
   * fails on the null. Every service the manifest declares is registered under its explicit
   * component intent — how apps bind their own services — with a {@link LazyBinder} that creates
   * and binds the real service when the app first uses it.
   *
   * <p>Runs before the application's {@code onCreate()}, since apps bind there (Firebase Sessions
   * does), before the session and its navigator exist; a binder resolves the navigator when the
   * app first calls it.
   */
  static void registerBindableServices(Application app) {
    ShadowApplication shadowApp = Shadow.extract(app);
    android.content.pm.PackageInfo info;
    try {
      info =
          app.getPackageManager()
              .getPackageInfo(app.getPackageName(), PackageManager.GET_SERVICES);
    } catch (PackageManager.NameNotFoundException e) {
      return;
    }
    if (info.services == null) return;
    for (ServiceInfo service : info.services) {
      ComponentName component = new ComponentName(service.packageName, service.name);
      LazyBinder binder = new LazyBinder(component);
      // Robolectric matches the whole intent, so the package-qualified form is registered too.
      shadowApp.setComponentNameAndServiceForBindServiceForIntent(
          new Intent().setComponent(component), component, binder);
      shadowApp.setComponentNameAndServiceForBindServiceForIntent(
          new Intent().setComponent(component).setPackage(service.packageName), component, binder);
    }
  }

  /** Binds [component] for the app: creates the service if needed and returns its binder. */
  private IBinder bindForApp(ComponentName component) {
    Intent intent = new Intent().setComponent(component);
    boundByApp.add(new Intent.FilterComparison(intent));
    Running running = runningOrCreate(component, intent);
    if (running.binder == null) running.binder = running.controller.get().onBind(intent);
    running.bindings++;
    protocol.event("service", describe(component, running, "bound"));
    return running.binder;
  }

  /**
   * The binder the app's {@code onServiceConnected} receives: the real service's, created on first
   * use, which a device would have created before connecting.
   */
  private static final class LazyBinder implements IBinder {
    private final ComponentName component;
    private IBinder real;

    LazyBinder(ComponentName component) {
      this.component = component;
    }

    private IBinder real() {
      Navigator navigator = current;
      if (real == null && navigator != null) real = navigator.bindForApp(component);
      return real;
    }

    @Override
    public android.os.IInterface queryLocalInterface(String descriptor) {
      IBinder binder = real();
      return binder == null ? null : binder.queryLocalInterface(descriptor);
    }

    @Override
    public String getInterfaceDescriptor() throws android.os.RemoteException {
      IBinder binder = real();
      return binder == null ? null : binder.getInterfaceDescriptor();
    }

    @Override
    public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
        throws android.os.RemoteException {
      IBinder binder = real();
      return binder != null && binder.transact(code, data, reply, flags);
    }

    @Override
    public boolean pingBinder() {
      return true;
    }

    @Override
    public boolean isBinderAlive() {
      return true;
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) {}

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
      return true;
    }

    @Override
    public void dump(java.io.FileDescriptor fd, String[] args) {}

    @Override
    public void dumpAsync(java.io.FileDescriptor fd, String[] args) {}
  }

  JSONObject bindService(Intent intent) {
    ComponentName component = resolveService(intent);
    Running running = runningOrCreate(component, intent);
    IBinder binder;
    try {
      binder = running.controller.get().onBind(intent);
    } catch (RuntimeException e) {
      if (!running.started && running.bindings == 0) {
        services.remove(component);
        running.controller.destroy();
      }
      throw e;
    }
    running.bindings++;
    JSONObject result = describe(component, running, "bound");
    Protocol.Json json = new Protocol.Json();
    try {
      json.put("binder", binder == null ? null : binder.getClass().getName());
      json.put("interface", binder == null ? null : binder.getInterfaceDescriptor());
    } catch (android.os.RemoteException e) {
      json.put("interface", null);
    }
    try {
      result.put("binding", json.object());
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
    protocol.event("service", result);
    return result;
  }

  JSONObject unbindService(Intent intent) {
    ComponentName component = resolveService(intent);
    Running running = services.get(component);
    if (running == null || running.bindings == 0) {
      return new Protocol.Json().put("bound", false).object();
    }
    running.bindings--;
    if (running.bindings == 0) {
      running.controller.get().onUnbind(intent);
      if (!running.started) {
        services.remove(component);
        running.controller.destroy();
        protocol.event("service", describe(component, running, "destroyed"));
        return describe(component, running, "destroyed");
      }
    }
    return describe(component, running, "unbound");
  }

  JSONArray describeServices() {
    JSONArray array = new JSONArray();
    for (Map.Entry<ComponentName, Running> e : services.entrySet()) {
      array.put(describe(e.getKey(), e.getValue(), "running"));
    }
    return array;
  }

  private Running runningOrCreate(ComponentName component, Intent intent) {
    Running running = services.get(component);
    if (running != null) return running;
    Class<? extends Service> type = loadClass(component.getClassName(), Service.class);
    running =
        new Running(Robolectric.buildService(type, new Intent(intent).setComponent(component)));
    running.controller.create();
    services.put(component, running);
    return running;
  }

  private void reapStoppedServices() {
    for (Iterator<Map.Entry<ComponentName, Running>> it = services.entrySet().iterator();
        it.hasNext(); ) {
      Map.Entry<ComponentName, Running> e = it.next();
      ShadowService shadow = Shadow.extract(e.getValue().controller.get());
      if (shadow.isStoppedBySelf()) {
        it.remove();
        e.getValue().controller.destroy();
        protocol.event("service", describe(e.getKey(), e.getValue(), "destroyed"));
      }
    }
  }

  private ComponentName resolveService(Intent intent) {
    if (intent.getComponent() != null) return intent.getComponent();
    ResolveInfo info = app.getPackageManager().resolveService(intent, 0);
    if (info == null || info.serviceInfo == null) {
      throw new IllegalArgumentException("no service handles " + intent);
    }
    ServiceInfo service = info.serviceInfo;
    return new ComponentName(service.packageName, service.name);
  }

  private JSONObject describe(ComponentName component, Running running, String state) {
    ShadowService shadow = Shadow.extract(running.controller.get());
    Notification foreground = shadow.getLastForegroundNotification();
    return new Protocol.Json()
        .put("state", state)
        .put("service", component.getClassName())
        .put("started", running.started)
        .put("bindings", running.bindings)
        .put(
            "foreground",
            foreground == null || shadow.isForegroundStopped()
                ? null
                : Notifications.describe(foreground, shadow.getLastForegroundNotificationId()))
        .object();
  }

  // ---------------------------------------------------------------------------------------------
  // Broadcasts

  /**
   * Sends [intent] to every receiver that would get it on a device: receivers the app registered
   * at run time, through Robolectric's own delivery, and receivers declared in the manifest, which
   * Robolectric does not register when the manifest comes from an APK. Each manifest receiver gets
   * a fresh instance, registered for this one delivery with a filter matching exactly this intent,
   * so it goes through the same delivery path (and {@code goAsync}) as a dynamic one.
   */
  JSONObject broadcast(Intent intent) {
    Set<String> dynamic = new HashSet<>();
    JSONArray delivered = new JSONArray();
    for (ShadowApplication.Wrapper wrapper : shadowApp.getRegisteredReceivers()) {
      dynamic.add(wrapper.getBroadcastReceiver().getClass().getName());
      if (wrapper.getIntentFilter().match(app.getContentResolver(), intent, false, "simulator")
          >= 0) {
        delivered.put(
            new Protocol.Json()
                .put("receiver", wrapper.getBroadcastReceiver().getClass().getName())
                .put("declared", "runtime")
                .object());
      }
    }
    List<BroadcastReceiver> oneShot = new ArrayList<>();
    List<ResolveInfo> manifest = app.getPackageManager().queryBroadcastReceivers(intent, 0);
    for (ResolveInfo info : manifest) {
      ActivityInfo receiver = info.activityInfo;
      if (receiver == null || dynamic.contains(receiver.name)) continue;
      BroadcastReceiver instance =
          newInstance(loadClass(receiver.name, BroadcastReceiver.class));
      if (intent.getAction() == null) {
        // An explicit broadcast with no action cannot be matched by a filter; call it directly.
        instance.onReceive(app, new Intent(intent));
      } else {
        register(instance, intent);
        oneShot.add(instance);
      }
      delivered.put(
          new Protocol.Json().put("receiver", receiver.name).put("declared", "manifest").object());
    }
    app.sendBroadcast(intent);
    // Robolectric collects the matching receivers when the broadcast is sent and delivers on the
    // main looper, so the one-shot registrations can go now.
    for (BroadcastReceiver instance : oneShot) app.unregisterReceiver(instance);
    return new Protocol.Json().put("delivered", delivered).object();
  }

  private void register(BroadcastReceiver receiver, Intent intent) {
    if (Build.VERSION.SDK_INT >= 33) {
      app.registerReceiver(receiver, Intents.filterMatching(intent), Context.RECEIVER_EXPORTED);
    } else {
      app.registerReceiver(receiver, Intents.filterMatching(intent));
    }
  }

  // ---------------------------------------------------------------------------------------------

  private <T> Class<? extends T> loadClass(String name, Class<T> base) {
    try {
      return Class.forName(name, true, app.getClassLoader()).asSubclass(base);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("class not found: " + name, e);
    }
  }

  private static <T> T newInstance(Class<? extends T> type) {
    try {
      return type.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("cannot instantiate " + type.getName(), e);
    }
  }
}
