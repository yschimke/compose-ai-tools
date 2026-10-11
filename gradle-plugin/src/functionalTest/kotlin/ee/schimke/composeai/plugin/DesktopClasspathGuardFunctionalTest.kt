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
 * `ComposePreviewTasks.registerDesktopClasspathGuard`). Feeding `@Classpath` a live `Configuration`
 * made the cache fail to serialize `__classpath__` (see #1796); it now takes a lazy
 * `incoming.artifactView { }.files`.
 *
 * The full failure needs the published renderer graph; this gating test checks the single-module
 * case round-trips the cache (store + reuse). `composePreviewRenderer` is pre-seeded with a
 * resolvable artifact so `ensureRendererDesktopConfig` skips the unpublished `renderer-desktop`
 * coordinate.
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

    // Configuration cache on: a non-serializable field fails the store regardless of `problems`
    // mode, so `.build()` fails here.
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
