package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SizeF;
import android.util.Xml;
import android.view.View;
import android.widget.RemoteViews;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.xmlpull.v1.XmlPullParser;

/**
 * The system side of the app's widgets, as {@code AppWidgetService} and a launcher between them
 * play it on a device: finds the app's providers in its manifest, binds widget ids to them, sends
 * the {@code APPWIDGET_*} broadcasts, keeps each widget's options, takes what the app passes to
 * {@code AppWidgetManager.updateAppWidget} (through {@link SimulatorShadowAppWidgetManager}) and
 * draws it in an {@link AppWidgetHostView} on {@link PhoneHome}, and sends the widget's {@code
 * PendingIntent}s when its views are tapped.
 *
 * <p>Sizes follow the preview runtime's launcher grid ({@link LauncherGrid}), so a 4×2 widget is
 * 312×152dp in both a preview and here.
 *
 * <p>The app calls {@code AppWidgetManager} from any thread, as it may on a device; state is
 * guarded by this object and view work is handed to the main thread.
 */
final class WidgetHost {

  /** {@code --widget} providers to place at launch, separated by {@code ;}. */
  static final String WIDGETS_PROPERTY = "composeai.simulator.widgets";

  /** The session's host, for {@link SimulatorShadowAppWidgetManager}; null before it starts. */
  static volatile WidgetHost current;

  /** A widget the host has bound: placed on the home screen, or still being configured. */
  static final class Hosted {
    final int id;
    final AppWidgetProviderInfo info;
    int cellX;
    int cellY;
    int spanX;
    int spanY;
    Bundle options = new Bundle();

    /** The views as the app last sent them, partial updates merged in. */
    RemoteViews views;

    int updates;
    long lastUpdateUptime = -1;
    String lastLayout;

    /** Placed on the grid; false while its configure activity runs. */
    boolean placed;

    /** The host-side cause of the next update, and when it was set: see {@link #reason}. */
    String pendingReason = "bind";

    long pendingReasonAt;

    /** Drawn on the home screen, while {@link PhoneHome} has a view for it. */
    AppWidgetHostView view;

    Hosted(int id, AppWidgetProviderInfo info) {
      this.id = id;
      this.info = info;
    }
  }

  /** A pin request from the app ({@code requestPinAppWidget}) waiting for an answer. */
  static final class PinRequest {
    final AppWidgetProviderInfo info;
    final Bundle extras;
    final PendingIntent callback;

    PinRequest(AppWidgetProviderInfo info, Bundle extras, PendingIntent callback) {
      this.info = info;
      this.extras = extras;
      this.callback = callback;
    }
  }

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final Handler main = new Handler(Looper.getMainLooper());
  private List<AppWidgetProviderInfo> providers;
  private final Map<Integer, Hosted> widgets = new LinkedHashMap<>();
  private final Map<ComponentName, RemoteViews> generatedPreviews = new LinkedHashMap<>();
  private final Map<ComponentName, Long> lastPeriodicUpdate = new LinkedHashMap<>();
  private int nextId = 1;
  private PinRequest pin;

  /** The home screen that draws and places widgets: set with {@code --launcher}. */
  private HomeScreen home;

