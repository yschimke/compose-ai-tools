package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Date;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.util.ReflectionHelpers;

/**
 * The phone's lock screen, part of {@link PhoneSystemUi}: the keyguard a phone shows when its
 * screen comes back on. Turning the screen off ({@code key POWER}, {@code sleep}, the timeout)
 * locks it; the app stays stopped behind the lock screen until it is unlocked, as on a device. It
 * shows the clock, the date and the notifications (a tap unlocks, then opens one); a swipe up
 * unlocks, through a PIN bouncer when a PIN is set.
 *
 * <p>Robolectric's {@code KeyguardManager} reports the state ({@code isKeyguardLocked}, {@code
 * isDeviceLocked}, {@code isDeviceSecure}), and its {@code requestDismissKeyguard} callback is
 * answered: an insecure lock screen dismisses at once, a secure one opens the bouncer. Unlocking
 * broadcasts {@code ACTION_USER_PRESENT}. An activity that shows when locked ({@code
 * setShowWhenLocked}, {@code android:showWhenLocked} or the window flag) is drawn over the lock
 * screen, and one that turns the screen on ({@code setTurnScreenOn}, {@code android:turnScreenOn})
 * wakes it when it comes to the top.
 *
 * <p>The command is {@code lockScreen {op: status | lock | unlock | pin, pin?}}.
 */
final class LockScreen {

  /** Above the app, the status bar and a heads-up card; below the navigation bar and the shade. */
  private static final int TYPE = WindowManager.LayoutParams.TYPE_STATUS_BAR + 18;

  /** {@code ActivityInfo.FLAG_SHOW_WHEN_LOCKED} and {@code FLAG_TURN_SCREEN_ON}, hidden. */
  private static final int SHOW_WHEN_LOCKED = 0x800000;

  private static final int TURN_SCREEN_ON = 0x1000000;

  /** The PIN a secure device takes when none was set with {@code lockScreen op:pin}. */
  static final String DEFAULT_PIN = "1234";

  private final PhoneSystemUi ui;
  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final NotificationStream stream;
  private ScreenPower screen;

  private boolean locked;
  private String pin;

  /** The app's activity the lock screen keeps stopped, which unlocking brings back. */
  private Navigator.Entry held;

  /** What runs once unlocked: a notification tapped on the lock screen. */
  private Runnable afterUnlock;

  private Root root;
  private View row;
  private LinearLayout content;
  private TextView clock;
  private LinearLayout bouncer;
  private TextView bouncerMessage;
  private Navigator.Entry turnedOn;

  LockScreen(
      PhoneSystemUi ui,
      Application app,
      Navigator navigator,
      Protocol protocol,
      NotificationStream stream) {
    this.ui = ui;
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.stream = stream;
  }

  void start() {
    stream.onChange(this::rebuild);
  }

  /** Wired by the session: the screen's power, which locks and wakes. */
  void screen(ScreenPower screen) {
    this.screen = screen;
  }

  // ---------------------------------------------------------------------------------------------
  // State

  boolean locked() {
    return locked;
  }

  /** Whether the lock screen is on the screen: locked, and no show-when-locked activity on top. */
  boolean showing() {
    return root != null && root.getVisibility() == View.VISIBLE;
  }

  boolean secure() {
    return pin != null || keyguardManager().isDeviceSecure();
  }

  private String expectedPin() {
    return pin != null ? pin : DEFAULT_PIN;
  }

  /** The screen went off: the phone locks. */
  void screenOff() {
    lock("screenOff");
  }

  /**
   * The screen came back on with [sleeping] stopped by it: the lock screen keeps it stopped, unless
   * it shows when locked. True when held.
   */
  boolean hold(Navigator.Entry sleeping) {
    if (!locked || sleeping == null || showsWhenLocked(sleeping.activity())) return false;
    held = sleeping;
    return true;
  }

  void lock(String reason) {
    if (locked) return;
    locked = true;
    ShadowKeyguardManager keyguard = keyguard();
    keyguard.setKeyguardLocked(true);
    keyguard.setIsDeviceLocked(secure());
    ui.closeShade();
    attach();
    protocol.event(
        "lockScreen", new Protocol.Json().put("state", "locked").put("reason", reason).object());
  }

