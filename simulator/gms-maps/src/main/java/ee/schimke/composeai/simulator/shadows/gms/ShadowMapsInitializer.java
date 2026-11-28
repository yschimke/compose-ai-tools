package ee.schimke.composeai.simulator.shadows.gms;

import android.content.Context;
import ee.schimke.composeai.simulator.gms.maps.GmsMapsDelegates;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services {@code MapsInitializer} shadow so apps and {@code maps-compose} succeed without
 * dynamically loading native/APK classes from {@code com.google.android.gms}.
 */
@Implements(className = ShadowMapsInitializer.MAPS_INITIALIZER, isInAndroidSdk = false)
public class ShadowMapsInitializer {

  public static final String MAPS_INITIALIZER = "com.google.android.gms.maps.MapsInitializer";
  private static final String RENDERER = "com.google.android.gms.maps.MapsInitializer$Renderer";
  private static final String CALLBACK =
      "com.google.android.gms.maps.OnMapsSdkInitializedCallback";

  @Implementation
  protected static int initialize(Context context) {
    return GmsMapsDelegates.initialize(context, null);
  }

  @Implementation
  protected static int initialize(
      Context context,
      @ClassName(RENDERER) Object preferredRenderer,
      @ClassName(CALLBACK) Object callback) {
    return GmsMapsDelegates.initialize(context, callback);
  }
}
