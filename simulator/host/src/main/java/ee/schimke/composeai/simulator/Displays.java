package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPixelCopy;
import org.robolectric.shadows.ShadowView;
import org.robolectric.simulator.KeyboardHandler;
import org.robolectric.simulator.SimulatorPanelRegistry;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * More than one display. Robolectric keeps a list of displays and builds an activity's context on
 * the display its {@code ActivityOptions} name, but draws every window into one screenshot and
 * sends every touch to whichever window is on top, whatever its display. This class adds and
 * removes displays as a device's display manager does ({@code DisplayListener.onDisplayAdded} and
 * the rest reach the app, and {@code DISPLAY_CATEGORY_PRESENTATION} finds an external or virtual
 * one), and gives each display its own picture and its own input: {@link #capture} composes only
 * the windows on one display, {@link #inject} dispatches only to them, and {@link
 * SimulatorShadowUiAutomation} makes Robolectric's own window and screenshots the default
 * display's alone. With a screen, each secondary display gets a window of its own, titled with its
 * name, that takes the mouse and keyboard; closing it unplugs the display.
 *
 * <p>Runs on the main thread, inside the sandbox, except where noted.
 */
final class Displays {

  /** {@code Display.TYPE_*}, hidden. */
  private static final int TYPE_INTERNAL = 1;

  private static final int TYPE_EXTERNAL = 2;
  private static final int TYPE_VIRTUAL = 5;

  /** {@code Display.FLAG_REAR}, hidden (API 34). */
  private static final int FLAG_REAR = 1 << 13;

  private static final long WINDOW_FRAME_MS = 50;

  /** The session's instance, for {@link SimulatorShadowUiAutomation}. */
  private static volatile Displays current;

  /** The display a person last focused a window of, which their key presses go to. */
  private static volatile int focusedDisplay = Display.DEFAULT_DISPLAY;

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final Map<Integer, DisplaySpec> added = new LinkedHashMap<>();
  private final Map<Integer, DisplayWindow> windows = new HashMap<>();
  private final boolean headless = GraphicsEnvironment.isHeadless();
  private long lastFrame;
  private int uniqueIds;

  Displays(Application app, Navigator navigator, Protocol protocol) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    current = this;
  }

  /** Adds the launch's displays ({@code --display}), before the launcher activity starts. */
  void start() {
    for (DisplaySpec spec : DisplaySpec.parseAll(System.getProperty(DisplaySpec.PROPERTY))) {
      add(spec);
    }
  }

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!name.equals("displays")) return null;
    String action = c.optString("action", c.optString("value", "list"));
    switch (action) {
      case "list":
        return new Protocol.Json().put("displays", list()).object();
      case "add":
        {
          int id = add(spec(c, null));
          return describe(id);
        }
      case "remove":
        return remove(id(c));
      case "change":
        {
          int id = id(c);
          DisplaySpec was = added.get(id);
          if (was == null) throw new IllegalArgumentException("display " + id + " was not added");
          change(id, spec(c, was));
          return describe(id);
        }
      default:
        throw new IllegalArgumentException(
            "unknown displays action " + action + " (list, add, remove, change)");
    }
  }

  static boolean changesUi(String name) {
    return name.equals("displays");
  }

  /** The display a command names with {@code display}, checked; the default display without. */
  int target(JSONObject c) {
    if (!c.has("display")) return Display.DEFAULT_DISPLAY;
    int id = c.optInt("display", -1);
    if (id < 0 || display(id) == null) {
      throw new IllegalArgumentException("no display " + c.opt("display") + " (see displays)");
    }
    return id;
  }

  private static int id(JSONObject c) throws JSONException {
    return c.has("id") && !c.has("display") ? c.getInt("id") : c.getInt("display");
  }

  private static DisplaySpec spec(JSONObject c, DisplaySpec base) {
    String density = null;
    if (c.has("density")) {
      Object d = c.opt("density");
      density = d instanceof Number ? ((Number) d).intValue() + "dpi" : d.toString();
    }
    String name = c.optString("name", null);
    String type = c.optString("type", null);
    Integer widthDp = c.has("widthDp") ? c.optInt("widthDp") : null;
    Integer heightDp = c.has("heightDp") ? c.optInt("heightDp") : null;
    String qualifiers = c.optString("qualifiers", null);
    return base == null
        ? DisplaySpec.of(name, type, widthDp, heightDp, density, qualifiers)
        : base.changed(name, type, widthDp, heightDp, density, qualifiers);
  }

  // ---------------------------------------------------------------------------------------------
  // The display manager

  /**
   * Adds [spec] as a new display and returns its id. The app's display listeners hear of it
   * before this returns.
   */
  int add(DisplaySpec spec) {
    Object info = displayInfo(spec);
    int id =
        ReflectionHelpers.callInstanceMethod(
            shadowDisplayManagerGlobal(),
            "addDisplay",
            ClassParameter.from(displayInfoClass(), info));
    added.put(id, spec);
    ShadowLooper.shadowMainLooper().idle();
    openWindow(id);
    protocol.event(
        "display", new Protocol.Json().put("state", "added").put("display", describe(id)).object());
    return id;
  }

  private JSONObject remove(int id) {
    if (!added.containsKey(id)) {
      throw new IllegalArgumentException("display " + id + " was not added");
    }
    JSONObject was = describe(id);
    // A device moves a removed display's tasks to the default display, or finishes them; here they
    // finish, as an app that closes its secondary-screen activity would see on a private display.
    JSONArray finished = navigator.finishOn(id);
    ShadowDisplayManager.removeDisplay(id);
    added.remove(id);
    ShadowLooper.shadowMainLooper().idle();
    closeWindow(id);
    JSONObject result = new Protocol.Json().put("removed", was).put("finished", finished).object();
    protocol.event("display", new Protocol.Json().put("state", "removed").put("id", id).object());
    return result;
  }

  private void change(int id, DisplaySpec spec) {
    Object info = displayInfo(spec);
    ReflectionHelpers.setField(info, "displayId", id);
    ReflectionHelpers.callInstanceMethod(
        shadowDisplayManagerGlobal(),
        "changeDisplay",
        ClassParameter.from(int.class, id),
        ClassParameter.from(displayInfoClass(), info));
    added.put(id, spec);
    ShadowLooper.shadowMainLooper().idle();
    navigator.displayChanged(id);
    resizeWindow(id);
  }

  /** A {@code DisplayInfo} for [spec]: Robolectric's from the qualifiers, then type and flags. */
  private Object displayInfo(DisplaySpec spec) {
    int type;
    int flags;
    switch (spec.kind) {
      case VIRTUAL:
        type = TYPE_VIRTUAL;
        flags = Display.FLAG_PRESENTATION;
        break;
      case REAR:
        type = TYPE_INTERNAL;
        flags = FLAG_REAR;
        break;
      default:
        type = TYPE_EXTERNAL;
        flags =
            Display.FLAG_PRESENTATION
                | Display.FLAG_SECURE
                | Display.FLAG_SUPPORTS_PROTECTED_BUFFERS;
    }
    Object info =
        ReflectionHelpers.callStaticMethod(
            ShadowDisplayManager.class,
            "createDisplayInfo",
            ClassParameter.from(String.class, spec.qualifiers),
            ClassParameter.from(Integer.class, null),
            ClassParameter.from(String.class, spec.name),
            ClassParameter.from(int.class, type));
    ReflectionHelpers.setField(info, "flags", flags);
    // Robolectric names every display "screen0"; the framework keys displays by it.
    String uniqueId = "simulator:" + spec.kind.label() + ":" + uniqueIds++;
    ReflectionHelpers.setField(info, "uniqueId", uniqueId);
    if (spec.kind == DisplaySpec.Kind.VIRTUAL) {
      ReflectionHelpers.setField(info, "ownerPackageName", app.getPackageName());
    }
    return info;
  }

  private static Class<?> displayInfoClass() {
    try {
      return Class.forName("android.view.DisplayInfo", false, Displays.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object shadowDisplayManagerGlobal() {
    try {
      Class<?> global =
          Class.forName(
              "android.hardware.display.DisplayManagerGlobal",
              true,
              Displays.class.getClassLoader());
      return Shadow.extract(ReflectionHelpers.callStaticMethod(global, "getInstance"));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private Display display(int id) {
    return app.getSystemService(DisplayManager.class).getDisplay(id);
  }

  JSONArray list() {
    JSONArray array = new JSONArray();
    for (Display d : app.getSystemService(DisplayManager.class).getDisplays()) {
      array.put(describe(d.getDisplayId()));
    }
    return array;
  }

  JSONObject describe(int id) {
    Display d = display(id);
    if (d == null) throw new IllegalArgumentException("no display " + id);
    DisplayMetrics metrics = new DisplayMetrics();
    d.getRealMetrics(metrics);
    DisplaySpec spec = added.get(id);
    String kind;
    if (id == Display.DEFAULT_DISPLAY) kind = "default";
    else if (spec != null) kind = spec.kind.label();
    else kind = typeOf(d) == TYPE_VIRTUAL ? "virtual" : "other";
    JSONArray flags = new JSONArray();
    if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) flags.put("presentation");
    if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) flags.put("private");
    if ((d.getFlags() & Display.FLAG_SECURE) != 0) flags.put("secure");
    if ((d.getFlags() & FLAG_REAR) != 0) flags.put("rear");
    float density = metrics.density == 0 ? 1 : metrics.density;
    Set<Object> hidden = navigator.hiddenWindowTokens();
    int windowCount = roots(id, hidden).size();
    return new Protocol.Json()
        .put("id", id)
        .put("name", d.getName())
        .put("type", kind)
        .put("flags", flags)
        .put("width", metrics.widthPixels)
        .put("height", metrics.heightPixels)
        .put("widthDp", Math.round(metrics.widthPixels / density))
        .put("heightDp", Math.round(metrics.heightPixels / density))
        .put("densityDpi", metrics.densityDpi)
        .put("state", d.getState() == Display.STATE_ON ? "on" : "off")
        .put("added", spec != null ? "simulator" : id == Display.DEFAULT_DISPLAY ? "device" : "app")
        .put("windows", windowCount)
        .put("activities", navigator.activitiesOn(id))
        .object();
  }

  private static int typeOf(Display d) {
    try {
      return ReflectionHelpers.callInstanceMethod(d, "getType");
    } catch (RuntimeException e) {
      return 0;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Activities on a display

  /**
   * The configuration of an activity on display [id], with its metrics in [metrics]: the
   * application's (locale, night mode, font scale) with the display's size, density, orientation
   * and window bounds, as a device's window manager gives an activity it places there. Robolectric
   * builds a secondary-display activity's context but keeps the default display's configuration.
   */
  static Configuration configurationOf(int id, DisplayMetrics metrics) {
    Application app = org.robolectric.RuntimeEnvironment.getApplication();
    Display display = app.getSystemService(DisplayManager.class).getDisplay(id);
    if (display == null) throw new IllegalArgumentException("no display " + id);
    display.getRealMetrics(metrics);
    Configuration config = new Configuration(app.getResources().getConfiguration());
    float density = metrics.density == 0 ? 1 : metrics.density;
    int widthDp = Math.round(metrics.widthPixels / density);
    int heightDp = Math.round(metrics.heightPixels / density);
    config.densityDpi = metrics.densityDpi;
    config.screenWidthDp = widthDp;
    config.screenHeightDp = heightDp;
    config.smallestScreenWidthDp = Math.min(widthDp, heightDp);
    config.orientation =
        widthDp > heightDp
            ? Configuration.ORIENTATION_LANDSCAPE
            : Configuration.ORIENTATION_PORTRAIT;
    config.screenLayout =
        (config.screenLayout
                & ~(Configuration.SCREENLAYOUT_SIZE_MASK | Configuration.SCREENLAYOUT_LONG_MASK))
            | DisplayGeometry.screenLayout(widthDp, heightDp);
    Object window = ReflectionHelpers.getField(config, "windowConfiguration");
    Rect bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    for (String setter : new String[] {"setBounds", "setAppBounds", "setMaxBounds"}) {
      ReflectionHelpers.callInstanceMethod(
          window, setter, ClassParameter.from(Rect.class, new Rect(bounds)));
    }
    return config;
  }

  /**
   * Gives [activity], attached on display [id] and not yet created, that display's configuration
   * ({@link #configurationOf}), so its resources, {@code getDisplay()} and layout see the display
   * it is on.
   */
  static void placeOn(Activity activity, int id) {
    if (id == Display.DEFAULT_DISPLAY) return;
    DisplayMetrics metrics = new DisplayMetrics();
    Configuration config = configurationOf(id, metrics);
    activity.getResources().updateConfiguration(config, metrics);
    ReflectionHelpers.setField(activity, "mCurrentConfig", new Configuration(config));
  }

  // ---------------------------------------------------------------------------------------------
  // Windows on a display

  /** One window, as {@code ShadowUiAutomation} sees it, on one display. */
  private static final class Root {
    final Object impl;
    final View view;
    final WindowManager.LayoutParams params;
    final int index;
    final Point location = new Point();

    Root(Object impl, View view, WindowManager.LayoutParams params, int index) {
      this.impl = impl;
      this.view = view;
      this.params = params;
      this.index = index;
      int[] xy = new int[2];
      view.getLocationOnScreen(xy);
      location.set(xy[0], xy[1]);
    }

    boolean has(int flag) {
      return (params.flags & flag) == flag;
    }

    boolean touchable() {
      return !has(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    boolean focusable() {
      return !has(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    boolean touchModal() {
      return focusable() && !has(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
    }

    boolean visible() {
      return view.getWidth() > 0 && view.getHeight() > 0;
    }

    boolean inside(MotionEvent event) {
      int i = event.getActionIndex();
      return event.getX(i) >= location.x
          && event.getX(i) <= location.x + view.getWidth()
          && event.getY(i) >= location.y
          && event.getY(i) <= location.y + view.getHeight();
    }
  }

  /** The display a window's root view is on; a window not yet attached counts as the default. */
  static int displayOf(View root) {
    Display d = root.getDisplay();
    return d == null ? Display.DEFAULT_DISPLAY : d.getDisplayId();
  }

  /**
   * The windows on display [id], top-most first, as Robolectric orders them: by window type, then
   * the latest added. A window whose token is in [hidden] (a stopped activity's, and its dialogs
   * and popups) is left out.
   */
  private static List<Root> roots(int id, Set<Object> hidden) {
    Object global;
    try {
      global =
          ReflectionHelpers.callStaticMethod(
              Class.forName(
                  "android.view.WindowManagerGlobal", true, Displays.class.getClassLoader()),
              "getInstance");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
    List<?> impls = asList(ReflectionHelpers.getField(global, "mRoots"));
    List<?> params = asList(ReflectionHelpers.getField(global, "mParams"));
    List<Root> roots = new ArrayList<>();
    for (int i = 0; i < Math.min(impls.size(), params.size()); i++) {
      Object impl = impls.get(i);
      View view = ReflectionHelpers.callInstanceMethod(impl, "getView");
      if (view == null || displayOf(view) != id) continue;
      WindowManager.LayoutParams p = (WindowManager.LayoutParams) params.get(i);
      // A stopped activity's window, and its dialogs and popups, which share its token.
      if (hidden.contains(view.getApplicationWindowToken())) continue;
      roots.add(new Root(impl, view, p, i));
    }
    roots.sort(
        Comparator.comparingInt((Root r) -> r.params.type)
            .reversed()
            .thenComparing(Comparator.comparingInt((Root r) -> r.index).reversed()));
    return roots;
  }

  private static List<?> asList(Object value) {
    if (value instanceof Object[]) return Arrays.asList((Object[]) value);
    if (value instanceof List) return new ArrayList<>((List<?>) value);
    return Collections.emptyList();
  }

  private static Set<Object> hidden() {
    Displays displays = current;
    return displays == null ? Collections.emptySet() : displays.navigator.hiddenWindowTokens();
  }

  /**
   * A picture of display [id]: each visible window on it drawn where it sits, bottom-most first,
   * on a bitmap the display's size. On the main thread, or handed to it.
   */
  static Bitmap capture(int id) throws Exception {
    if (!ShadowView.useRealGraphics()) return null;
    if (Looper.myLooper() != Looper.getMainLooper()) {
      FutureTask<Bitmap> task = new FutureTask<>(() -> capture(id));
      new Handler(Looper.getMainLooper()).post(task);
      return task.get();
    }
    Display display =
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(DisplayManager.class)
            .getDisplay(id);
    if (display == null) throw new IllegalArgumentException("no display " + id);
    Point size = new Point();
    display.getRealSize(size);
    Bitmap screen = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888);
    List<Root> roots = roots(id, hidden());
    Collections.reverse(roots);
    Canvas canvas = new Canvas(screen);
    Paint paint = new Paint();
    for (Root root : roots) {
      if (!root.visible()) continue;
      Bitmap window =
          Bitmap.createBitmap(root.view.getWidth(), root.view.getHeight(), Bitmap.Config.ARGB_8888);
      draw(window, root);
      canvas.drawBitmap(window, root.location.x, root.location.y, paint);
      window.recycle();
    }
    return screen;
  }

  /** As {@code ShadowUiAutomation} draws a window: from its surface, under real draw traversals. */
  private static void draw(Bitmap bitmap, Root root) {
    ShadowView shadow = Shadow.extract(root.view);
    ReflectionHelpers.callInstanceMethod(
        shadow, "setDrawingTime", ClassParameter.from(long.class, SystemClock.uptimeMillis()));
    Surface surface = ReflectionHelpers.getField(root.impl, "mSurface");
    boolean traversals =
        ReflectionHelpers.callStaticMethod(ShadowView.class, "areRealDrawTraversalsEnabled");
    if (traversals && surface != null) {
      int[] xy = new int[2];
      root.view.getLocationInSurface(xy);
      Rect bounds =
          new Rect(xy[0], xy[1], xy[0] + root.view.getWidth(), xy[1] + root.view.getHeight());
      ReflectionHelpers.callStaticMethod(
          ShadowPixelCopy.class,
          "captureImageFromSurface",
          ClassParameter.from(Surface.class, surface),
          ClassParameter.from(Bitmap.class, bitmap),
          ClassParameter.from(Rect.class, bounds));
    } else {
      root.view.draw(new Canvas(bitmap));
    }
  }

  /**
   * Dispatches [event] to the windows on display [id], as {@code ShadowUiAutomation} does for the
   * whole screen: a touch to the top-most touchable window under it (or a touch-modal one), a key
   * to the top-most focusable window.
   */
  static boolean inject(InputEvent event, int id) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      new Handler(Looper.getMainLooper()).post(() -> inject(event, id));
      return true;
    }
    List<Root> roots = roots(id, hidden());
    if (event instanceof KeyEvent) {
      for (Root root : roots) {
        if (root.focusable()) {
          root.view.dispatchKeyEvent((KeyEvent) event);
          break;
        }
      }
      return true;
    }
    MotionEvent motion = (MotionEvent) event;
    List<Root> touchable = new ArrayList<>();
    for (Root root : roots) if (root.touchable()) touchable.add(root);
    for (int i = 0; i < touchable.size(); i++) {
      Root root = touchable.get(i);
      if (i == touchable.size() - 1 || root.touchModal() || root.inside(motion)) {
        motion.offsetLocation(-root.location.x, -root.location.y);
        if (motion.isFromSource(InputDevice.SOURCE_MOUSE)
            && motion.getActionMasked() == MotionEvent.ACTION_SCROLL) {
          root.view.dispatchGenericMotionEvent(motion);
        } else {
          root.view.dispatchTouchEvent(motion);
        }
        motion.offsetLocation(root.location.x, root.location.y);
        break;
      } else if (motion.getActionMasked() == MotionEvent.ACTION_DOWN
          && !root.touchModal()
          && root.has(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)) {
        MotionEvent outside = MotionEvent.obtain(motion);
        outside.setAction(MotionEvent.ACTION_OUTSIDE);
        outside.offsetLocation(-root.location.x, -root.location.y);
        root.view.dispatchTouchEvent(outside);
        outside.recycle();
      }
    }
    return true;
  }

  /**
   * Where an event from Robolectric's own window goes: its mouse is over the default display, and
   * its keys follow whichever simulator window has focus.
   */
  static int routeOf(InputEvent event) {
    return event instanceof KeyEvent ? focusedDisplay : Display.DEFAULT_DISPLAY;
  }

  // ---------------------------------------------------------------------------------------------
  // A window per secondary display

  /** One simulator cycle: repaints each secondary display's window, a few times a second. */
  void cycle() {
    if (windows.isEmpty()) return;
    long now = System.nanoTime() / 1_000_000;
    if (now - lastFrame < WINDOW_FRAME_MS) return;
    lastFrame = now;
    for (Map.Entry<Integer, DisplayWindow> e : windows.entrySet()) {
      try {
        Bitmap bitmap = capture(e.getKey());
        if (bitmap == null) continue;
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        bitmap.recycle();
        DisplayWindow window = e.getValue();
        SwingUtilities.invokeLater(() -> window.show(pixels, w, h));
      } catch (Exception ex) {
        // A display removed mid-frame: the next cycle no longer has it.
      }
    }
  }

  private void openWindow(int id) {
    if (headless) return;
    Display d = display(id);
    Point size = new Point();
    d.getRealSize(size);
    String title = d.getName() + " — display " + id;
    DisplayWindow window = new DisplayWindow(id);
    windows.put(id, window);
    SwingUtilities.invokeLater(() -> window.open(title, size.x, size.y, () -> unplug(id)));
  }

  /** The person closed a display's window: the display goes, as if its cable were pulled. */
  private void unplug(int id) {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              if (added.containsKey(id)) remove(id);
            });
  }

  private void closeWindow(int id) {
    DisplayWindow window = windows.remove(id);
    if (window != null) SwingUtilities.invokeLater(window::close);
  }

  private void resizeWindow(int id) {
    DisplayWindow window = windows.get(id);
    if (window == null) return;
    Point size = new Point();
    display(id).getRealSize(size);
    SwingUtilities.invokeLater(() -> window.resize(size.x, size.y));
  }

  /** A secondary display's Swing window: its picture, and mouse and keys into its windows. */
  private static final class DisplayWindow {
    /** Pixels between windows laid out side by side. */
    static final int GAP = 16;

    final int id;
    JFrame frame;
    Panel panel;

    DisplayWindow(int id) {
      this.id = id;
    }

    void open(String title, int width, int height, Runnable onClose) {
      panel = new Panel(id);
      panel.setPreferredSize(new Dimension(width, height));
      frame = new JFrame(title);
      frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
      frame.addWindowListener(
          new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
              onClose.run();
            }
          });
      frame.addWindowFocusListener(
          new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
              focusedDisplay = id;
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
              if (focusedDisplay == id) focusedDisplay = Display.DEFAULT_DISPLAY;
            }
          });
      frame.getContentPane().add(panel);
      frame.pack();
      JPanel main = SimulatorPanelRegistry.getActivePanel();
      Window owner = main == null ? null : SwingUtilities.getWindowAncestor(main);
      if (owner != null) {
        // In a row to the right of the phone, past every display window already open.
        int x = owner.getX() + owner.getWidth() + GAP;
        for (Window open : Window.getWindows()) {
          if (open != frame && open != owner && open.isShowing()) {
            x = Math.max(x, open.getX() + open.getWidth() + GAP);
          }
        }
        frame.setLocation(x, owner.getY());
      }
      frame.setVisible(true);
    }

    void show(int[] pixels, int width, int height) {
      if (panel != null) panel.show(pixels, width, height);
    }

    void resize(int width, int height) {
      if (panel == null) return;
      panel.setPreferredSize(new Dimension(width, height));
      frame.pack();
    }

    void close() {
      if (frame != null) frame.dispose();
    }
  }

  private static final class Panel extends JPanel {
    private final int display;
    private BufferedImage image;
    private long downTime;
    private boolean pressed;

    Panel(int display) {
      this.display = display;
      setFocusable(true);
      setFocusTraversalKeysEnabled(false);
      // Robolectric's handler injects through UiAutomation, which routes keys to the focused
      // display's windows.
      addKeyListener(new KeyboardHandler());
      MouseAdapter mouse =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              if (!SwingUtilities.isLeftMouseButton(e)) return;
              requestFocusInWindow();
              focusedDisplay = display;
              pressed = true;
              downTime = SystemClock.uptimeMillis();
              post(e, MotionEvent.ACTION_DOWN);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              if (pressed) post(e, MotionEvent.ACTION_MOVE);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
              if (!pressed) return;
              pressed = false;
              post(e, MotionEvent.ACTION_UP);
            }
          };
      addMouseListener(mouse);
      addMouseMotionListener(mouse);
    }

    private void post(MouseEvent e, int action) {
      MotionEvent event =
          MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, e.getX(), e.getY(), 0);
      event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      inject(event, display);
      e.consume();
    }

    void show(int[] pixels, int width, int height) {
      if (image == null || image.getWidth() != width || image.getHeight() != height) {
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      }
      int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
      System.arraycopy(pixels, 0, data, 0, Math.min(pixels.length, data.length));
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (image != null) g.drawImage(image, 0, 0, this);
    }
  }
}
