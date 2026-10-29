package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The plugin must never realize tasks it does not depend on. AGP 9.5 registers
 * `generate<Variant>ComposePreviewRunfiles` for every variant, and its configuration throws when
 * the variant has unit tests disabled ("Unit tests are disabled for this variant (enableUnitTest =
 * false in beforeVariants)"). A predicate-based `tasks.matching { it.name in … }` in `dependsOn`
 * realized every task in the project at graph time, so each compose-preview task failed with "Could
 * not create task ':app:generateReleaseComposePreviewRunfiles'".
 *
 * The fixture registers a task with that name whose configuration throws, so it stands in for AGP
 * 9.5 on the AGP version the plugin builds against. A `--dry-run` builds the full task graph (where
 * the failure happened) without compiling or rendering.
 */
class UnrealizedTasksFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `android preview tasks build their graph without realizing unrelated tasks`() {
    val projectDir = createAndroidTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewDiscover",
          "composePreviewRender",
          "composePreviewRenderAll",
          "composePreviewCompile",
          "--dry-run",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).doesNotContain(REALIZED_MARKER)
    assertThat(result.output).contains(":composePreviewDiscover SKIPPED")
    assertThat(result.output).contains(":composePreviewRender SKIPPED")
    // The name-filtered dependencies still resolve to the real producers.
    assertThat(result.output).contains(":compileDebugKotlin SKIPPED")
    assertThat(result.output).contains(":generateDebugUnitTestConfig SKIPPED")
  }

  /** Same AGP + Compose compiler classloader arrangement as [DaemonBootstrapFunctionalTest]. */
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
        rootProject.name = "unrealized-tasks-android-test"
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
            namespace = "ee.schimke.composeai.functionaltest.unrealized"
            compileSdk = 36
            defaultConfig { minSdk = 24 }
            buildFeatures { compose = true }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }

        kotlin { jvmToolchain(17) }

        dependencies {
            implementation(platform("androidx.compose:compose-bom:2026.04.01"))
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.foundation:foundation")
            debugImplementation("androidx.compose.ui:ui-tooling")
        }

        // Stand-in for AGP 9.5's runfiles task on a variant with unit tests disabled: realizing
        // it throws, exactly as AGP's creation action does.
        tasks.register("generateReleaseComposePreviewRunfiles") {
            throw GradleException("$REALIZED_MARKER")
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties")
      .writeText(
        """
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

  private companion object {
    const val REALIZED_MARKER = "unrelated task was realized"
  }
}
