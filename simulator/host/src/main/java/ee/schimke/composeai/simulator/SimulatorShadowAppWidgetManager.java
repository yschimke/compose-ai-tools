package ee.schimke.composeai.simulator;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAppWidgetManager;

/**
 * Robolectric's {@code AppWidgetManager} shadow, answered by {@link WidgetHost}: the app's
 * providers come from its manifest, its widget ids are the ones the home screen bound, and what it
 * passes to {@code updateAppWidget} is drawn there. Robolectric's own keeps widgets only a test
 * created, and fails on an update for any other id. Without a session yet, Robolectric's behaviour
 * stands. Added to the shadow map by {@link SimulatorSandbox}.
 */
@Implements(AppWidgetManager.class)
public class SimulatorShadowAppWidgetManager extends ShadowAppWidgetManager {

  private static WidgetHost host() {
    return WidgetHost.current;
  }

  @Implementation
  @Override
  protected void updateAppWidget(int[] appWidgetIds, RemoteViews views) {
    WidgetHost host = host();
    if (host == null) return;
    for (int id : appWidgetIds) host.appUpdated(id, views, false);
  }

  @Implementation
  @Override
  protected void updateAppWidget(int appWidgetId, RemoteViews views) {
    WidgetHost host = host();
    if (host != null) host.appUpdated(appWidgetId, views, false);
  }

  @Implementation
  protected void updateAppWidget(ComponentName provider, RemoteViews views) {
    WidgetHost host = host();
    if (host != null) host.appUpdated(provider, views);
  }

  @Implementation
  protected void partiallyUpdateAppWidget(int[] appWidgetIds, RemoteViews views) {
    WidgetHost host = host();
    if (host == null) return;
    for (int id : appWidgetIds) host.appUpdated(id, views, true);
  }

  @Implementation
  protected void partiallyUpdateAppWidget(int appWidgetId, RemoteViews views) {
    WidgetHost host = host();
    if (host != null) host.appUpdated(appWidgetId, views, true);
  }

  @Implementation
  protected void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId) {
    WidgetHost host = host();
    if (host == null) return;
    for (int id : appWidgetIds) host.appDataChanged(id, viewId);
  }

  @Implementation
  protected void notifyAppWidgetViewDataChanged(int appWidgetId, int viewId) {
    WidgetHost host = host();
    if (host != null) host.appDataChanged(appWidgetId, viewId);
  }

  /** Hidden: the host view reports each tap to the service, which keeps usage stats. */
  @Implementation
  protected void noteAppWidgetTapped(int appWidgetId) {}

  @Implementation
  @Override
  protected int[] getAppWidgetIds(ComponentName provider) {
    WidgetHost host = host();
    return host == null ? super.getAppWidgetIds(provider) : host.idsOf(provider);
  }

  @Implementation
  @Override
  protected List<AppWidgetProviderInfo> getInstalledProviders() {
    WidgetHost host = host();
    return host == null ? super.getInstalledProviders() : new ArrayList<>(host.providers());
  }

  @Implementation
  @Override
  protected List<AppWidgetProviderInfo> getInstalledProvidersForProfile(UserHandle profile) {
    WidgetHost host = host();
    return host == null
        ? super.getInstalledProvidersForProfile(profile)
        : new ArrayList<>(host.providers());
  }

  @Implementation
  @Override
  protected List<AppWidgetProviderInfo> getInstalledProvidersForPackage(
      String packageName, UserHandle profile) {
    WidgetHost host = host();
    if (host == null) return super.getInstalledProvidersForPackage(packageName, profile);
    List<AppWidgetProviderInfo> providers = new ArrayList<>();
    for (AppWidgetProviderInfo info : host.providers()) {
      if (info.provider.getPackageName().equals(packageName)) providers.add(info);
    }
    return providers;
  }

  @Implementation
  @Override
  protected AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) {
    WidgetHost host = host();
    return host == null ? super.getAppWidgetInfo(appWidgetId) : host.infoOf(appWidgetId);
  }

  @Implementation
  @Override
  protected Bundle getAppWidgetOptions(int appWidgetId) {
    WidgetHost host = host();
    return host == null ? super.getAppWidgetOptions(appWidgetId) : host.optionsOf(appWidgetId);
  }

  @Implementation
  @Override
  protected void updateAppWidgetOptions(int appWidgetId, Bundle options) {
    WidgetHost host = host();
    if (host == null) super.updateAppWidgetOptions(appWidgetId, options);
    else host.appUpdatedOptions(appWidgetId, options);
  }

  /** Apps do not bind ids themselves; a launcher does, and only the simulator's binds here. */
  @Implementation
  @Override
  protected boolean bindAppWidgetIdIfAllowed(int appWidgetId, ComponentName provider) {
    return host() == null && super.bindAppWidgetIdIfAllowed(appWidgetId, provider);
  }

  @Implementation
  @Override
  protected boolean bindAppWidgetIdIfAllowed(
      int appWidgetId, ComponentName provider, Bundle options) {
    return host() == null && super.bindAppWidgetIdIfAllowed(appWidgetId, provider, options);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean isRequestPinAppWidgetSupported() {
    WidgetHost host = host();
    return host == null ? super.isRequestPinAppWidgetSupported() : host.hasHome();
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean requestPinAppWidget(
      ComponentName provider, Bundle extras, PendingIntent successCallback) {
    WidgetHost host = host();
    return host == null
        ? super.requestPinAppWidget(provider, extras, successCallback)
        : host.appRequestedPin(provider, extras, successCallback);
  }

  @Implementation(minSdk = Build.VERSION_CODES.VANILLA_ICE_CREAM)
  @Override
  protected boolean setWidgetPreview(
      ComponentName provider, int widgetCategories, RemoteViews preview) {
    WidgetHost host = host();
    if (host == null) return super.setWidgetPreview(provider, widgetCategories, preview);
    if ((widgetCategories & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0) {
      host.setGeneratedPreview(provider, preview);
    }
    return true;
  }

  @Implementation(minSdk = Build.VERSION_CODES.VANILLA_ICE_CREAM)
  @Override
  protected RemoteViews getWidgetPreview(
      ComponentName provider, UserHandle user, int widgetCategories) {
    WidgetHost host = host();
    if (host == null) return super.getWidgetPreview(provider, user, widgetCategories);
    return (widgetCategories & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0
        ? host.generatedPreview(provider)
        : null;
  }

  @Implementation(minSdk = Build.VERSION_CODES.VANILLA_ICE_CREAM)
  @Override
  protected void removeWidgetPreview(ComponentName provider, int widgetCategories) {
    WidgetHost host = host();
    if (host == null) super.removeWidgetPreview(provider, widgetCategories);
    else if ((widgetCategories & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0) {
      host.setGeneratedPreview(provider, null);
    }
  }
}
