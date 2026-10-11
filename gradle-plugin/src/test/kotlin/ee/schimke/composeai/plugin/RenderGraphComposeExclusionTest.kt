package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Rule 3 of [AndroidPreviewSupport.applyRenderGraphResolutionRules]: our CMP transitives must stay
 * off a consumer's Android render graph, or their Android variants upgrade the consumer's Compose
 * against a resource APK built from its own graph (`NoSuchFieldError` on
 * `androidx.compose.ui.R$id`, #3447).
 *
 * The exclusion rides on our own dependencies ([AndroidPreviewSupport.addRenderGraphDependency]),
 * never the configuration: a config-wide exclude strips a CMP consumer's only route to
 * `androidx.compose.material3` (#3483).
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
   * A render configuration extending a unit-test classpath that carries the consumer's own Compose
   * (Rule 3's precondition).
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
    // These publish file-less Android variants that only depend on `androidx.compose.*`, so
    // excluding them removes version pressure and no classes.
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
    // `rendererConfig` extends the consumer's classpath, so a configuration-level exclude would
    // also strip a CMP consumer's `material3` (`NoClassDefFoundError: …/ColorScheme`).
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
    // Not an `org.jetbrains.compose.` prefix: `components-resources` ships real Android classes.
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
    // #3484: a consumer with no Compose of its own (tiles only) would be left with none at all if
    // ours were excluded.
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
  fun `our own injected compose floor does not make a consumer look like a compose consumer`() {
    // Our own injected `ui` / `foundation` floor pins must not count as consumer Compose; otherwise
    // Rule 3 stays on for tile-only consumers and the renderer runs on the 1.9.5 floor it can't
    // link against:
    //
    //   NoSuchMethodError: androidx.compose.ui.node.ComposeUiNode$Companion
    //     .getApplyOnDeactivatedNodeAssertion()
    val project = project()
    val unitTest = project.configurations.create("debugUnitTestRuntimeClasspath")
    val configuration =
      project.configurations.create("composePreviewAndroidRendererDebug").apply {
        extendsFrom(unitTest)
      }
    // Exactly what the plugin contributes for a tile-only consumer.
    AndroidPreviewSupport.addPluginDependency(
      project,
      unitTest.name,
      "androidx.compose.ui:ui:${AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION}",
    )
    AndroidPreviewSupport.addPluginDependency(
      project,
      unitTest.name,
      "androidx.compose.foundation:foundation:" +
        AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION,
    )

    assertThat(AndroidPreviewSupport.consumerBringsOwnCompose(project, configuration)).isFalse()

    val dependency =
      AndroidPreviewSupport.addRenderGraphDependency(
        project,
        configuration.name,
        "ee.schimke.composeai:renderer-android:0.0.0",
      ) as ModuleDependency
    assertThat(dependency.excludeRules).isEmpty()
  }

  @Test
  fun `a real compose consumer still counts with our floor alongside it`() {
    // Skipping our pins mustn't hide a consumer's own declared Compose (the `ComposeStarter`
    // shape), which keeps Rule 3 on and #3447 fixed.
    val project = project()
    val unitTest = project.configurations.create("debugUnitTestRuntimeClasspath")
    val configuration =
      project.configurations.create("composePreviewAndroidRendererDebug").apply {
        extendsFrom(unitTest)
      }
    AndroidPreviewSupport.addPluginDependency(
      project,
      unitTest.name,
      "androidx.compose.ui:ui:${AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION}",
    )
    project.dependencies.add(unitTest.name, "androidx.compose.ui:ui:1.10.6")

    assertThat(AndroidPreviewSupport.consumerBringsOwnCompose(project, configuration)).isTrue()

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
  fun `a consumer declaring exactly our floor coordinate collapses onto ours`() {
    // A known limit: `DependencySet` collapses an identical coordinate+version into our instance,
    // so the probe sees no consumer Compose. Deliberate: that consumer's only Compose would be the
    // floor, and Rule 3 off keeps the renderer on a version it can run.
    val project = project()
    val unitTest = project.configurations.create("debugUnitTestRuntimeClasspath")
    val configuration =
      project.configurations.create("composePreviewAndroidRendererDebug").apply {
        extendsFrom(unitTest)
      }
    AndroidPreviewSupport.addPluginDependency(
      project,
      unitTest.name,
      "androidx.compose.ui:ui:${AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION}",
    )
    project.dependencies.add(
      unitTest.name,
      "androidx.compose.ui:ui:${AndroidPreviewSupport.RENDERER_COMPOSE_FLOOR_VERSION}",
    )

    assertThat(AndroidPreviewSupport.consumerBringsOwnCompose(project, configuration)).isFalse()
  }

  @Test
  fun `a consumer's compose reached only through extendsFrom still counts`() {
    // The probe must walk `extendsFrom`: consumer Compose lives on the unit-test classpath, not the
    // render configuration.
    val project = project()
    val configuration = composeConsumerRenderConfiguration(project)

    assertThat(AndroidPreviewSupport.consumerBringsOwnCompose(project, configuration)).isTrue()
    assertThat(configuration.dependencies).isEmpty()
  }

  @Test
  fun `main-variant compose pin follows whichever compose actually runs`() {
    // #3484's other face: with Rule 3 off, compose-ui on the render classpath is ours (1.11.2), but
    // the resource APK comes from the main variant, so pinning it at 1.9.5 gives newer classes an
    // older R class:
    //
    //   NoSuchFieldError: Class androidx.compose.ui.R$id does not have member field
    //     'int androidx_compose_ui_view_compose_view_context'
    //
    // The pin must follow whichever Compose runs.
    val tileOnly = project()
    tileOnly.configurations.create("debugUnitTestRuntimeClasspath")
    assertThat(AndroidPreviewSupport.mainVariantComposeVersion(tileOnly, "debug"))
      .isEqualTo(AndroidPreviewSupport.RENDERER_COMPOSE_CMP_RUNTIME_VERSION)

    // A Compose consumer's render graph is floored at the link floor, so the resource APK pin uses
    // that floor too. Still a pin: consumers above it keep their line; ones below are raised
    // (#3590).
    val composeApp = project()
    val unitTest = composeApp.configurations.create("debugUnitTestRuntimeClasspath")
    composeApp.dependencies.add(unitTest.name, "androidx.compose.ui:ui:1.10.6")
    assertThat(AndroidPreviewSupport.mainVariantComposeVersion(composeApp, "debug"))
      .isEqualTo(AndroidPreviewSupport.RENDERER_COMPOSE_LINK_FLOOR_VERSION)
  }
}
