package ee.schimke.composeai.simulator;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowViewGroup;

/**
 * Lets {@code AppWidgetHostView} be itself. Robolectric's shadow replaces its {@code
 * updateAppWidget} with a plain inflate, which skips what a launcher's host view does and {@link
 * WidgetHost} relies on: picking a responsive {@code RemoteViews}' layout by the view's size,
 * reapplying over the views it has, the interaction handler that routes taps, the collection
 * adapters (which need the host view as their root) and the error view. Added to the shadow map by
 * {@link SimulatorSandbox} in place of Robolectric's.
 */
@Implements(AppWidgetHostView.class)
public class SimulatorShadowAppWidgetHostView extends ShadowViewGroup {

  /**
   * The provider's context, which the host view builds from the provider's {@code ApplicationInfo}
   * after checking its APK paths against the package manager's; Robolectric's has no code path to
   * check. The only provider here is the app itself.
   */
  @Implementation
  protected Context getRemoteContextEnsuringCorrectCachedApkPath() {
    return RuntimeEnvironment.getApplication();
  }
}
