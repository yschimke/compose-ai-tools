package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.AppComponentFactory;
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
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBroadcastPendingResult;
import org.robolectric.shadows.ShadowService;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

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
    /** The display it was launched on ({@code ActivityOptions.setLaunchDisplayId}). */
    final int displayId;
    /** Paused and stopped because another activity went on top of it. */
    boolean stopped;

    /**
     * The simulator's own home screen ({@code --launcher}), not the app's: it stays at the bottom
     * of the default display, and force-stops and {@code CLEAR_TASK} leave it alone.
     */
    boolean system;

    Entry(
        ActivityController<? extends Activity> controller,
        Intent request,
        Entry caller,
        int requestCode,
        int displayId) {
      this.controller = controller;
      this.request = request;
      this.caller = caller;
      this.requestCode = requestCode;
      this.displayId = displayId;
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

  /** The app's binds, which Robolectric also logs as starts; see {@link #prepareBind}. */
  private static final Set<Intent.FilterComparison> boundByApp = ConcurrentHashMap.newKeySet();

  /** Intents for another package's services the simulator answers itself (WearCompanion). */
  private static final Set<Intent.FilterComparison> foreignBinds = ConcurrentHashMap.newKeySet();

  /** Registers [intent] as bound by the simulator, so the app's bind is not taken for a start. */
  static void registerForeignBind(Intent intent) {
    foreignBinds.add(new Intent.FilterComparison(new Intent(intent)));
  }

  /** A binder per manifest service, which creates and binds it on first use. */
  private static final Map<ComponentName, LazyBinder> bindable = new ConcurrentHashMap<>();

  /** The session's navigator, which {@link LazyBinder}s bound during startup resolve to. */
  private static volatile Navigator current;

  Navigator(Application app, Protocol protocol) {
    this.app = app;
    this.shadowApp = Shadow.extract(app);
    this.protocol = protocol;
    this.permissions = new Permissions(app);
    current = this;
  }

  /** The top activity on the default display, which display and device changes go to. */
  Entry top() {
    return top(Display.DEFAULT_DISPLAY);
  }

  /** The top activity on display [displayId], or null. */
  Entry top(int displayId) {
    for (Entry entry : stack) if (entry.displayId == displayId) return entry;
    return null;
  }

  /** The session's navigator, for the simulator's own system UI. */
  static Navigator current() {
    return current;
  }

  /** Whether display [displayId] has an activity of the app's own, rather than only the home. */
  boolean hasAppActivity(int displayId) {
    for (Entry entry : stack) if (entry.displayId == displayId && !entry.system) return true;
    return false;
  }

  /** The window tokens of activities stopped under another, which are not on screen. */
  Set<Object> hiddenWindowTokens() {
    Set<Object> hidden = new HashSet<>();
    for (Entry entry : stack) {
      if (!entry.stopped) continue;
      Activity activity = entry.activity();
      if (activity.getWindow() != null) {
        Object token = activity.getWindow().getDecorView().getApplicationWindowToken();
        if (token != null) hidden.add(token);
      }
    }
    return hidden;
  }

  /** The activity classes on display [displayId], top first. */
  JSONArray activitiesOn(int displayId) {
    JSONArray array = new JSONArray();
    for (Entry entry : stack) {
      if (entry.displayId == displayId) array.put(entry.activity().getClass().getName());
    }
    return array;
  }

  /**
   * Display [displayId] changed size or density: each activity on it gets the new configuration,
   * through {@code onConfigurationChanged} when its manifest {@code configChanges} covers the
   * change, and is otherwise re-created on the same display with its saved state. (Robolectric's
   * own re-creation would attach the new instance to the default display.)
   */
  void displayChanged(int displayId) {
    List<Entry> on = new ArrayList<>();
    for (Entry entry : stack) if (entry.displayId == displayId) on.add(entry);
    for (Entry entry : on) {
      DisplayMetrics metrics = new DisplayMetrics();
      Configuration config = Displays.configurationOf(displayId, metrics);
      Activity activity = entry.activity();
      int changes = activity.getResources().getConfiguration().diff(config);
      if (changes == 0) continue;
      int handled = 0;
      try {
        handled =
            app.getPackageManager().getActivityInfo(activity.getComponentName(), 0).configChanges;
      } catch (PackageManager.NameNotFoundException e) {
        // Not in the manifest: handles nothing.
      }
      if (entry.system || (handled & changes) == changes) {
        entry.controller.configurationChange(config, metrics);
      } else {
        relaunch(entry);
      }
    }
  }

  /** Destroys [entry]'s activity and creates it again in its place, from its saved state. */
  private void relaunch(Entry entry) {
    Bundle state = new Bundle();
    boolean wasStopped = entry.stopped;
    if (!wasStopped) entry.controller.pause();
    entry.controller.saveInstanceState(state);
    if (!wasStopped) entry.controller.stop();
    entry.controller.destroy();
    Activity old = entry.activity();
    Bundle options =
        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(entry.displayId).toBundle();
    Intent launch = old.getIntent();
    ActivityController<? extends Activity> controller =
        ActivityController.of(
            instantiate(
                () ->
                    factory()
                        .instantiateActivity(
                            app.getClassLoader(), old.getClass().getName(), launch)),
            launch,
            options);
    Displays.placeOn(controller.get(), entry.displayId);
    controller.setup(state);
    Entry fresh =
        new Entry(controller, entry.request, entry.caller, entry.requestCode, entry.displayId);
    List<Entry> entries = new ArrayList<>(stack);
    entries.set(entries.indexOf(entry), fresh);
    stack.clear();
    stack.addAll(entries);
    if (wasStopped) {
      controller.pause();
      controller.saveInstanceState(new Bundle()).stop();
      fresh.stopped = true;
    }
    protocol.event("activity", describe(fresh, "recreated"));
  }

  /** Finishes every activity on display [displayId], which is going away. */
  JSONArray finishOn(int displayId) {
    JSONArray finished = activitiesOn(displayId);
    for (Entry entry : stack) if (entry.displayId == displayId) entry.activity().finish();
    reapFinishedActivities();
    return finished;
  }

  List<Entry> backStack() {
    return new ArrayList<>(stack);
  }

  /** Drains what the app started or stopped since the last cycle, and reaps finished components. */
  void pump() {
    Intent intent;
    while ((intent = shadowApp.getNextStartedActivity()) != null) {
      ShadowActivity.IntentForResult forResult = takeForResult(intent);
      if (ACTION_REQUEST_PERMISSIONS.equals(intent.getAction())) {
        answerPermissions(intent, forResult == null ? -1 : forResult.requestCode);
        continue;
      }
      Entry requester = stack.peek();
      if (systemUi != null
          && systemUi.handle(
              intent,
              forResult == null ? -1 : forResult.requestCode,
              requester == null ? null : requester.activity())) {
        continue;
      }
      try {
        startActivity(
            intent,
            forResult == null ? -1 : forResult.requestCode,
            forResult == null ? null : forResult.options);
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
      if (boundByApp.remove(new Intent.FilterComparison(intent))) continue;
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
    return startActivity(intent, requestCode, null);
  }

  /**
   * As {@link #startActivity(Intent, int)}, with the caller's {@code ActivityOptions}: a launch
   * display there puts the activity on that display, above that display's own top activity and
   * leaving the others' alone, as a device's per-display task stacks do.
   */
  JSONObject startActivity(Intent intent, int requestCode, Bundle options) {
    int displayId = launchDisplay(options);
    ComponentName component = resolveActivity(intent);
    // An app activity started while the home screen shows goes into the app's task, which comes
    // forward over the home screen, as a launcher's NEW_TASK start does on a device. One the home
    // screen starts for a result (a widget's configure activity) goes over it, and returns to it.
    if (requestCode < 0) leaveHome(displayId);
    ComponentName implementation = targetOfAlias(component);
    Class<? extends Activity> type = loadClass(implementation.getClassName(), Activity.class);
    Intent launch = new Intent(intent).setComponent(component);
    int flags = intent.getFlags();

    if ((flags & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0) {
      for (Entry entry : new ArrayList<>(stack)) {
        if (entry.displayId != displayId || entry.system) continue;
        stack.remove(entry);
        destroy(entry);
      }
    } else if ((flags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0 && contains(type, displayId)) {
      while (top(displayId).activity().getClass() != type) {
        Entry entry = top(displayId);
        stack.remove(entry);
        destroy(entry);
      }
      if ((flags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) {
        Entry target = top(displayId);
        deliverNewIntent(target, launch);
        protocol.event("activity", describe(target, "newIntent"));
        return describe(target, "newIntent");
      }
      Entry cleared = top(displayId);
      stack.remove(cleared);
      destroy(cleared);
    }
    Entry below = top(displayId);
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
    ActivityController<? extends Activity> controller =
        ActivityController.of(
            instantiate(
                () ->
                    factory()
                        .instantiateActivity(
                            app.getClassLoader(), implementation.getClassName(), launch)),
            launch,
            options);
    Displays.placeOn(controller.get(), displayId);
    controller.setup();
    if (coverBelow) {
      below.controller.saveInstanceState(new Bundle()).stop();
      below.stopped = true;
    }
    // The caller is the activity that started this one, which on another display is not [below].
    Entry caller = below != null || stack.isEmpty() ? below : stack.peek();
    Entry entry = new Entry(controller, intent, caller, requestCode, displayId);
    stack.push(entry);
    protocol.event("activity", describe(entry, "resumed"));
    return describe(entry, "resumed");
  }

  // ---------------------------------------------------------------------------------------------
  // The home screen

  /**
   * Puts [activity], the simulator's home screen, at the bottom of the default display's stack and
   * creates it, resumed if nothing of the app's is there yet. It is not the app's, so it is built
   * directly rather than through the app's component factory.
   */
  Entry startHome(Activity activity, Intent intent) {
    ActivityController<? extends Activity> controller = ActivityController.of(activity, intent);
    controller.setup();
    Entry entry = new Entry(controller, intent, null, -1, Display.DEFAULT_DISPLAY);
    entry.system = true;
    stack.addLast(entry);
    if (stack.peekFirst() != entry) {
      controller.pause();
      controller.saveInstanceState(new Bundle()).stop();
      entry.stopped = true;
    }
    protocol.event("activity", describe(entry, entry.stopped ? "stopped" : "resumed"));
    return entry;
  }

  /** The home screen's entry, or null without {@code --launcher}. */
  Entry home() {
    for (Entry entry : stack) if (entry.system) return entry;
    return null;
  }

  /**
   * {@code HOME}: the app's top activity on the default display is paused and stopped, as the app
   * goes to the background, and the home screen comes forward. Nothing is destroyed.
   */
  JSONObject goHome() {
    Entry home = home();
    if (home == null) throw new IllegalStateException("no home screen: launch with --launcher");
    Entry top = top(Display.DEFAULT_DISPLAY);
    if (top == home) return describe(home, "resumed");
    if (top != null && !top.stopped) {
      top.controller.pause();
      top.controller.saveInstanceState(new Bundle()).stop();
      top.stopped = true;
      protocol.event("activity", describe(top, "stopped"));
    }
    stack.remove(home);
    stack.push(home);
    bringBack(home);
    protocol.event("activity", describe(home, "resumed"));
    return describe(home, "resumed");
  }

  /**
   * Brings the app's task on the default display forward over the home screen, as tapping the
   * icon of a running app does: its top activity restarts and resumes ({@code onRestart}, {@code
   * onStart}, {@code onResume}). Returns null when the app has no activity there to bring back.
   */
  JSONObject bringAppForward() {
    if (!hasAppActivity(Display.DEFAULT_DISPLAY)) return null;
    leaveHome(Display.DEFAULT_DISPLAY);
    Entry top = top(Display.DEFAULT_DISPLAY);
    if (top.stopped) bringBack(top);
    protocol.event("activity", describe(top, "resumed"));
    return describe(top, "resumed");
  }

  /**
   * The screen went to sleep under the app: its top activity on the default display is paused and
   * stopped where it is, as a phone's or a watch's without a watch face does. Returns that entry, or
   * null when nothing was resumed there.
   */
  Entry screenOff() {
    Entry top = top(Display.DEFAULT_DISPLAY);
    if (top == null || top.stopped) return null;
    top.controller.pause();
    top.controller.saveInstanceState(new Bundle()).stop();
    top.stopped = true;
    protocol.event("activity", describe(top, "stopped"));
    return top;
  }

  /** The screen woke: [entry], stopped by {@link #screenOff} and still on top, comes back. */
  void screenOn(Entry entry) {
    if (entry == null || !entry.stopped || !stack.contains(entry)) return;
    if (top(entry.displayId) != entry) return;
    bringBack(entry);
    protocol.event("activity", describe(entry, "resumed"));
  }

  /** The home screen on [displayId], if on top, is stopped and moved under the app's task. */
  private void leaveHome(int displayId) {
    Entry top = top(displayId);
    if (top == null || !top.system) return;
    if (!top.stopped) {
      top.controller.pause();
      top.controller.saveInstanceState(new Bundle()).stop();
      top.stopped = true;
    }
    stack.remove(top);
    stack.addLast(top);
  }

  /**
   * What {@code am force-stop} can do in one process: destroys every activity, top first, and
   * every service. The application object, its singletons and threads stay; there is no second
   * process to start in their place.
   */
  JSONObject forceStop() {
    JSONArray activities = new JSONArray();
    for (Entry entry : new ArrayList<>(stack)) {
      if (entry.system) continue;
      stack.remove(entry);
      destroy(entry);
      activities.put(entry.activity().getClass().getName());
      protocol.event("activity", describe(entry, "destroyed"));
    }
    Entry home = home();
    if (home != null && home.stopped && activities.length() > 0) {
      bringBack(home);
      protocol.event("activity", describe(home, "resumed"));
    }
    JSONArray stopped = new JSONArray();
    for (Map.Entry<ComponentName, Running> e : new ArrayList<>(services.entrySet())) {
      destroyService(e.getKey(), e.getValue());
      stopped.put(e.getKey().getClassName());
      protocol.event("service", describe(e.getKey(), e.getValue(), "destroyed"));
    }
    if (activities.length() > 0) {
      protocol.event("activity", new Protocol.Json().put("state", "empty").object());
    }
    return new Protocol.Json().put("activities", activities).put("services", stopped).object();
  }

  /**
   * Finishes the top activity on display [displayId] the way {@code Activity.finish()} would,
   * without a back press.
   */
  JSONObject finishTop(int displayId) {
    Entry top = top(displayId);
    if (top == null) {
      throw new IllegalStateException("no activity is running on display " + displayId);
    }
    if (top.system) {
      throw new IllegalStateException("the home screen is showing; it does not finish");
    }
    top.activity().finish();
    reapFinishedActivities();
    Entry now = top(displayId);
    return new Protocol.Json()
        .put("finished", top.activity().getClass().getName())
        .put("top", now == null ? null : now.activity().getClass().getName())
        .object();
  }

  private void reapFinishedActivities() {
    Map<Integer, Entry> topsBefore = new LinkedHashMap<>();
    for (Entry entry : stack) topsBefore.putIfAbsent(entry.displayId, entry);
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
    for (Map.Entry<Integer, Entry> before : topsBefore.entrySet()) {
      if (!finishing.contains(before.getValue())) continue;
      Entry resumed = top(before.getKey());
      if (resumed != null) {
        if (resumed.stopped) bringBack(resumed);
        protocol.event("activity", describe(resumed, "resumed"));
        if (resumed.system && !hasAppActivity(before.getKey())) {
          protocol.event("activity", new Protocol.Json().put("state", "empty").object());
        }
      } else if (stack.isEmpty()) {
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

  private boolean contains(Class<?> type, int displayId) {
    for (Entry entry : stack) {
      if (entry.displayId == displayId && entry.activity().getClass() == type) return true;
    }
    return false;
  }

  /** The display [options] launch on, checked to exist; the default display without one. */
  private int launchDisplay(Bundle options) {
    if (options == null) return Display.DEFAULT_DISPLAY;
    // ActivityOptions.KEY_LAUNCH_DISPLAY_ID; fromBundle is hidden.
    int id = options.getInt("android.activity.launchDisplayId", Display.INVALID_DISPLAY);
    if (id == Display.INVALID_DISPLAY) return Display.DEFAULT_DISPLAY;
    if (app.getSystemService(DisplayManager.class).getDisplay(id) == null) {
      throw new IllegalArgumentException("no display " + id + " to launch on");
    }
    return id;
  }

  /** How permission requests are answered; the {@code permissions} command sets it. */
  final Permissions permissions;

  /**
   * Answers a runtime permission request as the system's dialog would, by {@link Permissions}
   * (granting unless told otherwise): the answers are applied, and the result reaches the
   * requesting activity the way the dialog delivers it, so {@code onRequestPermissionsResult} and
   * androidx's {@code ActivityResultRegistry} both see it. Robolectric has no permission dialog;
   * the request would otherwise never complete.
   */
  private void answerPermissions(Intent request, int requestCode) {
    String[] names = request.getStringArrayExtra(EXTRA_REQUEST_PERMISSIONS_NAMES);
    if (names == null) names = new String[0];
    int[] results = permissions.answer(names);
    JSONArray granted = new JSONArray();
    JSONArray denied = new JSONArray();
    for (int i = 0; i < names.length; i++) {
      (results[i] == PackageManager.PERMISSION_GRANTED ? granted : denied).put(names[i]);
    }
    protocol.event(
        "permissions",
        new Protocol.Json()
            .put("granted", granted)
            .put("denied", denied)
            .put("requestCode", requestCode)
            .object());
    Activity requester = requesterOf(names, requestCode);
    if (requester == null || requestCode < 0) return;
    Intent data =
        new Intent(ACTION_REQUEST_PERMISSIONS)
            .putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, names)
            .putExtra(EXTRA_REQUEST_PERMISSIONS_RESULTS, results);
    dispatchResult(requester, REQUEST_PERMISSIONS_WHO_PREFIX, requestCode, Activity.RESULT_OK, data);
  }

  /**
   * Hands [requester] the result of an activity the system ran for it (a dialog it showed), as
   * {@code onActivityResult} and androidx's {@code ActivityResultRegistry} expect it.
   */
  void deliverResult(Activity requester, int requestCode, int resultCode, Intent data) {
    dispatchResult(requester, null, requestCode, resultCode, data);
  }

  private static void dispatchResult(
      Activity requester, String who, int requestCode, int resultCode, Intent data) {
    // Hidden: (who, requestCode, resultCode, data), plus a trailing `reason` since API 28.
    for (java.lang.reflect.Method method : Activity.class.getDeclaredMethods()) {
      if (!method.getName().equals("dispatchActivityResult")) continue;
      Object[] args = {who, requestCode, resultCode, data, null};
      try {
        method.setAccessible(true);
        method.invoke(requester, java.util.Arrays.copyOf(args, method.getParameterCount()));
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot deliver the activity result", e);
      }
      return;
    }
  }

  /** Answers intents only the system handles (its dialogs and settings screens); see Radios. */
  interface SystemUi {
    boolean handle(Intent intent, int requestCode, Activity requester);
  }

  SystemUi systemUi;

  /**
   * The activity that asked for [names] with [requestCode]: Robolectric keeps each activity's last
   * request. Not simply the top one, which may be an activity the requester started in the same
   * cycle; the top one only when no activity's last request matches.
   */
  private Activity requesterOf(String[] names, int requestCode) {
    for (Entry entry : stack) {
      if (entry.system) continue;
      ShadowActivity shadow = Shadow.extract(entry.activity());
      ShadowActivity.PermissionsRequest last = shadow.getLastRequestedPermission();
      if (last != null
          && last.requestCode == requestCode
          && java.util.Arrays.equals(last.requestedPermissions, names)) {
        return entry.activity();
      }
    }
    Entry top = stack.peek();
    return top == null ? null : top.activity();
  }

  /** {@code PackageManager.ACTION_REQUEST_PERMISSIONS}, hidden, as are the extras below. */
  private static final String ACTION_REQUEST_PERMISSIONS =
      "android.content.pm.action.REQUEST_PERMISSIONS";

  private static final String EXTRA_REQUEST_PERMISSIONS_NAMES =
      "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";

  /** {@code PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS}, hidden. */
  private static final String EXTRA_REQUEST_PERMISSIONS_RESULTS =
      "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";

  /** {@code Activity.REQUEST_PERMISSIONS_WHO_PREFIX}, hidden. */
  private static final String REQUEST_PERMISSIONS_WHO_PREFIX = "@android:requestPermissions:";

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
    Protocol.Json json =
        new Protocol.Json()
            .put("state", state)
            .put("activity", activity.getClass().getName())
            .put("intent", Intents.toJson(activity.getIntent()))
            .put("title", activity.getTitle() == null ? null : activity.getTitle().toString())
            .put("display", entry.displayId);
    if (entry.system) json.put("system", true);
    return json.object();
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
      destroyService(component, running);
      protocol.event("service", describe(component, running, "destroyed"));
      return describe(component, running, "destroyed");
    }
    return describe(component, running, "bound");
  }

  /**
   * Makes the app's own {@code bindService} connect. Robolectric answers a bind with whatever
   * binder was registered for that exact intent, else null, and Firebase Sessions among others
   * fails on the null. Every service the manifest declares gets a {@link LazyBinder}, which
   * creates and binds the real service when the app first uses it; {@link #prepareBind} registers
   * it for each bind's own intent.
   *
   * <p>Runs before the application's {@code onCreate()}, since apps bind there (Firebase Sessions
   * does), before the session and its navigator exist; a binder resolves the navigator when the
   * app first calls it.
   */
  static void registerBindableServices(Application app) {
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
      bindable.put(component, new LazyBinder(component));
    }
  }

  /**
   * Registers the binder of the app service [intent] names (or resolves to) for exactly that
   * intent, from {@link SimulatorShadowContextImpl} as the app binds; a device resolves an explicit
   * bind by its component alone. Also remembers the bind, which Robolectric logs as a start.
   */
  static void prepareBind(Intent intent) {
    Application app = RuntimeEnvironment.getApplication();
    if (app == null) return;
    // Another package's service the simulator stands in for (WearCompanion): Robolectric answers
    // the bind, and logs it as a start, which it is not.
    if (foreignBinds.contains(new Intent.FilterComparison(intent))) {
      boundByApp.add(new Intent.FilterComparison(new Intent(intent)));
      return;
    }
    ComponentName component = intent.getComponent();
    if (component == null) {
      ResolveInfo resolved = app.getPackageManager().resolveService(intent, 0);
      if (resolved == null || resolved.serviceInfo == null) return;
      component =
          new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name);
    }
    LazyBinder binder = bindable.get(component);
    if (binder == null) return;
    Intent key = new Intent(intent);
    ShadowApplication shadowApp = Shadow.extract(app);
    shadowApp.setComponentNameAndServiceForBindServiceForIntent(key, component, binder);
    boundByApp.add(new Intent.FilterComparison(key));
  }

  /**
   * Destroys a running service and drops its entry; a {@link LazyBinder} the app still holds for
   * it then binds a fresh instance rather than handing out the destroyed one's binder.
   */
  private void destroyService(ComponentName component, Running running) {
    services.remove(component);
    running.controller.destroy();
    LazyBinder binder = bindable.get(component);
    if (binder != null) binder.forget();
  }

  /** Binds [component] for the app: creates the service if needed and returns its binder. */
  private IBinder bindForApp(ComponentName component) {
    Intent intent = new Intent().setComponent(component);
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

    /** The service was destroyed: the next call binds a new instance. */
    void forget() {
      real = null;
    }

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
        destroyService(component, running);
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

  /**
   * Binds the service [intent] names for the simulator's own system UI, as a command's bind does,
   * and returns what its {@code onBind} returned: a launcher showing a widget's collection talks to
   * the {@code RemoteViewsService}'s factory in-process through it.
   */
  IBinder bindForHost(Intent intent) {
    ComponentName component = resolveService(intent);
    Running running = runningOrCreate(component, intent);
    IBinder binder = running.controller.get().onBind(intent);
    running.bindings++;
    protocol.event("service", describe(component, running, "bound"));
    return binder;
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
        destroyService(component, running);
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

  /**
   * The running instance of manifest service [component], created as a bind would create it, for
   * {@link DataLayer} to deliver a Wearable event to; null before the session's navigator exists.
   * Main thread only.
   */
  static Service serviceFor(ComponentName component) {
    Navigator navigator = current;
    if (navigator == null) return null;
    boolean created = !navigator.services.containsKey(component);
    Running running =
        navigator.runningOrCreate(component, new Intent().setComponent(component));
    if (created) {
      navigator.protocol.event("service", navigator.describe(component, running, "created"));
    }
    return running.controller.get();
  }

  private Running runningOrCreate(ComponentName component, Intent intent) {
    Running running = services.get(component);
    if (running != null) return running;
    Intent start = new Intent(intent).setComponent(component);
    Service service =
        instantiate(
            () -> factory().instantiateService(app.getClassLoader(), component.getClassName(), start));
    running = new Running(ServiceController.of(service, start));
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
        LazyBinder binder = bindable.get(e.getKey());
        if (binder != null) binder.forget();
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
          instantiate(
              () -> factory().instantiateReceiver(app.getClassLoader(), receiver.name, intent));
      if (intent.getAction() == null) {
        // An explicit broadcast with no action cannot be matched by a filter; call it directly,
        // with the pending result a delivery carries, so a receiver that calls goAsync() (Glance's
        // action receiver does) gets one rather than null.
        BroadcastReceiver.PendingResult result =
            ReflectionHelpers.callStaticMethod(
                ShadowBroadcastPendingResult.class,
                "create",
                ClassParameter.from(int.class, 0),
                ClassParameter.from(String.class, null),
                ClassParameter.from(Bundle.class, null),
                ClassParameter.from(boolean.class, false));
        // Hidden: BroadcastReceiver.setPendingResult.
        ReflectionHelpers.callInstanceMethod(
            instance,
            "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult.class, result));
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

  private AppComponentFactory factory() {
    return AppComponents.factory(app);
  }

  private interface Instantiation<T> {
    T create() throws ReflectiveOperationException;
  }

  private static <T> T instantiate(Instantiation<T> instantiation) {
    try {
      return instantiation.create();
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("cannot instantiate: " + e, e);
    }
  }
}
