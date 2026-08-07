package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Rule 3 of [AndroidPreviewSupport.applyRenderGraphResolutionRules]: our own Compose Multiplatform
 * transitives must not sit on a consumer's Android render graph, because their Android variants pin
 * `androidx.compose.*` and would win conflict resolution against the consumer's own Compose.
 *
 * The consumer's merged unit-test resource APK is built from *its* graph, so an upgraded Compose
 * means bytecode looking up `R.id` fields the resources never declared — `NoSuchFieldError:
 * androidx.compose.ui.R$id ... androidx_compose_ui_view_compose_view_context`, failing every
 * preview in the module at `onAttachedToWindow` (issue #3447 fallout, reproduced against
 * `wear-os-samples/ComposeStarter`).
 */
class RenderGraphComposeExclusionTest {

  private fun excludedGroups(): Set<String> {
    val project = ProjectBuilder.builder().build()
    val configuration = project.configurations.create("composePreviewAndroidRendererDebug")
    AndroidPreviewSupport.applyRenderGraphResolutionRules(configuration)
    return configuration.excludeRules.mapNotNull { it.group }.toSet()
  }

  @Test
  fun `render graph excludes the compose multiplatform families that alias androidx`() {
    // These six publish Android variants with NO files — they exist only to depend on the matching
    // `androidx.compose.*` artifact, so dropping them removes version pressure and zero classes.
    assertThat(excludedGroups())
      .containsAtLeast(
        "org.jetbrains.compose.animation",
        "org.jetbrains.compose.foundation",
        "org.jetbrains.compose.material",
        "org.jetbrains.compose.material3",
        "org.jetbrains.compose.runtime",
        "org.jetbrains.compose.ui",
      )
  }

  @Test
  fun `render graph does not exclude compose multiplatform families that ship android classes`() {
    // The guard on broadening Rule 3 to an `org.jetbrains.compose.` prefix:
    // `components-resources` DOES ship Android classes (`org.jetbrains.compose.resources.*`), so
    // excluding its group by prefix would strip real code off the render classpath rather than
    // just a version constraint.
    assertThat(excludedGroups()).doesNotContain("org.jetbrains.compose.components")
  }

  @Test
  fun `androidx compose is never excluded`() {
    // The consumer's own Compose is exactly what the rule exists to preserve. Excluding any
    // `androidx.compose.*` group would empty the render classpath instead of deferring to it.
    assertThat(excludedGroups().filter { it.startsWith("androidx.") }).isEmpty()
  }
}
