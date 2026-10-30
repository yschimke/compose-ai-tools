package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;

/**
 * The control channel: reads one JSON command per line from stdin on a background thread, and
 * runs each on the main thread from {@link SimulatorControl#onCycle}. Input that spans time (a long
 * press, a swipe) is spread over loop turns, so the app's handlers run between its events, as on a
 * device. Commands that change the UI answer only after that and {@code settleMs} more of simulated
 * time (default {@value #DEFAULT_SETTLE_MS} ms), so a {@code tap} followed by a {@code screenshot}
 * sees the result; commands run strictly in order.
 * Closing stdin ends the process, so a client that dies takes its simulator with it.
 *
 * <p>The command set is documented in {@code docs/SIMULATOR.md}.
 */
final class Session {

  static final long DEFAULT_SETTLE_MS = 250;

  private final Application app;
  private final UiAutomation ui;
  private final Protocol protocol;
  private final Navigator navigator;
  private final Device device;
  private final Logcat logcat;
  private final AppFiles files;
  private final DeviceDisplay display;
  private final DeviceState state;
  private final Displays displays;
  private final AgentOverlay overlay = new AgentOverlay();
  private final Queue<String> inbox = new ConcurrentLinkedQueue<>();
  /** Work due at a later uptime, in order; while any is pending no further command starts. */
  private final PriorityQueue<Deferred> deferred = new PriorityQueue<>();
  private long deferredSequence;
  private boolean ready;

  private static final class Deferred implements Comparable<Deferred> {
    final long at;
    final long sequence;
    final Runnable action;

    Deferred(long at, long sequence, Runnable action) {
      this.at = at;
      this.sequence = sequence;
      this.action = action;
    }

    @Override
    public int compareTo(Deferred other) {
      int byTime = Long.compare(at, other.at);
      return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
    }
  }

  Session(Application app, UiAutomation ui, Protocol protocol) {
    this.app = app;
    this.ui = ui;
    this.protocol = protocol;
    this.navigator = new Navigator(app, protocol);
    this.display = new DeviceDisplay(app, navigator);
    this.device = new Device(app, navigator, display);
    this.logcat = new Logcat(protocol);
    this.files = new AppFiles(app);
    this.state = new DeviceState(app, navigator);
    this.displays = new Displays(app, navigator, protocol);
  }

