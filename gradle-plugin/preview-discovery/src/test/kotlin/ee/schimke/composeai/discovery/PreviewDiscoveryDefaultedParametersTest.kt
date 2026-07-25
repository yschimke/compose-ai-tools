package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import io.github.classgraph.ClassGraph
import org.junit.Test

/**
 * Covers the rule that admits a `@Preview` whose parameters are *all* defaulted.
 *
 * Production composables are routinely annotated `@Preview` in place, and they nearly always carry
 * `modifier: Modifier = Modifier`. Discovery used to reject any parameter that wasn't
 * `@PreviewParameter`-injected, so those previews were dropped silently — 5 of JetLagged's 8
 * vanished, and the loss only surfaced at the end of the catalog pipeline as "missing renders".
 *
 * The distinction cannot be made from bytecode alone: Kotlin emits one synthetic `<name>$default`
 * bridge when *any* parameter has a default, so its presence says nothing about the rest. The rule
 * therefore reads `@kotlin.Metadata`, which carries a per-parameter default flag.
 */
class PreviewDiscoveryDefaultedParametersTest {

  /**
   * Run [block] against the fixture facade inside an open ClassGraph scan — `ClassInfo.resource`,
   * which the metadata read goes through, is only valid while the scan is open.
   */
  private fun allDefaults(simpleName: String, userParameterCount: Int): Boolean {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        // A defaulted function also emits a synthetic `<name>$default`; match the real one.
        val method = classInfo.methodInfo.first { it.name == simpleName }
        return PreviewDiscovery.allParametersHaveDefaults(classInfo, method, userParameterCount)
      }
  }

  @Test
  fun `a fully defaulted function is admitted`() {
    assertThat(allDefaults("allDefaultedComponent", userParameterCount = 2)).isTrue()
  }

  @Test
  fun `a partially defaulted function is rejected`() {
    // sampleComponent defaults only `count` and `note`; `state` and `labels` are required, so
    // invoking with an all-bits default mask would pass them null/0 and NPE at render time.
    assertThat(allDefaults("sampleComponent", userParameterCount = 5)).isFalse()
  }

  @Test
  fun `a metadata parameter-count mismatch is rejected rather than guessed`() {
    // The caller derives the count from the JVM signature. If it disagrees with what the author
    // wrote, the two views are out of sync and the safe answer is "not all defaulted".
    assertThat(allDefaults("allDefaultedComponent", userParameterCount = 3)).isFalse()
  }
}
