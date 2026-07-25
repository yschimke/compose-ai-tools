package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1852 regression: the desktop daemon-start path must resolve a KMP-Android module's
 * `androidRuntimeClasspath` through an `artifactType`-pinned artifact view.
 *
 * A pure `com.android.kotlin.multiplatform.library` dependency publishes an
 * `androidRuntimeElements` whose runtime graph carries ~12 secondary variants keyed by
 * `artifactType` (`android-classes-jar`, `android-aar-metadata`, … `jar`) and no unambiguous
 * default. Resolving the consumer's `androidRuntimeClasspath` through a bare `incoming.artifactView
 * {}` (no attributes) then fails with `AmbiguousArtifactsFailure` — "cannot choose between the
 * following variants" — under AGP 9.3's stricter matching, which sank `composePreviewDaemonStart`
 * (and with it the a11y pipeline that drives it) on such modules.
 * `ComposePreviewTasks.wireDesktopBtaInputs` must pin `artifactType=jar` (via
 * `pinnedConsumerClasspath`) exactly like the render / discover / guard consumer views already do —
 * this is the missing coverage for that desktop path (the existing `CliA11yEndToEndFunctionalTest`
 * exercises the classic `com.android.library` path only).
 *
 * Reproduced in plain Gradle — no AGP, Android SDK, or published renderer. The ambiguity is a pure
 * Gradle attribute-matching phenomenon: a producer subproject exposes an `androidRuntimeElements`-
 * shaped consumable configuration with multiple `artifactType` secondary variants and no base
 * artifact, and a consumer whose only runtime configuration is `androidRuntimeClasspath` (the
 * candidate the desktop path picks ahead of `runtimeClasspath`) depends on it. The build resolves
 * only `composePreviewDaemonStart.btaCompileClasspath` (the wiring under test) — never the daemon's
 * renderer closure — so the test stays hermetic and runs in the standard `functionalTest` job with
 * no `publishToMavenLocal` pre-step.
 */
class KmpAndroidDaemonClasspathFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `daemon-start bta classpath resolves a multi-variant androidRuntimeClasspath`() {
    val projectDir = createKmpAndroidConsumerProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("resolveDaemonBtaClasspath", "--stacktrace")
        .withPluginClasspath()
        .build()

    // A bare artifact view would fail resolving `:lib`'s runtime variants with
    // AmbiguousArtifactsFailure; the `artifactType=jar`-pinned view resolves cleanly.
    assertThat(result.output).doesNotContain("cannot choose between")
    assertThat(result.output).doesNotContain("AmbiguousArtifactsFailure")
    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  private fun createKmpAndroidConsumerProject(): File {
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
        rootProject.name = "kmp-android-daemon-test"
        include(":lib")
        """
          .trimIndent()
      )

    // Producer: an `androidRuntimeElements`-shaped consumable configuration exposing several
    // `artifactType` secondary variants (mirroring AGP's KMP-Android runtime) with no base
    // artifact.
    // The artifact files need not exist — variant *selection* fails before artifact *access*.
    val libDir = File(projectDir, "lib").apply { mkdirs() }
    File(libDir, "build.gradle.kts")
      .writeText(
        """
        val artifactType = Attribute.of("artifactType", String::class.java)
        val elements =
            configurations.consumable("androidRuntimeElements") {
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                }
            }
        listOf("android-classes-jar", "android-aar-metadata", "jar").forEach { type ->
            elements.get().outgoing.variants.create(type) {
                attributes.attribute(artifactType, type)
                artifact(layout.buildDirectory.file("stub-${'$'}type.jar"))
            }
        }
        """
          .trimIndent()
      )

    // Consumer: the desktop path picks `androidRuntimeClasspath` ahead of `runtimeClasspath`, so a
    // Kotlin/JVM + Compose module whose `androidRuntimeClasspath` carries the multi-variant
    // producer
    // exercises exactly the daemon-start classpath resolution that regressed. The custom task
    // resolves only `composePreviewDaemonStart.btaCompileClasspath` — the wiring under test — which
    // triggers the `androidRuntimeClasspath` view without realizing the daemon's renderer closure.
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        @file:Suppress("DEPRECATION")

        import ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask

        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        dependencies {
            implementation(compose.desktop.currentOs)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        val androidRuntimeClasspath by
            configurations.creating {
                isCanBeResolved = true
                isCanBeConsumed = false
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                }
            }
        dependencies { androidRuntimeClasspath(project(":lib")) }

        // Resolve only the daemon-start BTA compile classpath (the fixed `androidRuntimeClasspath`
        // view); the daemon's renderer closure is never realized, so no published renderer is needed.
        tasks.register("resolveDaemonBtaClasspath") {
            val bta =
                tasks.named("composePreviewDaemonStart", DaemonBootstrapTask::class.java).map {
                    it.btaCompileClasspath
                }
            doLast { logger.lifecycle("resolved BTA classpath entries: ${'$'}{bta.get().files.size}") }
        }
        """
          .trimIndent()
      )

    return projectDir
  }
}
