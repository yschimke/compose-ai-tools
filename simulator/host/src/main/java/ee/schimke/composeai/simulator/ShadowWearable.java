package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.content.Context;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services' {@code Wearable} entry point, answered by the paired simulator's {@link
 * DataLayer} instead of a binder to {@code com.google.android.gms}, which the simulator does not
 * have. Every {@code Wearable.getXClient} the app calls returns a client of the data layer the
 * CLI's hub connects to the other simulator.
 *
 * <p>Added to the shadow map, and {@code Wearable} instrumented, by {@link SimulatorSandbox} only
 * when the launch is paired. The class itself names no Play services type: it is loaded outside
 * the sandbox too, where the app's classes are not, so its methods return {@code Object} (the
 * instrumented caller casts) and name the options type through {@link ClassName}.
 */
@Implements(className = ShadowWearable.WEARABLE, isInAndroidSdk = false)
public class ShadowWearable {

  static final String WEARABLE = "com.google.android.gms.wearable.Wearable";
  private static final String OPTIONS = "com.google.android.gms.wearable.Wearable$WearableOptions";

  @Implementation
  protected static Object getDataClient(Context context) {
    return DataLayer.get().dataClient(context);
  }

  @Implementation
  protected static Object getDataClient(Context context, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().dataClient(context);
  }

  @Implementation
  protected static Object getDataClient(Activity activity) {
    return DataLayer.get().dataClient(activity);
  }

  @Implementation
  protected static Object getDataClient(Activity activity, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().dataClient(activity);
  }

  @Implementation
  protected static Object getMessageClient(Context context) {
    return DataLayer.get().messageClient(context);
  }

  @Implementation
  protected static Object getMessageClient(Context context, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().messageClient(context);
  }

  @Implementation
  protected static Object getMessageClient(Activity activity) {
    return DataLayer.get().messageClient(activity);
  }

  @Implementation
  protected static Object getMessageClient(
      Activity activity, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().messageClient(activity);
  }

  @Implementation
  protected static Object getNodeClient(Context context) {
    return DataLayer.get().nodeClient(context);
  }

  @Implementation
  protected static Object getNodeClient(Context context, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().nodeClient(context);
  }

  @Implementation
  protected static Object getNodeClient(Activity activity) {
    return DataLayer.get().nodeClient(activity);
  }

  @Implementation
  protected static Object getNodeClient(Activity activity, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().nodeClient(activity);
  }

  @Implementation
  protected static Object getCapabilityClient(Context context) {
    return DataLayer.get().capabilityClient(context);
  }

  @Implementation
  protected static Object getCapabilityClient(
      Context context, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().capabilityClient(context);
  }

  @Implementation
  protected static Object getCapabilityClient(Activity activity) {
    return DataLayer.get().capabilityClient(activity);
  }

  @Implementation
  protected static Object getCapabilityClient(
      Activity activity, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().capabilityClient(activity);
  }
  @Implementation
  protected static Object getChannelClient(Context context) {
    return DataLayer.get().channelClient(context);
  }

  @Implementation
  protected static Object getChannelClient(Context context, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().channelClient(context);
  }

  @Implementation
  protected static Object getChannelClient(Activity activity) {
    return DataLayer.get().channelClient(activity);
  }

  @Implementation
  protected static Object getChannelClient(
      Activity activity, @ClassName(OPTIONS) Object options) {
    return DataLayer.get().channelClient(activity);
  }
}
