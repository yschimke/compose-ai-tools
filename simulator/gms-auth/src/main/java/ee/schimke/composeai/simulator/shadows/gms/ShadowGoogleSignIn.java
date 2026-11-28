package ee.schimke.composeai.simulator.shadows.gms;

import android.content.Context;
import android.content.Intent;
import ee.schimke.composeai.simulator.gms.auth.GmsAuthClients;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Play services legacy {@code GoogleSignIn} entry point shadow. */
@Implements(className = ShadowGoogleSignIn.GOOGLE_SIGN_IN, isInAndroidSdk = false)
public class ShadowGoogleSignIn {

  public static final String GOOGLE_SIGN_IN =
      "com.google.android.gms.auth.api.signin.GoogleSignIn";

  @Implementation
  protected static Object getLastSignedInAccount(Context context) {
    return GmsAuthClients.lastSignedInAccount(context, loaderFor(context));
  }

  @Implementation
  protected static Object getSignedInAccountFromIntent(Intent data) {
    return GmsAuthClients.signedInAccountFromIntent(
        null, Thread.currentThread().getContextClassLoader());
  }

  private static ClassLoader loaderFor(Context context) {
    if (context != null && context.getClassLoader() != null) return context.getClassLoader();
    return Thread.currentThread().getContextClassLoader();
  }
}
