package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderFailuresTest {
  @Test
  fun `prints failures grouped by task path with class+method id`() {
    val failures =
      listOf(
        CapturedTestFailure(
          taskPath = ":app:renderPreviews",
          className = "RobolectricRenderTest",
          methodName = "render_PreviewA",
          displayName = "render_PreviewA(RobolectricRenderTest)",
          message = "expected: <1> but was: <2>",
          description =
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
        ),
        CapturedTestFailure(
          taskPath = ":previews:renderPreviews",
          className = "DesktopRenderTest",
          methodName = "renders_PreviewC",
          displayName = "renders_PreviewC(DesktopRenderTest)",
          message = "boom",
          description = "java.lang.RuntimeException: boom",
        ),
      )
    val buf = ByteArrayOutputStream()

    printCapturedTestFailures(failures, PrintStream(buf), stackLines = 4)

    val out = buf.toString()
    assertTrue(out.contains("Failing tests in :app:renderPreviews (1):"), out)
    assertTrue(out.contains("RobolectricRenderTest.render_PreviewA"), out)
    assertTrue(out.contains("expected: <1> but was: <2>"), out)
    assertTrue(out.contains("Failing tests in :previews:renderPreviews (1):"), out)
    assertTrue(out.contains("DesktopRenderTest.renders_PreviewC"), out)
    assertFalse(out.contains("line.5"), "stack trace should be capped at 4 lines: $out")
  }

  @Test
  fun `falls back to displayName when class+method are absent`() {
    val failures =
      listOf(
        CapturedTestFailure(
          taskPath = ":app:renderPreviews",
          className = null,
          methodName = null,
          displayName = "Robolectric setup",
          message = "no testRuntimeOnly libraries available",
          description = null,
        )
      )
    val buf = ByteArrayOutputStream()

    printCapturedTestFailures(failures, PrintStream(buf))

    val out = buf.toString()
    assertTrue(out.contains("Robolectric setup"), out)
    assertTrue(out.contains("no testRuntimeOnly libraries available"), out)
  }

  @Test
  fun `empty list is a no-op`() {
    val buf = ByteArrayOutputStream()

    printCapturedTestFailures(emptyList(), PrintStream(buf))

    assertEquals("", buf.toString())
  }
}
