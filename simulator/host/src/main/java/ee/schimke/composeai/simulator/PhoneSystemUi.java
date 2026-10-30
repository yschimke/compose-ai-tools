package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * A phone's system UI over the app and the home screen ({@code --launcher} on a phone): the status
 * bar along the top of the default display, the notification shade pulled down from it, and the
 * heads-up card an interrupting notification shows. Each is a window of its own above every
 * activity, as SystemUI's are, so it is drawn in screenshots and takes touches over whatever app is
 * on screen.
 *
 * <ul>
 *   <li>The status bar shows the clock, an icon per notification, Wi-Fi, mobile signal or airplane
 *       mode, and the battery. Robolectric's own status bar inset is switched on with it, so the
 *       app is laid out below the bar, or under it when it draws edge to edge; the bar takes the
 *       app's status bar colour and light or dark icons, and hides while the app hides it.
 *   <li>A swipe down from the bar, or {@code notifications op:open}, opens the shade: the clock,
 *       quick settings tiles (Wi-Fi, mobile data, airplane mode, battery) and each notification
 *       inflated from its own {@code RemoteViews} ({@code contentView}, or {@code bigContentView}
 *       when expanded), as the preview renderer does. Tapping one sends its content intent, its
 *       buttons its actions, a reply action opens an inline reply field, a sideways swipe dismisses
 *       it; Back, Home or a swipe up closes the shade.
 * </ul>
 *
 * <p>The notifications are {@link NotificationStream}'s, the model the watch's stream draws too, so
 * the {@code notification} command and these views act on them the same way. Views carry {@code
 * sim:id/...} ids and the notification's key as their description, as the watch's do. Plain views
 * only, as {@link PhoneHome}.
 */
final class PhoneSystemUi {

  /** {@code TYPE_STATUS_BAR_PANEL} (hidden): above the app and the status bar, below the shade. */
  private static final int TYPE_HEADS_UP = WindowManager.LayoutParams.TYPE_STATUS_BAR + 14;

  /** {@code TYPE_NOTIFICATION_SHADE} (hidden), the top-most of the three. */
  private static final int TYPE_SHADE = WindowManager.LayoutParams.TYPE_STATUS_BAR + 40;

  /** How long a heads-up card stays before it goes back into the shade. */
  private static final long HEADS_UP_MS = 5000;

  /** The status bar's height when Robolectric cannot say, as its standard bar is. */
  private static final int STATUS_BAR_DP = 24;

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final NotificationStream stream;
  private final Context context;
  private final WindowManager windows;

  /** Robolectric's {@code SystemUi.StatusBar} for the default display, or null. */
  private final Object insets;

  private int height;
  private StatusBarView bar;
  private WindowManager.LayoutParams barParams;
  private long lastTick;

  private ShadeRoot shade;

  /** The status bar's icons along the top of the open shade, which covers the bar's window. */
  private View shadeBar;

  private LinearLayout shadeList;
  private TextView shadeClock;

  /** The notification whose reply field is open, as {@code key/actionIndex}; or null. */
  private String replying;

  /** Notifications a person expanded (true) or collapsed (false); the rest take the default. */
  private final Map<String, Boolean> expanded = new HashMap<>();

  private FrameLayout headsUp;
  private String headsUpKey;
  private long headsUpUntil;
  private final Set<String> seen = new HashSet<>();

  /** Extra copies of the status bar's row, drawn at the top of the shade and the lock screen. */
  private final List<StatusBarView> rows = new ArrayList<>();

  /** Presses a key on the default display as the system's own buttons do. */
  final java.util.function.IntConsumer keys;

  final NavigationBar navigationBar;
  final LockScreen lockScreen;
  final SoftKeyboard keyboard;

