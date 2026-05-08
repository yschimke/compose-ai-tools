package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end functional coverage for the **standard** accessibility pipeline on the
 * `com.android.library` path: synthetic project + AGP + Robolectric + the plugin's auto-injected
 * `ee.schimke.composeai:renderer-android:<plugin-version>` AAR (both the AAR closure and the plugin
 * itself are resolved from `~/.m2/repository` after the parent build's `functionalTestWithAndroid`
 * task runs `:renderer-android:publishToMavenLocal` and `:gradle-plugin:publishToMavenLocal` as
 * pre-steps).
 *
 * Issue #733. Companion to [AccessibilityFunctionalTest], which pins the CMP-side manifest wiring
 * without booting Robolectric.
 *
 * Asserts the `:renderAllPreviews` artefact set the CLI's `A11yCommand` reads:
 * - `build/compose-previews/accessibility.json` — top-level aggregated report.
 * - `build/compose-previews/accessibility-per-preview/<className>.<funcName>_<variant>.json`.
 * - `build/compose-previews/renders/<previewId>.a11y.png` — annotated overlay PNG.
 *
 * The four typed `a11y-atf.json` / `a11y-hierarchy.json` / `a11y-touchTargets.json` /
 * `a11y-overlay.png` files described in earlier draft scoping notes are **daemon-mode-only**:
 * `:daemon:android`'s `RenderEngine` is the only caller of
 * `AccessibilityDataProducer.writeArtifacts`. The standard `RobolectricRenderTest` calls
 * `AccessibilityChecker.writePerPreviewReport` instead. Daemon-mode coverage is a separate
 * follow-up — needs daemon JVM + stdio JSON-RPC + lifecycle scaffolding.
 *
 * Skipped (`Assume.assumeTrue`) only when no Android SDK is reachable via `ANDROID_HOME` /
 * `ANDROID_SDK_ROOT` / host `local.properties`. Past the SDK gate the caller is committed to
 * Android coverage and the AAR / plugin-marker checks become **hard assertions**: skipping there
 * would silently green the test if a sibling-task race in the parent's task graph put this test
 * ahead of the renderer-android publishes (Gradle does not enforce sibling `dependsOn` order, and
 * the cross-build boundary blocks real ordering edges between the parent's publishes and this
 * included-build test). A loud failure surfaces the race; a skip would hide it.
 *
 * Keeps dev environments without an Android SDK green and lets `./gradlew
 * :gradle-plugin:functionalTest` stay fast — the Android-flavour coverage is gated behind
 * `./gradlew functionalTestWithAndroid` from the parent build, which pre-publishes both the
 * renderer AAR closure and the plugin itself.
 *
 * Deliberately does **not** call `GradleRunner.withPluginClasspath()`. The synthetic project
 * resolves both AGP (`id("com.android.library")`) and our plugin
 * (`id("ee.schimke.composeai.preview") version "<v>"`) through its own `plugins { ... }` block via
 * `pluginManagement.repositories.mavenLocal()`, so AGP and the plugin land on a single classloader
 * hierarchy. The earlier `withPluginClasspath()` path loaded both twice on different loaders and
 * made `AndroidComponentsExtension` identity checks fail (same FQN, two `Class` objects).
 */
class AccessibilityAndroidFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private val mavenLocal: String =
    System.getProperty("ee.schimke.composeai.functionalTest.mavenLocal", "")

  private val pluginVersion: String =
    System.getProperty("ee.schimke.composeai.functionalTest.pluginVersion", "")

  private val androidSdkDir: String =
    System.getProperty("ee.schimke.composeai.functionalTest.androidSdkDir", "")

  private fun createTestProject(): File {
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
        rootProject.name = "test-a11y-android"
        """
          .trimIndent()
      )

    // AGP reads `local.properties` from the project root for `sdk.dir`. `ANDROID_HOME` would also
    // work but `GradleRunner` doesn't propagate the parent's environment by default — writing
    // the file is the most portable signal.
    File(projectDir, "local.properties").writeText("sdk.dir=$androidSdkDir\n")

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            // AGP 9.x bundles its own Kotlin compilation; the standalone
            // `org.jetbrains.kotlin.android` plugin is gone (and applying it now hard-errors).
            id("com.android.library") version "9.2.0"
            id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
            // Resolved through the synthetic project's `pluginManagement.repositories.mavenLocal()`
            // — the plugin itself is published there by the parent build's `functionalTestWithAndroid`
            // task. Pinning the version (rather than relying on `withPluginClasspath()`) keeps AGP and
            // our plugin on one classloader so `AndroidComponentsExtension` identity checks succeed.
            id("ee.schimke.composeai.preview") version "$pluginVersion"
        }

        android {
            namespace = "ee.schimke.composeai.functionaltest.a11y"
            compileSdk = 36
            defaultConfig { minSdk = 24 }
            buildFeatures { compose = true }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            // `RobolectricRenderTest` reads the merged manifest + Compose theme through
            // Robolectric's resource loader; without this flag the synthetic project's
            // resources don't end up on the test classpath.
            testOptions { unitTests.isIncludeAndroidResources = true }
        }

        kotlin {
            jvmToolchain(17)
        }

        dependencies {
            implementation(platform("androidx.compose:compose-bom:2026.04.01"))
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.material3:material3")
            implementation("androidx.compose.foundation:foundation")
            debugImplementation("androidx.compose.ui:ui-tooling")
        }

        composePreview {
            previewExtensions { a11y { enableAllChecks() } }
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        # Robolectric workers need the same heap as the host build's samples.
        # `:renderer-android` AAR resolution + classes.jar zipTree expansion is light, but
        # Compose BOM transitives push the dep graph into multi-hundred-artefact territory.
        android.useAndroidX=true
        """
          .trimIndent()
      )

    val srcDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/functionaltest/a11y")
    srcDir.mkdirs()
    File(srcDir, "BadButtonPreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.functionaltest.a11y

        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Button
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        /**
         * Deliberately-broken preview: a Material Button with no content description and a
         * 20x20dp size. `AccessibilityChecker.analyze` should flag it for `TouchTargetSize`
         * (below 48dp) and `SpeakableText` (no accessible label). Mirrors the
         * `BadButtonPreview` fixture in `:samples:android` — same shape, same expected
         * findings, kept self-contained here so the functional test is reproducible.
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

  private fun runner(projectDir: File): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      // No `withPluginClasspath()` — see class-level kdoc. The synthetic project resolves the
      // plugin from mavenLocal so AGP and our plugin share one classloader.
      // No `withArguments()` here — call sites add task names + flags. The shared bits go
      // through `commonArgs`.
      .forwardOutput()

  /**
   * Args common to every test invocation here. Just `--stacktrace` so the (frequent, AGP-related)
   * config failures are debuggable from CI logs without a re-run. Gradle 9 removed `--no-daemon`
   * (TestKit uses an embedded daemon anyway) and the configuration cache is fine here — the
   * synthetic project rewrites its build script per-test, which invalidates the cache cleanly.
   */
  private fun commonArgs(vararg tasks: String): Array<String> = arrayOf(*tasks, "--stacktrace")

  @Test
  fun `renderAllPreviews produces the standard a11y artefact set`() {
    assumeTrue(
      "Skipping: no mavenLocal path passed via system property",
      mavenLocal.isNotBlank() && File(mavenLocal).isDirectory,
    )
    assumeTrue(
      "Skipping: plugin version not surfaced via system property",
      pluginVersion.isNotBlank(),
    )
    assumeTrue(
      "Skipping: no Android SDK reachable via ANDROID_HOME / local.properties",
      androidSdkDir.isNotBlank() && File(androidSdkDir).isDirectory,
    )

    // Past the SDK gate the caller is committed to Android coverage — the only valid invocation
    // path that gets here is `./gradlew functionalTestWithAndroid`, which depends on the
    // renderer-android AAR closure + plugin-marker `publishToMavenLocal` tasks. Treat missing
    // prerequisites as **hard failures** rather than `assumeTrue` skips: an `assumeTrue` here
    // would silently green the test if a sibling-task race in the parent's task graph
    // (publishes vs. this test) put the test ahead of the publishes — Gradle does not enforce
    // ordering between sibling `dependsOn` entries, and the cross-build boundary makes
    // expressing real ordering between the parent's publishes and this included-build test
    // impossible from either side. A loud failure surfaces the race; a skip would hide it.
    val rendererAar =
      File(mavenLocal, "ee/schimke/composeai/renderer-android/$pluginVersion")
        .listFiles { f -> f.extension == "aar" }
        ?.firstOrNull()
    assertWithMessage(
        "renderer-android-$pluginVersion.aar in mavenLocal " +
          "(did `:renderer-android:publishToMavenLocal` run? Use `./gradlew functionalTestWithAndroid`)"
      )
      .that(rendererAar)
      .isNotNull()

    // The synthetic project resolves `id("ee.schimke.composeai.preview") version "<v>"` from
    // mavenLocal, so the plugin marker artifact must already be there. Same hard-failure
    // semantics as the AAR check above — though the within-included-build `mustRunAfter`
    // ordering on `:gradle-plugin:functionalTest` makes this race-free in practice when
    // invoked via `functionalTestWithAndroid`, the assertion is the right shape if anyone
    // rewires the parent task later.
    val pluginMarker =
      File(
        mavenLocal,
        "ee/schimke/composeai/preview/ee.schimke.composeai.preview.gradle.plugin/$pluginVersion",
      )
    assertWithMessage(
        "ee.schimke.composeai.preview-$pluginVersion plugin marker in mavenLocal " +
          "(did `:gradle-plugin:publishToMavenLocal` run? Use `./gradlew functionalTestWithAndroid`)"
      )
      .that(pluginMarker.isDirectory)
      .isTrue()

    val projectDir = createTestProject()

    val result = runner(projectDir).withArguments(*commonArgs("renderAllPreviews")).build()

    assertThat(result.task(":renderPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // 1. Manifest pointer (sanity vs. AccessibilityFunctionalTest's CMP-side coverage).
    //    Reuses the plugin's `PreviewManifest` shape since it's on the functional-test classpath.
    val manifestFile = File(projectDir, "build/compose-previews/previews.json")
    assertThat(manifestFile.exists()).isTrue()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
    assertThat(manifest.accessibilityReport).isEqualTo("accessibility.json")

    // 2. Aggregated `accessibility.json`. Walked through `JsonObject` rather than a typed DTO:
    //    the producer-side `AccessibilityReport` type lives in `:data-a11y-core` (not on the
    //    functional-test classpath), and re-declaring a copy here would collide with the
    //    plugin's `PreviewManifest`-shaped neighbour at the package level. Walking the tree
    //    keeps the assertion close to the on-disk shape — the contract the CLI / VS Code
    //    extension / MCP agents all key off.
    val reportFile = File(projectDir, "build/compose-previews/accessibility.json")
    assertThat(reportFile.exists()).isTrue()
    val report = json.parseToJsonElement(reportFile.readText()).jsonObject
    val entries = report["entries"]?.jsonArray ?: error("accessibility.json missing entries")
    assertThat(entries).isNotEmpty()
    val badButton =
      entries
        .map { it.jsonObject }
        .firstOrNull { it["previewId"]?.jsonPrimitive?.content?.contains("BadButton") == true }
    assertThat(badButton).isNotNull()
    val findings = badButton!!["findings"]?.jsonArray ?: error("BadButton entry missing findings")
    assertThat(findings).isNotEmpty()

    // 3. Per-preview JSON sidecars exist (used by VS Code's preview drawer for
    //    deep-link-by-preview-id navigation).
    val perPreviewDir = File(projectDir, "build/compose-previews/accessibility-per-preview")
    assertThat(perPreviewDir.exists()).isTrue()
    assertThat(perPreviewDir.listFiles { f -> f.extension == "json" }?.isNotEmpty() ?: false)
      .isTrue()

    // 4. Annotated overlay PNG. The annotated path is recorded relative to `accessibility.json`'s
    //    parent directory; resolve and assert it exists with non-zero size.
    val annotated = badButton["annotatedPath"]?.jsonPrimitive?.content
    assertThat(annotated).isNotNull()
    val annotatedFile = reportFile.parentFile.resolve(annotated!!).canonicalFile
    assertThat(annotatedFile.exists()).isTrue()
    assertThat(annotatedFile.length()).isGreaterThan(0L)
  }
}
