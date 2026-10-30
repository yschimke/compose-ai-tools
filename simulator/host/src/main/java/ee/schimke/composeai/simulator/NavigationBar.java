package ee.schimke.composeai.simulator;

import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The phone's navigation bar, part of {@link PhoneSystemUi}: gesture navigation's handle (the
 * default) or the three buttons, in a window of its own along the bottom of the default display, or
 * the side in landscape, as SystemUI's is. Robolectric's own navigation bar inset is switched on
 * with it, so apps get its {@code WindowInsets} and are laid out above it, or under it when they
 * draw edge to edge.
 *
 * <ul>
 *   <li>Gestures: a swipe up from the handle goes home, and {@link EdgeBackGesture} takes Back from
 *       either edge.
 *   <li>Buttons: Back, Home, and Overview (sent as {@code KEYCODE_APP_SWITCH}; there is no recents
 *       screen); there is no edge Back, as on a device.
 * </ul>
 *
 * The bar takes the app's navigation bar colour when the app is laid out clear of it, light or dark
 * icons as the app asked ({@code APPEARANCE_LIGHT_NAVIGATION_BARS}), and hides while the app hides
 * it. The command is {@code navigationBar {mode?: gesture | buttons | none}}.
 */
final class NavigationBar {

  /** {@code TYPE_NAVIGATION_BAR}: above the app and the status bar. */
  private static final int TYPE = WindowManager.LayoutParams.TYPE_STATUS_BAR + 19;

  enum Mode {
    GESTURE("gesture", "GESTURAL_NAVIGATION"),
    BUTTONS("buttons", "THREE_BUTTON_NAVIGATION"),
    NONE("none", "NO_NAVIGATION_BAR");

    final String id;
    final String behavior;

    Mode(String id, String behavior) {
      this.id = id;
      this.behavior = behavior;
    }

    static Mode of(String id) {
      for (Mode mode : values()) if (mode.id.equals(id)) return mode;
      throw new IllegalArgumentException(
          "navigationBar mode is gesture, buttons or none, not " + id);
    }
  }

  private final PhoneSystemUi ui;
  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;

  private Mode mode = Mode.GESTURE;

  /** Robolectric's {@code SystemUi} for the default display, or null. */
  private Object systemUi;

  /** The edge Back gesture, which button navigation does without. */
  private EdgeBackGesture edgeBack;

  private BarView view;
  private WindowManager.LayoutParams params;
  private String side = "BOTTOM";
  private int size;
  private int screenWidth;
  private int screenHeight;

  NavigationBar(PhoneSystemUi ui, Application app, Navigator navigator, Protocol protocol) {
    this.ui = ui;
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
  }

  void start() {
    edgeBack = Displays.edgeBack;
    systemUi = robolectricSystemUi();
    apply(mode);
  }

