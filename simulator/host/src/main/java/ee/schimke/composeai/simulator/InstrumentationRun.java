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

  static final String DEVICE_RUNNER =
      "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner";

  static boolean enabled() {
    String jar = System.getProperty(PROPERTY);
    return jar != null && !jar.isEmpty();
  }

  /** On the main thread, once the simulator loops: declares the test activities, starts the run. */
  static void start(Application app) {
    declareTestActivities(app);
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
   * Keeps the simulated clock running while Espresso holds the main thread. Espresso's device
   * {@code UiController} loops the main queue itself ({@code Interrogator}, through {@code
   * MessageQueue.next()}), and Robolectric's paused queue waits there for the simulated clock to
   * reach the next message, which only the simulator's own loop advances, and it is not running:
   * the main thread is inside Espresso. This advances the clock in real time for exactly that
   * case, so delayed messages (a frame, a click's pressed-state timeout) still come due; anywhere
   * else the simulator's loop keeps time, and two clocks would run it fast.
   */
  private static void espressoClock(Thread main) {
    Thread ticker =
        new Thread(
            () -> {
              long last = System.nanoTime();
              while (true) {
                try {
                  Thread.sleep(2);
                } catch (InterruptedException e) {
                  return;
                }
                long now = System.nanoTime();
                if (inEspressoPoll(main)) {
                  ShadowSystemClock.advanceBy(Duration.ofNanos(now - last));
                }
                last = now;
              }
            },
            "Instr: espresso clock");
    ticker.setDaemon(true);
    ticker.start();
  }

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
              System.err.println("INSTRUMENTATION_FAILED: timed out after " + seconds + " s");
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
      System.err.println("INSTRUMENTATION_FAILED: " + t);
      t.printStackTrace();
      exit = 2;
    }
    System.err.flush();
    System.exit(exit);
  }

  static int run(ClassLoader sandbox) throws Exception {
    // AndroidJUnit4 otherwise picks Robolectric's runner whenever it is on the class path.
    if (System.getProperty("android.junit.runner") == null) {
      System.setProperty("android.junit.runner", DEVICE_RUNNER);
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
          System.err.println("INSTRUMENTATION_FAILED: no test class " + name);
          return 1;
        }
      }
    }
    System.err.println("instrument: " + classes.size() + " test class(es)");
    if (classes.isEmpty()) {
      System.err.println("INSTRUMENTATION_FAILED: no tests found");
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
      System.err.println("INSTRUMENTATION_FAILED: no such test " + String.join(", ", missing));
      return 1;
    }
    JUnitCore core = new JUnitCore();
    Reporter reporter = new Reporter();
    core.addListener(reporter);
    // One request per class, each filtered only by its own `Class#method` selectors: a suite named
    // whole keeps every test under it, whatever classes those belong to.
    int runCount = 0;
    int failures = 0;
    long millis = 0;
    for (int i = 0; i < classes.size(); i++) {
      Request one = Request.runner(runners.get(i));
      Filter filter = selection.filterFor(classes.get(i).getName());
      if (filter != null) one = one.filterWith(filter);
      Result result = core.run(one);
      runCount += result.getRunCount();
      failures += result.getFailureCount();
      millis += result.getRunTime();
    }
    String report = System.getProperty(REPORT_PROPERTY);
    if (report != null && !report.isEmpty()) {
      reporter.writeXml(Path.of(report), millis);
    }
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
   * Whether [scan] or a superclass in the same jar has a {@code @Test} method or {@code @RunWith}
   * (which is {@code @Inherited}): a concrete class whose tests all come from an abstract base still
   * runs them, as JUnit runs it.
   */
  private static boolean inheritsTests(
      TestClassScan scan, java.util.Map<String, TestClassScan> scans) {
    Set<String> seen = new java.util.HashSet<>();
    for (TestClassScan at = scan; at != null && seen.add(at.name); at = scans.get(at.superName)) {
      if (at.runWith || at.testMethod) return true;
      // JUnit 3: a TestCase subclass, which has neither annotation. TestCase itself is JUnit's,
      // so it is not among the scanned classes: it is recognised by name.
      if (JUNIT3_TEST_CASE.equals(at.superName)) return true;
    }
    return false;
  }

  static final String JUNIT3_TEST_CASE = "junit/framework/TestCase";

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

  /** Libraries a test APK bundles: never the suite's own tests. */
  private static boolean skipped(String entry) {
    for (String prefix :
        new String[] {
          "androidx/", "android/", "junit/", "org/junit/", "org/hamcrest/", "kotlin/", "kotlinx/",
          "com/google/", "org/jetbrains/", "javax/", "META-INF/"
        }) {
      if (entry.startsWith(prefix)) return true;
    }
    return false;
  }

  static final class TestClassScan extends ClassVisitor {
    String name;
    String superName;
    boolean concrete;
    boolean runWith;
    /** The {@code @RunWith} runner's internal name, when there is one. */
    String runner;
    boolean testMethod;

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
      return new MethodVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
          if ("Lorg/junit/Test;".equals(annotation)) testMethod = true;
          return null;
        }
      };
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
