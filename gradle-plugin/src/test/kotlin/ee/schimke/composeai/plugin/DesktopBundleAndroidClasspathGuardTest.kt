package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pack-time backstop for the `backend: desktop` / `*-android` classpath mismatch.
 *
 * The desktop classpath guard ([ValidateComposePreviewClasspathTask]) matches on file-path
 * substrings, which cannot see this: the `artifactType=jar` artifact view hands back
 * AGP-transformed `…/transforms/<hash>/transformed/<name>/jars/classes.jar` paths, in which nothing
 * of the original artifact id survives. So the check lives where the resolved Maven coordinates are
 * still exact — inside the pack itself.
 */
class DesktopBundleAndroidClasspathGuardTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun task(): BundlePreviewTask {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/main"),
      resolveDependencyConfigName = { "runtimeClasspath" },
      discoverTaskName = "composePreviewDiscover",
    )
    return project.tasks.getByName("composePreviewBundle") as BundlePreviewTask
  }

  private fun maven(group: String, artifact: String, type: String) =
    ClasspathEntry.Maven(group = group, artifact = artifact, version = "1.0", type = type)

  /** The exact shape that shipped in the meshcore-mobile `:meshcore-components` bundle. */
  private val androidComposeClasspath =
    listOf(
      ClasspathEntry.Module(path = "classes/app.jar"),
      maven("androidx.compose.ui", "ui-android", "aar"),
      maven("androidx.compose.material3", "material3-android", "aar"),
      maven("org.jetbrains.kotlin", "kotlin-stdlib", "jar"),
    )

  @Test
  fun `desktop bundle carrying android artifacts fails the pack`() {
    val e =
      assertThrows(GradleException::class.java) {
        task().failOnAndroidClasspathInDesktopBundle("desktop", androidComposeClasspath)
      }
    assertThat(e).hasMessageThat().contains("NoClassDefFoundError: android/os/Parcelable")
    assertThat(e).hasMessageThat().contains("androidx.compose.ui:ui-android:1.0")
    assertThat(e).hasMessageThat().contains("androidx.compose.material3:material3-android:1.0")
    // Actionable next step, not just a diagnosis.
    assertThat(e).hasMessageThat().contains("jvm(\"desktop\")")
    // Pure-JVM entries are not reported as offenders.
    assertThat(e).hasMessageThat().doesNotContain("kotlin-stdlib")
  }

  @Test
  fun `an android bundle may carry android artifacts`() {
    // The Robolectric sandbox supplies android.jar, so this is the correct, expected shape.
    task().failOnAndroidClasspathInDesktopBundle("android", androidComposeClasspath)
  }

  @Test
  fun `a clean desktop classpath passes`() {
    task()
      .failOnAndroidClasspathInDesktopBundle(
        "desktop",
        listOf(
          ClasspathEntry.Module(path = "classes/app.jar"),
          maven("org.jetbrains.compose.ui", "ui-desktop", "jar"),
          maven("org.jetbrains.kotlin", "kotlin-stdlib", "jar"),
          ClasspathEntry.Project(path = ":meshcore-core", inlinedAs = "libs/full.jar"),
        ),
      )
  }

  @Test
  fun `an aar is flagged even when its artifact id does not end in -android`() {
    val e =
      assertThrows(GradleException::class.java) {
        task()
          .failOnAndroidClasspathInDesktopBundle(
            "desktop",
            listOf(maven("androidx.versionedparcelable", "versionedparcelable", "aar")),
          )
      }
    assertThat(e).hasMessageThat().contains("versionedparcelable")
  }
}
