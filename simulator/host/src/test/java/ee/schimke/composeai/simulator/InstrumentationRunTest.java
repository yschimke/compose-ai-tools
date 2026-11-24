package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

  @Test
  public void findsConcreteClassesWithTestMethodsOnly() throws IOException {
    File jar = Files.createTempFile("tests", ".jar").toFile();
    jar.deleteOnExit();
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
      for (Class<?> type : List.of(
              InstrumentationRunTest.class,
              Helper.class,
              AbstractBase.class,
              InheritsTests.class)) {
        copyClass(type, out);
      }
      // A library the test APK bundles is never scanned, whatever it declares.
      out.putNextEntry(new JarEntry("org/junit/Assert.class"));
      out.closeEntry();
    }

    assertEquals(
        List.of(InstrumentationRunTest.class.getName(), InheritsTests.class.getName()),
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
    assertTrue(selection.includes("a.Whole", "anything"));
    assertTrue(selection.includes("a.Part", "one"));
    assertFalse(selection.includes("a.Part", "two"));
  }

  @Test
  public void filterKeepsSuitesWithASelectedChild() {
    InstrumentationRun.Selection selection = InstrumentationRun.Selection.parse("a.Part#one");
    Description suite = Description.createSuiteDescription("a.Part");
    suite.addChild(Description.createTestDescription("a.Part", "one"));
    suite.addChild(Description.createTestDescription("a.Part", "two"));

    assertTrue(selection.filter().shouldRun(suite));
    assertTrue(selection.filter().shouldRun(Description.createTestDescription("a.Part", "one")));
    assertFalse(selection.filter().shouldRun(Description.createTestDescription("a.Part", "two")));
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

    assertTrue(selection.filter().shouldRun(Description.createTestDescription("a.Param", "each[0]")));
    assertTrue(selection.filter().shouldRun(Description.createTestDescription("a.Param", "each[1]")));
    assertFalse(selection.filter().shouldRun(Description.createTestDescription("a.Param", "other[0]")));
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

  private static void copyClass(Class<?> type, JarOutputStream out) throws IOException {
    String name = type.getName().replace('.', '/') + ".class";
    out.putNextEntry(new JarEntry(name));
    try (InputStream in = type.getClassLoader().getResourceAsStream(name)) {
      in.transferTo((OutputStream) out);
    }
    out.closeEntry();
  }
}
