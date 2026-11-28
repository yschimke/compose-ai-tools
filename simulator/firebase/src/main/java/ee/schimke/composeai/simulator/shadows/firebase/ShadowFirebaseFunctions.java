package ee.schimke.composeai.simulator.shadows.firebase;

import ee.schimke.composeai.simulator.firebase.FirebaseEmulatorWiring;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Lightweight Robolectric shadow for {@code com.google.firebase.functions.FirebaseFunctions} that
 * wires regional and custom-domain Functions clients (e.g. {@code
 * FirebaseFunctions.getInstance("europe-west1")}) to the configured Firebase Functions Emulator as
 * they are obtained. Inert when an app does not bundle {@code firebase-functions} or when no
 * Functions emulator is configured.
 */
@Implements(className = ShadowFirebaseFunctions.TARGET, isInAndroidSdk = false)
public class ShadowFirebaseFunctions {

  public static final String TARGET = "com.google.firebase.functions.FirebaseFunctions";
  private static final String FIREBASE_APP = "com.google.firebase.FirebaseApp";

  @Implementation
  protected static Object getInstance() {
    Object instance = invokeReal();
    FirebaseEmulatorWiring.wireFunctionsInstance(instance);
    return instance;
  }

  @Implementation
  protected static Object getInstance(String regionOrCustomDomain) {
    Object instance = invokeReal(ClassParameter.from(String.class, regionOrCustomDomain));
    FirebaseEmulatorWiring.wireFunctionsInstance(instance);
    return instance;
  }

  @Implementation
  protected static Object getInstance(@ClassName(FIREBASE_APP) Object app) {
    Object instance = invokeRealWithApp(app, null, false);
    FirebaseEmulatorWiring.wireFunctionsInstance(instance);
    return instance;
  }

  @Implementation
  protected static Object getInstance(
      @ClassName(FIREBASE_APP) Object app, String regionOrCustomDomain) {
    Object instance = invokeRealWithApp(app, regionOrCustomDomain, true);
    FirebaseEmulatorWiring.wireFunctionsInstance(instance);
    return instance;
  }

  private static Object invokeReal(ClassParameter<?>... params) {
    try {
      Class<?> cls = Class.forName(TARGET, false, ShadowFirebaseFunctions.class.getClassLoader());
      return Shadow.directlyOn(cls, "getInstance", params);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object invokeRealWithApp(
      Object app, String regionOrCustomDomain, boolean includeRegion) {
    try {
      ClassLoader loader = ShadowFirebaseFunctions.class.getClassLoader();
      Class<?> cls = Class.forName(TARGET, false, loader);
      Class<?> appClass = Class.forName(FIREBASE_APP, false, loader);
      ClassParameter<?> appParam = new ClassParameter(appClass, app);
      if (includeRegion) {
        return Shadow.directlyOn(
            cls, "getInstance", appParam, ClassParameter.from(String.class, regionOrCustomDomain));
      }
      return Shadow.directlyOn(cls, "getInstance", appParam);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