  /** One cycle: the bar follows the app on top, and the display's rotation. */
  void cycle() {
    if (mode == Mode.NONE || view == null) return;
    String nowSide = side();
    int nowSize = size();
    android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    if (!nowSide.equals(side)
        || nowSize != size
        || metrics.widthPixels != screenWidth
        || metrics.heightPixels != screenHeight) {
      attach();
      return;
    }
    followApp();
  }

  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!name.equals("navigationBar")) return null;
    if (c.has("mode")) apply(Mode.of(c.getString("mode")));
    return describe();
  }

  /** The bar's height when it is along the bottom and showing, else 0. */
  int sizeAtBottom() {
    return mode != Mode.NONE && view != null && side.equals("BOTTOM") && shown() ? size : 0;
  }

  JSONObject describe() {
    return new Protocol.Json()
        .put("mode", mode.id)
        .put("side", mode == Mode.NONE ? null : side.toLowerCase(java.util.Locale.ROOT))
        .put("sizePx", mode == Mode.NONE ? 0 : size)
        .put("visible", view != null && view.getVisibility() == View.VISIBLE)
        .put("darkIcons", view != null && view.darkIcons)
        .put(
            "background",
            view == null || Color.alpha(view.background) == 0
                ? null
                : String.format("#%08x", view.background))
        .put("edgeBack", Displays.edgeBack != null)
        .object();
  }

  private void apply(Mode next) {
    mode = next;
    if (systemUi != null) {
      try {
        ClassLoader loader = NavigationBar.class.getClassLoader();
        Class<?> type = Class.forName("org.robolectric.shadows.SystemUi", true, loader);
        Class<?> behavior =
            Class.forName("org.robolectric.shadows.SystemUi$NavigationBarBehavior", true, loader);
        ReflectionHelpers.callInstanceMethod(
            systemUi,
            "setNavigationBarBehavior",
            ClassParameter.from(behavior, ReflectionHelpers.getStaticField(type, next.behavior)));
      } catch (ClassNotFoundException | RuntimeException e) {
        // No inset; the bar is still drawn.
      }
    }
    // Button navigation has no edge swipe for Back.
    Displays.edgeBack = next == Mode.BUTTONS ? null : edgeBack;
    detach();
    if (next != Mode.NONE) attach();
  }

  private void attach() {
    detach();
    side = side();
    size = size();
    boolean vertical = !side.equals("BOTTOM");
    view = new BarView(ui.context(), vertical);
    PhoneSystemUi.simId(view, "navigation_bar");
    view.setContentDescription(
        mode == Mode.GESTURE ? "Gesture navigation" : "Back, Home, Overview");
    params =
        new WindowManager.LayoutParams(
            vertical ? size : ViewGroup.LayoutParams.MATCH_PARENT,
            vertical ? ViewGroup.LayoutParams.MATCH_PARENT : size,
            TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    // Placed by position: Robolectric lays a window out from its x and y, not its gravity.
    android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    screenWidth = metrics.widthPixels;
    screenHeight = metrics.heightPixels;
    params.gravity = Gravity.TOP | Gravity.START;
    params.x = side.equals("RIGHT") ? screenWidth - size : 0;
    params.y = side.equals("BOTTOM") ? screenHeight - size : 0;
    params.setFitInsetsTypes(0);
    params.setTitle("NavigationBar");
    ui.windows().addView(view, params);
    followApp();
  }

  private void detach() {
    if (view == null) return;
    ui.windows().removeViewImmediate(view);
    view = null;
  }

  /**
   * Takes the look of the app's window on top: its navigation bar colour behind the bar when the
   * window is laid out clear of it, light or dark icons as it asked, and no bar while it hides it.
   */
  private void followApp() {
    int background = mode == Mode.BUTTONS ? Color.BLACK : Color.TRANSPARENT;
    boolean darkIcons = false;
    Navigator.Entry top = navigator.top(Display.DEFAULT_DISPLAY);
    Window window = top == null ? null : top.activity().getWindow();
    View decor = window == null ? null : window.peekDecorView();
    if (decor != null && decor.isAttachedToWindow() && decor.getRootView() != null) {
      int[] xy = new int[2];
      decor.getLocationOnScreen(xy);
      android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
      boolean clear =
          side.equals("BOTTOM")
              ? xy[1] + decor.getHeight() <= metrics.heightPixels - size
              : side.equals("LEFT")
                  ? xy[0] >= size
                  : xy[0] + decor.getWidth() <= metrics.widthPixels - size;
      if (clear) {
        @SuppressWarnings("deprecation")
        int color = window.getNavigationBarColor();
        background = Color.alpha(color) == 0 ? Color.BLACK : color | 0xFF000000;
      }
      WindowInsetsController controller = window.getInsetsController();
      darkIcons =
          controller != null
              && (controller.getSystemBarsAppearance()
                      & WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
                  != 0;
    }
    if (Color.alpha(background) != 0) {
      // Icons on a painted bar are as dark as it is light.
      darkIcons = darkIcons && luminance(background) > 0.5f;
    }
    if (background != view.background || darkIcons != view.darkIcons) {
      view.background = background;
      view.darkIcons = darkIcons;
      view.invalidate();
    }
    boolean shown = shown();
    if (shown != (view.getVisibility() == View.VISIBLE)) {
      view.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
      if (shown) {
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
      } else {
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
      }
      ui.windows().updateViewLayout(view, params);
    }
  }

  private static float luminance(int color) {
    return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color))
        / 255f;
  }

  // ---------------------------------------------------------------------------------------------
  // Robolectric's navigation bar

  private static Object robolectricSystemUi() {
    try {
      Class<?> type =
          Class.forName(
              "org.robolectric.shadows.SystemUi", true, NavigationBar.class.getClassLoader());
      return ReflectionHelpers.callStaticMethod(type, "systemUiForDefaultDisplay");
    } catch (ClassNotFoundException | RuntimeException e) {
      return null;
    }
  }

  private Object robolectricBar() {
    if (systemUi == null) return null;
    try {
      return ReflectionHelpers.callInstanceMethod(systemUi, "getNavigationBar");
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String side() {
    Object bar = robolectricBar();
    if (bar != null) {
      try {
        return String.valueOf(ReflectionHelpers.callInstanceMethod(bar, "getSide"));
      } catch (RuntimeException e) {
        // The bottom, below.
      }
    }
    return "BOTTOM";
  }

  private int size() {
    Object bar = robolectricBar();
    if (bar != null) {
      try {
        int value = ReflectionHelpers.callInstanceMethod(bar, "getSize");
        if (value > 0) return value;
      } catch (RuntimeException e) {
        // The standard size, below.
      }
    }
    return ui.dp(mode == Mode.BUTTONS ? 48 : 24);
  }

  private boolean shown() {
    Object bar = robolectricBar();
    if (bar == null) return true;
    try {
      return ReflectionHelpers.callInstanceMethod(bar, "isVisible");
    } catch (RuntimeException e) {
      return true;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The bar

  /** The handle, or the three buttons, drawn on a canvas; a swipe up or a press acts on them. */
  private final class BarView extends View {
    final boolean vertical;
    int background = Color.TRANSPARENT;
    boolean darkIcons;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int slop;
    private float downX;
    private float downY;
    private boolean swiped;

    BarView(android.content.Context context, boolean vertical) {
      super(context);
      this.vertical = vertical;
      this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (Color.alpha(background) != 0) canvas.drawColor(background);
      int fg = darkIcons ? 0xFF1F1F1F : Color.WHITE;
      paint.setColor(fg);
      float w = getWidth();
      float h = getHeight();
      if (mode == Mode.GESTURE) {
        paint.setStyle(Paint.Style.FILL);
        float length = ui.dp(108);
        float thick = ui.dp(4);
        RectF handle =
            vertical
                ? new RectF(
                    w / 2 - thick / 2, h / 2 - length / 2, w / 2 + thick / 2, h / 2 + length / 2)
                : new RectF(
                    w / 2 - length / 2, h / 2 - thick / 2, w / 2 + length / 2, h / 2 + thick / 2);
        canvas.drawRoundRect(handle, thick / 2, thick / 2, paint);
        return;
      }
      float icon = ui.dp(16);
      paint.setStrokeWidth(ui.dp(2));
      paint.setStyle(Paint.Style.STROKE);
      for (int i = 0; i < 3; i++) {
        float cx = vertical ? w / 2 : w * (2 * i + 1) / 6f;
        float cy = vertical ? h * (5 - 2 * i) / 6f : h / 2;
        switch (i) {
          case 0:
            {
              Path back = new Path();
              back.moveTo(cx + icon / 2, cy - icon / 2);
              back.lineTo(cx - icon / 2, cy);
              back.lineTo(cx + icon / 2, cy + icon / 2);
              back.close();
              canvas.drawPath(back, paint);
              break;
            }
          case 1:
            canvas.drawCircle(cx, cy, icon / 2, paint);
            break;
          default:
            canvas.drawRoundRect(
                new RectF(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2),
                ui.dp(2),
                ui.dp(2),
                paint);
        }
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          swiped = false;
          return true;
        case MotionEvent.ACTION_MOVE:
          if (mode == Mode.GESTURE && !swiped && inward(event) > slop * 2) {
            // Up from the handle: home, as gesture navigation's quick swipe.
            swiped = true;
            ui.keys.accept(KeyEvent.KEYCODE_HOME);
          }
          return true;
        case MotionEvent.ACTION_UP:
          if (mode == Mode.BUTTONS && Math.abs(inward(event)) < slop) press(event);
          return true;
        default:
          return true;
      }
    }

    /** How far the finger moved away from the edge the bar is on. */
    private float inward(MotionEvent event) {
      switch (side) {
        case "LEFT":
          return event.getX() - downX;
        case "RIGHT":
          return downX - event.getX();
        default:
          return downY - event.getY();
      }
    }

    private void press(MotionEvent event) {
      float along = vertical ? 1f - event.getY() / getHeight() : event.getX() / getWidth();
      int button = Math.min(2, (int) (along * 3));
      int code =
          button == 0
              ? KeyEvent.KEYCODE_BACK
              : button == 1 ? KeyEvent.KEYCODE_HOME : KeyEvent.KEYCODE_APP_SWITCH;
      protocol.event(
          "navigationBar",
          new Protocol.Json()
              .put("pressed", button == 0 ? "back" : button == 1 ? "home" : "overview")
              .object());
      ui.keys.accept(code);
    }
  }
}