  WidgetHost(Application app, Navigator navigator, Protocol protocol) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    current = this;
  }

  void attach(HomeScreen home) {
    this.home = home;
  }

  boolean hasHome() {
    return home != null;
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "widgets":
        return describe();
      case "widget":
        break;
      default:
        return null;
    }
    String action = c.optString("action", c.optString("value", ""));
    switch (action) {
      case "add":
        return add(
            provider(c.getString("provider")),
            optInt(c, "cellX"),
            optInt(c, "cellY"),
            optInt(c, "spanX"),
            optInt(c, "spanY"),
            c.optBoolean("configure", false));
      case "remove":
        return remove(hosted(c));
      case "resize":
        return resize(hosted(c), c.getInt("spanX"), c.getInt("spanY"));
      case "update":
        return update(c.has("widgetId") ? hosted(c) : null);
      case "reconfigure":
        return reconfigure(hosted(c));
      case "pin":
        {
          String answer = c.optString("answer", c.optString("decision", ""));
          if (answer.isEmpty() && c.has("value") && c.has("action")) answer = c.getString("value");
          return answerPin("accept".equals(answer) || c.optBoolean("accept", false));
        }
      default:
        throw new IllegalArgumentException(
            "unknown widget action " + action + " (add, remove, resize, update, reconfigure, pin)");
    }
  }

  static boolean changesUi(String name) {
    return name.equals("widget");
  }

  private static Integer optInt(JSONObject c, String key) throws JSONException {
    return c.has(key) ? c.getInt(key) : null;
  }

  /** The widget a command names with {@code widgetId}: {@code id} is the command's own. */
  private Hosted hosted(JSONObject c) throws JSONException {
    if (!c.has("widgetId")) throw new IllegalArgumentException("name the widget with widgetId");
    int id = c.getInt("widgetId");
    Hosted hosted;
    synchronized (this) {
      hosted = widgets.get(id);
    }
    if (hosted == null) throw new IllegalArgumentException("no widget " + id + " (see widgets)");
    return hosted;
  }

  /**
   * The provider [name] means: a component, a class name, a short name, or the receiver's label.
   */
  AppWidgetProviderInfo provider(String name) {
    List<AppWidgetProviderInfo> all = providers();
    for (AppWidgetProviderInfo info : all) {
      ComponentName c = info.provider;
      if (name.equals(c.flattenToString())
          || name.equals(c.flattenToShortString())
          || name.equals(c.getClassName())
          || name.equals(c.getShortClassName())
          || c.getClassName().endsWith("." + name)
          || name.equals(label(info))) {
        return info;
      }
    }
    JSONArray names = new JSONArray();
    for (AppWidgetProviderInfo info : all) names.put(info.provider.getClassName());
    throw new IllegalArgumentException("no widget provider " + name + "; the app has " + names);
  }

  // ---------------------------------------------------------------------------------------------
  // Providers

  /**
   * The app's widget providers: manifest receivers handling {@code APPWIDGET_UPDATE} with {@code
   * android.appwidget.provider} metadata, each parsed as {@code AppWidgetService} parses it (sizes
   * in pixels of the default display).
   */
  synchronized List<AppWidgetProviderInfo> providers() {
    if (providers != null) return providers;
    providers = new ArrayList<>();
    PackageManager pm = app.getPackageManager();
    Intent update =
        new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(app.getPackageName());
    for (ResolveInfo resolved : pm.queryBroadcastReceivers(update, PackageManager.GET_META_DATA)) {
      ActivityInfo receiver = resolved.activityInfo;
      if (receiver == null) continue;
      ComponentName component = new ComponentName(receiver.packageName, receiver.name);
      Bundle meta = receiver.metaData;
      if (meta == null) {
        try {
          meta = pm.getReceiverInfo(component, PackageManager.GET_META_DATA).metaData;
        } catch (PackageManager.NameNotFoundException e) {
          meta = null;
        }
      }
      int xml = meta == null ? 0 : meta.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER);
      if (xml == 0) continue;
      try {
        providers.add(parse(component, receiver, xml));
      } catch (RuntimeException | java.io.IOException | org.xmlpull.v1.XmlPullParserException e) {
        protocol.event(
            "error",
            new Protocol.Json()
                .put("kind", "widgetProvider")
                .put("provider", component.flattenToShortString())
                .put("message", String.valueOf(e))
                .object());
      }
    }
    return providers;
  }

  private static final int[] PROVIDER_ATTRS = {
    android.R.attr.minWidth,
    android.R.attr.minHeight,
    android.R.attr.minResizeWidth,
    android.R.attr.minResizeHeight,
    android.R.attr.maxResizeWidth,
    android.R.attr.maxResizeHeight,
    android.R.attr.targetCellWidth,
    android.R.attr.targetCellHeight,
    android.R.attr.updatePeriodMillis,
    android.R.attr.initialLayout,
    android.R.attr.initialKeyguardLayout,
    android.R.attr.configure,
    android.R.attr.previewImage,
    android.R.attr.previewLayout,
    android.R.attr.autoAdvanceViewId,
    android.R.attr.resizeMode,
    android.R.attr.widgetCategory,
    android.R.attr.widgetFeatures,
    android.R.attr.description,
  };

  private AppWidgetProviderInfo parse(ComponentName component, ActivityInfo receiver, int xml)
      throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
    Resources res = app.getResources();
    AppWidgetProviderInfo info = new AppWidgetProviderInfo();
    info.provider = component;
    ReflectionHelpers.setField(info, "providerInfo", receiver);
    try (XmlResourceParser parser = res.getXml(xml)) {
      int type;
      while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
          && type != XmlPullParser.START_TAG) {
        // Skip to the root element.
      }
      if (!"appwidget-provider".equals(parser.getName())) {
        throw new IllegalArgumentException(
            "android.appwidget.provider metadata is <"
                + parser.getName()
                + ">, not"
                + " <appwidget-provider>");
      }
      Attrs a = Attrs.read(res, Xml.asAttributeSet(parser), PROVIDER_ATTRS);
      try {
        info.minWidth = a.dimension(android.R.attr.minWidth);
        info.minHeight = a.dimension(android.R.attr.minHeight);
        info.minResizeWidth = a.dimension(android.R.attr.minResizeWidth, info.minWidth);
        info.minResizeHeight = a.dimension(android.R.attr.minResizeHeight, info.minHeight);
        info.maxResizeWidth = a.dimension(android.R.attr.maxResizeWidth);
        info.maxResizeHeight = a.dimension(android.R.attr.maxResizeHeight);
        info.targetCellWidth = a.integer(android.R.attr.targetCellWidth, 0);
        info.targetCellHeight = a.integer(android.R.attr.targetCellHeight, 0);
        info.updatePeriodMillis = a.integer(android.R.attr.updatePeriodMillis, 0);
        info.initialLayout = a.resource(android.R.attr.initialLayout);
        info.initialKeyguardLayout = a.resource(android.R.attr.initialKeyguardLayout);
        String configure = a.string(android.R.attr.configure);
        if (configure != null && !configure.isEmpty()) {
          info.configure = new ComponentName(component.getPackageName(), qualify(configure));
        }
        info.previewImage = a.resource(android.R.attr.previewImage);
        info.previewLayout = a.resource(android.R.attr.previewLayout);
        info.autoAdvanceViewId = a.resource(android.R.attr.autoAdvanceViewId);
        info.resizeMode = a.integer(android.R.attr.resizeMode, AppWidgetProviderInfo.RESIZE_NONE);
        info.widgetCategory =
            a.integer(
                android.R.attr.widgetCategory, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        info.widgetFeatures = a.integer(android.R.attr.widgetFeatures, 0);
        info.descriptionRes = a.resource(android.R.attr.description);
      } finally {
        a.recycle();
      }
    }
    PackageManager pm = app.getPackageManager();
    CharSequence label = receiver.loadLabel(pm);
    info.label = label == null ? receiver.name : label.toString();
    info.icon = receiver.getIconResource();
    return info;
  }

  private String qualify(String className) {
    return className.startsWith(".") ? app.getPackageName() + className : className;
  }

  /** A {@code TypedArray} over a sorted copy of the attribute ids, read by id. */
  static final class Attrs {
    private final TypedArray array;
    private final int[] sorted;

    private Attrs(TypedArray array, int[] sorted) {
      this.array = array;
      this.sorted = sorted;
    }

    static Attrs read(Resources res, AttributeSet set, int[] ids) {
      int[] sorted = ids.clone();
      Arrays.sort(sorted);
      return new Attrs(res.obtainAttributes(set, sorted), sorted);
    }

    private int index(int attr) {
      return Arrays.binarySearch(sorted, attr);
    }

    int dimension(int attr) {
      return dimension(attr, 0);
    }

    int dimension(int attr, int fallback) {
      int i = index(attr);
      return array.hasValue(i) ? array.getDimensionPixelSize(i, fallback) : fallback;
    }

    int integer(int attr, int fallback) {
      return array.getInt(index(attr), fallback);
    }

    int resource(int attr) {
      return array.getResourceId(index(attr), 0);
    }

    boolean bool(int attr, boolean fallback) {
      return array.getBoolean(index(attr), fallback);
    }

    String string(int attr) {
      return array.getString(index(attr));
    }

    CharSequence text(int attr) {
      return array.getText(index(attr));
    }

    void recycle() {
      array.recycle();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Cells and sizes

  private float density() {
    return app.getResources().getDisplayMetrics().density;
  }

  /** The span a launcher gives a new widget: {@code targetCell*} when set, else its min size. */
  int[] defaultSpan(AppWidgetProviderInfo info) {
    float d = density();
    int x =
        info.targetCellWidth > 0
            ? info.targetCellWidth
            : LauncherGrid.cellsAtLeast(info.minWidth, d);
    int y =
        info.targetCellHeight > 0
            ? info.targetCellHeight
            : LauncherGrid.cellsAtLeast(info.minHeight, d);
    return new int[] {x, y};
  }

  /** The smallest span it resizes to along each axis it resizes on. */
  int[] minSpan(AppWidgetProviderInfo info) {
    int[] span = defaultSpan(info);
    float d = density();
    int x =
        resizes(info, AppWidgetProviderInfo.RESIZE_HORIZONTAL)
            ? Math.min(span[0], LauncherGrid.cellsAtLeast(info.minResizeWidth, d))
            : span[0];
    int y =
        resizes(info, AppWidgetProviderInfo.RESIZE_VERTICAL)
            ? Math.min(span[1], LauncherGrid.cellsAtLeast(info.minResizeHeight, d))
            : span[1];
    return new int[] {x, y};
  }

  /** The largest span: {@code maxResize*} when set, else unbounded ({@link Integer#MAX_VALUE}). */
  int[] maxSpan(AppWidgetProviderInfo info) {
    int[] span = defaultSpan(info);
    float d = density();
    int x =
        !resizes(info, AppWidgetProviderInfo.RESIZE_HORIZONTAL)
            ? span[0]
            : info.maxResizeWidth > 0
                ? Math.max(span[0], LauncherGrid.cellsAtMost(info.maxResizeWidth, d))
                : Integer.MAX_VALUE;
    int y =
        !resizes(info, AppWidgetProviderInfo.RESIZE_VERTICAL)
            ? span[1]
            : info.maxResizeHeight > 0
                ? Math.max(span[1], LauncherGrid.cellsAtMost(info.maxResizeHeight, d))
                : Integer.MAX_VALUE;
    return new int[] {x, y};
  }

  private static boolean resizes(AppWidgetProviderInfo info, int axis) {
    return (info.resizeMode & axis) != 0;
  }

  /**
   * The options a launcher gives a widget it shows at [spanX]×[spanY]: min and max width and height
   * in dp (equal: the cells do not change with orientation here) and, from API 31, the one size it
   * is drawn at, which a responsive {@code RemoteViews(Map<SizeF, RemoteViews>)} picks its layout
   * by.
   */
  static Bundle sizeOptions(int spanX, int spanY) {
    int w = LauncherGrid.sizeDp(spanX);
    int h = LauncherGrid.sizeDp(spanY);
    Bundle b = new Bundle();
    b.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w);
    b.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w);
    b.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h);
    b.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h);
    b.putInt(
        AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
    ArrayList<SizeF> sizes = new ArrayList<>();
    sizes.add(new SizeF(w, h));
    b.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
    return b;
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle

  /**
   * Adds a widget of [info] as a launcher does: allocates and binds an id (the system sends {@code
   * APPWIDGET_ENABLED} for a provider's first widget, then {@code APPWIDGET_UPDATE}), then runs the
   * provider's configure activity if it has one, which places the widget when it returns {@code
   * RESULT_OK} and removes it when it does not. Without one it is placed now.
   */
  JSONObject add(
      AppWidgetProviderInfo info,
      Integer cellX,
      Integer cellY,
      Integer spanX,
      Integer spanY,
      boolean forceConfigure) {
    requireHome();
    // Dropping a widget closes the picker (or whatever is open) over the grid.
    home.closeOverlay();
    int[] span = defaultSpan(info);
    int[] min = minSpan(info);
    int[] max = maxSpan(info);
    int sx = spanX != null ? spanX : span[0];
    int sy = spanY != null ? spanY : span[1];
    checkSpan(info, sx, sy, min, max);
    int[] cell = home.findCell(sx, sy, cellX, cellY, -1);
    if (cell == null) {
      throw new IllegalStateException(
          "no room for a "
              + sx
              + "×"
              + sy
              + " widget on the home screen ("
              + home.describeGrid()
              + ")");
    }
    Hosted hosted = bind(info, sx, sy);
    hosted.cellX = cell[0];
    hosted.cellY = cell[1];
    boolean configure =
        info.configure != null
            && (forceConfigure
                || (info.widgetFeatures
                        & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL)
                    == 0);
    if (configure) {
      home.configure(hosted, info.configure);
      return put(describe(hosted), "configuring", info.configure.getClassName());
    }
    place(hosted, "placed");
    JSONObject result = describe(hosted);
    if (info.configure != null)
      put(result, "configureSkipped", "WIDGET_FEATURE_CONFIGURATION_OPTIONAL");
    return result;
  }

  private void checkSpan(AppWidgetProviderInfo info, int sx, int sy, int[] min, int[] max) {
    if (sx < min[0] || sy < min[1] || sx > max[0] || sy > max[1]) {
      throw new IllegalArgumentException(
          label(info)
              + " spans "
              + min[0]
              + "×"
              + min[1]
              + " to "
              + (max[0] == Integer.MAX_VALUE ? "∞" : String.valueOf(max[0]))
              + "×"
              + (max[1] == Integer.MAX_VALUE ? "∞" : String.valueOf(max[1]))
              + " (resizeMode "
              + resizeMode(info.resizeMode)
              + "), not "
              + sx
              + "×"
              + sy);
    }
  }

  /** Allocates an id and binds it to [info], with the options for [spanX]×[spanY]. */
  private Hosted bind(AppWidgetProviderInfo info, int spanX, int spanY) {
    Hosted hosted;
    boolean first;
    synchronized (this) {
      hosted = new Hosted(nextId++, info);
      hosted.spanX = spanX;
      hosted.spanY = spanY;
      hosted.options = sizeOptions(spanX, spanY);
      first = idsOf(info.provider).length == 0;
      widgets.put(hosted.id, hosted);
    }
    protocol.event("widget", event(hosted, "bound"));
    if (first) {
      broadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_ENABLED), info);
    }
    reason(hosted, "bind");
    broadcast(
        new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {hosted.id}),
        info);
    return hosted;
  }

  /** Puts [hosted] on the grid and tells the provider the size it got. */
  private void place(Hosted hosted, String state) {
    synchronized (this) {
      hosted.placed = true;
    }
    home.widgetsChanged();
    protocol.event("widget", event(hosted, state));
    sendOptions(hosted);
  }

  /** The configure activity returned [resultCode] for [id]. */
  void configured(int id, int resultCode) {
    Hosted hosted;
    synchronized (this) {
      hosted = widgets.get(id);
    }
    if (hosted == null) return;
    if (hosted.placed) {
      protocol.event("widget", put(event(hosted, "reconfigured"), "resultCode", resultCode));
      return;
    }
    if (resultCode == android.app.Activity.RESULT_OK) {
      place(hosted, "configured");
    } else {
      protocol.event("widget", put(event(hosted, "configureCancelled"), "resultCode", resultCode));
      delete(hosted);
    }
  }

  private JSONObject reconfigure(Hosted hosted) {
    requireHome();
    if (hosted.info.configure == null) {
      throw new IllegalArgumentException(label(hosted.info) + " has no configure activity");
    }
    if ((hosted.info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) == 0) {
      throw new IllegalArgumentException(
          label(hosted.info) + " is not WIDGET_FEATURE_RECONFIGURABLE");
    }
    home.configure(hosted, hosted.info.configure);
    return put(describe(hosted), "configuring", hosted.info.configure.getClassName());
  }

  JSONObject remove(Hosted hosted) {
    JSONObject was = describe(hosted);
    delete(hosted);
    return was;
  }

  /**
   * Deletes [hosted]'s id: {@code APPWIDGET_DELETED} for it, then {@code APPWIDGET_DISABLED} when
   * it was its provider's last.
   */
  private void delete(Hosted hosted) {
    boolean last;
    synchronized (this) {
      widgets.remove(hosted.id);
      last = idsOf(hosted.info.provider).length == 0;
    }
    if (home != null) home.widgetsChanged();
    broadcast(
        new Intent(AppWidgetManager.ACTION_APPWIDGET_DELETED)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, hosted.id),
        hosted.info);
    if (last) broadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_DISABLED), hosted.info);
    protocol.event("widget", event(hosted, "removed"));
  }

  JSONObject resize(Hosted hosted, int spanX, int spanY) {
    requireHome();
    checkSpan(hosted.info, spanX, spanY, minSpan(hosted.info), maxSpan(hosted.info));
    if (home.findCell(spanX, spanY, hosted.cellX, hosted.cellY, hosted.id) == null
        || !home.fits(hosted.cellX, hosted.cellY, spanX, spanY, hosted.id)) {
      throw new IllegalStateException(
          "no room to grow widget "
              + hosted.id
              + " to "
              + spanX
              + "×"
              + spanY
              + " at "
              + hosted.cellX
              + ","
              + hosted.cellY);
    }
    synchronized (this) {
      hosted.spanX = spanX;
      hosted.spanY = spanY;
    }
    home.widgetsChanged();
    protocol.event("widget", event(hosted, "resized"));
    sendOptions(hosted);
    return describe(hosted);
  }

  /** Moves or reshapes [hosted] because the grid changed under it; options follow a new size. */
  void reflowed(Hosted hosted, int cellX, int cellY, int spanX, int spanY) {
    boolean resized;
    synchronized (this) {
      resized = hosted.spanX != spanX || hosted.spanY != spanY;
      hosted.cellX = cellX;
      hosted.cellY = cellY;
      hosted.spanX = spanX;
      hosted.spanY = spanY;
    }
    if (resized) {
      protocol.event("widget", event(hosted, "resized"));
      sendOptions(hosted);
    }
  }

  /** {@code APPWIDGET_OPTIONS_CHANGED} with [hosted]'s size, as the launcher lays it out. */
  private void sendOptions(Hosted hosted) {
    Bundle options;
    synchronized (this) {
      hosted.options.putAll(sizeOptions(hosted.spanX, hosted.spanY));
      options = new Bundle(hosted.options);
    }
    reason(hosted, "options");
    broadcast(
        new Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, hosted.id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options),
        hosted.info);
  }

  /**
   * {@code APPWIDGET_UPDATE} for [hosted], or for every placed widget grouped by provider, as an
   * {@code updatePeriodMillis} tick or a launcher refresh sends it.
   */
  JSONObject update(Hosted hosted) {
    Map<ComponentName, List<Hosted>> byProvider = new LinkedHashMap<>();
    synchronized (this) {
      for (Hosted h : widgets.values()) {
        if (hosted != null && h != hosted) continue;
        byProvider.computeIfAbsent(h.info.provider, k -> new ArrayList<>()).add(h);
      }
    }
    JSONArray sent = new JSONArray();
    for (Map.Entry<ComponentName, List<Hosted>> e : byProvider.entrySet()) {
      int[] ids = new int[e.getValue().size()];
      for (int i = 0; i < ids.length; i++) {
        ids[i] = e.getValue().get(i).id;
        reason(e.getValue().get(i), "update");
      }
      JSONObject delivered =
          broadcast(
              new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                  .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
              e.getValue().get(0).info);
      JSONArray idArray = new JSONArray();
      for (int id : ids) idArray.put(id);
      sent.put(
          new Protocol.Json()
              .put("provider", e.getKey().getClassName())
              .put("ids", idArray)
              .put("delivered", delivered.opt("delivered"))
              .object());
    }
    return new Protocol.Json().put("updates", sent).object();
  }

  /** Sends [intent] to [info]'s receiver, as the system addresses each widget broadcast. */
  private JSONObject broadcast(Intent intent, AppWidgetProviderInfo info) {
    intent.setComponent(info.provider);
    try {
      return navigator.broadcast(intent);
    } catch (RuntimeException e) {
      protocol.event(
          "error",
          new Protocol.Json()
              .put("kind", "widgetBroadcast")
              .put("action", intent.getAction())
              .put("message", String.valueOf(e))
              .object());
      return new Protocol.Json().put("delivered", new JSONArray()).object();
    }
  }

  /** One simulator cycle: {@code updatePeriodMillis} ticks, by the simulated clock. */
  void cycle() {
    List<Hosted> due = new ArrayList<>();
    long now = SystemClock.uptimeMillis();
    synchronized (this) {
      for (Hosted h : widgets.values()) {
        if (!h.placed || h.info.updatePeriodMillis <= 0) continue;
        // The system clamps the period to at least 30 minutes.
        long period = Math.max(h.info.updatePeriodMillis, 30 * 60 * 1000L);
        Long last = lastPeriodicUpdate.get(h.info.provider);
        if (last == null) {
          lastPeriodicUpdate.put(h.info.provider, now);
        } else if (now - last >= period) {
          lastPeriodicUpdate.put(h.info.provider, now);
          due.add(h);
        }
      }
    }
    for (Hosted h : due) update(h);
  }

  private void requireHome() {
    if (home == null) {
      throw new IllegalStateException(
          "widgets need a home screen to live on: launch with --launcher");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The AppWidgetManager side, called by SimulatorShadowAppWidgetManager on any thread

  synchronized int[] idsOf(ComponentName provider) {
    List<Integer> ids = new ArrayList<>();
    for (Hosted h : widgets.values()) if (h.info.provider.equals(provider)) ids.add(h.id);
    int[] array = new int[ids.size()];
    for (int i = 0; i < array.length; i++) array[i] = ids.get(i);
    return array;
  }

  synchronized AppWidgetProviderInfo infoOf(int id) {
    Hosted h = widgets.get(id);
    return h == null ? null : h.info;
  }

  synchronized Bundle optionsOf(int id) {
    Hosted h = widgets.get(id);
    return h == null ? Bundle.EMPTY : new Bundle(h.options);
  }

  /** The app changed a widget's options itself: stored, and the provider told, as on a device. */
  void appUpdatedOptions(int id, Bundle options) {
    Hosted hosted;
    synchronized (this) {
      hosted = widgets.get(id);
      if (hosted == null || options == null) return;
      hosted.options.putAll(options);
    }
    onMain(
        () ->
            broadcast(
                new Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, new Bundle(options)),
                hosted.info));
  }

  /** {@code updateAppWidget}: [views] replace what widget [id] shows. */
  void appUpdated(int id, RemoteViews views, boolean partial) {
    Hosted hosted;
    int count;
    synchronized (this) {
      hosted = widgets.get(id);
      if (hosted == null || views == null) return;
      if (partial) {
        // As the service does: merged into the views it holds, and dropped without any.
        if (hosted.views == null) return;
        ReflectionHelpers.callInstanceMethod(
            hosted.views, "mergeRemoteViews", ClassParameter.from(RemoteViews.class, views));
      } else {
        hosted.views = views;
      }
      hosted.updates++;
      hosted.lastUpdateUptime = SystemClock.uptimeMillis();
      count = hosted.updates;
    }
    onMain(
        () -> {
          String layout = draw(hosted);
          // The count this update made, which a later update may already have moved on.
          JSONObject event = put(event(hosted, "updated"), "updates", count);
          put(event, "layout", layout);
          put(event, "partial", partial);
          put(event, "reason", takeReason(hosted));
          protocol.event("widget", event);
        });
  }

  /** {@code updateAppWidget(ComponentName, …)}: every widget of the provider. */
  void appUpdated(ComponentName provider, RemoteViews views) {
    for (int id : idsOf(provider)) appUpdated(id, views, false);
  }

  /** {@code notifyAppWidgetViewDataChanged}: the collection [viewId] in widget [id] reloads. */
  void appDataChanged(int id, int viewId) {
    Hosted hosted;
    synchronized (this) {
      hosted = widgets.get(id);
    }
    if (hosted == null) return;
    onMain(
        () -> {
          if (hosted.view != null) {
            // Hidden: the host view finds the collection and has its adapter reload.
            ReflectionHelpers.callInstanceMethod(
                hosted.view, "onViewDataChanged", ClassParameter.from(int.class, viewId));
          }
          protocol.event(
              "widget", put(event(hosted, "dataChanged"), "viewId", resourceName(viewId)));
        });
  }

  /**
   * {@code requestPinAppWidget}: the home screen comes forward with a confirm sheet, which the
   * agent answers with {@code widget pin} (or by tapping it). Returns whether the request was
   * taken, false when one is already waiting.
   */
  boolean appRequestedPin(ComponentName provider, Bundle extras, PendingIntent callback) {
    if (home == null) return false;
    AppWidgetProviderInfo info;
    try {
      info = provider(provider.getClassName());
    } catch (IllegalArgumentException e) {
      return false;
    }
    synchronized (this) {
      if (pin != null) return false;
      pin = new PinRequest(info, extras, callback);
    }
    onMain(
        () -> {
          protocol.event(
              "widget",
              new Protocol.Json()
                  .put("state", "pinRequested")
                  .put("provider", provider.getClassName())
                  .put("label", label(info))
                  .object());
          home.showPinRequest(info);
        });
    return true;
  }

  synchronized PinRequest pendingPin() {
    return pin;
  }

  /** The person (or {@code widget pin}) answered the pin request. */
  JSONObject answerPin(boolean accept) {
    PinRequest request;
    synchronized (this) {
      request = pin;
      pin = null;
    }
    if (request == null) throw new IllegalStateException("no pin request is waiting");
    home.closeOverlay();
    if (!accept) {
      protocol.event(
          "widget",
          new Protocol.Json()
              .put("state", "pinRejected")
              .put("provider", request.info.provider.getClassName())
              .object());
      return new Protocol.Json().put("accepted", false).object();
    }
    JSONObject added = add(request.info, null, null, null, null, false);
    int id = added.optInt("widgetId");
    JSONObject callback = null;
    if (request.callback != null) {
      Intent fill = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
      if (request.extras != null) fill.putExtras(request.extras);
      callback = PendingIntents.send(request.callback, fill, navigator);
    }
    return new Protocol.Json()
        .put("accepted", true)
        .put("widget", added)
        .put("callback", callback)
        .object();
  }

  synchronized void setGeneratedPreview(ComponentName provider, RemoteViews preview) {
    if (preview == null) generatedPreviews.remove(provider);
    else generatedPreviews.put(provider, preview);
  }

  synchronized RemoteViews generatedPreview(ComponentName provider) {
    return generatedPreviews.get(provider);
  }

  // ---------------------------------------------------------------------------------------------
  // Drawing and input

  /**
   * Draws [hosted]'s views in its host view, if it has one. After a partial update these are the
   * merged views, as the service hands the host; the host view reapplies them over the views it has
   * when the layout is the same. Returns the layout now shown, by resource name.
   */
  String draw(Hosted hosted) {
    RemoteViews views;
    synchronized (this) {
      views = hosted.views;
    }
    if (views == null) return null;
    if (hosted.view != null) hosted.view.updateAppWidget(views);
    String layout = resourceName(layoutShown(hosted, views));
    hosted.lastLayout = layout;
    return layout;
  }

  /** The layout the host applies for [views] at [hosted]'s size (responsive and land/port). */
  private int layoutShown(Hosted hosted, RemoteViews views) {
    try {
      SizeF size = new SizeF(LauncherGrid.sizeDp(hosted.spanX), LauncherGrid.sizeDp(hosted.spanY));
      RemoteViews applied =
          ReflectionHelpers.callInstanceMethod(
              views,
              "getRemoteViewsToApply",
              ClassParameter.from(Context.class, app),
              ClassParameter.from(SizeF.class, size));
      return applied.getLayoutId();
    } catch (RuntimeException e) {
      return views.getLayoutId();
    }
  }

  /**
   * A host view for [hosted], as a launcher creates one: no default padding (the cell is the
   * widget), taps on the app's views routed through {@link #interaction}, and the views the app
   * last sent, or its {@code initialLayout} until it sends any.
   */
  AppWidgetHostView createView(Context context, Hosted hosted) {
    AppWidgetHostView view = new AppWidgetHostView(context);
    view.setAppWidget(hosted.id, hosted.info);
    view.setPadding(0, 0, 0, 0);
    ReflectionHelpers.callInstanceMethod(
        view,
        "setInteractionHandler",
        ClassParameter.from(interactionHandlerClass(), interaction(hosted)));
    hosted.view = view;
    RemoteViews views;
    synchronized (this) {
      views = hosted.views;
    }
    if (views != null) {
      draw(hosted);
    } else if (hosted.info.initialLayout != 0) {
      view.updateAppWidget(new RemoteViews(app.getPackageName(), hosted.info.initialLayout));
    }
    return view;
  }

  private static Class<?> interactionHandlerClass() {
    try {
      return Class.forName(
          "android.widget.RemoteViews$InteractionHandler",
          false,
          WidgetHost.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The {@code RemoteViews.InteractionHandler} (hidden) a launcher installs: a tap on a view with a
   * click {@code PendingIntent}, a checked change, or a collection item's fill-in intent comes here
   * with the response, whose launch options carry the fill-in intent (and {@code EXTRA_CHECKED} for
   * a compound button). The intent is sent through {@link PendingIntents}.
   */
  private Object interaction(Hosted hosted) {
    Class<?> type = interactionHandlerClass();
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
          View view = (View) args[0];
          PendingIntent pending = (PendingIntent) args[1];
          Object response = args[2];
          Intent fillIn = null;
          try {
            android.util.Pair<?, ?> options =
                ReflectionHelpers.callInstanceMethod(
                    response, "getLaunchOptions", ClassParameter.from(View.class, view));
            fillIn = (Intent) options.first;
          } catch (RuntimeException e) {
            // No launch options: send the intent as it is.
          }
          reason(hosted, "click");
          JSONObject sent;
          try {
            sent = PendingIntents.send(pending, fillIn, navigator);
          } catch (RuntimeException e) {
            protocol.event(
                "error",
                new Protocol.Json()
                    .put("kind", "widgetClick")
                    .put("widgetId", hosted.id)
                    .put("message", String.valueOf(e))
                    .object());
            return true;
          }
          JSONObject event = event(hosted, "clicked");
          put(event, "view", resourceName(view.getId()));
          put(event, "sent", sent);
          protocol.event("widget", event);
          return true;
        };
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object objectMethod(Object proxy, Method method, Object[] args) {
    switch (method.getName()) {
      case "equals":
        return proxy == args[0];
      case "hashCode":
        return System.identityHashCode(proxy);
      default:
        return "WidgetHost.InteractionHandler";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Update reasons

  /** The host is about to make [hosted]'s provider update for [why]. */
  private void reason(Hosted hosted, String why) {
    synchronized (this) {
      hosted.pendingReason = why;
      hosted.pendingReasonAt = SystemClock.uptimeMillis();
    }
  }

  /**
   * Why an update came: the host's last prompt to the widget (bind, update, options, click,
   * configure) within ten simulated seconds, else {@code "app"} — the app updating on its own (a
   * worker, a broadcast, its own UI). A best guess: the system does not say.
   */
  private synchronized String takeReason(Hosted hosted) {
    String why = hosted.pendingReason;
    boolean recent = SystemClock.uptimeMillis() - hosted.pendingReasonAt <= 10_000;
    hosted.pendingReason = null;
    return why != null && recent ? why : "app";
  }

  void configuring(Hosted hosted) {
    reason(hosted, "configure");
  }

  // ---------------------------------------------------------------------------------------------
  // Describing

  JSONObject describe() {
    JSONArray available = new JSONArray();
    for (AppWidgetProviderInfo info : providers()) available.put(describe(info));
    JSONArray placed = new JSONArray();
    List<Hosted> all;
    synchronized (this) {
      all = new ArrayList<>(widgets.values());
    }
    for (Hosted h : all) placed.put(describe(h));
    Protocol.Json json = new Protocol.Json().put("providers", available).put("widgets", placed);
    PinRequest request = pendingPin();
    if (request != null) json.put("pinRequest", request.info.provider.getClassName());
    if (home == null)
      json.put("note", "no home screen to place widgets on: launch with --launcher");
    return json.object();
  }

  JSONObject describe(AppWidgetProviderInfo info) {
    float d = density();
    int[] span = defaultSpan(info);
    int[] min = minSpan(info);
    int[] max = maxSpan(info);
    Protocol.Json json =
        new Protocol.Json()
            .put("provider", info.provider.getClassName())
            .put("label", label(info))
            .put("description", description(info))
            .put("minWidthDp", Math.round(info.minWidth / d))
            .put("minHeightDp", Math.round(info.minHeight / d))
            .put("span", spanJson(span[0], span[1]))
            .put("minSpan", spanJson(min[0], min[1]))
            .put("maxSpan", spanJson(max[0], max[1]))
            .put("resizeMode", resizeMode(info.resizeMode))
            .put("updatePeriodMillis", info.updatePeriodMillis)
            .put("initialLayout", resourceName(info.initialLayout))
            .put("previewLayout", resourceName(info.previewLayout))
            .put("previewImage", resourceName(info.previewImage))
            .put("configure", info.configure == null ? null : info.configure.getClassName())
            .put("features", features(info.widgetFeatures))
            .put("categories", categories(info.widgetCategory))
            .put("placed", idsOf(info.provider).length);
    if (generatedPreview(info.provider) != null) json.put("generatedPreview", true);
    return json.object();
  }

  private static JSONArray spanJson(int x, int y) {
    return new JSONArray()
        .put(x == Integer.MAX_VALUE ? -1 : x)
        .put(y == Integer.MAX_VALUE ? -1 : y);
  }

  JSONObject describe(Hosted h) {
    Protocol.Json json;
    synchronized (this) {
      json =
          new Protocol.Json()
              .put("widgetId", h.id)
              .put("provider", h.info.provider.getClassName())
              .put("label", label(h.info))
              .put("state", h.placed ? "placed" : "configuring")
              .put("cellX", h.cellX)
              .put("cellY", h.cellY)
              .put("spanX", h.spanX)
              .put("spanY", h.spanY)
              .put("widthDp", LauncherGrid.sizeDp(h.spanX))
              .put("heightDp", LauncherGrid.sizeDp(h.spanY))
              .put("updates", h.updates)
              .put("lastUpdateUptimeMs", h.lastUpdateUptime < 0 ? null : h.lastUpdateUptime)
              .put("layout", h.lastLayout);
    }
    return json.object();
  }

  private JSONObject event(Hosted h, String state) {
    return new Protocol.Json()
        .put("state", state)
        .put("widgetId", h.id)
        .put("provider", h.info.provider.getClassName())
        .put("spanX", h.spanX)
        .put("spanY", h.spanY)
        .put("updates", h.updates)
        .object();
  }

  private static JSONObject put(JSONObject json, String key, Object value) {
    try {
      json.put(key, value == null ? JSONObject.NULL : value);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return json;
  }

  String label(AppWidgetProviderInfo info) {
    return info.label != null ? info.label : info.provider.getShortClassName();
  }

  String description(AppWidgetProviderInfo info) {
    if (info.descriptionRes == 0) return null;
    try {
      return app.getResources().getText(info.descriptionRes).toString();
    } catch (Resources.NotFoundException e) {
      return null;
    }
  }

  String resourceName(int id) {
    if (id == 0 || id == View.NO_ID) return null;
    try {
      return app.getResources().getResourceEntryName(id);
    } catch (Resources.NotFoundException e) {
      return "0x" + Integer.toHexString(id);
    }
  }

  static String resizeMode(int mode) {
    switch (mode
        & (AppWidgetProviderInfo.RESIZE_HORIZONTAL | AppWidgetProviderInfo.RESIZE_VERTICAL)) {
      case AppWidgetProviderInfo.RESIZE_HORIZONTAL:
        return "horizontal";
      case AppWidgetProviderInfo.RESIZE_VERTICAL:
        return "vertical";
      case AppWidgetProviderInfo.RESIZE_BOTH:
        return "horizontal|vertical";
      default:
        return "none";
    }
  }

  private static JSONArray features(int features) {
    JSONArray array = new JSONArray();
    if ((features & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0) {
      array.put("reconfigurable");
    }
    if ((features & AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER) != 0) {
      array.put("hideFromPicker");
    }
    if ((features & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0) {
      array.put("configurationOptional");
    }
    return array;
  }

  private static JSONArray categories(int category) {
    JSONArray array = new JSONArray();
    if ((category & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0)
      array.put("home_screen");
    if ((category & AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) != 0) array.put("keyguard");
    if ((category & AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX) != 0) array.put("searchbox");
    return array;
  }

  synchronized List<Hosted> all() {
    return new ArrayList<>(widgets.values());
  }

  synchronized Hosted get(int id) {
    return widgets.get(id);
  }

  private void onMain(Runnable action) {
    if (Looper.myLooper() == Looper.getMainLooper()) action.run();
    else main.post(action);
  }
}
