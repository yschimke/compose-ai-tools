package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPackageParser;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * {@code android-simulator --instrument}: an instrumentation APK's JUnit tests, run inside the
 * simulator beside the app, as {@code am instrument} runs them on a device.
 *
 * <p>As on a device, the tests run on a thread of their own while the main thread keeps looping:
 * Robolectric's simulator owns the main looper and runs it in real time, so {@code
 * runOnMainSync}, {@code ActivityScenario}, Espresso and the Compose test rules post to it and wait,
 * rather than driving a paused looper from the test thread as a Robolectric unit test does.
 *
 * <p>What stands in for {@code AndroidJUnitRunner}: JUnit's own {@link JUnitCore}, with {@code
 * AndroidJUnit4} pointed at androidx.test's device runner ({@code AndroidJUnit4ClassRunner}) rather
 * than at Robolectric's, which would start a sandbox of its own. The instrumentation is the one the
 * simulator already registered with {@code InstrumentationRegistry}. The test APK's classes are on
 * the sandbox's class path after the app's (the CLI converts them as it converts the app's), and
 * its manifest's activities ({@code androidx.test.core}'s {@code BootstrapActivity} and friends)
 * are declared to the package manager under the app's package, since one process serves both.
 *
 * <p>The results go to stderr, in {@code am instrument}'s summary shape, and, when {@value
 * #REPORT_PROPERTY} names a file, to a JUnit XML report; the simulator then exits with 0 when every
 * test passed and 1 otherwise.
 */
public final class InstrumentationRun {

  /** The converted test APK's classes jar: where the test classes are looked for. */
  static final String PROPERTY = "composeai.simulator.instrument";

  /** The test APK itself, for its manifest. */
  static final String APK_PROPERTY = "composeai.simulator.instrument.apk";

  /** Comma-separated {@code Class} or {@code Class#method}: only these run. */
  static final String TESTS_PROPERTY = "composeai.simulator.instrument.tests";

  /** A JUnit XML report to write. */
  static final String REPORT_PROPERTY = "composeai.simulator.instrument.report";

  /** A JSON report to write: each case's outcome, for agents and the Gradle task. */
  static final String JSON_PROPERTY = "composeai.simulator.instrument.json";

  /**
   * {@code index/count}: this simulator runs only its share of the tests, as AndroidJUnitRunner's
   * {@code -e shardIndex -e numShards} do. A test belongs to the shard its display name's hash
   * picks, so N simulators given the same tests split them between them with none run twice.
   */
  static final String SHARD_PROPERTY = "composeai.simulator.instrument.shard";

  /** Directory where JaCoCo {@code .ec} coverage execution data is written when coverage is on. */
  static final String COVERAGE_DIR_PROPERTY = "composeai.simulator.instrument.coverageDir";

  /** Directory for additional test outputs (benchmarks, screenshots, custom artifacts). */
  static final String ADDITIONAL_OUTPUT_DIR_PROPERTY =
      "composeai.simulator.instrument.additionalOutputDir";

  /**
   * When {@code "true"}, clears app SharedPreferences and private files between test classes,
   * approximating Android Test Orchestrator's {@code clearPackageData} isolation.
   */
  static final String CLEAR_PACKAGE_DATA_PROPERTY =
      "composeai.simulator.instrument.clearPackageData";

  static final String DEVICE_RUNNER =
      "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner";

  static boolean enabled() {
    String jar = System.getProperty(PROPERTY);
    return jar != null && !jar.isEmpty();
  }

  private static volatile Application activeApp;

  /** On the main thread, once the simulator loops: declares the test activities, starts the run. */
  static void start(Application app) {
    activeApp = app;
    declareTestActivities(app);
    disableAnimations(app);
    ClassLoader sandbox = Thread.currentThread().getContextClassLoader();
    DevicePlatform platform = new DevicePlatform(sandbox);
    // The main thread too: androidx.test looks its services up on whichever thread first needs
    // them, and Compose's test rule first reaches Espresso from the main thread.
    Thread.currentThread().setContextClassLoader(platform);
    Thread runner = new Thread(() -> runAndExit(sandbox), "Instr: androidx.test");
    runner.setContextClassLoader(platform);
    runner.setDaemon(true);
    runner.start();
    watchdog(runner);
    espressoClock(Looper.getMainLooper().getThread());
  }

