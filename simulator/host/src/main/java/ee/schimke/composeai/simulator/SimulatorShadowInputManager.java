package ee.schimke.composeai.simulator;

import android.view.InputEvent;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowInputManager;

/**
 * Robolectric's {@code InputManager} shadow, with {@code injectInputEvent} delivering the event as
 * the simulator's {@code UiAutomation} does ({@link Displays#inject}). Robolectric's ignores the
 * event up to API 33 and, from 34, leaves it to {@code InputManagerGlobal}, which has no input
 * service behind it and fails. Espresso injects every touch and key this way, so without it no
 * {@code click()} reached a view. Added to the shadow map by name, by {@link SimulatorSandbox}.
 */
@Implements(className = "android.hardware.input.InputManager")
public class SimulatorShadowInputManager extends ShadowInputManager {

  @Implementation
  protected boolean injectInputEvent(InputEvent event, int mode) {
    return Displays.inject(event, Displays.routeOf(event));
  }

  @Implementation(minSdk = 29)
  protected boolean injectInputEvent(InputEvent event, int mode, int targetUid) {
    return Displays.inject(event, Displays.routeOf(event));
  }
}
