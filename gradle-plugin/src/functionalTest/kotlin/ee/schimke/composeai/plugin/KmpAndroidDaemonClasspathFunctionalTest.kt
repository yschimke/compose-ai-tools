package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1852 regression: the desktop daemon-start path must resolve a KMP-Android module's
 * `androidRuntimeClasspath` through an `artifactType`-pinned artifact view.
 *
 * A pure `com.android.kotlin.multiplatform.library` dependency publishes an `androidRuntimeElements`
 * whose runtime graph carries ~12 secondary variants keyed by `artifactType` (`android-classes-jar`,
 * `android-aar-metadata`, … `jar`) and no unambiguous default. Resolving the consumer's
 * `androidRuntimeClasspath` through a bare `incoming.artifactView {}` (no attributes) then fails with
 * `AmbiguousArtifactsFailure` — "cannot choose between the following variants" — under AGP 9.3's
 * stricter matching, which sank `composePreviewDaemonStart` (and with it the a11y pipeline that
 * drives it) on such modules. The BTA-input wiring at `ComposePreviewTasks.wireDesktopBtaInputs`
 * must pin `artifactType=jar` (via `pinnedConsumerClasspath`) exactly like the render / discover /
 * guard consumer views already do — this test is the missing coverage for that desktop path (the
 * existing `CliA11yEndToEndFunctionalTest` exercises the classic `com.android.library` path only).
 *
 * The *variant-selection* half is reproduced in plain Gradle — no AGP or Android SDK, since the
 * ambiguity is a pure Gradle attribute-matching phenomenon: a producer subproject exposes an
 * `androidRuntimeElements`-shaped consumable configuration with multiple `artifactType` secondary
 * variants and no base artifact, and a consumer whose only runtime configuration is
 * `androidRuntimeClasspath` (the candidate the desktop path picks ahead of `runtimeClasspath`)
 * depends on it. Serializing `composePreviewDaemonStart` into the configuration cache resolves the
 * task's classpath inputs, so a bare view aborts the build here while the pinned view succeeds.
 *
 * Realizing the daemon-start task also resolves the desktop renderer closure, so — like the other
 * renderer-backed functional tests — this needs `ee.schimke.composeai:renderer-desktop:<version>` in
 * mavenLocal (published by the `functionalTestWithAndroid` / publish-backed CI flow). It skips when
 * that artifact is absent so a bare `:gradle-plugin:functionalTest` run stays green.
 */
class KmpAndroidDaemonClasspathFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val mavenLocal: String =
    System.getProperty("ee.schimke.composeai.functionalTest.mavenLocal", "")

  private val pluginVersion: String =
    System.getProperty("ee.schimke.composeai.functionalTest.pluginVersion", "")

  @Test
  fun `daemon-start resolves a multi-variant androidRuntimeClasspath without ambiguity`() {
    // The daemon-start task realizes the desktop renderer closure; skip unless it's published to
    // mavenLocal (the publish-backed CI flow does this; a bare functionalTest run does not).
    val rendererPublished =
      mavenLocal.isNotBlank() &&
        pluginVersion.isNotBlank() &&
        File(mavenLocal, "ee/schimke/composeai/renderer-desktop/$pluginVersion").isDirectory
    assumeTrue(
      "Skipping: renderer-desktop-$pluginVersion not in mavenLocal " +
        "(run via ./gradlew functionalTestWithAndroid, which publishes first)",
      rendererPublished,
    )

    val projectDir = createKmpAndroidConsumerProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDaemonStart", "--configuration-cache", "--stacktrace")
        .withPluginClasspath()
        .build()

    // `composePreviewDaemonStart` is `onlyIf`-gated off for a pure `androidRuntimeClasspath` module,
    // so the assertion is simply that the build resolved: storing the config cache resolves the
    // task's classpath inputs, and a bare artifact view would have failed with
    // AmbiguousArtifactsFailure on `:lib` before we ever reach BUILD SUCCESSFUL.
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
                mavenLocal()
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
    // `artifactType` secondary variants (mirroring AGP's KMP-Android runtime) with no base artifact.
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
    // Kotlin/JVM + Compose module whose `androidRuntimeClasspath` carries the multi-variant producer
    // exercises exactly the daemon-start classpath resolution that regressed.
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
        """
          .trimIndent()
      )

    return projectDir
  }
}
