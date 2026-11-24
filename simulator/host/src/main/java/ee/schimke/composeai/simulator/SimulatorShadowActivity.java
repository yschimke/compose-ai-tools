package ee.schimke.composeai.simulator;

import android.os.Handler;
import android.os.Looper;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;

/**
 * Robolectric's {@code Activity} shadow, with {@code recreate()} carried out by {@link Navigator}
 * as the simulator's {@code recreate} command does. Robolectric's own goes through {@code
 * ActivityController.recreate()}, which asks for window focus before the new window is attached and
 * fails on an activity the simulator's navigation started (an app switching theme, or a test's
 * {@code ActivityScenario.recreate()}). As on a device, the re-creation is posted, not run in the
 * call. Added to the shadow map by name, by {@link SimulatorSandbox}.
 */
@Implements(className = "android.app.Activity")
public class SimulatorShadowActivity extends ShadowActivity {

  @Implementation
  @Override
  protected void recreate() {
    Navigator navigator = Navigator.current();
    if (navigator == null) {
      super.recreate();
      return;
    }
    android.app.Activity activity = realActivity;
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              if (!navigator.recreate(activity)) {
                System.err.println("recreate: " + activity + " is not on the back stack");
              }
            });
  }
}
