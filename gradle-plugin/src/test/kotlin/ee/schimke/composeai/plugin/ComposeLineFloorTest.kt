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
    // asserting the opposite of its name (issue #3603 moved it from 1.11.2 to 1.11.0).
    assertThat(upgrade("androidx.compose.ui", "$floor-alpha01")).isEqualTo(floor)
    // …but an alpha of a HIGHER version is not.
    assertThat(upgrade("androidx.compose.ui", "1.12.0-alpha01")).isNull()
  }

  @Test
  fun `the floor sits inside the bracket the published artifacts prove`() {
    // Probing androidx.compose.ui:ui-android for the accessor that actually fails:
    //   1.9.5  — ComposeUiNode$Companion.getApplyOnDeactivatedNodeAssertion ABSENT
    //   1.10.0 — PRESENT
    // and yschimke/horologist renders its full 80-component catalog on 1.11.0. So the floor must
    // raise 1.9.5 (provably unlinkable) and must NOT raise 1.11.0 (proven to work end to end) —
    // the second half is what #3603 was: a 1.11.2 floor raised a consumer that was already fine.
    assertThat(upgrade("androidx.compose.ui", "1.9.5")).isEqualTo(floor)
    assertThat(upgrade("androidx.compose.ui", "1.11.0")).isNull()
  }

  @Test
  fun `the KMP sibling substitution carries the floor instead of dropping it`() {
    // Gradle hands every `eachDependency` action the ORIGINAL requested selector, so a `useTarget`
    // passing `requested.version` through silently undoes an earlier `useVersion`. Split across two
    // rules, this exact coordinate would be floored and then re-pinned to `ui-android:1.9.5` — the
    // artifact the floor exists to keep off the render graph.
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
    // The floor is only safe while the main-variant ui/foundation pins move with it, and the
    // opt-out branch deliberately leaves those to the consumer ("consumer must ensure
    // androidx.compose.ui:ui is on the main variant"). Raising the render graph there would put
    // floor-version classes over the consumer's older resources — the #3484 R$id NoSuchFieldError.
    //
    // The DECISION is still "do not raise"; the resolution rule additionally throws
    // composeFloorOptOutMessage for this case, so the consumer gets told rather than silently
    // rendering nothing. Kept separate so the pure decision stays testable on its own.
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
