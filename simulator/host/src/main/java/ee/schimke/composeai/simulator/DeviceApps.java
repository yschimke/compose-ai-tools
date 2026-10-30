package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.Display;
import java.awt.AWTEvent;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.simulator.SimulatorPanelRegistry;

/**
 * Which of the device's apps is on screen, when a device runs several ({@code --app phone:a.apk
 * --app phone:b.apk}). Each app runs in a simulator JVM of its own, as each runs in a process of its
 * own; the device's screen shows one of them. The app on screen starts the launch on screen, the
 * others in the background ({@link #BACKGROUND_PROPERTY}), with no activity until one is started.
 *
 * <p>An app comes to the screen when its icon on the home screen is tapped, when another app starts
 * one of its activities (an explicit intent, or one only it handles), or with the {@code foreground}
 * command. It tells the device's other apps over the {@link DeviceBus}, and the one that was on
 * screen goes to the background as an app does on {@code HOME}: its top activity is paused and
 * stopped, and its windows are hidden. The app coming forward shows its windows where the other's
 * were, and resumes its task, or starts its launcher activity, or the intent it was handed.
 *
 * <p>An activity of another app started for a result gets {@code RESULT_CANCELED} at once: the
 * result would come back from another process. The intent's extras reach the other app as strings.
 * Runs on the main thread.
 */
final class DeviceApps {

  /** Set for each app but the first of a device: it starts in the background. */
  static final String BACKGROUND_PROPERTY = "composeai.simulator.device.background";

  /** The bus event that moves the screen: {@code to}, and the {@code intent} and {@code window}. */
  private static final String EVENT = "foreground";

  /**
   * The bus event that names an app on the home screen: each app's label is a resource of its
   * own, which only its process reads, so each tells the others its own ({@code label}).
   */
  private static final String LABEL = "label";

  private static volatile DeviceApps current;

  /** The other apps' labels, by package, as they have told them. */
  private static final Map<String, String> labels = new ConcurrentHashMap<>();

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private HomeScreen home;
  private volatile boolean onScreen = !Boolean.getBoolean(BACKGROUND_PROPERTY);

  /** The windows hidden while in the background, shown again on coming forward. */
  private final Set<Window> hidden = new LinkedHashSet<>();

