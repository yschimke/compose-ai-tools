package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.ExecuteAppFunctionRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAppFunctionManager;

/**
 * {@code AppFunctionManager.executeAppFunction}, which Robolectric does not run: an agent app's
 * call into another app's functions. A call to the app's own package runs its {@code
 * AppFunctionService} in place; a call to another app installed on the device ({@code --install})
 * goes over the {@link DeviceBus} to that app's simulator, which runs the function there with the
 * caller's package ({@link AppFunctions#serveOnBus}), and the result document comes back. The
 * system's checks are made as on a device: a caller without {@code EXECUTE_APP_FUNCTIONS} is
 * {@code ERROR_DENIED}, an app that is not installed {@code ERROR_FUNCTION_NOT_FOUND}. Added to the
 * shadow map by {@link SimulatorSandbox}.
 */
@Implements(value = AppFunctionManager.class, minSdk = 36)
public class SimulatorShadowAppFunctionManager extends ShadowAppFunctionManager {

  static final String EXECUTE_APP_FUNCTIONS = "android.permission.EXECUTE_APP_FUNCTIONS";

  @Implementation
  protected void executeAppFunction(
      ExecuteAppFunctionRequest request,
      Executor executor,
      CancellationSignal signal,
      OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback) {
    Application app = RuntimeEnvironment.getApplication();
    String target = request.getTargetPackageName();
    AtomicBoolean cancelled = new AtomicBoolean();
    if (signal != null) signal.setOnCancelListener(() -> cancelled.set(true));
    AppFunctions.Api36.Result deliver =
        result -> {
          if (cancelled.get()) return;
          executor.execute(() -> answer(result, callback));
        };
    JSONObject params = AppFunctions.Api36.json(request.getParameters());
    String function = request.getFunctionIdentifier();
    try {
      if (target.equals(app.getPackageName())) {
        // An app calling its own functions needs no permission.
        AppFunctions.Api36.execute(
            Navigator.serviceFor(AppFunctions.service(app, target)),
            target,
            function,
            params,
            app.getPackageName(),
            deliver);
        return;
      }
      if (!AppFunctions.declares(app, EXECUTE_APP_FUNCTIONS)) {
        deliver.accept(
            AppFunctions.failure(
                AppFunctionException.ERROR_DENIED,
                app.getPackageName() + " does not hold " + EXECUTE_APP_FUNCTIONS));
        return;
      }
      if (!DeviceBus.enabled() || !DevicePeers.isPeer(target)) {
        deliver.accept(
            AppFunctions.failure(
                AppFunctionException.ERROR_FUNCTION_NOT_FOUND, target + " is not installed"));
        return;
      }
      DeviceBus.request(
              target,
              AppFunctions.BUS_OP,
              new Protocol.Json().put("function", function).put("params", params).object())
          .whenComplete(
              (reply, error) -> {
                if (error != null) {
                  deliver.accept(
                      AppFunctions.failure(AppFunctionException.ERROR_SYSTEM_ERROR, error.toString()));
                } else if (reply != null && reply.has("busError")) {
                  deliver.accept(
                      AppFunctions.failure(
                          AppFunctionException.ERROR_SYSTEM_ERROR, reply.optString("busError")));
                } else {
                  deliver.accept(reply);
                }
              });
    } catch (Exception e) {
      deliver.accept(AppFunctions.failure(AppFunctionException.ERROR_SYSTEM_ERROR, e.toString()));
    }
  }

  private static void answer(
      JSONObject result, OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback) {
    JSONObject error = result.optJSONObject("error");
    if (error != null) {
      callback.onError(
          new AppFunctionException(
              error.optInt("code", AppFunctionException.ERROR_SYSTEM_ERROR),
              error.optString("message")));
      return;
    }
    try {
      JSONObject document = result.optJSONObject("result");
      callback.onResult(
          new ExecuteAppFunctionResponse(
              AppFunctions.Api36.document(document == null ? new JSONObject() : document)));
    } catch (Exception e) {
      callback.onError(
          new AppFunctionException(AppFunctionException.ERROR_SYSTEM_ERROR, e.toString()));
    }
  }
}
