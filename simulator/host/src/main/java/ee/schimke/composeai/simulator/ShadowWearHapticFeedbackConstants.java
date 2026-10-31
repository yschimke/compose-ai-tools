package ee.schimke.composeai.simulator;

import android.view.HapticFeedbackConstants;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Simulator-safe implementation of Wear SDK haptic constants.
 *
 * <p>The Wear SDK packages signature-only implementations in an app's APK. On hardware they are
 * serviced by Wear OS; under Robolectric their methods deliberately throw. Wear Compose reaches
 * these constants while wiring rotary scrolling, so map them to equivalent framework feedback IDs.
 * Robolectric records no physical haptic effect.
 */
@Implements(
    className = ShadowWearHapticFeedbackConstants.TARGET,
    isInAndroidSdk = false)
public final class ShadowWearHapticFeedbackConstants {

  static final String TARGET = "com.google.wear.input.WearHapticFeedbackConstants";

  private ShadowWearHapticFeedbackConstants() {}

  @Implementation
  protected static int getScrollItemFocus() {
    return HapticFeedbackConstants.KEYBOARD_TAP;
  }

  @Implementation
  protected static int getScrollLimit() {
    return HapticFeedbackConstants.REJECT;
  }

  @Implementation
  protected static int getScrollTick() {
    return HapticFeedbackConstants.CLOCK_TICK;
  }
}
