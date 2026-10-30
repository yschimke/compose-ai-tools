package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * The home screen's activity, at the bottom of the default display's back stack under {@code
 * --launcher}. Plain {@code android.view}, no Compose: the app's Compose is the only one on the
 * sandbox's class path. It draws {@link HomeScreen}'s grid on a wallpaper — the app's icons, pinned
 * shortcuts and {@link WidgetHost}'s widgets, each an {@code AppWidgetHostView} — and the overlays
 * over it: the app drawer, a shortcut popup, the widget picker, the pin sheet and a widget's resize
 * frame.
 *
 * <p>Every view an agent may want carries a {@code sim:id/…} resource id ({@code icon}, {@code
 * widget}, {@code widget_resize}, {@code drawer_icon}, {@code shortcut}, …) for {@code find} and
 * {@code tap}, and labels as text or content descriptions.
 *
 * <p>Gestures, as on a phone: tap an icon to open the app; touch &amp; hold an icon for its
 * shortcuts, a widget for its resize frame, the wallpaper for the Widgets menu; swipe up for the
 * drawer; Back closes what is open.
 */
public class PhoneHome extends Activity {

  private HomeScreen home;
  private FrameLayout root;
  private FrameLayout overlay;
  private WidgetHost.Hosted resizing;

  public PhoneHome() {}

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    setTheme(android.R.style.Theme_Material_NoActionBar);
    super.onCreate(savedInstanceState);
    setTitle("Home");
    // Edge to edge, as a launcher is: the wallpaper runs on under the status bar.
    getWindow().setDecorFitsSystemWindows(false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarContrastEnforced(false);
    home = HomeScreen.current;
    home.attach(this);
    rebuild();
  }

  @Override
  protected void onDestroy() {
    if (home != null) home.detach(this);
    super.onDestroy();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    rebuild();
  }

  @Override
  public void onBackPressed() {
    // The home screen is the bottom of the stack: Back only closes what is open over it.
    if (home.overlay() != null && !"pin".equals(home.overlay())) home.closeOverlay();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    home.widgets.configured(requestCode, resultCode);
  }