  /**
   * Sets the three global animation scales to zero, matching {@code testOptions.animationsDisabled}
   * on an emulator so Espresso does not abort actions with "Animations or transitions are enabled
   * on the target device". Leaves {@code ValueAnimator}'s static duration scale alone because
   * setting it to 0f causes repeating Material/EditText animators to reschedule zero-delay
   * Choreographer frames indefinitely and trip Espresso's {@code AppNotIdleException}.
   */
  private static void disableAnimations(Application app) {
    android.content.ContentResolver cr = app.getContentResolver();
    android.provider.Settings.Global.putFloat(
        cr, android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 0f);
    android.provider.Settings.Global.putFloat(
        cr, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 0f);
    android.provider.Settings.Global.putFloat(
        cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
  }

  /**
   * Clears app-private SharedPreferences, files, and cache directories between test classes when
   * {@value #CLEAR_PACKAGE_DATA_PROPERTY} is enabled.
   */
  private static void clearPackageData(Application app) {
    File dataDir = app.getDataDir();
    if (dataDir == null) return;
    File prefsDir = new File(dataDir, "shared_prefs");
    File[] prefFiles = prefsDir.listFiles();
    if (prefFiles != null) {
      for (File f : prefFiles) {
        String name = f.getName();
        if (name.endsWith(".xml")) {
          String prefName = name.substring(0, name.length() - 4);
          app.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
              .edit()
              .clear()
              .commit();
        }
        f.delete();
      }
    }
    deleteChildren(app.getFilesDir());
    deleteChildren(app.getCacheDir());
  }

  private static void deleteChildren(File dir) {
    if (dir == null || !dir.isDirectory()) return;
    File[] children = dir.listFiles();
    if (children == null) return;
    for (File child : children) {
      if (child.isDirectory()) deleteChildren(child);
      child.delete();
    }
  }

  /**
   * Keeps the simulated clock running while Espresso holds the main thread. Espresso's device
   * {@code UiController} loops the main queue itself ({@code Interrogator}, through {@code
   * MessageQueue.next()}), and Robolectric's paused queue waits there for the simulated clock to
   * reach the next message, which only the simulator's own loop advances, and it is not running:
   * the main thread is inside Espresso. This advances the clock in real time for exactly that
   * case, so delayed messages (a frame, a click's pressed-state timeout) still come due; anywhere
   * else the simulator's loop keeps time, and two clocks would run it fast.
   */
  private static void espressoClock(Thread main) {
    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    java.util.concurrent.atomic.AtomicBoolean cycleQueued =
        new java.util.concurrent.atomic.AtomicBoolean();
    Thread ticker =
        new Thread(
            () -> {
              long last = System.nanoTime();
              long lastCycle = 0;
              while (true) {
                try {
                  Thread.sleep(2);
                } catch (InterruptedException e) {
                  return;
                }
                long now = System.nanoTime();
                if (inEspressoPoll(main)) {
                  ShadowSystemClock.advanceBy(Duration.ofNanos(now - last));
                  // The simulator's cycle too (the activity stack: a finish, a start, Back's
                  // task to the back), which its loop runs and Espresso's does not; else an
                  // Espresso check right after an action saw the stack from before it.
                  if (now - lastCycle >= CYCLE_NANOS && cycleQueued.compareAndSet(false, true)) {
                    lastCycle = now;
                    mainHandler.post(
                        () -> {
                          cycleQueued.set(false);
                          SimulatorHooks.cycleNow();
                        });
                  }
                }
                last = now;
              }
            },
            "Instr: espresso clock");
    ticker.setDaemon(true);
    ticker.start();
  }

  /** How often the simulator's cycle runs while Espresso holds the main thread: a frame. */
  private static final long CYCLE_NANOS = 16_000_000L;

  /** Whether [main] waits in the message queue's poll, called from Espresso's interrogator. */
  static boolean inEspressoPoll(Thread main) {
    Thread.State state = main.getState();
    if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
    boolean polling = false;
    for (StackTraceElement frame : main.getStackTrace()) {
      String owner = frame.getClassName();
      if (owner.endsWith("ShadowPausedMessageQueue") && "nativePollOnce".equals(frame.getMethodName())) {
        polling = true;
      } else if (owner.startsWith("org.robolectric.simulator.")) {
        // The simulator's loop polls too; it keeps its own time.
        return false;
      } else if (polling && owner.equals("androidx.test.espresso.base.Interrogator")) {
        return true;
      }
    }
    return false;
  }

  /** Seconds the whole run may take before its threads are dumped and the simulator exits 3. */
  static final String TIMEOUT_PROPERTY = "composeai.simulator.instrument.timeoutSeconds";

  private static void watchdog(Thread runner) {
    long seconds = Long.getLong(TIMEOUT_PROPERTY, 600);
    Thread watchdog =
        new Thread(
            () -> {
              try {
                runner.join(seconds * 1000);
              } catch (InterruptedException e) {
                return;
              }
              if (!runner.isAlive()) return;
              failed("timed out after " + seconds + " s");
              for (var entry : Thread.getAllStackTraces().entrySet()) {
                System.err.println("\n\"" + entry.getKey().getName() + "\"");
                for (StackTraceElement frame : entry.getValue()) {
                  System.err.println("    at " + frame);
                }
              }
              System.exit(3);
            },
            "Instr: watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  /**
   * The test thread's context class loader: the sandbox's, without Robolectric's androidx.test
   * service registrations ({@code LocalActivityInvoker}, {@code LocalUiController}, …), which
   * androidx.test finds through {@code ServiceLoader} and which drive a paused main looper from the
   * test thread. Without them it takes its device implementations: activities are started with
   * {@code startActivity}, which the simulator's {@code Navigator} carries out as a device's system
   * does, and Espresso synchronises with the looping main thread.
   */
  static final class DevicePlatform extends ClassLoader {
    private static final String SERVICES = "META-INF/services/androidx.test.";

    DevicePlatform(ClassLoader sandbox) {
      super(sandbox);
    }

    @Override
    public java.net.URL getResource(String name) {
      if (name.startsWith(SERVICES)) {
        try {
          Enumeration<java.net.URL> all = getResources(name);
          return all.hasMoreElements() ? all.nextElement() : null;
        } catch (IOException e) {
          return null;
        }
      }
      return super.getResource(name);
    }

    @Override
    public Enumeration<java.net.URL> getResources(String name) throws IOException {
      Enumeration<java.net.URL> all = super.getResources(name);
      if (!name.startsWith(SERVICES)) return all;
      List<java.net.URL> kept = new ArrayList<>();
      while (all.hasMoreElements()) {
        java.net.URL url = all.nextElement();
        if (!url.toString().contains("robolectric")) kept.add(url);
      }
      return java.util.Collections.enumeration(kept);
    }
  }


  /**
   * The test APK's activities, under the app's package: {@code ActivityScenario} starts
   * androidx.test's {@code BootstrapActivity}, which a device finds in the test APK's manifest.
   */
  private static void declareTestActivities(Application app) {
    String apk = System.getProperty(APK_PROPERTY);
    if (apk == null || apk.isEmpty()) return;
    try {
      Object parsed =
          ReflectionHelpers.callStaticMethod(
              ShadowPackageParser.class,
              "callParsePackage",
              ClassParameter.from(Path.class, Path.of(apk)));
      List<?> activities = ReflectionHelpers.getField(parsed, "activities");
      ShadowPackageManager manager = Shadow.extract(app.getPackageManager());
      for (Object activity : activities) {
        ActivityInfo info = new ActivityInfo(ReflectionHelpers.<ActivityInfo>getField(activity, "info"));
        info.packageName = app.getPackageName();
        info.applicationInfo = app.getApplicationInfo();
        info.processName = app.getApplicationInfo().processName;
        info.enabled = true;
        // Its theme and label are the test APK's resources, which the app's resource table does
        // not have (an id there names something else entirely); the framework's stand in.
        if (isAppResource(info.theme)) info.theme = frameworkTheme(info.name);
        if (isAppResource(info.labelRes)) info.labelRes = 0;
        manager.addOrUpdateActivity(info);
      }
    } catch (RuntimeException e) {
      System.err.println("instrument: cannot read the test APK's manifest: " + e);
    }
  }

  private static boolean isAppResource(int id) {
    return (id >>> 24) == 0x7f;
  }

  /**
   * androidx.test.core's {@code EmptyFloatingActivity} has to float (it pauses the activity under
   * test without stopping it); every other test activity gets a plain full-screen theme.
   */
  static int frameworkTheme(String activity) {
    return activity.endsWith("FloatingActivity")
        ? android.R.style.Theme_DeviceDefault_Light_Dialog
        : android.R.style.Theme_DeviceDefault_Light_NoActionBar;
  }

  private static void runAndExit(ClassLoader sandbox) {
    int exit;
    try {
      exit = run(sandbox);
    } catch (Throwable t) {
      failed(String.valueOf(t));
      t.printStackTrace();
      exit = 2;
    }
    System.err.flush();
    System.exit(exit);
  }

  /**
   * A run that could not get its tests going (or finish them): said on stderr as {@code am
   * instrument} says it, and, when {@value #JSON_PROPERTY} names a file, in the JSON report's
   * {@code error}, so an agent reading only the report still learns why there are no results.
   */
  static void failed(String reason) {
    System.err.println("INSTRUMENTATION_FAILED: " + reason);
    String json = System.getProperty(JSON_PROPERTY);
    if (json == null || json.isEmpty()) return;
    try {
      Path file = Path.of(json);
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          "{\"tests\":0,\"passed\":0,\"failed\":0,\"skipped\":0,\"error\":"
              + Reporter.jsonString(reason)
              + ",\"cases\":[]}\n");
    } catch (IOException | RuntimeException e) {
      System.err.println("instrument: cannot write " + json + ": " + e);
    }
  }

  static int run(ClassLoader sandbox) throws Exception {
    // AndroidJUnit4 otherwise picks Robolectric's runner whenever it is on the class path.
    if (System.getProperty("android.junit.runner") == null) {
      System.setProperty("android.junit.runner", DEVICE_RUNNER);
    }
    String additionalOutputDir = System.getProperty(ADDITIONAL_OUTPUT_DIR_PROPERTY);
    if (additionalOutputDir != null && !additionalOutputDir.isEmpty()) {
      Path dir = Path.of(additionalOutputDir);
      Files.createDirectories(dir);
      System.setProperty("android.test.additionalTestOutputDir", dir.toAbsolutePath().toString());
      System.setProperty("androidx.test.additionalTestOutputDir", dir.toAbsolutePath().toString());
    }
    Selection selection = Selection.parse(System.getProperty(TESTS_PROPERTY));
    List<Class<?>> classes = new ArrayList<>();
    if (selection.classes.isEmpty()) {
      for (String name : testClasses(new File(System.getProperty(PROPERTY)))) {
        classes.add(Class.forName(name, false, sandbox));
      }
    } else {
      // Named classes are loaded by name, not looked for: discovery passes over the libraries a
      // test APK bundles by package, and an app's tests may share one (com.google.…).
      for (String name : selection.classes) {
        try {
          classes.add(Class.forName(name, false, sandbox));
        } catch (ClassNotFoundException e) {
          failed("no test class " + name);
          return 1;
        }
      }
    }
    System.err.println("instrument: " + classes.size() + " test class(es)");
    if (classes.isEmpty()) {
      failed("no tests found");
      return 1;
    }
    // Each class's runner is built once, then validated, filtered and run: building one calls a
    // parameterised class's @Parameters factory, which must not run twice or see other cases.
    List<org.junit.runner.Runner> runners = new ArrayList<>();
    Description all = Description.createSuiteDescription("selected");
    for (Class<?> type : classes) {
      org.junit.runner.Runner runner = Request.aClass(type).getRunner();
      runners.add(runner);
      all.addChild(runner.getDescription());
    }
    List<String> missing = selection.missingMethods(all);
    if (!missing.isEmpty()) {
      failed("no such test " + String.join(", ", missing));
      return 1;
    }
    Shard shard = Shard.parse(System.getProperty(SHARD_PROPERTY));
    JUnitCore core = new JUnitCore();
    Reporter reporter = new Reporter();
    core.addListener(reporter);
    // One request per class, each filtered only by its own `Class#method` selectors: a suite named
    // whole keeps every test under it, whatever classes those belong to.
    boolean clearBetweenClasses = Boolean.getBoolean(CLEAR_PACKAGE_DATA_PROPERTY);
    int runCount = 0;
    int failures = 0;
    long millis = 0;
    int classesRun = 0;
    for (int i = 0; i < classes.size(); i++) {
      org.junit.runner.Runner runner = runners.get(i);
      Filter filter = selection.filterFor(classes.get(i).getName());
      Filter device = DeviceFilter.forThisDevice();
      filter = filter == null ? device : filter.intersect(device);
      if (shard != null) filter = shard.and(filter);
      // A class with none of its tests left is left out rather than filtered to nothing, which
      // JUnit reports as a failure ("No tests found matching").
      if (!runsAny(runner.getDescription(), filter)) continue;
      try {
        filter.apply(runner);
      } catch (org.junit.runner.manipulation.NoTestsRemainException e) {
        continue;
      }
      if (clearBetweenClasses && classesRun > 0 && activeApp != null) {
        MainThread.get(
            () -> {
              clearPackageData(activeApp);
              return null;
            });
      }
      classesRun++;
      Result result = core.run(Request.runner(runner));
      runCount += result.getRunCount();
      failures += result.getFailureCount();
      millis += result.getRunTime();
    }
    if (shard != null) {
      System.err.println("instrument: shard " + shard + ": " + runCount + " test(s)");
    }
    String coverageDir = System.getProperty(COVERAGE_DIR_PROPERTY);
    if (coverageDir != null && !coverageDir.isEmpty()) {
      dumpJacocoCoverage(sandbox, Path.of(coverageDir), shard);
    }
    String report = System.getProperty(REPORT_PROPERTY);
    if (report != null && !report.isEmpty()) {
      reporter.writeXml(Path.of(report), millis);
    }
    String json = System.getProperty(JSON_PROPERTY);
    if (json != null && !json.isEmpty()) reporter.writeJson(Path.of(json), millis);
    int skipped = reporter.skipped();
    if (failures == 0) {
      System.err.println(
          "\nOK (" + runCount + " tests" + (skipped > 0 ? ", " + skipped + " skipped" : "") + ")");
      return 0;
    }
    System.err.println("\nFAILURES!!!\nTests run: " + runCount + ",  Failures: " + failures);
    return 1;
  }

  /**
   * Dumps JaCoCo execution data ({@code .ec}) into {@code coverageDir} when JaCoCo runtime classes
   * are present in the instrumented APK or JVM. AGP's {@code JacocoReportTask} reads all {@code
   * *.ec} files from its managed-device coverage output directory.
   */
  static void dumpJacocoCoverage(ClassLoader sandbox, Path coverageDir, Shard shard) {
    try {
      Class<?> rtClass = null;
      try {
        rtClass = Class.forName("org.jacoco.agent.rt.RT", false, sandbox);
      } catch (ClassNotFoundException ignored) {
        try {
          rtClass = Class.forName("org.jacoco.agent.rt.RT");
        } catch (ClassNotFoundException ignoredToo) {
          return;
        }
      }
      Object agent = rtClass.getMethod("getAgent").invoke(null);
      if (agent == null) return;
      byte[] data = (byte[]) agent.getClass().getMethod("getExecutionData", boolean.class).invoke(agent, false);
      if (data == null || data.length == 0) return;
      Files.createDirectories(coverageDir);
      String fileName = shard != null ? "coverage-shard-" + shard.index + ".ec" : "coverage.ec";
      Files.write(coverageDir.resolve(fileName), data);
    } catch (Throwable t) {
      System.err.println("instrument: could not dump JaCoCo coverage: " + t);
    }
  }

  /**
   * The classes of [jar] JUnit would run: concrete, with a {@code @RunWith} or a {@code @Test}
   * method. Read with ASM, without loading anything: the test APK also carries androidx.test,
   * Espresso and the rest, which need not be touched.
   */
  static List<String> testClasses(File jar) throws IOException {
    java.util.Map<String, TestClassScan> scans = new java.util.HashMap<>();
    try (JarFile file = new JarFile(jar)) {
      Enumeration<JarEntry> entries = file.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.endsWith(".class") || skipped(name)) continue;
        try (InputStream in = file.getInputStream(entry)) {
          TestClassScan scan = new TestClassScan();
          new ClassReader(in)
              .accept(
                  scan, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
          scans.put(scan.name, scan);
        }
      }
    }
    List<String> sorted = new ArrayList<>();
    for (TestClassScan scan : scans.values()) {
      // A suite is left out: its member classes are found on their own, and running both would
      // run every member test twice. Name a suite with --tests to run it as one.
      if (scan.concrete && !isSuite(scan, scans) && inheritsTests(scan, scans)) {
        sorted.add(scan.name.replace('/', '.'));
      }
    }
    sorted.sort(null);
    return sorted;
  }

  /**
   * Whether [scan] or a superclass in the same jar has runnable tests: a {@code @Test} method, a
   * custom {@code @RunWith} (other than the standard JUnit4 / AndroidJUnit4 method runners, which
   * require at least one {@code @Test} method), or a JUnit 3 {@code TestCase} subclass with at
   * least one {@code public void test*()} method. Concrete helper base classes with no test methods
   * (e.g. {@code open class BaseViewTest : BaseAndroidTest()}) are skipped as AndroidJUnitRunner
   * skips them, rather than failing with {@code InvalidTestClassError: No runnable methods}.
   */
  private static boolean inheritsTests(
      TestClassScan scan, java.util.Map<String, TestClassScan> scans) {
    Set<String> seen = new java.util.HashSet<>();
    boolean hasTestMethod = false;
    boolean hasJunit3TestMethod = false;
    boolean isJunit3TestCase = false;
    String nearestRunner = null;
    for (TestClassScan at = scan; at != null && seen.add(at.name); at = scans.get(at.superName)) {
      if (at.testMethod) hasTestMethod = true;
      if (at.junit3TestMethod) hasJunit3TestMethod = true;
      if (at.runWith && nearestRunner == null) nearestRunner = at.runner != null ? at.runner : "";
      // JUnit 3: a TestCase subclass, which has neither annotation. TestCase itself is JUnit's,
      // so it is not among the scanned classes: it is recognised by name.
      if (JUNIT3_TEST_CASE.equals(at.superName)) isJunit3TestCase = true;
    }
    if (hasTestMethod) return true;
    if (isJunit3TestCase && hasJunit3TestMethod) return true;
    return nearestRunner != null && !STANDARD_METHOD_RUNNERS.contains(nearestRunner);
  }

  static final String JUNIT3_TEST_CASE = "junit/framework/TestCase";

  /** Standard JUnit4 / AndroidJUnit4 runners that require at least one {@code @Test} method. */
  static final Set<String> STANDARD_METHOD_RUNNERS =
      Set.of(
          "androidx/test/ext/junit/runners/AndroidJUnit4",
          "androidx/test/runner/AndroidJUnit4",
          "androidx/test/internal/runner/junit4/AndroidJUnit4ClassRunner",
          "org/junit/runners/JUnit4",
          "org/junit/runners/BlockJUnit4ClassRunner");

  /** JUnit's runners that only group other classes. */
  static final Set<String> SUITE_RUNNERS =
      Set.of("org/junit/runners/Suite", "org/junit/experimental/categories/Categories");

  /** Whether [scan], or the nearest superclass in the jar with a {@code @RunWith}, is a suite. */
  private static boolean isSuite(TestClassScan scan, java.util.Map<String, TestClassScan> scans) {
    Set<String> seen = new java.util.HashSet<>();
    for (TestClassScan at = scan; at != null && seen.add(at.name); at = scans.get(at.superName)) {
      if (at.runWith) return at.runner != null && SUITE_RUNNERS.contains(at.runner);
    }
    return false;
  }

  /**
   * Libraries a test APK bundles: never the suite's own tests. Google's libraries by their own
   * packages, not all of {@code com.google}: Google's apps and samples keep their tests there too
   * ({@code com.google.samples.apps.nowinandroid}), and a whole-prefix skip found none of them.
   */
  static boolean skipped(String entry) {
    for (String prefix : LIBRARY_PREFIXES) {
      if (entry.startsWith(prefix)) return true;
    }
    return false;
  }

  static final String[] LIBRARY_PREFIXES = {
    "androidx/", "android/", "junit/", "org/junit/", "org/hamcrest/", "kotlin/", "kotlinx/",
    "org/jetbrains/", "javax/", "META-INF/", "dagger/",
    "com/google/common/", "com/google/thirdparty/", "com/google/errorprone/",
    "com/google/j2objc/", "com/google/protobuf/", "com/google/gson/", "com/google/auto/",
    "com/google/dagger/", "com/google/firebase/", "com/google/accompanist/",
    "com/google/testing/", "com/google/truth/", "com/google/android/material/",
    "com/google/android/gms/", "com/google/android/play/", "com/google/android/datatransport/",
    "com/google/android/libraries/", "com/google/android/apps/common/testing/",
    "com/google/android/odml/", "com/google/mlkit/", "com/google/crypto/",
  };

  static final class TestClassScan extends ClassVisitor {
    String name;
    String superName;
    boolean concrete;
    boolean runWith;
    /** The {@code @RunWith} runner's internal name, when there is one. */
    String runner;
    boolean testMethod;
    boolean junit3TestMethod;

    TestClassScan() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      this.name = name;
      this.superName = superName;
      concrete =
          (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0
              && (access & Opcodes.ACC_PUBLIC) != 0;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      if (!"Lorg/junit/runner/RunWith;".equals(descriptor)) return null;
      runWith = true;
      return new AnnotationVisitor(Opcodes.ASM9) {
        @Override
        public void visit(String name, Object value) {
          if ("value".equals(name) && value instanceof org.objectweb.asm.Type type) {
            runner = type.getInternalName();
          }
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if ((access & Opcodes.ACC_PUBLIC) != 0
          && (access & Opcodes.ACC_STATIC) == 0
          && (access & Opcodes.ACC_ABSTRACT) == 0
          && name.startsWith("test")
          && "()V".equals(descriptor)) {
        junit3TestMethod = true;
      }
      return new MethodVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
          if ("Lorg/junit/Test;".equals(annotation)) testMethod = true;
          return null;
        }
      };
    }
  }

  /**
   * The tests this device runs, as AndroidJUnitRunner filters them before any starts: those whose
   * {@code @SdkSuppress} (on the method, else the class) admits the simulated API level or its
   * code name, and none marked {@code @RequiresDevice}, a physical device's. The annotations are
   * matched by name: they are the test APK's own classes.
   */
  static final class DeviceFilter extends Filter {
    static final String SDK_SUPPRESS = "androidx.test.filters.SdkSuppress";
    static final String REQUIRES_DEVICE = "androidx.test.filters.RequiresDevice";

    private final int sdk;
    private final String codeName;

    DeviceFilter(int sdk, String codeName) {
      this.sdk = sdk;
      this.codeName = codeName;
    }

    /** The simulated device's level; one that admits every test where there is no framework. */
    static DeviceFilter forThisDevice() {
      try {
        return new DeviceFilter(
            android.os.Build.VERSION.SDK_INT, android.os.Build.VERSION.CODENAME);
      } catch (LinkageError e) {
        return new DeviceFilter(UNKNOWN, null);
      }
    }

    private static final int UNKNOWN = -1;

    @Override
    public boolean shouldRun(Description description) {
      if (!description.isTest()) {
        for (Description child : description.getChildren()) {
          if (shouldRun(child)) return true;
        }
        return description.getChildren().isEmpty();
      }
      if (sdk == UNKNOWN) return true;
      Class<?> type = description.getTestClass();
      if (named(description.getAnnotations(), REQUIRES_DEVICE) != null) return false;
      if (type != null && named(Arrays.asList(type.getAnnotations()), REQUIRES_DEVICE) != null) {
        return false;
      }
      java.lang.annotation.Annotation suppress = named(description.getAnnotations(), SDK_SUPPRESS);
      if (suppress == null && type != null) {
        suppress = named(Arrays.asList(type.getAnnotations()), SDK_SUPPRESS);
      }
      return suppress == null || admits(suppress);
    }

    /** As androidx.test's {@code SdkSuppressFilter}: the range, less excluded levels, or the code name. */
    boolean admits(java.lang.annotation.Annotation suppress) {
      int min = intValue(suppress, "minSdkVersion", 1);
      int max = intValue(suppress, "maxSdkVersion", Integer.MAX_VALUE);
      boolean excluded = false;
      Object excludedSdks = value(suppress, "excludedSdks");
      if (excludedSdks instanceof int[] levels) {
        for (int level : levels) excluded |= level == sdk;
      }
      if (sdk >= min && sdk <= max && !excluded) return true;
      Object name = value(suppress, "codeName");
      return name != null && name.equals(codeName);
    }

    private static java.lang.annotation.Annotation named(
        java.util.Collection<java.lang.annotation.Annotation> annotations, String name) {
      for (java.lang.annotation.Annotation a : annotations) {
        if (a.annotationType().getName().equals(name)) return a;
      }
      return null;
    }

    private static int intValue(java.lang.annotation.Annotation a, String name, int otherwise) {
      Object v = value(a, name);
      return v instanceof Integer i ? i : otherwise;
    }

    private static Object value(java.lang.annotation.Annotation a, String name) {
      try {
        java.lang.reflect.Method m = a.annotationType().getMethod(name);
        m.setAccessible(true);
        return m.invoke(a);
      } catch (ReflectiveOperationException | RuntimeException e) {
        return null;
      }
    }

    @Override
    public String describe() {
      return "SdkSuppress / RequiresDevice at API " + sdk;
    }
  }

  /** Whether [filter] keeps any test under [description]. */
  static boolean runsAny(Description description, Filter filter) {
    if (description.isTest()) return filter.shouldRun(description);
    for (Description child : description.getChildren()) {
      if (runsAny(child, filter)) return true;
    }
    return false;
  }

  /** {@value #SHARD_PROPERTY}: which of N simulators this is, and so which tests are its. */
  static final class Shard extends Filter {
    final int index;
    final int count;

    Shard(int index, int count) {
      if (count < 1 || index < 0 || index >= count) {
        throw new IllegalArgumentException("shard " + index + "/" + count);
      }
      this.index = index;
      this.count = count;
    }

    /** {@code index/count}, or null for no sharding. */
    static Shard parse(String spec) {
      if (spec == null || spec.isBlank()) return null;
      int slash = spec.indexOf('/');
      if (slash < 0) throw new IllegalArgumentException(SHARD_PROPERTY + " takes index/count");
      return new Shard(
          Integer.parseInt(spec.substring(0, slash).trim()),
          Integer.parseInt(spec.substring(slash + 1).trim()));
    }

    /** The shard a test belongs to: by its display name, stable across JVMs and runs. */
    static int of(Description test, int count) {
      return Math.floorMod(test.getDisplayName().hashCode(), count);
    }

    @Override
    public boolean shouldRun(Description description) {
      if (description.isTest()) return of(description, count) == index;
      for (Description child : description.getChildren()) {
        if (shouldRun(child)) return true;
      }
      return false;
    }

    /** This shard's tests that [other] (null: every test) also keeps. */
    Filter and(Filter other) {
      return other == null ? this : intersect(other);
    }

    @Override
    public String describe() {
      return "shard " + this;
    }

    @Override
    public String toString() {
      return index + "/" + count;
    }
  }

  /** {@value #TESTS_PROPERTY}: classes, and methods of classes, to run. Empty runs everything. */
  static final class Selection {
    final Set<String> classes = new LinkedHashSet<>();
    final Set<String> methods = new LinkedHashSet<>();
    /** Classes named on their own, not only through one of their methods: every test runs. */
    final Set<String> wholeClasses = new LinkedHashSet<>();

    static Selection parse(String spec) {
      Selection selection = new Selection();
      if (spec == null) return selection;
      for (String part : spec.split(",")) {
        String test = part.trim();
        if (test.isEmpty()) continue;
        int hash = test.indexOf('#');
        if (hash < 0) {
          selection.classes.add(test);
          selection.wholeClasses.add(test);
        } else {
          selection.classes.add(test.substring(0, hash));
          selection.methods.add(test);
        }
      }
      return selection;
    }

    boolean includesClass(String name) {
      return classes.isEmpty() || classes.contains(name);
    }

    boolean hasMethods() {
      return !methods.isEmpty();
    }

    /**
     * The {@code Class#method} selectors that name no test under [suite]: each one asked for must
     * run, or the run fails rather than pass on less than it was asked. A parameterised test
     * matches by its method name ({@code method[0]}).
     */
    List<String> missingMethods(Description suite) {
      Set<String> present = new java.util.HashSet<>();
      collect(suite, present);
      List<String> missing = new ArrayList<>();
      for (String method : methods) {
        if (!present.contains(method)) missing.add(method);
      }
      return missing;
    }

    /** A parameterised test's method name without its parameters: {@code each[0]} is {@code each}. */
    static String baseMethod(String method) {
      int bracket = method.indexOf('[');
      return bracket > 0 ? method.substring(0, bracket) : method;
    }

    private static void collect(Description description, Set<String> into) {
      if (description.isTest() && description.getMethodName() != null) {
        into.add(description.getClassName() + "#" + baseMethod(description.getMethodName()));
      }
      for (Description child : description.getChildren()) collect(child, into);
    }

    /**
     * The filter for class [className]'s request: null when it was named whole (or nothing was
     * named), so every test under it runs; otherwise only its selected methods, every parameter of
     * a parameterised one included.
     */
    Filter filterFor(String className) {
      if (wholeClasses.contains(className)) return null;
      Set<String> wanted = new java.util.HashSet<>();
      for (String m : methods) {
        if (m.startsWith(className + "#")) wanted.add(m.substring(className.length() + 1));
      }
      if (wanted.isEmpty()) return null;
      return new Filter() {
        @Override
        public boolean shouldRun(Description description) {
          if (description.isSuite()) {
            for (Description child : description.getChildren()) {
              if (shouldRun(child)) return true;
            }
            return description.getChildren().isEmpty();
          }
          String method = description.getMethodName();
          return method != null && wanted.contains(baseMethod(method));
        }

        @Override
        public String describe() {
          return className + " " + wanted;
        }
      };
    }
  }

  /** Each test's outcome as it happens, and the XML report at the end. */
  static final class Reporter extends RunListener {
    private final List<Case> cases = new ArrayList<>();
    private Case current;

    static final class Case {
      final Description description;
      final long startNanos = System.nanoTime();
      long nanos;
      Failure failure;
      boolean ignored;

      Case(Description description) {
        this.description = description;
      }
    }

    @Override
    public void testStarted(Description description) {
      current = new Case(description);
      cases.add(current);
      System.err.println("instrument: " + description.getDisplayName() + " ...");
    }

    @Override
    public void testFinished(Description description) {
      if (current == null) return;
      current.nanos = System.nanoTime() - current.startNanos;
      System.err.println(
          "instrument: "
              + description.getDisplayName()
              + (current.failure != null ? " FAILED" : current.ignored ? " SKIPPED" : " PASSED")
              + " ("
              + current.nanos / 1_000_000
              + " ms)");
      current = null;
    }

    @Override
    public void testFailure(Failure failure) {
      Case target = current;
      if (target == null) {
        // A class-level failure (a @BeforeClass, a runner that could not start): its own case.
        target = new Case(failure.getDescription());
        cases.add(target);
      }
      target.failure = failure;
      System.err.println("instrument: " + failure.getTestHeader() + "\n" + failure.getTrace());
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
      Case target = current;
      if (target == null) {
        // An assumption failing in @BeforeClass skips the whole class: its own case.
        target = new Case(failure.getDescription());
        cases.add(target);
        System.err.println("instrument: " + failure.getTestHeader() + " SKIPPED");
      }
      target.ignored = true;
    }

    /** Cases that failed, from what was recorded rather than JUnit's counts. */
    int failures() {
      int n = 0;
      for (Case c : cases) if (c.failure != null) n++;
      return n;
    }

    /**
     * Cases skipped: {@code @Ignore}d and assumption failures both, which JUnit's {@code
     * getIgnoreCount()} does not add up (it leaves assumption failures out).
     */
    int skipped() {
      int n = 0;
      for (Case c : cases) if (c.ignored && c.failure == null) n++;
      return n;
    }

    @Override
    public void testIgnored(Description description) {
      Case ignored = new Case(description);
      ignored.ignored = true;
      cases.add(ignored);
    }

    void writeXml(Path file, long millis) throws IOException {
      int failures = failures();
      int ignored = skipped();
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      StringWriter xml = new StringWriter();
      xml.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      xml.write(
          "<testsuite name=\"simulator-instrumentation\" tests=\""
              + cases.size()
              + "\" failures=\""
              + failures
              + "\" skipped=\""
              + ignored
              + "\" time=\""
              + millis / 1000.0
              + "\">\n");
      for (Case c : cases) {
        xml.write(
            "  <testcase classname=\""
                + escape(c.description.getClassName())
                + "\" name=\""
                + escape(String.valueOf(c.description.getMethodName()))
                + "\" time=\""
                + c.nanos / 1e9
                + "\"");
        if (c.failure == null && !c.ignored) {
          xml.write("/>\n");
          continue;
        }
        xml.write(">\n");
        if (c.ignored) xml.write("    <skipped/>\n");
        if (c.failure != null) {
          xml.write(
              "    <failure message=\""
                  + escape(String.valueOf(c.failure.getMessage()))
                  + "\">"
                  + escape(c.failure.getTrace())
                  + "</failure>\n");
        }
        xml.write("  </testcase>\n");
      }
      xml.write("</testsuite>\n");
      try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        out.write(xml.toString());
      }
    }

    /**
     * The results as one JSON object: totals, then each case's class, method, status ({@code
     * passed}, {@code failed} or {@code skipped}), duration and, for a failure, its message and
     * stack trace. Written by hand: {@code org.json} here is the framework's, and this runs
     * after the last test whatever state the app is in.
     */
    void writeJson(Path file, long millis) throws IOException {
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      StringBuilder json = new StringBuilder();
      int failed = failures();
      int skipped = skipped();
      json.append("{\"tests\":").append(cases.size())
          .append(",\"passed\":").append(cases.size() - failed - skipped)
          .append(",\"failed\":").append(failed)
          .append(",\"skipped\":").append(skipped)
          .append(",\"durationMs\":").append(millis)
          .append(",\"cases\":[");
      for (int i = 0; i < cases.size(); i++) {
        Case c = cases.get(i);
        if (i > 0) json.append(',');
        String status = c.failure != null ? "failed" : c.ignored ? "skipped" : "passed";
        json.append("{\"class\":").append(jsonString(c.description.getClassName()))
            .append(",\"method\":").append(jsonString(c.description.getMethodName()))
            .append(",\"status\":\"").append(status).append('"')
            .append(",\"durationMs\":").append(c.nanos / 1_000_000);
        if (c.failure != null) {
          json.append(",\"message\":").append(jsonString(c.failure.getMessage()))
              .append(",\"trace\":").append(jsonString(c.failure.getTrace()));
        }
        json.append('}');
      }
      json.append("]}\n");
      try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        out.write(json.toString());
      }
    }

    static String jsonString(String text) {
      if (text == null) return "null";
      StringBuilder out = new StringBuilder(text.length() + 2).append('"');
      for (char ch : text.toCharArray()) {
        switch (ch) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
          }
        }
      }
      return out.append('"').toString();
    }

    private static String escape(String text) {
      StringBuilder out = new StringBuilder(text.length());
      for (char ch : text.toCharArray()) {
        switch (ch) {
          case '&' -> out.append("&amp;");
          case '<' -> out.append("&lt;");
          case '>' -> out.append("&gt;");
          case '"' -> out.append("&quot;");
          default -> {
            if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') out.append(' ');
            else out.append(ch);
          }
        }
      }
      return out.toString();
    }
  }

  private InstrumentationRun() {}
}
