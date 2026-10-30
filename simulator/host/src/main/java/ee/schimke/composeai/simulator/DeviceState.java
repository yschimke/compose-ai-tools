package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBatteryManager;
import org.robolectric.shadows.ShadowLocationManager;
import org.robolectric.shadows.ShadowPowerManager;

/**
 * The emulator's extended controls that are not the display: battery and power saving, a mock
 * location, and the time zone. Each sets what the framework's managers report and sends the
 * broadcast the system would, so an app that listens hears the change and one that asks later
 * reads it.
 *
 * <p>Runs on the main thread, inside the sandbox.
 */
final class DeviceState {

  private final Application app;
  private final Navigator navigator;
  private int batteryLevel = 100;
  private int plugged = BatteryManager.BATTERY_PLUGGED_AC;
  private boolean powerSave;
  private final BatteryWarnings warnings = new BatteryWarnings();

  DeviceState(Application app, Navigator navigator) {
    this.app = app;
    this.navigator = navigator;
  }

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "battery":
        return battery(c);
      case "location":
        return location(c);
      case "timezone":
        return timezone(c.getString("value"));
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    switch (name) {
      case "battery":
      case "location":
      case "timezone":
        return true;
      default:
        return false;
    }
  }

  /**
   * {@code level} (0–100), {@code plugged} ({@code ac}, {@code usb}, {@code wireless}, {@code
   * none}; or {@code charging} as a boolean) and {@code powerSave}; each optional, the rest kept.
   */
  private JSONObject battery(JSONObject c) throws JSONException {
    int oldLevel = batteryLevel;
    boolean wasCharging = plugged != 0;
    if (c.has("level")) batteryLevel = Math.max(0, Math.min(100, c.getInt("level")));
    if (c.has("plugged")) plugged = plugged(c.getString("plugged"));
    if (c.has("charging")) {
      plugged = c.getBoolean("charging") ? BatteryManager.BATTERY_PLUGGED_AC : 0;
    }
    boolean charging = plugged != 0;
    BatteryManager batteryManager = app.getSystemService(BatteryManager.class);
    ShadowBatteryManager battery = Shadow.extract(batteryManager);
    battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, batteryLevel);
    battery.setIntProperty(
        BatteryManager.BATTERY_PROPERTY_STATUS,
        charging
            ? (batteryLevel >= 100
                ? BatteryManager.BATTERY_STATUS_FULL
                : BatteryManager.BATTERY_STATUS_CHARGING)
            : BatteryManager.BATTERY_STATUS_DISCHARGING);
    battery.setIsCharging(charging);
    // The sticky broadcast is how most apps read the battery: registerReceiver(null, filter).
    Intent changed =
        new Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PRESENT, true)
            .putExtra(BatteryManager.EXTRA_LEVEL, batteryLevel)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(
                BatteryManager.EXTRA_STATUS,
                charging
                    ? BatteryManager.BATTERY_STATUS_CHARGING
                    : BatteryManager.BATTERY_STATUS_DISCHARGING)
            .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD);
    app.sendStickyBroadcast(changed);
    if (charging != wasCharging) {
      navigator.broadcast(
          new Intent(charging ? Intent.ACTION_POWER_CONNECTED : Intent.ACTION_POWER_DISCONNECTED));
    }
    switch (warnings.update(oldLevel, wasCharging, batteryLevel, charging)) {
      case LOW:
        navigator.broadcast(new Intent(Intent.ACTION_BATTERY_LOW));
        break;
      case OKAY:
        navigator.broadcast(new Intent(Intent.ACTION_BATTERY_OKAY));
        break;
      default:
        break;
    }
    if (c.has("powerSave") && c.getBoolean("powerSave") != powerSave) {
      powerSave = c.getBoolean("powerSave");
      ShadowPowerManager power = Shadow.extract(app.getSystemService(PowerManager.class));
      power.setIsPowerSaveMode(powerSave);
      navigator.broadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    }
    return new Protocol.Json()
        .put("level", batteryLevel)
        .put("charging", charging)
        .put("plugged", pluggedName(plugged))
        .put("low", warnings.low())
        .put("powerSave", app.getSystemService(PowerManager.class).isPowerSaveMode())
        .object();
  }

  private static int plugged(String name) {
    switch (name.toLowerCase(Locale.ROOT)) {
      case "ac":
        return BatteryManager.BATTERY_PLUGGED_AC;
      case "usb":
        return BatteryManager.BATTERY_PLUGGED_USB;
      case "wireless":
        return BatteryManager.BATTERY_PLUGGED_WIRELESS;
      case "none":
      case "unplugged":
        return 0;
      default:
        throw new IllegalArgumentException("plugged is ac, usb, wireless or none, not " + name);
    }
  }

  private static String pluggedName(int plugged) {
    switch (plugged) {
      case BatteryManager.BATTERY_PLUGGED_AC:
        return "ac";
      case BatteryManager.BATTERY_PLUGGED_USB:
        return "usb";
      case BatteryManager.BATTERY_PLUGGED_WIRELESS:
        return "wireless";
      default:
        return "none";
    }
  }

  /**
   * A fix at {@code lat}/{@code lng} ({@code accuracy} metres, {@code altitude}, {@code bearing},
   * {@code speed} optional), delivered to the platform's GPS, network and fused providers: their
   * listeners hear it and {@code getLastKnownLocation} returns it. Play services' fused location is
   * not present, so only apps on the platform API (or {@code LocationManagerCompat}) see it.
   */
  private JSONObject location(JSONObject c) throws JSONException {
    LocationManager manager = app.getSystemService(LocationManager.class);
    ShadowLocationManager shadow = Shadow.extract(manager);
    shadow.setLocationEnabled(true);
    for (String provider :
        new String[] {
          LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"
        }) {
      shadow.setProviderEnabled(provider, true);
      Location fix = new Location(provider);
      fix.setLatitude(c.getDouble("lat"));
      fix.setLongitude(c.getDouble("lng"));
      fix.setAccuracy((float) c.optDouble("accuracy", 5));
      if (c.has("altitude")) fix.setAltitude(c.getDouble("altitude"));
      if (c.has("bearing")) fix.setBearing((float) c.getDouble("bearing"));
      if (c.has("speed")) fix.setSpeed((float) c.getDouble("speed"));
      fix.setTime(System.currentTimeMillis());
      fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      shadow.simulateLocation(provider, fix);
    }
    Location last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
    return new Protocol.Json()
        .put("lat", last == null ? null : last.getLatitude())
        .put("lng", last == null ? null : last.getLongitude())
        .put("accuracy", last == null ? null : last.getAccuracy())
        .object();
  }

  /** Sets the default time zone ({@code Europe/London}, ...) and tells the app. */
  private JSONObject timezone(String id) {
    TimeZone zone = TimeZone.getTimeZone(id);
    if (!zone.getID().equals(id) && !"GMT".equals(id)) {
      throw new IllegalArgumentException("unknown time zone " + id);
    }
    TimeZone.setDefault(zone);
    JSONObject delivered =
        navigator.broadcast(
            new Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra("time-zone", zone.getID()));
    return new Protocol.Json()
        .put("timezone", TimeZone.getDefault().getID())
        .put("offsetMinutes", zone.getOffset(System.currentTimeMillis()) / 60000)
        .put("broadcast", delivered)
        .object();
  }
}
