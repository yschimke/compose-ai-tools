package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop daemon-start path must resolve a KMP-Android module's `androidRuntimeClasspath`
 * through an `artifactType`-pinned view (see #1852). Such modules publish several `artifactType`
 * secondary variants and no default, so a bare `incoming.artifactView {}` fails with
 * `AmbiguousArtifactsFailure` under AGP 9.3. `ComposePreviewTasks.wireDesktopBtaInputs` must pin
 * `artifactType=jar` via `pinnedConsumerClasspath`, like the other consumer views.
 *
 * Reproduced in plain Gradle (a producer with an `androidRuntimeElements`-shaped configuration),
 * resolving only `composePreviewDaemonStart.btaCompileClasspath`, so it's hermetic and needs no
 * `publishToMavenLocal`.
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
    // Require the `jar` variant itself: `lenient(true)` means a dropped pin would resolve empty and
    // still pass the checks above.
    assertThat(result.output).contains("stub-jar.jar")
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

    // The desktop path prefers `androidRuntimeClasspath` over `runtimeClasspath`; the task resolves
    // only `btaCompileClasspath`, not the renderer closure.
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
            doLast { logger.lifecycle("resolved BTA classpath: ${'$'}{bta.get().files.map { it.name }}") }
        }
        """
          .trimIndent()
      )

    return projectDir
  }
}
