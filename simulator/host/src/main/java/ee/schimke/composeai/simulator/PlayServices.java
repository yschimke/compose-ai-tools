package ee.schimke.composeai.simulator;

import java.util.Locale;

/**
 * The Google Play services the simulated device has, as its availability checks report it: by
 * default installed and current, as on a phone or watch with Play services, so an app that gates
 * code on {@code isGooglePlayServicesAvailable(context) == SUCCESS} takes that path. {@code launch
 * --play-services none|outdated|disabled|invalid} (and the {@code playServices} command) make it
 * missing or broken instead, for an app's error paths.
 *
 * <p>Only availability: {@link ShadowGooglePlayServicesUtilLight} answers the status and APK
 * version every {@code GoogleApiAvailability} / {@code GooglePlayServicesUtil} check comes down
 * to, {@link ShadowGoogleApiAvailability} answers {@code checkApiAvailability}, and {@link
 * PlayServicesPackages} are what {@code PackageManager} shows. No Play services API itself is here: under
 * {@code --pair} or {@code --data-layer agent} the Wearable Data Layer is ({@link DataLayer}), and
 * every other API is reported unavailable rather than left to fail binding a service that does not
 * exist. Plain JVM code, no Android or Play services types: tested on the JVM, and readable outside
 * the sandbox.
 */
final class PlayServices {

  /** The launch's state ({@code launch --play-services}); installed when unset. */
  static final String PROPERTY = "composeai.simulator.playServices";

  static final String GMS_PACKAGE = "com.google.android.gms";
  static final String STORE_PACKAGE = "com.android.vending";

  /** Play services 25.42.34, a release from late 2025: newer than any app's client library. */
  static final int VERSION_CODE = 254_234_035;

  static final String VERSION_NAME = "25.42.34 (190400-822516034)";

  /**
   * {@link State#OUTDATED}'s version: 11.9.75, older than the 12.4.51 every current client library
   * ({@code GOOGLE_PLAY_SERVICES_VERSION_CODE}) requires.
   */
  static final int OUTDATED_VERSION_CODE = 11_975_035;

  static final String OUTDATED_VERSION_NAME = "11.9.75 (040700-0)";

  /** The Play Store's version, shown in {@code PackageManager} beside Play services. */
  static final int STORE_VERSION_CODE = 84_731_130;

  static final String STORE_VERSION_NAME = "47.3.13-31 [0] [PR] 812345678";

  // ConnectionResult codes.
  static final int SUCCESS = 0;
  static final int SERVICE_MISSING = 1;
  static final int SERVICE_VERSION_UPDATE_REQUIRED = 2;
  static final int SERVICE_DISABLED = 3;
  static final int SERVICE_INVALID = 9;
  static final int API_UNAVAILABLE = 16;

  /** What the device's Play services are, and the {@code ConnectionResult} each reports. */
  enum State {
    /** Installed, enabled and current: {@code SUCCESS}. */
    INSTALLED("installed", SUCCESS),
    /** Not installed (a device without Google services): {@code SERVICE_MISSING}. */
    NONE("none", SERVICE_MISSING),
    /** Installed but older than the app needs: {@code SERVICE_VERSION_UPDATE_REQUIRED}. */
    OUTDATED("outdated", SERVICE_VERSION_UPDATE_REQUIRED),
    /** Installed but disabled in Settings: {@code SERVICE_DISABLED}. */
    DISABLED("disabled", SERVICE_DISABLED),
    /** Installed but not genuine (wrong signature): {@code SERVICE_INVALID}. */
    INVALID("invalid", SERVICE_INVALID);

    final String id;
    final int status;

    State(String id, int status) {
      this.id = id;
      this.status = status;
    }

    /** The state named [id] (as {@code launch --play-services} takes it). */
    static State parse(String id) {
      String key = id.trim().toLowerCase(Locale.ROOT);
      for (State state : values()) if (state.id.equals(key)) return state;
      if (key.equals("missing")) return NONE;
      throw new IllegalArgumentException(
          "unknown Play services state \"" + id + "\": one of " + ids());
    }

    static String ids() {
      StringBuilder out = new StringBuilder();
      for (State state : values()) {
        if (out.length() > 0) out.append(", ");
        out.append(state.id);
      }
      return out.toString();
    }
  }

  private static volatile State state = fromProperty();

  private static State fromProperty() {
    String value = System.getProperty(PROPERTY);
    return value == null || value.isEmpty() ? State.INSTALLED : State.parse(value);
  }

  static State state() {
    return state;
  }

  /** Changes the state for every check from now on (the {@code playServices} command). */
  static void set(State next) {
    state = next;
  }

  /**
   * {@code isGooglePlayServicesAvailable(context, minApkVersion)}: as Play services' own check
   * compares versions, by their first digits (a version code's last three are the build variant).
   */
  static int status(int minApkVersion) {
    State current = state;
    if (current != State.INSTALLED) return current.status;
    return minApkVersion / 1000 > VERSION_CODE / 1000 ? SERVICE_VERSION_UPDATE_REQUIRED : SUCCESS;
  }

  /** {@code getApkVersion}: the installed version code, 0 with none installed. */
  static int apkVersion() {
    switch (state) {
      case NONE:
        return 0;
      case OUTDATED:
        return OUTDATED_VERSION_CODE;
      default:
        return VERSION_CODE;
    }
  }

  static String versionName() {
    switch (state) {
      case NONE:
        return null;
      case OUTDATED:
        return OUTDATED_VERSION_NAME;
      default:
        return VERSION_NAME;
    }
  }

  /** Whether {@code PackageManager} lists Play services and the Play Store. */
  static boolean installed() {
    return state != State.NONE;
  }

  /** Whether Play services' {@code ApplicationInfo.enabled} is set. */
  static boolean enabled() {
    return state != State.DISABLED;
  }

  /**
   * {@code checkApiAvailability}'s answer for one API: Play services' own status when it is not
   * {@code SUCCESS}; otherwise success for an API the simulator implements ([simulated]) and
   * {@code API_UNAVAILABLE} for any other, which no service here could connect.
   */
  static int apiStatus(boolean simulated) {
    int status = status(0);
    if (status != SUCCESS) return status;
    return simulated ? SUCCESS : API_UNAVAILABLE;
  }

  private PlayServices() {}
}