  /** Unlocks, with [credential] when the device is secure; false when it is the wrong one. */
  boolean unlock(String credential, String via) {
    if (!locked) return true;
    if (secure() && !expectedPin().equals(credential)) {
      protocol.event(
          "lockScreen", new Protocol.Json().put("state", "wrongPin").put("via", via).object());
      return false;
    }
    locked = false;
    detach();
    ShadowKeyguardManager keyguard = keyguard();
    keyguard.setIsDeviceLocked(false);
    // Answers a waiting requestDismissKeyguard with onDismissSucceeded.
    keyguard.setKeyguardLocked(false);
    if (held != null) {
      navigator.screenOn(held);
      held = null;
    }
    navigator.broadcast(new Intent(Intent.ACTION_USER_PRESENT));
    protocol.event(
        "lockScreen", new Protocol.Json().put("state", "unlocked").put("via", via).object());
    Runnable then = afterUnlock;
    afterUnlock = null;
    if (then != null) then.run();
    return true;
  }

  /** Back on the lock screen closes the bouncer, and goes nowhere else. */
  boolean back() {
    if (!showing()) return false;
    if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) showBouncer(false);
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!name.equals("lockScreen")) return null;
    String op = c.optString("op", "status");
    switch (op) {
      case "status":
        return describe();
      case "lock":
        if (screen != null && c.optBoolean("screenOff", false)) screen.sleep("lockScreen");
        lock("command");
        return describe();
      case "unlock":
        {
          boolean ok = unlock(c.has("pin") ? c.getString("pin") : null, "command");
          JSONObject state = describe();
          state.put("unlocked", ok);
          return state;
        }
      case "pin":
        {
          pin = c.isNull("pin") || !c.has("pin") ? null : c.getString("pin");
          ShadowKeyguardManager keyguard = keyguard();
          keyguard.setIsDeviceSecure(pin != null);
          keyguard.setIsKeyguardSecure(pin != null);
          keyguard.setIsDeviceLocked(locked && pin != null);
          return describe();
        }
      default:
        throw new IllegalArgumentException(
            "lockScreen op is status, lock, unlock or pin, not " + op);
    }
  }

  JSONObject describe() {
    KeyguardManager keyguard = keyguardManager();
    return new Protocol.Json()
        .put("locked", locked)
        .put("showing", showing())
        .put("secure", secure())
        .put("bouncer", bouncer != null && bouncer.getVisibility() == View.VISIBLE)
        .put("keyguardLocked", keyguard.isKeyguardLocked())
        .put("deviceLocked", keyguard.isDeviceLocked())
        .put("held", held == null ? null : held.activity().getClass().getName())
        .object();
  }

  // ---------------------------------------------------------------------------------------------
  // Each cycle

  void cycle() {
    Navigator.Entry top = navigator.top(Display.DEFAULT_DISPLAY);
    Activity activity = top == null || top.system ? null : top.activity();
    // An activity that turns the screen on, newly on top of a dark screen, wakes it.
    if (activity != null && top != turnedOn && turnsScreenOn(activity)) {
      turnedOn = top;
      if (screen != null && !"on".equals(screen.state())) screen.wakeUp("turnScreenOn");
    }
    if (!locked) return;
    // A show-when-locked activity on top is drawn over the lock screen.
    boolean over = activity != null && held == null && showsWhenLocked(activity);
    if (root != null) {
      int visibility = over ? View.GONE : View.VISIBLE;
      if (root.getVisibility() != visibility) {
        root.setVisibility(visibility);
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        if (over) {
          params.flags |=
              WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                  | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
          params.flags &=
              ~(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                  | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        ui.windows().updateViewLayout(root, params);
      }
    }
    // The app asked to dismiss the keyguard: at once when insecure, else through the bouncer.
    if (dismissRequested()) {
      if (!secure()) {
        unlock(null, "requestDismissKeyguard");
      } else if (bouncer != null && bouncer.getVisibility() != View.VISIBLE) {
        showBouncer(true);
      }
    }
    if (clock != null) clock.setText(clockText());
  }

  private boolean dismissRequested() {
    try {
      return ReflectionHelpers.getStaticField(ShadowKeyguardManager.class, "callback") != null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean showsWhenLocked(Activity activity) {
    ShadowActivity shadow = Shadow.extract(activity);
    if (shadow.getShowWhenLocked()) return true;
    ActivityInfo info = activityInfo(activity);
    if (info != null && (info.flags & SHOW_WHEN_LOCKED) != 0) return true;
    @SuppressWarnings("deprecation")
    int flag = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
    return activity.getWindow() != null && (activity.getWindow().getAttributes().flags & flag) != 0;
  }

  private static boolean turnsScreenOn(Activity activity) {
    ShadowActivity shadow = Shadow.extract(activity);
    if (shadow.getTurnScreenOn()) return true;
    ActivityInfo info = activityInfo(activity);
    if (info != null && (info.flags & TURN_SCREEN_ON) != 0) return true;
    @SuppressWarnings("deprecation")
    int flag = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
    return activity.getWindow() != null && (activity.getWindow().getAttributes().flags & flag) != 0;
  }

  private static ActivityInfo activityInfo(Activity activity) {
    try {
      return activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
    } catch (Exception e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The window

  private void attach() {
    if (root != null) return;
    root = new Root(ui.context());
    root.setBackgroundColor(0xFF101418);
    PhoneSystemUi.simId(root, "lock_screen");
    row = ui.statusRow(false);
    root.addView(
        row,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ui.statusBarHeightPx(), Gravity.TOP));
    content = new LinearLayout(ui.context());
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(ui.dp(16), ui.statusBarHeightPx() + ui.dp(24), ui.dp(16), ui.dp(16));
    root.addView(content, new FrameLayout.LayoutParams(-1, -1));
    TextView hint = ui.text("Swipe up to unlock", 14, 0xFFC4C7C5);
    hint.setGravity(Gravity.CENTER);
    PhoneSystemUi.simId(hint, "lock_hint");
    FrameLayout.LayoutParams hintParams =
        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    hintParams.bottomMargin = ui.dp(48);
    root.addView(hint, hintParams);
    bouncer = bouncer();
    bouncer.setVisibility(View.GONE);
    root.addView(bouncer, new FrameLayout.LayoutParams(-1, -1));
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            TYPE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE);
    params.gravity = Gravity.TOP | Gravity.START;
    params.setFitInsetsTypes(0);
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    params.setTitle("Keyguard");
    ui.windows().addView(root, params);
    rebuild();
  }

  private void detach() {
    if (root == null) return;
    ui.windows().removeViewImmediate(root);
    ui.dropRow(row);
    root = null;
    row = null;
    content = null;
    clock = null;
    bouncer = null;
    bouncerMessage = null;
  }

  /** The clock, the date and the notifications, again after any change to them. */
  private void rebuild() {
    if (content == null) return;
    content.removeAllViews();
    clock = ui.text(clockText(), 72, 0xFFE3E3E3);
    clock.setGravity(Gravity.CENTER_HORIZONTAL);
    clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    PhoneSystemUi.simId(clock, "lock_clock");
    content.addView(clock, PhoneSystemUi.wide());
    TextView date =
        ui.text(DateFormat.format("EEEE, MMMM d", new Date()).toString(), 16, 0xFFC4C7C5);
    date.setGravity(Gravity.CENTER_HORIZONTAL);
    date.setPadding(0, 0, 0, ui.dp(24));
    content.addView(date, PhoneSystemUi.wide());
    List<NotificationStream.Item> items = ui.barItems();
    for (int i = 0; i < items.size() && i < 3; i++) {
      NotificationStream.Item item = items.get(i);
      View card = ui.card(item, false, false);
      Tapped wrapper = new Tapped(ui.context(), () -> openFromLockScreen(item));
      wrapper.addView(card);
      PhoneSystemUi.simId(wrapper, "lock_notification");
      wrapper.setContentDescription(item.key);
      LinearLayout.LayoutParams lp = PhoneSystemUi.wide();
      lp.topMargin = ui.dp(4);
      content.addView(wrapper, lp);
    }
    if (items.size() > 3) {
      TextView more = ui.text("+" + (items.size() - 3) + " more", 13, 0xFFC4C7C5);
      more.setGravity(Gravity.CENTER_HORIZONTAL);
      more.setPadding(0, ui.dp(8), 0, 0);
      content.addView(more, PhoneSystemUi.wide());
    }
  }

  /** A notification tapped on the lock screen opens once unlocked, as on a device. */
  private void openFromLockScreen(NotificationStream.Item item) {
    afterUnlock = () -> ui.tap(item);
    if (secure()) {
      showBouncer(true);
    } else {
      unlock(null, "notification");
    }
  }

  private String clockText() {
    return DateFormat.format(DateFormat.is24HourFormat(app) ? "HH:mm" : "h:mm", new Date())
        .toString();
  }

  /** The PIN entry a secure lock screen asks for. */
  private LinearLayout bouncer() {
    LinearLayout column = new LinearLayout(ui.context());
    column.setOrientation(LinearLayout.VERTICAL);
    column.setGravity(Gravity.CENTER_HORIZONTAL);
    column.setBackgroundColor(0xFF101418);
    column.setPadding(ui.dp(32), ui.statusBarHeightPx() + ui.dp(96), ui.dp(32), ui.dp(32));
    column.setClickable(true);
    TextView title = ui.text("Enter your PIN", 20, 0xFFE3E3E3);
    title.setGravity(Gravity.CENTER);
    column.addView(title, PhoneSystemUi.wide());
    bouncerMessage = ui.text("", 14, 0xFFF2B8B5);
    bouncerMessage.setGravity(Gravity.CENTER);
    bouncerMessage.setPadding(0, ui.dp(8), 0, ui.dp(16));
    column.addView(bouncerMessage, PhoneSystemUi.wide());
    EditText field = new EditText(ui.context());
    field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    field.setTextColor(0xFFE3E3E3);
    field.setGravity(Gravity.CENTER);
    field.setTextSize(24);
    field.setBackground(ui.rounded(0xFF2D3135, 24));
    field.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
    PhoneSystemUi.simId(field, "lock_pin");
    column.addView(field, PhoneSystemUi.wide());
    TextView enter = ui.pill("Enter", true);
    PhoneSystemUi.simId(enter, "lock_pin_enter");
    enter.setOnClickListener(
        v -> {
          if (!unlock(field.getText().toString(), "bouncer")) {
            bouncerMessage.setText("Wrong PIN");
            field.setText("");
          }
        });
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = ui.dp(16);
    column.addView(enter, lp);
    return column;
  }

  private void showBouncer(boolean show) {
    if (bouncer == null) return;
    bouncer.setVisibility(show ? View.VISIBLE : View.GONE);
    if (show) {
      bouncerMessage.setText("");
      bouncer.findFocus();
      for (int i = 0; i < bouncer.getChildCount(); i++) {
        if (bouncer.getChildAt(i) instanceof EditText) bouncer.getChildAt(i).requestFocus();
      }
    } else if (afterUnlock != null) {
      afterUnlock = null;
    }
    protocol.event(
        "lockScreen", new Protocol.Json().put("state", show ? "bouncer" : "locked").object());
  }

  /** A swipe up unlocks, through the bouncer when secure. */
  private void swipedUp() {
    if (secure()) {
      showBouncer(true);
    } else {
      unlock(null, "swipe");
    }
  }

  /** The lock screen's window: a swipe up anywhere on it unlocks. */
  private final class Root extends FrameLayout {
    private final int slop;
    private float downY;
    private boolean done;

    Root(android.content.Context context) {
      super(context);
      slop = ViewConfiguration.get(context).getScaledTouchSlop();
      setFocusable(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downY = event.getY();
          done = false;
          return false;
        case MotionEvent.ACTION_MOVE:
          return downY - event.getY() > slop * 3;
        default:
          return false;
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downY = event.getY();
          done = false;
          return true;
        case MotionEvent.ACTION_MOVE:
          if (!done && downY - event.getY() > ui.dp(80)) {
            done = true;
            swipedUp();
          }
          return true;
        default:
          return true;
      }
    }
  }

  /** Takes every touch for itself: a tap runs [onTap]. */
  private static final class Tapped extends FrameLayout {
    private final Runnable onTap;
    private float downX;
    private float downY;

    Tapped(android.content.Context context, Runnable onTap) {
      super(context);
      this.onTap = onTap;
      setClickable(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          return true;
        case MotionEvent.ACTION_UP:
          int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
          if (Math.abs(event.getX() - downX) < slop && Math.abs(event.getY() - downY) < slop) {
            onTap.run();
          }
          return true;
        default:
          // A swipe goes on to the lock screen.
          return false;
      }
    }
  }

  private ShadowKeyguardManager keyguard() {
    return Shadow.extract(keyguardManager());
  }

  private KeyguardManager keyguardManager() {
    return app.getSystemService(KeyguardManager.class);
  }
}
