package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
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

    // Pack the bundle by driving the CLI against a project that does NOT pre-apply the preview
    // plugin — the bundled `--init-script` is what makes the gradle tasks materialise. This is
    // the same shape end users hit when they invoke `compose-preview bundle pack` against an
    // unmodified Compose Desktop project. `:app` not `:` because the CLI's module discovery skips
    // the root project (gradlePath="") — real consumer projects almost always have a subproject.
    val bundle = File(projectDir, "app/build/compose-previews/bundle.png")
    val packBuilder =
      ProcessBuilder(
          cli.absolutePath,
          "bundle",
          "pack",
          "--module",
          ":app",
          "--id",
          previewId,
          "-o",
          bundle.absolutePath,
          "--verbose",
        )
        .directory(projectDir)
        .redirectErrorStream(true)
    // Auto-inject pulls the plugin classpath off `~/.m2` (where
    // `functionalTestWithBundleRender` publishes it). Real users wouldn't flip this on.
    packBuilder.environment()["COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL"] = "1"
    val packProc = packBuilder.start()
    val packOutput = packProc.inputStream.bufferedReader().use { it.readText() }
    val packExit = packProc.waitFor()
    assertWithMessage("compose-preview bundle pack output:\n$packOutput")
      .that(packExit)
      .isEqualTo(0)
    assertWithMessage("expected the plugin-not-applied warning in stderr:\n$packOutput")
      .that(packOutput)
      .contains("plugin not applied")
    assertWithMessage("composePreviewBundle output missing\n$packOutput")
      .that(bundle.isFile)
      .isTrue()

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

  private fun createDesktopProject(): File = BundleE2EFixture.createDesktopProject(tempDir.root)
}
