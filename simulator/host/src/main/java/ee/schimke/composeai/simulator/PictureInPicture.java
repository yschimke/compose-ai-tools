package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Picture-in-picture on the phone, part of {@link PhoneSystemUi}. Robolectric records {@code
 * enterPictureInPictureMode} and nothing else; this does what the system does next:
 *
 * <ul>
 *   <li>an activity goes into picture-in-picture when it calls {@code enterPictureInPictureMode}
 *       (in {@code onUserLeaveHint} on HOME, or from a button), or on HOME when it set {@code
 *       setAutoEnterEnabled(true)} in its {@code PictureInPictureParams};
 *   <li>it stays paused, not stopped, and {@link Navigator} brings the home screen forward under
 *       it; its window shrinks to a corner at its params' aspect ratio (16:9 without one), and
 *       {@code onPictureInPictureModeChanged(true, …)} is called;
 *   <li>a tap on the window shows its controls: expand (back to full screen, resumed) and close
 *       (stopped and destroyed); tapping the app's icon expands it too.
 * </ul>
 *
 * The command is {@code pip {op?: status | expand | close}}.
 */
final class PictureInPicture implements Navigator.PipListener {

  /** Above the app's windows, where SystemUI's PiP menu sits. */
  private static final int TYPE_MENU = WindowManager.LayoutParams.TYPE_STATUS_BAR + 3;

  private static final long CONTROLS_MS = 3000;

  private final PhoneSystemUi ui;
  private final Navigator navigator;
  private final Protocol protocol;

  private Navigator.Entry entry;
  private Saved saved;
  private Menu menu;
  private final RectF bounds = new RectF();

  /** The window's layout before it shrank, to give back on expanding. */
  private static final class Saved {
    int width;
    int height;
    int x;
    int y;
    int gravity;
    int flags;
  }

