package ee.schimke.composeai.simulator.shadows;

import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

/**
 * Extends Robolectric's {@link ShadowToast} to emit a {@link
 * AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED} event through {@link
 * SimulatorShadowUiAutomation#postAccessibilityEvent} when {@link Toast#show()} runs, matching
 * Android's NotificationManagerService so {@code UiAutomation.executeAndWaitForEvent} detects
 * toasts in instrumentation tests.
 */
@Implements(Toast.class)
public class SimulatorShadowToast extends ShadowToast {

  @Implementation
  @Override
  protected void show() {
    super.show();
    String text = ShadowToast.getTextOfLatestToast();
    AccessibilityEvent event =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
    event.setClassName(Toast.class.getName());
    if (text != null) {
      event.getText().add(text);
    }
    SimulatorShadowUiAutomation.postAccessibilityEvent(event);
  }
}
