package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `composePreviewBundle` must resolve its consumer runtime configuration at TASK-REALIZATION time,
 * not while [ComposePreviewTasks.registerBundleTask] is running.
 *
 * A `com.android.kotlin.multiplatform.library` module reaches `registerDesktopTasks` through
 * `pluginManager.withPlugin`, which fires BEFORE the consumer's `kotlin { jvm("desktop") }` block
 * has created `desktopRuntimeClasspath`. Resolving eagerly therefore fell through to the
 * `androidRuntimeClasspath` last-resort fallback even on a module that *does* declare a desktop
 * target — and packed that module's `*-android` Compose AARs into a bundle stamped `backend:
 * desktop`. Nothing failed the build (the `onlyIf` renderability gate re-resolved lazily and
 * correctly saw `desktopRuntimeClasspath`), so the broken bundle published, and every live render
 * of it died on the host JVM with `NoClassDefFoundError: android/os/Parcelable`.
 *
 * These tests pin the deferral by creating `desktopRuntimeClasspath` AFTER registration — exactly
 * the ordering the real KMP-Android + `jvm("desktop")` shape produces.
 */
class BundleDependencyConfigDeferralTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun jar(name: String): java.io.File = tmp.newFile(name).apply { writeText("stub") }

  @Test
  fun `bundle binds the desktop classpath when the desktop target configures after registration`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // The KMP-Android plugin's configuration exists at withPlugin time...
    val androidOnly = jar("android-only.jar")
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.dependencies.add("androidRuntimeClasspath", project.files(androidOnly))

    // ...and registration happens right there, with the real candidate resolver.
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/android/main"),
      resolveDependencyConfigName = { ComposePreviewTasks.desktopDependencyConfigName(project) },
      discoverTaskName = "composePreviewDiscover",
    )

    // Only NOW does `kotlin { jvm("desktop") }` create the JVM-flavoured runtime classpath.
    val desktopOnly = jar("desktop-only.jar")
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.dependencies.add("desktopRuntimeClasspath", project.files(desktopOnly))

    // Realising the task is what triggers the deferred resolution.
    val task = project.tasks.getByName("composePreviewBundle") as BundlePreviewTask
    val wired = task.dependencyJars.files

    assertThat(wired).contains(desktopOnly)
    assertThat(wired).doesNotContain(androidOnly)
  }

  @Test
  fun `a pure KMP-Android module still falls back to androidRuntimeClasspath`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // No `jvm("desktop")` target ever appears — the #1852 shape. The fallback must stay, so
    // discovery and the Tooling-API model keep working on such a module.
    val androidOnly = jar("android-only.jar")
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.dependencies.add("androidRuntimeClasspath", project.files(androidOnly))

    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = project.layout.buildDirectory.dir("compose-previews"),
      sourceClassDirs = project.files("build/classes/kotlin/android/main"),
      resolveDependencyConfigName = { ComposePreviewTasks.desktopDependencyConfigName(project) },
      discoverTaskName = "composePreviewDiscover",
    )

    val task = project.tasks.getByName("composePreviewBundle") as BundlePreviewTask
    assertThat(task.dependencyJars.files).contains(androidOnly)
  }

  @Test
  fun `desktop config wins over the android fallback once both exist`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.configurations.create("androidRuntimeClasspath")
    assertThat(ComposePreviewTasks.desktopDependencyConfigName(project))
      .isEqualTo("androidRuntimeClasspath")

    project.configurations.create("desktopRuntimeClasspath")
    assertThat(ComposePreviewTasks.desktopDependencyConfigName(project))
      .isEqualTo("desktopRuntimeClasspath")

    // A `jvm()` target (rather than `jvm("desktop")`) outranks both.
    project.configurations.create("jvmRuntimeClasspath")
    assertThat(ComposePreviewTasks.desktopDependencyConfigName(project))
      .isEqualTo("jvmRuntimeClasspath")
  }
}
