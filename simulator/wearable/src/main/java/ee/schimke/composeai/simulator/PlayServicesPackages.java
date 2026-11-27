package ee.schimke.composeai.simulator;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * {@code com.google.android.gms} and {@code com.android.vending} in {@code PackageManager}, as a
 * device with Play services lists them, at {@link PlayServices}' version: what an app reading
 * {@code getPackageInfo("com.google.android.gms", 0).versionCode}, or checking the Play Store is
 * there before opening a {@code market://} link, sees. Neither has components: nothing binds or
 * resolves to them. Absent with {@code --play-services none}; disabled with {@code disabled}.
 */
final class PlayServicesPackages {

  /** Installs, updates or removes both packages to match {@link PlayServices#state()}. */
  static void sync(Context context) {
    ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
    if (!PlayServices.installed()) {
      packages.removePackage(PlayServices.GMS_PACKAGE);
      packages.removePackage(PlayServices.STORE_PACKAGE);
      return;
    }
    packages.installPackage(
        info(
            PlayServices.GMS_PACKAGE,
            PlayServices.apkVersion(),
            PlayServices.versionName(),
            PlayServices.enabled()));
    packages.installPackage(
        info(
            PlayServices.STORE_PACKAGE,
            PlayServices.STORE_VERSION_CODE,
            PlayServices.STORE_VERSION_NAME,
            true));
    // installPackage leaves the enabled setting as it was; disabled is that setting on a device.
    context
        .getPackageManager()
        .setApplicationEnabledSetting(
        PlayServices.GMS_PACKAGE,
        PlayServices.enabled()
            ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        0);
  }

  /**
   * The {@code playServices} command: switches to [state] from now on, as installing, updating,
   * disabling or uninstalling Play services on a device would, with the package broadcasts that go
   * with it. An app that cached an earlier answer keeps it until it asks again.
   */
  static void change(Context context, String state) {
    boolean was = PlayServices.installed();
    PlayServices.set(PlayServices.State.parse(state));
    sync(context);
    boolean is = PlayServices.installed();
    String action =
        was == is
            ? Intent.ACTION_PACKAGE_CHANGED
            : is ? Intent.ACTION_PACKAGE_ADDED : Intent.ACTION_PACKAGE_REMOVED;
    Intent intent = new Intent(action, Uri.fromParts("package", PlayServices.GMS_PACKAGE, null));
    if (was && is) intent.putExtra(Intent.EXTRA_REPLACING, true);
    context.sendBroadcast(intent);
  }

  /** The {@code playServices} command's reply: the state and what the checks report. */
  static JSONObject describe() throws JSONException {
    JSONObject out = new JSONObject();
    out.put("state", PlayServices.state().id);
    out.put("status", PlayServices.status(0));
    out.put("installed", PlayServices.installed());
    if (PlayServices.installed()) {
      out.put("versionCode", PlayServices.apkVersion());
      out.put("versionName", PlayServices.versionName());
      out.put("enabled", PlayServices.enabled());
    }
    out.put("states", PlayServices.State.ids());
    return out;
  }

  private static PackageInfo info(String name, long versionCode, String versionName, boolean on) {
    PackageInfo info = new PackageInfo();
    info.packageName = name;
    info.versionName = versionName;
    info.setLongVersionCode(versionCode);
    ApplicationInfo app = new ApplicationInfo();
    app.packageName = name;
    app.name = name;
    app.enabled = on;
    app.flags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_INSTALLED;
    info.applicationInfo = app;
    return info;
  }

  private PlayServicesPackages() {}
}
