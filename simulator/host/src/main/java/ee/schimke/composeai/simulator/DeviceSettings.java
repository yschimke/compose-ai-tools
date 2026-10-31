package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ContentResolver;
import android.provider.Settings;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The settings of the device's apps ({@code --app phone:a.apk --app phone:b.apk}), each in a
 * simulator of its own: {@code Settings.System}, {@code Settings.Secure} and {@code
 * Settings.Global} are the device's, so a value one app (or a command to it) changes, every app
 * reads, and their observers of that setting's URI hear of it. {@link SimulatorShadowSettings}
 * reports each write; one that changes the value goes over the {@link DeviceBus}.
 *
 * <p>Every change is kept with the time it was made, on the clock all the device's simulators
 * share, and the latest wins. Writes made while an app starts, before it joins the bus (a provider
 * or {@code Application.onCreate}), go out when it joins, and the apps already there answer with
 * theirs, so an app that joins later catches up.
 */
final class DeviceSettings {

  private static final String EVENT = "setting";
  private static final String SYNC = "settingsSync";

  private static volatile Application app;

  /** Set while another app's change is written here, which is not sent on again. */
  private static boolean receiving;

  /** Every change seen here, this app's and the others', by table and name. */
  private static final Map<String, Change> changes = new ConcurrentHashMap<>();

  private static final class Change {
    final String table;
    final String name;
    final String value;
    final long time;

    Change(String table, String name, String value, long time) {
      this.table = table;
      this.name = name;
      this.value = value;
      this.time = time;
    }

    JSONObject toJson() {
      return new Protocol.Json()
          .put("table", table)
          .put("name", name)
          .put("value", value)
          .put("time", time)
          .object();
    }
  }

  static void attach(Application app) {
    DeviceSettings.app = app;
    DeviceBus.onBroadcast(EVENT, (from, body) -> apply(body));
    DeviceBus.onBroadcast(
        SYNC,
        (from, body) -> {
          JSONArray all = body.optJSONArray("changes");
          if (all != null) for (int i = 0; i < all.length(); i++) apply(all.getJSONObject(i));
          // A newcomer asks; the apps already there answer once with theirs.
          if (body.optBoolean("ask")) sync(false);
        });
  }

  /** Once this app is on the bus: its changes so far go out, and the others answer with theirs. */
  static void joined() {
    if (DeviceBus.enabled()) sync(true);
  }

  private static void sync(boolean ask) {
    JSONArray all = new JSONArray();
    for (Change change : changes.values()) all.put(change.toJson());
    DeviceBus.broadcast(SYNC, new Protocol.Json().put("changes", all).put("ask", ask).object());
  }

  /**
   * [table]'s ({@code system}, {@code secure}, {@code global}) [name] is being set to [value] here:
   * the other apps set it too when that changes it.
   */
  static void changing(String table, String name, String value) {
    if (receiving || name == null || !DeviceBus.enabled()) return;
    Application self = app;
    String before = self == null ? null : read(self.getContentResolver(), table, name);
    if (self != null && Objects.equals(before, value)) return;
    Change change = new Change(table, name, value, System.currentTimeMillis());
    changes.put(table + "/" + name, change);
    // Before the bus is joined, this goes out with the rest when it is (joined).
    if (self != null) DeviceBus.broadcast(EVENT, change.toJson());
  }

  /** Another app's [change], written here unless a later one already was. */
  private static void apply(JSONObject body) {
    Application self = app;
    if (self == null) return;
    String table = body.optString("table");
    String name = body.optString("name");
    String value = Protocol.string(body, "value");
    long time = body.optLong("time");
    String key = table + "/" + name;
    Change known = changes.get(key);
    if (known != null && known.time >= time) return;
    changes.put(key, new Change(table, name, value, time));
    if (Objects.equals(read(self.getContentResolver(), table, name), value)) return;
    ContentResolver resolver = self.getContentResolver();
    receiving = true;
    try {
      // Robolectric's put also tells the setting's observers, as the settings provider does.
      switch (table) {
        case "system":
          Settings.System.putString(resolver, name, value);
          break;
        case "secure":
          Settings.Secure.putString(resolver, name, value);
          break;
        case "global":
          Settings.Global.putString(resolver, name, value);
          break;
        default:
          break;
      }
    } finally {
      receiving = false;
    }
  }

  private static String read(ContentResolver resolver, String table, String name) {
    switch (table) {
      case "system":
        return Settings.System.getString(resolver, name);
      case "secure":
        return Settings.Secure.getString(resolver, name);
      default:
        return Settings.Global.getString(resolver, name);
    }
  }

  private DeviceSettings() {}
}
