package ee.schimke.composeai.simulator;

import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.UserHandle;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContextImpl;

/**
 * Robolectric's {@code ContextImpl} shadow, telling {@link Navigator} about each {@code
 * bindService} before Robolectric looks the intent up. Robolectric answers a bind with the binder
 * registered for that exact intent, action and extras' filter included; an app's own explicit bind
 * resolves by component on a device, and Firebase Sessions binds with an action of {@code "0"}.
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

  @Implementation
  @Override
  protected boolean bindServiceAsUser(
      Intent service, ServiceConnection connection, int flags, UserHandle user) {
    Navigator.prepareBind(service);
    return super.bindServiceAsUser(service, connection, flags, user);
  }
}
