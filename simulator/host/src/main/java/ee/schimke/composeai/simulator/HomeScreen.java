package ee.schimke.composeai.simulator;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * The home screen of {@code --launcher}: what a phone's launcher keeps and decides, drawn by {@link
 * PhoneHome}. It holds the grid (sized from the display, re-flowed when the display changes), the
 * app's icons on it, pinned shortcuts, and the widgets {@link WidgetHost} placed; it opens the app
 * drawer, a shortcut popup, the widget picker and the pin sheet; and it moves between the home
 * screen and the app ({@code HOME}, an icon tap) through {@link Navigator}, which keeps {@link
 * PhoneHome} at the bottom of the default display's back stack.
 *
 * <p>The secondary displays get no launcher. Runs on the main thread.
 */
final class HomeScreen {

  static final String PROPERTY = "composeai.simulator.launcher";

  static boolean enabled() {
    return "true".equals(System.getProperty(PROPERTY));
  }

  /** The session's home screen, for {@link PhoneHome}; null without {@code --launcher}. */
  static volatile HomeScreen current;

  /** An icon on the grid: one of the app's launcher activities, or a pinned shortcut. */
  static final class Icon {
    final String kind;
    final ComponentName activity;
    final String shortcutId;
    final CharSequence label;
    final Drawable icon;
    int cellX;
    int cellY;

    Icon(
        String kind, ComponentName activity, String shortcutId, CharSequence label, Drawable icon) {
      this.kind = kind;
      this.activity = activity;
      this.shortcutId = shortcutId;
      this.label = label;
      this.icon = icon;
    }
  }

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  final WidgetHost widgets;
  final Shortcuts shortcuts;
  final WidgetPicker picker;
  private final List<Icon> icons = new ArrayList<>();
  private LauncherGrid grid;

  /** What shows over the grid: null, or drawer, picker, shortcuts, menu, pin, resize. */
  private String overlay;

  private PhoneHome activity;
  private List<String> startupWidgets = new ArrayList<>();

