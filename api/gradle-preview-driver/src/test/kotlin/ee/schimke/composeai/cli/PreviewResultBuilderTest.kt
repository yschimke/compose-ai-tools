package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Coverage for the pure-function [PreviewResultBuilder] — the manifest → base-results step shared
 * by the CLI and contrib consumers. Doesn't drive any real Gradle build; writes synthetic
 * `previews.json` files and PNGs to a temp dir and asserts on the [PreviewResult] shape that comes
 * back.
 */
class PreviewResultBuilderTest {

  private val tempDir: File = Files.createTempDirectory("preview-result-builder-test").toFile()
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  private fun moduleDir(name: String): File =
    tempDir.resolve(name).apply {
      mkdirs()
      resolve("build/compose-previews").mkdirs()
    }

  private fun module(gradlePath: String, dir: File = moduleDir(gradlePath)) =
    PreviewModule(gradlePath = gradlePath, projectDir = dir)

  private fun writeManifest(module: PreviewModule, manifest: PreviewManifest) {
    module.projectDir
      .resolve("build/compose-previews/previews.json")
      .writeText(json.encodeToString(PreviewManifest.serializer(), manifest))
  }

  private fun writePng(module: PreviewModule, relative: String) {
    val file = module.projectDir.resolve("build/compose-previews/$relative")
    file.parentFile.mkdirs()
    val img = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    ImageIO.write(img, "png", file)
  }

  @Test
  fun `readManifest returns null when previews-json is missing`() {
    val module = module(":app")
    assertNull(PreviewResultBuilder.readManifest(module))
  }

  @Test
  fun `build maps manifest entries to PreviewResults with PNG paths and sha256`() {
    val module = module(":app")
    writeManifest(
      module,
      PreviewManifest(
        module = ":app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "HomeScreen",
              functionName = "HomeScreen",
              className = "com.app.HomeScreenKt",
              captures = listOf(Capture(renderOutput = "renders/HomeScreen.png")),
            )
          ),
      ),
    )
    writePng(module, "renders/HomeScreen.png")

    val results =
      PreviewResultBuilder.build(listOf(module to PreviewResultBuilder.readManifest(module)!!))

    assertEquals(1, results.size)
    val home = results.single()
    assertEquals("HomeScreen", home.id)
    assertEquals(":app", home.module)
    assertNotNull(home.pngPath)
    assertTrue(File(home.pngPath!!).exists(), "PNG path should resolve: ${home.pngPath}")
    assertNotNull(home.sha256)
    // Driver doesn't track diff state — `changed` is always null at this layer; CLI fills it.
    assertNull(home.changed)
    assertEquals(1, home.captures.size)
    assertEquals(home.pngPath, home.captures.single().pngPath)
    assertEquals(home.sha256, home.captures.single().sha256)
  }

  @Test
  fun `build expands previewParameter fan-out by globbing on-disk files in PARAM index order`() {
    val module = module(":params")
    writeManifest(
      module,
      PreviewManifest(
        module = ":params",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "ParamShow",
              functionName = "ParamShow",
              className = "com.params.ParamShowKt",
              params =
                PreviewParams(previewParameterProviderClassName = "com.params.SampleProvider"),
              captures = listOf(Capture(renderOutput = "ParamShow.png")),
            )
          ),
      ),
    )
    writePng(module, "ParamShow_PARAM_0.png")
    writePng(module, "ParamShow_PARAM_2.png")
    writePng(module, "ParamShow_PARAM_10.png")

    val results =
      PreviewResultBuilder.build(listOf(module to PreviewResultBuilder.readManifest(module)!!))

    val captures = results.single().captures
    assertEquals(3, captures.size, "expected 3 fan-out captures, got ${captures.size}")
    // Numeric PARAM_<idx> entries sort by index, so PARAM_10 lands AFTER PARAM_2 (not before, as
    // lexicographic ordering would produce).
    val names = captures.mapNotNull { it.pngPath }.map { File(it).name }
    assertEquals(
      listOf("ParamShow_PARAM_0.png", "ParamShow_PARAM_2.png", "ParamShow_PARAM_10.png"),
      names,
    )
    assertEquals(
      listOf("parameter 0", "parameter 2", "parameter 10"),
      captures.map { it.parameterLabel },
    )
  }

  @Test
  fun `build skips previews whose PNG file does not exist on disk yet`() {
    val module = module(":missing")
    writeManifest(
      module,
      PreviewManifest(
        module = ":missing",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "NeverRendered",
              functionName = "NeverRendered",
              className = "com.app.NeverRenderedKt",
              captures = listOf(Capture(renderOutput = "renders/NeverRendered.png")),
            )
          ),
      ),
    )
    // intentionally no writePng — PNG file doesn't exist

    val results =
      PreviewResultBuilder.build(listOf(module to PreviewResultBuilder.readManifest(module)!!))

    val capture = results.single().captures.single()
    assertNull(capture.pngPath)
    assertNull(capture.sha256)
  }
}
