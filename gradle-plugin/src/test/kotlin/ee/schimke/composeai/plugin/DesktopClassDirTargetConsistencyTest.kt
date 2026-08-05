package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The classes a desktop bundle carries must describe the SAME target as the dependencies it
 * resolves.
 *
 * `classes/kotlin/android/main` is the issue-#248 fallback for a module whose only compilation is
 * the KMP-Android one. On a dual-target module (KMP-Android *plus* `jvm("desktop")` — the
 * `samples/cmp-shared` / `:meshcore-components` shape) both that dir and `desktop/main` exist and
 * hold the same commonMain classes compiled for different targets. Packing both mixes them: the
 * android-compiled `MeshcoreFontsKt` facade wins and calls `MeshcoreFonts_androidKt`, while the
 * desktop pack carries `MeshcoreFonts_desktopKt`, so the render dies with `NoClassDefFoundError:
 * ee/schimke/meshcore/components/ui/theme/MeshcoreFonts_androidKt` — one layer past the `*-android`
 * dependency problem that [BundleDependencyConfigDeferralTest] covers, and only reachable once that
 * one is fixed.
 */
class DesktopClassDirTargetConsistencyTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun classDirsFor(configNames: List<String>): List<String> {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    // Both compilation outputs on disk — the dual-target shape.
    project.layout.buildDirectory.dir("classes/kotlin/android/main").get().asFile.mkdirs()
    project.layout.buildDirectory.dir("classes/kotlin/desktop/main").get().asFile.mkdirs()
    configNames.forEach { name ->
      project.configurations.create(name) {
        isCanBeResolved = true
        isCanBeConsumed = false
      }
    }
    ComposePreviewTasks.registerDesktopTasks(project, extension)
    val discover = project.tasks.getByName("composePreviewDiscover") as DiscoverPreviewsTask
    return discover.classDirs.files.map {
      it.relativeTo(project.projectDir).invariantSeparatorsPath
    }
  }

  @Test
  fun `a dual-target module does not pack the android compilation output`() {
    val dirs = classDirsFor(listOf("androidRuntimeClasspath", "desktopRuntimeClasspath"))
    assertThat(dirs).contains("build/classes/kotlin/desktop/main")
    // The whole point: mixing this in is what produced the NoClassDefFoundError.
    assertThat(dirs).doesNotContain("build/classes/kotlin/android/main")
  }

  @Test
  fun `a pure KMP-Android module still gets the android compilation output`() {
    // Issue #248 must keep working — this module has no JVM compilation to fall back to.
    val dirs = classDirsFor(listOf("androidRuntimeClasspath"))
    assertThat(dirs).contains("build/classes/kotlin/android/main")
  }

  @Test
  fun `a jvm target also excludes the android compilation output`() {
    val dirs = classDirsFor(listOf("androidRuntimeClasspath", "jvmRuntimeClasspath"))
    assertThat(dirs).doesNotContain("build/classes/kotlin/android/main")
  }
}
