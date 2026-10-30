package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;

/**
 * The device-level commands an {@code adb shell} reaches for, beyond input and intents: {@code
 * settings}, {@code wm size} / {@code density} ({@code display}), {@code getprop}, {@code pm path}
 * / {@code dumpsys package} ({@code package}), {@code pm list packages} ({@code packages}) and
 * {@code content} queries and writes.
 */
final class Device {

  private final Application app;
  private final Navigator navigator;
  /** Owns the display: every size, density or font change goes through it. */
  private final DeviceDisplay display;
  private final String initialQualifiers;

  Device(Application app, Navigator navigator, DeviceDisplay display) {
    this.app = app;
    this.navigator = navigator;
    this.display = display;
    this.initialQualifiers = RuntimeEnvironment.getQualifiers();
  }

  // ---------------------------------------------------------------------------------------------
  // settings

  /**
   * {@code settings get|put|delete} in {@code system}, {@code secure} or {@code global}. A put of
   * a setting the system acts on does what the system does: {@code airplane_mode_on} and {@code
   * wifi_on} switch the network, {@code animator_duration_scale} scales {@code ValueAnimator}s,
   * {@code font_scale} changes the configuration, {@code time_12_24} sends {@code TIME_SET}.
   */
  JSONObject settings(JSONObject c) throws JSONException {
    String op = c.getString("op");
    String namespace = c.getString("namespace");
    ContentResolver resolver = app.getContentResolver();
    if (op.equals("list")) {
      throw new IllegalArgumentException(
          "settings list is not supported: Robolectric's settings store cannot be enumerated");
    }
    String key = c.getString("key");
    Protocol.Json json = new Protocol.Json().put("namespace", namespace).put("key", key);
    switch (op) {
      case "get":
        {
          String value = get(resolver, namespace, key);
          return json.put("value", value).put("output", String.valueOf(value)).object();
        }
      case "delete":
        put(resolver, namespace, key, null);
        return json.put("deleted", true).object();
      case "put":
        {
          String value = c.get("value").toString();
          put(resolver, namespace, key, value);
          json.put("value", value);
          String effect = applySetting(namespace, key, value);
          if (effect != null) json.put("effect", effect);
          return json.object();
        }
      default:
        throw new IllegalArgumentException("settings op is get, put or delete, not " + op);
    }
  }

  private static String get(ContentResolver resolver, String namespace, String key) {
    switch (namespace) {
      case "system":
        return Settings.System.getString(resolver, key);
      case "secure":
        return Settings.Secure.getString(resolver, key);
      case "global":
        return Settings.Global.getString(resolver, key);
      default:
        throw new IllegalArgumentException("namespace is system, secure or global");
    }
  }

  private static void put(ContentResolver resolver, String namespace, String key, String value) {
    switch (namespace) {
      case "system":
        Settings.System.putString(resolver, key, value);
        break;
      case "secure":
        Settings.Secure.putString(resolver, key, value);
        break;
      case "global":
        Settings.Global.putString(resolver, key, value);
        break;
      default:
        throw new IllegalArgumentException("namespace is system, secure or global");
    }
  }

