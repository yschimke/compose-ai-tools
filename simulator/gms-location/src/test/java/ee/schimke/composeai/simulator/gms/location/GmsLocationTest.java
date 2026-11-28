package ee.schimke.composeai.simulator.gms.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.tasks.Task;
import ee.schimke.composeai.simulator.PlayServices;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Queue;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GmsLocationTest {

  private final Queue<Runnable> queue = new ArrayDeque<>();
  private GmsLocation location;
  private ClassLoader loader;

  @Before
  public void setUp() {
    PlayServices.set(PlayServices.State.INSTALLED);
    location = GmsLocation.installForTest(null, queue::add);
    loader = getClass().getClassLoader();
  }

  @After
  public void tearDown() {
    GmsLocation.resetCurrent();
    PlayServices.set(PlayServices.State.INSTALLED);
  }

  private void drain() {
    while (!queue.isEmpty()) queue.poll().run();
  }

  @Test
  public void fusedClientRegisteredAsSimulatedApi() {
    Object fused = GmsLocationClients.fusedClient(null, loader);
    assertTrue(GmsLocationClients.isLocationClient(fused));
    assertTrue(PlayServices.isSimulatedApi(fused));
    assertEquals(PlayServices.SUCCESS, PlayServices.apiStatus(PlayServices.isSimulatedApi(fused)));
  }

  private static Object call(Object target, String method, Class<?>[] types, Object... args)
      throws Exception {
    Method m = target.getClass().getMethod(method, types);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  @Test
  public void getLastLocationAndAvailabilityRespectState() throws Exception {
    Object fused = GmsLocationClients.fusedClient(null, loader);

    Task<?> t1 = (Task<?>) call(fused, "getLastLocation", new Class<?>[0]);
    assertFalse("Task must complete asynchronously on main executor", t1.isComplete());
    drain();
    assertTrue(t1.isSuccessful());
    assertNull(t1.getResult());

    Task<?> a1 = (Task<?>) call(fused, "getLocationAvailability", new Class<?>[0]);
    drain();
    assertTrue(a1.isSuccessful());
    assertTrue((Boolean) call(a1.getResult(), "isLocationAvailable", new Class<?>[0]));

    location.command(new JSONObject().put("available", false));
    Task<?> a2 = (Task<?>) call(fused, "getLocationAvailability", new Class<?>[0]);
    drain();
    assertFalse((Boolean) call(a2.getResult(), "isLocationAvailable", new Class<?>[0]));
  }

  @Test
  public void settingsClientSucceedsWhenEnabledAndReturnsResolvableExceptionWhenDisabled()
      throws Exception {
    Object settings = GmsLocationClients.settingsClient(null, loader);

    Task<?> okTask =
        (Task<?>) call(settings, "checkLocationSettings", new Class<?>[] {Object.class}, new Object());
    drain();
    assertTrue(okTask.isSuccessful());
    assertNotNull(okTask.getResult());

    location.command(new JSONObject().put("settingsEnabled", false));
    Task<?> failTask =
        (Task<?>) call(settings, "checkLocationSettings", new Class<?>[] {Object.class}, new Object());
    drain();
    assertFalse(failTask.isSuccessful());
    assertTrue(
        "Expected ResolvableApiException, got " + failTask.getException(),
        failTask.getException() instanceof ResolvableApiException);
    assertEquals(
        GmsLocationTasks.STATUS_RESOLUTION_REQUIRED,
        ((ApiException) failTask.getException()).getStatusCode());
  }

  @Test
  public void clientsFailWhenPlayServicesMissing() throws Exception {
    PlayServices.set(PlayServices.State.NONE);
    Object fused = GmsLocationClients.fusedClient(null, loader);
    Task<?> task = (Task<?>) call(fused, "getLastLocation", new Class<?>[0]);
    drain();
    assertFalse(task.isSuccessful());
    assertTrue(task.getException() instanceof ApiException);
    assertEquals(PlayServices.SERVICE_MISSING, ((ApiException) task.getException()).getStatusCode());
  }

  @Test
  public void activityRecognitionCommandUpdatesState() throws Exception {
    JSONObject reply =
        location.command(new JSONObject().put("activity", "WALKING").put("confidence", 92));
    assertEquals("WALKING", reply.getString("activity"));
    assertEquals(92, reply.getInt("activityConfidence"));

    JSONObject cleared = location.command(new JSONObject().put("action", "clear"));
    assertEquals("STILL", cleared.getString("activity"));
    assertEquals(100, cleared.getInt("activityConfidence"));
  }

  @Test
  public void haversineDistanceAccuracy() {
    // Same point -> 0 m
    assertEquals(0.0, GmsLocation.haversineMeters(37.422, -122.084, 37.422, -122.084), 0.01);
    // ~111.19 km per degree of latitude
    double oneDegLat = GmsLocation.haversineMeters(0.0, 0.0, 1.0, 0.0);
    assertEquals(111_195.0, oneDegLat, 200.0);
  }

  @Test
  public void installPreservesExistingStartupEngine() {
    GmsLocation before = GmsLocation.current();
    assertNotNull(before);
    GmsLocation after = GmsLocation.install(null);
    assertEquals("Session install must preserve startup-created engine", before, after);
  }

  @Test
  public void nonPublicCallbackSubclassReceivesAvailabilityUpdates() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean received =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    // Anonymous subclass of package-private test LocationCallback.
    Object callback =
        Class.forName("com.google.android.gms.location.TestLocationApi", true, loader)
            .getMethod("newAvailabilityCallback", java.util.function.Consumer.class)
            .invoke(null, (java.util.function.Consumer<Boolean>) received::set);
    location.registerLocationCallback(callback, Runnable::run);
    location.command(new JSONObject().put("available", false));
    assertFalse("Non-public LocationCallback subclass must receive availability update", received.get());
  }

  @Test
  public void passesMinIntervalEnforcesMinimumUpdateInterval() {
    assertTrue(GmsLocation.passesMinInterval(Long.MIN_VALUE, 1000L, 5000L));
    assertFalse(GmsLocation.passesMinInterval(1000L, 4999L, 5000L));
    assertFalse(GmsLocation.passesMinInterval(1000L, 5999L, 5000L));
    assertTrue(GmsLocation.passesMinInterval(1000L, 6000L, 5000L));
    assertTrue(GmsLocation.passesMinInterval(1000L, 1000L, 0L));
  }

  @Test
  public void setMockLocationFailsWhenMockModeDisabled() throws Exception {
    Object fused = GmsLocationClients.fusedClient(null, loader);
    Task<?> task =
        (Task<?>)
            call(
                fused,
                "setMockLocation",
                new Class<?>[] {android.location.Location.class},
                (Object) null);
    drain();
    assertFalse(task.isSuccessful());
    assertTrue(task.getException() instanceof ApiException);
    assertEquals(10, ((ApiException) task.getException()).getStatusCode());
  }
}
