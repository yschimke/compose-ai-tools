package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowInstrumentation;

/**
 * Robolectric's {@code Instrumentation} shadow, launching on the caller's display. On a device an
 * activity started without a launch display goes on the display of the activity that started it,
 * so in-app navigation on a secondary display stays there; Robolectric records the start with no
 * display, and {@link Navigator} would put it on the default display. Here the caller's display is
 * written into the start's options, as {@code ActivityOptions.setLaunchDisplayId} would, unless
 * the caller named one. Added to the shadow map by name, by {@link SimulatorSandbox}.
 */
@Implements(className = "android.app.Instrumentation")
public class SimulatorShadowInstrumentation extends ShadowInstrumentation {

  /** {@code ActivityOptions.KEY_LAUNCH_DISPLAY_ID}, hidden. */
  static final String LAUNCH_DISPLAY_ID = "android.activity.launchDisplayId";

  @Implementation
  @Override
  protected ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      Activity target,
      Intent intent,
      int requestCode,
      Bundle options) {
    return super.execStartActivity(
        who, contextThread, token, target, intent, requestCode, onCallersDisplay(who, options));
  }

  @Implementation
  @Override
  protected ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      String target,
      Intent intent,
      int requestCode,
      Bundle options) {
    return super.execStartActivity(
        who, contextThread, token, target, intent, requestCode, onCallersDisplay(who, options));
  }

  /** [options], launching on [who]'s display when that is a secondary one and none is named. */
  static Bundle onCallersDisplay(Context who, Bundle options) {
    if (who == null) return options;
    if (options != null && options.getInt(LAUNCH_DISPLAY_ID, Display.INVALID_DISPLAY) >= 0) {
      return options;
    }
    int display;
    try {
      Display d = who.getDisplay();
      display = d == null ? Display.DEFAULT_DISPLAY : d.getDisplayId();
    } catch (UnsupportedOperationException e) {
      // A context with no display (the application's) starts on the default display.
      return options;
    }
    if (display == Display.DEFAULT_DISPLAY) return options;
    Bundle on = options == null ? new Bundle() : new Bundle(options);
    on.putInt(LAUNCH_DISPLAY_ID, display);
    return on;
  }
}
