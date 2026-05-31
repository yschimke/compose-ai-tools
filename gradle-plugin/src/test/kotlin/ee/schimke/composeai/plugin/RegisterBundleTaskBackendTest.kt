package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the backend wiring of [ComposePreviewTasks.registerBundleTask] without standing up AGP/KGP.
 *
 * `composePreviewBundle` was originally registered only on the desktop/JVM path, so
 * `compose-preview render --bundle` against a project containing Android modules failed
 * task-not-found before rendering anything. The Android path now calls the same shared registration
 * with `backendId = "android"`; this test exercises that registration directly via a synthetic
 * project and asserts the resulting task records the right `backend` for `bundle.json`.
 */
class RegisterBundleTaskBackendTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun registerBundle(backendId: String): BundlePreviewTask {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/main"),
      resolveDependencyConfigName = { "runtimeClasspath" },
      discoverTaskName = "composePreviewDiscover",
      backendId = backendId,
    )
    return project.tasks.getByName("composePreviewBundle") as BundlePreviewTask
  }

  @Test
  fun `android registration records the android backend`() {
    assertThat(registerBundle("android").backend.get()).isEqualTo("android")
  }

  @Test
  fun `backend defaults to desktop`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    // No backendId argument — the default must stay "desktop" so the CMP/JVM path is unchanged.
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/main"),
      resolveDependencyConfigName = { "runtimeClasspath" },
      discoverTaskName = "composePreviewDiscover",
    )
    val task = project.tasks.getByName("composePreviewBundle") as BundlePreviewTask
    assertThat(task.backend.get()).isEqualTo("desktop")
  }

  @Test
  fun `desktop task registration still wires the bundle task with the desktop backend`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val task = project.tasks.getByName("composePreviewBundle") as BundlePreviewTask
    assertThat(task.backend.get()).isEqualTo("desktop")
  }
}
