package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proves the desktop renderer config resolves in the consumer's dependency graph so a single
 * coherent (max) version of every shared module wins — the fix for issue #1844.
 *
 * Background: a consumer on Compose Multiplatform 1.11 pulls a newer Skiko whose Java bindings call
 * `org.jetbrains.skia.paragraph.TextStyleKt._nSetFontEdging`; the renderer bundles an older Skiko
 * (pinned to CMP 1.10.3) whose native library doesn't export that symbol. Before the fix the
 * renderer config and the consumer's runtime classpath were merged as raw `FileCollection`s with no
 * cross-graph conflict resolution, so both Skikos landed on the render classpath and the older
 * native + newer bindings collided at runtime with `UnsatisfiedLinkError`.
 * `ComposePreviewTasks.alignDesktopToolWithConsumerGraph` makes the renderer config `extendsFrom`
 * the consumer's runtime classpath, so Gradle picks one coherent version.
 *
 * This test stands in for "renderer pinned older than consumer" by pre-seeding
 * `composePreviewRenderer` with an *older* `org.jetbrains.compose.material3` than the consumer's
 * Compose plugin resolves. Without the fold the renderer config would resolve that older version in
 * isolation; with it, conflict resolution against the consumer graph collapses to the single newer
 * version — the same mechanism that aligns the Skiko native library with the bindings.
 */
class DesktopRendererGraphAlignmentFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun createTestProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                google()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "test-desktop-graph-alignment"
        """
          .trimIndent()
      )

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        @file:Suppress("DEPRECATION")
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.components.uiToolingPreview)
        }
        // Stand in for the renderer: pre-seed the tool config so `ensureRendererDesktopConfig` skips
        // its Maven add for the unpublished `ee.schimke.composeai:renderer-desktop` coordinate (a
        // synthetic temp project can't resolve it). Pin an OLDER material3 than the Compose plugin
        // resolves for the consumer's own deps — without the graph fold the renderer config would
        // resolve this older version on its own.
        configurations.maybeCreate("composePreviewRenderer")
        dependencies {
            "composePreviewRenderer"("org.jetbrains.compose.material3:material3:1.7.3")
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }

        // Resolve the renderer tool classpath and print one line per artifact + the extendsFrom
        // edges so the test can assert on the coherent, single-version result.
        tasks.register("dumpRendererClasspath") {
            val rendererCfg = configurations.getByName("composePreviewRenderer")
            doLast {
                rendererCfg.extendsFrom.forEach { println("RENDERER_EXTENDS ${'$'}{it.name}") }
                rendererCfg.incoming.artifactView { }.files.forEach { println("RENDERER_JAR ${'$'}{it.name}") }
            }
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  @Test
  fun `desktop renderer config folds into the consumer graph and resolves one coherent version`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("dumpRendererClasspath", "-q", "--stacktrace")
        .withPluginClasspath()
        .build()

    val jars =
      result.output
        .lineSequence()
        .filter { it.startsWith("RENDERER_JAR ") }
        .map { it.removePrefix("RENDERER_JAR ").trim() }
        .toList()
    val extendsFrom =
      result.output
        .lineSequence()
        .filter { it.startsWith("RENDERER_EXTENDS ") }
        .map { it.removePrefix("RENDERER_EXTENDS ").trim() }
        .toList()

    // Sanity: the dump task resolved a real classpath (guards against an empty-resolution false
    // pass, and surfaces the actual artifact set in the failure message if anything below trips).
    assertThat(jars).isNotEmpty()

    // The fold is wired: the renderer config extends the consumer's runtime classpath.
    assertThat(extendsFrom).contains("runtimeClasspath")

    // The invariant the fix guarantees: the renderer's transitive Skiko native runtime resolves to
    // at most one version — the side-by-side duplicate (renderer-pinned older + consumer newer)
    // that
    // produced the `UnsatisfiedLinkError` is gone. Asserting "no duplicate version" rather than
    // "exactly one" keeps the test deterministic even when the OS-native variant download lands
    // late
    // on a cold cache.
    val skikoRuntimeVersions =
      jars
        .filter { it.startsWith("skiko-awt-runtime") }
        .map { it.substringAfterLast('-').removeSuffix(".jar") }
        .toSet()
    assertThat(skikoRuntimeVersions.size).isAtMost(1)

    // Positive proof that the graphs were actually folded: conflict resolution against the consumer
    // graph wins, so the pre-seeded older material3 (1.7.3) is replaced by the single version the
    // consumer's Compose plugin resolves (a `-desktop`-suffixed KMP artifact), not carried
    // alongside.
    val material3Jars = jars.filter { it.startsWith("material3-") && it.endsWith(".jar") }
    assertThat(material3Jars).hasSize(1)
    assertThat(material3Jars.single()).doesNotContain("1.7.3")
  }
}
