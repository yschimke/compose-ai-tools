package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.wearable.authentication.IAuthenticationRequestCallback;
import android.support.wearable.authentication.IAuthenticationRequestService;
import android.support.wearable.notifications.IBridgingManagerService;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;

/**
 * The two services of the Wear OS app ({@code com.google.android.wearable.app}) that {@code
 * androidx.wear:wear-phone-interactions} binds on a watch, registered for a paired watch so the
 * binds connect:
 *
 * <ul>
 *   <li>{@code RemoteAuthClient}'s {@code android.support.wearable.authentication.action.OAUTH}:
 *       {@code openUrl} hands the request to {@link DataLayer}, which sends it to the phone, and
 *       the phone's answer comes back to the app's callback ({@link #answer});
 *   <li>{@code BridgingManager}'s {@code BIND_BRIDGING_MANAGER}: {@code setBridgingConfig} is
 *       reported on the control channel.
 * </ul>
 *
 * Their AIDL classes are the app's own (compiled here against stubs), so this class is loaded only
 * when the app has them.
 */
final class WearCompanion {

  static final String ACTION_AUTH = "android.support.wearable.authentication.action.OAUTH";
  static final String ACTION_BRIDGING =
      "android.support.wearable.notifications.action.BIND_BRIDGING_MANAGER";
  static final String KEY_REQUEST_URL = "requestUrl";
  static final String KEY_PACKAGE_NAME = "packageName";
  static final String KEY_RESPONSE_URL = "responseUrl";
  static final String KEY_ERROR_CODE = "errorCode";
  /** {@code RemoteAuthClient.NO_ERROR}. */
  static final int NO_ERROR = -1;

  private static final String AUTH_SERVICE =
      "android.support.wearable.authentication.IAuthenticationRequestService";
  private static final String BRIDGING_SERVICE =
      "android.support.wearable.notifications.IBridgingManagerService";

  /** Registers the services whose AIDL the app has; a no-op for an app without the library. */
  static void install(Application app, DataLayer node) {
    if (has(app, AUTH_SERVICE)) {
      register(app, ACTION_AUTH, "SimulatedAuthenticationService", new Auth(node));
    }
    if (has(app, BRIDGING_SERVICE)) {
      register(app, ACTION_BRIDGING, "SimulatedBridgingManagerService", new Bridging(node));
    }
  }

  private static boolean has(Application app, String type) {
    try {
      Class.forName(type, false, app.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static void register(Application app, String action, String name, IBinder binder) {
    ShadowApplication shadow = Shadow.extract(app);
    Intent intent = new Intent(action).setPackage(RemoteIntents.COMPANION_PACKAGE);
    shadow.setComponentNameAndServiceForBindServiceForIntent(
        intent, new ComponentName(RemoteIntents.COMPANION_PACKAGE, name), binder);
    Navigator.registerForeignBind(intent);
  }

  /** Calls back the app's {@code RemoteAuthClient} with the phone's answer. */
  static void answer(Object callback, String responseUrl, int errorCode) {
    Bundle result = new Bundle();
    if (responseUrl != null) {
      result.putParcelable(KEY_RESPONSE_URL, android.net.Uri.parse(responseUrl));
    }
    result.putInt(KEY_ERROR_CODE, errorCode);
    try {
      ((IAuthenticationRequestCallback) callback).onResult(result);
    } catch (android.os.RemoteException | RuntimeException e) {
      System.err.println("datalayer: the app's OAuth callback failed: " + e);
    }
  }

  private static final class Auth extends IAuthenticationRequestService.Stub {
    private final DataLayer node;

    Auth(DataLayer node) {
      this.node = node;
    }

    @Override
    @SuppressWarnings("deprecation") // getParcelable(String): the typed one is API 33+.
    public void openUrl(Bundle request, IAuthenticationRequestCallback callback) {
      Object url = request == null ? null : request.getParcelable(KEY_REQUEST_URL);
      String packageName = request == null ? null : request.getString(KEY_PACKAGE_NAME);
      node.sendAuthRequest(url == null ? null : url.toString(), packageName, callback);
    }

    @Override
    public int getApiVersion() {
      return 1;
    }

    @Override
    public IBinder asBinder() {
      return this;
    }
  }

  private static final class Bridging extends IBridgingManagerService.Stub {
    private final DataLayer node;

    Bridging(DataLayer node) {
      this.node = node;
    }

    @Override
    public void setBridgingConfig(Bundle config) {
      node.reportBridging(config);
    }

    @Override
    public int getApiVersion() {
      return 1;
    }

    @Override
    public IBinder asBinder() {
      return this;
    }
  }

  private WearCompanion() {}
}
