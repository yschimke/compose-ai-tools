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
 * Navigator#appSentBroadcast}), and starts the deadline a {@code startForegroundService} sets
 * ({@link Navigator#expectForeground}).
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

  /**
   * The system services Robolectric has no fetcher for, which a device always has: {@code
   * AppFunctionManager} (API 36), whose calls {@link SimulatorShadowAppFunctionManager} answers.
   */
  @Implementation
  @Override
  protected Object getSystemService(String name) {
    Object service = super.getSystemService(name);
    if (service == null && "app_function".equals(name) && Build.VERSION.SDK_INT >= 36) {
      service = appFunctionManager();
    }
    return service;
  }

  private static Object appFunctions;

  /** One {@code AppFunctionManager}, built through its hidden constructor with no binder. */
  private static synchronized Object appFunctionManager() {
    if (appFunctions != null) return appFunctions;
    try {
      Class<?> type = Class.forName("android.app.appfunctions.AppFunctionManager");
      java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructors()[0];
      Object[] args = new Object[constructor.getParameterCount()];
      Class<?>[] types = constructor.getParameterTypes();
      for (int i = 0; i < args.length; i++) {
        if (android.content.Context.class.isAssignableFrom(types[i])) {
          args[i] = RuntimeEnvironment.getApplication();
        }
      }
      constructor.setAccessible(true);
      appFunctions = constructor.newInstance(args);
    } catch (ReflectiveOperationException | RuntimeException e) {
      System.err.println("app functions: no AppFunctionManager: " + e);
    }
    return appFunctions;
  }

  /** Starts the system's clock on the service calling {@code startForeground}. */
  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected android.content.ComponentName startForegroundService(Intent service) {
    Navigator.expectForeground(service);
    return super.startForegroundService(service);
  }

  @Implementation
  @Override
  protected boolean bindServiceAsUser(
      Intent service, ServiceConnection connection, int flags, UserHandle user) {
    Navigator.prepareBind(service);
    return super.bindServiceAsUser(service, connection, flags, user);
  }
}
