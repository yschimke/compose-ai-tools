package ee.schimke.composeai.simulator.shadows;

import android.os.Build;
import com.google.wear.Sdk;
import com.google.wear.WearApiVersion;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/**
 * Shadows for {@code com.google.wear.Sdk} and {@code com.google.wear.Sdk$VERSION_CODES}.
 *
 * <p>{@code SimulatorSandbox} instruments the entire {@code com.google.wear.} package because Wear
 * apps bundle signature-only Wear SDK stubs that throw at runtime. Without explicit shadows,
 * Robolectric replaces {@code Sdk.VERSION_CODES.lookup} and {@code Sdk.isApiVersionAtLeast} with
 * default no-op stubs returning {@code null}/{@code false}, which prevents {@code
 * androidx.wear.utils.WearApiVersionHelper} (and therefore Wear Compose 1.8's global status bar)
 * from ever seeing {@code WEAR_CINNAMON_BUN_2}.
 */
@Implements(className = "com.google.wear.Sdk", isInAndroidSdk = false)
public final class ShadowWearSdk {

  private static final Map<Object, String> LOOKUP_NAMES =
      Collections.synchronizedMap(new IdentityHashMap<>());

  private ShadowWearSdk() {}

  @Implementation
  protected static boolean isApiVersionAtLeast(WearApiVersion requiredVersion) {
    if (requiredVersion == null) return false;
    String name = LOOKUP_NAMES.get(requiredVersion);
    if (name == null) return false;
    int[] req = parseVersion(name);
    if (req == null) return false;
    int[] cur = currentVersion();
    return cur[0] > req[0] || (cur[0] == req[0] && cur[1] >= req[1]);
  }

  @Implementation
  protected static <T> T getWearManager(android.content.Context context, Class<T> type) {
    if (type == com.google.wear.services.ambient.AmbientManager.class) {
      return type.cast(com.google.wear.services.ambient.AmbientManager.INSTANCE);
    }
    if (type == com.google.wear.services.remoteinteractions.RemoteInteractionsManager.class) {
      return type.cast(
          new com.google.wear.services.remoteinteractions.RemoteInteractionsManager(context));
    }
    if (type == com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.class) {
      return type.cast(
          new com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager(context));
    }
    return null;
  }

  /** Shadow for {@code com.google.wear.Sdk$VERSION_CODES}. */
  @Implements(className = "com.google.wear.Sdk$VERSION_CODES", isInAndroidSdk = false)
  public static final class VersionCodes {

    private VersionCodes() {}

    @Implementation
    protected static WearApiVersion lookup(String name) {
      if (name == null) return null;
      int[] parsed = parseVersion(name);
      if (parsed == null) return null;
      WearApiVersion token = new WearApiVersion(name, parsed[0], parsed[1]);
      LOOKUP_NAMES.put(token, name);
      return token;
    }
  }

  static int[] currentVersion() {
    String explicit = System.getProperty(Sdk.WEAR_API_VERSION_PROPERTY);
    if (explicit != null && !explicit.isBlank()) {
      int[] parsed = parseVersion(explicit.trim());
      if (parsed != null) return parsed;
    }
    int sdkInt = Build.VERSION.SDK_INT;
    if (sdkInt >= 37) {
      boolean gsb =
          "true"
              .equalsIgnoreCase(
                  System.getProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "").trim());
      return new int[] {37, gsb ? 2 : 1};
    }
    if (sdkInt == 36) return new int[] {36, 1};
    if (sdkInt == 35) return new int[] {35, 1};
    if (sdkInt == 34) return new int[] {34, 1};
    if (sdkInt == 33) return new int[] {33, 4};
    return new int[] {sdkInt, 0};
  }

  static int[] parseVersion(String name) {
    switch (name) {
      case "WEAR_TIRAMISU_1":
        return new int[] {33, 1};
      case "WEAR_TIRAMISU_2":
        return new int[] {33, 2};
      case "WEAR_TIRAMISU_3":
        return new int[] {33, 3};
      case "WEAR_TIRAMISU_4":
        return new int[] {33, 4};
      case "WEAR_UDC_1":
        return new int[] {34, 1};
      case "WEAR_VIC_1":
        return new int[] {35, 1};
      case "WEAR_BAKLAVA_0":
        return new int[] {36, 0};
      case "WEAR_BAKLAVA_1":
        return new int[] {36, 1};
      case "WEAR_CINNAMON_BUN_0":
        return new int[] {37, 0};
      case "WEAR_CINNAMON_BUN_1":
        return new int[] {37, 1};
      case "WEAR_CINNAMON_BUN_2":
        return new int[] {37, 2};
      default:
        return null;
    }
  }
}
