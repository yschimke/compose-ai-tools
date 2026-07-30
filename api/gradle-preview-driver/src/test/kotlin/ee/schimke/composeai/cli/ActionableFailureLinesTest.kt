package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what a non-`--verbose` build failure shows from Gradle's stderr.
 *
 * The motivating bug: a render failure whose reason is plain prose (no `> ` decoration) printed `*
 * What went wrong:` followed immediately by `* Try:` — a failure with no cause anywhere in the
 * output, in CI, where re-running with `--verbose` isn't an option.
 */
class ActionableFailureLinesTest {

  @Test
  fun `keeps a plain-prose reason that matches none of the line patterns`() {
    val captured =
      """
      FAILURE: Build failed with an exception.

      * What went wrong:
      Execution failed for task ':samples:android:composePreviewRender'.

      * Try:
      > Run with --stacktrace option to get the stack trace.
      """
        .trimIndent()

    assertEquals(
      listOf(
        "FAILURE: Build failed with an exception.",
        "* What went wrong:",
        "Execution failed for task ':samples:android:composePreviewRender'.",
        "* Try:",
        "> Run with --stacktrace option to get the stack trace.",
      ),
      actionableFailureLines(captured),
    )
  }

  @Test
  fun `keeps every line of a multi-line task message`() {
    val captured =
      """
      * What went wrong:
      Execution failed for task ':app:composePreviewRenderAll'.
      2 previews produced no PNG:
        com.example.FooKt.Bar
        com.example.FooKt.Baz
      * Try:
      """
        .trimIndent()

    val lines = actionableFailureLines(captured)
    assertTrue(lines.contains("  com.example.FooKt.Bar"), lines.toString())
    assertTrue(lines.contains("  com.example.FooKt.Baz"), lines.toString())
  }

  @Test
  fun `stops keeping prose at the next section header`() {
    val captured =
      """
      * What went wrong:
      Something broke.
      * Try:
      Some untagged advice we don't need.
      * Get more help at https://help.gradle.org
      Another untagged line.
      """
        .trimIndent()

    assertEquals(
      listOf(
        "* What went wrong:",
        "Something broke.",
        "* Try:",
        "* Get more help at https://help.gradle.org",
      ),
      actionableFailureLines(captured),
    )
  }

  @Test
  fun `still keeps decorated causes and compiler diagnostics outside the block`() {
    val captured =
      """
      e: file:///src/Foo.kt:3:1 Unresolved reference: bar
      Something noisy we don't want.
      > Task :app:compileKotlin FAILED
      """
        .trimIndent()

    assertEquals(
      listOf(
        "e: file:///src/Foo.kt:3:1 Unresolved reference: bar",
        "> Task :app:compileKotlin FAILED",
      ),
      actionableFailureLines(captured),
    )
  }

  @Test
  fun `each failure of a multi-failure build keeps its own reason`() {
    val captured =
      """
      FAILURE: Build completed with 2 failures.
      * What went wrong:
      > Querying the mapped value of provider(java.util.Set) before task ':a:b' has completed
      * Try:
      * What went wrong:
      Execution failed for task ':c:d'.
      * Try:
      """
        .trimIndent()

    val lines = actionableFailureLines(captured)
    assertTrue(lines.contains("Execution failed for task ':c:d'."), lines.toString())
    assertEquals(2, lines.count { it == "* What went wrong:" })
  }

  @Test
  fun `empty input yields nothing`() {
    assertEquals(emptyList<String>(), actionableFailureLines(""))
  }
}
