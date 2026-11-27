package ee.schimke.composeai.simulator;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Shared helpers for system status bars ({@code PhoneSystemUi} in {@code :simulator-mobile} and
 * {@code WearStatusBar} in {@code :simulator-wear}).
 */
final class SystemBars {
  private SystemBars() {}

  /**
   * Returns Robolectric's {@code SystemUi.getStatusBar()} for the default display after enabling
   * {@code STANDARD_STATUS_BAR}, or null when Robolectric's {@code SystemUi} is unavailable.
   */
  static Object robolectricStatusBar() {
    try {
      ClassLoader loader = SystemBars.class.getClassLoader();
      Class<?> systemUi = Class.forName("org.robolectric.shadows.SystemUi", true, loader);
      Class<?> behavior =
          Class.forName("org.robolectric.shadows.SystemUi$StatusBarBehavior", true, loader);
      Object ui = ReflectionHelpers.callStaticMethod(systemUi, "systemUiForDefaultDisplay");
      ReflectionHelpers.callInstanceMethod(
          ui,
          "setStatusBarBehavior",
          ClassParameter.from(
              behavior, ReflectionHelpers.getStaticField(systemUi, "STANDARD_STATUS_BAR")));
      return ReflectionHelpers.callInstanceMethod(ui, "getStatusBar");
    } catch (ClassNotFoundException | RuntimeException e) {
      return null;
    }
  }

  /** Marks a simulator-owned chrome view with an accessibility id of {@code sim:id/<name>}. */
  static void simId(View view, String name) {
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    view.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setViewIdResourceName("sim:id/" + name);
          }
        });
  }
}
