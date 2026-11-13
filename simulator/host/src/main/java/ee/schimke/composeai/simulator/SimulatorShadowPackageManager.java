package ee.schimke.composeai.simulator;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Build;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;

/**
 * Extends Robolectric's {@code ApplicationPackageManager} shadow to serve manifest {@code
 * <property>} tags indexed by {@link WffWatchFaces} (Robolectric 4.17's {@code getProperty} throws
 * {@code NameNotFoundException("unsupported")}, which prevents {@code DeclarativeWatchFaceRuntime}
 * from reading {@code com.google.wear.watchface.format.version}).
 */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
public class SimulatorShadowPackageManager extends ShadowApplicationPackageManager {

  @Implementation(minSdk = Build.VERSION_CODES.S)
  @Override
  protected @ClassName("android.content.pm.PackageManager$Property") Object getProperty(
      String propertyName, String packageName) throws PackageManager.NameNotFoundException {
    Object property = WffWatchFaces.findProperty(packageName, "", propertyName);
    if (property != null) return property;
    throw new PackageManager.NameNotFoundException(propertyName);
  }

  @Implementation(minSdk = Build.VERSION_CODES.S)
  @Override
  protected @ClassName("android.content.pm.PackageManager$Property") Object getProperty(
      String propertyName, ComponentName componentName)
      throws PackageManager.NameNotFoundException {
    Object property =
        WffWatchFaces.findProperty(
            componentName.getPackageName(), componentName.getClassName(), propertyName);
    if (property != null) return property;
    throw new PackageManager.NameNotFoundException(propertyName);
  }
}
