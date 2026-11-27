package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The app's Wear ongoing activities ({@code androidx.wear.ongoing.OngoingActivity}): a workout, a
 * timer, a call, which the watch face shows as a chip and the launcher as a row.
 *
 * <p>{@code OngoingActivity} is library code inside the app. It writes its icon, status, title and
 * touch intent into the extras of the notification it decorates; they are read back with the
 * library's own {@code SerializationHelper.create(Notification)}, called reflectively through the
 * app's class loader so the version always matches the one that wrote them. A {@code Status} with
 * timers is evaluated against the simulator's clock, so {@code wait} advances it.
 *
 * <p>When the app's copy of the library was renamed by R8, the notification is still recognized by
 * its extras, and the chip falls back to the notification's own title, icon and content intent.
 */
final class OngoingActivities {

  /** {@code SerializationHelper.EXTRA_ONGOING_ACTIVITY}. */
  static final String EXTRA = "android.wearable.ongoingactivities.EXTENSIONS";

  private static final String HELPER = "androidx.wear.ongoing.SerializationHelper";

  /** One ongoing activity, read back from its notification. */
  static final class Item {
    final NotificationStream.Item notification;
    /** The library's {@code OngoingActivity}, or null when its classes could not be found. */
    final Object activity;

    Item(NotificationStream.Item notification, Object activity) {
      this.notification = notification;
      this.activity = activity;
    }
  }

  private final Application app;
  private final NotificationStream stream;
  private Method create;
  private boolean missing;

  OngoingActivities(Application app, NotificationStream stream) {
    this.app = app;
    this.stream = stream;
  }

  /** The ongoing activities, newest first. */
  List<Item> items() {
    List<Item> items = new ArrayList<>();
    for (NotificationStream.Item n : stream.items()) {
      if (!has(n.notification)) continue;
      items.add(0, new Item(n, recover(n.notification)));
    }
    return items;
  }

  static boolean has(Notification notification) {
    return notification.extras != null && notification.extras.containsKey(EXTRA);
  }

  private Object recover(Notification notification) {
    if (missing) return null;
    try {
      if (create == null) {
        Class<?> helper = Class.forName(HELPER, true, app.getClassLoader());
        create = helper.getMethod("create", Notification.class);
      }
      return create.invoke(null, notification);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      missing = true;
      return null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static Object call(Object target, String method) {
    if (target == null) return null;
    try {
      return target.getClass().getMethod(method).invoke(target);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /** The status line, timers evaluated now, or the notification's text without one. */
  String status(Item item) {
    Object status = call(item.activity, "getStatus");
    if (status != null) {
      try {
        Method getText =
            status.getClass().getMethod("getText", android.content.Context.class, long.class);
        Object text = getText.invoke(status, app, SystemClock.elapsedRealtime());
        if (text != null) return text.toString();
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Fall back to the notification's text.
      }
    }
    return NotificationStream.text(item.notification.notification.extras, Notification.EXTRA_TEXT);
  }

  String title(Item item) {
    Object title = call(item.activity, "getTitle");
    if (title != null) return title.toString();
    String text =
        NotificationStream.text(item.notification.notification.extras, Notification.EXTRA_TITLE);
    return text != null ? text : label();
  }

  /** The static icon, else the notification's small icon. */
  Drawable icon(Item item) {
    Object icon = call(item.activity, "getStaticIcon");
    Icon source = icon instanceof Icon ? (Icon) icon : item.notification.notification.getSmallIcon();
    try {
      return source == null ? null : source.loadDrawable(app);
    } catch (RuntimeException e) {
      return null;
    }
  }

  PendingIntent touchIntent(Item item) {
    Object intent = call(item.activity, "getTouchIntent");
    return intent instanceof PendingIntent
        ? (PendingIntent) intent
        : item.notification.notification.contentIntent;
  }

  private String label() {
    return app.getApplicationInfo().loadLabel(app.getPackageManager()).toString();
  }

  /** What {@code notifications} reports of [notification]'s ongoing activity, or null. */
  JSONObject describe(Notification notification) {
    if (!has(notification)) return null;
    for (Item item : items()) {
      if (item.notification.notification == notification) return describe(item);
    }
    return null;
  }

  JSONObject describe(Item item) {
    return new Protocol.Json()
        .put("key", item.notification.key)
        .put("title", title(item))
        .put("status", status(item))
        .put("category", call(item.activity, "getCategory"))
        .put("touchIntent", touchIntent(item) != null)
        .put("library", item.activity != null)
        .object();
  }

  JSONArray list() {
    JSONArray array = new JSONArray();
    for (Item item : items()) array.put(describe(item));
    return array;
  }

  /** {@code ongoing}: lists them, or with {@code op: "tap"} sends one's touch intent. */
  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "list");
    switch (op) {
      case "list":
        return new Protocol.Json().put("ongoing", list()).object();
      case "tap":
        return tap(c.optInt("index", 0));
      default:
        throw new IllegalArgumentException("ongoing op is list or tap, not " + op);
    }
  }

  JSONObject tap(int index) {
    List<Item> items = items();
    if (index < 0 || index >= items.size()) {
      throw new IllegalArgumentException(
          "there are " + items.size() + " ongoing activities, no " + index);
    }
    Item item = items.get(index);
    PendingIntent intent = touchIntent(item);
    if (intent == null) throw new IllegalStateException("the ongoing activity has no touch intent");
    return new Protocol.Json()
        .put("key", item.notification.key)
        .put("sent", stream.send(intent, null))
        .object();
  }
}
