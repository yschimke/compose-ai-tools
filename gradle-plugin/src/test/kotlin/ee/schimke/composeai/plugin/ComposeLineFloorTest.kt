package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The compose-ui line floor of [AndroidPreviewSupport.applyRenderGraphResolutionRules].
 *
 * Rule 3 defers the render classpath to the consumer's Compose, but the renderer's own bytecode
 * still has to link against whatever it lands on. Nothing pinned `ui` / `foundation` / `runtime` /
 * `animation` — [AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION] only ever reaches the
 * `ui-test-*` coordinates — so a consumer below the floor handed the renderer an unlinkable
 * classpath and every preview died before user code ran:
 * ```
 * NoSuchMethodError: 'kotlin.jvm.functions.Function1
 *   androidx.compose.ui.node.ComposeUiNode$Companion.getApplyOnDeactivatedNodeAssertion()'
 * ```
 *
 * (issue #3590 — `yschimke/home-assistant-android` on `compose-bom` 2025.01.00.)
 */
class ComposeLineFloorTest {

  private val floor = AndroidPreviewSupport.RENDERER_COMPOSE_LINK_FLOOR_VERSION

  private fun upgrade(group: String, version: String?) =
    AndroidPreviewSupport.composeLineFloorUpgrade(group, version)

  @Test
  fun `a consumer below the floor is raised to it`() {
    // The #3590 shape: compose-bom 2025.01.00 resolves the ui line to 1.7.6.
    assertThat(upgrade("androidx.compose.ui", "1.7.6")).isEqualTo(floor)
    assertThat(upgrade("androidx.compose.foundation", "1.7.6")).isEqualTo(floor)
    assertThat(upgrade("androidx.compose.runtime", "1.9.5")).isEqualTo(floor)
    assertThat(upgrade("androidx.compose.animation", "1.9.5")).isEqualTo(floor)
  }

  @Test
  fun `a consumer at or above the floor keeps its own version`() {
    // Rule 3's symmetry is the point: a consumer on a newer Compose is never dragged back to ours.
    assertThat(upgrade("androidx.compose.ui", floor)).isNull()
    assertThat(upgrade("androidx.compose.ui", "1.11.3")).isNull()
    assertThat(upgrade("androidx.compose.ui", "1.12.0")).isNull()
    assertThat(upgrade("androidx.compose.ui", "2.0.0")).isNull()
  }

  @Test
  fun `material and material3 are left alone`() {
    // They version independently of the ui line — there is no androidx.compose.material3 1.11.2,
    // so raising them to the floor would resolve to a version that does not exist.
    assertThat(upgrade("androidx.compose.material3", "1.3.1")).isNull()
    assertThat(upgrade("androidx.compose.material", "1.7.6")).isNull()
  }

  @Test
  fun `non-compose groups are left alone`() {
    assertThat(upgrade("androidx.wear.compose", "1.4.0")).isNull()
    assertThat(upgrade("org.jetbrains.compose.ui", "1.11.1")).isNull()
    assertThat(upgrade("com.example", "0.1.0")).isNull()
  }

  @Test
  fun `an alpha of the floor is still below it`() {
    assertThat(upgrade("androidx.compose.ui", "1.11.2-alpha01")).isEqualTo(floor)
    // …but an alpha of a HIGHER version is not.
    assertThat(upgrade("androidx.compose.ui", "1.12.0-alpha01")).isNull()
  }

  @Test
  fun `a version we cannot compare is never touched`() {
    // Forcing a version we cannot order risks dragging a consumer backwards, which is strictly
    // worse than leaving a classpath we merely suspect is too old.
    assertThat(upgrade("androidx.compose.ui", "+")).isNull()
    assertThat(upgrade("androidx.compose.ui", "1.9.+")).isNull()
    assertThat(upgrade("androidx.compose.ui", "[1.7,1.12)")).isNull()
    assertThat(upgrade("androidx.compose.ui", "")).isNull()
    assertThat(upgrade("androidx.compose.ui", null)).isNull()
  }
}
