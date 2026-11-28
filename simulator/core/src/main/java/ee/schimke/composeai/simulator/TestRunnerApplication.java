package ee.schimke.composeai.simulator;

import java.nio.file.Path;
import java.util.List;
import org.robolectric.shadows.ShadowPackageParser;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Which {@code Application} an {@code --instrument} run creates. Apart from {@link
 * InstrumentationRun}, whose test discovery and reporting run (and are unit tested) without the
 * framework's classes.
 */
public final class TestRunnerApplication {

  private static final ThreadLocal<String[]> PROBE = new ThreadLocal<>();

  private static final class ProbeRecorded extends RuntimeException {}

  /**
   * Called by {@link ee.schimke.composeai.simulator.shadows.SimulatorShadowInstrumentation} when
   * {@code Instrumentation.newApplication(Class, Context)} runs. During {@link #ask}, captures the
   * requested application class name and aborts before {@code Application.attach(Context)} runs
   * against the probe's dummy context (which would otherwise throw and lose the class name).
   */
  public static void recordApplicationClass(Class<?> clazz) {
    String[] slot = PROBE.get();
    if (slot == null) return;
    if (slot[0] == null && clazz != null) slot[0] = clazz.getName();
    throw new ProbeRecorded();
  }

  /**
   * The {@code Application} class the test APK's own runner creates in place of [manifestName],
   * or null when it keeps the manifest's. A device's {@code am instrument} creates the app through
   * the {@code <instrumentation android:name>} runner's {@code newApplication}, which Hilt projects
   * (and others) override to return their test application ({@code HiltTestApplication}); the
   * simulator runs the tests with its own runner, so it asks theirs which class it would have
   * created. The runner is instantiated and asked with a class loader that records the name it
   * loads and stops there, and with a static {@code Instrumentation.newApplication(Class, Context)}
   * probe for runners that bypass the class loader; one that creates the application some other way
   * is answered by the class of what it returns. Any failure keeps the manifest's application.
   */
  static String runnerApplication(String manifestName, String packageName) {
    // Robolectric asks more than once while it sets the application up; the runner is asked once.
    synchronized (TestRunnerApplication.class) {
      if (!asked) {
        answer = ask(manifestName, packageName);
        asked = true;
      }
      return answer;
    }
  }

  private static boolean asked;
  private static String answer;

  private static String ask(String manifestName, String packageName) {
    String apk = System.getProperty(InstrumentationRun.APK_PROPERTY);
    if (!InstrumentationRun.enabled() || apk == null || apk.isEmpty()) return null;
    try {
      Object parsed =
          ReflectionHelpers.callStaticMethod(
              ShadowPackageParser.class,
              "callParsePackage",
              ClassParameter.from(Path.class, Path.of(apk)));
      List<?> instrumentations = ReflectionHelpers.getField(parsed, "instrumentation");
      if (instrumentations == null || instrumentations.isEmpty()) return null;
      android.content.pm.InstrumentationInfo info =
          ReflectionHelpers.getField(instrumentations.get(0), "info");
      ClassLoader sandbox = TestRunnerApplication.class.getClassLoader();
      android.app.Instrumentation runner =
          (android.app.Instrumentation)
              Class.forName(info.name, true, sandbox).getDeclaredConstructor().newInstance();
      String[] asked = new String[1];
      ClassLoader recording =
          new ClassLoader(sandbox) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
              if (asked[0] == null) asked[0] = name;
              throw new ClassNotFoundException(RECORDED);
            }
          };
      android.content.Context context =
          new android.content.ContextWrapper(null) {
            @Override
            public String getPackageName() {
              return packageName;
            }
          };
      Object created = null;
      PROBE.set(asked);
      try {
        created = runner.newApplication(recording, manifestName, context);
      } catch (Throwable expected) {
        // The recording class loader or static newApplication probe stops creation once the name is
        // known.
      } finally {
        PROBE.remove();
      }
      String chosen =
          asked[0] != null ? asked[0] : created == null ? null : created.getClass().getName();
      if (chosen == null || chosen.equals(manifestName)) return null;
      System.err.println(
          "instrument: " + info.name + " creates " + chosen + " in place of " + manifestName);
      return chosen;
    } catch (Throwable t) {
      System.err.println("instrument: cannot ask the test APK's runner for its application: " + t);
      return null;
    }
  }

  private static final String RECORDED = "recorded by the simulator";

  private TestRunnerApplication() {}
}
