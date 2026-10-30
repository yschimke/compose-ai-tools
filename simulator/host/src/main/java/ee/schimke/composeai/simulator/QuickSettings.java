package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a system UI's quick settings and status bar show and switch, shared by the watch's ({@link
 * WearSystemUi}) and the phone's ({@link PhoneSystemUi}): the battery, and the radios as {@link
 * NetworkSim}'s {@code network} command reads and sets them.
 */
final class QuickSettings {

  /**
   * The battery, airplane mode, Wi-Fi and mobile data, and which network is the default: {@code
   * {battery, charging, airplane, wifi, data, cellular, default}}.
   */
  static JSONObject state(Application app) {
    BatteryManager battery = app.getSystemService(BatteryManager.class);
    int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    if (level < 0 || level > 100) {
      // Never set by the `battery` command: the sticky broadcast, else a full battery.
      Intent sticky = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      level = sticky == null ? 100 : sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
    }
    JSONObject network = NetworkSim.get().state();
    boolean cellular = false;
    JSONArray networks = network.optJSONArray("networks");
    for (int i = 0; networks != null && i < networks.length(); i++) {
      JSONObject n = networks.optJSONObject(i);
      if (n != null && "cellular".equals(n.optString("transport"))) cellular = true;
    }
    return new Protocol.Json()
        .put("battery", level)
        .put("charging", battery.isCharging())
        .put("airplane", network.optBoolean("airplane"))
        .put("wifi", network.optBoolean("wifi"))
        .put("data", network.optBoolean("data"))
        .put("cellular", cellular)
        .put("default", network.isNull("default") ? null : network.optString("default"))
        .object();
  }

  /**
   * Switches the radios given (a null leaves that one as it is), as the {@code network} command.
   */
  static void radios(Boolean wifi, Boolean airplane, Boolean data) {
    try {
      JSONObject c = new JSONObject();
      if (wifi != null) c.put("wifi", wifi);
      if (airplane != null) c.put("airplane", airplane);
      if (data != null) c.put("data", data);
      NetworkSim.get().command(c);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private QuickSettings() {}
}
