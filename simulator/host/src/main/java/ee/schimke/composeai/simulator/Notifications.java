package ee.schimke.composeai.simulator;

import android.app.Notification;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** A notification in brief, as a foreground service reports it; {@link NotificationStream} has the rest. */
final class Notifications {

  static JSONObject describe(Notification notification, Integer id) {
    Bundle extras = notification.extras;
    Protocol.Json json = new Protocol.Json();
    if (id != null) json.put("id", id);
    json.put("channel", notification.getChannelId());
    json.put("title", text(extras, Notification.EXTRA_TITLE));
    json.put("text", text(extras, Notification.EXTRA_TEXT));
    json.put("ongoing", (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
    JSONArray actions = new JSONArray();
    if (notification.actions != null) {
      for (Notification.Action action : notification.actions) {
        actions.put(action.title == null ? null : action.title.toString());
      }
    }
    json.put("actions", actions);
    return json.object();
  }

  private static String text(Bundle extras, String key) {
    if (extras == null) return null;
    CharSequence value = extras.getCharSequence(key);
    return value == null ? null : value.toString();
  }

  private Notifications() {}
}
