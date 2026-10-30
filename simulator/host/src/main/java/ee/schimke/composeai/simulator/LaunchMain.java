package ee.schimke.composeai.simulator;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.internal.AndroidSandbox;


/**
 * The launched JVM's entry point: what Robolectric's {@code SimulatorMain} does, on a {@link
 * SimulatorSandbox} so the manifest's content providers start before the application, and with two
 * things set first.
 *
 * <ul>
 *   <li>With the control channel on, {@code System.out} is pointed at stderr, so file descriptor 1
 *       carries only {@link Protocol} lines however much the app and Robolectric log.
 *   <li>The launcher activity is started through {@code Context.startActivity} rather than
 *       Robolectric's {@code setupActivity}, so it reaches {@link Navigator} like every activity
 *       the app starts later, and the back stack has one owner.
 * </ul>
 *
 * Arguments: the APK (Robolectric's resource APK, also on the classpath so its {@code
 * resources.arsc} opens), then extra classpath jars (the converted classes).
 */
public final class LaunchMain {

  public static void main(String[] args) {
    if ("stdio".equals(System.getProperty(Protocol.CONTROL_PROPERTY))) {
      System.setOut(System.err);
    }
    System.setProperty("robolectric.useContextStartActivity", "true");
    // Before any app code builds an HTTP client: the simulated network decides what its traffic
    // reaches (NetworkSim drives it). Online and untouched until then.
    ee.schimke.composeai.simulator.net.NetworkGate.install();
    // The app's crash reporter (Crashlytics, for one) installs its own handler and chains to the
    // one it found, so set ours first: an app crash is printed instead of ending the process
    // silently. One on this thread (the sandbox failing to start, say) ends the simulator, which
    // would otherwise sit with the window's threads and no app.
    Thread launcher = Thread.currentThread();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          System.err.println("Uncaught exception in thread \"" + thread.getName() + "\":");
          error.printStackTrace();
          if (thread == launcher) System.exit(1);
        });
    if (args.length < 1) {
      System.err.println("usage: LaunchMain <apk> [extra jars]");
      System.exit(2);
    }
    File apk = new File(args[0]);
    List<Path> classpath = new ArrayList<>();
    classpath.add(apk.toPath());
    for (int i = 1; i < args.length; i++) classpath.add(Path.of(args[i]));
    int sdk = Integer.parseInt(System.getProperty("robolectric.deviceconfig.sdk", "35"));

    Startup.mark(Startup.MAIN);
    AndroidSandbox sandbox = SimulatorSandbox.build(classpath, sdk);
    Startup.mark(Startup.SANDBOX);
    try {
      sandbox.runOnMainThread(
          () -> {
            try {
              Class<?> loader = sandbox.bootstrappedClass(ApkAppLoader.class);
              Constructor<?> ctor = loader.getConstructor(AndroidSandbox.class, Path.class);
              ((Runnable) ctor.newInstance(sandbox, apk.toPath())).run();
            } catch (ReflectiveOperationException e) {
              throw new RuntimeException(e);
            }
          });
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(1);
    }
  }

  private LaunchMain() {}
}
