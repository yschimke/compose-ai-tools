package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the CLI-side half of `--missing-renders`. The Gradle-plugin-side throw-vs-warn-vs-silent
 * branch is covered by `RenderFunctionalTest`; this exercises the matching CLI exit-code policy so
 * a `compose-preview show --missing-renders warn` run that legitimately produces some empty
 * captures doesn't get bumped back to exit 2 by `ShowCommand`'s post-check.
 */
class MissingRendersPolicyTest {
  // Tiny concrete subclass — `Command` is abstract and the policy helpers are `protected`, so
  // exercising them needs an in-package shim. Run is unused.
  private class Probe(args: List<String>) : Command(args) {
    override fun run() = Unit

    fun policy(): String? = missingRendersPolicy

    fun gradleArgs(): List<String> = missingRendersGradleArgs()

    fun shouldFail(): Boolean = shouldFailOnMissingRenders()
  }

  @Test
  fun `absent flag defaults to fail and emits no gradle arg`() {
    val p = Probe(listOf("show"))
    assertEquals(null, p.policy())
    assertEquals(emptyList(), p.gradleArgs())
    assertTrue(p.shouldFail(), "default policy must keep the historical exit-2 behaviour")
  }

  @Test
  fun `warn opts down both the CLI exit and forwards the gradle property`() {
    val p = Probe(listOf("show", "--missing-renders", "warn"))
    assertEquals("warn", p.policy())
    assertEquals(listOf("-PcomposePreview.missingRenders=warn"), p.gradleArgs())
    assertFalse(p.shouldFail())
  }

  @Test
  fun `ignore also opts down the CLI exit`() {
    val p = Probe(listOf("show", "--missing-renders", "ignore"))
    assertFalse(p.shouldFail())
  }

  @Test
  fun `unknown values fall back to fail`() {
    // Mirrors the Gradle-plugin-side fallback: a typo or garbage value must not silently widen
    // the policy. The CLI just forwards the value verbatim; the plugin will see it, fall back
    // to fail too, and the CLI's own post-check stays hard.
    val p = Probe(listOf("show", "--missing-renders", "bogus"))
    assertTrue(p.shouldFail())
  }

  @Test
  fun `equals-form flag is accepted`() {
    val p = Probe(listOf("show", "--missing-renders=warn"))
    assertEquals("warn", p.policy())
    assertFalse(p.shouldFail())
  }
}
