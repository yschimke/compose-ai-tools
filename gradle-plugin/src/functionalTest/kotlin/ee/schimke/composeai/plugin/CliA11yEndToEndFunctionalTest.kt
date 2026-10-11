package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end `compose-preview a11y` through the real CLI against a synthetic Android library,
 * asserting `accessibility.json` carries the `BadButtonPreview` canary finding
 * ([CliA11yInputsFunctionalTest] only pins the Gradle inputs).
 *
 * Gates, in order:
 * 1. `cli.a11y.e2e=true` — too slow for `check`; CI enables it via `functionalTestWithAndroid`.
 * 2. Android SDK reachable — `assumeTrue` skip otherwise.
 * 3. Renderer AAR, plugin marker and CLI binary present — hard failures, so a publish race can't go
 *    green.
 *
 * No `withPluginClasspath()`: AGP and our plugin resolve through the project's own `plugins { }`
 * via mavenLocal, sharing one classloader; loading them twice breaks `AndroidComponentsExtension`
 * identity.
 */
class CliA11yEndToEndFunctionalTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private val cliA11yE2E: Boolean =
    System.getProperty("composeai.functionalTest.cliA11yE2E", "false") == "true"

  private val cliBinary: String = System.getProperty("composeai.functionalTest.cliBinary", "")

  private val mavenLocal: String =
    System.getProperty("ee.schimke.composeai.functionalTest.mavenLocal", "")

  private val pluginVersion: String =
    System.getProperty("ee.schimke.composeai.functionalTest.pluginVersion", "")

  private val androidSdkDir: String =
    System.getProperty("ee.schimke.composeai.functionalTest.androidSdkDir", "")

  @Test
  fun `compose-preview a11y surfaces BadButtonPreview finding via daemon-driven flow`() {
    assumeTrue("Skipping: -Pcli.a11y.e2e=true not set", cliA11yE2E)
    assumeTrue(
      "Skipping: no Android SDK reachable via ANDROID_HOME / local.properties",
      androidSdkDir.isNotBlank() && File(androidSdkDir).isDirectory,
    )

    // Past the opt-in and SDK gates, missing pieces are setup errors, not skips.
    assertWithMessage("CLI binary path not surfaced via system property")
      .that(cliBinary)
      .isNotEmpty()
    val cli = File(cliBinary)
    assertWithMessage(
        "CLI binary $cliBinary missing — did `:cli:installDist` run? Use " +
          "`./gradlew functionalTestWithAndroid`"
      )
      .that(cli.isFile)
      .isTrue()
    val rendererAar =
      File(mavenLocal, "ee/schimke/composeai/renderer-android/$pluginVersion")
        .listFiles { f -> f.extension == "aar" }
        ?.firstOrNull()
    assertWithMessage(
        "renderer-android-$pluginVersion.aar in mavenLocal " +
          "(did `:renderer-android:publishToMavenLocal` run?)"
      )
      .that(rendererAar)
      .isNotNull()

    val projectDir = createAndroidTestProject()

    // The project applies only `com.android.library`; the plugin comes from the CLI's auto-inject
    // init script, the entry point under test. `--module :app` because module discovery skips the
    // root.
    val builder =
      ProcessBuilder(cli.absolutePath, "a11y", "--module", ":app", "--verbose")
        .directory(projectDir)
        .redirectErrorStream(true)
    builder.environment()["ANDROID_HOME"] = androidSdkDir
    // Lets auto-inject find the locally published plugin; real users get it from Maven Central.
    builder.environment()["COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL"] = "1"
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    assertWithMessage("compose-preview a11y output:\n$output").that(exitCode).isEqualTo(0)

    // accessibility.json should exist and contain the BadButtonPreview finding.
    val accessibilityJson = File(projectDir, "app/build/compose-previews/accessibility.json")
    assertWithMessage("accessibility.json should exist after `compose-preview a11y`")
      .that(accessibilityJson.exists())
      .isTrue()

    val report = json.parseToJsonElement(accessibilityJson.readText()).jsonObject
    val entries = report["entries"]?.jsonArray ?: error("accessibility.json has no entries field")
    val findings =
      entries
        .map { it.jsonObject }
        .flatMap { it["findings"]?.jsonArray?.toList() ?: emptyList() }
        .map { it.jsonObject }

    // Assert only the canary check, so ATF updates adding findings don't break the test.
    val types = findings.map { it["type"]?.jsonPrimitive?.content }.toSet()
    assertWithMessage("a11y findings types: $types\nfull output:\n$output")
      .that(types)
      .contains("SpeakableTextPresentCheck")
  }

  /**
   * Root plus `:app`, since CLI module discovery skips the root project (path `:`); also the
   * realistic shape. `local.properties` carries the SDK path.
   */
  private fun createAndroidTestProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                google()
                mavenLocal()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenLocal()
                mavenCentral()
            }
        }
        rootProject.name = "cli-a11y-e2e"
        include(":app")
        """
          .trimIndent()
      )

    File(projectDir, "local.properties").writeText("sdk.dir=$androidSdkDir\n")

    val appDir = File(projectDir, "app").apply { mkdirs() }
    File(appDir, "build.gradle.kts")
      .writeText(
        """
        // No `id("ee.schimke.composeai.preview")` on purpose — the CLI's bundled
        // `--init-script` auto-injects it onto every project that applies `com.android.library`
        // (or `com.android.application` / `org.jetbrains.compose`). That code path is what we're
        // covering here; pre-applying the plugin would mask any regression in auto-inject.
        plugins {
            id("com.android.library") version "9.2.0"
            id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
        }

        android {
            namespace = "ee.schimke.composeai.functionaltest.clia11y"
            compileSdk = 36
            defaultConfig { minSdk = 24 }
            buildFeatures { compose = true }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }

        kotlin {
            jvmToolchain(17)
        }

        // No `composePreview { daemon { enabled = true } }` block either — `DaemonExtension.enabled`
        // defaults to `true`, and the block isn't reachable without the plugin literally applied
        // in this file. The auto-inject path wires the plugin via `pluginManager.withPlugin(...)`
        // *after* the build script has finished parsing, so the daemon defaults are what kick in.

        dependencies {
            implementation(platform("androidx.compose:compose-bom:2026.04.01"))
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.material3:material3")
            implementation("androidx.compose.foundation:foundation")
            debugImplementation("androidx.compose.ui:ui-tooling")
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

    // `compose-preview` walks up looking for `gradlew` to identify the project root. A stub is
    // enough — the CLI drives Gradle via the Tooling API, not by `exec`-ing this script.
    File(projectDir, "gradlew").writeText("#!/usr/bin/env bash\nexit 1\n")

    val srcDir = File(appDir, "src/main/kotlin/ee/schimke/composeai/functionaltest/clia11y")
    srcDir.mkdirs()
    File(srcDir, "BadButtonPreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.functionaltest.clia11y

        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Button
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        /**
         * Deliberately-broken preview — a 20dp Material Button with no content description. ATF
         * reliably flags `SpeakableTextPresentCheck` (no accessible label); often also flags
         * `TouchTargetSize` (below 48dp). Mirrors `BadButtonPreview` in `:samples:android`.
         */
        @Preview(name = "Bad Button", showBackground = true, backgroundColor = 0xFFFFFFFF)
        @Composable
        fun BadButtonPreview() {
            Button(onClick = {}, modifier = Modifier.size(20.dp)) {}
        }
        """
          .trimIndent()
      )

    return projectDir
  }
}
