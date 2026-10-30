package ee.schimke.composeai.simulator;

import android.content.Context;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services' availability status and version, from {@link PlayServices} instead of the
 * package and signature checks of {@code com.google.android.gms}, which the simulator does not
 * have. Every public check comes down to these: {@code GoogleApiAvailability(Light)
 * .isGooglePlayServicesAvailable}, {@code GooglePlayServicesUtil(Light)
 * .isGooglePlayServicesAvailable}, {@code verifyGooglePlayServicesIsAvailable}, {@code
 * makeGooglePlayServicesAvailable}, {@code getApkVersion}, and the check each {@code GoogleApi}
 * makes before it connects.
 *
 * <p>Like {@link ShadowWearable}, it names no Play services type: it is loaded outside the sandbox
 * too, and an app without Play services simply never loads the class it shadows.
 */
@Implements(className = ShadowGooglePlayServicesUtilLight.UTIL_LIGHT, isInAndroidSdk = false)
public class ShadowGooglePlayServicesUtilLight {

  static final String UTIL_LIGHT = "com.google.android.gms.common.GooglePlayServicesUtilLight";

  @Implementation
  protected static int isGooglePlayServicesAvailable(Context context) {
    return PlayServices.status(0);
  }

  @Implementation
  protected static int isGooglePlayServicesAvailable(Context context, int minApkVersion) {
    return PlayServices.status(minApkVersion);
  }

  @Implementation
  protected static int getApkVersion(Context context) {
    return PlayServices.apkVersion();
  }
}
