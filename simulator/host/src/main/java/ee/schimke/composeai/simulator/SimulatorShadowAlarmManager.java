package ee.schimke.composeai.simulator;

import android.app.AlarmManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAlarmManager;

/**
 * Robolectric's {@code AlarmManager} shadow, with every instance known. Each context caches its
 * own {@code AlarmManager}, and Robolectric keeps each one's alarms apart, where a device has one
 * alarm service: {@link Alarms} goes through all of them. Added to the shadow map by {@link
 * SimulatorSandbox}.
 */
@Implements(AlarmManager.class)
public class SimulatorShadowAlarmManager extends ShadowAlarmManager {

  private static final Set<SimulatorShadowAlarmManager> instances =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  @RealObject AlarmManager real;

  public SimulatorShadowAlarmManager() {
    instances.add(this);
  }

  AlarmManager real() {
    return real;
  }

  static List<SimulatorShadowAlarmManager> all() {
    synchronized (instances) {
      return new ArrayList<>(instances);
    }
  }
}
