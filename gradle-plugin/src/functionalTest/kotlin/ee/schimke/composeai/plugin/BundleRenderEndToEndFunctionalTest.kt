package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end coverage for `compose-preview bundle render` driven through the actual CLI binary.
 * Validates the full "pack a bundle from a Gradle project → carry it elsewhere → re-render outside
 * of any Gradle project" flow this PR adds.
 *
 * Gating — three layers, evaluated in order so the failure mode is informative:
 * 1. **`bundle.render.e2e=true` Gradle property must be set.** The test spawns a subprocess JVM per
 *    preview (cold-start Compose Desktop ~1-2s), too slow for default `./gradlew check`. The root
 *    build's `functionalTestWithBundleRender` task flips this on.
 * 2. **CLI binary must exist.** `:cli:installDist` is a hard prerequisite of the wrapping task. A
 *    missing binary past the property gate is a setup error, not a dev-environment skip.
 * 3. **Renderer jars must be present in `<install>/lib-renderer/`.** Sanity check on the
 *    distribution layout — if the renderer config didn't get copied, every render will fail with
 *    `ClassNotFoundException: DesktopRendererMainKt`. Catch it upfront with a clearer message.
 *
 * No `withPluginClasspath()` for the pack step — the synthetic Compose Desktop project resolves our
 * plugin from `mavenLocal()`, the same shape `CliA11yEndToEndFunctionalTest` uses.
 */
class BundleRenderEndToEndFunctionalTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val bundleRenderE2E: Boolean =
    System.getProperty("composeai.functionalTest.cliBundleRender", "false") == "true"

  private val cliBinary: String = System.getProperty("composeai.functionalTest.cliBinary", "")

  private val pluginVersion: String =
    System.getProperty("ee.schimke.composeai.functionalTest.pluginVersion", "")

  @Test
  fun `compose-preview bundle render produces real PNGs from a packed bundle`() {
    assumeTrue("Skipping: -Pbundle.render.e2e=true not set", bundleRenderE2E)

    assertWithMessage("CLI binary path not surfaced via system property")
      .that(cliBinary)
      .isNotEmpty()
    val cli = File(cliBinary)
    assertWithMessage(
        "CLI binary $cliBinary missing — did `:cli:installDist` run? Use " +
          "`./gradlew functionalTestWithBundleRender`"
      )
      .that(cli.isFile)
      .isTrue()
    val libRenderer = cli.parentFile.parentFile.resolve("lib-renderer")
    assertWithMessage(
        "lib-renderer dir missing in CLI distribution at ${libRenderer.path} — the cli build " +
          "didn't include `composePreviewRenderer` configuration outputs."
      )
      .that(libRenderer.isDirectory)
      .isTrue()
    assertWithMessage("lib-renderer is empty — renderer-desktop and its Compose deps not copied")
      .that(libRenderer.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().asList())
      .isNotEmpty()

    val projectDir = createDesktopProject()
    val previewId = "test.PreviewsKt.SimpleBoxPreview"

    // Pack the bundle via the real Gradle task — same code path users hit.
    val pack =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "renderPreviews",
          "composePreviewBundle",
          "-PbundlePreviewIds=$previewId",
          "--stacktrace",
        )
        .forwardOutput()
        .build()
    assertThat(pack.output).doesNotContain("BUILD FAILED")

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    assertWithMessage("composePreviewBundle output missing").that(bundle.isFile).isTrue()

    val renderOut = File(projectDir, "render-out").apply { mkdirs() }

    val builder =
      ProcessBuilder(
          cli.absolutePath,
          "bundle",
          "render",
          bundle.absolutePath,
          "-o",
          renderOut.absolutePath,
          "--verbose",
        )
        .directory(projectDir)
        .redirectErrorStream(true)
    val proc = builder.start()
    val output = proc.inputStream.bufferedReader().use { it.readText() }
    val exitCode = proc.waitFor()
    assertWithMessage("compose-preview bundle render output:\n$output").that(exitCode).isEqualTo(0)

    val rendered = renderOut.listFiles { f -> f.extension == "png" }.orEmpty()
    assertWithMessage("no PNGs in $renderOut\noutput:\n$output").that(rendered).isNotEmpty()

    // Verify the produced PNG is a real Compose render — non-zero size and parseable by ImageIO.
    val first = rendered.single()
    assertThat(first.length()).isGreaterThan(0L)
    val img = ImageIO.read(first)
    assertWithMessage("ImageIO failed to parse ${first.path}").that(img).isNotNull()
    // Default wrap-content sandbox is 400×800 dp @ 2.625× = 1050×2100 px. SimpleBoxPreview
    // doesn't override dimensions, so the renderer's wrap flags crop to the box's intrinsic size
    // — but the canvas dimensions are at most the sandbox bounds.
    assertThat(img.width).isAtMost(1050)
    assertThat(img.height).isAtMost(2100)
  }

  private fun createDesktopProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenLocal()
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
        rootProject.name = "bundle-render-e2e"
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
            id("ee.schimke.composeai.preview") version "$pluginVersion"
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
    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun SimpleBoxPreview() {
            Box(modifier = Modifier.size(120.dp).background(Color(0xFF3F51B5)))
        }
        """
          .trimIndent()
      )

    return projectDir
  }
}
