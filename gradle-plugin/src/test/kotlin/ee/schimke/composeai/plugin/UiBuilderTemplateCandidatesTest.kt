package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The `ui-builder/` template tree is authored designs, never a build directory.
 *
 * `ui-builder` is a *conventional* directory name for template designs. In a repository that also
 * has a Gradle module called `ui-builder` — compose-preview-server does — they are the same path,
 * so handing the whole directory to an `@InputFiles` property snapshotted that module's `build/`
 * output too. Gradle does not warn about that, it fails the build: `composePreviewDiscover` and
 * `composePreviewBundle` end up consuming `build/wasmDist` and
 * `build/tmp/wasmJsPublicPackageJson/package.json` from tasks they do not depend on, which is an
 * *"uses this output of task … without declaring an explicit or implicit dependency"* validation
 * error. Every job in that repository's CI went red on the 2.4.0 bump.
 */
class UiBuilderTemplateCandidatesTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `a build directory under ui-builder is not an input`() {
    val root = tmp.root
    val design = File(root, "ui-builder/designs/wear-list.json").withText("{}")
    // What a co-located Gradle module writes: the two paths that actually broke
    // compose-preview-server, plus a non-JSON one.
    val wasmDist = File(root, "ui-builder/build/wasmDist/index.html").withText("<html></html>")
    val packageJson =
      File(root, "ui-builder/build/tmp/wasmJsPublicPackageJson/package.json").withText("{}")
    val nestedBuild = File(root, "ui-builder/designs/build/generated.json").withText("{}")

    val discover = discoverTask(root)
    val files = discover.uiBuilderTemplateCandidates.files

    assertThat(files).contains(design)
    assertThat(files).doesNotContain(wasmDist)
    assertThat(files).doesNotContain(packageJson)
    assertThat(files).doesNotContain(nestedBuild)
  }

  @Test
  fun `the authored designs are still found`() {
    val root = tmp.root
    val top = File(root, "ui-builder/designs/wear-list.json").withText("{}")
    val nested = File(root, "ui-builder/designs/wear/list.json").withText("{}")
    // Not a design, and never was one — the tree is JSON.
    File(root, "ui-builder/designs/README.md").withText("# designs")

    val discover = discoverTask(root)

    assertThat(discover.uiBuilderTemplateCandidates.files).containsExactly(top, nested)
  }

  private fun discoverTask(root: File): DiscoverPreviewsTask {
    val project = ProjectBuilder.builder().withProjectDir(root).build()
    project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("runtimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    ComposePreviewTasks.registerDesktopTasks(
      project,
      project.extensions.getByType(PreviewExtension::class.java),
    )
    project.tasks.register("compileKotlin", DefaultTask::class.java)
    return project.tasks.named("composePreviewDiscover", DiscoverPreviewsTask::class.java).get()
  }

  private fun File.withText(text: String): File = apply {
    parentFile.mkdirs()
    writeText(text)
  }
}
