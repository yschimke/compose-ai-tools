package ee.schimke.composeai.simulator.shadows.firebase;

import ee.schimke.composeai.simulator.firebase.FirebaseCrashlyticsHost;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Lightweight Robolectric shadow for {@code com.google.firebase.crashlytics.FirebaseCrashlytics}
 * that captures non-fatal exceptions, logs, custom keys, and user ID into {@link
 * FirebaseCrashlyticsHost}. Inert when an app does not bundle {@code firebase-crashlytics}.
 */
@Implements(className = ShadowFirebaseCrashlytics.TARGET, isInAndroidSdk = false)
public class ShadowFirebaseCrashlytics {

  public static final String TARGET = "com.google.firebase.crashlytics.FirebaseCrashlytics";

  @Implementation
  protected void recordException(Throwable throwable) {
    FirebaseCrashlyticsHost.recordException(throwable);
  }

  @Implementation
  protected void log(String message) {
    FirebaseCrashlyticsHost.log(message);
  }

  @Implementation
  protected void setUserId(String identifier) {
    FirebaseCrashlyticsHost.setUserId(identifier);
  }

  @Implementation
  protected void setCustomKey(String key, boolean value) {
    FirebaseCrashlyticsHost.setCustomKey(key, value);
  }

  @Implementation
  protected void setCustomKey(String key, double value) {
    FirebaseCrashlyticsHost.setCustomKey(key, value);
  }

  @Implementation
  protected void setCustomKey(String key, float value) {
    FirebaseCrashlyticsHost.setCustomKey(key, value);
  }

  @Implementation
  protected void setCustomKey(String key, int value) {
    FirebaseCrashlyticsHost.setCustomKey(key, value);
  }

  @Implementation
  protected void setCustomKey(String key, long value) {
    FirebaseCrashlyticsHost.setCustomKey(key, value);
  }

  @Implementation
  protected void setCustomKey(String key, String value) {
    FirebaseCrashlyticsHost.setCustomKey(key, value);
  }

  @Implementation
  protected void setCrashlyticsCollectionEnabled(boolean enabled) {
    FirebaseCrashlyticsHost.setCollectionEnabled(enabled);
  }

  @Implementation
  protected void setCrashlyticsCollectionEnabled(Boolean enabled) {
    FirebaseCrashlyticsHost.setCollectionEnabled(enabled == null || enabled);
  }
}