  private PhoneSystemUi(
      Application app,
      Navigator navigator,
      Protocol protocol,
      NotificationStream stream,
      java.util.function.IntConsumer keys,
      java.util.function.Consumer<String> typer) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.stream = stream;
    this.keys = keys;
    this.context = new ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault_DayNight);
    this.windows = app.getSystemService(WindowManager.class);
    this.insets = robolectricStatusBar();
    this.navigationBar = new NavigationBar(this, app, navigator, protocol);
    this.lockScreen = new LockScreen(this, app, navigator, protocol, stream);
    this.keyboard = new SoftKeyboard(this, app, navigator, protocol, typer);
  }

  /**
   * The phone's system UI when {@code --launcher} draws the phone home screen, else null. [keys]
   * presses a key on the default display, for the navigation bar's buttons.
   */
  static PhoneSystemUi create(
      Application app,
      Navigator navigator,
      Protocol protocol,
      NotificationStream stream,
      java.util.function.IntConsumer keys,
      java.util.function.Consumer<String> typer) {
    if (FormFactor.current() != FormFactor.PHONE) return null;
    return new PhoneSystemUi(app, navigator, protocol, stream, keys, typer);
  }

  /** Adds the status bar window: before the first cycle, so the app is laid out below it. */
  void start() {
    height = statusBarHeight();
    bar = new StatusBarView(context);
    simId(bar, "status_bar");
    barParams =
        new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    barParams.gravity = Gravity.TOP | Gravity.START;
    barParams.setFitInsetsTypes(0);
    barParams.setTitle("StatusBar");
    windows.addView(bar, barParams);
    stream.onChange(this::streamChanged);
    navigationBar.start();
    lockScreen.start();
    refresh();
  }

  /** One cycle: the bar follows the app on top; once a second, the clock, radios and battery. */
  void cycle() {
    if (bar == null) return;
    long now = SystemClock.uptimeMillis();
    if (headsUp != null && now >= headsUpUntil) hideHeadsUp();
    followApp();
    navigationBar.cycle();
    lockScreen.cycle();
    keyboard.cycle();
    if (now - lastTick < 1000) return;
    lastTick = now;
    refresh();
  }

  /** Whether the shade is open over the app or the home screen. */
  boolean shadeOpen() {
    return shade != null;
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    JSONObject owned = navigationBar.command(name, c);
    if (owned == null) owned = lockScreen.command(name, c);
    if (owned == null) owned = keyboard.command(name, c);
    if (owned != null) return owned;
    switch (name) {
      case "notifications":
        {
          String op = c.optString("op", "");
          switch (op) {
            case "open":
              openShade();
              return describe();
            case "close":
              closeShade();
              return describe();
            case "clear":
              return stream.clearAll();
            default:
              // The list: the stream's.
              return null;
          }
        }
      case "notification":
        {
          String op = c.optString("op", "");
          if (!op.equals("expand") && !op.equals("collapse")) return null;
          Object value = c.get("key");
          String key =
              value instanceof Number
                  ? Integer.toString(((Number) value).intValue())
                  : value.toString();
          NotificationStream.Item item = stream.item(key);
          expanded.put(item.key, op.equals("expand"));
          rebuildShade();
          return new Protocol.Json()
              .put("key", item.key)
              .put("expanded", op.equals("expand"))
              .object();
        }
      case "quickSettings":
        {
          if (c.has("wifi") || c.has("airplane") || c.has("data")) {
            QuickSettings.radios(
                c.has("wifi") ? c.getBoolean("wifi") : null,
                c.has("airplane") ? c.getBoolean("airplane") : null,
                c.has("data") ? c.getBoolean("data") : null);
          }
          openShade();
          refresh();
          JSONObject state = QuickSettings.state(app);
          state.put("surface", surface());
          return state;
        }
      case "statusBar":
        return describe();
      case "home":
        // HOME closes the shade on its way; the home screen does the rest, unless locked.
        if (home()) return describe();
        return null;
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    switch (name) {
      case "notifications":
      case "quickSettings":
      case "navigationBar":
      case "lockScreen":
      case "keyboard":
        return true;
      default:
        return false;
    }
  }

  /** Back while the shade is open closes it, before any app hears it; else false. */
  boolean back() {
    if (shade != null) {
      closeShade();
      return true;
    }
    if (keyboard.back()) return true;
    return lockScreen.back();
  }

  /**
   * HOME: the shade and a heads-up card close, as the system collapses them. True when the lock
   * screen keeps it from going further: locked, HOME goes nowhere.
   */
  boolean home() {
    closeShade();
    hideHeadsUp();
    return lockScreen.showing();
  }

  /** What the default display shows, the shade over everything else. */
  String surface() {
    if (shade != null) return "notificationShade";
    if (lockScreen.showing()) return "lockScreen";
    HomeScreen home = HomeScreen.current;
    return home == null ? "app" : home.surface();
  }

  JSONObject describe() {
    JSONArray icons = new JSONArray();
    for (NotificationStream.Item item : barItems()) icons.put(item.key);
    JSONObject quick = QuickSettings.state(app);
    Protocol.Json status =
        new Protocol.Json()
            .put("visible", bar != null && bar.getVisibility() == View.VISIBLE)
            .put("heightPx", height)
            .put("time", bar == null ? null : bar.time)
            .put("notifications", icons)
            .put("wifi", "wifi".equals(quick.optString("default")))
            .put("cellular", quick.optBoolean("cellular") && !quick.optBoolean("airplane"))
            .put("airplane", quick.optBoolean("airplane"))
            .put("battery", quick.optInt("battery"))
            .put("charging", quick.optBoolean("charging"))
            .put("darkIcons", bar != null && bar.darkIcons)
            .put(
                "background",
                bar == null || Color.alpha(bar.background) == 0
                    ? null
                    : String.format("#%08x", bar.background));
    return new Protocol.Json()
        .put("surface", surface())
        .put("statusBar", status.object())
        .put("shade", shade != null)
        .put("headsUp", headsUp == null ? null : headsUpKey)
        .put("navigationBar", navigationBar.describe())
        .put("lockScreen", lockScreen.describe())
        .put("keyboard", keyboard.describe())
        .object();
  }

  /**
   * A copy of the status bar's row, for the top of a system window that covers the bar; dark icons
   * on a light background. Kept up to date until {@link #dropRow}.
   */
  View statusRow(boolean darkIcons) {
    StatusBarView row = new StatusBarView(context);
    row.darkIcons = darkIcons;
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    rows.add(row);
    refresh();
    return row;
  }

  void dropRow(View row) {
    rows.remove(row);
  }

  int statusBarHeightPx() {
    return height;
  }

  Context context() {
    return context;
  }

  WindowManager windows() {
    return windows;
  }

  // ---------------------------------------------------------------------------------------------
  // The status bar

  /**
   * Switches on Robolectric's status bar for the default display, which insets the app's windows by
   * its height as a device's would; returns its {@code SystemUi.StatusBar}, or null on a
   * Robolectric without one.
   */
  static Object robolectricStatusBar() {
    try {
      ClassLoader loader = PhoneSystemUi.class.getClassLoader();
      Class<?> systemUi = Class.forName("org.robolectric.shadows.SystemUi", true, loader);
      Class<?> behavior =
          Class.forName("org.robolectric.shadows.SystemUi$StatusBarBehavior", true, loader);
      Object ui = ReflectionHelpers.callStaticMethod(systemUi, "systemUiForDefaultDisplay");
      ReflectionHelpers.callInstanceMethod(
          ui,
          "setStatusBarBehavior",
          ClassParameter.from(
              behavior, ReflectionHelpers.getStaticField(systemUi, "STANDARD_STATUS_BAR")));
      return ReflectionHelpers.callInstanceMethod(ui, "getStatusBar");
    } catch (ClassNotFoundException | RuntimeException e) {
      return null;
    }
  }

  private int statusBarHeight() {
    if (insets != null) {
      try {
        int size = ReflectionHelpers.callInstanceMethod(insets, "getSize");
        if (size > 0) return size;
      } catch (RuntimeException e) {
        // The standard height below.
      }
    }
    return dp(STATUS_BAR_DP);
  }

  /** Whether the app has the status bar showing (it hides it for immersive mode). */
  private boolean barShown() {
    if (insets == null) return true;
    try {
      return ReflectionHelpers.callInstanceMethod(insets, "isVisible");
    } catch (RuntimeException e) {
      return true;
    }
  }

  /**
   * Takes the look of the app's window on top: its status bar colour behind the bar when the window
   * is laid out below it (it draws itself behind the bar when edge to edge), light or dark icons as
   * it asked, and no bar while it hides it.
   */
  private void followApp() {
    int background = Color.TRANSPARENT;
    boolean darkIcons = false;
    Navigator.Entry top = navigator.top(android.view.Display.DEFAULT_DISPLAY);
    Window window = top == null ? null : top.activity().getWindow();
    View decor = window == null ? null : window.peekDecorView();
    if (decor != null && decor.isAttachedToWindow()) {
      int[] xy = new int[2];
      decor.getLocationOnScreen(xy);
      if (xy[1] >= height) {
        @SuppressWarnings("deprecation")
        int color = window.getStatusBarColor();
        background = Color.alpha(color) == 0 ? Color.BLACK : color | 0xFF000000;
      }
      darkIcons = lightStatusBar(window, decor);
    }
    boolean shown = barShown();
    if (background != bar.background || darkIcons != bar.darkIcons) {
      bar.background = background;
      bar.darkIcons = darkIcons;
      bar.invalidate();
    }
    if (shown != (bar.getVisibility() == View.VISIBLE)) {
      bar.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
      // A hidden bar takes no touches: the app's own reach the top of the screen.
      if (shown) {
        barParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
      } else {
        barParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
      }
      windows.updateViewLayout(bar, barParams);
    }
  }

  @SuppressWarnings("deprecation")
  private static boolean lightStatusBar(Window window, View decor) {
    WindowInsetsController controller = window.getInsetsController();
    if (controller != null
        && (controller.getSystemBarsAppearance()
                & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            != 0) {
      return true;
    }
    return (decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0;
  }

  /** The clock, the radios, the battery and the notification icons, redrawn. */
  private void refresh() {
    if (bar == null) return;
    JSONObject quick = QuickSettings.state(app);
    String time = clock();
    List<Drawable> icons = new ArrayList<>();
    StringBuilder description = new StringBuilder(time);
    List<NotificationStream.Item> items = barItems();
    for (NotificationStream.Item item : items) {
      Drawable icon = load(item.notification.getSmallIcon());
      if (icon != null) icons.add(icon.mutate());
    }
    if (!items.isEmpty()) {
      description.append(", ").append(items.size()).append(" notification");
      if (items.size() > 1) description.append('s');
    }
    List<StatusBarView> views = new ArrayList<>(rows);
    views.add(bar);
    for (StatusBarView view : views) {
      view.time = time;
      view.icons = icons;
      view.airplane = quick.optBoolean("airplane");
      view.wifi = "wifi".equals(quick.optString("default"));
      view.cellular = quick.optBoolean("cellular") && !view.airplane;
      view.battery = quick.optInt("battery", 100);
      view.charging = quick.optBoolean("charging");
      view.invalidate();
    }
    if (bar.airplane) description.append(", airplane mode");
    if (bar.wifi) description.append(", Wi-Fi");
    if (bar.cellular) description.append(", mobile data");
    description.append(", battery ").append(bar.battery).append('%');
    if (bar.charging) description.append(" charging");
    bar.setContentDescription(description);
    if (shadeClock != null) shadeClock.setText(time);
  }

  /**
   * The notifications the bar has an icon for, in the shade's order: those that interrupt first,
   * then the rest; the newest first within each, as SystemUI ranks them.
   */
  List<NotificationStream.Item> barItems() {
    List<NotificationStream.Item> items = new ArrayList<>();
    for (NotificationStream.Item item : stream.shown()) {
      if (!hiddenInShade(item)) items.add(item);
    }
    // Robolectric posts within one task at the same instant: then the later `when`, then the
    // higher id, so the order is at least stable.
    items.sort(
        java.util.Comparator.comparing((NotificationStream.Item item) -> !stream.headsUp(item))
            .thenComparing(
                (NotificationStream.Item item) -> item.postTime,
                java.util.Comparator.reverseOrder())
            .thenComparing(
                (NotificationStream.Item item) -> item.notification.when,
                java.util.Comparator.reverseOrder())
            .thenComparing(
                (NotificationStream.Item item) -> item.id, java.util.Comparator.reverseOrder()));
    return items;
  }

  String clock() {
    return DateFormat.format(DateFormat.is24HourFormat(app) ? "HH:mm" : "h:mm", new Date())
        .toString();
  }

  /** The bar itself, drawn on a canvas: clock and notification icons left, system icons right. */
  private final class StatusBarView extends View {
    String time = "";
    List<Drawable> icons = new ArrayList<>();
    boolean wifi;
    boolean cellular;
    boolean airplane;
    int battery = 100;
    boolean charging;
    int background = Color.TRANSPARENT;
    boolean darkIcons;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int slop;
    private float downY = Float.NaN;

    StatusBarView(Context context) {
      super(context);
      slop = ViewConfiguration.get(context).getScaledTouchSlop();
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (Color.alpha(background) != 0) canvas.drawColor(background);
      int fg = darkIcons ? 0xFF1F1F1F : Color.WHITE;
      float h = getHeight();
      float pad = dp(16);
      float icon = dp(16);
      float top = (h - icon) / 2f;
      paint.setColor(fg);
      paint.setStyle(Paint.Style.FILL);
      paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      paint.setTextSize(
          TypedValue.applyDimension(
              TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
      Paint.FontMetrics m = paint.getFontMetrics();
      float baseline = h / 2f - (m.ascent + m.descent) / 2f;
      canvas.drawText(time, pad, baseline, paint);

      float x = pad + paint.measureText(time) + dp(10);
      int shown = Math.min(icons.size(), 4);
      for (int i = 0; i < shown; i++) {
        Drawable d = icons.get(i);
        d.setTint(fg);
        d.setBounds(Math.round(x), Math.round(top), Math.round(x + icon), Math.round(top + icon));
        d.draw(canvas);
        x += icon + dp(6);
      }
      if (icons.size() > shown) canvas.drawCircle(x + dp(2), h / 2f, dp(2), paint);

      float right = getWidth() - pad;
      String percent = battery + "%";
      paint.setTextSize(
          TypedValue.applyDimension(
              TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
      m = paint.getFontMetrics();
      float percentWidth = paint.measureText(percent);
      canvas.drawText(percent, right - percentWidth, h / 2f - (m.ascent + m.descent) / 2f, paint);
      right -= percentWidth + dp(4);
      drawBattery(canvas, right - dp(9), top, fg);
      right -= dp(9) + dp(8);
      if (airplane) {
        drawAirplane(canvas, right - icon, top, icon, fg);
        right -= icon + dp(6);
      }
      if (cellular) {
        drawSignal(canvas, right - icon, top, icon, fg);
        right -= icon + dp(6);
      }
      if (wifi) drawWifi(canvas, right - icon, top, icon, fg);
    }

    private void drawBattery(Canvas canvas, float left, float top, int fg) {
      float w = dp(9);
      float h = dp(15);
      float cap = dp(1.5f);
      float t = top + (dp(16) - h) / 2f + cap;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(fg);
      canvas.drawRect(left + w * 0.3f, t - cap, left + w * 0.7f, t, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(1.2f));
      RectF body = new RectF(left, t, left + w, t + h - cap);
      canvas.drawRoundRect(body, dp(1.5f), dp(1.5f), paint);
      paint.setStyle(Paint.Style.FILL);
      float inset = dp(2);
      float full = body.height() - 2 * inset;
      float level = full * Math.max(0, Math.min(100, battery)) / 100f;
      if (battery <= 15 && !charging) paint.setColor(0xFFF28B82);
      if (charging) paint.setColor(darkIcons ? 0xFF146C2E : 0xFF6DD58C);
      canvas.drawRect(
          body.left + inset,
          body.bottom - inset - level,
          body.right - inset,
          body.bottom - inset,
          paint);
      paint.setColor(fg);
    }

    private void drawWifi(Canvas canvas, float left, float top, float size, int fg) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(fg);
      RectF fan = new RectF(left, top + size * 0.15f, left + size, top + size * 1.85f);
      canvas.drawArc(fan, 225, 90, true, paint);
    }

    private void drawSignal(Canvas canvas, float left, float top, float size, int fg) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(fg);
      Path path = new Path();
      path.moveTo(left + size, top + size * 0.1f);
      path.lineTo(left + size, top + size);
      path.lineTo(left + size * 0.1f, top + size);
      path.close();
      canvas.drawPath(path, paint);
    }

    private void drawAirplane(Canvas canvas, float left, float top, float size, int fg) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(fg);
      float cx = left + size / 2f;
      float body = size * 0.09f;
      canvas.drawRoundRect(new RectF(cx - body, top, cx + body, top + size), body, body, paint);
      Path wings = new Path();
      wings.moveTo(cx, top + size * 0.3f);
      wings.lineTo(left, top + size * 0.62f);
      wings.lineTo(left + size, top + size * 0.62f);
      wings.close();
      canvas.drawPath(wings, paint);
      Path tail = new Path();
      tail.moveTo(cx, top + size * 0.78f);
      tail.lineTo(cx - size * 0.25f, top + size);
      tail.lineTo(cx + size * 0.25f, top + size);
      tail.close();
      canvas.drawPath(tail, paint);
    }

    /** A drag down from the bar opens the shade, as a device's does. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downY = event.getY();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (!Float.isNaN(downY) && event.getY() - downY > slop) {
            downY = Float.NaN;
            openShade();
          }
          return true;
        default:
          downY = Float.NaN;
          return true;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The shade

  void openShade() {
    hideHeadsUp();
    if (shade != null) {
      rebuildShade();
      return;
    }
    shade = new ShadeRoot(context);
    shade.setBackgroundColor(night() ? 0xFF121212 : 0xFFE9EEF6);
    shadeList = new LinearLayout(context);
    shadeList.setOrientation(LinearLayout.VERTICAL);
    shadeList.setPadding(dp(12), dp(8), dp(12), dp(16));
    ScrollView scroll = new ScrollView(context);
    scroll.addView(shadeList);
    simId(scroll, "notification_shade");
    shade.scroll = scroll;
    // Below the status bar's row, so the list scrolls out of sight under it.
    FrameLayout.LayoutParams scrollParams =
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    scrollParams.topMargin = height;
    shade.addView(scroll, scrollParams);
    shadeBar = statusRow(!night());
    shade.addView(
        shadeBar,
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.TOP));
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            TYPE_SHADE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.START;
    params.setFitInsetsTypes(0);
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    params.setTitle("NotificationShade");
    windows.addView(shade, params);
    refresh();
    rebuildShade();
    protocol.event("surface", new Protocol.Json().put("surface", surface()).object());
  }

  void closeShade() {
    if (shade == null) return;
    windows.removeViewImmediate(shade);
    shade = null;
    dropRow(shadeBar);
    shadeBar = null;
    shadeList = null;
    shadeClock = null;
    replying = null;
    protocol.event("surface", new Protocol.Json().put("surface", surface()).object());
  }

  private void rebuildShade() {
    if (shadeList == null) return;
    shadeList.removeAllViews();
    shadeList.addView(header());
    shadeList.addView(tiles());
    List<NotificationStream.Item> items = new ArrayList<>();
    for (NotificationStream.Item item : barItems()) {
      // A player's notification is the media player under the tiles, as Android 13+ shows it.
      if (isMedia(item)) {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(8);
        shadeList.addView(mediaPlayer(item), lp);
      } else {
        items.add(item);
      }
    }
    for (int i = 0; i < items.size(); i++) {
      NotificationStream.Item item = items.get(i);
      Boolean open = expanded.get(item.key);
      View card = card(item, open != null ? open : i == 0, false);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(i == 0 ? 16 : 4);
      shadeList.addView(card, lp);
    }
    if (items.isEmpty()) {
      TextView empty = text("No notifications", 15, secondaryText());
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, dp(24), 0, dp(24));
      simId(empty, "notifications_empty");
      shadeList.addView(empty, wide());
      return;
    }
    boolean clearable = false;
    for (NotificationStream.Item item : items) clearable |= dismissible(item);
    if (!clearable) return;
    TextView clear = pill("Clear all", false);
    simId(clear, "notifications_clear_all");
    clear.setOnClickListener(v -> run(stream::clearAll));
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.gravity = Gravity.END;
    lp.topMargin = dp(12);
    shadeList.addView(clear, lp);
  }

  private View header() {
    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.BOTTOM);
    header.setPadding(dp(8), dp(8), dp(8), dp(12));
    shadeClock = text(clock(), 34, primaryText());
    simId(shadeClock, "shade_clock");
    header.addView(shadeClock);
    TextView date =
        text(DateFormat.format("EEE, MMM d", new Date()).toString(), 14, secondaryText());
    date.setPadding(dp(12), 0, 0, dp(6));
    header.addView(date);
    return header;
  }

  private View tiles() {
    JSONObject state = QuickSettings.state(app);
    LinearLayout grid = new LinearLayout(context);
    grid.setOrientation(LinearLayout.VERTICAL);
    boolean wifi = state.optBoolean("wifi");
    boolean data = state.optBoolean("data");
    boolean airplane = state.optBoolean("airplane");
    int battery = state.optInt("battery", 100);
    boolean charging = state.optBoolean("charging");
    grid.addView(
        row(
            tile(
                "Internet",
                wifi ? "Wi-Fi on" : "Wi-Fi off",
                wifi,
                "qs_wifi",
                () -> QuickSettings.radios(!wifi, null, null)),
            tile(
                "Mobile data",
                data ? "On" : "Off",
                data && !airplane,
                "qs_data",
                () -> QuickSettings.radios(null, null, !data))));
    grid.addView(
        row(
            tile(
                "Airplane mode",
                airplane ? "On" : "Off",
                airplane,
                "qs_airplane",
                () -> QuickSettings.radios(null, !airplane, null)),
            tile(
                "Battery",
                battery + "%" + (charging ? " · Charging" : ""),
                false,
                "qs_battery",
                null)));
    return grid;
  }

  private LinearLayout row(View left, View right) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(64), 1f);
    l.setMarginEnd(dp(4));
    LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, dp(64), 1f);
    r.setMarginStart(dp(4));
    row.addView(left, l);
    row.addView(right, r);
    row.setPadding(0, dp(4), 0, dp(4));
    return row;
  }

  private View tile(String label, String value, boolean on, String id, Runnable toggle) {
    LinearLayout tile = new LinearLayout(context);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setPadding(dp(16), 0, dp(12), 0);
    int fill = on ? 0xFFA8C7FA : night() ? 0xFF2D2F31 : 0xFFFFFFFF;
    int fg = on ? 0xFF062E6F : primaryText();
    tile.setBackground(rounded(fill, 20));
    TextView name = text(label, 14, fg);
    name.setTypeface(Typeface.DEFAULT_BOLD);
    tile.addView(name);
    tile.addView(text(value, 12, on ? 0xFF062E6F : secondaryText()));
    simId(tile, id);
    tile.setContentDescription(label + ": " + value);
    if (toggle != null) {
      tile.setClickable(true);
      tile.setOnClickListener(
          v -> {
            toggle.run();
            refresh();
            rebuildShade();
          });
    }
    return tile;
  }

  /** The shade's window: Back closes it, as does a swipe up or a tap below the list. */
  private final class ShadeRoot extends FrameLayout {
    ScrollView scroll;
    private final int slop;
    private float downX;
    private float downY;
    private boolean moved;

    ShadeRoot(Context context) {
      super(context);
      slop = ViewConfiguration.get(context).getScaledTouchSlop();
      setFocusable(true);
      setFocusableInTouchMode(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
      if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
        if (event.getAction() == KeyEvent.ACTION_UP) closeShade();
        return true;
      }
      return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          moved = false;
          return false;
        case MotionEvent.ACTION_MOVE:
          float dy = event.getY() - downY;
          // Up, mostly vertical, with nothing further to scroll: the shade goes back up.
          return -dy > slop * 3
              && Math.abs(dy) > Math.abs(event.getX() - downX)
              && (scroll == null || !scroll.canScrollVertically(1));
        default:
          return false;
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          moved = false;
          return true;
        case MotionEvent.ACTION_MOVE:
          if (Math.abs(event.getY() - downY) > slop || Math.abs(event.getX() - downX) > slop) {
            moved = true;
          }
          if (downY - event.getY() > slop * 3) closeShade();
          return true;
        case MotionEvent.ACTION_UP:
          // A tap on the scrim below the list.
          if (!moved) closeShade();
          return true;
        default:
          return true;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Notifications

  /** Whether [item] is a player's: {@code MediaStyle}, platform or AndroidX's. */
  static boolean isMedia(NotificationStream.Item item) {
    String template =
        item.notification.extras == null
            ? null
            : item.notification.extras.getString(Notification.EXTRA_TEMPLATE);
    return template != null && template.contains("MediaStyle");
  }

  /**
   * The media player a {@code MediaStyle} notification shows as: its art (the large icon) behind
   * the title and artist, and its actions as the player's buttons, the compact ones first. A tap
   * opens the player's content intent; a button sends its action.
   */
  private View mediaPlayer(NotificationStream.Item item) {
    Notification n = item.notification;
    FrameLayout card = new FrameLayout(context);
    card.setBackground(rounded(0xFF3C4043, 24));
    card.setClipToOutline(true);
    simId(card, "media_player");
    card.setContentDescription(item.key);
    card.setOnClickListener(v -> tap(item));
    Drawable art = load(n.getLargeIcon());
    if (art != null) {
      android.widget.ImageView image = new android.widget.ImageView(context);
      image.setImageDrawable(art);
      image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
      card.addView(image, new FrameLayout.LayoutParams(-1, -1));
      View scrim = new View(context);
      scrim.setBackgroundColor(0x80000000);
      card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(16), dp(16), dp(16), dp(12));
    TextView app = text(appLabel(), 12, 0xCCFFFFFF);
    column.addView(app);
    String title = NotificationStream.text(n.extras, Notification.EXTRA_TITLE);
    String artist = NotificationStream.text(n.extras, Notification.EXTRA_TEXT);
    TextView titleView = text(title == null ? "" : title, 20, Color.WHITE);
    titleView.setTypeface(Typeface.DEFAULT_BOLD);
    titleView.setPadding(0, dp(28), 0, 0);
    simId(titleView, "media_title");
    column.addView(titleView);
    if (artist != null) column.addView(text(artist, 14, 0xE6FFFFFF));
    LinearLayout buttons = new LinearLayout(context);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    buttons.setPadding(0, dp(12), 0, 0);
    List<Notification.Action> actions = NotificationStream.actions(n);
    for (int i : mediaOrder(n, actions.size())) {
      Notification.Action action = actions.get(i);
      android.widget.ImageView button = new android.widget.ImageView(context);
      Drawable icon = load(action.getIcon());
      if (icon != null) {
        icon = icon.mutate();
        icon.setTint(Color.WHITE);
        button.setImageDrawable(icon);
      }
      button.setPadding(dp(8), dp(8), dp(8), dp(8));
      button.setContentDescription(item.key + "/" + i);
      simId(button, "media_action");
      button.setClickable(true);
      int index = i;
      button.setOnClickListener(v -> run(() -> collapseFor(stream.action(item, index, null))));
      buttons.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }
    column.addView(buttons, wide());
    card.addView(column, new FrameLayout.LayoutParams(-1, -2));
    return card;
  }

  /** The actions' order in the player: the compact view's first, then the rest. */
  private static List<Integer> mediaOrder(Notification n, int count) {
    List<Integer> order = new ArrayList<>();
    int[] compact =
        n.extras == null ? null : n.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS);
    if (compact != null) {
      for (int i : compact) if (i >= 0 && i < count && !order.contains(i)) order.add(i);
    }
    for (int i = 0; i < count; i++) if (!order.contains(i)) order.add(i);
    return order;
  }

  private String appLabel() {
    return app.getApplicationInfo().loadLabel(app.getPackageManager()).toString();
  }

  /** A group summary whose children are listed is not, as the shade shows a group. */
  private boolean hiddenInShade(NotificationStream.Item item) {
    return NotificationStream.isSummary(item) && !stream.children(item).isEmpty();
  }

  private static boolean dismissible(NotificationStream.Item item) {
    return (item.notification.flags
            & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR))
        == 0;
  }

  /**
   * One notification as the shade or a heads-up card shows it: its own {@code RemoteViews},
   * collapsed, expanded or heads-up, on a rounded card that a tap opens and a sideways swipe
   * dismisses; with the reply field under it while one of its reply actions is being answered.
   */
  View card(NotificationStream.Item item, boolean open, boolean headsUp) {
    Card card = new Card(context, item, headsUp);
    card.setBackground(rounded(night() ? 0xFF1F1F1F : 0xFFFFFFFF, 20));
    card.setClipToOutline(true);
    simId(card, headsUp ? "heads_up" : "notification_card");
    card.setContentDescription(item.key);
    card.setOnClickListener(v -> tap(item));
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    View content = inflate(item, open, headsUp, column);
    column.addView(content, wide());
    if (!headsUp && replying != null && replying.startsWith(item.key + "/")) {
      int index = Integer.parseInt(replying.substring(item.key.length() + 1));
      column.addView(replyField(item, index), wide());
    }
    card.addView(column);
    return card;
  }

  /** [item]'s content view as its template (or the app's custom view) inflates, or a fallback. */
  @SuppressWarnings("deprecation")
  private View inflate(
      NotificationStream.Item item, boolean open, boolean headsUp, ViewGroup parent) {
    try {
      Notification.Builder builder =
          Notification.Builder.recoverBuilder(context, item.notification);
      RemoteViews views = null;
      boolean big = false;
      if (headsUp) {
        views = builder.createHeadsUpContentView();
      } else if (open) {
        views = builder.createBigContentView();
        big = views != null;
      }
      if (views == null) views = builder.createContentView();
      View view = apply(views, parent, interaction(item));
      wireExpand(view, item, open, headsUp);
      tagActions(view, item);
      // Measured as SystemUI's NotificationContentView does: a collapsed template at the fixed
      // collapsed height (a custom view at most that), the others at most their maximum.
      int height;
      if (headsUp) {
        height =
            MeasureSpec.makeMeasureSpec(
                dimen("notification_max_heads_up_height", 148), MeasureSpec.AT_MOST);
      } else if (big) {
        height =
            MeasureSpec.makeMeasureSpec(dimen("notification_max_height", 256), MeasureSpec.AT_MOST);
      } else {
        height =
            MeasureSpec.makeMeasureSpec(
                dimen("notification_min_height", 64),
                item.notification.contentView == null ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
      }
      return new TemplateFrame(context, view, height);
    } catch (RuntimeException e) {
      protocol.event(
          "error",
          new Protocol.Json()
              .put("kind", "notificationView")
              .put("key", item.key)
              .put("message", String.valueOf(e))
              .object());
      String title = NotificationStream.text(item.notification.extras, Notification.EXTRA_TITLE);
      String body = NotificationStream.text(item.notification.extras, Notification.EXTRA_TEXT);
      TextView fallback =
          text((title == null ? "" : title) + (body == null ? "" : "\n" + body), 14, primaryText());
      fallback.setPadding(dp(16), dp(12), dp(16), dp(12));
      return fallback;
    }
  }

  /** A framework dimension SystemUI sizes notifications by, else [fallbackDp]. */
  private int dimen(String name, int fallbackDp) {
    int id = Resources.getSystem().getIdentifier(name, "dimen", "android");
    return id == 0 ? dp(fallbackDp) : Resources.getSystem().getDimensionPixelSize(id);
  }

  /** Holds a notification's content view, measuring it with the height spec SystemUI would. */
  private static final class TemplateFrame extends FrameLayout {
    private final int heightSpec;

    TemplateFrame(Context context, View content, int heightSpec) {
      super(context);
      this.heightSpec = heightSpec;
      addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      View content = getChildAt(0);
      content.measure(
          MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
          heightSpec);
      setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), content.getMeasuredHeight());
    }
  }

  /** {@code RemoteViews.apply} with the (hidden) interaction handler SystemUI passes. */
  private View apply(RemoteViews views, ViewGroup parent, Object handler) {
    try {
      return ReflectionHelpers.callInstanceMethod(
          views,
          "apply",
          ClassParameter.from(Context.class, context),
          ClassParameter.from(ViewGroup.class, parent),
          ClassParameter.from(WidgetHost.interactionHandlerClass(), handler));
    } catch (RuntimeException e) {
      if (!(e.getCause() instanceof NoSuchMethodException)
          && !(e instanceof IllegalStateException)) {
        throw e;
      }
      return views.apply(context, parent);
    }
  }

  /** The template's expand button opens and closes the notification, as SystemUI binds it. */
  private void wireExpand(View view, NotificationStream.Item item, boolean open, boolean headsUp) {
    int id = Resources.getSystem().getIdentifier("expand_button", "id", "android");
    View button = id == 0 ? null : view.findViewById(id);
    if (button == null || headsUp) return;
    simId(button, "notification_expand");
    button.setContentDescription(item.key);
    button.setOnClickListener(
        v -> {
          expanded.put(item.key, !open);
          rebuildShade();
        });
  }

  /** The template's action buttons, by index, as {@code notification_action} {@code key/index}. */
  private void tagActions(View view, NotificationStream.Item item) {
    int id = Resources.getSystem().getIdentifier("actions", "id", "android");
    View container = id == 0 ? null : view.findViewById(id);
    if (!(container instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) container;
    List<View> buttons = new ArrayList<>();
    for (int i = 0; i < group.getChildCount(); i++) {
      if (group.getChildAt(i).getVisibility() != View.GONE) buttons.add(group.getChildAt(i));
    }
    if (buttons.size() != NotificationStream.actions(item.notification).size()) return;
    for (int i = 0; i < buttons.size(); i++) {
      simId(buttons.get(i), "notification_action");
      // The label stays the button's text, which find matches on.
      buttons.get(i).setContentDescription(item.key + "/" + i);
    }
  }

  /**
   * The {@code RemoteViews.InteractionHandler} SystemUI installs on a notification: a tap on one of
   * its actions presses that action (a reply action opens the reply field), and any other {@code
   * PendingIntent} in the views is sent.
   */
  private Object interaction(NotificationStream.Item item) {
    Class<?> type = WidgetHost.interactionHandlerClass();
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return WidgetHost.objectMethod(proxy, method, args);
          }
          View view = (View) args[0];
          PendingIntent pending = (PendingIntent) args[1];
          Intent fillIn = null;
          try {
            android.util.Pair<?, ?> options =
                ReflectionHelpers.callInstanceMethod(
                    args[2], "getLaunchOptions", ClassParameter.from(View.class, view));
            fillIn = (Intent) options.first;
          } catch (RuntimeException e) {
            // No launch options: send the intent as it is.
          }
          List<Notification.Action> actions = NotificationStream.actions(item.notification);
          for (int i = 0; i < actions.size(); i++) {
            if (pending.equals(actions.get(i).actionIntent)) {
              press(item, i);
              return true;
            }
          }
          if (pending.equals(item.notification.contentIntent)) {
            tap(item);
            return true;
          }
          Intent sentFillIn = fillIn;
          run(() -> collapseFor(stream.send(pending, sentFillIn)));
          return true;
        };
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  void tap(NotificationStream.Item item) {
    run(() -> collapseFor(stream.tap(item)));
  }

  /**
   * Presses action [index]; one that takes a reply opens the reply field under the card instead.
   */
  private void press(NotificationStream.Item item, int index) {
    Notification.Action action = NotificationStream.actions(item.notification).get(index);
    RemoteInput[] inputs = action.getRemoteInputs();
    if (inputs != null && inputs.length > 0) {
      if (shade == null) {
        // From a heads-up card: the reply field is in the shade.
        expanded.put(item.key, true);
        openShade();
      }
      replying = item.key + "/" + index;
      rebuildShade();
      return;
    }
    run(() -> collapseFor(stream.action(item, index, null)));
  }

  /**
   * The inline reply field SystemUI shows for a reply action: the app's suggested replies, a text
   * field labelled with the input's label, and send.
   */
  private View replyField(NotificationStream.Item item, int index) {
    Notification.Action action = NotificationStream.actions(item.notification).get(index);
    RemoteInput[] inputs = action.getRemoteInputs();
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(16), 0, dp(16), dp(12));
    LinearLayout choices = new LinearLayout(context);
    choices.setOrientation(LinearLayout.HORIZONTAL);
    CharSequence label = null;
    for (RemoteInput input : inputs) {
      if (label == null) label = input.getLabel();
      CharSequence[] options = input.getChoices();
      if (options == null) continue;
      for (CharSequence choice : options) {
        TextView chip = pill(choice.toString(), false);
        simId(chip, "notification_reply");
        chip.setContentDescription(item.key + "/" + index + "/" + choice);
        chip.setOnClickListener(v -> reply(item, index, choice.toString()));
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        choices.addView(chip, lp);
      }
    }
    if (choices.getChildCount() > 0) {
      choices.setPadding(0, 0, 0, dp(8));
      column.addView(choices);
    }
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackground(rounded(night() ? 0xFF2D2F31 : 0xFFE9EEF6, 24));
    row.setPadding(dp(16), dp(2), dp(4), dp(2));
    EditText field = new EditText(context);
    field.setHint(label == null ? "Reply" : label);
    field.setTextColor(primaryText());
    field.setHintTextColor(secondaryText());
    field.setBackground(null);
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    simId(field, "notification_reply_text");
    field.setContentDescription(item.key + "/" + index);
    row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    TextView send = pill("Send", true);
    simId(send, "notification_reply_send");
    send.setContentDescription(item.key + "/" + index);
    send.setOnClickListener(v -> reply(item, index, field.getText().toString()));
    row.addView(send);
    column.addView(row);
    field.requestFocus();
    return column;
  }

  private void reply(NotificationStream.Item item, int index, String text) {
    replying = null;
    run(() -> stream.action(item, index, text));
    rebuildShade();
  }

  /** An activity started from the shade or a heads-up card closes them, as on a device. */
  private JSONObject collapseFor(JSONObject result) {
    JSONObject sent = result.optJSONObject("sent");
    if ("activity".equals((sent == null ? result : sent).optString("kind"))) {
      closeShade();
      hideHeadsUp();
    }
    return result;
  }

  /** A notification card: a sideways swipe dismisses it; on a heads-up card, up puts it away. */
  private final class Card extends FrameLayout {
    private final NotificationStream.Item item;
    private final boolean headsUp;
    private final int slop;
    private float downX;
    private float downY;
    private boolean dragging;

    Card(Context context, NotificationStream.Item item, boolean headsUp) {
      super(context);
      this.item = item;
      this.headsUp = headsUp;
      this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getRawX();
          downY = event.getRawY();
          dragging = false;
          return false;
        case MotionEvent.ACTION_MOVE:
          float dx = event.getRawX() - downX;
          float dy = event.getRawY() - downY;
          if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) dragging = true;
          if (headsUp && -dy > slop && Math.abs(dy) > Math.abs(dx)) dragging = true;
          if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
          return dragging;
        default:
          return false;
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (!dragging && event.getActionMasked() != MotionEvent.ACTION_MOVE) {
        return super.onTouchEvent(event);
      }
      float dx = event.getRawX() - downX;
      float dy = event.getRawY() - downY;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_MOVE:
          if (!dragging) {
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
              dragging = true;
            } else {
              return super.onTouchEvent(event);
            }
          }
          if (Math.abs(dx) >= Math.abs(dy)) setTranslationX(dismissible(item) ? dx : dx / 4f);
          return true;
        case MotionEvent.ACTION_UP:
          dragging = false;
          setTranslationX(0);
          if (Math.abs(dx) > getWidth() / 3f && Math.abs(dx) > Math.abs(dy)) {
            if (headsUp) hideHeadsUp();
            run(() -> stream.dismiss(item));
          } else if (headsUp && -dy > slop) {
            hideHeadsUp();
          }
          return true;
        default:
          dragging = false;
          setTranslationX(0);
          return true;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Heads-up

  private void streamChanged() {
    NotificationStream.Item interrupting = null;
    for (NotificationStream.Item item : stream.shown()) {
      if (seen.add(item.key + "@" + item.postTime)
          && stream.headsUp(item)
          && !hiddenInShade(item)
          && interrupting == null) {
        interrupting = item;
      }
    }
    refresh();
    if (shade != null) {
      if (replying != null && !stillActive(replying.substring(0, replying.lastIndexOf('/')))) {
        replying = null;
      }
      rebuildShade();
    } else if (interrupting != null) {
      showHeadsUp(interrupting);
    } else if (headsUp != null && !stillActive(headsUpKey)) {
      hideHeadsUp();
    }
  }

  private boolean stillActive(String key) {
    for (NotificationStream.Item item : stream.items()) if (item.key.equals(key)) return true;
    return false;
  }

  private void showHeadsUp(NotificationStream.Item item) {
    hideHeadsUp();
    headsUp = new FrameLayout(context);
    headsUp.setPadding(dp(8), dp(4), dp(8), dp(4));
    headsUp.addView(card(item, false, true), wide());
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            TYPE_HEADS_UP,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.START;
    params.y = height;
    params.setFitInsetsTypes(0);
    params.setTitle("HeadsUp");
    windows.addView(headsUp, params);
    headsUpKey = item.key;
    headsUpUntil = SystemClock.uptimeMillis() + HEADS_UP_MS;
    protocol.event("headsUp", new Protocol.Json().put("key", item.key).object());
  }

  private void hideHeadsUp() {
    if (headsUp == null) return;
    windows.removeViewImmediate(headsUp);
    headsUp = null;
    headsUpKey = null;
  }

  // ---------------------------------------------------------------------------------------------
  // Building blocks

  interface Action {
    JSONObject run() throws Exception;
  }

  /** Runs a tap's action; a failure is an {@code error} event, as the app's own would be. */
  void run(Action action) {
    try {
      JSONObject result = action.run();
      if (result != null) {
        protocol.event("systemUi", new Protocol.Json().put("result", result).object());
      }
    } catch (Exception e) {
      protocol.event(
          "error",
          new Protocol.Json().put("kind", "systemUi").put("message", String.valueOf(e)).object());
    }
  }

  boolean night() {
    return (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
  }

  int primaryText() {
    return night() ? 0xFFE3E3E3 : 0xFF1F1F1F;
  }

  int secondaryText() {
    return night() ? 0xFFC4C7C5 : 0xFF444746;
  }

  TextView text(String value, float sp, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    return view;
  }

  TextView pill(String label, boolean primary) {
    TextView pill = text(label, 14, primary ? 0xFF062E6F : primaryText());
    pill.setPadding(dp(16), dp(8), dp(16), dp(8));
    pill.setBackground(rounded(primary ? 0xFFA8C7FA : night() ? 0xFF2D2F31 : 0xFFFFFFFF, 18));
    pill.setClickable(true);
    return pill;
  }

  GradientDrawable rounded(int color, float radiusDp) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(radiusDp));
    return background;
  }

  static LinearLayout.LayoutParams wide() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private Drawable load(android.graphics.drawable.Icon icon) {
    if (icon == null) return null;
    try {
      return icon.loadDrawable(app);
    } catch (RuntimeException e) {
      return null;
    }
  }

  int dp(float value) {
    return Math.round(value * app.getResources().getDisplayMetrics().density);
  }

  /** Gives [view] the resource id {@code sim:id/<name>}, which find and tap match on. */
  static void simId(View view, String name) {
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    view.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setViewIdResourceName("sim:id/" + name);
          }
        });
  }
}
