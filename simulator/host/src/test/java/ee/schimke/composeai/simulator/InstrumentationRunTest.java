package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Test;
import org.junit.runner.Description;

public class InstrumentationRunTest {

  /** Not a test: no @Test method, no @RunWith. */
  public static class Helper {
    public void run() {}
  }

  /** Abstract: JUnit could not run it. */
  public abstract static class AbstractBase {
    @Test
    public void inherited() {}
  }

  /** Concrete, its only tests inherited from [AbstractBase]: it runs them. */
  public static class InheritsTests extends AbstractBase {}

  /** JUnit 3: no annotations at all. */
  public static class LegacyCase extends junit.framework.TestCase {
    public void testSomething() {}
  }

  /** A suite of [InheritsTests]: left to --tests, or that test would run twice. */
  @org.junit.runner.RunWith(org.junit.runners.Suite.class)
  @org.junit.runners.Suite.SuiteClasses(InheritsTests.class)
  public static class AllTests {}

  @Test
  public void findsConcreteClassesWithTestMethodsOnly() throws IOException {
    File jar = Files.createTempFile("tests", ".jar").toFile();
    jar.deleteOnExit();
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
      for (Class<?> type : List.of(
              InstrumentationRunTest.class,
              Helper.class,
              AbstractBase.class,
              InheritsTests.class,
              LegacyCase.class,
              AllTests.class)) {
        copyClass(type, out);
      }
      // A library the test APK bundles is never scanned, whatever it declares.
      out.putNextEntry(new JarEntry("org/junit/Assert.class"));
      out.closeEntry();
    }

