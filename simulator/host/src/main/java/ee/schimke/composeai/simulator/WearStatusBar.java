package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import com.google.wear.Sdk;
import com.google.wear.settings.WearSettings;
import java.util.Calendar;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Wear OS global system status bar (Wear OS 7.1 Canary, API 37.2 / Wear OS 8): renders the 12
 * o'clock time and single priority status indicator over opted-in Wear apps and supplies top {@code
 * WindowInsets.Type.statusBars()} insets coordinated with {@code WindowInsetsController.hide/show}.
 *
 * <p>Matches AndroidX Wear Compose 1.8 ({@code androidx.wear.compose.material3.StatusBarKt}) and
 * the Wear OS platform enablement requirements:
 *
 * <ul>
 *   <li>Wear form factor with global status bar enabled ({@code --global-status-bar} / {@code
 *       composeai.simulator.globalStatusBar=true}) on Wear API {@code WEAR_CINNAMON_BUN_2} (37.2);
 *   <li>app {@code targetSdkVersion >= 35};
 *   <li>application manifest {@code <meta-data
 *       android:name="com.google.wear.ENABLE_GLOBAL_STATUS_BAR" android:value="true" />}.
 * </ul>
 *
 * <p>When any requirement is not met, the status bar stays inactive: no top status bar inset is
 * injected and no overlay window is added, so legacy and non-opted-in apps keep their in-app {@code
 * TimeText}.
 */
final class WearStatusBar {

  static final String PROPERTY = Sdk.GLOBAL_STATUS_BAR_PROPERTY;
  static final String METADATA_KEY = WearSettings.METADATA_ENABLE_GLOBAL_STATUS_BAR;
  static final int MIN_TARGET_SDK = 35;

  /** Fallback status bar height in dp when Robolectric's {@code SystemUi.StatusBar} has none. */
  private static final int STATUS_BAR_DP = 24;

  /** System property checked by Wear Compose's {@code GlobalStatusBar.kt}. */
  private static final String GLOBAL_STATUS_BAR_SYSPROP = "persist.global_status_bar_opt_in";

  /** Settings.Global keys for Wear OS watch modes. */
  static final String SETTING_ZEN_MODE = "zen_mode";
  static final String SETTING_BEDTIME_MODE = "bedtime_mode";
  static final String SETTING_THEATER_MODE = "theater_mode_on";

  /**
   * Single priority status indicator shown beside the clock (at most one at a time), matching Wear
   * OS 8's documented privacy indicators, watch modes, battery, and connectivity icons.
   */
  enum Indicator {
    MICROPHONE("microphone", "microphone active"),
    LOCATION("location", "location active"),
    AIRPLANE("airplane", "airplane mode"),
    DND("dnd", "do not disturb"),
    BEDTIME("bedtime", "bedtime mode"),
    THEATER("theater", "theater mode"),
    BATTERY_SAVER("batterySaver", "battery saver"),
    CHARGING("charging", "charging"),
    LOW_BATTERY("lowBattery", "low battery"),
    OFFLINE("offline", "offline");

    final String id;
    final String description;

    Indicator(String id, String description) {
      this.id = id;
      this.description = description;
    }
  }

  /** Snapshot of system/mode flags evaluated by {@link #pickIndicator(Signals)}. */
  static final class Signals {
    boolean microphone;
    boolean location;
    boolean airplane;
    boolean dnd;
    boolean bedtime;
    boolean theater;
    boolean batterySaver;
    boolean charging;
    int battery = 100;
    boolean offline;
  }

  private final Application app;
  private final WearSystemUi wear;
  private final Context context;
  private final WindowManager windows;
  private final boolean platformEnabled;
  private final boolean appOptIn;
  private final int targetSdk;
  private final boolean enabled;

  /** Robolectric's {@code SystemUi.StatusBar} for the default display, or null when inactive. */
  private Object insets;

  private int height;
  private WearStatusBarView bar;
  private WindowManager.LayoutParams barParams;
  private boolean ambient;
  private boolean microphoneActive;
  private boolean locationActive;
  private long lastTick;

