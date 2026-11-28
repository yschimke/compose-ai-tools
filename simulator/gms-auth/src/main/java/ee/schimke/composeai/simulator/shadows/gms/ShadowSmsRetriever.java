package ee.schimke.composeai.simulator.shadows.gms;

import android.app.Activity;
import android.content.Context;
import ee.schimke.composeai.simulator.gms.auth.SimulatedSmsRetrieverClient;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Play services {@code SmsRetriever} factory shadow returning a concrete {@link
 * SimulatedSmsRetrieverClient} so abstract methods on {@code SmsRetrieverClient} are intercepted.
 */
@Implements(className = ShadowSmsRetriever.SMS_RETRIEVER, isInAndroidSdk = false)
public class ShadowSmsRetriever {

  public static final String SMS_RETRIEVER = "com.google.android.gms.auth.api.phone.SmsRetriever";

  @Implementation
  protected static Object getClient(Context context) {
    return new SimulatedSmsRetrieverClient(context);
  }

  @Implementation
  protected static Object getClient(Activity activity) {
    return new SimulatedSmsRetrieverClient(activity);
  }
}
