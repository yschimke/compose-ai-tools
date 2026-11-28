package ee.schimke.composeai.simulator.shadows.gms;

import android.app.Activity;
import android.content.Context;
import ee.schimke.composeai.simulator.gms.location.GmsLocationClients;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services' {@code ActivityRecognition} entry point, answered by {@link GmsLocationClients}.
 *
 * <p>Names no Play services types directly so it can be loaded outside the sandbox.
 */
@Implements(className = ShadowActivityRecognition.ACTIVITY_RECOGNITION, isInAndroidSdk = false)
public class ShadowActivityRecognition {

  public static final String ACTIVITY_RECOGNITION =
      "com.google.android.gms.location.ActivityRecognition";

  @Implementation
  protected static Object getClient(Context context) {
    return GmsLocationClients.activityRecognitionClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getClient(Activity activity) {
    return GmsLocationClients.activityRecognitionClient(activity, loaderFor(activity));
  }

  private static ClassLoader loaderFor(Context context) {
    if (context != null && context.getClassLoader() != null) return context.getClassLoader();
    return Thread.currentThread().getContextClassLoader();
  }
}
