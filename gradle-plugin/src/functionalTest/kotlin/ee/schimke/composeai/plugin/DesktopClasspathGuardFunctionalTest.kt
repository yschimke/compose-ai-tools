package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Configuration-cache coverage for the desktop classpath guard
 * ([ValidateComposePreviewClasspathTask], wired by
 * `ComposePreviewTasks.registerDesktopClasspathGuard`).
 *
 * Issue #1796: the guard fed its `@Classpath` collection with `classpath.from(toolClasspath)`,
 * pinning the live `Configuration` into the task's `__classpath__` backing field. When that
 * configuration resolves the published `renderer-desktop` graph, the configuration cache can't
 * serialize the reference — the nested TestKit bundle E2E builds (config cache on) failed the store
 * step with "field `__classpath__` … error writing value" and the bundle task exited 1, breaking
 * `BundleRenderEndToEndFunctionalTest` / `BundleDaemonEndToEndFunctionalTest`. Those checks are
 * non-gating, so it had been merging red. The fix feeds a lazily-resolved `incoming.artifactView {
 * }.files` view instead, dropping the unserializable `Configuration` reference (mirrors how the
 * sibling `composePreviewDiscover` task already resolves its classpath).
 *
 * The full failure needs the published multi-module renderer graph the bundle E2E sets up, which a
 * synthetic temp project can't resolve. This gating test instead locks in that the desktop validate
 * path round-trips the configuration cache (store + reuse) for the common single-module case, so a
 * regression that breaks serialization outright fails a required check rather than only the
 * advisory bundle E2E. Both desktop guard tasks share the one helper, so the render variant covers
 * both.
 *
 * `composePreviewRenderer` is pre-seeded with a resolvable Compose artifact —
 * `ensureRendererDesktopConfig` skips its Maven add when the config already has dependencies — so
 * the guard resolves a real classpath without reaching for the unpublished
 * `ee.schimke.composeai:renderer-desktop` coordinate.
 */
class DesktopClasspathGuardFunctionalTest {

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
        rootProject.name = "test-desktop-guard"
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
            implementation(compose.uiTooling)
            implementation(compose.components.uiToolingPreview)
        }
        // Pre-seed the renderer tool classpath with a resolvable artifact so
        // `ensureRendererDesktopConfig` skips its Maven add for the unpublished
        // `ee.schimke.composeai:renderer-desktop` coordinate (which a synthetic temp project can't
        // see). The guard then resolves a real classpath and exercises the configuration-cache store.
        configurations.maybeCreate("composePreviewRenderer")
        dependencies {
            "composePreviewRenderer"(compose.material3)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        """
          .trimIndent()
      )

    // Configuration cache on — mirrors the repo + the nested bundle E2E builds. A non-serializable
    // `__classpath__` field aborts the store outright (a store failure is a hard error regardless
    // of
    // the `problems` mode), so a wiring that pins an unserializable reference fails `.build()`
    // here.
    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    return projectDir
  }

  @Test
  fun `desktop render classpath guard serializes cleanly under the configuration cache`() {
    val projectDir = createTestProject()

    // First invocation stores the cache.
    val store =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("validateComposePreviewDesktopRenderClasspath", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(store.output).contains("Configuration cache entry stored")
    assertThat(store.output).doesNotContain("__classpath__")
    assertThat(store.output).doesNotContain("Configuration cache problems found")

    // Second invocation must reuse the stored entry — proves the entry round-trips, not just that
    // the store didn't crash.
    val reuse =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("validateComposePreviewDesktopRenderClasspath", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(reuse.output).contains("Configuration cache entry reused")
  }
}
