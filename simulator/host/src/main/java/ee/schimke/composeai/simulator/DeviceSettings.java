package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ContentResolver;
import android.provider.Settings;
import java.util.Objects;
import org.json.JSONObject;

/**
 * The settings of the device's apps ({@code --app phone:a.apk --app phone:b.apk}), each in a
 * simulator of its own: {@code Settings.System}, {@code Settings.Secure} and {@code
 * Settings.Global} are the device's, so a value one app (or a command to it) changes, every app
 * reads, and their observers of that setting's URI hear of it. {@link SimulatorShadowSettings}
 * reports each write; one that changes the value goes over the {@link DeviceBus}.
 */
final class DeviceSettings {

  private static final String EVENT = "setting";

  private static volatile Application app;

  /** Set while another app's change is written here, which is not sent on again. */
  private static boolean receiving;

  static void attach(Application app) {
    DeviceSettings.app = app;
    DeviceBus.onBroadcast(EVENT, DeviceSettings::heard);
  }

  /**
   * [table]'s ({@code system}, {@code secure}, {@code global}) [name] is being set to [value] here:
   * the other apps set it too when that changes it.
   */
  static void changing(String table, String name, String value) {
    Application self = app;
    if (self == null || receiving || !DeviceBus.enabled() || name == null) return;
    if (Objects.equals(read(self.getContentResolver(), table, name), value)) return;
    DeviceBus.broadcast(
        EVENT,
        new Protocol.Json().put("table", table).put("name", name).put("value", value).object());
  }

  private static void heard(String from, JSONObject body) {
    Application self = app;
    if (self == null) return;
    String table = body.optString("table");
    String name = body.optString("name");
    String value = Protocol.string(body, "value");
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