  private WearStatusBar(Application app, WearSystemUi wear) {
    this.app = app;
    this.wear = wear;
    this.context = new ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault);
    this.windows = app.getSystemService(WindowManager.class);
    this.platformEnabled = isPlatformEnabled();
    this.targetSdk = readTargetSdk(app);
    this.appOptIn = readManifestOptIn(app);
    this.enabled = platformEnabled && targetSdk >= MIN_TARGET_SDK && appOptIn;
  }

  private static boolean isPlatformEnabled() {
    if (FormFactor.current() != FormFactor.WEAR) return false;
    if (!"true".equalsIgnoreCase(System.getProperty(PROPERTY, "").trim())) return false;
    String explicit = System.getProperty(Sdk.WEAR_API_VERSION_PROPERTY);
    if (explicit != null && !explicit.isBlank()) {
      com.google.wear.WearApiVersion v = Sdk.VERSION_CODES.lookup(explicit.trim());
      return v != null && v.compareTo(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2) >= 0;
    }
    return android.os.Build.VERSION.SDK_INT >= 37;
  }

  /** Creates the Wear status bar controller when running on a Wear form factor, else null. */
  static WearStatusBar create(Application app, WearSystemUi wear) {
    if (FormFactor.current() != FormFactor.WEAR) return null;
    return new WearStatusBar(app, wear);
  }

  boolean isEnabled() {
    return enabled;
  }

  /**
   * Activates Robolectric's status bar insets and adds the 12 o'clock overlay window when all
   * enablement requirements are satisfied. Called before the app's first activity launches.
   */
  void start() {
    if (!enabled) return;
    setSystemProperty(GLOBAL_STATUS_BAR_SYSPROP, "true");
    insets = PhoneSystemUi.robolectricStatusBar();
    height = statusBarHeight();
    bar = new WearStatusBarView(context);
    PhoneSystemUi.simId(bar, "status_bar");
    barParams =
        new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    barParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    barParams.setFitInsetsTypes(0);
    barParams.setTitle("WearStatusBar");
    windows.addView(bar, barParams);
    refresh();
  }

  void ambient(boolean value) {
    ambient = value;
  }

  /** Syncs visibility with {@code WindowInsetsController} and updates the clock / indicator. */
  void cycle() {
    if (!enabled || bar == null) return;
    boolean onWatchFace = wear != null && "watchface".equals(wear.surface());
    boolean shown = barShown() && !onWatchFace && !ambient;
    if (shown != (bar.getVisibility() == View.VISIBLE)) {
      bar.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
    }
    long now = SystemClock.uptimeMillis();
    if (now - lastTick < 1000) return;
    lastTick = now;
    refresh();
  }

  /**
   * Control-channel commands owned by the Wear status bar, or null for anything else.
   *
   * <p>{@code statusBar} accepts optional boolean fields ({@code microphone}, {@code location},
   * {@code dnd}, {@code bedtime}, {@code theater}) to simulate Wear OS privacy indicators and watch
   * modes, then returns the full status bar state.
   */
  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!"statusBar".equals(name)) return null;
    if (c != null) {
      if (c.has("microphone")) microphoneActive = c.getBoolean("microphone");
      if (c.has("location")) locationActive = c.getBoolean("location");
      if (c.has("dnd")) setDnd(c.getBoolean("dnd"));
      if (c.has("bedtime")) {
        Settings.Global.putInt(
            app.getContentResolver(), SETTING_BEDTIME_MODE, c.getBoolean("bedtime") ? 1 : 0);
      }
      if (c.has("theater")) {
        Settings.Global.putInt(
            app.getContentResolver(), SETTING_THEATER_MODE, c.getBoolean("theater") ? 1 : 0);
      }
    }
    refresh();
    return describe();
  }

  private void setDnd(boolean active) {
    Settings.Global.putInt(app.getContentResolver(), SETTING_ZEN_MODE, active ? 1 : 0);
    NotificationManager nm = app.getSystemService(NotificationManager.class);
    if (nm != null) {
      try {
        nm.setInterruptionFilter(
            active
                ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
      } catch (RuntimeException ignored) {
        // Settings.Global zen_mode still reflects DND for the status bar.
      }
    }
  }

  JSONObject describe() {
    Signals signals = readSignals();
    Indicator activeIndicator = pickIndicator(signals);
    boolean visible = enabled && bar != null && bar.getVisibility() == View.VISIBLE;
    Protocol.Json status =
        new Protocol.Json()
            .put("enabled", enabled)
            .put("platformEnabled", platformEnabled)
            .put("appOptIn", appOptIn)
            .put("targetSdk", targetSdk)
            .put("wearApiVersion", Sdk.currentApiVersion().getName())
            .put("visible", visible)
            .put("heightPx", enabled ? height : 0)
            .put("time", bar == null ? null : bar.time)
            .put("indicator", activeIndicator == null ? null : activeIndicator.id)
            .put("microphone", signals.microphone)
            .put("location", signals.location)
            .put("airplane", signals.airplane)
            .put("dnd", signals.dnd)
            .put("bedtime", signals.bedtime)
            .put("theater", signals.theater)
            .put("batterySaver", signals.batterySaver);
    return new Protocol.Json()
        .put("surface", wear != null ? wear.surface() : "app")
        .put("statusBar", status.object())
        .object();
  }

  private void refresh() {
    if (bar == null) return;
    String time = formatClock();
    Indicator indicator = pickIndicator(readSignals());
    boolean changed = !time.equals(bar.time) || indicator != bar.indicator;
    bar.time = time;
    bar.indicator = indicator;
    if (changed) bar.invalidate();
    StringBuilder description = new StringBuilder(time);
    if (indicator != null) {
      description.append(", ").append(indicator.description);
    }
    bar.setContentDescription(description.toString());
  }

  private Signals readSignals() {
    Signals s = new Signals();
    s.microphone = microphoneActive;
    s.location = locationActive;
    JSONObject quick = QuickSettings.state(app);
    if (quick != null) {
      s.airplane = quick.optBoolean("airplane");
      s.charging = quick.optBoolean("charging");
      s.battery = quick.optInt("battery", 100);
      boolean wifi = "wifi".equals(quick.optString("default"));
      boolean cellular = quick.optBoolean("cellular");
      s.offline = !wifi && !cellular && quick.has("default");
    }
    s.dnd = isDndActive();
    s.bedtime =
        Settings.Global.getInt(app.getContentResolver(), SETTING_BEDTIME_MODE, 0) != 0;
    s.theater =
        Settings.Global.getInt(app.getContentResolver(), SETTING_THEATER_MODE, 0) != 0;
    PowerManager pm = app.getSystemService(PowerManager.class);
    s.batterySaver =
        (pm != null && pm.isPowerSaveMode())
            || (WatchState.current() != null && WatchState.current().powerSave());
    return s;
  }

  private boolean isDndActive() {
    if (Settings.Global.getInt(app.getContentResolver(), SETTING_ZEN_MODE, 0) != 0) {
      return true;
    }
    NotificationManager nm = app.getSystemService(NotificationManager.class);
    if (nm == null) return false;
    int filter = nm.getCurrentInterruptionFilter();
    return filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        || filter == NotificationManager.INTERRUPTION_FILTER_NONE
        || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS;
  }

  /**
   * Selects at most one status indicator by priority, matching Wear OS 8's single-icon status bar
   * rule: privacy (microphone > location) > watch modes (airplane > DND > bedtime > theater >
   * battery saver) > battery (charging > low battery) > connectivity (offline).
   */
  static Indicator pickIndicator(Signals s) {
    if (s == null) return null;
    if (s.microphone) return Indicator.MICROPHONE;
    if (s.location) return Indicator.LOCATION;
    if (s.airplane) return Indicator.AIRPLANE;
    if (s.dnd) return Indicator.DND;
    if (s.bedtime) return Indicator.BEDTIME;
    if (s.theater) return Indicator.THEATER;
    if (s.batterySaver) return Indicator.BATTERY_SAVER;
    if (s.charging) return Indicator.CHARGING;
    if (s.battery <= 15) return Indicator.LOW_BATTERY;
    if (s.offline) return Indicator.OFFLINE;
    return null;
  }

  private String formatClock() {
    WatchState ws = WatchState.current();
    Calendar cal = ws != null ? Calendar.getInstance(ws.timeZone()) : Calendar.getInstance();
    cal.setTimeInMillis(WatchState.currentTimeMillis());
    boolean is24 = ws != null ? ws.is24Hour() : DateFormat.is24HourFormat(app);
    int hour = is24 ? cal.get(Calendar.HOUR_OF_DAY) : cal.get(Calendar.HOUR);
    if (!is24 && hour == 0) hour = 12;
    return String.format(Locale.ROOT, is24 ? "%02d:%02d" : "%d:%02d", hour, cal.get(Calendar.MINUTE));
  }

  private int statusBarHeight() {
    if (insets != null) {
      try {
        int size = ReflectionHelpers.callInstanceMethod(insets, "getSize");
        if (size > 0) return size;
      } catch (RuntimeException ignored) {
        // Fall back to standard Wear status bar height.
      }
    }
    return dp(STATUS_BAR_DP);
  }

  private boolean barShown() {
    if (insets == null) return true;
    try {
      return ReflectionHelpers.callInstanceMethod(insets, "isVisible");
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  private int dp(int value) {
    return Math.round(value * app.getResources().getDisplayMetrics().density);
  }

  static int readTargetSdk(Context context) {
    try {
      ApplicationInfo info = context.getApplicationInfo();
      return info == null ? 0 : info.targetSdkVersion;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  static boolean readManifestOptIn(Context context) {
    try {
      ApplicationInfo direct = context.getApplicationInfo();
      if (direct != null && direct.metaData != null) {
        return direct.metaData.getBoolean(METADATA_KEY, false);
      }
      PackageManager pm = context.getPackageManager();
      if (pm != null && context.getPackageName() != null) {
        ApplicationInfo loaded =
            pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        Bundle metaData = loaded == null ? null : loaded.metaData;
        return metaData != null && metaData.getBoolean(METADATA_KEY, false);
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
      // Missing metadata means not opted in.
    }
    return false;
  }

  private static void setSystemProperty(String key, String value) {
    try {
      Class<?> shadowProps =
          Class.forName(
              "org.robolectric.shadows.ShadowSystemProperties",
              true,
              WearStatusBar.class.getClassLoader());
      ReflectionHelpers.callStaticMethod(
          shadowProps,
          "override",
          ClassParameter.from(String.class, key),
          ClassParameter.from(String.class, value));
    } catch (ClassNotFoundException | RuntimeException ignored) {
      // Optional: Wear Compose 1.8.0-alpha04 uses WearSettings.isStatusBarEnabled.
    }
  }

  /** 12 o'clock Wear OS 8 system status bar overlay: translucent pill backplate, time, and icon. */
  private final class WearStatusBarView extends View {
    String time = "";
    Indicator indicator;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Path path = new Path();

    WearStatusBarView(Context context) {
      super(context);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      if (w <= 0 || h <= 0) return;

      float density = getResources().getDisplayMetrics().density;
      paint.setStyle(Paint.Style.FILL);
      paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      paint.setTextSize(
          TypedValue.applyDimension(
              TypedValue.COMPLEX_UNIT_SP, 12.5f, getResources().getDisplayMetrics()));
      float textWidth = paint.measureText(time);
      float iconSize = indicator != null ? 11f * density : 0f;
      float gap = indicator != null ? 4f * density : 0f;
      float padH = 8f * density;
      float padV = 2f * density;
      float contentWidth = iconSize + gap + textWidth;
      float pillWidth = contentWidth + padH * 2f;
      float pillHeight = Math.min(h - padV * 2f, 18f * density);
      float cx = w / 2f;
      float cy = h / 2f;

      pill.set(cx - pillWidth / 2f, cy - pillHeight / 2f, cx + pillWidth / 2f, cy + pillHeight / 2f);
      paint.setColor(0xCC000000);
      canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, paint);

      float x = cx - contentWidth / 2f;
      if (indicator != null) {
        drawIndicator(canvas, indicator, x, cy - iconSize / 2f, iconSize, density);
        x += iconSize + gap;
      }

      paint.setStyle(Paint.Style.FILL);
      paint.setColor(Color.WHITE);
      Paint.FontMetrics fm = paint.getFontMetrics();
      float baseline = cy - (fm.ascent + fm.descent) / 2f;
      canvas.drawText(time, x, baseline, paint);
    }

    private void drawIndicator(
        Canvas canvas, Indicator kind, float x, float y, float size, float density) {
      int color = Color.WHITE;
      if (kind == Indicator.MICROPHONE || kind == Indicator.LOCATION) {
        color = 0xFF4CAF50; // Android privacy indicator green.
      } else if (kind == Indicator.LOW_BATTERY) {
        color = 0xFFFF6B6B;
      } else if (kind == Indicator.BATTERY_SAVER) {
        color = 0xFFFFC107;
      }
      paint.setColor(color);
      float midX = x + size / 2f;
      float midY = y + size / 2f;
      switch (kind) {
        case MICROPHONE:
          paint.setStyle(Paint.Style.FILL);
          canvas.drawRoundRect(
              x + size * 0.34f,
              y + size * 0.12f,
              x + size * 0.66f,
              y + size * 0.62f,
              size * 0.16f,
              size * 0.16f,
              paint);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.2f * density);
          canvas.drawArc(
              x + size * 0.2f, y + size * 0.28f, x + size * 0.8f, y + size * 0.76f, 0f, 180f, false, paint);
          canvas.drawLine(midX, y + size * 0.76f, midX, y + size * 0.92f, paint);
          break;
        case LOCATION:
          paint.setStyle(Paint.Style.FILL);
          canvas.drawCircle(midX, y + size * 0.38f, size * 0.26f, paint);
          path.reset();
          path.moveTo(x + size * 0.28f, y + size * 0.48f);
          path.lineTo(midX, y + size * 0.92f);
          path.lineTo(x + size * 0.72f, y + size * 0.48f);
          path.close();
          canvas.drawPath(path, paint);
          break;
        case AIRPLANE:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.5f * density);
          paint.setStrokeCap(Paint.Cap.ROUND);
          canvas.drawLine(midX, y + density, midX, y + size - density, paint);
          canvas.drawLine(x + density, midY, x + size - density, midY, paint);
          break;
        case DND:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.3f * density);
          canvas.drawCircle(midX, midY, size * 0.38f, paint);
          canvas.drawLine(x + size * 0.28f, midY, x + size * 0.72f, midY, paint);
          break;
        case BEDTIME:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.5f * density);
          canvas.drawArc(
              x + size * 0.18f,
              y + size * 0.14f,
              x + size * 0.86f,
              y + size * 0.86f,
              40f,
              250f,
              false,
              paint);
          break;
        case THEATER:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.2f * density);
          canvas.drawRoundRect(
              x + size * 0.15f,
              y + size * 0.2f,
              x + size * 0.85f,
              y + size * 0.8f,
              size * 0.15f,
              size * 0.15f,
              paint);
          canvas.drawLine(x + size * 0.22f, y + size * 0.22f, x + size * 0.78f, y + size * 0.78f, paint);
          break;
        case BATTERY_SAVER:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.2f * density);
          canvas.drawRect(
              x + size * 0.25f, y + size * 0.15f, x + size * 0.75f, y + size * 0.92f, paint);
          canvas.drawLine(midX, y + size * 0.36f, midX, y + size * 0.72f, paint);
          canvas.drawLine(x + size * 0.36f, y + size * 0.54f, x + size * 0.64f, y + size * 0.54f, paint);
          break;
        case CHARGING:
          paint.setStyle(Paint.Style.FILL);
          path.reset();
          path.moveTo(x + size * 0.58f, y);
          path.lineTo(x + size * 0.18f, y + size * 0.55f);
          path.lineTo(x + size * 0.48f, y + size * 0.55f);
          path.lineTo(x + size * 0.42f, y + size);
          path.lineTo(x + size * 0.82f, y + size * 0.45f);
          path.lineTo(x + size * 0.52f, y + size * 0.45f);
          path.close();
          canvas.drawPath(path, paint);
          break;
        case LOW_BATTERY:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.2f * density);
          canvas.drawRect(
              x + size * 0.25f, y + size * 0.15f, x + size * 0.75f, y + size * 0.92f, paint);
          paint.setStyle(Paint.Style.FILL);
          canvas.drawRect(
              x + size * 0.38f, y + size * 0.05f, x + size * 0.62f, y + size * 0.15f, paint);
          canvas.drawRect(
              x + size * 0.32f, y + size * 0.70f, x + size * 0.68f, y + size * 0.86f, paint);
          break;
        case OFFLINE:
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1.4f * density);
          paint.setStrokeCap(Paint.Cap.ROUND);
          canvas.drawLine(x + density, y + density, x + size - density, y + size - density, paint);
          canvas.drawCircle(midX, midY, size * 0.35f, paint);
          break;
      }
    }
  }
}