  void start() {
    displays.start();
    // Created before Application.onCreate(); reported now that the channel is open.
    JSONArray providers = Providers.installed();
    if (providers.length() > 0) {
      protocol.event("providers", new Protocol.Json().put("providers", providers).object());
    }
    if (!protocol.enabled()) return;
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  if (!line.isBlank()) inbox.add(line);
                }
              } catch (IOException e) {
                // Treated as end of input.
              }
              // The client went away: so does the simulator.
              System.exit(0);
            },
            "simulator-control");
    reader.setDaemon(true);
    reader.start();
  }

  /** One simulator cycle, on the main thread. */
  void cycle() {
    navigator.pump();
    displays.cycle();
    Connectivity.notifyCallbacks(RuntimeEnvironment.getApplication());
    logcat.poll();
    if (!ready && navigator.top() != null) {
      ready = true;
      protocol.event("ready", info());
    }
    long now = SystemClock.uptimeMillis();
    while (!deferred.isEmpty() && deferred.peek().at <= now) {
      Deferred next = deferred.poll();
      try {
        next.action.run();
      } catch (RuntimeException e) {
        protocol.event("error", new Protocol.Json().put("message", describe(e)).object());
      }
    }
    String line;
    while (deferred.isEmpty() && (line = inbox.poll()) != null) handle(line);
  }

  /** Runs [action] on the main thread once [delayMs] of simulated time has passed. */
  private void later(long delayMs, Runnable action) {
    deferred.add(new Deferred(SystemClock.uptimeMillis() + delayMs, deferredSequence++, action));
  }

  /** The uptime the last deferred action is due at, or now. */
  private long busyUntil() {
    long until = SystemClock.uptimeMillis();
    for (Deferred d : deferred) until = Math.max(until, d.at);
    return until;
  }

  private void handle(String line) {
    Object id = null;
    try {
      JSONObject command = new JSONObject(line);
      id = command.opt("id");
      String name = command.getString("cmd");
      if (!name.equals("ping")) overlay.status(summary(name, command));
      JSONObject shell = null;
      if (name.equals("shell")) {
        // An adb shell line: run the command it translates to, and say what that was.
        shell = shellCommand(command);
        if (command.has("settleMs")) shell.put("settleMs", command.get("settleMs"));
        command = shell;
        name = command.getString("cmd");
        if (name.equals("shell")) throw new IllegalArgumentException("shell inside shell");
      }
      if (name.equals("quit")) {
        // The reply goes out first and is flushed (the protocol stream autoflushes per line).
        protocol.ok(id, new Protocol.Json().put("quitting", true).object());
        System.exit(0);
        return;
      }
      JSONObject ran = run(name, command);
      JSONObject result =
          shell == null
              ? ran
              : new Protocol.Json().put("command", shell).put("result", ran).object();
      long settle =
          name.equals("wait")
              ? command.optLong("settleMs", command.optLong("ms", 0))
              : command.optLong("settleMs", changesUi(name) ? DEFAULT_SETTLE_MS : 0);
      long replyIn = busyUntil() - SystemClock.uptimeMillis() + settle;
      if (replyIn > 0) {
        Object replyId = id;
        later(replyIn, () -> protocol.ok(replyId, result));
      } else {
        protocol.ok(id, result);
      }
    } catch (Throwable t) {
      protocol.error(id, describe(t));
    }
  }

  private static boolean changesUi(String name) {
    if (DeviceDisplay.changesUi(name) || DeviceState.changesUi(name) || Displays.changesUi(name)) {
      return true;
    }
    switch (name) {
      case "tap":
      case "longPress":
      case "swipe":
      case "key":
      case "text":
      case "back":
      case "finish":
      case "launch":
      case "startActivity":
      case "startService":
      case "stopService":
      case "bindService":
      case "unbindService":
      case "broadcast":
      case "qualifiers":
      case "forceStop":
      case "clearData":
      case "display":
      case "settings":
      case "network":
      case "content":
        return true;
      default:
        return false;
    }
  }

  private JSONObject run(String name, JSONObject c) throws Exception {
    JSONObject emulated = display.command(name, c);
    if (emulated == null) emulated = state.command(name, c);
    if (emulated == null) emulated = displays.command(name, c);
    if (emulated != null) return emulated;
    switch (name) {
      case "ping":
        return new Protocol.Json().put("uptimeMs", SystemClock.uptimeMillis()).object();
      case "info":
        return info();
      case "wait":
        // The settle delay is the whole command.
        return new Protocol.Json().put("waitedMs", c.optLong("settleMs", c.optLong("ms"))).object();
      case "activities":
        return new Protocol.Json().put("activities", navigator.describeStack()).object();
      case "services":
        return new Protocol.Json().put("services", navigator.describeServices()).object();
      case "notifications":
        return new Protocol.Json().put("notifications", Notifications.all(app)).object();
      case "components":
        return components();
      case "hierarchy":
        {
          List<Hierarchy.Node> windows = snapshot(displays.target(c));
          if (c.optBoolean("flat", false)) {
            JSONObject flat = new Protocol.Json().put("nodes", Hierarchy.flatten(windows)).object();
            if (c.has("path")) {
              File file = outputFile(c);
              file.getAbsoluteFile().getParentFile().mkdirs();
              java.nio.file.Files.writeString(file.toPath(), flat.toString(2));
              flat.put("path", file.getPath());
            }
            return flat;
          }
          JSONArray array = new JSONArray();
          for (Hierarchy.Node window : windows) array.put(window.toJson(true));
          return new Protocol.Json().put("windows", array).object();
        }
      case "find":
        {
          JSONArray array = new JSONArray();
          for (Hierarchy.Node node : Hierarchy.find(snapshot(displays.target(c)), selector(c))) {
            array.put(node.toJson(false));
          }
          return new Protocol.Json().put("nodes", array).object();
        }
      case "tap":
        return tap(c, 0);
      case "longPress":
        return tap(c, c.optLong("durationMs", 800));
      case "swipe":
        return swipe(c);
      case "key":
        {
          Object keys = c.get("key");
          boolean longPress = c.optBoolean("longPress", false);
          int target = displays.target(c);
          if (!(keys instanceof JSONArray)) return key(keyCode(keys), longPress, target);
          JSONArray codes = new JSONArray();
          for (int i = 0; i < ((JSONArray) keys).length(); i++) {
            codes.put(key(keyCode(((JSONArray) keys).get(i)), longPress, target).get("keyCode"));
          }
          return new Protocol.Json().put("keyCodes", codes).object();
        }
      case "back":
        return key(KeyEvent.KEYCODE_BACK, false, displays.target(c));
      case "text":
        return text(c.getString("text"), displays.target(c));
      case "finish":
        return navigator.finishTop(displays.target(c));
      case "screenshot":
        return screenshot(c);
      case "launch":
        {
          checkPackage(c);
          Intent launcher = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
          if (launcher == null) throw new IllegalStateException("the app has no launcher activity");
          return navigator.startActivity(launcher, -1, launchOptions(c));
        }
      case "startActivity":
        {
          Intent intent = intent(c);
          if (c.optBoolean("forceStop", false)) navigator.forceStop();
          return navigator.startActivity(intent, -1, launchOptions(c));
        }
      case "startService":
        return navigator.startService(intent(c));
      case "stopService":
        return navigator.stopService(intent(c));
      case "bindService":
        return navigator.bindService(intent(c));
      case "unbindService":
        return navigator.unbindService(intent(c));
      case "broadcast":
        return navigator.broadcast(intent(c));
      case "query":
        return device.content(c);
      case "content":
        return device.content(c);
      case "permissions":
        checkPackage(c);
        return navigator.permissions.command(c);
      case "settings":
        return device.settings(c);
      case "display":
        return device.display(c);
      case "getprop":
        return device.getprop(c);
      case "package":
        checkPackage(c);
        return device.packageInfo();
      case "packages":
        return device.packages(c);
      case "network":
        {
          Boolean wifi = c.has("wifi") ? c.getBoolean("wifi") : null;
          Boolean airplane = c.has("airplane") ? c.getBoolean("airplane") : null;
          JSONObject state = Connectivity.set(app, wifi, airplane);
          if (airplane != null) {
            navigator.broadcast(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", airplane));
          }
          return state;
        }
      case "logcat":
        return logcat.command(c);
      case "files":
        checkPackage(c);
        return files.command(c);
      case "forceStop":
        {
          checkPackage(c);
          JSONObject stopped = navigator.forceStop();
          app.getSystemService(NotificationManager.class).cancelAll();
          return stopped;
        }
      case "clearData":
        {
          checkPackage(c);
          JSONObject stopped = navigator.forceStop();
          app.getSystemService(NotificationManager.class).cancelAll();
          navigator.permissions.reset();
          int deleted = files.clear();
          return new Protocol.Json()
              .put("stopped", stopped)
              .put("deletedFiles", deleted)
              .put(
                  "note",
                  "the process lives on: in-memory state (open databases, cached preferences,"
                      + " singletons) survives until the simulator is relaunched")
              .object();
        }
      case "qualifiers":
        return qualifiers(c.getString("value"));
      default:
        throw new IllegalArgumentException("unknown command: " + name);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Read

  JSONObject info() {
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    Protocol.Json json = new Protocol.Json();
    json.put("package", app.getPackageName());
    try {
      PackageInfo pkg = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
      json.put("versionName", pkg.versionName);
    } catch (PackageManager.NameNotFoundException e) {
      json.put("versionName", null);
    }
    json.put("application", app.getClass().getName());
    json.put("sdk", Build.VERSION.SDK_INT);
    json.put("qualifiers", RuntimeEnvironment.getQualifiers());
    json.put("width", metrics.widthPixels);
    json.put("height", metrics.heightPixels);
    json.put("density", metrics.density);
    json.put("display", display.describe());
    json.put("displays", displays.list());
    Navigator.Entry top = navigator.top();
    json.put("activity", top == null ? null : top.activity().getClass().getName());
    json.put("backStack", navigator.backStack().size());
    json.put("services", navigator.describeServices().length());
    return json.object();
  }

  private JSONObject components() throws PackageManager.NameNotFoundException {
    int flags =
        PackageManager.GET_ACTIVITIES
            | PackageManager.GET_SERVICES
            | PackageManager.GET_RECEIVERS
            | PackageManager.GET_PROVIDERS;
    PackageInfo pkg = app.getPackageManager().getPackageInfo(app.getPackageName(), flags);
    Intent launcher =
        app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
    return new Protocol.Json()
        .put(
            "launcher",
            launcher == null || launcher.getComponent() == null
                ? null
                : launcher.getComponent().getClassName())
        .put("activities", names(pkg.activities))
        .put("services", names(pkg.services))
        .put("receivers", names(pkg.receivers))
        .put("providers", names(pkg.providers))
        .object();
  }

  private static JSONArray names(ComponentInfo[] components) {
    JSONArray array = new JSONArray();
    if (components == null) return array;
    for (ComponentInfo component : components) {
      array.put(
          new Protocol.Json()
              .put("name", component.name)
              .put("exported", component.exported)
              .put("enabled", component.enabled)
              .object());
    }
    return array;
  }

  /** The windows on display [displayId]; those of stopped activities are not on screen. */
  private List<Hierarchy.Node> snapshot(int displayId) {
    return new Hierarchy().snapshot(navigator.hiddenWindowTokens(), displayId);
  }

  /**
   * A PNG of one display ({@code display}, the default display without), or with {@code
   * "display":"all"} one per display: {@code path} for the default display and {@code
   * <path>-display<id>.png} beside it for each other one.
   */
  private JSONObject screenshot(JSONObject c) throws Exception {
    if ("all".equals(c.optString("display"))) {
      JSONArray shots = new JSONArray();
      JSONArray all = displays.list();
      for (int i = 0; i < all.length(); i++) {
        int id = all.getJSONObject(i).getInt("id");
        JSONObject one = new JSONObject(c.toString()).put("display", id);
        if (c.has("path") && id != android.view.Display.DEFAULT_DISPLAY) {
          one.put("path", c.getString("path").replaceFirst("(\\.png)?$", "-display" + id + ".png"));
        }
        shots.put(screenshot(one).put("display", id));
      }
      return new Protocol.Json().put("displays", shots).object();
    }
    int displayId = displays.target(c);
    Bitmap bitmap =
        displayId == android.view.Display.DEFAULT_DISPLAY
            ? ui.takeScreenshot()
            : Displays.capture(displayId);
    if (bitmap == null) throw new IllegalStateException("nothing on screen to capture");
    Protocol.Json json =
        new Protocol.Json().put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
    if (c.has("path")) {
      File file = outputFile(c);
      File parent = file.getParentFile();
      if (parent != null) parent.mkdirs();
      try (OutputStream out = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      json.put("path", file.getPath());
    } else {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      json.put("png", Base64.getEncoder().encodeToString(out.toByteArray()));
    }
    return json.object();
  }

  // ---------------------------------------------------------------------------------------------
  // Act

  private JSONObject tap(JSONObject c, long holdMs) throws JSONException {
    int displayId = displays.target(c);
    float x;
    float y;
    JSONObject target = null;
    if (c.has("x") && c.has("y")) {
      x = (float) c.getDouble("x");
      y = (float) c.getDouble("y");
    } else {
      List<Hierarchy.Node> matches = Hierarchy.find(snapshot(displayId), selector(c));
      int index = c.optInt("index", 0);
      if (matches.size() <= index) {
        throw new IllegalArgumentException(
            "no node matches " + selector(c) + " (found " + matches.size() + ")");
      }
      Hierarchy.Node node = matches.get(index);
      x = node.bounds.exactCenterX();
      y = node.bounds.exactCenterY();
      target = node.toJson(false);
    }
    if (displayId == android.view.Display.DEFAULT_DISPLAY) overlay.touch(x, y, holdMs > 0);
    long down = SystemClock.uptimeMillis();
    inject(touch(down, down, MotionEvent.ACTION_DOWN, x, y), displayId);
    float upX = x;
    float upY = y;
    Runnable up =
        () ->
            inject(
                touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, upX, upY),
                displayId);
    if (holdMs > 0) later(holdMs, up);
    else up.run();
    return new Protocol.Json()
        .put("x", x)
        .put("y", y)
        .put("display", displayId)
        .put("node", target)
        .object();
  }

  private JSONObject swipe(JSONObject c) throws JSONException {
    float x1 = (float) c.getDouble("x1");
    float y1 = (float) c.getDouble("y1");
    float x2 = (float) c.getDouble("x2");
    float y2 = (float) c.getDouble("y2");
    long duration = Math.max(1, c.optLong("durationMs", 300));
    int steps = Math.max(2, c.optInt("steps", 12));
    // A drag holds still first, long enough to be a long press.
    long hold = Math.max(0, c.optLong("holdMs", 0));
    int displayId = displays.target(c);
    if (displayId == android.view.Display.DEFAULT_DISPLAY) overlay.swipe(x1, y1, x2, y2);
    long down = SystemClock.uptimeMillis();
    inject(touch(down, down, MotionEvent.ACTION_DOWN, x1, y1), displayId);
    for (int i = 1; i < steps; i++) {
      float f = i / (float) steps;
      float x = x1 + (x2 - x1) * f;
      float y = y1 + (y2 - y1) * f;
      later(
          hold + duration * i / steps,
          () ->
              inject(
                  touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y),
                  displayId));
    }
    later(
        hold + duration,
        () ->
            inject(
                touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2), displayId));
    return new Protocol.Json().put("durationMs", duration).put("steps", steps).object();
  }

  private JSONObject key(int code, boolean longPress, int displayId) {
    long now = SystemClock.uptimeMillis();
    inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), displayId);
    if (longPress) {
      // As a held key repeats: a second down flagged as a long press, then the up.
      long held = ViewConfiguration.getLongPressTimeout();
      later(
          held,
          () ->
              inject(
                  new KeyEvent(
                      now,
                      SystemClock.uptimeMillis(),
                      KeyEvent.ACTION_DOWN,
                      code,
                      1,
                      0,
                      KeyCharacterMap.VIRTUAL_KEYBOARD,
                      0,
                      KeyEvent.FLAG_LONG_PRESS),
                  displayId));
      later(
          held,
          () ->
              inject(
                  new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0),
                  displayId));
      return new Protocol.Json().put("keyCode", code).put("longPress", true).object();
    }
    inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), displayId);
    return new Protocol.Json().put("keyCode", code).object();
  }

  private JSONObject text(String text, int displayId) {
    KeyEvent[] events =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
    if (events == null) throw new IllegalArgumentException("cannot type " + text);
    for (KeyEvent event : events) inject(event, displayId);
    return new Protocol.Json().put("typed", text.length()).object();
  }

  private JSONObject qualifiers(String value) {
    display.qualifiers(value);
    return info();
  }

  private static MotionEvent touch(long down, long time, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    return event;
  }

  /** Into the windows on display [displayId] alone, as a device's input dispatcher does. */
  private void inject(android.view.InputEvent event, int displayId) {
    Displays.inject(event, displayId);
  }

  /** {@code ActivityOptions} launching on the command's {@code display}, or none. */
  private android.os.Bundle launchOptions(JSONObject c) {
    if (!c.has("display")) return null;
    return android.app.ActivityOptions.makeBasic()
        .setLaunchDisplayId(displays.target(c))
        .toBundle();
  }

  private static int keyCode(Object key) {
    if (key instanceof Number) return ((Number) key).intValue();
    String name = String.valueOf(key).toUpperCase(Locale.ROOT);
    if (!name.startsWith("KEYCODE_")) name = "KEYCODE_" + name;
    try {
      return KeyEvent.class.getField(name).getInt(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("unknown key " + key);
    }
  }

  // ---------------------------------------------------------------------------------------------

  private Intent intent(JSONObject c) throws JSONException {
    JSONObject spec = c.optJSONObject("intent");
    return Intents.fromJson(spec != null ? spec : c, app);
  }

  /** A command translated from {@code command} (an adb shell line) or {@code args} (its argv). */
  private static JSONObject shellCommand(JSONObject c) throws JSONException {
    java.util.Map<String, Object> translated;
    JSONArray args = c.optJSONArray("args");
    if (args != null) {
      java.util.List<String> argv = new java.util.ArrayList<>();
      for (int i = 0; i < args.length(); i++) argv.add(args.getString(i));
      translated = AdbShell.translate(argv, null);
    } else {
      translated = AdbShell.translate(c.getString("command"));
    }
    return (JSONObject) toJson(translated);
  }

  private static Object toJson(Object value) {
    if (value instanceof java.util.Map) {
      JSONObject object = new JSONObject();
      for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) value).entrySet()) {
        try {
          object.put((String) e.getKey(), toJson(e.getValue()));
        } catch (JSONException ex) {
          throw new IllegalArgumentException(ex);
        }
      }
      return object;
    }
    if (value instanceof java.util.List) {
      JSONArray array = new JSONArray();
      for (Object item : (java.util.List<?>) value) array.put(toJson(item));
      return array;
    }
    return value == null ? JSONObject.NULL : value;
  }

  /** A file a command writes: a host path, or a device path when the shell translation says so. */
  private File outputFile(JSONObject c) throws JSONException {
    String path = c.getString("path");
    // `screencap -p s.png` or `/sdcard/s.png` writes on the "device", where a later pull by the
    // same name finds it; another absolute path, and a `> file` redirect, are host paths.
    if (c.optBoolean("devicePath", false) && files.isDevicePath(path)) return files.resolve(path);
    return new File(path).getAbsoluteFile();
  }

  /** Refuses a {@code package} other than the app's: it is the only one installed. */
  private void checkPackage(JSONObject c) {
    String pkg = c.optString("package", null);
    if (pkg != null && !pkg.equals(app.getPackageName())) {
      throw new IllegalArgumentException(
          pkg + " is not installed; the simulator runs only " + app.getPackageName());
    }
  }

  /** What a command does, in a few words, for the person watching the window. */
  private static String summary(String name, JSONObject c) {
    String target = null;
    if (name.equals("shell")) target = c.optString("command", null);
    else if (c.has("text")) target = "\"" + c.optString("text") + "\"";
    else if (c.has("textContains")) target = "\"" + c.optString("textContains") + "\"…";
    else if (c.has("desc")) target = "\"" + c.optString("desc") + "\"";
    else if (c.has("resourceId")) target = "#" + c.optString("resourceId");
    else if (c.has("x") && c.has("y")) target = c.optInt("x") + "," + c.optInt("y");
    else if (c.has("key")) target = c.optString("key");
    else if (c.has("value")) target = c.optString("value");
    else if (c.has("uri")) target = c.optString("uri");
    else {
      JSONObject intent = c.optJSONObject("intent");
      if (intent != null) {
        target = intent.optString("data", intent.optString("component", intent.optString("action")));
      }
    }
    return target == null || target.isEmpty() ? name : name + " " + target;
  }

  private static JSONObject selector(JSONObject c) throws JSONException {
    JSONObject nested = c.optJSONObject("selector");
    if (nested != null) return nested;
    JSONObject selector = new JSONObject();
    for (String key :
        new String[] {
          "text", "textContains", "desc", "descContains", "resourceId", "cls", "clickable"
        }) {
      if (c.has(key)) selector.put(key, c.get(key));
    }
    return selector;
  }

  private static String describe(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    String message = t.toString();
    if (root != t) message += " (caused by " + root + ")";
    StackTraceElement[] frames = root.getStackTrace();
    if (frames.length > 0) message += " at " + frames[0];
    return message;
  }
}
