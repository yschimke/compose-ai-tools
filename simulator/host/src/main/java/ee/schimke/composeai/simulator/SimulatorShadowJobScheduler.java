package ee.schimke.composeai.simulator;

import android.app.job.JobScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowJobScheduler;

/**
 * Robolectric's {@code JobSchedulerImpl} shadow, with every instance known: as with {@link
 * SimulatorShadowAlarmManager}, each context's scheduler keeps its own jobs, where a device has
 * one job scheduler. {@link Jobs} goes through all of them. Added to the shadow map by {@link
 * SimulatorSandbox}.
 */
@Implements(className = "android.app.JobSchedulerImpl", isInAndroidSdk = false)
public class SimulatorShadowJobScheduler extends ShadowJobScheduler.ShadowJobSchedulerImpl {

  private static final Set<SimulatorShadowJobScheduler> instances =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  @RealObject JobScheduler real;

  public SimulatorShadowJobScheduler() {
    instances.add(this);
  }

  JobScheduler real() {
    return real;
  }

  static List<SimulatorShadowJobScheduler> all() {
    synchronized (instances) {
      return new ArrayList<>(instances);
    }
  }
}
