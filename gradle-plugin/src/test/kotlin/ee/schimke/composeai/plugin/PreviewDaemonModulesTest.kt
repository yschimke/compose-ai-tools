package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.attributes.Category
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The plugin injects compose-preview-daemon modules into consumer builds versionless, with
 * `compose-preview-daemon-bom` at [PreviewDaemonVersion] imported as a platform on the same
 * configuration. A module pinned at the daemon *release* version 404s the first time that release
 * skips the module, because the daemon publishes only what changed — the BOM is the record of which
 * version each module is at. See [PreviewDaemonModules].
 */
class PreviewDaemonModulesTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun project(): Project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

  private fun Project.declared(configurationName: String) =
    configurations.getByName(configurationName).dependencies.map { dependency ->
      val category =
        (dependency as? ModuleDependency)?.attributes?.getAttribute(Category.CATEGORY_ATTRIBUTE)
      Declared("${dependency.group}:${dependency.name}", dependency.version, category?.name)
    }

  private data class Declared(val module: String, val version: String?, val category: String?)

  private val bom = "ee.schimke.composeai:compose-preview-daemon-bom"

  @Test
  fun `dependency returns a versionless coordinate and imports the daemon BOM once`() {
    val project = project()
    project.configurations.create("tool")

    val renderer = PreviewDaemonModules.dependency(project, "tool", "renderer-android")
    project.dependencies.add("tool", renderer)
    val connector =
      PreviewDaemonModules.dependency(project, "tool", "data-layoutinspector-connector")
    project.dependencies.add("tool", connector)

    assertThat(renderer).isEqualTo("ee.schimke.composeai:renderer-android")
    assertThat(connector).isEqualTo("ee.schimke.composeai:data-layoutinspector-connector")
    assertThat(project.declared("tool"))
      .containsExactly(
        Declared(bom, PreviewDaemonVersion.value, Category.REGULAR_PLATFORM),
        Declared("ee.schimke.composeai:renderer-android", null, null),
        Declared("ee.schimke.composeai:data-layoutinspector-connector", null, null),
      )
  }

  @Test
  fun `the baked daemon version is the BOM version, not a module version`() {
    assertThat(PreviewDaemonModules.bomCoordinate()).isEqualTo("$bom:${PreviewDaemonVersion.value}")
    assertThat(PreviewDaemonVersion.value).matches("""\d+\.\d+\.\d+.*""")
  }

  @Test
  fun `desktop renderer config gets a versionless renderer and the daemon BOM platform`() {
    val project = project()

    ComposePreviewTasks.ensureRendererDesktopConfig(project, "composePreviewRenderer")
    (project as ProjectInternal).evaluate()

    assertThat(project.declared("composePreviewRenderer"))
      .containsExactly(
        Declared(bom, PreviewDaemonVersion.value, Category.REGULAR_PLATFORM),
        Declared("ee.schimke.composeai:renderer-desktop", null, null),
      )
  }

  @Test
  fun `a consumer-populated desktop renderer config gets neither the default nor the BOM`() {
    val project = project()
    project.configurations.create("composePreviewRenderer")
    project.dependencies.add("composePreviewRenderer", "com.example:my-renderer:1.0")

    ComposePreviewTasks.ensureRendererDesktopConfig(project, "composePreviewRenderer")
    (project as ProjectInternal).evaluate()

    assertThat(project.declared("composePreviewRenderer"))
      .containsExactly(Declared("com.example:my-renderer", "1.0", null))
  }

  /**
   * The Android injection sites (`renderer-android`, `daemon-android`,
   * `data-layoutinspector-connector`) and `daemon-desktop` sit behind AGP / full task registration
   * that a `ProjectBuilder` project cannot reach, so pin the shape at the source: no main source
   * may build a daemon coordinate out of [PreviewDaemonVersion] by hand. Every one has to go
   * through [PreviewDaemonModules], which cannot hand out a coordinate without importing the BOM.
   */
  @Test
  fun `no main source pins a daemon module at the daemon release version`() {
    val mainSources = File("src/main/kotlin")
    assertThat(mainSources.isDirectory).isTrue()
    val pinned = Regex("""ee\.schimke\.composeai:[\w.-]+:\$\{PreviewDaemonVersion""")
    val offenders =
      mainSources
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
          file
            .readLines()
            .withIndex()
            .filter { pinned.containsMatchIn(it.value) }
            .map { "${file.path}:${it.index + 1}: ${it.value.trim()}" }
        }
        .toList()
    assertThat(offenders).isEmpty()
  }
}
