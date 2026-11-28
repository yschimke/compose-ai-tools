package ee.schimke.composeai.simulator.shadows.gms;

import android.app.Activity;
import android.content.Context;
import ee.schimke.composeai.simulator.gms.location.GmsLocationClients;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services' {@code LocationServices} entry point, answered by {@link GmsLocationClients}
 * instead of binding to {@code com.google.android.gms}.
 *
 * <p>Names no Play services types directly so it can be loaded outside the sandbox.
 */
@Implements(className = ShadowLocationServices.LOCATION_SERVICES, isInAndroidSdk = false)
public class ShadowLocationServices {

  public static final String LOCATION_SERVICES = "com.google.android.gms.location.LocationServices";

  @Implementation
  protected static Object getFusedLocationProviderClient(Context context) {
    return GmsLocationClients.fusedClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getFusedLocationProviderClient(Activity activity) {
    return GmsLocationClients.fusedClient(activity, loaderFor(activity));
  }

  @Implementation
  protected static Object getGeofencingClient(Context context) {
    return GmsLocationClients.geofencingClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getGeofencingClient(Activity activity) {
    return GmsLocationClients.geofencingClient(activity, loaderFor(activity));
  }

  @Implementation
  protected static Object getSettingsClient(Context context) {
    return GmsLocationClients.settingsClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getSettingsClient(Activity activity) {
    return GmsLocationClients.settingsClient(activity, loaderFor(activity));
  }

  private static ClassLoader loaderFor(Context context) {
    if (context != null && context.getClassLoader() != null) return context.getClassLoader();
    return Thread.currentThread().getContextClassLoader();
  }
}
