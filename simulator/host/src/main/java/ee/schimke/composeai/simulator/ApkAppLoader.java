package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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
import org.robolectric.util.inject.Injector;

/**
 * Robolectric's {@code AppLoader}: sets up the application from the APK and starts the {@link
 * Simulator} on its launcher activity, except that a launcher declared as an {@code
 * <activity-alias>} runs its {@code targetActivity}. Robolectric loads the alias name as a class;
 * apps that switch launcher icons have only aliases as launchers (Home Assistant RemoteCompose).
 * Runs inside the sandbox.
 */
public final class ApkAppLoader implements Runnable {

  private final AndroidSandbox sandbox;
  private final Path apk;

  public ApkAppLoader(AndroidSandbox sandbox, Path apk) {
    this.sandbox = sandbox;
    this.apk = apk;
  }

  @Override
  public void run() {
    Thread.currentThread().setContextClassLoader(sandbox.getRobolectricClassLoader());
    AndroidManifest manifest = new AndroidManifest(null, null, null, null, null, apk);
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
    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> found = application.getPackageManager().queryIntentActivities(launcher, 0);
    if (found.isEmpty()) {
      throw new IllegalArgumentException("Could not find a launcher Activity in provided manifest");
    }
    ActivityInfo info = found.get(0).activityInfo;
    String name = info.targetActivity != null ? info.targetActivity : info.name;
    try {
      new Simulator(Class.forName(name).asSubclass(Activity.class)).start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
