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
    if (available) return completed(loader, "forResult", Object.class, null);
    return failedTask(loader, apis, statuses, AVAILABILITY_EXCEPTION);
  }

  static final String AVAILABILITY_EXCEPTION =
      "com.google.android.gms.common.api.AvailabilityException";

  /**
   * A failed {@code Task}: the {@code AvailabilityException} the app expects or, if this Play
   * services build has no constructor the simulator can use, an {@code ApiException} with the first
   * failing status. It never throws, as the caller is promised a failed {@code Task}.
   */
  static Object failedTask(
      ClassLoader loader, List<Object> apis, int[] statuses, String exceptionClass) {
    Exception failure;
    try {
      failure = availabilityException(loader, apis, statuses, exceptionClass);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      failure = apiException(loader, firstFailure(statuses), e);
    }
    return completed(loader, "forException", Exception.class, failure);
  }

  private static Object completed(
      ClassLoader loader, String method, Class<?> parameter, Object value) {
    try {
      return Class.forName("com.google.android.gms.tasks.Tasks", false, loader)
          .getMethod(method, parameter)
          .invoke(null, value);
    } catch (ReflectiveOperationException e) {
      // Without Play services' Tasks there is no Task to return at all.
      throw new IllegalStateException("Play services' Tasks." + method, e);
    }
  }

  private static int firstFailure(int[] statuses) {
    for (int status : statuses) if (status != PlayServices.SUCCESS) return status;
    return PlayServices.API_UNAVAILABLE;
  }

  /** {@code new ApiException(new Status(status))}, or a plain exception if even that fails. */
  private static Exception apiException(ClassLoader loader, int status, Throwable cause) {
    try {
      Class<?> statusClass =
          Class.forName("com.google.android.gms.common.api.Status", false, loader);
      Constructor<?> newStatus = statusClass.getDeclaredConstructor(int.class);
      newStatus.setAccessible(true);
      Constructor<?> newException =
          Class.forName("com.google.android.gms.common.api.ApiException", false, loader)
              .getDeclaredConstructor(statusClass);
      newException.setAccessible(true);
      return (Exception) newException.newInstance(newStatus.newInstance(status));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      IllegalStateException fallback =
          new IllegalStateException("Play services API unavailable: status " + status, cause);
      fallback.addSuppressed(e);
      return fallback;
    }
  }

  private static boolean simulated(Object api) {
    // DataLayerClients names the app's Play services Wearable classes: loaded only when the launch
    // simulates the Data Layer.
    return DataLayerLink.enabled() && DataLayerClients.simulated(api);
  }

  /**
   * {@code new AvailabilityException(map)}, each API's key to its {@code ConnectionResult}. The map
   * is whatever its one-argument constructor takes: {@code androidx.collection.ArrayMap} in every
   * Play services release checked (a public constructor, though the reference omits it), matched
   * here as any {@link Map} or any map with a no-argument constructor and {@code put}, such as
   * {@code SimpleArrayMap}, and reached whatever its visibility.
   */
  private static Exception availabilityException(
      ClassLoader loader, List<Object> apis, int[] statuses, String exceptionClass)
      throws ReflectiveOperationException {
    Class<?> exception = Class.forName(exceptionClass, false, loader);
    Constructor<?> constructor = null;
    Method put = null;
    for (Constructor<?> candidate : exception.getDeclaredConstructors()) {
      if (candidate.getParameterCount() != 1) continue;
      Class<?> map = candidate.getParameterTypes()[0];
      Method candidatePut = mapPut(map);
      if (candidatePut == null) continue;
      constructor = candidate;
      put = candidatePut;
      if (Map.class.isAssignableFrom(map)) break;
    }
    if (constructor == null) throw new NoSuchMethodException(exceptionClass + "(Map)");
    Constructor<?> newMap = constructor.getParameterTypes()[0].getDeclaredConstructor();
    newMap.setAccessible(true);
    Object results = newMap.newInstance();
    Constructor<?> connectionResult =
        Class.forName("com.google.android.gms.common.ConnectionResult", false, loader)
            .getDeclaredConstructor(int.class);
    connectionResult.setAccessible(true);
    for (int i = 0; i < statuses.length; i++) {
      Object api = apis.get(i);
      Method key = api.getClass().getMethod("getApiKey");
      key.setAccessible(true);
      put.invoke(results, key.invoke(api), connectionResult.newInstance(statuses[i]));
    }
    constructor.setAccessible(true);
    return (Exception) constructor.newInstance(results);
  }

  /** {@code put(key, value)} of a concrete map type, or null if it is not one. */
  private static Method mapPut(Class<?> map) {
    if (map.isInterface() || java.lang.reflect.Modifier.isAbstract(map.getModifiers())) return null;
    try {
      map.getDeclaredConstructor();
      Method put = map.getMethod("put", Object.class, Object.class);
      put.setAccessible(true);
      return put;
    } catch (NoSuchMethodException | RuntimeException e) {
      return null;
    }
  }
}
