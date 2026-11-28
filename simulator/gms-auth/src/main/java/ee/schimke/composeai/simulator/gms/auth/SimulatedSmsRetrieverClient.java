package ee.schimke.composeai.simulator.gms.auth;

import android.app.Activity;
import android.content.Context;
import com.google.android.gms.auth.api.phone.SmsRetrieverClient;
import com.google.android.gms.tasks.Task;

/**
 * Concrete {@link SmsRetrieverClient} subclass returned by {@code ShadowSmsRetriever.getClient}.
 *
 * <p>Compiled against {@code gmsStubs}; loaded inside the sandbox against the app's own {@code
 * play-services-auth-api-phone} classes.
 */
public final class SimulatedSmsRetrieverClient extends SmsRetrieverClient {

  private final Context context;

  public SimulatedSmsRetrieverClient(Context context) {
    super(context);
    this.context = context;
  }

  public SimulatedSmsRetrieverClient(Activity activity) {
    super(activity);
    this.context = activity;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Task<Void> startSmsRetriever() {
    return (Task<Void>) GmsAuthClients.startSmsRetriever(context, loader());
  }

  @Override
  @SuppressWarnings("unchecked")
  public Task<Void> startSmsUserConsent(String senderPhoneNumber) {
    return (Task<Void>) GmsAuthClients.startSmsRetriever(context, loader());
  }

  private ClassLoader loader() {
    return context != null && context.getClassLoader() != null
        ? context.getClassLoader()
        : getClass().getClassLoader();
  }
}
