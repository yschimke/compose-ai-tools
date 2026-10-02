package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Both discover tasks the plugin registers declare an APNG-capable backend, so
 * `@AnimatedPreview(format = Apng)` is recorded as APNG and named `.apng` on Android as well as on
 * desktop.
 *
 * Android used to keep `animatedPreviewApngSupported = false`: its renderer encoded GIF whatever
 * the annotation asked, so discovery downgraded the request to GIF (APNG plan step D2). The Android
 * renderer honours `format` from compose-preview-daemon 3.13.0 (#208), and the plugin resolves
 * `renderer-android` at exactly that pin, so the Android call site — which passes no flag of its
 * own — now gets `true`. A regression back to `false` would silently write every Android APNG
 * request as a `.gif` again.
 */
class AnimatedPreviewApngSupportTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `the discover task the Android backend registers declares APNG support`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("debugRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    // The same arguments `AndroidPreviewSupport` passes — notably no
    // `animatedPreviewApngSupported`.
    val discover =
      ComposePreviewTasks.registerDiscoverTask(
        project,
        project.files(),
        { "debugRuntimeClasspath" },
        project.layout.buildDirectory.dir("compose-previews"),
        extension,
      ) {}

    assertThat(discover.get().animatedPreviewApngSupported.get()).isTrue()
  }

  @Test
  fun `the desktop discover task declares APNG support`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val discover = project.tasks.getByName("composePreviewDiscover") as DiscoverPreviewsTask
    assertThat(discover.animatedPreviewApngSupported.get()).isTrue()
  }
}
