package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The compose-ui line floor of [AndroidPreviewSupport.applyRenderGraphResolutionRules]. Rule 3
 * defers the render classpath to the consumer's Compose, but the renderer must still link against
 * it; [AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION] alone only reaches `ui-test-*`, so an
 * older consumer failed every preview with:
 * ```
 * NoSuchMethodError: 'kotlin.jvm.functions.Function1
 *   androidx.compose.ui.node.ComposeUiNode$Companion.getApplyOnDeactivatedNodeAssertion()'
 * ```
 * (see #3590)
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
    // They version independently of the ui line — there is no androidx.compose.material3 on it —
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
    // Derived from the constant rather than hard-coded, so moving the floor cannot leave this
    // asserting the opposite of its name when the floor moves.
    assertThat(upgrade("androidx.compose.ui", "$floor-alpha01")).isEqualTo(floor)
    // …but an alpha of a HIGHER version is not.
    assertThat(upgrade("androidx.compose.ui", "1.12.0-alpha01")).isNull()
  }

  @Test
  fun `the floor sits inside the bracket the published artifacts prove`() {
    // `ui-android` 1.9.5 lacks `ComposeUiNode$Companion.getApplyOnDeactivatedNodeAssertion`; 1.10.0
    // has it and renders the fixtures, so it's the first accepted release.
    assertThat(upgrade("androidx.compose.ui", "1.9.5")).isEqualTo(floor)
    assertThat(upgrade("androidx.compose.ui", "1.10.0")).isNull()
    assertThat(upgrade("androidx.compose.ui", "1.11.0")).isNull()
  }

  @Test
  fun `the KMP sibling substitution carries the floor instead of dropping it`() {
    // Every `eachDependency` action sees the ORIGINAL requested selector, so a `useTarget` passing
    // `requested.version` would undo an earlier `useVersion` and re-pin `ui-android:1.9.5`.
    val target =
      AndroidPreviewSupport.renderGraphTarget(
        group = "androidx.compose.ui",
        name = "ui-jvmstubs",
        version = "1.9.5",
        floorComposeLine = true,
      )
    assertThat(target).isNotNull()
    assertThat(target!!.name).isEqualTo("ui-android")
    assertThat(target.version).isEqualTo(floor)
    assertThat(target.flooredComposeLine).isTrue()
  }

  @Test
  fun `a sibling already above the floor keeps its own version`() {
    val target =
      AndroidPreviewSupport.renderGraphTarget(
        group = "androidx.compose.ui",
        name = "ui-jvmstubs",
        version = "1.12.0",
        floorComposeLine = true,
      )
    assertThat(target!!.name).isEqualTo("ui-android")
    assertThat(target.version).isEqualTo("1.12.0")
    assertThat(target.flooredComposeLine).isFalse()
  }

  @Test
  fun `manageDependencies=false leaves the compose line alone but still substitutes siblings`() {
    // The opt-out leaves main-variant ui/foundation to the consumer, so raising the render graph
    // would put newer classes over older resources (the #3484 `R$id` NoSuchFieldError). The
    // decision is "do not raise"; ValidateComposeFloorTask reports composeFloorOptOutMessage
    // separately.
    assertThat(
        AndroidPreviewSupport.renderGraphTarget(
          group = "androidx.compose.ui",
          name = "ui",
          version = "1.7.6",
          floorComposeLine = false,
        )
      )
      .isNull()

    // The sibling substitution is unrelated to the floor and must survive the opt-out.
    val sibling =
      AndroidPreviewSupport.renderGraphTarget(
        group = "androidx.compose.ui",
        name = "ui-jvmstubs",
        version = "1.7.6",
        floorComposeLine = false,
      )
    assertThat(sibling!!.name).isEqualTo("ui-android")
    assertThat(sibling.version).isEqualTo("1.7.6")
    assertThat(sibling.flooredComposeLine).isFalse()
  }

  @Test
  fun `a non-compose module with no sibling is left alone entirely`() {
    assertThat(AndroidPreviewSupport.renderGraphTarget("com.example", "thing", "1.0.0", true))
      .isNull()
  }

  @Test
  fun `the opt-out message names the versions and both ways out`() {
    // The hole this closes: validateExternallyManagedDependencies checks that coordinates are
    // DECLARED, never what they resolve to, so a below-floor opt-out consumer got #3590's
    // NoSuchMethodError on every preview with nothing explaining why.
    val message = AndroidPreviewSupport.composeFloorOptOutMessage("androidx.compose.ui:ui", "1.7.6")

    assertThat(message).contains("manageDependencies = false")
    // Both numbers a reader needs: what they have, and what is required.
    assertThat(message).contains("1.7.6")
    assertThat(message).contains(floor)
    // Both escape hatches, so the message is actionable rather than merely accurate.
    assertThat(message).contains("compose-bom")
    assertThat(message).contains("manageDependencies = true")
    // …and the symptom, so someone who already hit it can connect the two.
    assertThat(message).contains("getApplyOnDeactivatedNodeAssertion")
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
