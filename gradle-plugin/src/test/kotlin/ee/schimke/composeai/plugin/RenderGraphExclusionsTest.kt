package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * [RenderGraphExclusions] — where `composePreview { renderGraph { exclude(…) } }` lands
 * (issue #4995).
 *
 * The render configurations `extendsFrom` the consumer's own test/runtime classpath on purpose, so
 * that renderer and consumer resolve as ONE graph. A consumer whose graph carries a strict-version
 * platform (`strictly(v)` + `reject("(v,")` on every module) inherits those constraints onto our
 * configuration too, where any renderer dependency newer than one of them is an unsolvable
 * conflict. The exclusion is what lets them keep the platform on their own graph and off ours.
 *
 * What the tests below pin is that it stays a *scalpel*: rules land on the configuration we own,
 * the consumer's configuration is never mutated, and an unconfigured build gets no rules at all.
 */
class RenderGraphExclusionsTest {

  /**
   * A render configuration shaped like the real one: ours, extending the consumer's unit-test
   * runtime classpath.
   */
  private fun renderConfiguration(project: Project) =
    project.configurations.create("composePreviewAndroidRendererDebug").apply {
      extendsFrom(project.configurations.create("debugUnitTestRuntimeClasspath"))
    }

  @Test
  fun `an exclusion becomes an exclude rule on our configuration`() {
    val project = ProjectBuilder.builder().build()
    val configuration = renderConfiguration(project)

    RenderGraphExclusions.applyTo(
      project,
      configuration,
      listOf(RenderGraphExclusion("com.example", "version-constraints")),
    )

    assertThat(configuration.excludeRules.map { it.group to it.module })
      .containsExactly("com.example" to "version-constraints")
  }

  @Test
  fun `a half-specified exclusion keeps the other half unconstrained`() {
    val project = ProjectBuilder.builder().build()
    val configuration = renderConfiguration(project)

    RenderGraphExclusions.applyTo(
      project,
      configuration,
      listOf(RenderGraphExclusion("com.example", null), RenderGraphExclusion(null, "constraints")),
    )

    // Gradle reads a null half as "any": group-only drops the group, module-only drops that name
    // wherever it comes from. Writing the empty string instead would match nothing.
    assertThat(configuration.excludeRules.map { it.group to it.module })
      .containsExactly("com.example" to null, null to "constraints")
  }

  @Test
  fun `no exclusions means no rules`() {
    val project = ProjectBuilder.builder().build()
    val configuration = renderConfiguration(project)

    RenderGraphExclusions.applyTo(project, configuration, emptyList())

    // The default path for every consumer who never touches the DSL: the render graph is exactly
    // what it was before this feature existed.
    assertThat(configuration.excludeRules).isEmpty()
  }

  @Test
  fun `the consumer's own configuration is left alone`() {
    val project = ProjectBuilder.builder().build()
    val configuration = renderConfiguration(project)

    RenderGraphExclusions.applyTo(
      project,
      configuration,
      listOf(RenderGraphExclusion("com.example", "version-constraints")),
    )

    // The load-bearing half of the design: the consumer's normal build must resolve exactly as it
    // did before, platform and all. Excluding on the inherited configuration instead of ours would
    // silently unpin their production graph.
    assertThat(project.configurations.getByName("debugUnitTestRuntimeClasspath").excludeRules)
      .isEmpty()
  }

  @Test
  fun `applying the same exclusion twice does not duplicate the rule`() {
    val project = ProjectBuilder.builder().build()
    val configuration = renderConfiguration(project)
    val exclusions = listOf(RenderGraphExclusion("com.example", "version-constraints"))

    // `maybeCreate` plus a second registration pass can reach one configuration twice.
    RenderGraphExclusions.applyTo(project, configuration, exclusions)
    RenderGraphExclusions.applyTo(project, configuration, exclusions)

    assertThat(configuration.excludeRules).hasSize(1)
  }

  @Test
  fun `exclusions survive on a configuration that also carries the render resolution rules`() {
    val project = ProjectBuilder.builder().build()
    val configuration = renderConfiguration(project)

    // Order matters at the call sites: the KMP-sibling / Hamcrest / compose-floor rules go on
    // first, the consumer's exclusions after, so a module they keep off the graph is not pulled
    // back in by one of our substitutions.
    AndroidPreviewSupport.applyRenderGraphResolutionRules(configuration)
    RenderGraphExclusions.applyTo(
      project,
      configuration,
      listOf(RenderGraphExclusion("com.example", "version-constraints")),
    )

    assertThat(configuration.excludeRules.map { it.group to it.module })
      .containsExactly("com.example" to "version-constraints")
  }
}