  HomeScreen(Application app, Navigator navigator, Protocol protocol, WidgetHost widgets) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.widgets = widgets;
    this.shortcuts = new Shortcuts(app, navigator, protocol, this);
    this.picker = new WidgetPicker(this);
    widgets.attach(this);
    current = this;
  }

  /**
   * Puts the home screen at the bottom of the default display's stack, before the app's launcher
   * activity starts over it, with the app's launcher icons in its bottom row.
   */
  void start() {
    grid = currentGrid();
    for (ResolveInfo info : deviceLaunchables()) {
      Icon icon = iconOf(info);
      int[] cell = findIconCell(true);
      if (cell == null) break;
      icon.cellX = cell[0];
      icon.cellY = cell[1];
      icons.add(icon);
    }
    String requested = System.getProperty(WidgetHost.WIDGETS_PROPERTY);
    if (requested != null) {
      for (String name : requested.split(";")) if (!name.isBlank()) startupWidgets.add(name.trim());
    }
    registerActivity();
    navigator.startHome(
        new PhoneHome(),
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(new ComponentName(app, PhoneHome.class)));
  }

  /**
   * Declares {@link PhoneHome} to the package manager, as {@code ActivityController} would on first
   * attach, but handling every configuration change itself (it re-lays the grid) and with a theme
   * of its own rather than the app's.
   */
  private void registerActivity() {
    ActivityInfo info = new ActivityInfo();
    info.name = PhoneHome.class.getName();
    info.packageName = app.getPackageName();
    info.applicationInfo = app.getApplicationInfo();
    info.exported = false;
    info.enabled = true;
    info.theme = android.R.style.Theme_Material_NoActionBar;
    info.configChanges = 0x7fffffff;
    info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    // As the manifest parser marks every activity of an app targeting API 14+: Robolectric's
    // screenshots read what the hardware renderer drew.
    info.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
    ShadowPackageManager shadow = Shadow.extract(app.getPackageManager());
    shadow.addOrUpdateActivity(info);
  }

  /** The app's launchable activities, {@code <activity-alias>}es included. */
  List<ResolveInfo> launchables() {
    Intent launcher =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(app.getPackageName());
    return app.getPackageManager().queryIntentActivities(launcher, 0);
  }

  /**
   * The launchable activities of every app on the device: the app's own ({@link #launchables}),
   * then those of the device's other apps ({@link DeviceApps}), in launch order.
   */
  List<ResolveInfo> deviceLaunchables() {
    List<ResolveInfo> all = new ArrayList<>(launchables());
    if (!DeviceBus.enabled()) return all;
    for (String peer : DevicePeers.packages()) {
      Intent launcher =
          new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(peer);
      all.addAll(app.getPackageManager().queryIntentActivities(launcher, 0));
    }
    return all;
  }

  /**
   * [info]'s icon on the grid or in the drawer. Another app's resources are in its own process,
   * not this one: its icon is the default activity icon, and its label the manifest's literal one,
   * else the one the app told ({@link DeviceApps#labelOf}), else the last part of its package.
   */
  Icon iconOf(ResolveInfo info) {
    ComponentName activity =
        new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    PackageManager pm = app.getPackageManager();
    if (activity.getPackageName().equals(app.getPackageName())) {
      return new Icon("app", activity, null, info.loadLabel(pm), info.loadIcon(pm));
    }
    CharSequence label = info.nonLocalizedLabel;
    if (label == null) label = info.activityInfo.nonLocalizedLabel;
    if (label == null) label = DeviceApps.labelOf(activity.getPackageName());
    if (label == null && info.activityInfo.applicationInfo != null) {
      label = info.activityInfo.applicationInfo.nonLocalizedLabel;
    }
    if (label == null) {
      String pkg = activity.getPackageName();
      label = pkg.substring(pkg.lastIndexOf('.') + 1);
    }
    return new Icon("app", activity, null, label, pm.getDefaultActivityIcon());
  }

  /** Another of the device's apps told its label: its icons on the grid take it. */
  void relabel(String packageName) {
    PackageManager pm = app.getPackageManager();
    for (int i = 0; i < icons.size(); i++) {
      Icon old = icons.get(i);
      if (old.activity == null || !old.activity.getPackageName().equals(packageName)) continue;
      ResolveInfo info = pm.resolveActivity(new Intent().setComponent(old.activity), 0);
      if (info == null) continue;
      Icon icon = iconOf(info);
      icon.cellX = old.cellX;
      icon.cellY = old.cellY;
      icons.set(i, icon);
    }
    if (activity != null) activity.rebuild();
  }

  /** One simulator cycle: re-flow on a display change, widgets placed at launch, new pins. */
  void cycle() {
    LauncherGrid now = currentGrid();
    if (!now.same(grid)) {
      grid = now;
      reflow();
    }
    if (!startupWidgets.isEmpty() && navigator.hasAppActivity(Display.DEFAULT_DISPLAY)) {
      List<String> names = startupWidgets;
      startupWidgets = new ArrayList<>();
      // As a person would: the app has started once; home, then add the widget.
      navigator.goHome();
      for (String name : names) {
        try {
          widgets.add(widgets.provider(name), null, null, null, null, false);
        } catch (RuntimeException e) {
          protocol.event(
              "error",
              new Protocol.Json()
                  .put("kind", "widget")
                  .put("provider", name)
                  .put("message", String.valueOf(e))
                  .object());
        }
      }
    }
    shortcuts.cycle();
    widgets.cycle();
  }

  private LauncherGrid currentGrid() {
    Configuration config = app.getResources().getConfiguration();
    return new LauncherGrid(
        config.screenWidthDp,
        config.screenHeightDp,
        app.getResources().getDisplayMetrics().density);
  }

  LauncherGrid grid() {
    return grid;
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /** The control-channel commands the home screen owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    JSONObject handled = widgets.command(name, c);
    if (handled == null) handled = shortcuts.command(name, c);
    if (handled == null) handled = picker.command(name, c);
    if (handled != null) return handled;
    switch (name) {
      case "home":
        goHome();
        return describe();
      case "drawer":
        {
          String action = c.optString("action", c.optString("value", "open"));
          if (action.equals("open")) {
            showHome();
            setOverlay("drawer");
          } else if (action.equals("close")) {
            if ("drawer".equals(overlay)) closeOverlay();
          } else {
            throw new IllegalArgumentException("drawer takes open or close, not " + action);
          }
          return describe();
        }
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    switch (name) {
      case "home":
      case "drawer":
      case "shortcut":
      case "shortcuts":
      case "picker":
        return true;
      default:
        return WidgetHost.changesUi(name);
    }
  }

  /** The commands that need {@code --launcher}, refused without it. */
  static boolean needsLauncher(String name) {
    switch (name) {
      case "home":
      case "drawer":
      case "shortcut":
      case "shortcuts":
      case "picker":
      case "widget":
        return true;
      default:
        return false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Navigation

  /** {@code HOME}: the app goes to the background, overlays close, the home screen shows. */
  JSONObject goHome() {
    // HOME on a split screen ends it: the home screen takes the whole display.
    DeviceApps apps = DeviceApps.current();
    if (apps != null) apps.endSplitForHome();
    overlay = null;
    if (activity != null) activity.closeOverlay();
    return navigator.goHome();
  }

  /** The home screen on top, as it is before an overlay opens; the app is left as it is if so. */
  private void showHome() {
    Navigator.Entry top = navigator.top(Display.DEFAULT_DISPLAY);
    if (top == null || !top.system) navigator.goHome();
  }

  /**
   * An icon was tapped: the app's task comes forward when it runs that activity at its root, as a
   * launcher's {@code NEW_TASK} start brings an existing task forward; otherwise the activity
   * starts, in the app's task.
   */
  JSONObject openApp(ComponentName activity) {
    closeOverlay();
    if (DeviceApps.isOtherApp(activity.getPackageName())) {
      // Another of the device's apps: it comes on screen, and this one goes to the background.
      try {
        return DeviceApps.current()
            .show(activity.getPackageName(), DeviceApps.launcherOf(activity));
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
    Navigator.Entry root = appRoot();
    if (root != null
        && root.activity().getComponentName().getClassName().equals(className(activity))) {
      return navigator.bringAppForward(true);
    }
    Intent launch =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    return navigator.startActivity(launch, -1);
  }

  /** The class [component] runs: itself, or an {@code <activity-alias>}'s target. */
  private String className(ComponentName component) {
    try {
      ActivityInfo info = app.getPackageManager().getActivityInfo(component, 0);
      if (info.targetActivity != null) return info.targetActivity;
    } catch (PackageManager.NameNotFoundException e) {
      // Not in the manifest: its own name.
    }
    return component.getClassName();
  }

  /** The bottom-most of the app's activities on the default display: its task's root. */
  private Navigator.Entry appRoot() {
    List<Navigator.Entry> stack = navigator.backStack();
    for (int i = stack.size() - 1; i >= 0; i--) {
      Navigator.Entry entry = stack.get(i);
      if (!entry.system && entry.displayId == Display.DEFAULT_DISPLAY) return entry;
    }
    return null;
  }

  /** The launcher activity, as {@code launch} with {@code --launcher} starts it from an icon. */
  JSONObject launch() {
    Intent launcher = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
    if (launcher == null || launcher.getComponent() == null) {
      throw new IllegalStateException("the app has no launcher activity");
    }
    return openApp(launcher.getComponent());
  }

  /**
   * Starts a widget's configure activity for [hosted] from the home screen, for a result, as a
   * launcher does when the widget is dropped; the answer comes back to {@link PhoneHome}.
   */
  void configure(WidgetHost.Hosted hosted, ComponentName configure) {
    showHome();
    closeOverlay();
    if (activity == null) throw new IllegalStateException("the home screen is not running");
    widgets.configuring(hosted);
    protocol.event(
        "widget",
        new Protocol.Json()
            .put("state", "configuring")
            .put("widgetId", hosted.id)
            .put("provider", hosted.info.provider.getClassName())
            .put("configure", configure.getClassName())
            .object());
    activity.startActivityForResult(
        new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, hosted.id),
        hosted.id);
  }

  /** The launcher activity whose shortcuts the popup shows. */
  private ComponentName shortcutsFor;

  ComponentName shortcutsFor() {
    return shortcutsFor;
  }

  /** A long press on [activity]'s icon: its shortcuts, and the app's widgets if it has any. */
  void showShortcuts(ComponentName activity) {
    if (activity == null) throw new IllegalStateException("the app has no launcher activity");
    showHome();
    shortcutsFor = activity;
    setOverlay("shortcuts");
  }

  /** The app asked to pin a widget: the home screen comes forward with the confirm sheet. */
  void showPinRequest(android.appwidget.AppWidgetProviderInfo info) {
    showHome();
    setOverlay("pin");
  }

  // ---------------------------------------------------------------------------------------------
  // Overlays

  String overlay() {
    return overlay;
  }

  void setOverlay(String kind) {
    overlay = kind;
    if (activity != null) activity.showOverlay(kind);
    protocol.event(
        "home", new Protocol.Json().put("surface", surface()).put("overlay", kind).object());
  }

  /** Closes what shows over the grid; a waiting pin request stays until it is answered. */
  void closeOverlay() {
    if (overlay == null) return;
    overlay = null;
    if (activity != null) activity.closeOverlay();
    protocol.event("home", new Protocol.Json().put("surface", surface()).object());
  }

  /** What the default display shows: {@code app}, {@code home}, or an overlay over home. */
  String surface() {
    Navigator.Entry top = navigator.top(Display.DEFAULT_DISPLAY);
    if (top == null || !top.system) return "app";
    if ("drawer".equals(overlay) || "picker".equals(overlay)) return overlay;
    return "home";
  }

  // ---------------------------------------------------------------------------------------------
  // The grid

  List<Icon> icons() {
    return icons;
  }

  void addIcon(Icon icon) {
    int[] cell = findIconCell(false);
    if (cell == null) {
      throw new IllegalStateException("no room on the home screen for " + icon.label);
    }
    icon.cellX = cell[0];
    icon.cellY = cell[1];
    icons.add(icon);
    if (activity != null) activity.rebuild();
  }

  /** A free cell for an icon: the bottom row first for the app's own, the top first otherwise. */
  private int[] findIconCell(boolean bottomUp) {
    for (int i = 0; i < grid.rows; i++) {
      int y = bottomUp ? grid.rows - 1 - i : i;
      for (int x = 0; x < grid.columns; x++) {
        if (fits(x, y, 1, 1, -1, null)) return new int[] {x, y};
      }
    }
    return null;
  }

  /**
   * A free place for a [spanX]×[spanY] widget: at [cellX],[cellY] when both are given (null if it
   * does not fit there), else the first free one, row by row from the top. Widget [ignoreId]'s own
   * cells count as free.
   */
  int[] findCell(int spanX, int spanY, Integer cellX, Integer cellY, int ignoreId) {
    if (cellX != null && cellY != null) {
      return fits(cellX, cellY, spanX, spanY, ignoreId) ? new int[] {cellX, cellY} : null;
    }
    for (int y = 0; y + spanY <= grid.rows; y++) {
      for (int x = 0; x + spanX <= grid.columns; x++) {
        if (fits(x, y, spanX, spanY, ignoreId)) return new int[] {x, y};
      }
    }
    return null;
  }

  boolean fits(int cellX, int cellY, int spanX, int spanY, int ignoreId) {
    return fits(cellX, cellY, spanX, spanY, ignoreId, null);
  }

  private boolean fits(int cellX, int cellY, int spanX, int spanY, int ignoreId, Icon ignoreIcon) {
    if (cellX < 0 || cellY < 0 || cellX + spanX > grid.columns || cellY + spanY > grid.rows) {
      return false;
    }
    for (Icon icon : icons) {
      if (icon == ignoreIcon) continue;
      if (overlaps(cellX, cellY, spanX, spanY, icon.cellX, icon.cellY, 1, 1)) return false;
    }
    for (WidgetHost.Hosted w : widgets.all()) {
      if (w.id == ignoreId) continue;
      if (overlaps(cellX, cellY, spanX, spanY, w.cellX, w.cellY, w.spanX, w.spanY)) return false;
    }
    return true;
  }

  private static boolean overlaps(int x1, int y1, int w1, int h1, int x2, int y2, int w2, int h2) {
    return x1 < x2 + w2 && x2 < x1 + w1 && y1 < y2 + h2 && y2 < y1 + h1;
  }

  /**
   * The display changed size: what still fits stays; icons that do not move to a free cell, and
   * widgets that do not shrink to fit (within their minimum) and move, as a launcher re-flows its
   * workspace. A widget that changed size hears of it through its options.
   */
  private void reflow() {
    List<Icon> movedIcons = new ArrayList<>();
    for (Icon icon : icons) {
      if (icon.cellX >= grid.columns || icon.cellY >= grid.rows) movedIcons.add(icon);
    }
    icons.removeAll(movedIcons);
    for (WidgetHost.Hosted w : widgets.all()) {
      if (w.cellX + w.spanX <= grid.columns && w.cellY + w.spanY <= grid.rows) continue;
      int[] min = widgets.minSpan(w.info);
      int sx = Math.max(min[0], Math.min(w.spanX, grid.columns));
      int sy = Math.max(min[1], Math.min(w.spanY, grid.rows));
      // Off the grid while it finds a place, so its old cells do not block it.
      int[] cell = findCell(sx, sy, null, null, w.id);
      if (cell == null) {
        cell = new int[] {0, 0};
      }
      widgets.reflowed(w, cell[0], cell[1], sx, sy);
    }
    for (Icon icon : movedIcons) {
      int[] cell = findIconCell(icon.kind.equals("app"));
      if (cell == null) continue;
      icon.cellX = cell[0];
      icon.cellY = cell[1];
      icons.add(icon);
    }
    if (activity != null) activity.rebuild();
    protocol.event(
        "home", new Protocol.Json().put("state", "reflowed").put("grid", describeGrid()).object());
  }

  void widgetsChanged() {
    if (activity != null) activity.rebuild();
  }

  String describeGrid() {
    return grid.toString();
  }

  JSONObject describe() {
    JSONArray items = new JSONArray();
    for (Icon icon : icons) {
      items.put(
          new Protocol.Json()
              .put("kind", icon.kind)
              .put("label", icon.label == null ? null : icon.label.toString())
              .put("activity", icon.activity == null ? null : icon.activity.getClassName())
              .put("shortcutId", icon.shortcutId)
              .put("cellX", icon.cellX)
              .put("cellY", icon.cellY)
              .object());
    }
    for (WidgetHost.Hosted w : widgets.all()) {
      JSONObject item = widgets.describe(w);
      try {
        item.put("kind", "widget");
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      items.put(item);
    }
    return new Protocol.Json()
        .put("surface", surface())
        .put("overlay", overlay)
        .put(
            "grid",
            new Protocol.Json()
                .put("columns", grid.columns)
                .put("rows", grid.rows)
                .put("cellDp", LauncherGrid.CELL_DP)
                .put("gapDp", LauncherGrid.GAP_DP)
                .object())
        .put("items", items)
        .object();
  }

  // ---------------------------------------------------------------------------------------------

  void attach(PhoneHome activity) {
    this.activity = activity;
  }

  void detach(PhoneHome activity) {
    if (this.activity == activity) this.activity = null;
  }

  Application app() {
    return app;
  }

  Navigator navigator() {
    return navigator;
  }

  Protocol protocol() {
    return protocol;
  }
}
