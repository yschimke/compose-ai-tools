package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A Wear OS system UI around the app ({@code --launcher}): the watch face, the app launcher, the
 * notification stream, the tiles and widgets carousel and quick settings, in {@link WearHome} at
 * the bottom of the default display's back stack. Leaving the app for one of them stops it, and
 * coming back restarts it, through {@link Navigator}, as on a watch.
 *
 * <p>Each surface answers both the gestures a person makes and a control-channel command:
 *
 * <ul>
 *   <li>back from the app's root, {@code home}: the watch face;
 *   <li>the side button ({@code key STEM_PRIMARY}), {@code launcher}: the app list;
 *   <li>a swipe up on the face, {@code notifications op:open}: the stream;
 *   <li>a swipe left / right on the face, {@code tiles op:next / prev / show}: the carousel;
 *   <li>a swipe down on the face, {@code quickSettings}: battery, airplane mode, Wi-Fi.
 * </ul>
 *
 * Its views carry {@code sim:id/...} resource ids and the item's identity (a notification's key, a
 * component) as their content description, so {@code find} and {@code tap} reach them like the
 * app's. Plain views only: a launcher in Compose would bring a second Compose onto the app's class
 * path.
 */
final class WearSystemUi {

  static final String PROPERTY = "composeai.simulator.launcher";

  /** How long a heads-up card shows on the watch face. */
  private static final long PEEK_MS = 4000;

  enum Surface {
    WATCHFACE("watchface"),
    LAUNCHER("launcher"),
    NOTIFICATIONS("notifications"),
    TILES("tiles"),
    QUICK_SETTINGS("quickSettings");

    final String id;

    Surface(String id) {
      this.id = id;
    }
  }

  /** The session's system UI, which {@link WearHome} and key interception reach. */
  static volatile WearSystemUi current;

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final NotificationStream stream;
  private final TileHost tiles;
  private final SystemData systemData;
  /** Each tile's page, which keeps its rendered view while other pages show. */
  private final java.util.Map<String, FrameLayout> tilePages = new java.util.HashMap<>();
  /** Each widget's frame on the widgets page, which keeps its player across rebuilds. */
  private final java.util.Map<String, FrameLayout> widgetFrames = new java.util.HashMap<>();
  /** The page whose tile was last told it is on screen. */
  private String enteredTile;
  private Navigator.Entry home;
  private Surface surface = Surface.WATCHFACE;
  private boolean ambient;
  private final WatchFaceHost watchFaces;
  private int lastMinute = -1;

  private Context context;
  private Root root;
  private FrameLayout face;
  private WatchFaceView faceView;
  private TextView ongoingChip;
  private LinearLayout peek;
  private long peekUntil;
  private ScrollView launcher;
  private LinearLayout launcherList;
  private ScrollView notifications;
  private LinearLayout notificationList;
  private FrameLayout carousel;
  /** The carousel page's list (widgets or the add page), which the crown scrolls; else null. */
  private ScrollView carouselScroll;
  private ScrollView quickSettings;
  private LinearLayout quickSettingsList;
  private final Set<String> expanded = new HashSet<>();
  private final Set<String> seen = new HashSet<>();
  private long lastTick;

