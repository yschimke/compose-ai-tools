package ee.schimke.composeai.simulator;

import android.graphics.Bitmap;
import android.view.Display;
import android.view.InputEvent;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowUiAutomation;

/**
 * Robolectric's {@code UiAutomation} shadow, one display at a time. Robolectric's own composes
 * every window into one screenshot and gives a touch to the top-most window, whatever display
 * either is on, so an app's {@code Presentation} on a second display would cover the phone's
 * screen and take its touches. Here a screenshot — which is also what Robolectric's window paints
 * — is the default display's alone, and input goes to the display {@link Displays#routeOf}
 * names; {@link Displays} draws and drives the others. Added to the shadow map by name, by {@link
 * SimulatorSandbox}.
 */
@Implements(className = "android.app.UiAutomation")
public class SimulatorShadowUiAutomation extends ShadowUiAutomation {

  @Implementation
  @Override
  protected Bitmap takeScreenshot() throws Exception {
    return Displays.capture(Display.DEFAULT_DISPLAY);
  }

  @Implementation
  @Override
  protected boolean injectInputEvent(InputEvent event, boolean sync) {
    return Displays.inject(event, Displays.routeOf(event));
  }
}