  /** Lays the grid out again from {@link HomeScreen}: after any change to what is on it. */
  void rebuild() {
    for (WidgetHost.Hosted w : home.widgets.all()) {
      if (w.view != null && w.view.getParent() instanceof ViewGroup) {
        ((ViewGroup) w.view.getParent()).removeView(w.view);
      }
    }
    root = new HomeRoot(this);
    root.setBackground(wallpaper());
    CellLayout cells = new CellLayout(this, home.grid());
    simId(cells, "workspace");
    cells.setLongClickable(true);
    cells.setOnLongClickListener(
        v -> {
          home.setOverlay("menu");
          return true;
        });
    for (HomeScreen.Icon icon : home.icons()) {
      View view = iconView(icon, false);
      cells.addView(view, new CellLayout.Params(icon.cellX, icon.cellY, 1, 1));
    }
    for (WidgetHost.Hosted w : home.widgets.all()) {
      if (!w.placed) continue;
      AppWidgetHostView hostView = w.view != null ? w.view : home.widgets.createView(this, w);
      WidgetFrame frame = new WidgetFrame(this, w, hostView);
      cells.addView(frame, new CellLayout.Params(w.cellX, w.cellY, w.spanX, w.spanY));
    }
    root.addView(cells, new FrameLayout.LayoutParams(-1, -1));
    TextView handle = text(this, "⌃  Apps", 13, Color.WHITE);
    handle.setGravity(Gravity.CENTER);
    handle.setShadowLayer(4, 0, 1, 0x99000000);
    handle.setPadding(dp(16), dp(8), dp(16), dp(8));
    handle.setContentDescription("Apps");
    simId(handle, "drawer_handle");
    handle.setOnClickListener(v -> home.setOverlay("drawer"));
    root.addView(
        handle, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    ((FrameLayout.LayoutParams) handle.getLayoutParams()).bottomMargin = dp(16);
    // Clear of the status and navigation bars, which the wallpaper runs on under.
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(android.view.WindowInsets.Type.systemBars());
          FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cells.getLayoutParams();
          FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) handle.getLayoutParams();
          if (lp.topMargin != bars.top || lp.bottomMargin != bars.bottom) {
            lp.setMargins(bars.left, bars.top, bars.right, bars.bottom);
            cells.setLayoutParams(lp);
            hp.bottomMargin = dp(16) + bars.bottom;
            handle.setLayoutParams(hp);
          }
          return insets;
        });
    overlay = new FrameLayout(this);
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    if (home.overlay() != null) showOverlay(home.overlay());
  }

  private Drawable wallpaper() {
    GradientDrawable wallpaper =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF1A2A6C, 0xFF2E6F8E, 0xFF5FA8A0});
    return wallpaper;
  }

  // ---------------------------------------------------------------------------------------------
  // Icons

  private View iconView(HomeScreen.Icon icon, boolean inDrawer) {
    TextView view = text(this, icon.label == null ? "" : icon.label.toString(), 12, Color.WHITE);
    view.setGravity(Gravity.CENTER_HORIZONTAL);
    view.setSingleLine(true);
    view.setEllipsize(TextUtils.TruncateAt.END);
    view.setShadowLayer(4, 0, 1, 0xCC000000);
    view.setPadding(dp(2), dp(6), dp(2), 0);
    view.setCompoundDrawablePadding(dp(4));
    if (icon.icon != null) {
      Drawable drawable =
          icon.icon.getConstantState() != null
              ? icon.icon.getConstantState().newDrawable().mutate()
              : icon.icon;
      drawable.setBounds(0, 0, dp(48), dp(48));
      view.setCompoundDrawables(null, drawable, null, null);
    }
    simId(view, inDrawer ? "drawer_icon" : icon.kind.equals("shortcut") ? "shortcut_icon" : "icon");
    view.setOnClickListener(
        v -> {
          if (icon.kind.equals("shortcut")) {
            home.shortcuts.open(home.shortcuts.find(icon.shortcutId));
          } else {
            home.openApp(icon.activity);
          }
        });
    view.setOnLongClickListener(
        v -> {
          if (icon.activity != null) home.showShortcuts(icon.activity);
          return true;
        });
    return view;
  }

  // ---------------------------------------------------------------------------------------------
  // Overlays

  /** Shows overlay [kind] over the grid, replacing any other. */
  void showOverlay(String kind) {
    if (overlay == null) return;
    overlay.removeAllViews();
    resizing = null;
    int width = getResources().getDisplayMetrics().widthPixels;
    View view;
    switch (kind) {
      case "drawer":
        view = drawer();
        break;
      case "picker":
        view = home.picker.build(this, width);
        break;
      case "shortcuts":
        view = shortcutPopup(home.shortcutsFor());
        break;
      case "menu":
        view = menu();
        break;
      case "pin":
        {
          WidgetHost.PinRequest request = home.widgets.pendingPin();
          if (request == null) return;
          view = home.picker.buildPinSheet(this, request, width);
          break;
        }
      case "resize":
        return;
      default:
        throw new IllegalArgumentException("no overlay " + kind);
    }
    overlay.addView(view, new FrameLayout.LayoutParams(-1, -1));
  }

  void closeOverlay() {
    if (overlay != null) overlay.removeAllViews();
    resizing = null;
  }

  private View drawer() {
    FrameLayout sheet = new FrameLayout(this);
    sheet.setBackgroundColor(0xF2202124);
    sheet.setClickable(true);
    simId(sheet, "drawer");
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(8), dp(40), dp(8), dp(16));
    TextView title = text(this, "Apps", 22, Color.WHITE);
    title.setPadding(dp(8), 0, 0, dp(16));
    list.addView(title);
    LauncherGrid grid = home.grid();
    LinearLayout row = null;
    int i = 0;
    for (android.content.pm.ResolveInfo info : home.launchables()) {
      if (i % grid.columns == 0) {
        row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        list.addView(row);
      }
      HomeScreen.Icon icon =
          new HomeScreen.Icon(
              "app",
              new ComponentName(info.activityInfo.packageName, info.activityInfo.name),
              null,
              info.loadLabel(getPackageManager()),
              info.loadIcon(getPackageManager()));
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(dp(LauncherGrid.CELL_DP), dp(LauncherGrid.CELL_DP + 16));
      lp.leftMargin = dp(LauncherGrid.GAP_DP / 2f);
      lp.rightMargin = dp(LauncherGrid.GAP_DP / 2f);
      row.addView(iconView(icon, true), lp);
      i++;
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(list);
    sheet.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    return sheet;
  }

  /** The long-press popup of [activity]'s icon: its shortcuts, then Widgets when it has any. */
  private View shortcutPopup(ComponentName activity) {
    FrameLayout scrim = dismissingScrim();
    LinearLayout card = card();
    simId(card, "shortcuts");
    List<Shortcuts.Shortcut> shortcuts = home.shortcuts.forActivity(activity);
    for (Shortcuts.Shortcut shortcut : shortcuts) {
      TextView row =
          popupRow(
              shortcut.label() == null ? shortcut.id : shortcut.label().toString(), shortcut.icon);
      simId(row, "shortcut");
      row.setContentDescription(shortcut.id);
      row.setEnabled(shortcut.enabled);
      row.setOnClickListener(v -> home.shortcuts.open(shortcut));
      card.addView(row);
    }
    if (!home.widgets.providers().isEmpty()) {
      TextView widgets = popupRow("Widgets", null);
      simId(widgets, "shortcut_widgets");
      widgets.setOnClickListener(v -> home.setOverlay("picker"));
      card.addView(widgets);
    }
    if (card.getChildCount() == 0) {
      card.addView(popupRow("No shortcuts", null));
    }
    place(scrim, card, iconRect(activity));
    return scrim;
  }

  /** The long-press menu of the wallpaper. */
  private View menu() {
    FrameLayout scrim = dismissingScrim();
    LinearLayout card = card();
    simId(card, "home_menu");
    TextView widgets = popupRow("Widgets", null);
    simId(widgets, "home_widgets");
    widgets.setOnClickListener(v -> home.setOverlay("picker"));
    card.addView(widgets);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(200), -2, Gravity.CENTER);
    scrim.addView(card, lp);
    return scrim;
  }

  private FrameLayout dismissingScrim() {
    FrameLayout scrim = new FrameLayout(this);
    scrim.setBackgroundColor(0x33000000);
    scrim.setOnClickListener(v -> home.closeOverlay());
    return scrim;
  }

  private LinearLayout card() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(0, dp(8), 0, dp(8));
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xFFF1F3F4);
    background.setCornerRadius(dp(20));
    card.setBackground(background);
    card.setElevation(dp(8));
    card.setClickable(true);
    return card;
  }

  private TextView popupRow(String label, Drawable icon) {
    TextView row = text(this, label, 15, 0xFF202124);
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setCompoundDrawablePadding(dp(12));
    if (icon != null) {
      Drawable d =
          icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
      d.setBounds(0, 0, dp(24), dp(24));
      row.setCompoundDrawables(d, null, null, null);
    }
    row.setClickable(true);
    return row;
  }

  /** Puts [card] above the item at [rect] (below it when there is no room), within the screen. */
  private void place(FrameLayout scrim, View card, int[] rect) {
    int width = dp(240);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -2);
    int screenW = getResources().getDisplayMetrics().widthPixels;
    int screenH = getResources().getDisplayMetrics().heightPixels;
    card.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
    int h = card.getMeasuredHeight();
    if (rect == null) {
      lp.gravity = Gravity.CENTER;
    } else {
      lp.leftMargin = Math.max(dp(8), Math.min(rect[0], screenW - width - dp(8)));
      int above = rect[1] - h - dp(8);
      lp.topMargin = above >= dp(24) ? above : Math.min(rect[3] + dp(8), screenH - h - dp(8));
    }
    scrim.addView(card, lp);
  }

  private int[] iconRect(ComponentName activity) {
    for (HomeScreen.Icon icon : home.icons()) {
      if (icon.kind.equals("app") && icon.activity.equals(activity)) {
        return home.grid().rect(icon.cellX, icon.cellY, 1, 1);
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Widgets

  /** A widget's resize frame: handles to drag, and Remove (and Settings, if reconfigurable). */
  void showResizeFrame(WidgetHost.Hosted w) {
    home.setOverlay("resize");
    overlay.removeAllViews();
    resizing = w;
    FrameLayout scrim = dismissingScrim();
    int[] rect = home.grid().rect(w.cellX, w.cellY, w.spanX, w.spanY);
    FrameLayout frame = new FrameLayout(this);
    GradientDrawable border = new GradientDrawable();
    border.setStroke(dp(2), Color.WHITE);
    border.setCornerRadius(dp(16));
    frame.setBackground(border);
    simId(frame, "widget_resize");
    frame.setContentDescription(
        home.widgets.label(w.info) + " widget, " + w.spanX + " by " + w.spanY);
    FrameLayout.LayoutParams flp =
        new FrameLayout.LayoutParams(rect[2] - rect[0], rect[3] - rect[1]);
    flp.leftMargin = rect[0];
    flp.topMargin = rect[1];
    scrim.addView(frame, flp);
    View handle = new View(this);
    GradientDrawable dot = new GradientDrawable();
    dot.setShape(GradientDrawable.OVAL);
    dot.setColor(Color.WHITE);
    handle.setBackground(dot);
    handle.setContentDescription("Resize");
    simId(handle, "widget_resize_handle");
    int size = dp(20);
    FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(size, size);
    hlp.leftMargin = rect[2] - size / 2;
    hlp.topMargin = rect[3] - size / 2;
    handle.setOnTouchListener(new ResizeDrag(w));
    scrim.addView(handle, hlp);
    LinearLayout actions = new LinearLayout(this);
    TextView remove = button(this, "Remove", false);
    simId(remove, "widget_remove");
    remove.setOnClickListener(
        v -> {
          home.closeOverlay();
          home.widgets.remove(w);
        });
    actions.addView(remove);
    if (w.info.configure != null
        && (w.info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0) {
      TextView settings = button(this, "Settings", false);
      simId(settings, "widget_reconfigure");
      settings.setOnClickListener(v -> home.configure(w, w.info.configure));
      actions.addView(settings);
    }
    FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(-2, -2);
    alp.leftMargin = rect[0];
    alp.topMargin = rect[1] >= dp(56) ? rect[1] - dp(48) : rect[3] + dp(8);
    scrim.addView(actions, alp);
    overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
  }

  /** Dragging the resize handle: the span follows the drag, in whole cells, when let go. */
  private final class ResizeDrag implements View.OnTouchListener {
    private final WidgetHost.Hosted w;
    private float downX;
    private float downY;

    ResizeDrag(WidgetHost.Hosted w) {
      this.w = w;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = e.getRawX();
          downY = e.getRawY();
          return true;
        case MotionEvent.ACTION_MOVE:
          v.setTranslationX(e.getRawX() - downX);
          v.setTranslationY(e.getRawY() - downY);
          return true;
        case MotionEvent.ACTION_UP:
          {
            float step =
                (LauncherGrid.CELL_DP + LauncherGrid.GAP_DP)
                    * getResources().getDisplayMetrics().density;
            int sx = Math.max(1, w.spanX + Math.round((e.getRawX() - downX) / step));
            int sy = Math.max(1, w.spanY + Math.round((e.getRawY() - downY) / step));
            int[] min = home.widgets.minSpan(w.info);
            int[] max = home.widgets.maxSpan(w.info);
            sx = Math.max(min[0], Math.min(max[0], sx));
            sy = Math.max(min[1], Math.min(max[1], sy));
            try {
              if (sx != w.spanX || sy != w.spanY) home.widgets.resize(w, sx, sy);
            } catch (RuntimeException ex) {
              home.protocol()
                  .event(
                      "error",
                      new Protocol.Json()
                          .put("kind", "widgetResize")
                          .put("message", String.valueOf(ex))
                          .object());
            }
            showResizeFrame(w);
            return true;
          }
        default:
          return true;
      }
    }
  }

  /**
   * A placed widget: its host view, with the touch &amp; hold that opens the resize frame taken
   * before the widget's own views see it, as a launcher's long-press helper does.
   */
  private final class WidgetFrame extends FrameLayout {
    private final WidgetHost.Hosted w;
    private final Runnable longPress;
    private boolean longPressed;
    private float downX;
    private float downY;

    WidgetFrame(Context context, WidgetHost.Hosted w, AppWidgetHostView hostView) {
      super(context);
      this.w = w;
      addView(hostView, new FrameLayout.LayoutParams(-1, -1));
      setContentDescription(home.widgets.label(w.info) + " widget");
      simId(this, "widget");
      longPress =
          () -> {
            longPressed = true;
            showResizeFrame(w);
          };
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          longPressed = false;
          downX = e.getX();
          downY = e.getY();
          postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
          break;
        case MotionEvent.ACTION_MOVE:
          float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
          if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
            removeCallbacks(longPress);
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          removeCallbacks(longPress);
          break;
        default:
          break;
      }
      return longPressed;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (e.getActionMasked() == MotionEvent.ACTION_UP
          || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        removeCallbacks(longPress);
        longPressed = false;
      }
      return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      info.setLongClickable(true);
      info.setText(null);
      info.setContentDescription(
          home.widgets.label(w.info) + " widget " + w.id + ", " + w.spanX + "×" + w.spanY);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The workspace

  /** The wallpaper and everything on it; a swipe up from the grid opens the drawer. */
  private final class HomeRoot extends FrameLayout {
    private float downX;
    private float downY;
    private boolean tracking;

    HomeRoot(Context context) {
      super(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
      if (home.overlay() != null) return false;
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = e.getX();
          downY = e.getY();
          tracking = !onWidget(e.getX(), e.getY());
          return false;
        case MotionEvent.ACTION_MOVE:
          return tracking && swipedUp(e);
        default:
          return false;
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (e.getActionMasked() == MotionEvent.ACTION_UP && tracking && swipedUp(e)) {
        tracking = false;
        home.setOverlay("drawer");
      }
      return true;
    }

    private boolean swipedUp(MotionEvent e) {
      float dy = downY - e.getY();
      return dy > dp(64) && dy > 2 * Math.abs(e.getX() - downX);
    }

    private boolean onWidget(float x, float y) {
      for (WidgetHost.Hosted w : home.widgets.all()) {
        if (!w.placed) continue;
        int[] r = home.grid().rect(w.cellX, w.cellY, w.spanX, w.spanY);
        if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return true;
      }
      return false;
    }
  }

  /** Lays children out on {@link LauncherGrid} cells. */
  static final class CellLayout extends ViewGroup {
    static final class Params extends ViewGroup.LayoutParams {
      final int cellX;
      final int cellY;
      final int spanX;
      final int spanY;

      Params(int cellX, int cellY, int spanX, int spanY) {
        super(-2, -2);
        this.cellX = cellX;
        this.cellY = cellY;
        this.spanX = spanX;
        this.spanY = spanY;
      }
    }

    private final LauncherGrid grid;

    CellLayout(Context context, LauncherGrid grid) {
      super(context);
      this.grid = grid;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        Params p = (Params) child.getLayoutParams();
        int[] r = grid.rect(p.cellX, p.cellY, p.spanX, p.spanY);
        child.measure(
            MeasureSpec.makeMeasureSpec(r[2] - r[0], MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(r[3] - r[1], MeasureSpec.EXACTLY));
      }
      setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        Params p = (Params) child.getLayoutParams();
        int[] rect = grid.rect(p.cellX, p.cellY, p.spanX, p.spanY);
        child.layout(rect[0], rect[1], rect[2], rect[3]);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private int dp(float dp) {
    return Math.round(dp * getResources().getDisplayMetrics().density);
  }

  static TextView text(Context context, String text, float sp, int color) {
    TextView view = new TextView(context);
    view.setText(text);
    view.setTextSize(sp);
    view.setTextColor(color);
    return view;
  }

  static TextView button(Context context, String label, boolean primary) {
    TextView button = text(context, label, 14, primary ? 0xFF062E6F : 0xFFA8C7FA);
    float density = context.getResources().getDisplayMetrics().density;
    int h = Math.round(16 * density);
    int v = Math.round(10 * density);
    button.setPadding(h, v, h, v);
    GradientDrawable background = new GradientDrawable();
    background.setCornerRadius(20 * density);
    background.setColor(primary ? 0xFFA8C7FA : 0x00000000);
    button.setBackground(background);
    button.setClickable(true);
    return button;
  }

  /**
   * Gives [view] the resource id {@code sim:id/<name>} in the hierarchy, which the framework only
   * reports for ids in a package's resources.
   */
  static void simId(View view, String name) {
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
