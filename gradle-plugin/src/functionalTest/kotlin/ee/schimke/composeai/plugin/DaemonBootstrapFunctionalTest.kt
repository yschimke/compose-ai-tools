package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DaemonBootstrapFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  /**
   * `backgroundSandboxBoot` only works if it reaches the descriptor's `systemProperties` (also read
   * by `.github/ci/daemon-roundtrip.py`); stopping short is silently inert. Needs a real build
   * because resolving `composePreviewDesktopDaemon` needs repositories.
   */
  @Test
  fun `backgroundSandboxBoot opt-out in the daemon block reaches the descriptor`() {
    val projectDir =
      createCmpTestProject(
        extraBuildScript =
          """
          composePreview {
              daemon {
                  backgroundSandboxBoot = false
              }
          }
          """
            .trimIndent()
      )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val descriptor = File(projectDir, "build/compose-previews/daemon-launch.json").readText()
    assertThat(backgroundSandboxBootIn(descriptor)).isEqualTo("false")
  }

  @Test
  fun `backgroundSandboxBoot defaults to true in the descriptor`() {
    // Default-on has to hold at the descriptor, not just the extension: this is what every plugin
    // consumer gets, and the descriptor is the daemon JVM's only view of it.
    val projectDir = createCmpTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val descriptor = File(projectDir, "build/compose-previews/daemon-launch.json").readText()
    assertThat(backgroundSandboxBootIn(descriptor)).isEqualTo("true")
  }

  /** The flag's value from the descriptor, independent of JSON spacing. */
  private fun backgroundSandboxBootIn(descriptorJson: String): String? =
    Regex("\"composeai\\.daemon\\.backgroundSandboxBoot\"\\s*:\\s*\"([^\"]*)\"")
      .find(descriptorJson)
      ?.groupValues
      ?.get(1)

  /**
   * The in-process compiler must be the consumer's own Kotlin compiler. This fixture applies Kotlin
   * 2.2.21 with no version catalog, which used to fall back to the plugin's bundled Kotlin.
   */
  @Test
  fun `the descriptor's BTA compiler matches the applied Kotlin plugin without a version catalog`() {
    val projectDir = createCmpTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val descriptor = File(projectDir, "build/compose-previews/daemon-launch.json").readText()
    val buildToolsImpl = Regex("kotlin-build-tools-impl-([^/\\\\\"]+)\\.jar").findAll(descriptor)
    assertThat(buildToolsImpl.map { it.groupValues[1] }.toSet()).containsExactly("2.2.21")
  }

  /**
   * The descriptor's `sourceWatch` names where the build reads sources, so a tool that notices
   * edits on its own looks there too: here a custom `srcDir` no source-layout convention would
   * find.
   */
  @Test
  fun `the descriptor's sourceWatch lists the compilation's real source dirs and build files`() {
    val projectDir =
      createCmpTestProject(
        extraBuildScript =
          """
          kotlin { sourceSets.named("main") { kotlin.srcDir("extra/kotlin") } }
          """
            .trimIndent()
      )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val descriptor =
      kotlinx.serialization.json.Json.parseToJsonElement(
          File(projectDir, "build/compose-previews/daemon-launch.json").readText()
        )
        .jsonObject
    val watch = descriptor["sourceWatch"]!!.jsonObject
    fun paths(key: String) = watch[key]!!.jsonArray.map { it.jsonPrimitive.content }
    val root = projectDir.canonicalFile
    assertThat(paths("sourceDirs"))
      .containsAtLeast(
        File(root, "src/main/kotlin").path,
        File(root, "extra/kotlin").path,
        File(root, "src/main/java").path,
      )
    assertThat(paths("sourceDirs").none { "test" in it.lowercase() }).isTrue()
    assertThat(paths("resourceDirs")).contains(File(root, "src/main/resources").path)
    assertThat(paths("buildFiles"))
      .containsAtLeast(File(root, "build.gradle.kts").path, File(root, "settings.gradle.kts").path)
  }

  @Test
  fun `composePreviewDaemonStart is cached after source edit`() {
    val projectDir = createCmpTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--build-cache", "--stacktrace")
      .withPluginClasspath()
      .build()

    File(projectDir, "src/main/kotlin/test/Previews.kt")
      .appendText(
        """

        internal const val EditedAfterDaemonBootstrap = "changed"
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDaemonStart", "--build-cache", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDaemonStart")?.outcome)
      .isIn(listOf(TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE))
  }

  @Test
  fun `discover and daemonStart in one invocation pass validation, discover first`() {
    // `previewsManifest` comes from a layout Provider with no build dependency, so requesting both
    // tasks failed Gradle's validation ("uses this output of task ':composePreviewDiscover' without
    // declaring an explicit or implicit dependency").
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "composePreviewDaemonStart", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val executed = result.tasks.map { it.path }
    assertThat(executed).containsAtLeast(":composePreviewDiscover", ":composePreviewDaemonStart")
    assertThat(executed.indexOf(":composePreviewDiscover"))
      .isLessThan(executed.indexOf(":composePreviewDaemonStart"))
  }

  @Test
  fun `daemonStart alone does not drag in discovery`() {
    // Must be `mustRunAfter`, not `dependsOn`: the daemon warms before any discovery (hence
    // `@Optional` manifest), and `dependsOn` would make every VS Code warm pay for discovery.
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDaemonStart", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":composePreviewDiscover")).isNull()
  }

  /**
   * The Android path under the configuration cache (#6207). The CMP fixtures above all run with the
   * cache on, but an Android module wires AGP's `test<Variant>UnitTest` classpath into the
   * descriptor task, which only an AGP fixture exercises. Storing the entry resolves every file
   * collection the task holds, so a classpath the cache can't write fails here, before execution.
   * Later identical runs must then reuse the entry: a configuration-phase read of `previews.json`
   * made every discovery invalidate it.
   */
  @Test
  fun `composePreviewDaemonStart on an Android application is configuration-cache compatible`() {
    val projectDir = createAndroidTestProject()

    val runner =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewDiscover",
          "composePreviewDaemonStart",
          "--configuration-cache",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())

    val result = runner.build()

    assertThat(result.output).contains("Configuration cache entry stored")
    assertThat(result.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // The first run wrote `previews.json`. Nothing the configuration phase reads may depend on it,
    // or every discovery would invalidate the entry it just stored.
    val second = runner.build()
    assertThat(second.output).contains("Reusing configuration cache.")
    assertThat(second.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    // A new preview makes discovery rewrite `previews.json`. The entry still holds, and the
    // descriptor re-emits because the manifest is its task input.
    File(projectDir, "src/main/kotlin/ee/schimke/composeai/functionaltest/Previews.kt")
      .appendText(
        """

        @Preview
        @Composable
        fun BlueBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Blue))
        }
        """
          .trimIndent()
      )
    val third = runner.build()
    assertThat(third.output).contains("Reusing configuration cache.")
    assertThat(third.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(third.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(projectDir, "build/compose-previews/previews.json").readText())
      .contains("BlueBoxPreview")

    val descriptor =
      kotlinx.serialization.json.Json.parseToJsonElement(
          File(projectDir, "build/compose-previews/daemon-launch.json").readText()
        )
        .jsonObject
    assertThat(descriptor["mainClass"]?.jsonPrimitive?.content)
      .isEqualTo("ee.schimke.composeai.daemon.DaemonMain")
    assertThat(descriptor["classpath"]!!.jsonArray).isNotEmpty()
  }

  /**
   * The plugin under test plus AGP and the Compose compiler plugin, in one injected classloader:
   * AGP is `compileOnly` for the plugin, so a versioned `id("com.android.application")` would load
   * it in a classloader the plugin can't see.
   */
  private fun androidPluginClasspath(): List<File> {
    val metadata = java.util.Properties()
    javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use {
      metadata.load(it)
    }
    val plugin = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
    val android =
      System.getProperty("ee.schimke.composeai.functionalTest.androidPluginClasspath", "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
    return (plugin + android).map(::File)
  }

  private fun createAndroidTestProject(): File {
    val androidSdkDir = System.getProperty("ee.schimke.composeai.functionalTest.androidSdkDir", "")
    assumeTrue(
      "Skipping: no Android SDK reachable via ANDROID_HOME / local.properties",
      androidSdkDir.isNotBlank() && File(androidSdkDir).isDirectory,
    )
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                google()
                gradlePluginPortal()
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
        rootProject.name = "daemon-bootstrap-android-test"
        """
          .trimIndent()
      )

    File(projectDir, "local.properties").writeText("sdk.dir=$androidSdkDir\n")

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.plugin.compose")
            id("ee.schimke.composeai.preview")
        }

        android {
            namespace = "ee.schimke.composeai.functionaltest.daemonbootstrap"
            compileSdk = 36
            defaultConfig { minSdk = 24 }
            buildFeatures { compose = true }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }

        kotlin { jvmToolchain(17) }

        dependencies {
            implementation(platform("androidx.compose:compose-bom:2026.04.01"))
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.foundation:foundation")
            debugImplementation("androidx.compose.ui:ui-tooling")
            testImplementation("junit:junit:4.13.2")
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.configuration-cache=true
        org.gradle.configuration-cache.problems=fail
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        android.useAndroidX=true
        """
          .trimIndent()
      )

    File(projectDir, "src/main").mkdirs()
    File(projectDir, "src/main/AndroidManifest.xml").writeText("<manifest />\n")

    val srcDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/functionaltest")
    srcDir.mkdirs()
    File(srcDir, "Previews.kt")
      .writeText(
        """
        package ee.schimke.composeai.functionaltest

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun RedBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red))
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  private fun createCmpTestProject(extraBuildScript: String = ""): File {
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
        rootProject.name = "daemon-bootstrap-test"
        include(":daemon:desktop")
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
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        $extraBuildScript
        """
          .trimIndent()
      )

    val daemonDesktopDir = File(projectDir, "daemon/desktop")
    daemonDesktopDir.mkdirs()
    File(daemonDesktopDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            java
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.configuration-cache=true
        """
          .trimIndent()
      )

    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun RedBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red))
        }
        """
          .trimIndent()
      )

    return projectDir
  }
}
