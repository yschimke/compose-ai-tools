package ee.schimke.composeai.simulator;

import android.app.Application;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowInputMethodManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The phone's on-screen keyboard, part of {@link PhoneSystemUi}. It comes up when the app asks for
 * the soft input ({@code InputMethodManager.showSoftInput}, which is how a focused text field asks,
 * Compose's included) and goes when the app hides it, Back is pressed, or another activity comes to
 * the top. Robolectric records the request; this draws a QWERTY keyboard in an input-method window
 * along the bottom, types its keys into the focused field (as key events, as the {@code text}
 * command does), and gives the app's windows the IME's {@code WindowInsets}, so {@code
 * imePadding()}, {@code WindowInsets.ime} and {@code adjustResize} lay the app out above it.
 *
 * <p>The command is {@code keyboard {op?: status | show | hide}}.
 */
final class SoftKeyboard {

  /** {@code TYPE_INPUT_METHOD}: above the app, below the navigation bar. */
  private static final int TYPE = WindowManager.LayoutParams.TYPE_STATUS_BAR + 11;

  /**
   * The band's height, and its look below, are the interactive previews' {@code SoftKeyboardBand}
   * (compose-preview-daemon's {@code :data-keyboard-band}), drawn here with plain views since the
   * system UI has no Compose of its own.
   */
  private static final int HEIGHT_DP = 240;

  private static final String[] LETTERS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
  private static final String[] SYMBOLS = {"1234567890", "@#$_&-+()/", "*\"':;!?"};

  private final PhoneSystemUi ui;
  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final Consumer<String> typer;

  private LinearLayout view;
  private boolean shift;
  private boolean night;
  private boolean symbols;
  private int heightPx;
  private Navigator.Entry shownFor;

  /** A text editor has had focus since a touch went to the app. */
  private boolean tappedEditor;

  private long seenTouch;

  /** The app's request for the soft input, as of the last cycle. */
  private boolean lastRequested;

  /** Whether the IME's insets reached the app's windows (they need hidden platform API). */
  private boolean insetsApplied;

  SoftKeyboard(
      PhoneSystemUi ui,
      Application app,
      Navigator navigator,
      Protocol protocol,
      Consumer<String> typer) {
    this.ui = ui;
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.typer = typer;
  }

  boolean showing() {
    return view != null;
  }

  /** One cycle: another activity on top takes the keyboard down; the app keeps its insets. */
  void cycle() {
    // Follows the app's request: a change shows or hides, so a keyboard Back or the command took
    // down stays down until the app asks again.
    boolean requested = requested();
    if (requested != lastRequested) {
      lastRequested = requested;
      if (requested) {
        show("app");
      } else {
        hide("app");
      }
    }
    if (view == null) return;
    if (navigator.top(Display.DEFAULT_DISPLAY) != shownFor) {
      hide("focus");
      return;
    }
    // A relayout hands the app the window manager's insets again, without the IME's.
    applyInsets(true, false);
  }

  /** Back while the keyboard is up takes it down, before the app hears it. */
  boolean back() {
    if (view == null) return false;
    hide("back");
    return true;
  }

  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!name.equals("keyboard")) return null;
    String op = c.optString("op", "status");
    switch (op) {
      case "status":
        return describe();
      case "show":
        show("command");
        return describe();
      case "hide":
        hide("command");
        return describe();
      default:
        throw new IllegalArgumentException("keyboard op is status, show or hide, not " + op);
    }
  }

  JSONObject describe() {
    return new Protocol.Json()
        .put("shown", view != null)
        .put("heightPx", view == null ? 0 : heightPx)
        .put("layout", symbols ? "symbols" : shift ? "shift" : "letters")
        .put("imeInsets", view != null && insetsApplied)
        .put("requested", requested())
        .object();
  }

  // ---------------------------------------------------------------------------------------------

  private void show(String reason) {
    if (view != null) return;
    shownFor = navigator.top(Display.DEFAULT_DISPLAY);
    shift = false;
    symbols = false;
    view = new LinearLayout(ui.context());
    view.setOrientation(LinearLayout.VERTICAL);
    night = ui.night();
    view.setBackgroundColor(night ? 0xFF1B1C1E : 0xFFE8EBEF);
    PhoneSystemUi.simId(view, "keyboard");
    build();
    int nav = ui.navigationBar.sizeAtBottom();
    heightPx = ui.dp(HEIGHT_DP) + nav;
    view.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6) + nav);
    android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heightPx,
            TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE);
    // Placed by position: Robolectric lays a window out from its x and y, not its gravity.
    params.gravity = Gravity.TOP | Gravity.START;
    params.y = metrics.heightPixels - heightPx;
    params.setFitInsetsTypes(0);
    params.setTitle("InputMethod");
    ui.windows().addView(view, params);
    applyInsets(true, true);
    protocol.event(
        "keyboard", new Protocol.Json().put("shown", true).put("reason", reason).object());
  }

  private void hide(String reason) {
    if (view == null) return;
    ui.windows().removeViewImmediate(view);
    view = null;
    applyInsets(false, true);
    Navigator.Entry owner = shownFor;
    shownFor = null;
    // The app's window no longer asks for it, as when a device's IME goes.
    if (owner != null && !owner.system && owner.activity().getWindow() != null) {
      android.view.WindowInsetsController controller =
          owner.activity().getWindow().getInsetsController();
      if (controller != null) controller.hide(WindowInsets.Type.ime());
    }
    // Robolectric's record of the soft input follows, as the app would read it.
    ShadowInputMethodManager shadow = shadow();
    if (shadow.isSoftInputVisible()) {
      View focused = Displays.focusedWindow(Display.DEFAULT_DISPLAY);
      if (focused != null) {
        app.getSystemService(InputMethodManager.class)
            .hideSoftInputFromWindow(focused.getWindowToken(), 0);
      }
    }
    protocol.event(
        "keyboard", new Protocol.Json().put("shown", false).put("reason", reason).object());
  }

  /** The keys, again after shift or the symbols key. */
  private void build() {
    view.removeAllViews();
    String[] rows = symbols ? SYMBOLS : LETTERS;
    view.addView(row(keys(rows[0])));
    LinearLayout second = row(keys(rows[1]));
    second.setPadding(symbols ? 0 : ui.dp(16), 0, symbols ? 0 : ui.dp(16), 0);
    view.addView(second);
    List<View> third = new ArrayList<>();
    third.add(
        symbols ? spacer(1.5f) : special(shift ? "⇧" : "⇪", "shift", 1.5f, this::toggleShift));
    third.addAll(keys(rows[2]));
    third.add(special("⌫", "delete", 1.5f, () -> key(KeyEvent.KEYCODE_DEL)));
    view.addView(row(third));
    List<View> fourth = new ArrayList<>();
    fourth.add(special(symbols ? "ABC" : "?123", "symbols", 1.5f, this::toggleSymbols));
    fourth.add(character(",", 1f));
    fourth.add(special(" ", "space", 5f, () -> type(" ")));
    fourth.add(character(".", 1f));
    fourth.add(special("⏎", "enter", 1.5f, () -> key(KeyEvent.KEYCODE_ENTER)));
    view.addView(row(fourth));
  }

  private List<View> keys(String row) {
    List<View> keys = new ArrayList<>();
    for (char c : row.toCharArray()) keys.add(character(String.valueOf(c), 1f));
    return keys;
  }

  private LinearLayout row(List<View> keys) {
    LinearLayout row = new LinearLayout(ui.context());
    row.setOrientation(LinearLayout.HORIZONTAL);
    for (View key : keys) {
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(
              0, ViewGroup.LayoutParams.MATCH_PARENT, ((Weight) key.getTag()).value);
      lp.setMargins(ui.dp(2), 0, ui.dp(2), 0);
      row.addView(key, lp);
    }
    // Four rows share the band, 6dp apart.
    LinearLayout.LayoutParams rowParams =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    rowParams.setMargins(0, ui.dp(3), 0, ui.dp(3));
    row.setLayoutParams(rowParams);
    return row;
  }

  private View character(String c, float weight) {
    String shown = shift && !symbols ? c.toUpperCase(java.util.Locale.ROOT) : c;
    TextView key = label(shown, night ? 0xFF3C4043 : 0xFFFFFFFF, weight);
    PhoneSystemUi.simId(key, "key");
    key.setOnClickListener(
        v -> {
          type(shown);
          if (shift) {
            shift = false;
            build();
          }
        });
    return key;
  }

  private View special(String label, String id, float weight, Runnable action) {
    TextView key = label(label, night ? 0xFF24262A : 0xFFC8CCD3, weight);
    PhoneSystemUi.simId(key, "key_" + id);
    key.setContentDescription(id);
    key.setOnClickListener(v -> action.run());
    return key;
  }

  private View spacer(float weight) {
    View space = new View(ui.context());
    space.setTag(new Weight(weight));
    return space;
  }

  private TextView label(String text, int color, float weight) {
    TextView key = ui.text(text, 14, night ? 0xFFE8EAED : 0xFF1F1F1F);
    key.setGravity(Gravity.CENTER);
    // Pressed, a key lights up as the preview band's held key does.
    android.graphics.drawable.StateListDrawable background =
        new android.graphics.drawable.StateListDrawable();
    background.addState(
        new int[] {android.R.attr.state_pressed}, ui.rounded(night ? 0xFF8AB4F8 : 0xFF1A73E8, 6));
    background.addState(new int[0], ui.rounded(color, 6));
    key.setBackground(background);
    key.setClickable(true);
    key.setTag(new Weight(weight));
    return key;
  }

  private static final class Weight {
    final float value;

    Weight(float value) {
      this.value = value;
    }
  }

  private void toggleShift() {
    shift = !shift;
    build();
  }

  private void toggleSymbols() {
    symbols = !symbols;
    shift = false;
    build();
  }

  private void type(String text) {
    typer.accept(text);
  }

  private void key(int code) {
    ui.keys.accept(code);
  }

  // ---------------------------------------------------------------------------------------------
  // The IME's insets

  /**
   * Gives each of the app's windows on the default display an IME insets source over the keyboard,
   * visible or not, as the window manager does; when [force] is false only where it is missing.
   * Hidden platform API (API 34+), so best effort.
   */
  private void applyInsets(boolean visible, boolean force) {
    android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    Rect frame =
        new Rect(0, metrics.heightPixels - heightPx, metrics.widthPixels, metrics.heightPixels);
    boolean applied = false;
    try {
      Class<?> stateClass =
          Class.forName("android.view.InsetsState", true, getClass().getClassLoader());
      Class<?> sourceClass =
          Class.forName("android.view.InsetsSource", true, getClass().getClassLoader());
      int imeId = ReflectionHelpers.getStaticField(sourceClass, "ID_IME");
      for (Object impl : appRoots()) {
        Object controller = ReflectionHelpers.callInstanceMethod(impl, "getInsetsController");
        Object current = ReflectionHelpers.callInstanceMethod(controller, "getState");
        Object existing =
            ReflectionHelpers.callInstanceMethod(
                current, "peekSource", ClassParameter.from(int.class, imeId));
        if (!force
            && existing != null
            && (boolean) ReflectionHelpers.callInstanceMethod(existing, "isVisible") == visible) {
          applied = true;
          continue;
        }
        Object state =
            stateClass.getConstructor(stateClass, boolean.class).newInstance(current, true);
        Object source =
            ReflectionHelpers.callInstanceMethod(
                state,
                "getOrCreateSource",
                ClassParameter.from(int.class, imeId),
                ClassParameter.from(int.class, WindowInsets.Type.ime()));
        ReflectionHelpers.callInstanceMethod(
            source, "setFrame", ClassParameter.from(Rect.class, frame));
        ReflectionHelpers.callInstanceMethod(
            source, "setVisible", ClassParameter.from(boolean.class, visible));
        ReflectionHelpers.callInstanceMethod(
            controller, "onStateChanged", ClassParameter.from(stateClass, state));
        applied = true;
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      applied = false;
    }
    insetsApplied = applied;
  }

  /**
   * Whether the app wants the soft input: its window asked for the IME's insets to show ({@code
   * WindowInsetsController.show(ime())}, which is how Compose's keyboard controller and AndroidX's
   * ask), or it called {@code InputMethodManager.showSoftInput}, which Robolectric records.
   */
  private boolean requested() {
    // The lock screen's PIN field is the system's: no app keyboard over or under it.
    if (ui.lockScreen.showing()) return false;
    if (shadow().isSoftInputVisible()) return true;
    Navigator.Entry top = navigator.top(Display.DEFAULT_DISPLAY);
    if (top == null || top.system || top.activity().getWindow() == null) return false;
    View decor = top.activity().getWindow().peekDecorView();
    if (decor == null || !decor.isAttachedToWindow()) return false;
    // A text editor focused by a tap: an EditText, or Compose's view while a text field has an
    // input session, which on a device is what brings the IME up when the field is tapped. Focus
    // a window takes by default (Robolectric does not start in touch mode) does not.
    View focused = decor.findFocus();
    boolean editor = focused != null && focused.onCheckIsTextEditor();
    if (!editor) {
      tappedEditor = false;
    } else if (Displays.lastAppTouch > seenTouch) {
      tappedEditor = true;
    }
    seenTouch = Displays.lastAppTouch;
    if (tappedEditor) return true;
    try {
      Object impl = ReflectionHelpers.callInstanceMethod(decor, "getViewRootImpl");
      if (impl == null) return false;
      Object controller = ReflectionHelpers.callInstanceMethod(impl, "getInsetsController");
      int types = ReflectionHelpers.callInstanceMethod(controller, "getRequestedVisibleTypes");
      return (types & WindowInsets.Type.ime()) != 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The {@code ViewRootImpl}s of the app's own windows on the default display. */
  private static List<Object> appRoots() throws ClassNotFoundException {
    Object global =
        ReflectionHelpers.callStaticMethod(
            Class.forName(
                "android.view.WindowManagerGlobal", true, SoftKeyboard.class.getClassLoader()),
            "getInstance");
    List<?> roots = asList(ReflectionHelpers.getField(global, "mRoots"));
    List<?> params = asList(ReflectionHelpers.getField(global, "mParams"));
    List<Object> app = new ArrayList<>();
    for (int i = 0; i < Math.min(roots.size(), params.size()); i++) {
      WindowManager.LayoutParams p = (WindowManager.LayoutParams) params.get(i);
      if (p.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) continue;
      View view = ReflectionHelpers.callInstanceMethod(roots.get(i), "getView");
      if (view == null || Displays.displayOf(view) != Display.DEFAULT_DISPLAY) continue;
      app.add(roots.get(i));
    }
    return app;
  }

  private static List<?> asList(Object value) {
    if (value instanceof Object[]) return java.util.Arrays.asList((Object[]) value);
    if (value instanceof List) return new ArrayList<>((List<?>) value);
    return java.util.Collections.emptyList();
  }

  private ShadowInputMethodManager shadow() {
    return Shadow.extract(app.getSystemService(InputMethodManager.class));
  }
}
