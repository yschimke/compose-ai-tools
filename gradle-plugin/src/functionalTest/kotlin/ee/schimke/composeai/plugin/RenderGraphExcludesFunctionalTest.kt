package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `composePreview { renderGraph { exclude(…) } }` lets a consumer keep a strict-version platform
 * off the render graph without touching internal configuration names (see #4995). The render
 * configuration deliberately `extendsFrom` the consumer's classpath, so it inherits `strictly(v)` +
 * `reject("(v,")` constraints that a newer renderer dependency can't satisfy. Guava stands in: the
 * platform pins 31.1-jre, the renderer config asks for 33.0.0-jre.
 */
class RenderGraphExcludesFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  /**
   * [renderGraphBlock] is spliced into the `composePreview { }` extension, so one harness covers
   * the unconfigured build (the failure), the DSL and — via [extraArgs] — the Gradle property.
   */
  private fun createTestProject(renderGraphBlock: String = ""): File {
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
        rootProject.name = "test-render-graph-excludes"
        include(":constraints")
        """
          .trimIndent()
      )

    // The consumer's version-constraints platform: `strictly` + `reject` on every module it
    // manages, exactly the shape that makes a newer renderer dependency unresolvable rather than
    // merely downgraded.
    File(projectDir, "constraints").mkdirs()
    File(projectDir, "constraints/build.gradle.kts")
      .writeText(
        """
        plugins {
            `java-platform`
        }
        group = "com.example"
        version = "1.0"
        dependencies {
            constraints {
                api("com.google.guava:guava") {
                    version {
                        strictly("31.1-jre")
                        reject("(31.1-jre,")
                    }
                }
            }
        }
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
            // The platform on `implementation`, as the reporting consumer applies it: it reaches
            // `runtimeClasspath`, which the render configuration extends.
            implementation(platform(project(":constraints")))
            implementation(compose.desktop.currentOs)
        }
        // Stand in for the published renderer: pre-seeding the tool config makes
        // `ensureRendererDesktopConfig` skip its Maven add for the unpublished
        // `ee.schimke.composeai:renderer-desktop` coordinate. The seed is a module the consumer's
        // platform rejects, which is the whole conflict under test.
        configurations.maybeCreate("composePreviewRenderer")
        dependencies {
            "composePreviewRenderer"("com.google.guava:guava:33.0.0-jre")
        }
        composePreview {
            $renderGraphBlock
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }

        tasks.register("dumpRendererClasspath") {
            val rendererCfg = configurations.getByName("composePreviewRenderer")
            doLast {
                rendererCfg.incoming.artifactView { }.files.forEach { println("RENDERER_JAR ${'$'}{it.name}") }
            }
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  private fun runner(projectDir: File, vararg extraArgs: String) =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("dumpRendererClasspath", "-q", "--stacktrace", *extraArgs)
      .withPluginClasspath()

  private fun rendererJars(output: String): List<String> =
    output
      .lineSequence()
      .filter { it.startsWith("RENDERER_JAR ") }
      .map { it.removePrefix("RENDERER_JAR ").trim() }
      .toList()

  @Test
  fun `without an exclusion the inherited strict platform makes the render graph unresolvable`() {
    // The bug report, reproduced. This is the baseline the DSL has to move: if this ever starts
    // passing on its own, the two tests below stop proving anything.
    val result = runner(createTestProject()).buildAndFail()

    assertThat(result.output).contains("guava")
    assertThat(result.output).contains("composePreviewRenderer")
  }

  @Test
  fun `the renderGraph DSL keeps the platform off the render graph`() {
    val projectDir =
      createTestProject(
        renderGraphBlock =
          """renderGraph { exclude(group = "com.example", module = "constraints") }"""
      )

    val result = runner(projectDir).build()

    // Resolution now succeeds AND lands on the renderer's own version: the exclusion removed the
    // platform's pressure rather than merely tolerating the conflict.
    assertThat(rendererJars(result.output)).contains("guava-33.0.0-jre.jar")
  }

  @Test
  fun `the gradle property is equivalent for a build script the consumer cannot edit`() {
    // The CLI auto-injects the plugin into builds that never mention it, so there is not always a
    // `composePreview { }` block to put the DSL in.
    val result =
      runner(createTestProject(), "-PcomposePreview.renderGraphExcludes=com.example:constraints")
        .build()

    assertThat(rendererJars(result.output)).contains("guava-33.0.0-jre.jar")
  }

  @Test
  fun `the consumer's own classpath keeps the platform`() {
    val projectDir =
      createTestProject(
        renderGraphBlock =
          """renderGraph { exclude(group = "com.example", module = "constraints") }"""
      )
    File(projectDir, "build.gradle.kts")
      .appendText(
        """

        tasks.register("dumpConsumerConstraints") {
            val runtime = configurations.getByName("runtimeClasspath")
            doLast {
                runtime.incoming.resolutionResult.allComponents.forEach {
                    println("CONSUMER_COMPONENT ${'$'}{it.id.displayName}")
                }
            }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("dumpConsumerConstraints", "-q", "--stacktrace")
        .withPluginClasspath()
        .build()

    // The exclusion is scoped to configurations the plugin owns. A consumer's normal build — and
    // every guarantee their platform gives it — must resolve exactly as it did before.
    assertThat(result.output).contains("CONSUMER_COMPONENT project ':constraints'")
  }
}
