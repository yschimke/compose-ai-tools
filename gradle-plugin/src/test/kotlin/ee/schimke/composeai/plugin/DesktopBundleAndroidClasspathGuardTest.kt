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

  /**
   * Backs each decision with a real file, so `assembleClasspath(embed = true)` takes its genuine
   * embedding branch rather than the "no file to embed" coordinate fallback.
   */
  private fun dep(
    coordinate: String?,
    kept: Boolean = true,
    projectPath: String? = null,
  ): DependencyDecision {
    val name = (coordinate ?: projectPath!!).replace(':', '_').replace('.', '_') + ".jar"
    val file = tmp.newFile(name).apply { writeText("stub") }
    return DependencyDecision(
      sourcePath = file.absolutePath,
      coordinate = coordinate,
      projectPath = projectPath,
      totalClasses = 10,
      reachableClasses = if (kept) 5 else 0,
      originalBytes = 1024,
      kept = kept,
    )
  }

  private fun jarsFor(deps: List<DependencyDecision>) = deps.map { java.io.File(it.sourcePath) }

  /**
   * The exact shape that shipped in the meshcore-mobile `:meshcore-components` bundle.
   *
   * A function, not a field: [dep] writes into [tmp], whose root only exists once the JUnit rule
   * has run — a field initializer would evaluate at construction time and fail every test.
   */
  private fun androidComposeDeps() =
    listOf(
      dep("androidx.compose.ui:ui-android:1.11.4:aar"),
      dep("androidx.compose.material3:material3-android:1.5.0-alpha08:aar"),
      dep("org.jetbrains.kotlin:kotlin-stdlib:2.4.10:jar"),
    )

  @Test
  fun `desktop bundle carrying android artifacts fails the pack`() {
    val e =
      assertThrows(GradleException::class.java) {
        task().failOnAndroidClasspathInDesktopBundle("desktop", androidComposeDeps())
      }
    assertThat(e).hasMessageThat().contains("NoClassDefFoundError: android/os/Parcelable")
    assertThat(e).hasMessageThat().contains("androidx.compose.ui:ui-android:1.11.4")
    assertThat(e).hasMessageThat().contains("androidx.compose.material3:material3-android")
    // Actionable next step, not just a diagnosis.
    assertThat(e).hasMessageThat().contains("jvm(\"desktop\")")
    // Pure-JVM entries are not reported as offenders.
    assertThat(e).hasMessageThat().doesNotContain("kotlin-stdlib")
  }

  /**
   * Regression for the `--embed-deps` hole: `assembleClasspath` rewrites every kept Maven dep into
   * a metadata-free `ClasspathEntry.Embedded`, so a guard reading the assembled entries would find
   * no coordinates and pass a bundle that is just as broken — the AGP-transformed `classes.jar` is
   * then carried *inside* `libs/` rather than referenced. Keying on
   * [DependencyDecision.coordinate], which is identical in both modes, is what closes it.
   */
  @Test
  fun `embed mode is covered because the check reads decisions not assembled entries`() {
    val deps = androidComposeDeps()
    val embedded =
      task()
        .assembleClasspath(jars = jarsFor(deps), deps = deps, embed = true)
        .entries
        .filterIsInstance<ClasspathEntry.Maven>()
    // Precondition: embed mode really does erase the Maven metadata the naive guard relied on.
    assertThat(embedded).isEmpty()

    val e =
      assertThrows(GradleException::class.java) {
        task().failOnAndroidClasspathInDesktopBundle("desktop", deps)
      }
    assertThat(e).hasMessageThat().contains("ui-android")
  }

  @Test
  fun `an android bundle may carry android artifacts`() {
    // The Robolectric sandbox supplies android.jar, so this is the correct, expected shape.
    task().failOnAndroidClasspathInDesktopBundle("android", androidComposeDeps())
  }

  @Test
  fun `a clean desktop classpath passes`() {
    task()
      .failOnAndroidClasspathInDesktopBundle(
        "desktop",
        listOf(
          dep("org.jetbrains.compose.ui:ui-desktop:1.11.1:jar"),
          dep("org.jetbrains.kotlin:kotlin-stdlib:2.4.10:jar"),
          dep(coordinate = null, projectPath = ":meshcore-core"),
        ),
      )
  }

  @Test
  fun `a dropped dependency is not an offender`() {
    // Not on the bundle's classpath at all — nothing to fail over.
    task()
      .failOnAndroidClasspathInDesktopBundle(
        "desktop",
        listOf(dep("androidx.compose.ui:ui-android:1.11.4:aar", kept = false)),
      )
  }

  @Test
  fun `an aar is flagged even when its artifact id does not end in -android`() {
    val e =
      assertThrows(GradleException::class.java) {
        task()
          .failOnAndroidClasspathInDesktopBundle(
            "desktop",
            listOf(dep("androidx.versionedparcelable:versionedparcelable:1.1.1:aar")),
          )
      }
    assertThat(e).hasMessageThat().contains("versionedparcelable")
  }
}
