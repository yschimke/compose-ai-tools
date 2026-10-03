package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An external desktop consumer — one with no compose-preview-daemon checkout and no BOM of its own
 * — gets `renderer-desktop` and `daemon-desktop` versionless, versioned by the
 * `compose-preview-daemon-bom` platform the plugin imports onto the same configuration, and both
 * resolve from Maven Central.
 *
 * The daemon publishes only the modules a release changes, so the old shape — each module pinned at
 * the daemon release version — would 404 for every consumer the first time a release skipped one of
 * them. See [PreviewDaemonModules].
 *
 * Resolves component metadata only (`resolutionResult`), never artifacts, so it costs POM and
 * Gradle-module downloads rather than the renderer's Skiko natives.
 */
class DaemonBomInjectionFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun createDesktopConsumer(): File {
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
        rootProject.name = "test-daemon-bom-injection"
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
            implementation(compose.components.uiToolingPreview)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }

        tasks.register("dumpDaemonModules") {
            val names = listOf("composePreviewRenderer", "composePreviewDesktopDaemon")
            doLast {
                names.forEach { name ->
                    val cfg = configurations.getByName(name)
                    cfg.dependencies.forEach { d ->
                        val category = (d as? ModuleDependency)
                            ?.attributes?.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
                        println("DECLARED ${'$'}name ${'$'}{d.group}:${'$'}{d.name}:${'$'}{d.version ?: ""} ${'$'}{category ?: "-"}")
                    }
                    val result = cfg.incoming.resolutionResult
                    result.allDependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                        .forEach {
                            println("UNRESOLVED ${'$'}name ${'$'}{it.attempted.displayName}")
                            println("REASON ${'$'}{it.failure.message?.lines()?.take(3)?.joinToString(" | ")}")
                        }
                    result.allComponents.forEach { c ->
                        val id = c.moduleVersion ?: return@forEach
                        if (id.group == "ee.schimke.composeai") {
                            println("RESOLVED ${'$'}name ${'$'}{id.name}:${'$'}{id.version}")
                        }
                    }
                }
            }
        }
        """
          .trimIndent()
      )
    return projectDir
  }

  @Test
  fun `desktop renderer and daemon resolve versionless through the daemon BOM`() {
    val result =
      GradleRunner.create()
        .withProjectDir(createDesktopConsumer())
        .withArguments("dumpDaemonModules", "-q", "--stacktrace")
        .withPluginClasspath()
        .build()
    val lines = result.output.lines()
    fun tagged(tag: String, config: String) =
      lines.filter { it.startsWith("$tag $config ") }.map { it.removePrefix("$tag $config ") }

    val bom = "ee.schimke.composeai:compose-preview-daemon-bom"
    for ((config, module) in
      listOf(
        "composePreviewRenderer" to "renderer-desktop",
        "composePreviewDesktopDaemon" to "daemon-desktop",
      )) {
      val declared = tagged("DECLARED", config)
      // The module carries no version of its own...
      assertThat(declared).contains("ee.schimke.composeai:$module: -")
      // ...and the BOM that supplies one sits on the same configuration, as a platform.
      val platform = declared.single { it.startsWith("$bom:") }
      assertThat(platform).endsWith(" platform")
      val bomVersion = platform.removePrefix("$bom:").substringBefore(' ')
      assertThat(bomVersion).isNotEmpty()

      assertWithMessage(lines.filter { it.startsWith("REASON") }.joinToString("\n"))
        .that(tagged("UNRESOLVED", config))
        .isEmpty()
      val resolved = tagged("RESOLVED", config)
      assertThat(resolved).contains("compose-preview-daemon-bom:$bomVersion")
      // The version comes from the BOM, which is the point: a module the release skipped sits at
      // its last published version there, never at a release version it was never published at.
      assertThat(resolved.single { it.startsWith("$module:") }).isNotEqualTo("$module:")
    }
  }
}