  PictureInPicture(
      PhoneSystemUi ui, android.app.Application app, Navigator navigator, Protocol protocol) {
    this.ui = ui;
    this.navigator = navigator;
    this.protocol = protocol;
    navigator.pipListener = this;
    // A phone supports it; without the feature the platform drops setPictureInPictureParams.
    org.robolectric.shadows.ShadowPackageManager packages = Shadow.extract(app.getPackageManager());
    packages.setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true);
  }

  /** One cycle: an app on top that asked for picture-in-picture goes into it; menu timeout. */
  void cycle() {
    if (navigator.pip == null) {
      Navigator.Entry top = navigator.top(android.view.Display.DEFAULT_DISPLAY);
      if (top != null && !top.system && !top.stopped && top.activity().isInPictureInPictureMode()) {
        navigator.enterPip(top);
      }
      return;
    }
    if (entry != null && entry.activity().isFinishing()) {
      // The app finished itself in picture-in-picture.
      navigator.dismissPip();
      return;
    }
    if (menu != null && menu.controlsUntil > 0 && SystemClock.uptimeMillis() > menu.controlsUntil) {
      menu.controls(false);
    }
  }

  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!name.equals("pip")) return null;
    String op = c.optString("op", "status");
    switch (op) {
      case "status":
        return describe();
      case "expand":
        requirePip();
        navigator.expandPip();
        return describe();
      case "close":
        requirePip();
        navigator.dismissPip();
        return describe();
      default:
        throw new IllegalArgumentException("pip op is status, expand or close, not " + op);
    }
  }

  private void requirePip() {
    if (navigator.pip == null)
      throw new IllegalStateException("no activity is in picture-in-picture");
  }

  JSONObject describe() {
    Navigator.Entry pip = navigator.pip;
    Protocol.Json json =
        new Protocol.Json()
            .put("active", pip != null)
            .put("activity", pip == null ? null : pip.activity().getClass().getName());
    if (pip != null) {
      json.put(
          "bounds",
          new Protocol.Json()
              .put("x", Math.round(bounds.left))
              .put("y", Math.round(bounds.top))
              .put("width", Math.round(bounds.width()))
              .put("height", Math.round(bounds.height()))
              .object());
      json.put("controls", menu != null && menu.controlsUntil > 0);
    }
    return json.object();
  }

  // ---------------------------------------------------------------------------------------------
  // Navigator.PipListener

  @Override
  public boolean wantsPip(Navigator.Entry entry) {
    Activity activity = entry.activity();
    if (activity.isInPictureInPictureMode()) return true;
    PictureInPictureParams params = params(activity);
    if (params == null || !autoEnter(params)) return false;
    ReflectionHelpers.setField(shadow(activity), "isInPictureInPictureMode", true);
    return true;
  }

  @Override
  public void entered(Navigator.Entry entry) {
    this.entry = entry;
    Activity activity = entry.activity();
    shrink(activity);
    dispatch(activity, true);
    showMenu();
    protocol.event(
        "pip",
        new Protocol.Json()
            .put("state", "entered")
            .put("activity", activity.getClass().getName())
            .object());
  }

  @Override
  public void left(Navigator.Entry entry, boolean expanded) {
    hideMenu();
    Activity activity = entry.activity();
    ReflectionHelpers.setField(shadow(activity), "isInPictureInPictureMode", false);
    restore(activity);
    if (expanded) dispatch(activity, false);
    this.entry = null;
    protocol.event(
        "pip",
        new Protocol.Json()
            .put("state", expanded ? "expanded" : "closed")
            .put("activity", activity.getClass().getName())
            .object());
  }

  // ---------------------------------------------------------------------------------------------

  private static ShadowActivity shadow(Activity activity) {
    return Shadow.extract(activity);
  }

  private static PictureInPictureParams params(Activity activity) {
    if (activity.getWindow() == null) return null;
    android.os.IBinder token = ReflectionHelpers.callInstanceMethod(activity, "getActivityToken");
    return SimulatorShadowActivityClient.params(token);
  }

  private static boolean autoEnter(PictureInPictureParams params) {
    try {
      return ReflectionHelpers.callInstanceMethod(params, "isAutoEnterEnabled");
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The aspect ratio the app asked for, else 16:9; kept within the platform's 2.39:1 limits. */
  private static float aspect(Activity activity) {
    PictureInPictureParams params = params(activity);
    float ratio = 16f / 9f;
    if (params != null) {
      try {
        Rational asked = ReflectionHelpers.callInstanceMethod(params, "getAspectRatio");
        if (asked != null && asked.floatValue() > 0) ratio = asked.floatValue();
      } catch (RuntimeException e) {
        // 16:9.
      }
    }
    return Math.max(1 / 2.39f, Math.min(2.39f, ratio));
  }

  /** Shrinks [activity]'s window to the bottom-right corner, at its aspect ratio. */
  private void shrink(Activity activity) {
    View decor = activity.getWindow().getDecorView();
    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) decor.getLayoutParams();
    saved = new Saved();
    saved.width = lp.width;
    saved.height = lp.height;
    saved.x = lp.x;
    saved.y = lp.y;
    saved.gravity = lp.gravity;
    saved.flags = lp.flags;
    DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
    float ratio = aspect(activity);
    int w;
    int h;
    if (ratio >= 1) {
      w = Math.round(Math.min(metrics.widthPixels, metrics.heightPixels) * 0.55f);
      h = Math.round(w / ratio);
    } else {
      h = Math.round(Math.max(metrics.widthPixels, metrics.heightPixels) * 0.3f);
      w = Math.round(h * ratio);
    }
    int margin = ui.dp(16);
    int x = metrics.widthPixels - w - margin;
    int y = metrics.heightPixels - h - margin - ui.navigationBar.sizeAtBottom();
    bounds.set(x, y, x + w, y + h);
    lp.width = w;
    lp.height = h;
    lp.x = x;
    lp.y = y;
    lp.gravity = Gravity.TOP | Gravity.START;
    // As a device's PiP window: no keys, no focus, Back goes to what is under it.
    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    activity.getWindowManager().updateViewLayout(decor, lp);
  }

  private void restore(Activity activity) {
    if (saved == null || activity.getWindow() == null) return;
    View decor = activity.getWindow().getDecorView();
    if (!(decor.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) decor.getLayoutParams();
    lp.width = saved.width;
    lp.height = saved.height;
    lp.x = saved.x;
    lp.y = saved.y;
    lp.gravity = saved.gravity;
    lp.flags = saved.flags;
    saved = null;
    if (decor.isAttachedToWindow()) activity.getWindowManager().updateViewLayout(decor, lp);
  }

  /** {@code onPictureInPictureModeChanged}, with the window's size as the new configuration's. */
  private void dispatch(Activity activity, boolean inPip) {
    Configuration config = new Configuration(activity.getResources().getConfiguration());
    if (inPip) {
      float density = activity.getResources().getDisplayMetrics().density;
      config.screenWidthDp = Math.round(bounds.width() / density);
      config.screenHeightDp = Math.round(bounds.height() / density);
    }
    try {
      ReflectionHelpers.callInstanceMethod(
          activity,
          "dispatchPictureInPictureModeChanged",
          ClassParameter.from(boolean.class, inPip),
          ClassParameter.from(Configuration.class, config));
    } catch (RuntimeException e) {
      activity.onPictureInPictureModeChanged(inPip, config);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The menu: SystemUI's PiP touch handler and controls, over the window

  private void showMenu() {
    hideMenu();
    menu = new Menu(ui.context());
    PhoneSystemUi.simId(menu, "pip");
    menu.setContentDescription(entry.activity().getClass().getName());
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            Math.round(bounds.width()),
            Math.round(bounds.height()),
            TYPE_MENU,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.START;
    params.x = Math.round(bounds.left);
    params.y = Math.round(bounds.top);
    params.setFitInsetsTypes(0);
    params.setTitle("PictureInPictureMenu");
    ui.windows().addView(menu, params);
  }

  private void hideMenu() {
    if (menu == null) return;
    ui.windows().removeViewImmediate(menu);
    menu = null;
  }

  /** Over the PiP window: a tap shows expand and close for a few seconds. */
  private final class Menu extends FrameLayout {
    long controlsUntil;
    private final Button expand;
    private final Button close;

    Menu(Context context) {
      super(context);
      expand = new Button(context, true);
      PhoneSystemUi.simId(expand, "pip_expand");
      expand.setContentDescription("Expand");
      expand.setOnClickListener(v -> navigator.expandPip());
      close = new Button(context, false);
      PhoneSystemUi.simId(close, "pip_close");
      close.setContentDescription("Close");
      close.setOnClickListener(v -> navigator.dismissPip());
      addView(expand, new LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER));
      addView(close, new LayoutParams(ui.dp(40), ui.dp(40), Gravity.TOP | Gravity.END));
      controls(false);
      setOnClickListener(v -> controls(controlsUntil == 0));
    }

    void controls(boolean shown) {
      controlsUntil = shown ? SystemClock.uptimeMillis() + CONTROLS_MS : 0;
      setBackgroundColor(shown ? 0x66000000 : Color.TRANSPARENT);
      expand.setVisibility(shown ? VISIBLE : GONE);
      close.setVisibility(shown ? VISIBLE : GONE);
    }
  }

  /** Expand (corners out) or close (an X), drawn white. */
  private final class Button extends View {
    private final boolean expandIcon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Button(Context context, boolean expandIcon) {
      super(context);
      this.expandIcon = expandIcon;
      setClickable(true);
      paint.setColor(Color.WHITE);
      paint.setStrokeWidth(ui.dp(2));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      float s = Math.min(w, h) * 0.22f;
      float cx = w / 2;
      float cy = h / 2;
      if (!expandIcon) {
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, paint);
        canvas.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
        return;
      }
      float a = s * 1.4f;
      float l = s * 0.7f;
      // Four corners pointing out.
      canvas.drawLine(cx - a, cy - a, cx - a + l, cy - a, paint);
      canvas.drawLine(cx - a, cy - a, cx - a, cy - a + l, paint);
      canvas.drawLine(cx + a, cy - a, cx + a - l, cy - a, paint);
      canvas.drawLine(cx + a, cy - a, cx + a, cy - a + l, paint);
      canvas.drawLine(cx - a, cy + a, cx - a + l, cy + a, paint);
      canvas.drawLine(cx - a, cy + a, cx - a, cy + a - l, paint);
      canvas.drawLine(cx + a, cy + a, cx + a - l, cy + a, paint);
      canvas.drawLine(cx + a, cy + a, cx + a, cy + a - l, paint);
    }
  }
}
