package ee.schimke.composeai.simulator.shadows.firebase;

import ee.schimke.composeai.simulator.firebase.FirebaseRemoteConfigHost;
import java.util.LinkedHashSet;
import java.util.Set;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Lightweight Robolectric shadow for {@code com.google.firebase.remoteconfig.FirebaseRemoteConfig}
 * that applies simulator overrides from {@link FirebaseRemoteConfigHost} above activated, fetched,
 * and default values, delegating non-overridden keys to the real SDK instance. Inert when an app
 * does not bundle {@code firebase-config}.
 */
@Implements(className = ShadowFirebaseRemoteConfig.TARGET, isInAndroidSdk = false)
public class ShadowFirebaseRemoteConfig {

  public static final String TARGET = "com.google.firebase.remoteconfig.FirebaseRemoteConfig";

  @RealObject private Object realObject;

  @Implementation
  protected String getString(String key) {
    if (FirebaseRemoteConfigHost.hasOverride(key)) {
      return FirebaseRemoteConfigHost.getStringOverride(key);
    }
    return invokeReal("getString", ClassParameter.from(String.class, key));
  }

  @Implementation
  protected boolean getBoolean(String key) {
    if (FirebaseRemoteConfigHost.hasOverride(key)) {
      return FirebaseRemoteConfigHost.getBooleanOverride(key);
    }
    return invokeReal("getBoolean", ClassParameter.from(String.class, key));
  }

  @Implementation
  protected long getLong(String key) {
    if (FirebaseRemoteConfigHost.hasOverride(key)) {
      return FirebaseRemoteConfigHost.getLongOverride(key);
    }
    return invokeReal("getLong", ClassParameter.from(String.class, key));
  }

  @Implementation
  protected double getDouble(String key) {
    if (FirebaseRemoteConfigHost.hasOverride(key)) {
      return FirebaseRemoteConfigHost.getDoubleOverride(key);
    }
    return invokeReal("getDouble", ClassParameter.from(String.class, key));
  }

  @Implementation
  protected Set<String> getKeysByPrefix(String prefix) {
    Set<String> combined = new LinkedHashSet<>();
    Set<String> real = invokeReal("getKeysByPrefix", ClassParameter.from(String.class, prefix));
    if (real != null) combined.addAll(real);
    combined.addAll(FirebaseRemoteConfigHost.keysByPrefix(prefix));
    return combined;
  }

  @SuppressWarnings("unchecked")
  private <T> T invokeReal(String method, ClassParameter<?>... params) {
    return (T) Shadow.directlyOn(realObject, realObject.getClass().getName(), method, params);
  }
}
