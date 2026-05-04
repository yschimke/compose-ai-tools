package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class ValidateComposePreviewClasspathTaskTest {

  @Test
  fun `desktop validator flags androidx compose maven cache paths`() {
    val offenders =
      ValidateComposePreviewClasspathTask.androidxComposeArtifactsOnDesktopClasspath(
        listOf(
          "/home/user/.gradle/caches/modules-2/files-2.1/androidx.compose.ui/ui-jvmstubs/1.9.5/ui-jvmstubs-1.9.5.jar",
          "/home/user/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.ui/ui-desktop/1.10.3/ui-desktop-1.10.3.jar",
        )
      )

    assertThat(offenders)
      .containsExactly(
        "/home/user/.gradle/caches/modules-2/files-2.1/androidx.compose.ui/ui-jvmstubs/1.9.5/ui-jvmstubs-1.9.5.jar"
      )
  }

  @Test
  fun `desktop validator fails before emitting mixed compose classpath`() {
    val project = ProjectBuilder.builder().build()
    val badJar =
      project.layout.buildDirectory
        .file("fake-cache/androidx.compose.ui/ui-jvmstubs/1.9.5/ui-jvmstubs-1.9.5.jar")
        .get()
        .asFile
    badJar.parentFile.mkdirs()
    badJar.writeText("not a real jar")

    val task =
      project.tasks.register(
        "validateComposePreviewDesktopClasspath",
        ValidateComposePreviewClasspathTask::class.java,
      ) {
        platform.set("desktop")
        classpath.from(badJar)
      }

    val thrown = runCatching { task.get().validate() }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(GradleException::class.java)
    assertThat(thrown!!.message).contains("AndroidX Compose UI artifacts")
    assertThat(thrown.message).contains("ui-jvmstubs-1.9.5.jar")
  }

  @Test
  fun `android validator allows androidx compose artifacts`() {
    val project = ProjectBuilder.builder().build()
    val badJar =
      project.layout.buildDirectory
        .file("fake-cache/androidx.compose.ui/ui/1.9.5/ui-1.9.5.jar")
        .get()
        .asFile
    badJar.parentFile.mkdirs()
    badJar.writeText("not a real jar")

    val task =
      project.tasks.register(
        "validateComposePreviewAndroidClasspath",
        ValidateComposePreviewClasspathTask::class.java,
      ) {
        platform.set("android")
        classpath.from(badJar)
      }

    task.get().validate()
  }
}
