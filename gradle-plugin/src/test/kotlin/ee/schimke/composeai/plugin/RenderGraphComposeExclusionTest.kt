package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.artifacts.ModuleDependency
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
 *
 * The exclusion rides on the dependencies WE add, never on the configuration — see
 * [AndroidPreviewSupport.addRenderGraphDependency]. A config-wide exclude also strips the
 * consumer's own `org.jetbrains.compose.*` dependencies, which is fatal for a pure Compose
 * Multiplatform consumer whose only route to `androidx.compose.material3` is that redirector
 * (issue #3483).
 */
class RenderGraphComposeExclusionTest {

  private val aliasGroups =
    listOf(
      "org.jetbrains.compose.animation",
      "org.jetbrains.compose.foundation",
      "org.jetbrains.compose.material",
      "org.jetbrains.compose.material3",
      "org.jetbrains.compose.runtime",
      "org.jetbrains.compose.ui",
    )

  private fun project() = ProjectBuilder.builder().build()

  /**
   * A render configuration shaped like a Compose consumer's: it `extendsFrom` the unit-test
   * classpath, and that classpath carries the consumer's own Compose. Rule 3 only applies to a
   * consumer that has Compose of its own to defer to.
   */
  private fun composeConsumerRenderConfiguration(project: org.gradle.api.Project) =
    project.configurations.create("composePreviewAndroidRendererDebug").apply {
      val unitTest =
        project.configurations.create("debugUnitTestRuntimeClasspath").apply {
          project.dependencies.add(name, "androidx.compose.ui:ui:1.10.6")
        }
      extendsFrom(unitTest)
    }

  @Test
  fun `our own render dependency excludes the compose multiplatform families that alias androidx`() {
    // These six publish Android variants with NO files — they exist only to depend on the matching
    // `androidx.compose.*` artifact, so dropping them from OUR subtree removes version pressure
    // and zero classes.
    val project = project()
    val configuration = composeConsumerRenderConfiguration(project)

    val dependency =
      AndroidPreviewSupport.addRenderGraphDependency(
        project,
        configuration.name,
        "ee.schimke.composeai:renderer-android:0.0.0",
      ) as ModuleDependency

    assertThat(dependency.excludeRules.mapNotNull { it.group })
      .containsAtLeastElementsIn(aliasGroups)
  }

  @Test
  fun `the render configuration itself excludes nothing`() {
    // The regression that motivated this test. `rendererConfig` extends the consumer's unit-test
    // classpath, so ANY exclude rule on the configuration applies to the consumer's own
    // dependencies as well as ours. A CMP consumer declaring `implementation(compose.material3)`
    // then loses `androidx.compose.material3` entirely and every preview dies in the renderer's
    // `CaptureMaterialTheme` with `NoClassDefFoundError: androidx/compose/material3/ColorScheme`.
    val project = project()
    val configuration = project.configurations.create("composePreviewAndroidRendererDebug")
    AndroidPreviewSupport.applyRenderGraphResolutionRules(configuration)

    AndroidPreviewSupport.addRenderGraphDependency(
      project,
      configuration.name,
      "ee.schimke.composeai:renderer-android:0.0.0",
    )

    assertThat(configuration.excludeRules).isEmpty()
  }

  @Test
  fun `a consumer's own compose multiplatform dependency survives on the render graph`() {
    // The consumer-facing shape of the same guarantee: what the consumer put on its own test
    // classpath must still be there after the plugin has contributed the renderer.
    val project = project()
    val configuration = project.configurations.create("composePreviewAndroidRendererDebug")
    AndroidPreviewSupport.applyRenderGraphResolutionRules(configuration)
    project.dependencies.add(
      configuration.name,
      "org.jetbrains.compose.material3:material3:1.11.0-alpha07",
    )

    AndroidPreviewSupport.addRenderGraphDependency(
      project,
      configuration.name,
      "ee.schimke.composeai:renderer-android:0.0.0",
    )

    val consumerDependency =
      configuration.dependencies.single { it.group == "org.jetbrains.compose.material3" }
    assertThat((consumerDependency as ModuleDependency).excludeRules).isEmpty()
    assertThat(configuration.excludeRules).isEmpty()
  }

  @Test
  fun `render graph does not exclude compose multiplatform families that ship android classes`() {
    // The guard on broadening Rule 3 to an `org.jetbrains.compose.` prefix:
    // `components-resources` DOES ship Android classes (`org.jetbrains.compose.resources.*`), so
    // excluding its group by prefix would strip real code off the render classpath rather than
    // just a version constraint.
    assertThat(aliasGroups).doesNotContain("org.jetbrains.compose.components")
  }

  @Test
  fun `androidx compose is never excluded`() {
    // The consumer's own Compose is exactly what the rule exists to preserve. Excluding any
    // `androidx.compose.*` group would empty the render classpath instead of deferring to it.
    val project = project()
    val configuration = composeConsumerRenderConfiguration(project)

    val dependency =
      AndroidPreviewSupport.addRenderGraphDependency(
        project,
        configuration.name,
        "ee.schimke.composeai:renderer-android:0.0.0",
      ) as ModuleDependency

    assertThat(
        dependency.excludeRules.mapNotNull { it.group }.filter { it.startsWith("androidx.") }
      )
      .isEmpty()
  }

  @Test
  fun `a consumer with no compose of its own keeps ours on the render graph`() {
    // Issue #3484. Rule 3 says "the consumer's Compose wins" — a consumer that HAS none wins
    // nothing, and excluding ours leaves the render classpath with no Compose at all. That is
    // `wear-os-samples/WearTilesKotlin`: a protolayout-tiles app whose previews are tiles, whose
    // dependencies are `androidx.wear.tiles` / `androidx.wear.protolayout.*`, and which lost all
    // 188 renders the moment our own Compose Multiplatform transitives were dropped.
    val project = project()
    val configuration =
      project.configurations.create("composePreviewAndroidRendererDebug").apply {
        val unitTest =
          project.configurations.create("debugUnitTestRuntimeClasspath").apply {
            project.dependencies.add(name, "androidx.wear.protolayout:protolayout-material3:1.4.0")
          }
        extendsFrom(unitTest)
      }

    val dependency =
      AndroidPreviewSupport.addRenderGraphDependency(
        project,
        configuration.name,
        "ee.schimke.composeai:renderer-android:0.0.0",
      ) as ModuleDependency

    assertThat(dependency.excludeRules).isEmpty()
  }

  @Test
  fun `a consumer's compose reached only through extendsFrom still counts`() {
    // The consumer's Compose sits on the unit-test classpath the render configuration extends, not
    // on the render configuration itself, so the probe has to walk `extendsFrom` to see it.
    // Reading only the render configuration's own dependencies would classify every Compose
    // consumer as Compose-less and turn Rule 3 off exactly where it is needed.
    val project = project()
    val configuration = composeConsumerRenderConfiguration(project)

    assertThat(AndroidPreviewSupport.consumerBringsOwnCompose(project, configuration)).isTrue()
    assertThat(configuration.dependencies).isEmpty()
  }
}
