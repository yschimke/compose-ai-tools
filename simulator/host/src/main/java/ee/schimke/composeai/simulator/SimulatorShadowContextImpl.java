package ee.schimke.composeai.simulator;

import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;
import java.util.concurrent.Executor;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContextImpl;

/**
 * Robolectric's {@code ContextImpl} shadow, telling {@link Navigator} about each {@code
 * bindService} before Robolectric looks the intent up. Robolectric answers a bind with the binder
 * registered for that exact intent, action and extras' filter included; an app's own explicit bind
 * resolves by component on a device, and Firebase Sessions binds with an action of {@code "0"}.
 * Also routes the app's explicit broadcasts to its manifest receivers ({@link
 * Navigator#appSentBroadcast}).
 * Added to the shadow map by {@link SimulatorSandbox}.
 */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
public class SimulatorShadowContextImpl extends ShadowContextImpl {

  @Implementation
  @Override
  protected boolean bindService(Intent service, ServiceConnection connection, int flags) {
    Navigator.prepareBind(service);
    return super.bindService(service, connection, flags);
  }

  @Implementation(minSdk = Build.VERSION_CODES.Q)
  @Override
  protected boolean bindService(
      Intent service, int flags, Executor executor, ServiceConnection connection) {
    Navigator.prepareBind(service);
    return super.bindService(service, flags, executor, connection);
  }

  /**
   * The app's explicit broadcast also reaches its manifest receivers, which Robolectric does not
   * register for an APK: see {@link Navigator#appSentBroadcast}.
   */
  @Implementation
  @Override
  protected void sendBroadcast(Intent intent) {
    if (!Navigator.appSentBroadcast(intent)) super.sendBroadcast(intent);
  }

  @Implementation
  @Override
  protected void sendBroadcast(Intent intent, String receiverPermission) {
    // The receivers are the app's own, so they hold the permission when the app does; when it does
    // not, Robolectric's delivery applies the check and no manifest receiver could pass it.
    boolean held =
        receiverPermission == null
            || RuntimeEnvironment.getApplication().checkSelfPermission(receiverPermission)
                == PackageManager.PERMISSION_GRANTED;
    if (!held || !Navigator.appSentBroadcast(intent)) {
      super.sendBroadcast(intent, receiverPermission);
    }
  }

  @Implementation
  @Override
  protected boolean bindServiceAsUser(
      Intent service, ServiceConnection connection, int flags, UserHandle user) {
    Navigator.prepareBind(service);
    return super.bindServiceAsUser(service, connection, flags, user);
  }
}
