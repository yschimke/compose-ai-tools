package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderFailuresTest {
  private val tempDir = createTempDirectory("render-failures-test").toFile()

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  private fun moduleWithReport(name: String, vararg xmls: Pair<String, String>): PreviewModule {
    val dir = File(tempDir, name).apply { mkdirs() }
    val resultsDir = File(dir, "build/test-results/renderPreviews").apply { mkdirs() }
    for ((fileName, contents) in xmls) {
      File(resultsDir, fileName).writeText(contents)
    }
    return PreviewModule(gradlePath = name, projectDir = dir)
  }

  @Test
  fun `parses failure and error elements with messages and stack traces`() {
    val module =
      moduleWithReport(
        "app",
        "TEST-RobolectricRenderTest.xml" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <testsuite name="RobolectricRenderTest" tests="2" failures="1" errors="1">
            <testcase name="render_PreviewA" classname="RobolectricRenderTest">
              <failure message="expected: &lt;1&gt; but was: &lt;2&gt;" type="org.junit.ComparisonFailure">
          org.junit.ComparisonFailure: expected: &lt;1&gt; but was: &lt;2&gt;
            at RobolectricRenderTest.render_PreviewA(RobolectricRenderTest.kt:42)
            at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
              </failure>
            </testcase>
            <testcase name="render_PreviewB" classname="RobolectricRenderTest">
              <error message="boom" type="java.lang.RuntimeException">
          java.lang.RuntimeException: boom
            at RobolectricRenderTest.render_PreviewB(RobolectricRenderTest.kt:99)
              </error>
            </testcase>
          </testsuite>
          """
            .trimIndent(),
      )

    val failures = collectRenderTestFailures(listOf(module))

    assertEquals(2, failures.size)
    val byTest = failures.associateBy { it.testName }
    val a = byTest.getValue("render_PreviewA")
    assertEquals("failure", a.kind)
    assertEquals("expected: <1> but was: <2>", a.message)
    assertTrue(a.stackTrace!!.contains("RobolectricRenderTest.render_PreviewA"))
    val b = byTest.getValue("render_PreviewB")
    assertEquals("error", b.kind)
    assertEquals("boom", b.message)
  }

  @Test
  fun `skips passing testcases and modules without reports`() {
    val module =
      moduleWithReport(
        "app",
        "TEST-RobolectricRenderTest.xml" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <testsuite name="RobolectricRenderTest" tests="1" failures="0" errors="0">
            <testcase name="render_Ok" classname="RobolectricRenderTest"/>
          </testsuite>
          """
            .trimIndent(),
      )
    val emptyModule =
      PreviewModule(
        gradlePath = "previews",
        projectDir = File(tempDir, "previews").apply { mkdirs() },
      )

    val failures = collectRenderTestFailures(listOf(module, emptyModule))

    assertEquals(0, failures.size)
  }

  @Test
  fun `prints grouped failures with module path and trimmed stack`() {
    val failures =
      listOf(
        RenderTestFailure(
          module = "app",
          className = "RobolectricRenderTest",
          testName = "render_PreviewA",
          kind = "failure",
          message = "expected: <1> but was: <2>",
          stackTrace =
            """
            org.junit.ComparisonFailure: expected: <1> but was: <2>
              at RobolectricRenderTest.render_PreviewA(RobolectricRenderTest.kt:42)
              at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
              at line.4
              at line.5
              at line.6
              at line.7
              at line.8
            """
              .trimIndent(),
        )
      )
    val buf = ByteArrayOutputStream()

    printRenderTestFailures(failures, PrintStream(buf), stackLines = 4)

    val out = buf.toString()
    assertTrue(out.contains("Failing tests in :app:renderPreviews (1):"), out)
    assertTrue(out.contains("failure RobolectricRenderTest.render_PreviewA"), out)
    assertTrue(out.contains("expected: <1> but was: <2>"), out)
    assertTrue(
      out.contains("RobolectricRenderTest.render_PreviewA(RobolectricRenderTest.kt:42)"),
      out,
    )
    assertTrue(!out.contains("line.5"), "stack trace should be capped at 4 lines: $out")
  }

  @Test
  fun `malformed XML is swallowed`() {
    val module = moduleWithReport("app", "TEST-Broken.xml" to "not actually xml <<")

    val failures = collectRenderTestFailures(listOf(module))

    assertEquals(0, failures.size)
  }
}
