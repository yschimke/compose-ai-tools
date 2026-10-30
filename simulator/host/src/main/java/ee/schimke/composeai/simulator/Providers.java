package ee.schimke.composeai.simulator;

import android.app.AppComponentFactory;
import android.app.Application;
import android.content.ContentProvider;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import java.util.Arrays;
import java.util.Comparator;
import org.json.JSONArray;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * The manifest's content providers, created and registered for their authorities as the system
 * does at process start: highest {@code initOrder} first, each built by the app's {@code
 * AppComponentFactory} and attached to the application with its manifest meta-data (which runs its
 * {@code onCreate()}), all before {@code Application.onCreate()}. Robolectric skips this when the
 * manifest comes from an APK. Off with {@code composeai.simulator.skipProviders=true}.
 */
final class Providers {

  /** What [installBeforeOnCreate] did, reported as the `providers` event once the channel opens. */
  private static volatile JSONArray installed = new JSONArray();

  static void installBeforeOnCreate(Application app) {
    if (Boolean.getBoolean("composeai.simulator.skipProviders")) return;
    installed = install(app);
  }

  static JSONArray installed() {
    return installed;
  }

  private static JSONArray install(Application app) {
    JSONArray result = new JSONArray();
    PackageInfo info;
    try {
      info =
          app.getPackageManager()
              .getPackageInfo(
                  app.getPackageName(),
                  PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
    } catch (PackageManager.NameNotFoundException e) {
      return result;
    }
    if (info.providers == null) return result;
    ProviderInfo[] providers = info.providers.clone();
    Arrays.sort(providers, Comparator.comparingInt((ProviderInfo p) -> p.initOrder).reversed());
    AppComponentFactory factory = AppComponents.factory(app);
    for (ProviderInfo provider : providers) {
      Protocol.Json row =
          new Protocol.Json().put("provider", provider.name).put("authority", provider.authority);
      try {
        ContentProvider instance = factory.instantiateProvider(app.getClassLoader(), provider.name);
        instance.attachInfo(app, provider);
        if (provider.authority != null) {
          for (String authority : provider.authority.split(";")) {
            ShadowContentResolver.registerProviderInternal(authority, instance);
          }
        }
        row.put("installed", true);
      } catch (Throwable t) {
        row.put("installed", false).put("error", String.valueOf(t));
      }
      result.put(row.object());
    }
    return result;
  }

  private Providers() {}
}
