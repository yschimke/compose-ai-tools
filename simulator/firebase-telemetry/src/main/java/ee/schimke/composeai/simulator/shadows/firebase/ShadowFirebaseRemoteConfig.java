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
  protected Object getValue(String key) {
    if (FirebaseRemoteConfigHost.hasOverride(key)) {
      Object proxy = createRemoteConfigValueProxy(key);
      if (proxy != null) return proxy;
    }
    return invokeReal("getValue", ClassParameter.from(String.class, key));
  }

  @Implementation
  protected java.util.Map<String, Object> getAll() {
    java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
    java.util.Map<String, Object> real = invokeReal("getAll");
    if (real != null) merged.putAll(real);
    for (String key : FirebaseRemoteConfigHost.keysByPrefix("")) {
      Object proxy = createRemoteConfigValueProxy(key);
      if (proxy != null) merged.put(key, proxy);
    }
    return merged;
  }

  @Implementation
  protected Set<String> getKeysByPrefix(String prefix) {
    Set<String> combined = new LinkedHashSet<>();
    Set<String> real = invokeReal("getKeysByPrefix", ClassParameter.from(String.class, prefix));
    if (real != null) combined.addAll(real);
    combined.addAll(FirebaseRemoteConfigHost.keysByPrefix(prefix));
    return combined;
  }

  private Object createRemoteConfigValueProxy(String key) {
    try {
      ClassLoader loader = realObject.getClass().getClassLoader();
      Class<?> valueInterface =
          Class.forName("com.google.firebase.remoteconfig.FirebaseRemoteConfigValue", false, loader);
      return java.lang.reflect.Proxy.newProxyInstance(
          loader,
          new Class<?>[] {valueInterface},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "asString":
                return FirebaseRemoteConfigHost.getStringOverride(key);
              case "asBoolean":
                return FirebaseRemoteConfigHost.getBooleanOverride(key);
              case "asLong":
                return FirebaseRemoteConfigHost.getLongOverride(key);
              case "asDouble":
                return FirebaseRemoteConfigHost.getDoubleOverride(key);
              case "asByteArray":
                return FirebaseRemoteConfigHost.getStringOverride(key)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
              case "getSource":
                return 2; // FirebaseRemoteConfig.VALUE_SOURCE_REMOTE
              default:
                return null;
            }
          });
    } catch (Throwable ignored) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private <T> T invokeReal(String method, ClassParameter<?>... params) {
    return (T) Shadow.directlyOn(realObject, realObject.getClass().getName(), method, params);
  }
}
