package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The `composePreview { renderGraph { exclude(…) } }` DSL and its
 * `-PcomposePreview.renderGraphExcludes=…` twin (issue #4995).
 *
 * The DSL is the supported replacement for reaching into the plugin's own configuration names from
 * a consumer's build script — `configurations.matching { it.name.startsWith("composePreview") }` —
 * so these tests pin the surface a consumer writes against: what an exclusion may omit, what it may
 * not, and that the two entry points accumulate instead of shadowing each other.
 */
class RenderGraphExclusionTest {

  private fun extension(): RenderGraphExtension {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("ee.schimke.composeai.preview.config")
    return (project.extensions.getByName(ComposePreviewDsl.EXTENSION_NAME) as PreviewExtension)
      .renderGraph
  }

  @Test
  fun `no exclusions by default`() {
    // The whole feature is opt-in: an untouched build must present Gradle with an empty rule set,
    // never an exclusion the consumer did not ask for.
    assertThat(extension().excludes.get()).isEmpty()
  }

  @Test
  fun `exclude records group and module`() {
    val renderGraph = extension()
    renderGraph.exclude(group = "com.example", module = "version-constraints")

    assertThat(renderGraph.excludes.get())
      .containsExactly(RenderGraphExclusion("com.example", "version-constraints"))
  }

  @Test
  fun `either half may be omitted`() {
    val renderGraph = extension()
    renderGraph.exclude(group = "com.example")
    renderGraph.exclude(module = "version-constraints")

    // Mirrors Gradle's own `Configuration.exclude(group:, module:)`: a group-only rule drops the
    // whole group, a module-only rule drops that name in any group.
    assertThat(renderGraph.excludes.get())
      .containsExactly(
        RenderGraphExclusion("com.example", null),
        RenderGraphExclusion(null, "version-constraints"),
      )
      .inOrder()
  }

  @Test
  fun `an exclusion with neither half is rejected`() {
    // Gradle would accept `exclude()` and quietly read it as "exclude everything", emptying the
    // render classpath. Fail at the point the mistake is made instead.
    val failure = assertThrows(IllegalArgumentException::class.java) { extension().exclude() }
    assertThat(failure).hasMessageThat().contains("requires a group, a module, or both")
  }

  @Test
  fun `blank halves count as absent`() {
    val failure =
      assertThrows(IllegalArgumentException::class.java) { extension().exclude(group = "  ") }
    assertThat(failure).hasMessageThat().contains("requires a group, a module, or both")
  }

  @Test
  fun `groovy map notation matches the kotlin named-argument form`() {
    val renderGraph = extension()
    // `renderGraph { exclude group: 'com.example', module: 'x' }` in a `build.gradle` arrives here
    // as a Map — the Groovy DSL has no named arguments.
    renderGraph.exclude(mapOf("group" to "com.example", "module" to "version-constraints"))

    assertThat(renderGraph.excludes.get())
      .containsExactly(RenderGraphExclusion("com.example", "version-constraints"))
  }

  @Test
  fun `groovy map notation rejects unknown keys`() {
    // `exclude version: '1.0'` is not a thing; silently ignoring the key would produce an exclusion
    // that matches far more than the author intended.
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        extension().exclude(mapOf("group" to "com.example", "version" to "1.0"))
      }
    assertThat(failure).hasMessageThat().contains("unknown key(s) [version]")
  }

  @Test
  fun `parses the gradle property spec`() {
    assertThat(
        RenderGraphExclusion.parse("com.example:version-constraints, com.other:platform-bom")
      )
      .containsExactly(
        RenderGraphExclusion("com.example", "version-constraints"),
        RenderGraphExclusion("com.other", "platform-bom"),
      )
      .inOrder()
  }

  @Test
  fun `property spec allows either half to be empty`() {
    assertThat(RenderGraphExclusion.parse("com.example:,:version-constraints"))
      .containsExactly(
        RenderGraphExclusion("com.example", null),
        RenderGraphExclusion(null, "version-constraints"),
      )
      .inOrder()
  }

  @Test
  fun `property spec tolerates blank entries`() {
    // A trailing comma from a shell-assembled `-P` value is not worth failing a render over.
    assertThat(RenderGraphExclusion.parse("com.example:version-constraints, ,"))
      .containsExactly(RenderGraphExclusion("com.example", "version-constraints"))
  }

  @Test
  fun `property spec rejects a coordinate that is not group colon module`() {
    // The symptom of a silently-dropped exclusion is an unresolvable render configuration whose
    // Gradle error never mentions this property, so a typo has to fail here and say so.
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        RenderGraphExclusion.parse("version-constraints")
      }
    assertThat(failure).hasMessageThat().contains("is not a 'group:module' coordinate")
  }

  @Test
  fun `property spec rejects an entry with neither half`() {
    val failure =
      assertThrows(IllegalArgumentException::class.java) { RenderGraphExclusion.parse(":") }
    assertThat(failure).hasMessageThat().contains("requires a group, a module, or both")
  }
}
