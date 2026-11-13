package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A consumer that applies the plugin itself (no init script, so the tooling leaves Isolated
 * Projects on) must be able to configure every compose-preview task under
 * `org.gradle.unsafe.isolated-projects=true`. The module is a *subproject*, so `project !=
 * rootProject` and any read of the root project's model from `:app` — for example
 * `rootProject.layout` for the repository-root `ui-builder.policy.json` / `catalog.spec.json`
 * candidates — is a cross-project access that fails the build.
 *
 * A `--dry-run` realizes and configures the task graph without compiling. The render tasks are left
 * out: IP implies the configuration cache, whose store resolves the multi-hundred-MB Robolectric
 * classpath; discover and bundle are the tasks that read the repository root.
 */
class IsolatedProjectsFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `android preview tasks configure under Isolated Projects in a subproject`() {
    val projectDir = createAndroidSubprojectBuild()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          ":app:composePreviewDiscover",
          ":app:composePreviewBundle",
          ":app:composePreviewCompile",
          "--dry-run",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).doesNotContain("cannot access")
    assertThat(result.output).contains(":app:composePreviewDiscover SKIPPED")
    assertThat(result.output).contains(":app:composePreviewBundle SKIPPED")
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

  private fun createAndroidSubprojectBuild(): File {
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
        rootProject.name = "isolated-projects-android-test"
        include(":app")
        """
          .trimIndent(),
      )
    File(projectDir, "local.properties").writeText("sdk.dir=$androidSdkDir\n")
    File(projectDir, "build.gradle.kts").writeText("")
    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        android.useAndroidX=true
        org.gradle.configuration-cache=true
        org.gradle.unsafe.isolated-projects=true
        """
          .trimIndent(),
      )

    val app = File(projectDir, "app").apply { mkdirs() }
    File(app, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.plugin.compose")
            id("ee.schimke.composeai.preview")
        }

        android {
            namespace = "ee.schimke.composeai.functionaltest.isolated"
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
        """
          .trimIndent(),
      )
    File(app, "src/main").mkdirs()
    File(app, "src/main/AndroidManifest.xml").writeText("<manifest />\n")
    return projectDir
  }
}
