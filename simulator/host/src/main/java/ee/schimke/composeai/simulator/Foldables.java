package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.core.util.function.Consumer;
import androidx.window.extensions.layout.DisplayFeature;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;

/**
 * The simulated device's fold: what the Jetpack WindowManager extensions report to an app's {@code
 * WindowInfoTracker}, and so to Compose's {@code currentWindowAdaptiveInfo()} (its posture and
 * hinge list) and to Material 3 adaptive scaffolds, which split panes at a separating hinge.
 *
 * <p>A device's system image supplies the extensions; Robolectric has none, so the app's {@code
 * androidx.window} falls back to an empty sidecar and never sees a fold. The classes under {@code
 * androidx.window.extensions} in this jar take their place, at vendor API level 2, and report the
 * fold set here: none (a phone, a tablet, a folded foldable's outer screen), or one across the
 * middle of the window, flat or half-opened, along either axis. A hinge with width is a {@code
 * TYPE_HINGE} (a dual-screen device); a zero-width one a {@code TYPE_FOLD}.
 *
 * <p>Listeners are told on registration and again after every change, on the main thread.
 */
public final class Foldables implements WindowLayoutComponent {

  public static final WindowExtensions EXTENSIONS =
      new WindowExtensions() {
        @Override
        public int getVendorApiLevel() {
          return 2;
        }

        @Override
        public WindowLayoutComponent getWindowLayoutComponent() {
          return INSTANCE;
        }
      };

  static final Foldables INSTANCE = new Foldables();

  /** No fold: the display is one flat panel. */
  static final String NONE = "none";

  /** {@link FoldingFeature#STATE_FLAT} or {@link FoldingFeature#STATE_HALF_OPENED}; 0 for none. */
  private int state;

  /** The fold runs top to bottom (book), rather than side to side (tabletop / laptop). */
  private boolean vertical = true;

  /** The hinge's width in dp; 0 for a fold with no gap. */
  private float hingeDp;

  private final Map<Object, Context> listeners = new IdentityHashMap<>();
  private final Map<Object, WindowLayoutInfo> lastSent = new IdentityHashMap<>();

