package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `uiMode` on a **daemon-served scroll data product**.
 *
 * The daemon's `runScrollScenario` is a second, independent call into `:renderer-desktop`'s
 * `renderScrollPreview` — the standalone renderer's argv path does not reach it. So teaching the
 * renderer to honour the night bit fixes Gradle-rendered captures and leaves every `scroll-long` /
 * `scroll-gif` product the daemon serves rendering light, on a white ground, for a dark preview.
 *
 * Nothing in the suite could see that: every scrolling fixture pins its own `lightColorScheme()`,
 * and the one dark-aware fixture ([DarkAwareSquare]) does not scroll. Hence
 * [DarkAwareLongScrollPreview], and hence this test.
 */
class RenderEngineScrollUiModeTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var previousIndexProp: String? = null

  @After
  fun restoreIndexProperty() {
    if (previousIndexProp == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previousIndexProp!!)
  }

  private val viewportPx = 240

  private fun installPreviewIndex(previewId: String, functionName: String) {
    val json =
      """
      {
        "previews": [
          {
            "id": "$previewId",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "$functionName",
            "sourceFile": "RedFixturePreviews.kt",
            "captures": [{"renderOutput": "renders/$previewId.png"}],
            "dataProducts": [
              {
                "kind": "render/scroll/long",
                "scroll": {"mode":"LONG","axis":"VERTICAL","reduceMotion":true}
              }
            ]
          }
        ]
      }
      """
        .trimIndent()
    val file = tempFolder.newFile("$previewId-previews.json").also { it.writeText(json) }
    previousIndexProp = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, file.absolutePath)
  }

  /**
   * Drives a `scroll-long` render through the daemon under [uiMode] and returns the stitched PNG.
   */
  private fun stitchedUnder(previewId: String, uiMode: String): java.awt.image.BufferedImage {
    val functionName = "DarkAwareLongScrollPreview"
    installPreviewIndex(previewId, functionName)
    val outputDir = tempFolder.newFolder("renders-$previewId")
    val dataDir = tempFolder.newFolder("data-$previewId")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=$functionName;" +
              "widthPx=200;heightPx=$viewportPx;density=1.0;showBackground=true;" +
              "uiMode=$uiMode;" +
              "previewId=$previewId;outputBaseName=$previewId;mode=scroll-long"
        ),
        timeoutMs = 240_000,
      )
    } finally {
      host.shutdown()
    }
    val stitched = File(File(dataDir, "render-scroll-long"), "$previewId.png")
    assertTrue("stitched long PNG must be produced: ${stitched.absolutePath}", stitched.exists())
    return ImageIO.read(stitched) ?: error("stitched PNG was not decodable")
  }

  private fun java.awt.image.BufferedImage.countWhere(
    predicate: (r: Int, g: Int, b: Int) -> Boolean
  ): Int {
    var n = 0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val rgb = getRGB(x, y)
        if (predicate((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) n++
      }
    }
    return n
  }

  /**
   * The claim: a dark preview's stitched scroll product is dark. Before the daemon forwarded
   * `uiMode`, this capture came back entirely white — `isSystemInDarkTheme()` reported light, and
   * `showBackground` resolved to white behind it.
   */
  @Test
  fun `a dark preview's stitched scroll product is rendered dark`() {
    val dark = stitchedUnder("DarkAwareLongDark", uiMode = "dark")
    val nearBlack = dark.countWhere { r, g, b -> r < 40 && g < 40 && b < 40 }
    val nearWhite = dark.countWhere { r, g, b -> r > 215 && g > 215 && b > 215 }
    assertTrue("a dark scroll product must be mostly dark (got $nearBlack px)", nearBlack > 0)
    assertEquals("a dark scroll product must carry no white ground", 0, nearWhite)
  }

  /**
   * The bit-level claim, asserted directly because the rendered tests cannot make it.
   *
   * `systemThemeFromUiMode` reads three states — `0x20` dark, `0x10` light, anything else `Unknown`
   * — and `Unknown` hands `isSystemInDarkTheme()` back to the JVM's own theme probe. So sending `0`
   * for an explicit `uiMode=light` does not mean light, it means "ask the host", and the render
   * would come back dark on a dark-themed machine.
   *
   * The light render test below cannot catch that: on a light-themed host `Unknown` resolves light
   * anyway, so it passes for the wrong reason and would only fail on somebody else's machine. This
   * assertion has no such dependency.
   */
  @Test
  fun `uiMode bits distinguish light from unspecified`() {
    assertEquals(0x20, RenderSpec.uiModeBits(RenderSpec.SpecUiMode.DARK))
    assertEquals(0x10, RenderSpec.uiModeBits(RenderSpec.SpecUiMode.LIGHT))
    assertEquals(0, RenderSpec.uiModeBits(null))
  }

  /** The control: the same fixture under light stays light, so the flip is what moved it. */
  @Test
  fun `a light preview's stitched scroll product stays light`() {
    val light = stitchedUnder("DarkAwareLongLight", uiMode = "light")
    val nearWhite = light.countWhere { r, g, b -> r > 215 && g > 215 && b > 215 }
    val nearBlack = light.countWhere { r, g, b -> r < 40 && g < 40 && b < 40 }
    assertTrue("a light scroll product must be mostly light (got $nearWhite px)", nearWhite > 0)
    assertEquals("a light scroll product must carry no dark ground", 0, nearBlack)
  }
}
