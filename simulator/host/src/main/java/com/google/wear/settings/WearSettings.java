package com.google.wear.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import com.google.wear.Sdk;

/**
 * Wear OS platform settings ({@code wear-sdk}), queried by Wear Compose Material 3 1.8+ ({@code
 * androidx.wear.compose.material3.isStatusBarEnabled}) to determine whether the global system
 * status bar is active for the current app.
 *
 * <p>Matches the Wear OS 7.1 Canary (API 37.2) / Wear OS 8 enablement contract:
 *
 * <ul>
 *   <li>Wear form factor with the platform global status bar enabled ({@code
 *       composeai.simulator.globalStatusBar=true}) and Wear API version at least {@code
 *       WEAR_CINNAMON_BUN_2} (API 37.2);
 *   <li>app {@code targetSdkVersion >= 35} ({@link Build.VERSION_CODES#VANILLA_ICE_CREAM});
 *   <li>application manifest opt-in {@code <meta-data
 *       android:name="com.google.wear.ENABLE_GLOBAL_STATUS_BAR" android:value="true" />}.
 * </ul>
 */
public final class WearSettings {

  public static final String METADATA_ENABLE_GLOBAL_STATUS_BAR =
      "com.google.wear.ENABLE_GLOBAL_STATUS_BAR";

  private static final int MIN_TARGET_SDK = 35;

  private WearSettings() {}

  /**
   * Returns true when the global system status bar is enabled on the simulated Wear device and the
   * app represented by {@code context} meets the platform opt-in requirements.
   */
  public static boolean isStatusBarEnabled(Context context) {
    if (context == null) return false;
    if (!isPlatformStatusBarEnabled()) return false;
    return hasAppOptIn(context);
  }

  /** Whether dynamic color theming is enabled on the watch. */
  public static boolean isDynamicColorThemingEnabled(Context context) {
    return !"false"
        .equalsIgnoreCase(
            System.getProperty("composeai.simulator.themeFromWatchface", "true").trim());
  }

  /** Whether wrist-detection auto-locking is enabled on the watch. */
  public static boolean isWristDetectionAutoLockingEnabled(Context context) {
    return false;
  }

  /** Whether the simulated Wear OS platform itself has the global status bar active. */
  public static boolean isPlatformStatusBarEnabled() {
    String formFactor = System.getProperty("composeai.simulator.formFactor", "phone").trim();
    if (!"wear".equalsIgnoreCase(formFactor)) return false;
    if (!"true"
        .equalsIgnoreCase(System.getProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "").trim())) {
      return false;
    }
    return Sdk.isApiVersionAtLeast(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2);
  }

  /**
   * Whether the app meets the manifest and targetSdk requirements for the global status bar:
   * {@code targetSdkVersion >= 35} and {@code com.google.wear.ENABLE_GLOBAL_STATUS_BAR = true}.
   */
  public static boolean hasAppOptIn(Context context) {
    if (context == null) return false;
    ApplicationInfo appInfo = readApplicationInfo(context);
    if (appInfo == null || appInfo.targetSdkVersion < MIN_TARGET_SDK) return false;
    Bundle metaData = appInfo.metaData;
    return metaData != null && metaData.getBoolean(METADATA_ENABLE_GLOBAL_STATUS_BAR, false);
  }

  private static ApplicationInfo readApplicationInfo(Context context) {
    ApplicationInfo direct = null;
    try {
      direct = context.getApplicationInfo();
    } catch (RuntimeException ignored) {
      // Fall through to PackageManager.
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
      // Fall back to direct ApplicationInfo if PackageManager lookup fails.
    }
    return direct;
  }
}
