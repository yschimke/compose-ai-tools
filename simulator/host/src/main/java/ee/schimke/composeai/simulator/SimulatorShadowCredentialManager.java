package ee.schimke.composeai.simulator;

import android.content.Context;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CreateCredentialResponse;
import android.credentials.CredentialManager;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * The platform {@code CredentialManager}'s requests, answered by {@link Credentials} in place of
 * the system's account sheet, which Robolectric's has no service for. Added to the shadow map by
 * {@link SimulatorSandbox}.
 */
@Implements(value = CredentialManager.class, minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class SimulatorShadowCredentialManager {

  @Implementation
  protected void getCredential(
      Context context,
      GetCredentialRequest request,
      CancellationSignal cancellationSignal,
      Executor executor,
      OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
    Credentials.get(request, executor, callback);
  }

  @Implementation
  protected void createCredential(
      Context context,
      CreateCredentialRequest request,
      CancellationSignal cancellationSignal,
      Executor executor,
      OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback) {
    Credentials.create(request, executor, callback);
  }
}
