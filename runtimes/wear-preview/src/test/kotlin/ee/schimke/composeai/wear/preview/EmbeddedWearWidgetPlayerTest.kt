package ee.schimke.composeai.wear.preview

import com.google.common.truth.Truth.assertThat
import java.net.URLClassLoader
import org.junit.Test

/**
 * Pins the embedded-player availability gate the CMP lane asks before calling.
 *
 * [`the pinned entry point describes the real facade`] is the load-bearing one: the gate resolves
 * the entry point *method*, so if the vendored player's signature drifts (a re-vendor from a newer
 * upstream, an added parameter) the pin silently stops matching and every widget preview quietly
 * falls back to the View-backed lane — the exact regression #5259 fixed, arriving as a no-op
 * dependency bump. Resolving the real facade off the test classpath is what makes that a failing
 * test instead.
 */
class EmbeddedWearWidgetPlayerTest {

  @Test
  fun `the pinned entry point describes the real facade`() {
    val facade = Class.forName(EMBEDDED_PLAYER_FACADE)
    val overloads =
      facade.declaredMethods
        .filter { it.name == EMBEDDED_PLAYER_ENTRY_POINT }
        .map { method -> method.parameterTypes.map { it.name } }
    assertThat(overloads).isNotEmpty()
    assertThat(overloads).contains(EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS)
    assertThat(declaresEntryPoint(facade, EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS)).isTrue()
  }

  @Test
  fun `the player on this classpath is available`() {
    assertThat(embeddedPlayerEntryPointPresent(javaClass.classLoader)).isTrue()
  }

  @Test
  fun `an absent player is unavailable rather than an error`() {
    // "the consumer doesn't ship the embedded player at all" — the case that has to degrade to
    // upstream `WearWidgetPreview` rather than throw.
    assertThat(embeddedPlayerEntryPointPresent(URLClassLoader(emptyArray(), null))).isFalse()
    assertThat(embeddedPlayerEntryPointPresent(null)).isFalse()
  }

  @Test
  fun `a facade whose entry point drifted is unavailable`() {
    val facade = Class.forName(EMBEDDED_PLAYER_FACADE)
    assertThat(declaresEntryPoint(facade, EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS + "boolean"))
      .isFalse()
    assertThat(declaresEntryPoint(facade, EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS.reversed()))
      .isFalse()
  }
}
