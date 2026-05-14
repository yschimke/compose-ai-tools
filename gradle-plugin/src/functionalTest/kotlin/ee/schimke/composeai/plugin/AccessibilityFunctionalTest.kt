package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Functional coverage for the per-extension-reports manifest pointer on the CMP / desktop path.
 * A11y is opt-in (off by default), so a stock `discoverPreviews` invocation is expected to write an
 * empty `dataExtensionReports` map. Opting in via the Gradle property surfaces an `"a11y" ->
 * "accessibility.json"` entry the CLI / VS Code follow.
 *
 * The renderer-side half — `AccessibilityChecker.analyze` running under Robolectric and producing
 * sidecar artefacts — needs an Android + AGP + Robolectric synthetic project; covered by
 * [AccessibilityAndroidFunctionalTest].
 */
class AccessibilityFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun createTestProject(): File {
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
        rootProject.name = "test-a11y-wiring"
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
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Foo.kt")
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
        fun FooPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red))
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  private fun File.readManifest(): PreviewManifest {
    val manifestFile = resolve("build/compose-previews/previews.json")
    assertThat(manifestFile.exists()).isTrue()
    return json.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
  }

  @Test
  fun `dataExtensionReports is empty when a11y is off`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("discoverPreviews")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val manifest = projectDir.readManifest()
    assertThat(manifest.previews).hasSize(1)
    // Default: a11y is opt-in. The CLI / VS Code treat an empty map as "feature off" and don't
    // probe the filesystem for a stale `accessibility.json`.
    assertThat(manifest.dataExtensionReports).isEmpty()
  }

  @Test
  fun `dataExtensionReports gains an a11y entry when opted in via Gradle property`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "discoverPreviews",
          "-PcomposePreview.previewExtensions.a11y.enableAllChecks=true",
        )
        .withPluginClasspath()
        .build()

    assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val manifest = projectDir.readManifest()
    assertThat(manifest.previews).hasSize(1)
    // The plugin pins the relative pointer to a fixed filename; the absolute path is resolved
    // against the manifest's parent dir at consumer-side (CLI / VS Code extension) so it tolerates
    // `./gradlew clean`.
    assertThat(manifest.dataExtensionReports).containsExactly("a11y", "accessibility.json")
  }

  @Test
  fun `dataExtensionReports gains an a11y entry when generic extension form opts in`() {
    // Regression test for the snapshot-timing footgun in `resolveA11yEnabled`: an earlier draft
    // captured `extensions.findByName("a11y")` eagerly at task-registration time and would have
    // pinned the typed defaults if the user opted in via the generic-container form
    // (`previewExtensions { extension("a11y") { enableAllChecks() } }`) — that block runs in the
    // build-script body, which is evaluated *after* plugin apply does the task wiring. The fix
    // eagerly registers the built-in ids in the generic container at extension construction time
    // so `findByName` is non-null from plugin-apply onward, and the user's `extension("a11y") {
    // ... }` block reaches the same instance via `maybeCreate`. If anyone reverts that init block,
    // this test fails. The Gradle-property form is exercised by the previous test and the typed
    // DSL by samples/android.
    val projectDir = createTestProject()
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        composePreview {
            previewExtensions {
                // Generic-container form. Goes through `extensions.maybeCreate("a11y")` —
                // returns the pre-registered instance so this `enableAllChecks()` configures
                // the same Property the resolver reads.
                extension("a11y") { enableAllChecks() }
            }
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
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("discoverPreviews")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val manifest = projectDir.readManifest()
    assertThat(manifest.previews).hasSize(1)
    assertThat(manifest.dataExtensionReports).containsExactly("a11y", "accessibility.json")
  }

  @Test
  fun `a11y opt-in round-trips through the configuration cache`() {
    // The lazy-`Provider { project.extension.findByName(...) }` shape we briefly considered
    // would have captured `project` into the closure and tripped `org.gradle.configuration-cache
    // .problems=fail` (set in the synthetic project's `gradle.properties` above) at cache-write
    // time. The eager-findByName-on-pre-registered-instance shape we landed on instead must
    // round-trip cleanly: write the cache on the first invocation, reuse it on the second. If
    // anyone re-introduces a `Project`-capturing Provider into the resolvers, the first run
    // will fail with "cannot serialize a reference to Project" and this test will fail loudly.
    val projectDir = createTestProject()
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        composePreview {
            previewExtensions { a11y { enableAllChecks() } }
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
        """
          .trimIndent()
      )

    // `problems=fail` is the strict mode set on the host build (`gradle.properties`,
    // documented in AGENTS.md). Mirror it here via a Gradle CLI override so any captured
    // `Project` reference in a Provider closure stops the build instead of degrading to a
    // warning that the test would otherwise silently green.
    val strictCacheArgs =
      arrayOf(
        "discoverPreviews",
        "--configuration-cache",
        "-Dorg.gradle.configuration-cache.problems=fail",
      )

    val first =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*strictCacheArgs)
        .withPluginClasspath()
        .build()
    assertThat(first.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(first.output).contains("Configuration cache entry stored")
    assertThat(projectDir.readManifest().dataExtensionReports)
      .containsExactly("a11y", "accessibility.json")

    val second =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*strictCacheArgs, "--rerun-tasks")
        .withPluginClasspath()
        .build()
    assertThat(second.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(second.output).contains("Configuration cache entry reused")
    assertThat(projectDir.readManifest().dataExtensionReports)
      .containsExactly("a11y", "accessibility.json")
  }
}
