package ee.schimke.composeai.plugin

import java.io.File

/**
 * Shared scaffolding for the bundle-{render,daemon} end-to-end tests — a synthetic Compose Desktop
 * project with one `@Preview` composable, plus helpers to drive `compose-preview bundle pack`
 * against it. Lives next to the tests so the e2e gating + sysprops stay co-located with the
 * assertions that consume them.
 */
internal object BundleE2EFixture {

  /**
   * Write a one-preview Compose Desktop project into [projectDir] (a fresh `TemporaryFolder.root`
   * works). Returns [projectDir] for fluent chaining. Mirrors the layout
   * `CliA11yEndToEndFunctionalTest` uses — no `id("ee.schimke.composeai.preview")` on purpose so
   * the bundled `--init-script` is exercised. The single preview is named
   * `test.PreviewsKt.SimpleBoxPreview`.
   */
  fun createDesktopProject(projectDir: File): File {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenLocal()
                google()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                mavenLocal()
                google()
                mavenCentral()
            }
        }
        rootProject.name = "bundle-e2e"
        include(":app")
        """
          .trimIndent()
      )

    val appDir = File(projectDir, "app").apply { mkdirs() }
    File(appDir, "build.gradle.kts")
      .writeText(
        """
        @file:Suppress("DEPRECATION")
        // No `id("ee.schimke.composeai.preview")` on purpose — the CLI's bundled `--init-script`
        // auto-injects it onto any project that applies `org.jetbrains.compose`. Pre-applying
        // the plugin here would mask any regression in auto-inject. See
        // `CliA11yEndToEndFunctionalTest` for the equivalent Android-flavour coverage.
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
        }
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.uiTooling)
            implementation(compose.components.uiToolingPreview)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    // `compose-preview` walks up looking for `gradlew` to identify the project root. A stub is
    // enough — the CLI drives Gradle via the Tooling API, not by `exec`-ing this script.
    File(projectDir, "gradlew").writeText("#!/usr/bin/env bash\nexit 1\n")

    val srcDir = File(appDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun SimpleBoxPreview() {
            Box(modifier = Modifier.size(120.dp).background(Color(0xFF3F51B5)))
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  /** The single preview id [createDesktopProject] produces. */
  const val PREVIEW_ID: String = "test.PreviewsKt.SimpleBoxPreview"
}
