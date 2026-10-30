package ee.schimke.composeai.simulator;

import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * {@code GoogleApiAvailability.checkApiAvailability} for the paired simulator's Wearable clients:
 * available, as a device with Play services would answer. Horologist's {@code
 * WearableApiAvailability.isAvailable} (and with it {@code DataLayerAppHelper.isAvailable}) asks it
 * before using the Data Layer; Play services' own answer needs a connection to {@code
 * com.google.android.gms}, which the simulator does not have.
 *
 * <p>The {@code HasApiKey} overload, which Javac picks only for a caller holding no {@code
 * GoogleApi}, is left alone, as is any other API: those still get Play services' own answer. Like {@link ShadowWearable}, the class
 * names no Play services type: it is loaded outside the sandbox too.
 */
@Implements(className = ShadowGoogleApiAvailability.AVAILABILITY, isInAndroidSdk = false)
public class ShadowGoogleApiAvailability {

  static final String AVAILABILITY = "com.google.android.gms.common.GoogleApiAvailability";
  private static final String GOOGLE_API = "com.google.android.gms.common.api.GoogleApi";
  private static final String GOOGLE_APIS = "[Lcom.google.android.gms.common.api.GoogleApi;";

  @RealObject private Object realObject;

  // Robolectric matches a shadow parameter by @ClassName only when it is declared Object, so the
  // varargs array is an Object here too.
  @Implementation
  protected Object checkApiAvailability(
      @ClassName(GOOGLE_API) Object api, @ClassName(GOOGLE_APIS) Object more) {
    Object[] rest = (Object[]) more;
    if (DataLayerClients.allSimulated(api, rest)) return DataLayerClients.available();
    return real(GOOGLE_API, GOOGLE_APIS, api, rest);
  }

  /** Play services' own {@code checkApiAvailability}, for an API the simulator does not answer. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private Object real(String type, String arrayType, Object api, Object[] more) {
    try {
      ClassLoader loader = realObject.getClass().getClassLoader();
      Class first = Class.forName(type, false, loader);
      Class rest = Class.forName(arrayType, false, loader);
      return Shadow.directlyOn(
          realObject,
          AVAILABILITY,
          "checkApiAvailability",
          ClassParameter.from(first, api),
          ClassParameter.from(rest, more));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
