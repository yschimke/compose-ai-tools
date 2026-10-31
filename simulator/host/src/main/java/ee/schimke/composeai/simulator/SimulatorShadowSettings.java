package ee.schimke.composeai.simulator;

import android.content.ContentResolver;
import android.provider.Settings;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

/**
 * Robolectric's {@code Settings} table shadows, reporting each write to {@link DeviceSettings} so
 * the device's other apps see it too, then storing it as Robolectric does. Added to the shadow map
 * by {@link SimulatorSandbox}.
 */
public class SimulatorShadowSettings {

  @Implements(value = Settings.System.class)
  public static class SimulatorShadowSystem extends ShadowSettings.ShadowSystem {
    @Implementation
    protected static boolean putString(ContentResolver cr, String name, String value) {
      DeviceSettings.changing("system", name, value);
      return ShadowSettings.ShadowSystem.putString(cr, name, value);
    }

    @Implementation
    protected static boolean putStringForUser(
        ContentResolver cr, String name, String value, int userHandle) {
      DeviceSettings.changing("system", name, value);
      return ShadowSettings.ShadowSystem.putStringForUser(cr, name, value, userHandle);
    }

    @Implementation
    protected static boolean putFloat(ContentResolver cr, String name, float value) {
      DeviceSettings.changing("system", name, Float.toString(value));
      return ShadowSettings.ShadowSystem.putFloat(cr, name, value);
    }
  }

  @Implements(value = Settings.Secure.class)
  public static class SimulatorShadowSecure extends ShadowSettings.ShadowSecure {
    @Implementation
    protected static boolean putInt(ContentResolver cr, String name, int value) {
      DeviceSettings.changing("secure", name, Integer.toString(value));
      return ShadowSettings.ShadowSecure.putInt(cr, name, value);
    }

    @Implementation
    protected static boolean putString(ContentResolver cr, String name, String value) {
      DeviceSettings.changing("secure", name, value);
      return ShadowSettings.ShadowSecure.putString(cr, name, value);
    }

    @Implementation
    protected static boolean putStringForUser(
        ContentResolver cr, String name, String value, int userHandle) {
      DeviceSettings.changing("secure", name, value);
      return ShadowSettings.ShadowSecure.putStringForUser(cr, name, value, userHandle);
    }
  }

  @Implements(value = Settings.Global.class)
  public static class SimulatorShadowGlobal extends ShadowSettings.ShadowGlobal {
    @Implementation
    protected static boolean putString(ContentResolver cr, String name, String value) {
      DeviceSettings.changing("global", name, value);
      return ShadowSettings.ShadowGlobal.putString(cr, name, value);
    }

    @Implementation
    protected static boolean putStringForUser(
        ContentResolver cr, String name, String value, int userHandle) {
      DeviceSettings.changing("global", name, value);
      return ShadowSettings.ShadowGlobal.putStringForUser(cr, name, value, userHandle);
    }

    @Implementation
    protected static boolean putFloat(ContentResolver cr, String name, float value) {
      DeviceSettings.changing("global", name, Float.toString(value));
      return ShadowSettings.ShadowGlobal.putFloat(cr, name, value);
    }
  }

  private SimulatorShadowSettings() {}
}
