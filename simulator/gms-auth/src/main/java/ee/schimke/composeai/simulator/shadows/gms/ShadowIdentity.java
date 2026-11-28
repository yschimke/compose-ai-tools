package ee.schimke.composeai.simulator.shadows.gms;

import android.app.Activity;
import android.content.Context;
import ee.schimke.composeai.simulator.gms.auth.GmsAuthClients;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Play services {@code Identity} entry point shadow (One Tap, Authorization, Credential Saving). */
@Implements(className = ShadowIdentity.IDENTITY, isInAndroidSdk = false)
public class ShadowIdentity {

  public static final String IDENTITY = "com.google.android.gms.auth.api.identity.Identity";

  @Implementation
  protected static Object getSignInClient(Context context) {
    return GmsAuthClients.signInClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getSignInClient(Activity activity) {
    return GmsAuthClients.signInClient(activity, loaderFor(activity));
  }

  @Implementation
  protected static Object getAuthorizationClient(Context context) {
    return GmsAuthClients.authorizationClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getAuthorizationClient(Activity activity) {
    return GmsAuthClients.authorizationClient(activity, loaderFor(activity));
  }

  @Implementation
  protected static Object getCredentialSavingClient(Context context) {
    return GmsAuthClients.credentialSavingClient(context, loaderFor(context));
  }

  @Implementation
  protected static Object getCredentialSavingClient(Activity activity) {
    return GmsAuthClients.credentialSavingClient(activity, loaderFor(activity));
  }

  private static ClassLoader loaderFor(Context context) {
    if (context != null && context.getClassLoader() != null) return context.getClassLoader();
    return Thread.currentThread().getContextClassLoader();
  }
}
