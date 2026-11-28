package ee.schimke.composeai.simulator.shadows.gms;

import android.os.Bundle;
import android.view.ViewGroup;
import ee.schimke.composeai.simulator.gms.maps.GmsMapsDelegates;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/**
 * Play services {@code MapView} shadow that mounts {@code SimulatedMapCanvasView} and delivers a
 * simulated {@code GoogleMap} to {@code getMapAsync}.
 */
@Implements(className = ShadowMapView.MAP_VIEW, isInAndroidSdk = false)
public class ShadowMapView {

  public static final String MAP_VIEW = "com.google.android.gms.maps.MapView";
  private static final String ON_MAP_READY = "com.google.android.gms.maps.OnMapReadyCallback";

  @RealObject private ViewGroup realMapView;

  @Implementation
  protected void onCreate(Bundle savedInstanceState) {
    GmsMapsDelegates.attachCanvas(realMapView);
  }

  @Implementation
  protected void onStart() {}

  @Implementation
  protected void onResume() {
    GmsMapsDelegates.attachCanvas(realMapView);
  }

  @Implementation
  protected void onPause() {}

  @Implementation
  protected void onStop() {}

  @Implementation
  protected void onDestroy() {}

  @Implementation
  protected void onLowMemory() {}

  @Implementation
  protected void onSaveInstanceState(Bundle outState) {}

  @Implementation
  protected void getMapAsync(@ClassName(ON_MAP_READY) Object callback) {
    GmsMapsDelegates.attachCanvas(realMapView);
    GmsMapsDelegates.getMapAsync(realMapView, callback);
  }
}
