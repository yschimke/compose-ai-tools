package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proves the desktop renderer config resolves in the consumer's graph so one version of each shared
 * module wins (#1844). Merging raw `FileCollection`s put a consumer's newer Skiko bindings beside
 * the renderer's older native library → `UnsatisfiedLinkError`;
 * `ComposePreviewTasks.alignDesktopToolWithConsumerGraph` fixes it with `extendsFrom`.
 *
 * Simulated by seeding `composePreviewRenderer` with an older `material3` than the consumer
 * resolves: with the fold, conflict resolution collapses to the newer one.
 */
class DesktopRendererGraphAlignmentFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  /**
   * [rendererSeed] stands in for the renderer's Compose/Skiko; [consumerCompose] is the consumer's
   * CMP plugin version. Varying both covers both skew directions.
   */
  private fun createTestProject(
    rendererSeed: String = "org.jetbrains.compose.material3:material3:1.7.3",
    consumerCompose: String = "1.10.3",
  ): File {
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
            id("org.jetbrains.compose") version "$consumerCompose"
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
            "composePreviewRenderer"("$rendererSeed")
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

        // The classpath the render JVM is actually launched with — tool jars AND whatever the
        // render task adds of the consumer's own classpath — so a test can see a second copy of a
        // module that the tool config alone never shows.
        tasks.register("dumpRenderTaskClasspath") {
            val renderCp = tasks.named("composePreviewRender").map {
                it.property("renderClasspath") as FileCollection
            }
            doLast {
                renderCp.get().forEach { println("RENDER_CP ${'$'}{it.name}") }
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

    // At most one Skiko native runtime version. "No duplicate" rather than "exactly one" tolerates
    // a late native download on a cold cache.
    val skikoRuntimeVersions =
      jars
        .filter { it.startsWith("skiko-awt-runtime") }
        .map { it.substringAfterLast('-').removeSuffix(".jar") }
        .toSet()
    assertThat(skikoRuntimeVersions.size).isAtMost(1)

    // Proof of folding: the seeded older material3 is replaced by the consumer's version, not
    // carried alongside.
    val material3Jars = jars.filter { it.startsWith("material3-") && it.endsWith(".jar") }
    assertThat(material3Jars).hasSize(1)
    assertThat(material3Jars.single()).doesNotContain("1.7.3")
  }

  /**
   * The mirror case (#3447): the renderer pinned newer than the consumer. The desktop renderer
   * carries its Compose and Skiko via `implementation`, so a CMP bump pushes a newer Skiko (with
   * `PathBuilder` natives the old one lacks) at older consumers. Both must still collapse to one
   * version.
   */
  @Test
  fun `a consumer older than the renderer still folds to one coherent Skiko`() {
    // Consumer on the previous floor (1.10.3 -> skiko 0.9.37.4); renderer carrying what the repo
    // now pins (CMP 1.11.1 -> skiko 0.144.6), mirroring `renderer-desktop`'s own `implementation`.
    val projectDir =
      createTestProject(
        rendererSeed = "org.jetbrains.compose.ui:ui-desktop:1.11.1",
        consumerCompose = "1.10.3",
      )

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

    assertThat(jars).isNotEmpty()

    // Guard against a vacuous pass over an empty set of Skiko versions.
    assertThat(jars.filter { it.startsWith("skiko-awt") }).isNotEmpty()

    // The safety property: bindings and native are one version, so there is no split-Skiko render
    // classpath regardless of which side is newer.
    val skikoRuntimeVersions =
      jars
        .filter { it.startsWith("skiko-awt-runtime") }
        .map { it.substringAfterLast('-').removeSuffix(".jar") }
        .toSet()
    assertThat(skikoRuntimeVersions.size).isAtMost(1)

    val skikoAwtVersions =
      jars
        .filter { it.startsWith("skiko-awt-") && !it.startsWith("skiko-awt-runtime") }
        .map { it.substringAfterLast('-').removeSuffix(".jar") }
        .toSet()
    assertThat(skikoAwtVersions.size).isAtMost(1)

    // The renderer's newer bindings must win (max version), not be dragged down.
    if (skikoAwtVersions.isNotEmpty() && skikoRuntimeVersions.isNotEmpty()) {
      assertThat(skikoAwtVersions.single()).isEqualTo(skikoRuntimeVersions.single())
    }
    (skikoAwtVersions + skikoRuntimeVersions).forEach { assertThat(it).isNotEqualTo("0.9.37.4") }
  }

  /**
   * The render JVM's classpath carries one copy of each shared module. The task used to also
   * prepend the consumer's separately-resolved runtime classpath, putting an older
   * `kotlinx-coroutines-core` ahead of the one `kotlinx-coroutines-test` needs, so every motion
   * capture failed with `NoSuchMethodError`.
   */
  @Test
  fun `the render classpath carries the folded graph once, not the consumer's copy as well`() {
    val projectDir =
      createTestProject(rendererSeed = "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("dumpRenderTaskClasspath", "-q", "--stacktrace")
        .withPluginClasspath()
        .build()

    val coreJars =
      result.output
        .lineSequence()
        .filter { it.startsWith("RENDER_CP ") }
        .map { it.removePrefix("RENDER_CP ").trim() }
        .filter { it.startsWith("kotlinx-coroutines-core-jvm-") }
        .toList()

    // Not vacuous: Compose drags coroutines onto every desktop classpath.
    assertThat(coreJars).isNotEmpty()
    // One core, and it is the version the renderer's coroutines-test was built against.
    assertThat(coreJars).containsExactly("kotlinx-coroutines-core-jvm-1.11.0.jar")
  }
}
