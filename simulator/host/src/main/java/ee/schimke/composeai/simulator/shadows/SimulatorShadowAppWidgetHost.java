package ee.schimke.composeai.simulator.shadows;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import ee.schimke.composeai.simulator.WidgetHost;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAppWidgetHost;
import org.robolectric.util.ReflectionHelpers;

/**
 * An {@code AppWidgetHost} the app runs itself (a launcher of its own, a widget picker's preview,
 * an instrumentation test), served by {@link WidgetHost} as the service serves one: ids allocated
 * from the device's id space, bound with {@code bindAppWidgetIdIfAllowed}, and the provider's
 * {@code updateAppWidget} drawn by the host views it creates. Robolectric's own allocates one fixed
 * id and never hands its views an update. Without a session, Robolectric's behaviour stands. Added
 * to the shadow map by {@code SimulatorSandbox}.
 */
@Implements(AppWidgetHost.class)
public class SimulatorShadowAppWidgetHost extends ShadowAppWidgetHost {

  @Implementation
  @Override
  protected int allocateAppWidgetId() {
    WidgetHost host = WidgetHost.current;
    return host == null ? super.allocateAppWidgetId() : host.allocateForApp(getHostId());
  }

  @Implementation
  @Override
  protected AppWidgetHostView createView(
      Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
    // As the framework's: the host's own view class (onCreateView), then bound to the widget.
    // Not Robolectric's, which casts the view's shadow to its own (the simulator has another).
    AppWidgetHostView view =
        ReflectionHelpers.callInstanceMethod(
            AppWidgetHost.class,
            ReflectionHelpers.getField(this, "realAppWidgetHost"),
            "onCreateView",
            ReflectionHelpers.ClassParameter.from(Context.class, context),
            ReflectionHelpers.ClassParameter.from(int.class, appWidgetId),
            ReflectionHelpers.ClassParameter.from(AppWidgetProviderInfo.class, appWidget));
    view.setAppWidget(appWidgetId, appWidget);
    WidgetHost host = WidgetHost.current;
    if (host != null) host.appViewCreated(appWidgetId, view);
    return view;
  }

  @Implementation
  protected void deleteAppWidgetId(int appWidgetId) {
    WidgetHost host = WidgetHost.current;
    if (host != null) host.deleteForApp(appWidgetId);
  }
}
