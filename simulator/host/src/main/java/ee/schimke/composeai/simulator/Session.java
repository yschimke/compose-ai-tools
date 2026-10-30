package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
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
  private final DeviceDisplay display;
  private final DeviceState device;
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
    this.device = new DeviceState(app, navigator);
  }

  void start() {
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
    Connectivity.notifyCallbacks(RuntimeEnvironment.getApplication());
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
      if (name.equals("quit")) {
        // The reply goes out first and is flushed (the protocol stream autoflushes per line).
        protocol.ok(id, new Protocol.Json().put("quitting", true).object());
        System.exit(0);
        return;
      }
      JSONObject result = run(name, command);
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
    if (DeviceDisplay.changesUi(name) || DeviceState.changesUi(name)) return true;
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
        return true;
      default:
        return false;
    }
  }

  private JSONObject run(String name, JSONObject c) throws Exception {
    JSONObject emulated = display.command(name, c);
    if (emulated == null) emulated = device.command(name, c);
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
          List<Hierarchy.Node> windows = snapshot();
          if (c.optBoolean("flat", false)) {
            return new Protocol.Json().put("nodes", Hierarchy.flatten(windows)).object();
          }
          JSONArray array = new JSONArray();
          for (Hierarchy.Node window : windows) array.put(window.toJson(true));
          return new Protocol.Json().put("windows", array).object();
        }
      case "find":
        {
          JSONArray array = new JSONArray();
          for (Hierarchy.Node node : Hierarchy.find(snapshot(), selector(c))) {
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
        return key(keyCode(c.get("key")));
      case "back":
        return key(KeyEvent.KEYCODE_BACK);
      case "text":
        return text(c.getString("text"));
      case "finish":
        return navigator.finishTop();
      case "screenshot":
        return screenshot(c);
      case "launch":
        {
          Intent launcher = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
          if (launcher == null) throw new IllegalStateException("the app has no launcher activity");
          return navigator.startActivity(launcher, -1);
        }
      case "startActivity":
        return navigator.startActivity(intent(c), -1);
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
        return query(c);
      case "permissions":
        return navigator.permissions.command(c);
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

  private List<Hierarchy.Node> snapshot() {
    // Windows of activities below the top of the back stack are stopped, and not on screen.
    Set<Object> hidden = new HashSet<>();
    Navigator.Entry top = navigator.top();
    for (Navigator.Entry entry : navigator.backStack()) {
      if (entry == top) continue;
      Activity activity = entry.activity();
      if (activity.getWindow() != null) {
        hidden.add(activity.getWindow().getDecorView().getApplicationWindowToken());
      }
    }
    hidden.remove(null);
    return new Hierarchy().snapshot(hidden);
  }

  private JSONObject screenshot(JSONObject c) throws IOException, JSONException {
    Bitmap bitmap = ui.takeScreenshot();
    if (bitmap == null) throw new IllegalStateException("nothing on screen to capture");
    Protocol.Json json =
        new Protocol.Json().put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
    if (c.has("path")) {
      File file = new File(c.getString("path")).getAbsoluteFile();
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

  private JSONObject query(JSONObject c) throws JSONException {
    Uri uri = Uri.parse(c.getString("uri"));
    String[] projection = strings(c.optJSONArray("projection"));
    String[] args = strings(c.optJSONArray("args"));
    int limit = c.optInt("limit", 50);
    JSONArray rows = new JSONArray();
    JSONArray columns = new JSONArray();
    try (Cursor cursor =
        app.getContentResolver()
            .query(uri, projection, c.optString("selection", null), args, c.optString("sort", null))) {
      if (cursor == null) throw new IllegalArgumentException("no provider answered " + uri);
      for (String column : cursor.getColumnNames()) columns.put(column);
      while (rows.length() < limit && cursor.moveToNext()) {
        JSONArray row = new JSONArray();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
          row.put(cursor.getType(i) == Cursor.FIELD_TYPE_BLOB ? "<blob>" : cursor.getString(i));
        }
        rows.put(row);
      }
      return new Protocol.Json()
          .put("columns", columns)
          .put("rows", rows)
          .put("count", cursor.getCount())
          .object();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Act

  private JSONObject tap(JSONObject c, long holdMs) throws JSONException {
    float x;
    float y;
    JSONObject target = null;
    if (c.has("x") && c.has("y")) {
      x = (float) c.getDouble("x");
      y = (float) c.getDouble("y");
    } else {
      List<Hierarchy.Node> matches = Hierarchy.find(snapshot(), selector(c));
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
    overlay.touch(x, y, holdMs > 0);
    long down = SystemClock.uptimeMillis();
    inject(touch(down, down, MotionEvent.ACTION_DOWN, x, y));
    float upX = x;
    float upY = y;
    Runnable up = () -> inject(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, upX, upY));
    if (holdMs > 0) later(holdMs, up);
    else up.run();
    return new Protocol.Json().put("x", x).put("y", y).put("node", target).object();
  }

  private JSONObject swipe(JSONObject c) throws JSONException {
    float x1 = (float) c.getDouble("x1");
    float y1 = (float) c.getDouble("y1");
    float x2 = (float) c.getDouble("x2");
    float y2 = (float) c.getDouble("y2");
    long duration = Math.max(1, c.optLong("durationMs", 300));
    int steps = Math.max(2, c.optInt("steps", 12));
    overlay.swipe(x1, y1, x2, y2);
    long down = SystemClock.uptimeMillis();
    inject(touch(down, down, MotionEvent.ACTION_DOWN, x1, y1));
    for (int i = 1; i < steps; i++) {
      float f = i / (float) steps;
      float x = x1 + (x2 - x1) * f;
      float y = y1 + (y2 - y1) * f;
      later(
          duration * i / steps,
          () -> inject(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)));
    }
    later(
        duration,
        () -> inject(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2)));
    return new Protocol.Json().put("durationMs", duration).put("steps", steps).object();
  }

  private JSONObject key(int code) {
    long now = SystemClock.uptimeMillis();
    inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
    inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    return new Protocol.Json().put("keyCode", code).object();
  }

  private JSONObject text(String text) {
    KeyEvent[] events =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
    if (events == null) throw new IllegalArgumentException("cannot type " + text);
    for (KeyEvent event : events) inject(event);
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

  private void inject(android.view.InputEvent event) {
    ui.injectInputEvent(event, true);
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

  /** What a command does, in a few words, for the person watching the window. */
  private static String summary(String name, JSONObject c) {
    String target = null;
    if (c.has("text")) target = "\"" + c.optString("text") + "\"";
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

  private static String[] strings(JSONArray array) throws JSONException {
    if (array == null) return null;
    String[] out = new String[array.length()];
    for (int i = 0; i < out.length; i++) out[i] = array.getString(i);
    return out;
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
