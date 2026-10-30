package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Locale;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.simulator.SimulatorPanelRegistry;

/**
 * The emulator-style display controls: resizing the display, device presets, rotation, fold
 * posture, font scale and raw qualifiers. Each is a configuration change as a device makes one —
 * the display and resources change, then the top activity either gets {@code
 * onConfigurationChanged} (when its manifest {@code configChanges} covers the change) or is
 * recreated — and the fold the app's {@code WindowInfoTracker} sees ({@link Foldables}) and the
 * Swing window follow.
 *
 * <p>Runs on the main thread, inside the sandbox.
 */
final class DeviceDisplay {

  private final Application app;
  private final Navigator navigator;
  private final Foldables folds = Foldables.INSTANCE;
  /** Surface.ROTATION_*: quarter turns from the natural orientation. */
  private int rotation;
  private String preset = System.getProperty(DisplayGeometry.DEVICE_PROPERTY);

  /** The launch's density, which a preset without its own returns to (after {@code desktop}). */
  private final String launchDensity;

  DeviceDisplay(Application app, Navigator navigator) {
    this.app = app;
    this.navigator = navigator;
    this.launchDensity =
        DisplayGeometry.densityQualifier(app.getResources().getDisplayMetrics().densityDpi);
  }

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "resize":
        {
          int width = c.getInt("widthDp");
          int height = c.getInt("heightDp");
          preset = null;
          resize(width, height, density(c));
          if (c.has("posture")) setPosture(c);
          return describe();
        }
      case "device":
        return device(c.getString("value"), density(c));
      case "rotate":
        return rotate(c.opt("value"));
      case "posture":
        setPosture(c);
        return describe();
      case "uimode":
        return uimode(c);
      case "fontScale":
        {
          RuntimeEnvironment.setFontScale((float) c.getDouble("value"));
          configurationChanged();
          return describe();
        }
      case "display":
        // `wm size` / `wm density` (size / density) are Device's; bare, this describes the display.
        if (c.has("size") || c.has("density")) return null;
        return describe();
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    switch (name) {
      case "resize":
      case "device":
      case "rotate":
      case "posture":
      case "fontScale":
      case "uimode":
        return true;
      default:
        return false;
    }
  }

  /** {@code cmd uimode night}'s mode as last set: {@code yes}, {@code no} or {@code auto}. */
  private String nightMode;

  /**
   * {@code cmd uimode night [yes|no|auto]}: sets night mode as a configuration change ({@code auto}
   * is day here: no clock or location decides), and answers as the device does, {@code Night mode:
   * yes}, which is what Android Studio's Device Settings reads its Dark Theme switch from.
   */
  private JSONObject uimode(JSONObject c) throws JSONException {
    if (c.has("night")) {
      String mode = c.getString("night");
      if (!mode.equals("yes") && !mode.equals("no") && !mode.equals("auto")) {
        throw new IllegalArgumentException("night mode is yes, no or auto, not " + mode);
      }
      nightMode = mode;
      boolean night = mode.equals("yes");
      if (night != night(app.getResources().getConfiguration())) {
        qualifiers(night ? "+night" : "+notnight");
      }
    }
    String mode =
        nightMode != null ? nightMode : night(app.getResources().getConfiguration()) ? "yes" : "no";
    return new Protocol.Json()
        .put("night", mode)
        .put("output", "Night mode: " + mode)
        .object();
  }

  /** The display's rotation in quarter turns, as {@code Surface.ROTATION_*}. */
  int rotation() {
    return rotation;
  }

  /** Applies resource qualifiers (Robolectric syntax; a leading {@code +} merges) as a change. */
  JSONObject qualifiers(String value) {
    RuntimeEnvironment.setQualifiers(value);
    configurationChanged();
    return describe();
  }

  private JSONObject device(String name, String density) {
    DisplayGeometry.Preset p = DisplayGeometry.preset(name);
    // Presets are given in their natural orientation; a device starts upright.
    rotation = 0;
    folds.set(p.posture, Foldables.NONE.equals(p.posture) ? null : "vertical", null);
    String d = density != null ? density : p.density != null ? p.density : launchDensity;
    resize(p.widthDp, p.heightDp, d);
    preset = p.name;
    return describe();
  }

  /**
   * Turns the display to [value]: {@code 0}/{@code 90}/{@code 180}/{@code 270} degrees, {@code
   * portrait} / {@code landscape}, or a quarter turn clockwise when absent. The width and height
   * swap when the turn crosses between portrait and landscape, and the fold turns with them.
   */
  private JSONObject rotate(Object value) {
    int target;
    if (value == null || JSONObject.NULL.equals(value)) {
      target = (rotation + 1) % 4;
    } else if (value instanceof Number) {
      target = Math.floorMod(((Number) value).intValue() / 90, 4);
    } else {
      String v = value.toString().toLowerCase(Locale.ROOT);
      Configuration now = app.getResources().getConfiguration();
      boolean landscape = now.screenWidthDp > now.screenHeightDp;
      switch (v) {
        case "portrait":
          target = landscape ? (rotation + 3) % 4 : rotation;
          break;
        case "landscape":
          target = landscape ? rotation : (rotation + 1) % 4;
          break;
        default:
          target = Math.floorMod(Integer.parseInt(v) / 90, 4);
      }
    }
    Configuration config = app.getResources().getConfiguration();
    int width = config.screenWidthDp;
    int height = config.screenHeightDp;
    if ((target - rotation) % 2 != 0) {
      int swap = width;
      width = height;
      height = swap;
      folds.rotated();
    }
    rotation = target;
    resize(width, height, null);
    return describe();
  }

  private void setPosture(JSONObject c) throws JSONException {
    Float hinge = c.has("hingeDp") ? (float) c.getDouble("hingeDp") : null;
    String posture = c.has("posture") ? c.getString("posture") : c.getString("value");
    folds.set(posture, c.has("orientation") ? c.getString("orientation") : null, hinge);
  }

  /**
   * {@code wm size} / {@code wm density}: [widthPx] x [heightPx] at [dpi], as on a device, so a
   * density change keeps the pixel size. An override has no preset and no fold.
   */
  void overrideSize(int widthPx, int heightPx, int dpi) {
    preset = null;
    rotation = 0;
    folds.set(Foldables.NONE, null, null);
    resize(widthPx * 160 / dpi, heightPx * 160 / dpi, dpi + "dpi");
  }

  /** {@code wm size reset}: back to the launch's display, its preset and fold included. */
  void reset(String launchQualifiers) {
    rotation = 0;
    String launchPreset = System.getProperty(DisplayGeometry.DEVICE_PROPERTY);
    if (launchPreset != null) {
      device(launchPreset, null);
      return;
    }
    preset = null;
    folds.set(Foldables.NONE, null, null);
    RuntimeEnvironment.setQualifiers(launchQualifiers);
    configurationChanged();
  }

  /** The display's size, preset and fold, which a split screen gives back when it ends. */
  static final class Saved {
    final int widthDp;
    final int heightDp;
    final String preset;
    final Foldables.Saved fold;

    Saved(int widthDp, int heightDp, String preset, Foldables.Saved fold) {
      this.widthDp = widthDp;
      this.heightDp = heightDp;
      this.preset = preset;
      this.fold = fold;
    }
  }

  /** Whether the display's fold runs top to bottom; null when it has none. */
  Boolean foldVertical() {
    return folds.folded() ? folds.vertical() : null;
  }

  Saved save() {
    Configuration config = app.getResources().getConfiguration();
    return new Saved(config.screenWidthDp, config.screenHeightDp, preset, folds.save());
  }

  /**
   * The app's window is [widthDp] x [heightDp], its part of a split screen. No fold runs through
   * it: a foldable splits along its fold, which is the edge between the two windows.
   */
  void splitTo(int widthDp, int heightDp) {
    preset = null;
    folds.set(Foldables.NONE, null, null);
    resize(widthDp, heightDp, null);
  }

  /** The whole display again, as it was before a split ([saved]). */
  void restore(Saved saved) {
    preset = saved.preset;
    folds.restore(saved.fold);
    resize(saved.widthDp, saved.heightDp, null);
  }

  /**
   * Resizes the display to [widthDp] x [heightDp], keeping the density unless [density] names one
   * ({@code hdpi}, {@code 420dpi}, ...). The smallest width, screen size and aspect buckets and
   * the orientation follow from the size, as they do on a device; Robolectric's {@code +} merge
   * would keep the old ones.
   */
  void resize(int widthDp, int heightDp, String density) {
    if (widthDp <= 0 || heightDp <= 0) {
      throw new IllegalArgumentException("a display is at least 1dp each way");
    }
    StringBuilder q = new StringBuilder("+");
    q.append(DisplayGeometry.sizeQualifiers(widthDp, heightDp));
    if (density != null) q.append('-').append(density);
    RuntimeEnvironment.setQualifiers(q.toString());
    configurationChanged();
  }

  /**
   * Delivers the configuration Robolectric now holds to the top activity, the way the system does
   * after a display change, then reports the new fold bounds and resizes the window.
   */
  void configurationChanged() {
    Display display =
        app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    if (display != null) {
      ShadowDisplay shadow = Shadow.extract(display);
      shadow.setRotation(rotation);
    }
    Navigator.Entry top = navigator.top();
    if (top != null) {
      top.controller.configurationChange(
          app.getResources().getConfiguration(), app.getResources().getDisplayMetrics());
      // An activity that does not handle the change is recreated, and Robolectric's recreate stops
      // short of attaching the new window.
      Activity activity = top.activity();
      if (activity.getWindow() != null
          && !activity.getWindow().getDecorView().isAttachedToWindow()) {
        top.controller.visible();
      }
    }
    folds.publish();
    followWindow();
  }

  /**
   * Sizes the Swing window to the display, at its zoom, which Robolectric's window never does
   * itself.
   */
  private void followWindow() {
    if (GraphicsEnvironment.isHeadless()) return;
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    Dimension size = new Dimension(metrics.widthPixels, metrics.heightPixels);
    SwingUtilities.invokeLater(
        () -> {
          JPanel panel = SimulatorPanelRegistry.getActivePanel();
          if (panel == null) return;
          WindowZoom zoom = WindowZoom.of(panel);
          if (zoom != null) {
            zoom.deviceSize(size.width, size.height);
            return;
          }
          panel.setPreferredSize(size);
          Window window = SwingUtilities.getWindowAncestor(panel);
          if (window != null) window.pack();
        });
  }

  JSONObject describe() {
    Configuration config = app.getResources().getConfiguration();
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    return new Protocol.Json()
        .put("preset", preset)
        .put("widthDp", config.screenWidthDp)
        .put("heightDp", config.screenHeightDp)
        .put("smallestWidthDp", config.smallestScreenWidthDp)
        .put("width", metrics.widthPixels)
        .put("height", metrics.heightPixels)
        .put("density", metrics.density)
        .put("orientation", landscape(config) ? "landscape" : "portrait")
        .put("rotation", rotation * 90)
        .put("fontScale", config.fontScale)
        .put("night", night(config))
        .put("locale", config.getLocales().get(0).toLanguageTag())
        .put("fold", folds.describe())
        .put("qualifiers", RuntimeEnvironment.getQualifiers())
        .object();
  }

  private static boolean landscape(Configuration config) {
    return config.orientation == Configuration.ORIENTATION_LANDSCAPE;
  }

  private static boolean night(Configuration config) {
    return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
  }

  private static String density(JSONObject c) {
    if (!c.has("density")) return null;
    Object d = c.opt("density");
    if (d instanceof Number) return ((Number) d).intValue() + "dpi";
    return d.toString();
  }
}