  private WearSystemUi(
      Application app, Navigator navigator, Protocol protocol, NotificationStream stream) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.stream = stream;
    this.systemData = new SystemData(app);
    this.tiles = new TileHost(app, navigator, systemData, this::tileRendered, this::widgetRendered);
    this.watchFaces =
        new WatchFaceHost(
            app,
            navigator,
            protocol,
            new WatchFaceHost.Display() {
              @Override
              public void frame(android.graphics.Bitmap frame) {
                if (faceView != null) faceView.appFrame(frame);
              }

              @Override
              public void builtInComplication(String text) {
                if (faceView != null) faceView.complication(text);
              }
            });
    stream.onChange(this::streamChanged);
  }

  /** The system UI when {@code --launcher} asked for it on a Wear app, else null. */
  static WearSystemUi create(
      Application app, Navigator navigator, Protocol protocol, NotificationStream stream) {
    if (!"true".equals(System.getProperty(PROPERTY))) return null;
    // Elsewhere --launcher is the phone home screen (HomeScreen).
    if (FormFactor.current() != FormFactor.WEAR) return null;
    WearSystemUi ui = new WearSystemUi(app, navigator, protocol, stream);
    current = ui;
    return ui;
  }

  /**
   * Starts {@link WearHome} under the app: called before the first cycle drains the app's
   * launcher intent, so the app is launched over it.
   */
  void start() {
    Intent intent =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(new ComponentName(app.getPackageName(), WearHome.class.getName()));
    home = navigator.startHome(new WearHome(), intent);
    tiles.addFromLaunch(protocol);
  }

  /** One cycle: the clock, timers in ongoing activities, and the heads-up card's timeout. */
  void cycle() {
    if (root == null) return;
    long now = SystemClock.uptimeMillis();
    if (peek.getVisibility() == View.VISIBLE && now >= peekUntil) peek.setVisibility(View.GONE);
    if (now - lastTick < 1000) return;
    lastTick = now;
    faceView.tick();
    int minute = (int) (System.currentTimeMillis() / 60_000L);
    watchFaces.tick(minute != lastMinute);
    lastMinute = minute;
    if (surface == Surface.WATCHFACE) refreshChip();
  }

  /** What is on the default display: a system surface, or {@code app}. */
  String surface() {
    return onTop() ? surface.id : "app";
  }

  private boolean onTop() {
    return home != null && navigator.top() == home;
  }

  // ---------------------------------------------------------------------------------------------
  // Navigation

  /** Shows [target], bringing the system UI over the app first if the app is on screen. */
  private void show(Surface target) {
    if (!onTop()) navigator.goHome();
    if (target != Surface.TILES) leaveTile();
    surface = target;
    if (root == null) return;
    face.setVisibility(target == Surface.WATCHFACE ? View.VISIBLE : View.GONE);
    launcher.setVisibility(target == Surface.LAUNCHER ? View.VISIBLE : View.GONE);
    notifications.setVisibility(target == Surface.NOTIFICATIONS ? View.VISIBLE : View.GONE);
    carousel.setVisibility(target == Surface.TILES ? View.VISIBLE : View.GONE);
    quickSettings.setVisibility(target == Surface.QUICK_SETTINGS ? View.VISIBLE : View.GONE);
    switch (target) {
      case WATCHFACE:
        refreshChip();
        break;
      case LAUNCHER:
        buildLauncher();
        launcher.scrollTo(0, 0);
        break;
      case NOTIFICATIONS:
        buildNotifications();
        notifications.scrollTo(0, 0);
        break;
      case TILES:
        buildCarousel();
        break;
      case QUICK_SETTINGS:
        buildQuickSettings();
        break;
    }
    protocol.event("surface", new Protocol.Json().put("surface", target.id).object());
  }

  /** {@link WearHome} came back on top: back from the app's root lands on the watch face. */
  void homeRestarted() {
    surface = Surface.WATCHFACE;
    if (root != null) {
      face.setVisibility(View.VISIBLE);
      launcher.setVisibility(View.GONE);
      notifications.setVisibility(View.GONE);
      carousel.setVisibility(View.GONE);
      quickSettings.setVisibility(View.GONE);
      refreshChip();
    }
  }

  /** Back on a system surface: to the watch face; on the face itself nothing happens. */
  void back() {
    if (surface == Surface.TILES && tiles.previous()) {
      buildCarousel();
      return;
    }
    leaveTile();
    if (surface != Surface.WATCHFACE) show(Surface.WATCHFACE);
  }

  /**
   * The keys the system handles before any app: the side button opens the launcher (and closes it
   * again from the launcher), Home goes to the watch face. Both the down and the up are consumed.
   */
  static boolean intercepts(KeyEvent event, int displayId) {
    WearSystemUi ui = current;
    if (ui == null || displayId != android.view.Display.DEFAULT_DISPLAY) return false;
    int code = event.getKeyCode();
    if (code != KeyEvent.KEYCODE_STEM_PRIMARY && code != KeyEvent.KEYCODE_HOME) return false;
    if (event.getAction() == KeyEvent.ACTION_UP) {
      if (code == KeyEvent.KEYCODE_HOME
          || (ui.onTop() && ui.surface == Surface.LAUNCHER)) {
        ui.show(Surface.WATCHFACE);
      } else {
        ui.show(Surface.LAUNCHER);
      }
    }
    return true;
  }

  /** Launches [activity] from the launcher, or brings the app's task back if that is its root. */
  private JSONObject launch(ComponentName activity) {
    Navigator.Entry root = null;
    for (Navigator.Entry entry : navigator.backStack()) {
      if (!entry.system && entry.displayId == android.view.Display.DEFAULT_DISPLAY) root = entry;
    }
    if (root != null
        && (activity.equals(root.activity().getComponentName())
            || activity.equals(root.request.getComponent()))) {
      return navigator.bringAppForward(true);
    }
    Intent intent =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return navigator.startActivity(intent, -1);
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "home":
        show(Surface.WATCHFACE);
        return describe();
      case "launcher":
        return launcherCommand(c);
      case "notifications":
        {
          String op = c.optString("op", "");
          if (op.equals("open")) {
            show(Surface.NOTIFICATIONS);
            return describe();
          }
          if (op.equals("close")) {
            show(Surface.WATCHFACE);
            return describe();
          }
          if (op.equals("clear")) return stream.clearAll();
          return null;
        }
      case "quickSettings":
        {
          if (c.has("airplane") || c.has("wifi")) {
            radios(
                c.has("wifi") ? c.getBoolean("wifi") : null,
                c.has("airplane") ? c.getBoolean("airplane") : null);
          }
          show(Surface.QUICK_SETTINGS);
          return quickSettingsState();
        }
      case "tiles":
        return tilesCommand(c);
      case "watchface":
        {
          JSONObject result = watchFaces.command(c);
          String op = c.optString("op", "status");
          if (!op.equals("status") && !op.equals("uncomplication") && !op.equals("unset")) {
            show(Surface.WATCHFACE);
          }
          return result;
        }
      case "health":
        {
          JSONObject data = systemData.command(c);
          if (surface == Surface.TILES && onTop()) buildCarousel();
          return data;
        }
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    switch (name) {
      case "home":
      case "launcher":
      case "notifications":
      case "quickSettings":
      case "tiles":
      case "watchface":
      case "health":
        return true;
      default:
        return false;
    }
  }

  /**
   * The screen changed power state ({@link ScreenPower}): the watch face dims to its ambient
   * outline while the screen dozes, and a system surface left open goes back to the face, as on a
   * watch.
   */
  void screen(ScreenPolicy.State state) {
    ambient = state != ScreenPolicy.State.ON;
    if (faceView != null) faceView.ambient(ambient);
    watchFaces.ambient(ambient);
    if (ongoingChip != null) ongoingChip.setAlpha(ambient ? 0.5f : 1f);
    if (ambient && onTop() && surface != Surface.WATCHFACE) show(Surface.WATCHFACE);
  }

  JSONObject describe() {
    return new Protocol.Json()
        .put("surface", surface())
        .put("ambient", ambient)
        .put("activity", navigator.top() == null ? null : navigator.top().activity().getClass().getName())
        .object();
  }

  private JSONObject launcherCommand(JSONObject c) throws JSONException {
    List<ResolveInfo> apps = launchable();
    if (c.has("index") || c.has("component") || c.has("label")) {
      ResolveInfo chosen = null;
      if (c.has("index")) {
        int i = c.getInt("index");
        if (i < 0 || i >= apps.size()) {
          throw new IllegalArgumentException("the launcher has " + apps.size() + " apps, no " + i);
        }
        chosen = apps.get(i);
      } else {
        String want = c.has("component") ? c.getString("component") : c.getString("label");
        for (ResolveInfo info : apps) {
          String name = info.activityInfo.name;
          if (name.equals(want)
              || name.endsWith("." + want)
              || name.endsWith(want)
              || label(info).equalsIgnoreCase(want)) {
            chosen = info;
            break;
          }
        }
        if (chosen == null) throw new IllegalArgumentException("no launcher entry " + want);
      }
      return launch(component(chosen));
    }
    show(Surface.LAUNCHER);
    JSONArray items = new JSONArray();
    for (ResolveInfo info : apps) {
      items.put(
          new Protocol.Json()
              .put("label", label(info))
              .put("component", info.activityInfo.name)
              .object());
    }
    return new Protocol.Json()
        .put("surface", surface())
        .put("apps", items)
        .put("ongoing", stream.ongoing.list())
        .object();
  }

  private JSONObject tilesCommand(JSONObject c) throws JSONException {
    String op = c.optString("op", "list");
    boolean showing = onTop() && surface == Surface.TILES;
    switch (op) {
      case "list":
        return tiles.list();
      case "add":
        tiles.add(c.getString("component"));
        show(Surface.TILES);
        break;
      case "remove":
        {
          TileHost.Item item = tiles.remove(c.getString("component"));
          widgetFrames.remove(item.key());
          FrameLayout page = tilePages.remove(item.key());
          if (page != null && page.getParent() != null) ((ViewGroup) page.getParent()).removeView(page);
          if (item.key().equals(enteredTile)) enteredTile = null;
          if (showing) buildCarousel();
          break;
        }
      case "show":
        if (c.has("component")) tiles.show(c.getString("component"));
        else tiles.show(c.optInt("index", 0));
        show(Surface.TILES);
        break;
      case "next":
        if (showing) tiles.next();
        else tiles.show(0);
        show(Surface.TILES);
        break;
      case "prev":
        if (showing && !tiles.previous()) show(Surface.WATCHFACE);
        else show(Surface.TILES);
        break;
      case "refresh":
        {
          TileHost.Item item =
              c.has("component") ? null : tiles.item(tiles.page());
          if (c.has("component")) {
            tiles.show(c.getString("component"));
            item = tiles.item(tiles.page());
          }
          if (item == null || !item.tile()) throw new IllegalStateException("no tile is showing");
          tiles.client(item).request();
          show(Surface.TILES);
          break;
        }
      default:
        throw new IllegalArgumentException(
            "tiles op is list, add, remove, show, next, prev or refresh, not " + op);
    }
    JSONObject result = tiles.list();
    result.put("surface", surface());
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // Views

  /** Builds the surfaces for [activity], {@link WearHome}, and returns its content view. */
  View attach(Activity activity) {
    context = activity;
    root = new Root(activity, this);
    root.setBackgroundColor(Color.BLACK);

    face = new FrameLayout(activity);
    faceView = new WatchFaceView(activity);
    faceView.ambient(ambient);
    face.addView(faceView, matchParent());
    ongoingChip = new TextView(activity);
    ongoingChip.setTextColor(Color.WHITE);
    ongoingChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    ongoingChip.setSingleLine(true);
    ongoingChip.setEllipsize(TextUtils.TruncateAt.END);
    ongoingChip.setGravity(Gravity.CENTER_VERTICAL);
    ongoingChip.setCompoundDrawablePadding(dp(6));
    ongoingChip.setPadding(dp(10), dp(6), dp(12), dp(6));
    ongoingChip.setBackground(pill(0xFF2D3F66));
    ongoingChip.setMaxWidth(dp(150));
    ongoingChip.setOnClickListener(v -> run(() -> stream.ongoing.tap(0)));
    tag(ongoingChip, "ongoing_chip", null);
    FrameLayout.LayoutParams chip =
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    chip.bottomMargin = dp(30);
    face.addView(ongoingChip, chip);
    peek = new LinearLayout(activity);
    peek.setOrientation(LinearLayout.VERTICAL);
    peek.setVisibility(View.GONE);
    FrameLayout.LayoutParams peekParams =
        new FrameLayout.LayoutParams(
            dp(170), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    peekParams.topMargin = dp(28);
    face.addView(peek, peekParams);
    root.addView(face, matchParent());

    launcherList = column(activity);
    launcher = scroller(activity, launcherList);
    root.addView(launcher, matchParent());

    notificationList = column(activity);
    notifications = scroller(activity, notificationList);
    root.addView(notifications, matchParent());

    carousel = new FrameLayout(activity);
    root.addView(carousel, matchParent());

    quickSettingsList = column(activity);
    quickSettings = scroller(activity, quickSettingsList);
    root.addView(quickSettings, matchParent());

    surface = Surface.WATCHFACE;
    launcher.setVisibility(View.GONE);
    notifications.setVisibility(View.GONE);
    carousel.setVisibility(View.GONE);
    quickSettings.setVisibility(View.GONE);
    refreshChip();
    return root;
  }

  private void refreshChip() {
    if (ongoingChip == null) return;
    faceView.notifications(stream.shown().size());
    List<OngoingActivities.Item> items = stream.ongoing.items();
    if (items.isEmpty()) {
      ongoingChip.setVisibility(View.GONE);
      return;
    }
    OngoingActivities.Item item = items.get(0);
    String status = stream.ongoing.status(item);
    ongoingChip.setText(status == null ? stream.ongoing.title(item) : status);
    ongoingChip.setContentDescription(item.notification.key);
    Drawable icon = stream.ongoing.icon(item);
    if (icon != null) {
      icon = icon.mutate();
      icon.setBounds(0, 0, dp(16), dp(16));
      icon.setTint(Color.WHITE);
    }
    ongoingChip.setCompoundDrawables(icon, null, null, null);
    ongoingChip.setVisibility(View.VISIBLE);
  }

  private void streamChanged() {
    List<NotificationStream.Item> shown = stream.shown();
    NotificationStream.Item interrupting = null;
    for (NotificationStream.Item item : shown) {
      if (seen.add(item.key + "@" + item.postTime) && stream.headsUp(item) && interrupting == null) {
        interrupting = item;
      }
    }
    if (root == null) return;
    if (interrupting != null && onTop() && surface == Surface.WATCHFACE && !ambient) {
      peek.removeAllViews();
      peek.addView(notificationCard(interrupting, false));
      peek.setVisibility(View.VISIBLE);
      peekUntil = SystemClock.uptimeMillis() + PEEK_MS;
    }
    if (surface == Surface.NOTIFICATIONS) buildNotifications();
    if (surface == Surface.LAUNCHER) buildLauncher();
    refreshChip();
  }

  // -- Launcher

  private List<ResolveInfo> launchable() {
    Intent main =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(app.getPackageName());
    List<ResolveInfo> apps = app.getPackageManager().queryIntentActivities(main, 0);
    return apps == null ? new ArrayList<>() : apps;
  }

  private String label(ResolveInfo info) {
    CharSequence label = info.loadLabel(app.getPackageManager());
    return label == null ? info.activityInfo.name : label.toString();
  }

  private static ComponentName component(ResolveInfo info) {
    return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
  }

  private void buildLauncher() {
    launcherList.removeAllViews();
    launcherList.addView(heading("Apps"));
    List<OngoingActivities.Item> ongoing = stream.ongoing.items();
    for (int i = 0; i < ongoing.size(); i++) {
      OngoingActivities.Item item = ongoing.get(i);
      int index = i;
      String status = stream.ongoing.status(item);
      View row =
          row(
              stream.ongoing.icon(item),
              stream.ongoing.title(item),
              status,
              0xFF2D3F66,
              v -> run(() -> stream.ongoing.tap(index)));
      tag(row, "ongoing_row", item.notification.key);
      launcherList.addView(row);
    }
    for (ResolveInfo info : launchable()) {
      ComponentName component = component(info);
      Drawable icon;
      try {
        icon = info.loadIcon(app.getPackageManager());
      } catch (RuntimeException e) {
        icon = null;
      }
      View row =
          row(icon, label(info), null, 0xFF202124, v -> run(() -> launch(component)));
      tag(row, "launcher_item", component.getClassName());
      launcherList.addView(row);
    }
  }

  // -- Notifications

  private void buildNotifications() {
    notificationList.removeAllViews();
    notificationList.addView(heading("Notifications"));
    List<NotificationStream.Item> shown = stream.shown();
    int cards = 0;
    for (NotificationStream.Item item : shown) {
      if (stream.collapsedUnderSummary(item)) continue;
      notificationList.addView(notificationCard(item, expanded.contains(item.key)));
      cards++;
    }
    if (cards == 0) {
      TextView empty = text("No notifications", 14, 0xFFBDC1C6, false);
      empty.setGravity(Gravity.CENTER);
      tag(empty, "notifications_empty", null);
      notificationList.addView(empty, wide());
      return;
    }
    TextView clear = chip("Clear all", v -> run(() -> {
      stream.clearAll();
      return null;
    }));
    tag(clear, "notifications_clear_all", null);
    notificationList.addView(clear, wide());
  }

  /**
   * Scrolls the stream so the card for [key] starts near the top, as a watch opens one; once the
   * rebuilt list has been laid out, since only then are the cards where they will be drawn.
   */
  private void reveal(String key) {
    for (int i = 0; i < notificationList.getChildCount(); i++) {
      View child = notificationList.getChildAt(i);
      if (!key.contentEquals(String.valueOf(child.getContentDescription()))) continue;
      android.view.ViewTreeObserver observer = notifications.getViewTreeObserver();
      observer.addOnGlobalLayoutListener(
          new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
              notifications.getViewTreeObserver().removeOnGlobalLayoutListener(this);
              notifications.scrollTo(0, Math.max(0, child.getTop() - dp(24)));
            }
          });
      notifications.requestLayout();
      return;
    }
  }

  private View notificationCard(NotificationStream.Item item, boolean open) {
    Notification n = item.notification;
    Bundle extras = n.extras;
    LinearLayout card = new LinearLayout(context);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(12), dp(10), dp(12), dp(10));
    card.setBackground(rounded(0xFF202124, 22));
    tag(card, "notification_card", item.key);
    card.setOnClickListener(
        v -> {
          boolean opening = expanded.add(item.key);
          if (!opening) expanded.remove(item.key);
          buildNotifications();
          if (opening) reveal(item.key);
        });

    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    Drawable small = load(n.getSmallIcon());
    if (small != null) {
      ImageView icon = new ImageView(context);
      small = small.mutate();
      small.setTint(n.color != 0 ? n.color : 0xFFA8C7FA);
      icon.setImageDrawable(small);
      header.addView(icon, new LinearLayout.LayoutParams(dp(14), dp(14)));
    }
    TextView appName = text(appLabel(), 11, 0xFFBDC1C6, false);
    appName.setPadding(dp(6), 0, 0, 0);
    header.addView(appName);
    card.addView(header);

    String title = NotificationStream.text(extras, Notification.EXTRA_TITLE);
    if (title != null) card.addView(text(title, 14, Color.WHITE, true));
    String bigText = NotificationStream.text(extras, Notification.EXTRA_BIG_TEXT);
    String body = NotificationStream.text(extras, Notification.EXTRA_TEXT);
    JSONArray messages = NotificationStream.messages(n);
    if (messages.length() > 0) {
      int from = open ? 0 : Math.max(0, messages.length() - 2);
      for (int i = from; i < messages.length(); i++) {
        JSONObject m = messages.optJSONObject(i);
        String sender = m.isNull("sender") ? "" : m.optString("sender", "");
        TextView line =
            text((sender.isEmpty() ? "" : sender + ": ") + m.optString("text", ""), 13,
                0xFFE8EAED, false);
        if (!open) line.setMaxLines(1);
        card.addView(line);
      }
    } else {
      String shownText = open && bigText != null ? bigText : body != null ? body : bigText;
      if (shownText != null) {
        TextView line = text(shownText, 13, 0xFFE8EAED, false);
        if (!open) {
          line.setMaxLines(2);
          line.setEllipsize(TextUtils.TruncateAt.END);
        }
        card.addView(line);
      }
    }
    List<NotificationStream.Item> children = stream.children(item);
    if (!children.isEmpty()) {
      for (int i = 0; i < children.size() && (open || i < 2); i++) {
        String childTitle =
            NotificationStream.text(children.get(i).notification.extras, Notification.EXTRA_TITLE);
        String childText =
            NotificationStream.text(children.get(i).notification.extras, Notification.EXTRA_TEXT);
        TextView child =
            text(
                (childTitle == null ? "" : childTitle + "  ")
                    + (childText == null ? "" : childText),
                12, 0xFFBDC1C6, false);
        child.setMaxLines(1);
        card.addView(child);
      }
      if (!open && children.size() > 2) {
        card.addView(text("+" + (children.size() - 2) + " more", 12, 0xFFA8C7FA, false));
      }
    }
    if (!open) return card;

    Bitmap picture = picture(extras);
    if (picture != null) {
      ImageView image = new ImageView(context);
      image.setImageBitmap(picture);
      image.setAdjustViewBounds(true);
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80));
      params.topMargin = dp(6);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      card.addView(image, params);
    }
    if (n.contentIntent != null) {
      TextView openChip = chip("Open", v -> run(() -> stream.tap(item)));
      tag(openChip, "notification_open", item.key);
      card.addView(openChip, wide());
    }
    List<Notification.Action> actions = NotificationStream.actions(n);
    for (int i = 0; i < actions.size(); i++) {
      Notification.Action action = actions.get(i);
      int index = i;
      TextView actionChip =
          chip(
              action.title == null ? "Action " + i : action.title.toString(),
              v -> run(() -> stream.action(item, index, null)));
      tag(actionChip, "notification_action", item.key + "/" + i);
      card.addView(actionChip, wide());
      RemoteInput[] inputs = action.getRemoteInputs();
      if (inputs == null) continue;
      for (RemoteInput input : inputs) {
        CharSequence[] choices = input.getChoices();
        if (choices == null) continue;
        for (CharSequence choice : choices) {
          TextView reply =
              chip(
                  "↩ " + choice,
                  v -> run(() -> stream.action(item, index, choice.toString())));
          tag(reply, "notification_reply", item.key + "/" + i + "/" + choice);
          card.addView(reply, wide());
        }
      }
    }
    if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR)) == 0) {
      TextView dismiss = chip("Dismiss", v -> run(() -> stream.dismiss(item)));
      tag(dismiss, "notification_dismiss", item.key);
      card.addView(dismiss, wide());
    }
    return card;
  }

  private Bitmap picture(Bundle extras) {
    if (extras == null) return null;
    Object picture = extras.get(Notification.EXTRA_PICTURE);
    if (picture instanceof Bitmap) return (Bitmap) picture;
    Object large = extras.get(Notification.EXTRA_LARGE_ICON_BIG);
    if (large instanceof Bitmap) return (Bitmap) large;
    return null;
  }

  // -- Tiles

  /** Shows the carousel's current page: the widgets list, one tile, or the add page. */
  private void buildCarousel() {
    for (int i = carousel.getChildCount() - 1; i >= 0; i--) carousel.removeViewAt(i);
    carouselScroll = null;
    String page = tiles.page();
    TileHost.Item item = tiles.item(page);
    if (item != null && item.tile()) {
      carousel.addView(tilePage(item), matchParent());
      enterTile(item);
    } else {
      leaveTile();
      carousel.addView(page.equals(TileHost.WIDGETS_PAGE) ? widgetsPage() : addPage(), matchParent());
    }
    carousel.addView(pageDots(), matchParent());
  }

  /** [item]'s page: the renderer's view once it has drawn, a placeholder until then. */
  private FrameLayout tilePage(TileHost.Item item) {
    FrameLayout page = tilePages.get(item.key());
    TileClient client = tiles.client(item);
    if (page == null) {
      page = new FrameLayout(context);
      tag(page, "tile", item.component.getClassName());
      tilePages.put(item.key(), page);
      client.attach(context, page);
      TextView loading = text("Loading " + item.label + "\u2026", 13, 0xFFBDC1C6, false);
      loading.setGravity(Gravity.CENTER);
      page.addView(loading, matchParent());
      client.request();
    }
    return page;
  }

  /** Tells the tile on screen it is entered, and the one before it that it was left. */
  private void enterTile(TileHost.Item item) {
    if (item.key().equals(enteredTile)) return;
    leaveTile();
    enteredTile = item.key();
    try {
      tiles.client(item).event("TileEnterEvent", "onTileEnterEvent");
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Best effort, as on a watch.
    }
  }

  private void leaveTile() {
    if (enteredTile == null) return;
    TileHost.Item item = tiles.item(enteredTile);
    enteredTile = null;
    if (item == null) return;
    try {
      tiles.client(item).event("TileLeaveEvent", "onTileLeaveEvent");
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Best effort.
    }
  }

  /** A tile drew (or failed): its page shows the result. */
  private void tileRendered(TileClient client, View view, String error) {
    if (error != null) {
      FrameLayout page = tilePages.get(client.component.flattenToString());
      if (page != null) {
        page.removeAllViews();
        TextView failed = text("Tile failed:\n" + error, 11, 0xFFF28B82, false);
        failed.setGravity(Gravity.CENTER);
        failed.setPadding(dp(28), 0, dp(28), 0);
        tag(failed, "tile_error", client.component.getClassName());
        page.addView(failed, matchParent());
      }
    }
    protocol.event(
        "tile",
        new Protocol.Json()
            .put("component", client.component.getClassName())
            .put("state", error == null ? "rendered" : "failed")
            .put("error", error)
            .object());
  }

  /**
   * The added Wear Widgets on one page, as a scrolling list in the manner of Wear Compose's {@code
   * TransformingLazyColumn}: items shrink and fade as they near the round screen's top and bottom
   * edges. Each widget is its container and nothing more, as a watch shows it: the document paints
   * its own background, the host only masks it to the container's shape and adds no card, frame or
   * label. Small (2x1) and large (2x2) containers are centred, 8dp apart.
   */
  private View widgetsPage() {
    LinearLayout list = column(context);
    list.setGravity(Gravity.CENTER_HORIZONTAL);
    list.setPadding(0, dp(48), 0, dp(64));
    for (TileHost.Item item : tiles.widgets()) {
      WidgetClient.Container container = container(item);
      FrameLayout frame = widgetFrame(item, container);
      tag(frame, "widget", item.component.getClassName());
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(dp(container.widthDp), dp(container.heightDp));
      params.topMargin = dp(8);
      list.addView(frame, params);
    }
    TextView add = chip("+ Add widget", v -> showAddPage());
    tag(add, "tiles_add_page", null);
    LinearLayout.LayoutParams addParams =
        new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT);
    addParams.topMargin = dp(12);
    list.addView(add, addParams);
    ScrollView scroll = new TransformingScrollView(context);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(list);
    carouselScroll = scroll;
    return scroll;
  }

  /** The host slot [item] gets on this watch: its container type, on this display's shape. */
  private WidgetClient.Container container(TileHost.Item item) {
    return WidgetClient.Container.of(
        item.containerType,
        FormFactor.isRound(),
        app.getResources().getConfiguration().screenWidthDp);
  }

  /** [item]'s frame: its document once played, its preview image until then. */
  private FrameLayout widgetFrame(TileHost.Item item, WidgetClient.Container container) {
    FrameLayout frame = widgetFrames.get(item.key());
    if (frame != null) {
      if (frame.getParent() != null) ((ViewGroup) frame.getParent()).removeView(frame);
      return frame;
    }
    frame = new FrameLayout(context);
    widgetFrames.put(item.key(), frame);
    Drawable preview = tiles.preview(item);
    if (preview != null) {
      ImageView image = new ImageView(context);
      image.setImageDrawable(preview);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      frame.addView(image, matchParent());
    }
    WidgetClient client = tiles.widgetClient(item);
    client.attach(context, frame, container);
    client.request();
    return frame;
  }

  private void widgetRendered(WidgetClient client, View view, String error) {
    if (error != null) {
      FrameLayout frame = widgetFrames.get(client.component.flattenToString());
      if (frame != null) {
        TextView failed = text("Widget failed:\n" + error, 10, 0xFFF28B82, false);
        failed.setGravity(Gravity.CENTER);
        tag(failed, "widget_error", client.component.getClassName());
        frame.addView(failed, matchParent());
      }
    }
    protocol.event(
        "widget",
        new Protocol.Json()
            .put("component", client.component.getClassName())
            .put("state", error == null ? "rendered" : "failed")
            .put("error", error)
            .object());
  }

  private void showAddPage() {
    tiles.show(tiles.pages().indexOf(TileHost.ADD_PAGE));
    buildCarousel();
  }

  /** What the app offers and is not in the carousel yet; a tap adds it and shows its page. */
  private View addPage() {
    LinearLayout list = column(context);
    list.addView(heading("Add tile or widget"));
    List<TileHost.Item> available = tiles.notAdded();
    if (available.isEmpty()) {
      TextView none = text("Everything is added", 13, 0xFFBDC1C6, false);
      none.setGravity(Gravity.CENTER);
      list.addView(none, wide());
    }
    for (TileHost.Item item : available) {
      View row =
          row(
              tiles.preview(item),
              item.label,
              item.tile() ? "Tile" : "Wear Widget",
              0xFF202124,
              v -> run(() -> {
                tiles.add(item.component.flattenToString());
                buildCarousel();
                return tiles.describe(item);
              }));
      tag(row, "tile_add", item.component.getClassName());
      list.addView(row);
    }
    ScrollView scroll = new TransformingScrollView(context);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(list);
    carouselScroll = scroll;
    return scroll;
  }

  /** The page dots along the bottom: one per page, the current one filled. */
  private View pageDots() {
    LinearLayout dots = new LinearLayout(context);
    dots.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    dots.setPadding(0, 0, 0, dp(10));
    List<String> pages = tiles.pages();
    for (int i = 0; i < pages.size(); i++) {
      View dot = new View(context);
      dot.setBackground(rounded(i == tiles.index() ? Color.WHITE : 0xFF5F6368, 3));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(6), dp(6));
      params.setMargins(dp(3), 0, dp(3), 0);
      dots.addView(dot, params);
    }
    return dots;
  }

  /**
   * A scroll view whose items scale and fade toward the top and bottom edges, as Wear Compose's
   * {@code TransformingLazyColumn} morphs them on a round screen.
   */
  private static final class TransformingScrollView extends ScrollView {
    TransformingScrollView(Context context) {
      super(context);
      setBackgroundColor(Color.BLACK);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      super.onLayout(changed, l, t, r, b);
      transform();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
      super.onScrollChanged(l, t, oldl, oldt);
      transform();
    }

    private void transform() {
      if (getChildCount() == 0 || getHeight() == 0) return;
      ViewGroup list = (ViewGroup) getChildAt(0);
      float half = getHeight() / 2f;
      for (int i = 0; i < list.getChildCount(); i++) {
        View item = list.getChildAt(i);
        float center = item.getTop() + item.getHeight() / 2f - getScrollY();
        float distance = Math.min(1f, Math.abs(center - half) / half);
        // Flat through the middle, then easing down to 80% and 40% opacity at the very edge.
        float edge = Math.max(0f, (distance - 0.45f) / 0.55f);
        float scale = 1f - 0.2f * edge * edge;
        item.setScaleX(scale);
        item.setScaleY(scale);
        item.setAlpha(1f - 0.6f * edge * edge);
      }
    }
  }

  // -- Quick settings

  private JSONObject quickSettingsState() {
    JSONObject state = QuickSettings.state(app);
    try {
      state.put("surface", surface());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return state;
  }

  /** A quick-settings switch: the radios as {@link NetworkSim}'s {@code network} command sets them. */
  private static void radios(Boolean wifi, Boolean airplane) {
    QuickSettings.radios(wifi, airplane, null);
  }

  private void buildQuickSettings() {
    quickSettingsList.removeAllViews();
    JSONObject state = quickSettingsState();
    quickSettingsList.addView(heading("Quick settings"));
    TextView battery =
        chip(
            "Battery " + state.optInt("battery") + "%"
                + (state.optBoolean("charging") ? " ⚡" : ""),
            null);
    tag(battery, "qs_battery", null);
    quickSettingsList.addView(battery, wide());
    boolean airplane = state.optBoolean("airplane");
    TextView airplaneChip =
        chip(
            "Airplane mode: " + (airplane ? "on" : "off"),
            v -> run(() -> {
              radios(null, !airplane);
              buildQuickSettings();
              return null;
            }));
    tag(airplaneChip, "qs_airplane", airplane ? "on" : "off");
    quickSettingsList.addView(airplaneChip, wide());
    boolean wifi = state.optBoolean("wifi");
    TextView wifiChip =
        chip(
            "Wi-Fi: " + (wifi ? "on" : "off"),
            v -> run(() -> {
              radios(!wifi, null);
              buildQuickSettings();
              return null;
            }));
    tag(wifiChip, "qs_wifi", wifi ? "on" : "off");
    quickSettingsList.addView(wifiChip, wide());
  }

  // -- Gestures and the crown

  /** A swipe on the current surface, in the direction the finger moved. */
  private void swiped(int dx, int dy) {
    boolean horizontal = Math.abs(dx) > Math.abs(dy);
    switch (surface) {
      case WATCHFACE:
        if (horizontal && dx < 0) {
          tiles.show(0);
          show(Surface.TILES);
        } else if (!horizontal && dy < 0) {
          show(Surface.NOTIFICATIONS);
        } else if (!horizontal && dy > 0) {
          show(Surface.QUICK_SETTINGS);
        }
        break;
      case TILES:
        if (horizontal && dx < 0) {
          if (tiles.next()) buildCarousel();
        } else if (horizontal && dx > 0) {
          if (tiles.previous()) {
            buildCarousel();
          } else {
            leaveTile();
            show(Surface.WATCHFACE);
          }
        }
        break;
      case QUICK_SETTINGS:
        if ((horizontal && dx > 0) || (!horizontal && dy < 0)) show(Surface.WATCHFACE);
        break;
      default:
        // Launcher and stream scroll vertically; a swipe right dismisses them, as on a watch.
        if (horizontal && dx > 0) show(Surface.WATCHFACE);
        break;
    }
  }

  /** Whether a drag in this direction is the system's rather than the surface's scroll. */
  private boolean claims(int dx, int dy) {
    boolean horizontal = Math.abs(dx) > Math.abs(dy);
    switch (surface) {
      case WATCHFACE:
        return true;
      case TILES:
        return horizontal;
      case QUICK_SETTINGS:
        return horizontal || dy < 0;
      default:
        return horizontal && dx > 0;
    }
  }

  private ScrollView scrollerShown() {
    switch (surface) {
      case LAUNCHER:
        return launcher;
      case NOTIFICATIONS:
        return notifications;
      case QUICK_SETTINGS:
        return quickSettings;
      case TILES:
        // The widgets page and the add page scroll; a tile page is the tile's own.
        return carouselScroll != null && carouselScroll.isAttachedToWindow() ? carouselScroll : null;
      default:
        return null;
    }
  }

  /** The surfaces' parent: takes the system swipes and the crown. */
  private static final class Root extends FrameLayout {
    private final WearSystemUi ui;
    private final int slop;
    private float downX;
    private float downY;
    private boolean claimed;

    Root(Context context, WearSystemUi ui) {
      super(context);
      this.ui = ui;
      this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
      setFocusable(true);
      setFocusableInTouchMode(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          claimed = false;
          return false;
        case MotionEvent.ACTION_MOVE:
          {
            int dx = (int) (event.getX() - downX);
            int dy = (int) (event.getY() - downY);
            if (Math.hypot(dx, dy) > slop && ui.claims(dx, dy)) {
              claimed = true;
              return true;
            }
            return false;
          }
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
          claimed = true;
          return true;
        case MotionEvent.ACTION_UP:
          {
            int dx = (int) (event.getX() - downX);
            int dy = (int) (event.getY() - downY);
            if (claimed && Math.hypot(dx, dy) > slop * 3) ui.swiped(dx, dy);
            claimed = false;
            return true;
          }
        default:
          return true;
      }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
      if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
          && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
        ScrollView scroller = ui.scrollerShown();
        if (scroller == null) return false;
        float delta =
            -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                * ViewConfiguration.get(getContext()).getScaledVerticalScrollFactor();
        scroller.scrollBy(0, Math.round(delta));
        return true;
      }
      return super.dispatchGenericMotionEvent(event);
    }
  }

  // -- Building blocks

  private interface Action {
    JSONObject run() throws Exception;
  }

  /** Runs a tap's action; a failure is an {@code error} event, as the app's own would be. */
  private void run(Action action) {
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

  /** Gives [view] the resource id {@code sim:id/<id>} and, when given, [identity] as its desc. */
  private static void tag(View view, String id, String identity) {
    if (identity != null) view.setContentDescription(identity);
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    view.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setViewIdResourceName("sim:id/" + id);
          }
        });
  }

  private String appLabel() {
    return app.getApplicationInfo().loadLabel(app.getPackageManager()).toString();
  }

  private Drawable load(Icon icon) {
    if (icon == null) return null;
    try {
      return icon.loadDrawable(app);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private int dp(float value) {
    return Math.round(value * app.getResources().getDisplayMetrics().density);
  }

  private static FrameLayout.LayoutParams matchParent() {
    return new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
  }

  private LinearLayout.LayoutParams wide() {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(6);
    return params;
  }

  /** A vertical list inset for the round screen: wide margins top and bottom, narrower sides. */
  private LinearLayout column(Context context) {
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(22), dp(32), dp(22), dp(48));
    return column;
  }

  private ScrollView scroller(Context context, View content) {
    ScrollView scroll = new ScrollView(context);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setBackgroundColor(Color.BLACK);
    scroll.addView(content);
    return scroll;
  }

  private TextView heading(String value) {
    TextView heading = text(value, 13, 0xFFA8C7FA, true);
    heading.setGravity(Gravity.CENTER);
    heading.setPadding(0, 0, 0, dp(4));
    return heading;
  }

  private TextView text(String value, int sp, int color, boolean bold) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextColor(color);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
    return view;
  }

  private TextView chip(String value, View.OnClickListener click) {
    TextView chip = text(value, 13, Color.WHITE, false);
    chip.setGravity(Gravity.CENTER);
    chip.setSingleLine(true);
    chip.setEllipsize(TextUtils.TruncateAt.END);
    chip.setPadding(dp(12), dp(9), dp(12), dp(9));
    chip.setBackground(pill(0xFF303134));
    if (click != null) chip.setOnClickListener(click);
    return chip;
  }

  private View row(
      Drawable icon, String label, String detail, int background, View.OnClickListener click) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(8), dp(12), dp(8));
    row.setBackground(pill(background));
    if (icon != null) {
      ImageView image = new ImageView(context);
      image.setImageDrawable(icon);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
      params.rightMargin = dp(10);
      row.addView(image, params);
    }
    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView title = text(label, 14, Color.WHITE, false);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(title);
    if (detail != null) {
      TextView second = text(detail, 12, 0xFFBDC1C6, false);
      second.setSingleLine(true);
      texts.addView(second);
    }
    row.addView(texts);
    row.setOnClickListener(click);
    LinearLayout.LayoutParams params = wide();
    row.setLayoutParams(params);
    return row;
  }

  private GradientDrawable pill(int color) {
    return rounded(color, 26);
  }

  private GradientDrawable rounded(int color, int radiusDp) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(radiusDp));
    return shape;
  }
}
