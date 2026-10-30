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
  private final NotificationStream notifications;
  /** The Wear system UI ({@code --launcher}), or null. */
  private final WearSystemUi wear;
  /** On, ambient or off, and the gestures between them. */
  private final ScreenPower screen;
  private final WidgetHost widgets;
  /** The home screen, with {@code --launcher}; null without. */
  private final HomeScreen home;
  private final Radios radios;
  private final AgentOverlay overlay = new AgentOverlay();
  private final TimeTick timeTick = new TimeTick();
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
    this.notifications = new NotificationStream(app, navigator, protocol);
    this.wear = WearSystemUi.create(app, navigator, protocol, notifications);
    this.screen = new ScreenPower(app, navigator, protocol, wear);
    this.widgets = new WidgetHost(app, navigator, protocol);
    // --launcher is the Wear system UI on a watch, the phone home screen everywhere else.
    this.home =
        HomeScreen.enabled() && wear == null
            ? new HomeScreen(app, navigator, protocol, widgets)
            : null;
    // Only when paired: DataLayer needs the app's Play services classes to load at all.
    if (DataLayerLink.enabled()) DataLayer.attach(protocol);
    CompanionDevices.attach(protocol);
    this.radios = new Radios(app, protocol, navigator);
  }

  void start() {
    displays.start();
    screen.start();
    if (wear != null) wear.start();
    if (home != null) home.start();
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
    if (home != null) home.cycle();
    displays.cycle();
    radios.cycle();
    overlay.round(FormFactor.isRound());
    logcat.poll();
    notifications.cycle();
    if (wear != null) wear.cycle();
    // A clock that redraws on TIME_TICK (Wear Compose's TimeText) moves with the wall clock.
    if (timeTick.due(System.currentTimeMillis())) {
      app.sendBroadcast(new Intent(Intent.ACTION_TIME_TICK));
    }
    screen.cycle();
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
      if (!name.equals("ping") && !name.equals("touch")) overlay.status(summary(name, command));
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
    if (DeviceDisplay.changesUi(name)
        || DeviceState.changesUi(name)
        || Displays.changesUi(name)
        || ScreenPower.changesUi(name)
        || HomeScreen.changesUi(name)
        || WearSystemUi.changesUi(name)
        || NotificationStream.changesUi(name)
        || Radios.changesUi(name)) {
      return true;
    }
    switch (name) {
      case "tap":
      case "longPress":
      case "swipe":
      case "rotary":
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
    if (emulated == null) emulated = screen.command(name, c);
    if (emulated == null && wear != null) emulated = wear.command(name, c);
    if (emulated == null) emulated = notifications.command(name, c);
    if (emulated == null) emulated = launcherCommand(name, c);
    if (emulated == null) emulated = radios.command(name, c);
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
      case "components":
        return components();
      case "debugSurface":
        return debugSurface(c);
      case "companion":
        return CompanionDevices.command(c);
      case "dataLayer":
        {
          if (DataLayerLink.enabled() && c.has("action")) return DataLayer.agentCurrent(c);
          if (DataLayerLink.enabled()) {
            // As if the app's android_wear_capabilities declared (or dropped) one: lets an agent
            // stand in for a capability an app forgot, and see what its peer does with it.
            if (c.has("addCapability")) {
              DataLayer.get().setLocalCapability(c.getString("addCapability"), true);
            }
            if (c.has("removeCapability")) {
              DataLayer.get().setLocalCapability(c.getString("removeCapability"), false);
            }
            if (c.has("oauthResponse")) {
              return DataLayer.get()
                  .answerAuth(c.getString("oauthResponse"), c.optInt("errorCode", -1));
            }
          }
          JSONObject state = DataLayerLink.enabled() ? DataLayer.describeCurrent() : null;
          if (state == null) {
            throw new IllegalStateException(
                "no data layer: launch with --pair <other.apk> or --data-layer agent");
          }
          return state;
        }
      case "playServices":
        if (c.has("state")) PlayServicesPackages.change(app, c.getString("state"));
        return PlayServicesPackages.describe();
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
      case "longPress":
      case "swipe":
      case "rotary":
        {
          // A sleeping screen wakes on the first touch or turn, which goes no further.
          JSONObject woke = onDefaultDisplay(c) ? screen.input(name) : null;
          if (woke != null) return woke;
          if (name.equals("tap")) return tap(c, 0);
          if (name.equals("longPress")) return tap(c, c.optLong("durationMs", 800));
          if (name.equals("swipe")) return swipe(c);
          return rotary(c);
        }
      case "touch":
        return touch(c);
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
          // From the home screen's icon: a running app's task comes forward.
          if (home != null && !c.has("display")) return home.launch();
          Intent launcher = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
          if (launcher == null) throw new IllegalStateException("the app has no launcher activity");
          return navigator.startActivity(launcher, -1, launchOptions(c));
        }
      case "startActivity":
        {
          Intent intent = intent(c);
          if (home != null && intent.hasCategory(Intent.CATEGORY_HOME)) return home.goHome();
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

  private boolean onDefaultDisplay(JSONObject c) {
    return displays.target(c) == android.view.Display.DEFAULT_DISPLAY;
  }

  /**
   * Android Studio's Wear surface launches ({@code am broadcast -a …DEBUG_SURFACE}, translated by
   * {@link AdbShell#debugSurface}): a tile or Wear Widget goes into the watch's carousel and shows,
   * as the Wear OS system UI does; on a phone with the home screen, {@code add-widget} places an app
   * widget. The reply's {@code index} is the carousel page, which {@code show-tile} takes back.
   */
  private JSONObject debugSurface(JSONObject c) throws Exception {
    String operation = c.getString("operation");
    switch (operation) {
      case "add-tile":
      case "add-widget":
        if (wear != null) {
          return run(
              "tiles",
              new Protocol.Json().put("op", "add").put("component", c.getString("component")).object());
        }
        if (home != null && operation.equals("add-widget")) {
          return run(
              "widget",
              new Protocol.Json()
                  .put("action", "add")
                  .put("provider", c.getString("component"))
                  .object());
        }
        throw new IllegalStateException(
            "DEBUG_SURFACE " + operation + " needs the system UI: launch with --launcher");
      case "show-tile":
        if (wear == null) throw new IllegalStateException("show-tile needs the Wear system UI");
        return run(
            "tiles", new Protocol.Json().put("op", "show").put("index", c.optInt("index", 0)).object());
      case "remove-tile":
      case "remove-widget":
        if (wear == null) throw new IllegalStateException(operation + " needs the Wear system UI");
        return run(
            "tiles",
            new Protocol.Json().put("op", "remove").put("component", c.getString("component")).object());
      default:
        throw new IllegalArgumentException(
            "DEBUG_SURFACE "
                + operation
                + " is not simulated: the system UI has tiles and widgets, but no watch face or"
                + " complication host");
    }
  }

  /** The home screen's and widgets' commands; refused without {@code --launcher} where needed. */
  private JSONObject launcherCommand(String name, JSONObject c) throws JSONException {
    if (home != null) return home.command(name, c);
    if (name.equals("widgets")) return widgets.command(name, c);
    if (HomeScreen.needsLauncher(name)) {
      throw new IllegalStateException(name + " needs the home screen: launch with --launcher");
    }
    return null;
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
    json.put("formFactor", FormFactor.current().id());
    json.put("qualifiers", RuntimeEnvironment.getQualifiers());
    json.put("round", FormFactor.isRound());
    json.put("width", metrics.widthPixels);
    json.put("height", metrics.heightPixels);
    json.put("density", metrics.density);
    json.put("display", display.describe());
    json.put("displays", displays.list());
    Navigator.Entry top = navigator.top();
    json.put("activity", top == null ? null : top.activity().getClass().getName());
    json.put("screen", screen.state());
    if (wear != null) json.put("surface", wear.surface());
    if (home != null) json.put("surface", home.surface());
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
    if (displayId == android.view.Display.DEFAULT_DISPLAY && screen.dark()) {
      // Off, or dozing with nothing that draws in ambient: the panel shows black.
      Bitmap black =
          Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
      black.eraseColor(android.graphics.Color.BLACK);
      bitmap = black;
    }
    if (displayId == android.view.Display.DEFAULT_DISPLAY
        && FormFactor.isRound()
        && !c.optBoolean("square", false)) {
      bitmap = FormFactor.cropRound(bitmap);
    }
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

  /** The down time of the pointer {@code touch} holds down, or -1. */
  private long touchDown = -1;

  /**
   * One pointer event — {@code down}, {@code move} or {@code up} — as a touchscreen streams them:
   * for a client that relays a person's touches as they happen (the emulator gRPC endpoint of
   * {@code launch --adb}) rather than a whole gesture at once. Replies at once, without settling.
   */
  private JSONObject touch(JSONObject c) throws JSONException {
    int displayId = displays.target(c);
    float x = (float) c.getDouble("x");
    float y = (float) c.getDouble("y");
    String action = c.getString("action");
    long now = SystemClock.uptimeMillis();
    int motion;
    switch (action) {
      case "down":
        if (touchDown >= 0) inject(touch(touchDown, now, MotionEvent.ACTION_CANCEL, x, y), displayId);
        JSONObject woke = onDefaultDisplay(c) ? screen.input("tap") : null;
        if (woke != null) return woke;
        touchDown = now;
        motion = MotionEvent.ACTION_DOWN;
        break;
      case "move":
        if (touchDown < 0) return new Protocol.Json().put("ignored", "no pointer down").object();
        motion = MotionEvent.ACTION_MOVE;
        break;
      case "up":
        if (touchDown < 0) return new Protocol.Json().put("ignored", "no pointer down").object();
        motion = MotionEvent.ACTION_UP;
        break;
      default:
        throw new IllegalArgumentException("touch action is down, move or up, not " + action);
    }
    inject(touch(touchDown, now, motion, x, y), displayId);
    if (motion == MotionEvent.ACTION_UP) touchDown = -1;
    return new Protocol.Json().put("x", x).put("y", y).put("action", action).object();
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

  /**
   * Turns a Wear watch's crown (or a rotating bezel): {@code count} {@code ACTION_SCROLL} events
   * from the rotary encoder, {@code intervalMs} apart, each carrying {@code delta} on {@code
   * AXIS_SCROLL}. As on a device they go to the top window's focused view, where Compose's {@code
   * rotaryScrollable} (Wear's {@code ScalingLazyColumn}, {@code TransformingLazyColumn}) scrolls by
   * {@code -delta} times the scaled vertical scroll factor: a positive delta scrolls up.
   */
  private JSONObject rotary(JSONObject c) throws JSONException {
    float delta = (float) c.optDouble("delta", -1);
    int count = Math.max(1, c.optInt("count", 1));
    long interval = Math.max(1, c.optLong("intervalMs", 20));
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    float x = metrics.widthPixels / 2f;
    float y = metrics.heightPixels / 2f;
    for (int i = 0; i < count; i++) {
      Runnable turn =
          () -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
            properties[0].id = 0;
            properties[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
            coords[0].x = x;
            coords[0].y = y;
            coords[0].setAxisValue(MotionEvent.AXIS_SCROLL, delta);
            MotionEvent event =
                MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_ROTARY_ENCODER, 0);
            dispatchToFocus(event);
            event.recycle();
          };
      if (i == 0) turn.run();
      else later(interval * i, turn);
    }
    float factor = android.view.ViewConfiguration.get(app).getScaledVerticalScrollFactor();
    return new Protocol.Json()
        .put("delta", delta)
        .put("count", count)
        .put("scrollPx", -delta * factor * count)
        .object();
  }

  /**
   * Delivers a rotary event as the system does: to the top window's view hierarchy, which routes it
   * to the focused view. Robolectric's {@code UiAutomation} would send it as a touch.
   */
  private boolean dispatchToFocus(MotionEvent event) {
    Navigator.Entry top = navigator.top();
    if (top == null || top.activity().getWindow() == null) return false;
    return top.activity().getWindow().getDecorView().dispatchGenericMotionEvent(event);
  }

  private JSONObject key(int code, boolean longPress, int displayId) throws JSONException {
    if (displayId == android.view.Display.DEFAULT_DISPLAY) {
      // POWER, SLEEP and WAKEUP are the system's; any other key wakes a sleeping screen first.
      JSONObject power = screen.key(code);
      if (power != null) return power.put("keyCode", code);
    }
    if (code == KeyEvent.KEYCODE_HOME
        && home != null
        && displayId == android.view.Display.DEFAULT_DISPLAY) {
      // The system's, as the window manager takes HOME before any app sees it.
      home.goHome();
      return new Protocol.Json().put("keyCode", code).put("surface", home.surface()).object();
    }
    if (BackRoute.isSystemBack(code, longPress)) {
      // The system's too: an opted-in window hears it as its OnBackInvokedCallback, not a key,
      // held or not (a long press is one Back, delivered on the release).
      JSONObject routed = SystemBack.dispatch(navigator, displayId);
      if (routed != null) return routed.put("keyCode", code);
    }
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
    Protocol.Json reply = new Protocol.Json().put("keyCode", code);
    if (code == KeyEvent.KEYCODE_BACK) reply.put("via", "key");
    return reply.object();
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
