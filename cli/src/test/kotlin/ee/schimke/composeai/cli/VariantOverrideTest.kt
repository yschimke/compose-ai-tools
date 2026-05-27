package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the CLI-side half of `--variant` (issue #1546). The Gradle-plugin-side variant matching is
 * covered by `VariantMatchesTargetTest`; this exercises the flag parsing and the
 * `-PcomposePreview.variant=<value>` arg shape that the connection extraArguments use to make the
 * plugin's task-registration filter line up with model-query and task-run invocations alike.
 */
class VariantOverrideTest {
  private class Probe(args: List<String>) : Command(args) {
    override fun run() = Unit

    fun override(): String? = variantOverride

    fun gradleArgs(): List<String> = variantGradleArgs()
  }

  @Test
  fun `absent flag emits no gradle arg`() {
    val p = Probe(listOf("list"))
    assertNull(p.override())
    assertEquals(emptyList(), p.gradleArgs())
  }

  @Test
  fun `space-form flag forwards as -PcomposePreview-variant`() {
    val p = Probe(listOf("list", "--variant", "demoDebug"))
    assertEquals("demoDebug", p.override())
    assertEquals(listOf("-PcomposePreview.variant=demoDebug"), p.gradleArgs())
  }

  @Test
  fun `equals-form flag is accepted`() {
    val p = Probe(listOf("list", "--variant=prodRelease"))
    assertEquals("prodRelease", p.override())
    assertEquals(listOf("-PcomposePreview.variant=prodRelease"), p.gradleArgs())
  }

  @Test
  fun `blank value is treated as absent`() {
    val p = Probe(listOf("list", "--variant", "   "))
    assertNull(p.override())
    assertEquals(emptyList(), p.gradleArgs())
  }
}