  DeviceApps(Application app, Navigator navigator, Protocol protocol) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    current = this;
    if (!DeviceBus.enabled()) return;
    navigator.addSystemUi(this::handle);
    DeviceBus.onBroadcast(EVENT, this::heard);
    DeviceBus.onBroadcast(LABEL, this::heardLabel);
    if (!onScreen && !GraphicsEnvironment.isHeadless()) {
      // The simulator's windows open as it starts; an app in the background keeps them hidden.
      Toolkit.getDefaultToolkit()
          .addAWTEventListener(
              event -> {
                if (event.getID() == WindowEvent.WINDOW_OPENED && !onScreen) {
                  Window window = ((WindowEvent) event).getWindow();
                  synchronized (hidden) {
                    hidden.add(window);
                  }
                  window.setVisible(false);
                }
              },
              AWTEvent.WINDOW_EVENT_MASK);
      SwingUtilities.invokeLater(this::hideWindows);
    }
  }

  static DeviceApps current() {
    return current;
  }

  void attach(HomeScreen home) {
    this.home = home;
  }

  /** Tells the device's other apps this one's label, once the bus is joined. */
  void announce() {
    if (!DeviceBus.enabled()) return;
    CharSequence label = app.getApplicationInfo().loadLabel(app.getPackageManager());
    DeviceBus.broadcast(LABEL, new Protocol.Json().put("label", String.valueOf(label)).object());
  }

  /** The label another app of the device told, or null. */
  static String labelOf(String packageName) {
    return labels.get(packageName);
  }

  private void heardLabel(String from, JSONObject body) {
    String label = body.optString("label", null);
    if (label == null || label.equals(labels.put(from, label))) return;
    // An app that joined after this one announced has not heard it yet.
    announce();
    if (home != null) home.relabel(from);
  }

  boolean onScreen() {
    return onScreen;
  }

  /** Whether [packageName] is another app of this device. */
  static boolean isOtherApp(String packageName) {
    return DeviceBus.enabled() && DevicePeers.isPeer(packageName);
  }

  /**
   * The device's screen goes to [packageName]: this app, or another of the device's, which then
   * starts [intent] (null: resumes its task, or starts its launcher activity).
   */
  JSONObject show(String packageName, Intent intent) throws JSONException {
    if (packageName.equals(app.getPackageName())) {
      JSONObject shown = comeForward(intent, null);
      DeviceBus.broadcast(EVENT, new Protocol.Json().put("to", packageName).object());
      return shown;
    }
    if (!isOtherApp(packageName)) {
      throw new IllegalArgumentException(
          "no app " + packageName + " on this device; it has " + DevicePeers.packages());
    }
    JSONObject window = windowBounds();
    goBackground();
    DeviceBus.broadcast(
        EVENT,
        new Protocol.Json()
            .put("to", packageName)
            .put("intent", intent == null ? null : Intents.toJson(intent))
            .put("window", window)
            .object());
    return new Protocol.Json().put("package", packageName).put("onScreen", true).object();
  }

  /** The {@code foreground} command: this app, or the {@code package} named, comes on screen. */
  JSONObject command(JSONObject c) throws JSONException {
    return show(c.optString("package", app.getPackageName()), null);
  }

  private void heard(String from, JSONObject body) throws JSONException {
    String to = body.optString("to");
    if (to.equals(app.getPackageName())) {
      JSONObject spec = body.optJSONObject("intent");
      comeForward(spec == null ? null : Intents.fromJson(spec, app), body.optJSONObject("window"));
    } else if (onScreen) {
      goBackground();
    }
  }

  /** The system-UI step for an activity of another of the device's apps: that app starts it. */
  private boolean handle(Intent intent, int requestCode, Activity requester) {
    if (Intent.ACTION_CHOOSER.equals(intent.getAction())) return false;
    try {
      if (startInOtherApp(intent) == null) return false;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    if (requester != null && requestCode >= 0 && navigator.isRunning(requester)) {
      navigator.deliverResult(requester, requestCode, Activity.RESULT_CANCELED, null);
    }
    return true;
  }

  /**
   * Starts [intent] in the other app of the device whose activity it names or only it handles,
   * which comes on screen; null, and nothing done, when the intent is this app's.
   */
  JSONObject startInOtherApp(Intent intent) throws JSONException {
    if (!DeviceBus.enabled()) return null;
    String target = otherAppFor(intent);
    return target == null ? null : show(target, intent);
  }

  /** The other app of the device [intent] starts an activity of, or null when it is this one's. */
  private String otherAppFor(Intent intent) {
    if (intent.getComponent() != null) {
      String pkg = intent.getComponent().getPackageName();
      return isOtherApp(pkg) ? pkg : null;
    }
    if (intent.getPackage() != null) {
      return isOtherApp(intent.getPackage()) ? intent.getPackage() : null;
    }
    String other = null;
    for (ResolveInfo info : app.getPackageManager().queryIntentActivities(intent, 0)) {
      String pkg = info.activityInfo.packageName;
      // One of the app's own handles it: it stays here, as the app's own handler would win a
      // chooser the user has set no default for.
      if (pkg.equals(app.getPackageName())) return null;
      if (other == null && isOtherApp(pkg)) other = pkg;
    }
    return other;
  }

  private JSONObject comeForward(Intent intent, JSONObject window) throws JSONException {
    boolean was = onScreen;
    onScreen = true;
    showWindows(window);
    JSONObject result;
    if (intent != null) {
      Intent start = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (start.getComponent() == null) start.setPackage(app.getPackageName());
      result = navigator.startActivity(start, -1);
    } else if (home != null) {
      result = home.launch();
    } else {
      result = navigator.bringAppForward();
      if (result == null) {
        Intent launcher = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launcher == null) throw new IllegalStateException("the app has no launcher activity");
        result = navigator.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), -1);
      }
    }
    if (!was) event();
    return new Protocol.Json()
        .put("package", app.getPackageName())
        .put("onScreen", true)
        .put("activity", result)
        .object();
  }

  private void goBackground() {
    if (!onScreen) return;
    onScreen = false;
    if (home != null) {
      Navigator.Entry top = navigator.top(Display.DEFAULT_DISPLAY);
      if (top != null && !top.system) home.goHome();
    } else {
      navigator.screenOff();
    }
    SwingUtilities.invokeLater(this::hideWindows);
    event();
  }

  private void event() {
    protocol.event(
        EVENT,
        new Protocol.Json().put("package", app.getPackageName()).put("onScreen", onScreen).object());
  }

  // ---------------------------------------------------------------------------------------------
  // Windows

  /** Where the main window is, for the app coming forward to open its own; null headless. */
  private static JSONObject windowBounds() throws JSONException {
    Window main = mainWindow();
    if (main == null) return null;
    return new Protocol.Json().put("x", main.getX()).put("y", main.getY()).object();
  }

  private static Window mainWindow() {
    if (GraphicsEnvironment.isHeadless()) return null;
    JPanel panel = SimulatorPanelRegistry.getActivePanel();
    return panel == null ? null : SwingUtilities.getWindowAncestor(panel);
  }

  private void hideWindows() {
    if (onScreen || GraphicsEnvironment.isHeadless()) return;
    for (Window window : Window.getWindows()) {
      if (!window.isShowing()) continue;
      synchronized (hidden) {
        hidden.add(window);
      }
      window.setVisible(false);
    }
  }

  private void showWindows(JSONObject at) {
    if (GraphicsEnvironment.isHeadless()) return;
    int x = at == null ? Integer.MIN_VALUE : at.optInt("x", Integer.MIN_VALUE);
    int y = at == null ? Integer.MIN_VALUE : at.optInt("y", Integer.MIN_VALUE);
    SwingUtilities.invokeLater(
        () -> {
          List<Window> windows;
          synchronized (hidden) {
            windows = new ArrayList<>(hidden);
            hidden.clear();
          }
          Window main = mainWindow();
          if (main != null && x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
            main.setLocation(x, y);
          }
          for (Window window : windows) if (window.isDisplayable()) window.setVisible(true);
          if (main != null) main.toFront();
        });
  }

  /** The launcher activity of another of the device's apps, for its home-screen icon. */
  static Intent launcherOf(ComponentName activity) {
    return new Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(activity)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
  }
}