  private Foldables() {
    // A launch on a foldable preset starts open and flat, before the app first asks.
    String device = System.getProperty(DisplayGeometry.DEVICE_PROPERTY);
    if (device != null && !device.isBlank()) {
      if (!NONE.equals(DisplayGeometry.preset(device).posture)) state = FoldingFeature.STATE_FLAT;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The control channel's side

  /**
   * Sets the fold. {@code posture} is {@code none}, {@code flat}, {@code half-opened}, or the
   * named half-opened postures {@code book} (vertical) and {@code tabletop} (horizontal);
   * {@code orientation} ({@code vertical} / {@code horizontal}) overrides the axis, else it is
   * kept.
   */
  void set(String posture, String orientation, Float hingeDp) {
    String p = posture.toLowerCase(Locale.ROOT).replace('_', '-');
    switch (p) {
      case NONE:
      case "closed":
        state = 0;
        break;
      case "flat":
      case "open":
        state = FoldingFeature.STATE_FLAT;
        break;
      case "half-opened":
      case "half":
        state = FoldingFeature.STATE_HALF_OPENED;
        break;
      case "book":
        state = FoldingFeature.STATE_HALF_OPENED;
        vertical = true;
        break;
      case "tabletop":
        state = FoldingFeature.STATE_HALF_OPENED;
        vertical = false;
        break;
      default:
        throw new IllegalArgumentException(
            "unknown posture " + posture + " (none, flat, half-opened, book, tabletop)");
    }
    if (orientation != null) vertical = parseVertical(orientation);
    if (hingeDp != null) this.hingeDp = Math.max(0, hingeDp);
    publish();
  }

  /** A quarter turn of the display: the fold turns with it. */
  void rotated() {
    vertical = !vertical;
  }

  boolean folded() {
    return state != 0;
  }

  /** The posture as Material's adaptive library would name it. */
  String posture() {
    if (state == 0) return NONE;
    if (state == FoldingFeature.STATE_FLAT) return "flat";
    return vertical ? "book" : "tabletop";
  }

  JSONObject describe() {
    Protocol.Json json = new Protocol.Json().put("posture", posture());
    if (state == 0) return json.object();
    json.put("state", state == FoldingFeature.STATE_FLAT ? "FLAT" : "HALF_OPENED");
    json.put("orientation", vertical ? "VERTICAL" : "HORIZONTAL");
    json.put("type", hingeDp > 0 ? "HINGE" : "FOLD");
    json.put("hingeDp", hingeDp);
    Rect window = windowBounds(RuntimeEnvironment.getApplication());
    Rect bounds = foldBounds(window, density(RuntimeEnvironment.getApplication()));
    json.put("bounds", bounds.toShortString());
    json.put("listeners", listeners.size());
    return json.object();
  }

  /** Tells every listener the current features, where they changed (on the main thread). */
  void publish() {
    for (Map.Entry<Object, Context> entry : new ArrayList<>(listeners.entrySet())) {
      Object consumer = entry.getKey();
      if (!listeners.containsKey(consumer)) continue;
      WindowLayoutInfo info = info(entry.getValue());
      if (info.equals(lastSent.get(consumer))) continue;
      deliver(consumer, info);
    }
  }

  private static boolean parseVertical(String orientation) {
    switch (orientation.toLowerCase(Locale.ROOT)) {
      case "vertical":
        return true;
      case "horizontal":
        return false;
      default:
        throw new IllegalArgumentException(
            "orientation is vertical or horizontal, not " + orientation);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The extensions' side, called by the app's androidx.window

  @Override
  public void addWindowLayoutInfoListener(
      Activity activity, java.util.function.Consumer<WindowLayoutInfo> consumer) {
    add(activity, consumer);
  }

  @Override
  public void removeWindowLayoutInfoListener(
      java.util.function.Consumer<WindowLayoutInfo> consumer) {
    remove(consumer);
  }

  @Override
  public void addWindowLayoutInfoListener(Context context, Consumer<WindowLayoutInfo> consumer) {
    add(context, consumer);
  }

  @Override
  public void removeWindowLayoutInfoListener(Consumer<WindowLayoutInfo> consumer) {
    remove(consumer);
  }

  private void add(Context context, Object consumer) {
    listeners.put(consumer, context);
    // As a device's extensions do: the current value on registration.
    deliver(consumer, info(context));
  }

  private void remove(Object consumer) {
    listeners.remove(consumer);
    lastSent.remove(consumer);
  }

  @SuppressWarnings("unchecked")
  private void deliver(Object consumer, WindowLayoutInfo info) {
    lastSent.put(consumer, info);
    if (consumer instanceof Consumer) {
      ((Consumer<WindowLayoutInfo>) consumer).accept(info);
    } else {
      ((java.util.function.Consumer<WindowLayoutInfo>) consumer).accept(info);
    }
  }

  /** The features overlapping [context]'s window: the fold, when there is one. */
  WindowLayoutInfo info(Context context) {
    if (state == 0) return new WindowLayoutInfo(Collections.<DisplayFeature>emptyList());
    Rect bounds = foldBounds(windowBounds(context), density(context));
    int type = hingeDp > 0 ? FoldingFeature.TYPE_HINGE : FoldingFeature.TYPE_FOLD;
    List<DisplayFeature> features = new ArrayList<>();
    features.add(new FoldingFeature(bounds, type, state));
    return new WindowLayoutInfo(features);
  }

  /**
   * The fold across the middle of [window]: a line for a fold, a band [hingeDp] wide for a hinge.
   * Its bounds span the window's whole height (vertical) or width (horizontal), which is what
   * {@code androidx.window} accepts.
   */
  Rect foldBounds(Rect window, float density) {
    int[] b =
        DisplayGeometry.foldBounds(
            window.left,
            window.top,
            window.right,
            window.bottom,
            Math.round(hingeDp * density),
            vertical);
    return new Rect(b[0], b[1], b[2], b[3]);
  }

  /**
   * The window's bounds as {@code androidx.window} computes them for the same context (its
   * {@code WindowMetricsCalculator}), so the fold validates against them.
   */
  private static Rect windowBounds(Context context) {
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        if (windowManager != null) return windowManager.getCurrentWindowMetrics().getBounds();
      } catch (RuntimeException e) {
        // A context with no window: fall back to the display.
      }
    }
    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
  }

  private static float density(Context context) {
    return context.getResources().getDisplayMetrics().density;
  }
}
