package ee.schimke.composeai.simulator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * {@code GoogleApiAvailability.checkApiAvailability}, answered at once from {@link PlayServices}:
 * success for the APIs the simulator implements (the Wearable clients of {@link DataLayerClients},
 * under {@code --pair} or {@code --data-layer agent}), {@code API_UNAVAILABLE} for any other, or
 * Play services' own status for every API when {@code --play-services} makes it missing or broken.
 * Play services' implementation would connect each API to {@code com.google.android.gms}, which the
 * simulator does not have. Horologist's {@code WearableApiAvailability.isAvailable} (and with it
 * {@code DataLayerAppHelper.isAvailable}) asks it before using the Data Layer.
 *
 * <p>The {@code GoogleApi} overload and the {@code Executor} one, which the {@code HasApiKey}
 * overload (Javac's pick for a caller holding no {@code GoogleApi}, such as a Kotlin {@code
 * HasApiKey<*>}) calls in every release that has it; the two {@code (Object, Object)} shadows could
 * not both be declared. A failure is Play services' own {@code AvailabilityException}, with a {@code
 * ConnectionResult} for every API asked about, so {@code getConnectionResult(api)} works.
 *
 * <p>Like {@link ShadowWearable}, the class names no Play services type: it is loaded outside the
 * sandbox too. Robolectric matches a shadow parameter by {@link ClassName} only when it is declared
 * {@code Object}, so the varargs arrays are {@code Object} here too, and the app's classes are
 * reached by reflection through the app's class loader.
 */
@Implements(className = ShadowGoogleApiAvailability.AVAILABILITY, isInAndroidSdk = false)
public class ShadowGoogleApiAvailability {

  static final String AVAILABILITY = "com.google.android.gms.common.GoogleApiAvailability";
  private static final String GOOGLE_API = "com.google.android.gms.common.api.GoogleApi";
  private static final String GOOGLE_APIS = "[Lcom.google.android.gms.common.api.GoogleApi;";
  private static final String HAS_API_KEY = "com.google.android.gms.common.api.HasApiKey";
  private static final String HAS_API_KEYS = "[Lcom.google.android.gms.common.api.HasApiKey;";

  @Implementation
  protected Object checkApiAvailability(
      @ClassName(GOOGLE_API) Object api, @ClassName(GOOGLE_APIS) Object more) {
    return answer(api, (Object[]) more);
  }

  @Implementation
  protected Object checkApiAvailability(
      java.util.concurrent.Executor executor,
      @ClassName(HAS_API_KEY) Object api,
      @ClassName(HAS_API_KEYS) Object more) {
    return answer(api, (Object[]) more);
  }

  /** A completed {@code Task<Void>}: success, or an {@code AvailabilityException}. */
  static Object answer(Object first, Object[] more) {
    List<Object> apis = new ArrayList<>();
    apis.add(first);
    if (more != null) for (Object api : more) apis.add(api);
    int[] statuses = new int[apis.size()];
    boolean available = true;
    for (int i = 0; i < statuses.length; i++) {
      statuses[i] = PlayServices.apiStatus(simulated(apis.get(i)));
      if (statuses[i] != PlayServices.SUCCESS) available = false;
    }
    ClassLoader loader = first.getClass().getClassLoader();
    try {
      Class<?> tasks = Class.forName("com.google.android.gms.tasks.Tasks", false, loader);
      if (available) return tasks.getMethod("forResult", Object.class).invoke(null, (Object) null);
      Exception failure = availabilityException(loader, apis, statuses);
      return tasks.getMethod("forException", Exception.class).invoke(null, failure);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Play services' Tasks or AvailabilityException", e);
    }
  }

  private static boolean simulated(Object api) {
    // DataLayerClients names the app's Play services Wearable classes: loaded only when the launch
    // simulates the Data Layer.
    return DataLayerLink.enabled() && DataLayerClients.simulated(api);
  }

  /**
   * {@code new AvailabilityException(map)}, each API's key to its {@code ConnectionResult}. The map
   * is whatever its one-argument constructor takes ({@code androidx.collection.ArrayMap} today).
   */
  @SuppressWarnings("unchecked")
  private static Exception availabilityException(
      ClassLoader loader, List<Object> apis, int[] statuses) throws ReflectiveOperationException {
    Class<?> exception =
        Class.forName("com.google.android.gms.common.api.AvailabilityException", false, loader);
    Constructor<?> constructor = null;
    for (Constructor<?> candidate : exception.getConstructors()) {
      if (candidate.getParameterCount() == 1
          && Map.class.isAssignableFrom(candidate.getParameterTypes()[0])) {
        constructor = candidate;
      }
    }
    if (constructor == null) throw new NoSuchMethodException("AvailabilityException(Map)");
    Map<Object, Object> results =
        (Map<Object, Object>) constructor.getParameterTypes()[0].getConstructor().newInstance();
    Constructor<?> connectionResult =
        Class.forName("com.google.android.gms.common.ConnectionResult", false, loader)
            .getConstructor(int.class);
    for (int i = 0; i < statuses.length; i++) {
      Object api = apis.get(i);
      Method key = api.getClass().getMethod("getApiKey");
      key.setAccessible(true);
      results.put(key.invoke(api), connectionResult.newInstance(statuses[i]));
    }
    return (Exception) constructor.newInstance(results);
  }
}
