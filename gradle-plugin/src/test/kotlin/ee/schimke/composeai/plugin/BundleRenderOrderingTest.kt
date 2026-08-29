package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the ordering `composePreviewBundle` declares against the render tasks whose output it reads.
 *
 * `renderFiles` declares three directories as inputs — `renders/`, `svg-renders/` and
 * `lottie-renders/` — written by three different tasks. Only the first had an ordering, so asking
 * for the whole render family and the bundle in ONE Gradle invocation failed the build outright:
 * ```
 * $ ./gradlew :catalog:composePreviewRenderAll :catalog:composePreviewBundle
 * Declare an explicit dependency on ':catalog:composePreviewRenderLottie'
 * from ':catalog:composePreviewBundle' using Task#mustRunAfter.
 * ```
 *
 * `composePreviewRenderAll` aggregates all three, so the documented "render then pack" flow only
 * worked as two separate invocations — and a consumer that split it that way had no way to know the
 * bundle would happily pack whatever renders were left on disk from an earlier run.
 *
 * Ordering, not dependency: bundling WITHOUT a render is a supported flow (stub cover), which is
 * why `registerBundleTask` has never used `dependsOn` here. These assertions are as much about that
 * choice as about the edges — a `dependsOn` would pass the first test and break the third.
 */
class BundleRenderOrderingTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun projectWithBundle(): org.gradle.api.Project {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    // `registerBundleTask` declares `dependsOn(discoverTaskName)`, so the discover task has to
    // exist before `taskDependencies` can be resolved at all.
    project.tasks.register("composePreviewDiscover")
    // The two asset passes are registered on the Android path in the real plugin; register them
    // directly here so the ordering can be asserted without standing up AGP.
    project.tasks.register("composePreviewRender", RenderPreviewsTask::class.java)
    project.tasks.register("composePreviewRenderSvg", RenderPreviewsTask::class.java)
    project.tasks.register("composePreviewRenderLottie", RenderPreviewsTask::class.java)
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/main"),
      resolveDependencyConfigName = { "runtimeClasspath" },
      discoverTaskName = "composePreviewDiscover",
    )
    return project
  }

  private fun orderingNames(project: org.gradle.api.Project): Set<String> {
    val bundle = project.tasks.getByName("composePreviewBundle")
    return bundle.mustRunAfter.getDependencies(bundle).map { it.name }.toSet()
  }

  @Test
  fun `bundle runs after every render task whose output it declares as an input`() {
    assertThat(orderingNames(projectWithBundle()))
      .containsAtLeast(
        "composePreviewRender",
        "composePreviewRenderSvg",
        "composePreviewRenderLottie",
      )
  }

  @Test
  fun `the ordering is empty where the asset passes are not registered`() {
    // Desktop modules have no SVG/Lottie tasks. Naming them as strings would throw there, which is
    // why the ordering is declared by type.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.tasks.register("composePreviewDiscover")
    project.tasks.register("composePreviewRender", RenderPreviewsTask::class.java)
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/main"),
      resolveDependencyConfigName = { "runtimeClasspath" },
      discoverTaskName = "composePreviewDiscover",
    )
    assertThat(orderingNames(project)).containsExactly("composePreviewRender")
  }

  @Test
  fun `bundle does not depend on any render task, so packing without one still works`() {
    val project = projectWithBundle()
    val bundle = project.tasks.getByName("composePreviewBundle")
    val dependsOn = bundle.taskDependencies.getDependencies(bundle).map { it.name }
    assertThat(dependsOn).doesNotContain("composePreviewRender")
    assertThat(dependsOn).doesNotContain("composePreviewRenderSvg")
    assertThat(dependsOn).doesNotContain("composePreviewRenderLottie")
  }
}
