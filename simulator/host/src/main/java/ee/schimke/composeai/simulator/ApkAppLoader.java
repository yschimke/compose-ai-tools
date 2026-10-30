package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.annotation.experimental.LazyApplication;
import org.robolectric.internal.AndroidSandbox;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.pluginapi.config.Configurer;
import org.robolectric.simulator.FixedConfiguration;
import org.robolectric.simulator.Simulator;
import org.robolectric.shadows.ShadowPackageParser;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.inject.Injector;

/**
 * Robolectric's {@code AppLoader}: sets up the application from the APK and starts the {@link
 * Simulator} on its launcher activity, except that a launcher declared as an {@code
 * <activity-alias>} runs its {@code targetActivity}. Robolectric loads the alias name as a class;
 * apps that switch launcher icons have only aliases as launchers (Home Assistant RemoteCompose).
 *
 * <p>The manifest also answers {@code appComponentFactory}, which Robolectric reads from a source
 * manifest and finds unset in an APK, from the framework's parse of the APK instead, so the
 * application is created through the app's factory as on a device. A dependency-injection factory
 * wires itself up there (Cadence's Metro factory collects its activity providers), and {@link
 * Navigator} creates components through it.
 *
 * <p>For a Glimmer launch the CLI may name another activity to start: the app's projected one.
 *
 * Runs inside the sandbox.
 */
public final class ApkAppLoader implements Runnable {

  static final String LAUNCH_ACTIVITY_PROPERTY = "composeai.simulator.launchActivity";

  private final AndroidSandbox sandbox;
  private final Path apk;

  public ApkAppLoader(AndroidSandbox sandbox, Path apk) {
    this.sandbox = sandbox;
    this.apk = apk;
  }

  @Override
  public void run() {
    Thread.currentThread().setContextClassLoader(sandbox.getRobolectricClassLoader());
    AndroidManifest manifest =
        new AndroidManifest(null, null, null, null, null, apk) {
          private ApplicationInfo parsed;

          @Override
          public String getAppComponentFactory() {
            return parsed().appComponentFactory;
          }

          /** Parsed on first use, inside application setup, once Robolectric is configured. */
          private ApplicationInfo parsed() {
            if (parsed == null) {
              parsed =
                  ReflectionHelpers.getField(
                      ReflectionHelpers.callStaticMethod(
                          ShadowPackageParser.class,
                          "callParsePackage",
                          ReflectionHelpers.ClassParameter.from(Path.class, apk)),
                      "applicationInfo");
            }
            return parsed;
          }
        };
    FixedConfiguration configuration =
        FixedConfiguration.newBuilder()
            .put(ConscryptMode.Mode.class, ConscryptMode.Mode.OFF)
            .put(LooperMode.Mode.class, LooperMode.Mode.PAUSED)
            .put(LazyApplication.LazyLoad.class, LazyApplication.LazyLoad.OFF)
            .put(GraphicsMode.Mode.class, GraphicsMode.Mode.NATIVE)
            .put(SQLiteMode.Mode.class, SQLiteMode.Mode.NATIVE)
            .put(Config.class, defaultConfig())
            .build();
    sandbox.getTestEnvironment().setUpApplicationState("simulator", configuration, manifest);

    Application application = RuntimeEnvironment.getApplication();
    ActivityInfo info = firstActivity(application);
    String name = info.targetActivity != null ? info.targetActivity : info.name;
    try {
      new Simulator(Class.forName(name).asSubclass(Activity.class)).start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * The activity the launch starts: the one the CLI names in {@value #LAUNCH_ACTIVITY_PROPERTY} (a
   * Glimmer app's projected activity, which on a device the phone app sends to the glasses), else
   * the first launcher.
   */
  private static ActivityInfo firstActivity(Application application) {
    String requested = System.getProperty(LAUNCH_ACTIVITY_PROPERTY);
    if (requested != null && !requested.isBlank()) {
      try {
        return application
            .getPackageManager()
            .getActivityInfo(
                new ComponentName(application.getPackageName(), requested.trim()),
                PackageManager.MATCH_DISABLED_COMPONENTS);
      } catch (PackageManager.NameNotFoundException e) {
        System.err.println("No activity " + requested + " in the manifest; starting the launcher");
      }
    }
    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> found = application.getPackageManager().queryIntentActivities(launcher, 0);
    if (found.isEmpty()) {
      throw new IllegalArgumentException("Could not find a launcher Activity in provided manifest");
    }
    return found.get(0).activityInfo;
  }

  private static Config defaultConfig() {
    Injector injector =
        new Injector.Builder().bind(Properties.class, System.getProperties()).build();
    for (Configurer<?> configurer : injector.getInstance(Configurer[].class)) {
      if (configurer.getConfigClass().equals(Config.class)) {
        return (Config) configurer.defaultConfig();
      }
    }
    return null;
  }
}