    assertEquals(
        List.of(
            InstrumentationRunTest.class.getName(),
            InheritsTests.class.getName(),
            LegacyCase.class.getName()),
        InstrumentationRun.testClasses(jar));
  }

  @Test
  public void emptySelectionRunsEverything() {
    InstrumentationRun.Selection all = InstrumentationRun.Selection.parse(null);

    assertTrue(all.includesClass("a.B"));
    assertFalse(all.hasMethods());
  }

  @Test
  public void selectionNamesClassesAndMethods() {
    InstrumentationRun.Selection selection =
        InstrumentationRun.Selection.parse("a.Whole, a.Part#one");

    assertTrue(selection.includesClass("a.Whole"));
    assertTrue(selection.includesClass("a.Part"));
    assertFalse(selection.includesClass("a.Other"));
    assertNull(selection.filterFor("a.Whole"));
    assertTrue(selection.filterFor("a.Part").shouldRun(test("a.Part", "one")));
    assertFalse(selection.filterFor("a.Part").shouldRun(test("a.Part", "two")));
  }

  @Test
  public void filterKeepsSuitesWithASelectedChild() {
    InstrumentationRun.Selection selection = InstrumentationRun.Selection.parse("a.Part#one");
    Description suite = Description.createSuiteDescription("a.Part");
    suite.addChild(Description.createTestDescription("a.Part", "one"));
    suite.addChild(Description.createTestDescription("a.Part", "two"));

    assertTrue(selection.filterFor("a.Part").shouldRun(suite));
    assertTrue(selection.filterFor("a.Part").shouldRun(Description.createTestDescription("a.Part", "one")));
    assertFalse(selection.filterFor("a.Part").shouldRun(Description.createTestDescription("a.Part", "two")));
  }

  @Test
  public void reportsSelectedMethodsThatNameNoTest() {
    InstrumentationRun.Selection selection =
        InstrumentationRun.Selection.parse("a.Part#one,a.Part#typo,a.Param#each");
    Description suite = Description.createSuiteDescription("all");
    Description part = Description.createSuiteDescription("a.Part");
    part.addChild(Description.createTestDescription("a.Part", "one"));
    suite.addChild(part);
    suite.addChild(Description.createTestDescription("a.Param", "each[0]"));

    assertEquals(List.of("a.Part#typo"), selection.missingMethods(suite));
  }

  @Test
  public void selectedParameterisedMethodRunsEveryParameter() {
    InstrumentationRun.Selection selection = InstrumentationRun.Selection.parse("a.Param#each");

    assertTrue(selection.filterFor("a.Param").shouldRun(test("a.Param", "each[0]")));
    assertTrue(selection.filterFor("a.Param").shouldRun(test("a.Param", "each[1]")));
    assertFalse(selection.filterFor("a.Param").shouldRun(test("a.Param", "other[0]")));
  }

  @Test
  public void floatingTestActivitiesKeepFloating() {
    assertEquals(
        android.R.style.Theme_DeviceDefault_Light_Dialog,
        InstrumentationRun.frameworkTheme(
            "androidx.test.core.app.InstrumentationActivityInvoker$EmptyFloatingActivity"));
    assertEquals(
        android.R.style.Theme_DeviceDefault_Light_NoActionBar,
        InstrumentationRun.frameworkTheme(
            "androidx.test.core.app.InstrumentationActivityInvoker$EmptyActivity"));
  }

  @Test
  public void wholeSuiteKeepsItsTestsBesideAMethodSelector() {
    InstrumentationRun.Selection selection =
        InstrumentationRun.Selection.parse("a.AllTests,a.OtherTest#one");

    // The suite's request is unfiltered, so tests of other classes under it all run.
    assertNull(selection.filterFor("a.AllTests"));
    assertTrue(selection.filterFor("a.OtherTest").shouldRun(test("a.OtherTest", "one")));
    assertFalse(selection.filterFor("a.OtherTest").shouldRun(test("a.OtherTest", "two")));
  }

  @Test
  public void wholeClassBesideOneOfItsMethodsRunsUnfiltered() {
    InstrumentationRun.Selection selection = InstrumentationRun.Selection.parse("a.Part,a.Part#one");

    assertNull(selection.filterFor("a.Part"));
  }

  /** Fixture: one test skipped by an assumption, one passing. */
  public static class AssumingCase {
    @Test
    public void skipped() {
      org.junit.Assume.assumeTrue(false);
    }

    @Test
    public void passes() {}
  }

  /** Fixture: the whole class skipped in @BeforeClass. */
  public static class AssumingClass {
    @org.junit.BeforeClass
    public static void notHere() {
      org.junit.Assume.assumeTrue(false);
    }

    @Test
    public void never() {}
  }

  @Test
  public void assumptionFailuresAreReportedAsSkipped() throws IOException {
    InstrumentationRun.Reporter reporter = new InstrumentationRun.Reporter();
    org.junit.runner.JUnitCore core = new org.junit.runner.JUnitCore();
    core.addListener(reporter);
    core.run(AssumingCase.class);
    core.run(AssumingClass.class);

    assertEquals(0, reporter.failures());
    assertEquals(2, reporter.skipped());
    File xml = Files.createTempFile("report", ".xml").toFile();
    xml.deleteOnExit();
    reporter.writeXml(xml.toPath(), 0);
    String report = Files.readString(xml.toPath());
    assertTrue(report, report.contains("tests=\"3\" failures=\"0\" skipped=\"2\""));
  }

  /** Fixture: counts how often its @Parameters factory runs. */
  @org.junit.runner.RunWith(org.junit.runners.Parameterized.class)
  public static class CountedParameters {
    static int factoryCalls;

    @org.junit.runners.Parameterized.Parameters
    public static List<Object[]> cases() {
      factoryCalls++;
      return List.of(new Object[] {1}, new Object[] {2});
    }

    public CountedParameters(int value) {}

    @Test
    public void each() {}
  }

  @Test
  public void parameterFactoryRunsOncePerClass() throws Exception {
    CountedParameters.factoryCalls = 0;
    String previous = System.getProperty(InstrumentationRun.TESTS_PROPERTY);
    System.setProperty(
        InstrumentationRun.TESTS_PROPERTY, CountedParameters.class.getName() + "#each");
    try {
      assertEquals(0, InstrumentationRun.run(getClass().getClassLoader()));
    } finally {
      if (previous == null) System.clearProperty(InstrumentationRun.TESTS_PROPERTY);
      else System.setProperty(InstrumentationRun.TESTS_PROPERTY, previous);
      System.clearProperty("android.junit.runner");
    }
    assertEquals(1, CountedParameters.factoryCalls);
  }

  @Test
  public void jsonReportCarriesEachCaseAndItsFailure() throws IOException {
    InstrumentationRun.Reporter reporter = new InstrumentationRun.Reporter();
    Description passes = test("a.Part", "passes");
    Description fails = test("a.Part", "fails");
    Description skips = test("a.Part", "skips");
    reporter.testStarted(passes);
    reporter.testFinished(passes);
    reporter.testStarted(fails);
    reporter.testFailure(
        new org.junit.runner.notification.Failure(fails, new AssertionError("said \"no\"\nthen")));
    reporter.testFinished(fails);
    reporter.testStarted(skips);
    reporter.testAssumptionFailure(
        new org.junit.runner.notification.Failure(skips, new org.junit.AssumptionViolatedException("x")));
    reporter.testFinished(skips);

    File json = Files.createTempFile("report", ".json").toFile();
    json.deleteOnExit();
    reporter.writeJson(json.toPath(), 12);
    String report = Files.readString(json.toPath());

    assertTrue(
        report,
        report.startsWith(
            "{\"tests\":3,\"passed\":1,\"failed\":1,\"skipped\":1,\"durationMs\":12,"));
    assertTrue(report, report.contains("\"method\":\"fails\",\"status\":\"failed\""));
    assertTrue(report, report.contains("\"message\":\"said \\\"no\\\"\\nthen\""));
    assertTrue(report, report.contains("\"method\":\"skips\",\"status\":\"skipped\""));
  }

  @Test
  public void aRunThatCannotStartSaysWhyInItsJson() throws IOException {
    File json = Files.createTempFile("report", ".json").toFile();
    json.deleteOnExit();
    String previous = System.getProperty(InstrumentationRun.JSON_PROPERTY);
    System.setProperty(InstrumentationRun.JSON_PROPERTY, json.getAbsolutePath());
    try {
      InstrumentationRun.failed("no test class a.Missing");
    } finally {
      if (previous == null) System.clearProperty(InstrumentationRun.JSON_PROPERTY);
      else System.setProperty(InstrumentationRun.JSON_PROPERTY, previous);
    }
    String report = Files.readString(json.toPath());
    assertTrue(report, report.contains("\"tests\":0,"));
    assertTrue(report, report.contains("\"error\":\"no test class a.Missing\""));
  }

  private static Description test(String className, String method) {
    return Description.createTestDescription(className, method);
  }

  private static void copyClass(Class<?> type, JarOutputStream out) throws IOException {
    String name = type.getName().replace('.', '/') + ".class";
    out.putNextEntry(new JarEntry(name));
    try (InputStream in = type.getClassLoader().getResourceAsStream(name)) {
      in.transferTo((OutputStream) out);
    }
    out.closeEntry();
  }

  @Test
  public void shardsSplitEveryTestOnceBetweenThem() {
    Description suite = Description.createSuiteDescription("all");
    for (int c = 0; c < 7; c++) {
      Description type = Description.createSuiteDescription("com.example.Test" + c);
      for (int m = 0; m < 9; m++) {
        type.addChild(Description.createTestDescription("com.example.Test" + c, "case" + m));
      }
      suite.addChild(type);
    }
    int count = 3;
    int seen = 0;
    for (Description type : suite.getChildren()) {
      for (Description test : type.getChildren()) {
        int owners = 0;
        for (int i = 0; i < count; i++) {
          if (new InstrumentationRun.Shard(i, count).shouldRun(test)) owners++;
        }
        assertEquals(test.getDisplayName(), 1, owners);
        seen++;
      }
    }
    assertEquals(63, seen);
    for (int i = 0; i < count; i++) {
      assertTrue(InstrumentationRun.runsAny(suite, new InstrumentationRun.Shard(i, count)));
    }
  }

  @Test
  public void aShardParsesIndexOverCount() {
    assertNull(InstrumentationRun.Shard.parse(null));
    assertNull(InstrumentationRun.Shard.parse(" "));
    InstrumentationRun.Shard shard = InstrumentationRun.Shard.parse("1/4");
    assertEquals(1, shard.index);
    assertEquals(4, shard.count);
    assertThrowsIllegal("4/4");
    assertThrowsIllegal("3");
  }

  @Test
  public void aShardAndASelectionKeepOnlyTheirCommonTests() {
    Description type = Description.createSuiteDescription("com.example.Only");
    Description a = Description.createTestDescription("com.example.Only", "a");
    type.addChild(a);
    int owner = InstrumentationRun.Shard.of(a, 2);
    InstrumentationRun.Selection none = InstrumentationRun.Selection.parse("com.example.Only#b");
    InstrumentationRun.Shard mine = new InstrumentationRun.Shard(owner, 2);
    assertTrue(InstrumentationRun.runsAny(type, mine.and(null)));
    assertFalse(InstrumentationRun.runsAny(type, mine.and(none.filterFor("com.example.Only"))));
    assertFalse(InstrumentationRun.runsAny(type, new InstrumentationRun.Shard(1 - owner, 2)));
  }

  private static void assertThrowsIllegal(String spec) {
    try {
      InstrumentationRun.Shard.parse(spec);
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("parsed " + spec);
  }

  public static class Suppressed {
    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 37)
    public void from37() {}

    @Test
    @androidx.test.filters.SdkSuppress(maxSdkVersion = 35)
    public void upTo35() {}

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 30, excludedSdks = {36})
    public void not36() {}

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 99, codeName = "Baklava")
    public void onBaklava() {}

    @Test
    @androidx.test.filters.RequiresDevice
    public void onHardware() {}

    @Test
    public void always() {}
  }

  @androidx.test.filters.SdkSuppress(minSdkVersion = 40)
  public static class SuppressedClass {
    @Test
    public void inherits() {}

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 21)
    public void methodWins() {}
  }

  @Test
  public void sdkSuppressAndRequiresDeviceFilterAsAndroidJUnitRunnerDoes() {
    InstrumentationRun.DeviceFilter at36 = new InstrumentationRun.DeviceFilter(36, "REL");
    List<String> ran = new java.util.ArrayList<>();
    for (Description test : Description.createSuiteDescription(Suppressed.class).getChildren()) {
      if (at36.shouldRun(test)) ran.add(test.getMethodName());
    }
    org.junit.runner.Runner runner =
        org.junit.runner.Request.aClass(Suppressed.class).getRunner();
    ran.clear();
    for (Description test : runner.getDescription().getChildren()) {
      if (at36.shouldRun(test)) ran.add(test.getMethodName());
    }
    assertEquals(List.of("always"), sorted(ran));

    InstrumentationRun.DeviceFilter baklava = new InstrumentationRun.DeviceFilter(36, "Baklava");
    ran.clear();
    for (Description test : runner.getDescription().getChildren()) {
      if (baklava.shouldRun(test)) ran.add(test.getMethodName());
    }
    assertEquals(List.of("always", "onBaklava"), sorted(ran));

    org.junit.runner.Runner classLevel =
        org.junit.runner.Request.aClass(SuppressedClass.class).getRunner();
    ran.clear();
    for (Description test : classLevel.getDescription().getChildren()) {
      if (at36.shouldRun(test)) ran.add(test.getMethodName());
    }
    assertEquals(List.of("methodWins"), ran);
  }

  private static List<String> sorted(List<String> names) {
    List<String> copy = new java.util.ArrayList<>(names);
    java.util.Collections.sort(copy);
    return copy;
  }

  @Test
  public void googlesOwnTestsAreFoundAndItsLibrariesAreNot() {
    assertFalse(
        InstrumentationRun.skipped(
            "com/google/samples/apps/nowinandroid/ui/NavigationTest.class"));
    assertFalse(InstrumentationRun.skipped("com/google/android/apps/example/MainTest.class"));
    assertTrue(InstrumentationRun.skipped("com/google/common/truth/Truth.class"));
    assertTrue(InstrumentationRun.skipped("com/google/android/material/button/MaterialButton.class"));
    assertTrue(InstrumentationRun.skipped("dagger/hilt/android/testing/HiltTestApplication.class"));
    assertTrue(InstrumentationRun.skipped("androidx/test/core/app/ActivityScenario.class"));
  }
}
