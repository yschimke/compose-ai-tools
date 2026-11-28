package ee.schimke.composeai.simulator.shadows.gms;

import ee.schimke.composeai.simulator.gms.maps.GmsMapsDelegates;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services {@code CameraUpdateFactory} shadow producing simulated {@code CameraUpdate}
 * instances that {@code GmsMapsDelegates} applies in {@code moveCamera} / {@code animateCamera}.
 */
@Implements(className = ShadowCameraUpdateFactory.CAMERA_UPDATE_FACTORY, isInAndroidSdk = false)
public class ShadowCameraUpdateFactory {

  public static final String CAMERA_UPDATE_FACTORY =
      "com.google.android.gms.maps.CameraUpdateFactory";
  private static final String LAT_LNG = "com.google.android.gms.maps.model.LatLng";
  private static final String CAMERA_POSITION = "com.google.android.gms.maps.model.CameraPosition";
  private static final String LAT_LNG_BOUNDS = "com.google.android.gms.maps.model.LatLngBounds";

  @Implementation
  protected static Object newLatLng(@ClassName(LAT_LNG) Object latLng) {
    return GmsMapsDelegates.cameraUpdateNewLatLng(latLng);
  }

  @Implementation
  protected static Object newLatLngZoom(@ClassName(LAT_LNG) Object latLng, float zoom) {
    return GmsMapsDelegates.cameraUpdateNewLatLngZoom(latLng, zoom);
  }

  @Implementation
  protected static Object newCameraPosition(@ClassName(CAMERA_POSITION) Object cameraPosition) {
    return GmsMapsDelegates.cameraUpdateNewCameraPosition(cameraPosition);
  }

  @Implementation
  protected static Object newLatLngBounds(@ClassName(LAT_LNG_BOUNDS) Object bounds, int padding) {
    return GmsMapsDelegates.cameraUpdateNewLatLngBounds(bounds, 0, 0, padding);
  }

  @Implementation
  protected static Object newLatLngBounds(
      @ClassName(LAT_LNG_BOUNDS) Object bounds, int width, int height, int padding) {
    return GmsMapsDelegates.cameraUpdateNewLatLngBounds(bounds, width, height, padding);
  }

  @Implementation
  protected static Object zoomTo(float zoom) {
    return GmsMapsDelegates.cameraUpdateZoomTo(loader(), zoom);
  }

  @Implementation
  protected static Object zoomBy(float amount) {
    return GmsMapsDelegates.cameraUpdateZoomBy(loader(), amount);
  }

  @Implementation
  protected static Object zoomIn() {
    return GmsMapsDelegates.cameraUpdateZoomBy(loader(), 1f);
  }

  @Implementation
  protected static Object zoomOut() {
    return GmsMapsDelegates.cameraUpdateZoomBy(loader(), -1f);
  }

  private static ClassLoader loader() {
    return Thread.currentThread().getContextClassLoader();
  }
}
