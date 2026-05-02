package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.DefaultTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class ComposePreviewCompileTaskTest {

  @Test
  fun `compile-only task picks up compile task registered later`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    val compileOnly =
      ComposePreviewTasks.registerCompileOnlyTask(
        project,
        extension,
        compileTaskNames = listOf("compileDebugKotlin"),
      )
    val compileTask = project.tasks.register("compileDebugKotlin", DefaultTask::class.java)

    val deps = compileOnly.get().taskDependencies.getDependencies(compileOnly.get())

    assertThat(deps).contains(compileTask.get())
  }

  @Test
  fun `desktop discover task picks up compile task registered later`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("runtimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)
    val compileTask = project.tasks.register("compileKotlin", DefaultTask::class.java)

    val discover = project.tasks.named("discoverPreviews").get()
    val deps = discover.taskDependencies.getDependencies(discover)

    assertThat(deps).contains(compileTask.get())
  }
}