  /** What the system does when [key] changes; null when it does nothing the app could see. */
  private String applySetting(String namespace, String key, String value) {
    switch (key) {
      case Settings.Global.AIRPLANE_MODE_ON:
        {
          boolean on = !value.equals("0");
          Connectivity.set(app, null, on);
          navigator.broadcast(
              new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on));
          return "airplane mode " + (on ? "on" : "off") + ", AIRPLANE_MODE broadcast";
        }
      case Settings.Global.WIFI_ON:
        Connectivity.set(app, !value.equals("0"), null);
        return "wifi " + (value.equals("0") ? "off" : "on");
      case Settings.Global.ANIMATOR_DURATION_SCALE:
        {
          float scale = Float.parseFloat(value);
          try {
            // @hide; what the system calls when the setting changes.
            android.animation.ValueAnimator.class
                .getMethod("setDurationScale", float.class)
                .invoke(null, scale);
          } catch (ReflectiveOperationException e) {
            return "not applied: " + e;
          }
          return "ValueAnimator duration scale " + scale;
        }
      case Settings.System.FONT_SCALE:
        RuntimeEnvironment.setFontScale(Float.parseFloat(value));
        display.configurationChanged();
        return "font scale " + value + ", configuration changed";
      case Settings.System.TIME_12_24:
        // Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT, @hide.
        navigator.broadcast(
            new Intent(Intent.ACTION_TIME_CHANGED)
                .putExtra(
                    "android.intent.extra.TIME_PREF_24_HOUR_FORMAT", value.equals("24") ? 1 : 0));
        return "TIME_SET broadcast";
      default:
        return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // display

  /**
   * {@code wm size WxH} and {@code wm density N}, in device pixels as on a device: a density change
   * keeps the pixel size, so the size in dp changes. {@code reset} restores the launch qualifiers.
   */
  JSONObject display(JSONObject c) throws JSONException {
    boolean reset =
        "reset".equals(c.optString("size", null)) || "reset".equals(c.optString("density", null));
    if (reset) {
      display.reset(initialQualifiers);
    } else if (c.has("size") || c.has("density")) {
      DisplayMetrics metrics = app.getResources().getDisplayMetrics();
      int dpi = c.has("density") ? c.getInt("density") : metrics.densityDpi;
      int width = metrics.widthPixels;
      int height = metrics.heightPixels;
      if (c.has("size")) {
        String[] size = c.getString("size").split("x");
        width = Integer.parseInt(size[0]);
        height = Integer.parseInt(size[1]);
      }
      display.overrideSize(width, height, dpi);
    }
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    return new Protocol.Json()
        .put("width", metrics.widthPixels)
        .put("height", metrics.heightPixels)
        .put("density", metrics.densityDpi)
        .put("qualifiers", RuntimeEnvironment.getQualifiers())
        .put(
            "output",
            "Physical size: "
                + metrics.widthPixels
                + "x"
                + metrics.heightPixels
                + "\nPhysical density: "
                + metrics.densityDpi)
        .object();
  }

  // ---------------------------------------------------------------------------------------------
  // getprop, package, packages

  private Map<String, String> properties() {
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ro.build.version.sdk", String.valueOf(Build.VERSION.SDK_INT));
    props.put("ro.build.version.release", Build.VERSION.RELEASE);
    props.put("ro.build.version.codename", Build.VERSION.CODENAME);
    props.put("ro.build.fingerprint", Build.FINGERPRINT);
    props.put("ro.build.type", Build.TYPE);
    props.put("ro.build.tags", Build.TAGS);
    props.put("ro.product.brand", Build.BRAND);
    props.put("ro.product.manufacturer", Build.MANUFACTURER);
    props.put("ro.product.model", Build.MODEL);
    props.put("ro.product.device", Build.DEVICE);
    props.put("ro.product.name", Build.PRODUCT);
    props.put("ro.product.cpu.abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
    props.put("ro.hardware", Build.HARDWARE);
    props.put("ro.debuggable", "1");
    props.put("ro.kernel.qemu", "0");
    int dpi = app.getResources().getDisplayMetrics().densityDpi;
    props.put("ro.sf.lcd_density", String.valueOf(dpi));
    props.put("persist.sys.locale", Locale.getDefault().toLanguageTag());
    props.put("ro.composeai.simulator", "1");
    return props;
  }

  JSONObject getprop(JSONObject c) {
    Map<String, String> props = properties();
    if (c.has("name")) {
      String name = c.optString("name");
      String value = props.getOrDefault(name, "");
      return new Protocol.Json()
          .put("name", name)
          .put("value", value)
          .put("output", value)
          .object();
    }
    Protocol.Json all = new Protocol.Json();
    StringBuilder output = new StringBuilder();
    for (Map.Entry<String, String> e : props.entrySet()) {
      all.put(e.getKey(), e.getValue());
      output.append('[').append(e.getKey()).append("]: [").append(e.getValue()).append("]\n");
    }
    return new Protocol.Json()
        .put("properties", all.object())
        .put("output", output.toString())
        .object();
  }

  JSONObject packageInfo() throws PackageManager.NameNotFoundException {
    PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
    String path = app.getPackageCodePath();
    return new Protocol.Json()
        .put("package", app.getPackageName())
        .put("versionName", info.versionName)
        .put("versionCode", info.getLongVersionCode())
        .put("targetSdk", app.getApplicationInfo().targetSdkVersion)
        .put("path", path)
        .put("dataDir", app.getDataDir().getPath())
        .put("permissions", navigator.permissions.state())
        .put("output", "package:" + path)
        .object();
  }

  JSONObject packages(JSONObject c) {
    String pkg = app.getPackageName();
    JSONArray list = new JSONArray();
    if (!c.has("filter") || pkg.contains(c.optString("filter"))) list.put(pkg);
    return new Protocol.Json()
        .put("packages", list)
        .put("output", list.length() == 0 ? "" : "package:" + pkg)
        .object();
  }

  // ---------------------------------------------------------------------------------------------
  // content

  /** {@code content query|insert|update|delete} against the app's or the framework's providers. */
  JSONObject content(JSONObject c) throws JSONException {
    String op = c.optString("op", "query");
    Uri uri = Uri.parse(c.getString("uri"));
    ContentResolver resolver = app.getContentResolver();
    String selection = c.optString("selection", null);
    String[] args = strings(c.optJSONArray("args"));
    switch (op) {
      case "query":
        return query(resolver, uri, c, selection, args);
      case "insert":
        {
          Uri inserted = resolver.insert(uri, values(c.optJSONObject("values")));
          return new Protocol.Json()
              .put("uri", inserted == null ? null : inserted.toString())
              .object();
        }
      case "update":
        {
          ContentValues values = values(c.optJSONObject("values"));
          int updated = resolver.update(uri, values, selection, args);
          return new Protocol.Json().put("updated", updated).object();
        }
      case "delete":
        return new Protocol.Json().put("deleted", resolver.delete(uri, selection, args)).object();
      default:
        throw new IllegalArgumentException(
            "content op is query, insert, update or delete, not " + op);
    }
  }

  private static JSONObject query(
      ContentResolver resolver, Uri uri, JSONObject c, String selection, String[] args)
      throws JSONException {
    String[] projection = strings(c.optJSONArray("projection"));
    int limit = c.optInt("limit", 50);
    JSONArray rows = new JSONArray();
    JSONArray columns = new JSONArray();
    try (Cursor cursor =
        resolver.query(uri, projection, selection, args, c.optString("sort", null))) {
      if (cursor == null) throw new IllegalArgumentException("no provider answered " + uri);
      for (String column : cursor.getColumnNames()) columns.put(column);
      while (rows.length() < limit && cursor.moveToNext()) {
        JSONArray row = new JSONArray();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
          row.put(cursor.getType(i) == Cursor.FIELD_TYPE_BLOB ? "<blob>" : cursor.getString(i));
        }
        rows.put(row);
      }
      return new Protocol.Json()
          .put("columns", columns)
          .put("rows", rows)
          .put("count", cursor.getCount())
          .object();
    }
  }

  /** Values as the shell translation writes them, {@code name: {type, value}}, or plain JSON. */
  private static ContentValues values(JSONObject values) throws JSONException {
    ContentValues out = new ContentValues();
    if (values == null) return out;
    for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
      String key = keys.next();
      Object value = values.get(key);
      if (value instanceof JSONObject && ((JSONObject) value).has("type")) {
        JSONObject typed = (JSONObject) value;
        switch (typed.getString("type")) {
          case "string" -> out.put(key, typed.getString("value"));
          case "boolean" -> out.put(key, typed.getBoolean("value"));
          case "int" -> out.put(key, typed.getInt("value"));
          case "long" -> out.put(key, typed.getLong("value"));
          case "float" -> out.put(key, (float) typed.getDouble("value"));
          case "double" -> out.put(key, typed.getDouble("value"));
          case "null" -> out.putNull(key);
          default -> throw new JSONException("unknown value type for " + key);
        }
      } else if (value == JSONObject.NULL) {
        out.putNull(key);
      } else if (value instanceof Boolean) {
        out.put(key, (Boolean) value);
      } else if (value instanceof Integer) {
        out.put(key, (Integer) value);
      } else if (value instanceof Long) {
        out.put(key, (Long) value);
      } else if (value instanceof Number) {
        out.put(key, ((Number) value).doubleValue());
      } else {
        out.put(key, value.toString());
      }
    }
    return out;
  }

  static String[] strings(JSONArray array) throws JSONException {
    if (array == null) return null;
    String[] out = new String[array.length()];
    for (int i = 0; i < out.length; i++) out[i] = array.getString(i);
    return out;
  }
}
