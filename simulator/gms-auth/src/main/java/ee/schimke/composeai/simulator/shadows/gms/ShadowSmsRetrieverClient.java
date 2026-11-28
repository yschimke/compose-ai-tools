package ee.schimke.composeai.simulator.shadows.gms;

import ee.schimke.composeai.simulator.gms.auth.GmsAuthClients;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Play services {@code SmsRetrieverClient} shadow answering {@code startSmsRetriever} / User Consent. */
@Implements(className = ShadowSmsRetrieverClient.SMS_RETRIEVER_CLIENT, isInAndroidSdk = false)
public class ShadowSmsRetrieverClient {

  public static final String SMS_RETRIEVER_CLIENT =
      "com.google.android.gms.auth.api.phone.SmsRetrieverClient";

  @Implementation
  protected Object startSmsRetriever() {
    return GmsAuthClients.startSmsRetriever(null, Thread.currentThread().getContextClassLoader());
  }

  @Implementation
  protected Object startSmsUserConsent(String senderPhoneNumber) {
    return GmsAuthClients.startSmsRetriever(null, Thread.currentThread().getContextClassLoader());
  }
}
