package ee.schimke.composeai.simulator.shadows;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import com.google.wear.Sdk;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Shadow for {@code com.google.wear.settings.WearSettings}, queried by Wear Compose Material 3 1.8
 * ({@code androidx.wear.compose.material3.StatusBarKt.isStatusBarEnabled}).
 *
 * <p>Because {@code SimulatorSandbox} instruments {@code com.google.wear.}, this shadow ensures
 * {@code WearSettings.isStatusBarEnabled(Context)} runs inside the sandbox rather than being
 * replaced by Robolectric's default {@code false} stub.
 */
@Implements(className = "com.google.wear.settings.WearSettings", isInAndroidSdk = false)
public final class ShadowWearSettings {

  private static final String METADATA_KEY = "com.google.wear.ENABLE_GLOBAL_STATUS_BAR";
  private static final int MIN_TARGET_SDK = 35;

  private ShadowWearSettings() {}

  @Implementation
  protected static boolean isStatusBarEnabled(Context context) {
    if (context == null) return false;
    String formFactor = System.getProperty("composeai.simulator.formFactor", "phone").trim();
    if (!"wear".equalsIgnoreCase(formFactor)) return false;
    if (!"true"
        .equalsIgnoreCase(System.getProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "").trim())) {
      return false;
    }
    int[] cur = ShadowWearSdk.currentVersion();
    if (cur[0] < 37 || (cur[0] == 37 && cur[1] < 2)) return false;

    ApplicationInfo appInfo = readApplicationInfo(context);
    if (appInfo == null || appInfo.targetSdkVersion < MIN_TARGET_SDK) return false;
    Bundle metaData = appInfo.metaData;
    return metaData != null && metaData.getBoolean(METADATA_KEY, false);
  }

  @Implementation
  protected static boolean isDynamicColorThemingEnabled(Context context) {
    return !"false"
        .equalsIgnoreCase(
            System.getProperty("composeai.simulator.themeFromWatchface", "true").trim());
  }

  @Implementation
  protected static boolean isWristDetectionAutoLockingEnabled(Context context) {
    return false;
  }

  private static ApplicationInfo readApplicationInfo(Context context) {
    ApplicationInfo direct = null;
    try {
      direct = context.getApplicationInfo();
    } catch (RuntimeException ignored) {
    }
    if (direct != null && direct.metaData != null) {
      return direct;
    }
    try {
      PackageManager pm = context.getPackageManager();
      if (pm != null && context.getPackageName() != null) {
        ApplicationInfo loaded =
            pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        if (loaded != null) return loaded;
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
    }
    return direct;
  }
}
