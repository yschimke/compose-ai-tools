package ee.schimke.composeai.simulator;

import android.app.AppComponentFactory;
import android.app.Application;
import org.robolectric.util.ReflectionHelpers;

/** How the app's components are constructed. */
final class AppComponents {

  /**
   * The app's {@code AppComponentFactory}, the one Robolectric created the application with. A
   * manifest's factory is how dependency-injected apps construct their components (Cadence's Metro
   * factory gives activities constructor arguments); without one this is the platform default.
   */
  static AppComponentFactory factory(Application app) {
    Object loadedApk = ReflectionHelpers.getField(app, "mLoadedApk");
    AppComponentFactory factory =
        loadedApk == null ? null : ReflectionHelpers.callInstanceMethod(loadedApk, "getAppFactory");
    return factory != null ? factory : new AppComponentFactory();
  }

  private AppComponents() {}
}
