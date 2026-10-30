package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;

/**
 * How runtime permission requests are answered, in place of the system's dialog: each permission
 * by its own answer if one was given, else by the policy, which grants as a user tapping "Allow"
 * would. Set with the {@code permissions} command. Install-time permissions are granted from the
 * start, as on a device.
 */
final class Permissions {

  private final Application app;
  private final ShadowApplication shadowApp;
  private boolean grantByDefault = true;

  /** Per-permission answers, overriding the policy. */
  private final Map<String, Boolean> answers = new LinkedHashMap<>();

  Permissions(Application app) {
    this.app = app;
    this.shadowApp = Shadow.extract(app);
  }

  /**
   * The platform's runtime ("dangerous") permissions, the ones a device asks the user for.
   * Robolectric has no platform permission definitions to read a protection level from.
   */
  private static final Set<String> RUNTIME =
      new HashSet<>(
          Arrays.asList(
              "ACCEPT_HANDOVER",
              "ACCESS_BACKGROUND_LOCATION",
              "ACCESS_COARSE_LOCATION",
              "ACCESS_FINE_LOCATION",
              "ACCESS_MEDIA_LOCATION",
              "ACTIVITY_RECOGNITION",
              "ADD_VOICEMAIL",
              "ANSWER_PHONE_CALLS",
              "BLUETOOTH_ADVERTISE",
              "BLUETOOTH_CONNECT",
              "BLUETOOTH_SCAN",
              "BODY_SENSORS",
              "BODY_SENSORS_BACKGROUND",
              "CALL_PHONE",
              "CAMERA",
              "GET_ACCOUNTS",
              "NEARBY_WIFI_DEVICES",
              "POST_NOTIFICATIONS",
              "PROCESS_OUTGOING_CALLS",
              "RANGING",
              "READ_CALENDAR",
              "READ_CALL_LOG",
              "READ_CONTACTS",
              "READ_EXTERNAL_STORAGE",
              "READ_MEDIA_AUDIO",
              "READ_MEDIA_IMAGES",
              "READ_MEDIA_VIDEO",
              "READ_MEDIA_VISUAL_USER_SELECTED",
              "READ_PHONE_NUMBERS",
              "READ_PHONE_STATE",
              "READ_SMS",
              "RECEIVE_MMS",
              "RECEIVE_SMS",
              "RECEIVE_WAP_PUSH",
              "RECORD_AUDIO",
              "SEND_SMS",
              "USE_SIP",
              "UWB_RANGING",
              "WRITE_CALENDAR",
              "WRITE_CALL_LOG",
              "WRITE_CONTACTS",
              "WRITE_EXTERNAL_STORAGE"));

  /**
   * Grants what a device grants at install: every requested permission that is not a runtime one.
   * Robolectric starts with all of them denied, so {@code INTERNET} or {@code FOREGROUND_SERVICE}
   * checks fail. Runs before the application's {@code onCreate()}.
   */
  static void grantInstallTime(Application app) {
    PackageManager pm = app.getPackageManager();
    PackageInfo info;
    try {
      info = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
    } catch (PackageManager.NameNotFoundException e) {
      return;
    }
    if (info.requestedPermissions == null) return;
    ShadowApplication shadowApp = Shadow.extract(app);
    for (String name : info.requestedPermissions) {
      if (!isRuntime(pm, name)) shadowApp.grantPermissions(name);
    }
  }

  /** A runtime permission: the platform's, or one the app defines as dangerous. */
  static boolean isRuntime(PackageManager pm, String name) {
    // Health Connect's are all runtime.
    if (name.startsWith("android.permission.health.")) return true;
    if (name.startsWith("android.permission.")) {
      return RUNTIME.contains(name.substring("android.permission.".length()));
    }
    try {
      return pm.getPermissionInfo(name, 0).getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /** Whether a request for [permission] is granted. */
  boolean grants(String permission) {
    Boolean answer = answers.get(permission);
    return answer != null ? answer : grantByDefault;
  }

  /** Applies a request's answers to the app's permission state, as the dialog does. */
  int[] answer(String[] names) {
    int[] results = new int[names.length];
    for (int i = 0; i < names.length; i++) {
      boolean granted = grants(names[i]);
      set(names[i], granted);
      results[i] = granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }
    return results;
  }

  /**
   * The {@code permissions} command: {@code policy} ({@code grant} or {@code deny}) answers later
   * requests; {@code grant} and {@code deny} name permissions to answer that way from now on, and
   * change their state now too (a deny revokes). Returns the policy, the answers, and the state
   * of every permission the manifest requests.
   */
  JSONObject command(JSONObject c) throws JSONException {
    if (c.has("policy")) {
      String policy = c.getString("policy");
      if (!policy.equals("grant") && !policy.equals("deny")) {
        throw new IllegalArgumentException("policy is grant or deny, not " + policy);
      }
      grantByDefault = policy.equals("grant");
    }
    answer(c.optJSONArray("grant"), true);
    answer(c.optJSONArray("deny"), false);
    return state();
  }

  private void answer(JSONArray names, boolean granted) throws JSONException {
    if (names == null) return;
    for (int i = 0; i < names.length(); i++) {
      String name = names.getString(i);
      answers.put(name, granted);
      set(name, granted);
    }
  }

  private void set(String permission, boolean granted) {
    if (granted) {
      shadowApp.grantPermissions(permission);
    } else {
      shadowApp.denyPermissions(permission);
    }
  }

  private JSONObject state() {
    JSONObject current = new JSONObject();
    try {
      PackageInfo info =
          app.getPackageManager()
              .getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
      if (info.requestedPermissions != null) {
        for (String name : info.requestedPermissions) {
          boolean granted = app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED;
          current.put(name, granted ? "granted" : "denied");
        }
      }
    } catch (PackageManager.NameNotFoundException | JSONException e) {
      // Reported without the state.
    }
    JSONObject overrides = new JSONObject();
    try {
      for (Map.Entry<String, Boolean> entry : answers.entrySet()) {
        overrides.put(entry.getKey(), entry.getValue() ? "grant" : "deny");
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return new Protocol.Json()
        .put("policy", grantByDefault ? "grant" : "deny")
        .put("answers", overrides)
        .put("state", current)
        .object();
  }
}
