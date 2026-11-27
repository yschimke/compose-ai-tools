package com.google.wear;

import android.os.Build;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Wear OS system library ({@code wear-sdk}, {@code <uses-library android:name="wear-sdk">}) as
 * the simulator's watch reports it: a Wear OS with the ambient, remote interactions, watch face
 * push, and API version services. A watch on API 34+ has the library, and androidx.wear reads
 * {@code Sdk.VERSION.WEAR_SDK_INT} / {@code Sdk.VERSION_CODES.lookup} / {@code
 * Sdk.isApiVersionAtLeast} before using version-gated platform features such as the global status
 * bar ({@code WEAR_CINNAMON_BUN_2}, API 37.2). Apps compile against the real library and never
 * package it, so this copy on the simulator's class path is the only one.
 */
public final class Sdk {

  /** System property that enables the Wear OS 7.1 Canary global status bar (API 37.2). */
  public static final String GLOBAL_STATUS_BAR_PROPERTY = "composeai.simulator.globalStatusBar";

  /** Optional explicit Wear API version override (e.g. {@code WEAR_CINNAMON_BUN_2}). */
  public static final String WEAR_API_VERSION_PROPERTY = "composeai.simulator.wearApiVersion";

  /** The Wear SDK's version constants. */
  public static final class VERSION {
    /** Not a compile-time constant, so the app reads this value at run time. */
    public static final int WEAR_SDK_INT = Integer.getInteger("composeai.simulator.wearSdk", 0);

    /** Incremental Wear OS release within the current Android platform API level. */
    public static final int RELEASE = currentApiVersion().getIncrementalApiLevel();

    private VERSION() {}
  }

  /**
   * Wear OS incremental API version codes, matching {@code androidx.wear.utils.WearApiVersionHelper}
   * lookup names.
   */
  public static final class VERSION_CODES {
    public static final WearApiVersion WEAR_TIRAMISU_1 =
        new WearApiVersion("WEAR_TIRAMISU_1", 33, 1);
    public static final WearApiVersion WEAR_TIRAMISU_2 =
        new WearApiVersion("WEAR_TIRAMISU_2", 33, 2);
    public static final WearApiVersion WEAR_TIRAMISU_3 =
        new WearApiVersion("WEAR_TIRAMISU_3", 33, 3);
    public static final WearApiVersion WEAR_TIRAMISU_4 =
        new WearApiVersion("WEAR_TIRAMISU_4", 33, 4);
    public static final WearApiVersion WEAR_UDC_1 = new WearApiVersion("WEAR_UDC_1", 34, 1);
    public static final WearApiVersion WEAR_VIC_1 = new WearApiVersion("WEAR_VIC_1", 35, 1);
    public static final WearApiVersion WEAR_BAKLAVA_0 =
        new WearApiVersion("WEAR_BAKLAVA_0", 36, 0);
    public static final WearApiVersion WEAR_BAKLAVA_1 =
        new WearApiVersion("WEAR_BAKLAVA_1", 36, 1);
    public static final WearApiVersion WEAR_CINNAMON_BUN_0 =
        new WearApiVersion("WEAR_CINNAMON_BUN_0", 37, 0);
    public static final WearApiVersion WEAR_CINNAMON_BUN_1 =
        new WearApiVersion("WEAR_CINNAMON_BUN_1", 37, 1);
    public static final WearApiVersion WEAR_CINNAMON_BUN_2 =
        new WearApiVersion("WEAR_CINNAMON_BUN_2", 37, 2);

    private static final Map<String, WearApiVersion> BY_NAME;

    static {
      Map<String, WearApiVersion> map = new LinkedHashMap<>();
      WearApiVersion[] all = {
        WEAR_TIRAMISU_1,
        WEAR_TIRAMISU_2,
        WEAR_TIRAMISU_3,
        WEAR_TIRAMISU_4,
        WEAR_UDC_1,
        WEAR_VIC_1,
        WEAR_BAKLAVA_0,
        WEAR_BAKLAVA_1,
        WEAR_CINNAMON_BUN_0,
        WEAR_CINNAMON_BUN_1,
        WEAR_CINNAMON_BUN_2,
      };
      for (WearApiVersion version : all) {
        map.put(version.getName(), version);
      }
      BY_NAME = Collections.unmodifiableMap(map);
    }

    /** Returns the {@link WearApiVersion} for {@code name}, or null if unrecognized. */
    public static WearApiVersion lookup(String name) {
      if (name == null) return null;
      return BY_NAME.get(name);
    }

    private VERSION_CODES() {}
  }

  /**
   * Returns true when the simulated Wear OS API level is at least {@code requiredVersion}.
   *
   * <p>Called by {@code androidx.wear.utils.WearApiVersionHelper#isApiVersionAtLeast(String)} on
   * API 36+.
   */
  public static boolean isApiVersionAtLeast(WearApiVersion requiredVersion) {
    if (requiredVersion == null) return false;
    return currentApiVersion().compareTo(requiredVersion) >= 0;
  }

  /** The simulated Wear OS API version for the current platform SDK and launch flags. */
  public static WearApiVersion currentApiVersion() {
    return currentApiVersion(Build.VERSION.SDK_INT);
  }

  static WearApiVersion currentApiVersion(int sdkInt) {
    String explicit = System.getProperty(WEAR_API_VERSION_PROPERTY);
    if (explicit != null && !explicit.isBlank()) {
      WearApiVersion known = VERSION_CODES.lookup(explicit.trim());
      if (known != null) return known;
    }
    if (sdkInt >= 37) {
      return isGlobalStatusBarFlagEnabled()
          ? VERSION_CODES.WEAR_CINNAMON_BUN_2
          : VERSION_CODES.WEAR_CINNAMON_BUN_1;
    }
    if (sdkInt == 36) return VERSION_CODES.WEAR_BAKLAVA_1;
    if (sdkInt == 35) return VERSION_CODES.WEAR_VIC_1;
    if (sdkInt == 34) return VERSION_CODES.WEAR_UDC_1;
    if (sdkInt == 33) return VERSION_CODES.WEAR_TIRAMISU_4;
    return new WearApiVersion("API_" + sdkInt, sdkInt, 0);
  }

  static boolean isGlobalStatusBarFlagEnabled() {
    return "true".equalsIgnoreCase(System.getProperty(GLOBAL_STATUS_BAR_PROPERTY, "").trim());
  }

  /**
   * The Wear SDK services the simulator has: the ambient service ({@link
   * com.google.wear.services.ambient.AmbientManager}), which Wear Compose's {@code
   * rememberAmbientModeManager()} uses whatever {@code WEAR_SDK_INT} says, and {@code
   * RemoteInteractionsManager} ({@code RemoteActivityHelper} needs it on API 34+); null for any
   * other.
   */
  public static <T> T getWearManager(android.content.Context context, Class<T> type) {
    if (type == com.google.wear.services.ambient.AmbientManager.class) {
      return type.cast(com.google.wear.services.ambient.AmbientManager.INSTANCE);
    }
    if (type
        == com.google.wear.services.remoteinteractions.RemoteInteractionsManager.class) {
      return type.cast(
          new com.google.wear.services.remoteinteractions.RemoteInteractionsManager(context));
    }
    if (type
        == com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.class) {
      return type.cast(
          new com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager(context));
    }
    return null;
  }

  private Sdk() {}
}
