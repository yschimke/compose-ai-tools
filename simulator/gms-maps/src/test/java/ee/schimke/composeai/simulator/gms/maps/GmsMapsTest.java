package ee.schimke.composeai.simulator.gms.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapsInitializer;
import ee.schimke.composeai.simulator.PlayServices;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GmsMapsTest {

  private GmsMaps maps;

  @Before
  public void setUp() {
    PlayServices.set(PlayServices.State.INSTALLED);
    maps = GmsMaps.installForTest(Runnable::run);
  }

  @After
  public void tearDown() {
    GmsMaps.resetCurrent();
    PlayServices.set(PlayServices.State.INSTALLED);
  }

  @Test
  public void mercatorProjectionRoundTripsAccurately() {
    maps.setCamera(51.5074, -0.1278, 14f, 0f, 0f);
    float[] center = maps.latLngToScreen(51.5074, -0.1278, 400, 600);
    assertEquals(200f, center[0], 0.1f);
    assertEquals(300f, center[1], 0.1f);

    double[] roundTrip = maps.screenToLatLng(240f, 260f, 400, 600);
    float[] projected = maps.latLngToScreen(roundTrip[0], roundTrip[1], 400, 600);
    assertEquals(240f, projected[0], 0.2f);
    assertEquals(260f, projected[1], 0.2f);
  }

  @Test
  public void mapsInitializerRespectsPlayServicesAndInvokesCallback() {
    AtomicReference<MapsInitializer.Renderer> received = new AtomicReference<>();
    Object callback =
        new Object() {
          @SuppressWarnings("unused")
          public void onMapsSdkInitialized(MapsInitializer.Renderer renderer) {
            received.set(renderer);
          }
        };
    assertEquals(PlayServices.SUCCESS, GmsMapsDelegates.initialize(null, callback));
    assertEquals(MapsInitializer.Renderer.LATEST, received.get());

    PlayServices.set(PlayServices.State.NONE);
    assertEquals(PlayServices.SERVICE_MISSING, GmsMapsDelegates.initialize(null, null));
  }

  @Test
  public void googleMapDelegateReflectsCameraAndMapType() {
    GoogleMap map =
        (GoogleMap) GmsMapsDelegates.createGoogleMap(maps, getClass().getClassLoader());
    assertNotNull(map);
    maps.setCamera(48.8566, 2.3522, 13f, 0f, 0f);
    assertEquals(48.8566, map.getCameraPosition().target.latitude, 0.0001);
    assertEquals(13f, map.getCameraPosition().zoom, 0.01f);

    map.setMapType(GmsMaps.MAP_TYPE_SATELLITE);
    assertEquals(GmsMaps.MAP_TYPE_SATELLITE, map.getMapType());
  }

  @Test
  public void mapsCommandUpdatesCameraOverlaysAndClicks() throws Exception {
    GmsMaps.MarkerRecord marker = maps.addMarker(37.422, -122.084, "HQ", "Mountain View");
    maps.addPolyline(List.of(new double[] {37.42, -122.08}, new double[] {37.43, -122.09}), 0, 4f);

    AtomicReference<double[]> clicked = new AtomicReference<>();
    maps.setMapClickBridge((lat, lng) -> clicked.set(new double[] {lat, lng}));

    JSONObject moved =
        maps.command(
            new JSONObject()
                .put("lat", 37.7749)
                .put("lng", -122.4194)
                .put("zoom", 15)
                .put("mapType", "terrain"));
    assertEquals("terrain", moved.getString("mapType"));
    assertEquals(1, moved.getJSONArray("markers").length());
    assertEquals(1, moved.getInt("polylineCount"));

    maps.command(new JSONObject().put("action", "tap").put("lat", 37.77).put("lng", -122.42));
    assertNotNull(clicked.get());
    assertEquals(37.77, clicked.get()[0], 0.001);

    JSONObject tapMarker =
        maps.command(new JSONObject().put("action", "tapMarker").put("id", marker.id));
    assertTrue(tapMarker.getBoolean("markerTapped"));

    JSONObject cleared = maps.command(new JSONObject().put("action", "clear"));
    assertEquals(0, cleared.getJSONArray("markers").length());
    assertEquals(0, cleared.getInt("polylineCount"));
  }

  @Test
  public void appDrivenCameraUpdatesAndMarkerClickListenerWork() {
    GoogleMap map =
        (GoogleMap) GmsMapsDelegates.createGoogleMap(maps, getClass().getClassLoader());
    assertNotNull(map);

    com.google.android.gms.maps.CameraUpdate update =
        (com.google.android.gms.maps.CameraUpdate)
            GmsMapsDelegates.cameraUpdateNewLatLngZoom(
                new com.google.android.gms.maps.model.LatLng(35.6762, 139.6503), 16f);
    assertNotNull(update);
    map.delegate.moveCamera(update.remoteObject);
    assertEquals(35.6762, map.getCameraPosition().target.latitude, 0.0001);
    assertEquals(16f, map.getCameraPosition().zoom, 0.01f);

    com.google.android.gms.maps.internal.IGoogleMapDelegate.IMarkerDelegate marker =
        map.delegate.addMarker(
            new com.google.android.gms.maps.model.MarkerOptions(
                new com.google.android.gms.maps.model.LatLng(35.6762, 139.6503), "Tokyo", "JP"));
    AtomicReference<String> clickedTitle = new AtomicReference<>();
    map.delegate.setOnMarkerClickListener(
        m -> {
          clickedTitle.set(m.getTitle());
          return true;
        });

    assertTrue(maps.dispatchMarkerClick(marker.getId()));
    assertEquals("Tokyo", clickedTitle.get());
  }

  @Test
  public void separateMapEnginesIsolateState() {
    GmsMaps second = GmsMaps.newInstance();
    maps.setMapType(GmsMaps.MAP_TYPE_SATELLITE);
    second.setMapType(GmsMaps.MAP_TYPE_TERRAIN);
    assertEquals(GmsMaps.MAP_TYPE_SATELLITE, maps.mapType());
    assertEquals(GmsMaps.MAP_TYPE_TERRAIN, second.mapType());
  }

  @Test
  public void hiddenMarkerPreservesInitialVisibilityAndBoundsFitZoom() {
    GoogleMap map =
        (GoogleMap) GmsMapsDelegates.createGoogleMap(maps, getClass().getClassLoader());
    map.delegate.addMarker(
        new com.google.android.gms.maps.model.MarkerOptions(
            new com.google.android.gms.maps.model.LatLng(37.42, -122.08), "Hidden", "", false));
    assertEquals(1, maps.markersSnapshot().size());
    assertEquals(false, maps.markersSnapshot().get(0).visible);

    // Wide country-scale bounds should zoom way out from default 12f.
    maps.fitBounds(32.0, -124.0, 42.0, -114.0, 800, 800, 40);
    assertEquals(37.0, maps.cameraLat(), 0.01);
    assertEquals(-119.0, maps.cameraLng(), 0.01);
    assertTrue("Expected zoom < 7 for 10-degree span, got " + maps.cameraZoom(), maps.cameraZoom() < 7f);
  }
}
