package ee.schimke.composeai.simulator.shadows.firebase;

import android.os.Bundle;
import ee.schimke.composeai.simulator.firebase.FirebaseAnalyticsHost;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Lightweight Robolectric shadow for {@code com.google.firebase.analytics.FirebaseAnalytics} that
 * captures events, user properties, and user ID into {@link FirebaseAnalyticsHost} so agents and
 * tests can assert on analytics calls without a GMS measurement backend. Inert when an app does not
 * bundle {@code firebase-analytics}.
 */
@Implements(className = ShadowFirebaseAnalytics.TARGET, isInAndroidSdk = false)
public class ShadowFirebaseAnalytics {

  public static final String TARGET = "com.google.firebase.analytics.FirebaseAnalytics";

  @Implementation
  protected void logEvent(String name, Bundle params) {
    FirebaseAnalyticsHost.recordEvent(name, params);
  }

  @Implementation
  protected void setUserProperty(String name, String value) {
    FirebaseAnalyticsHost.setUserProperty(name, value);
  }

  @Implementation
  protected void setUserId(String id) {
    FirebaseAnalyticsHost.setUserId(id);
  }

  @Implementation
  protected void setAnalyticsCollectionEnabled(boolean enabled) {
    FirebaseAnalyticsHost.setCollectionEnabled(enabled);
  }
}
