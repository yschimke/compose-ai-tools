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

  /**
   * [rendererSeed] stands in for whatever Compose/Skiko the published `renderer-desktop` carries;
   * [consumerCompose] is the Compose Multiplatform plugin version the consumer applies. Varying the
   * two independently is what lets one harness cover both skew directions.
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

  /**
   * The MIRROR of the test above, and the direction the CMP 1.11.1 bump creates (issue #3447): the
   * renderer is now pinned *newer* than an existing consumer, rather than older.
   *
   * This matters because `renderers/desktop/build.gradle.kts` declares Compose with
   * `implementation`, not `compileOnly` — unlike `renderer-android`, the desktop renderer *carries*
   * its Compose and Skiko into the consumer's graph. So raising the repo's `compose-multiplatform`
   * floor to 1.11.1 pushes skiko 0.144.6 at every desktop consumer, including the ones still on
   * 1.10.3 (skiko 0.9.37.4). Those two Skikos are not interchangeable — 0.144.6 introduced
   * `org.jetbrains.skia.PathBuilder`, whose native symbols 0.9.37.4 does not export at all.
   *
   * The property that keeps such a consumer working is the same fold as above: both Skikos must
   * collapse to ONE version rather than landing side by side. If they ever split, the older native
   * pairs with the newer bindings and every path-touching render dies with `UnsatisfiedLinkError` —
   * the exact production failure #3447 reported, just reached from the other side.
   *
   * Without this case the bump would have shipped with only the older-renderer direction covered.
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

    // Guard against a vacuous pass: every assertion below is over a *set of Skiko versions*, and
    // "at most one" / "none equals the old version" are both trivially true of an empty set. If the
    // seed ever stops dragging Skiko onto the tool classpath this test would silently stop testing
    // anything, so require the artifacts to actually be there first.
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

    // And the bindings the renderer was COMPILED against must be the ones that win. Gradle resolves
    // to the max version, so the renderer's newer Skiko carries the older consumer up rather than
    // the consumer dragging the renderer down to a native that lacks `PathBuilder_*`.
    if (skikoAwtVersions.isNotEmpty() && skikoRuntimeVersions.isNotEmpty()) {
      assertThat(skikoAwtVersions.single()).isEqualTo(skikoRuntimeVersions.single())
    }
    (skikoAwtVersions + skikoRuntimeVersions).forEach { assertThat(it).isNotEqualTo("0.9.37.4") }
  }
}
