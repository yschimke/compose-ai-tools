package ee.schimke.composeai.simulator.shadows.gms;

import ee.schimke.composeai.simulator.gms.auth.GmsAuthClients;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Play services {@code Fido2ApiClient} shadow for platform authenticator checks. */
@Implements(className = ShadowFido2ApiClient.FIDO2_API_CLIENT, isInAndroidSdk = false)
public class ShadowFido2ApiClient {

  public static final String FIDO2_API_CLIENT = "com.google.android.gms.fido.fido2.Fido2ApiClient";

  @Implementation
  protected Object isUserVerifyingPlatformAuthenticatorAvailable() {
    return GmsAuthClients.fidoPlatformAuthenticatorAvailable(
        null, Thread.currentThread().getContextClassLoader());
  }
}
