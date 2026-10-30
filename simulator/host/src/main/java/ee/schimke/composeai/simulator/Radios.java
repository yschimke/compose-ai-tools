package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The control channel's radio commands — {@code network}, {@code wifi}, {@code bluetooth} — and the
 * system dialogs and settings screens an app opens about them, which a device would show and the
 * agent answers here. {@link Session} hands it commands, cycles and the intents no activity of the
 * app's handles.
 */
final class Radios {

  private static Radios current;

  private final NetworkSim network;
  private final WifiSim wifi;
  private final BluetoothSim bluetooth;
  private final Protocol protocol;
  private final Navigator navigator;

  /** Settings screens and panels about the radios, which only the system handles. */
  private static final Set<String> SETTINGS_SCREENS =
      new HashSet<>(
          Arrays.asList(
              "android.settings.panel.action.INTERNET_CONNECTIVITY",
              "android.settings.panel.action.WIFI",
              "android.settings.panel.action.NFC",
              "android.settings.WIFI_SETTINGS",
              "android.settings.WIFI_ADD_NETWORKS",
              "android.settings.WIRELESS_SETTINGS",
              "android.settings.AIRPLANE_MODE_SETTINGS",
              "android.settings.BLUETOOTH_SETTINGS",
              "android.settings.DATA_ROAMING_SETTINGS",
              "android.settings.DATA_USAGE_SETTINGS",
              "android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS",
              "android.net.wifi.action.REQUEST_ENABLE",
              "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE"));

  Radios(Application app, Protocol protocol, Navigator navigator) {
    this.protocol = protocol;
    this.navigator = navigator;
    this.network = NetworkSim.get();
    this.wifi = network.wifi();
    this.bluetooth = new BluetoothSim(app, protocol, navigator);
    network.attach(navigator, bluetooth);
    wifi.attach(protocol);
    navigator.systemUi = this::systemIntent;
    current = this;
  }

  static Radios current() {
    return current;
  }

  /** The commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "network":
        return network.command(c);
      case "wifi":
        return wifi.command(c);
      case "bluetooth":
        return bluetooth.command(c);
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    return name.equals("network") || name.equals("wifi") || name.equals("bluetooth");
  }

  /** A {@code settings put} the radios act on; null when [key] is not theirs. */
  String setting(String key, String value) {
    String effect = network.setting(key, value);
    return effect != null ? effect : bluetooth.setting(key, value);
  }

  void cycle() {
    network.cycle();
    wifi.cycle();
    bluetooth.cycle();
  }

  /**
   * An activity intent only the system handles: Bluetooth's enable dialog is answered as {@link
   * BluetoothSim} decides; a radio settings screen or panel is reported as a {@code systemUi} event
   * and, for a result, closed unchanged ({@code RESULT_CANCELED}), as a user backing out would.
   * False for anything else.
   */
  boolean systemIntent(Intent intent, int requestCode, Activity requester) {
    if (bluetooth.requestEnable(intent, requestCode, requester)) return true;
    String action = intent.getAction();
    if (action == null || !SETTINGS_SCREENS.contains(action)) return false;
    protocol.event(
        "systemUi",
        new Protocol.Json()
            .put("action", action)
            .put("intent", Intents.toJson(intent))
            .put("requestCode", requestCode)
            .put("answer", requestCode >= 0 ? "RESULT_CANCELED" : null)
            .object());
    if (requester != null && requestCode >= 0) {
      navigator.deliverResult(requester, requestCode, Activity.RESULT_CANCELED, null);
    }
    return true;
  }
}
