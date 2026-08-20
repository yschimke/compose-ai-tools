package ee.schimke.composeai.daemon

import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A `scroll-long` render must compose inside a locale scope (#4384 / #4385 review).
 *
 * `RenderEngine.render` dispatches the scroll / long-SVG / Lottie modes **before** `setUp` and
 * returns from there, so they used to compose outside the locale gate entirely — no
 * JVM-default-`Locale` override for a `localeTag` request, no serialisation against a held
 * session's frames, and no bound on the pseudolocalised string items such a render leaves in CMP's
 * process-wide `stringItemsCache`. A stitched PNG or GIF rendered afterwards could be served that
 * transformed text at no locale at all.
 *
 * `RenderEngine.pseudolocaleCacheClears` is the observable: a scope entered with a pseudolocale tag
 * clears on its way out, so driving a real `scroll-long` render at `en-XA` and watching the counter
 * move proves the lane went through `withPreviewLocale`. Dispatched outside the gate, as before,
 * the counter does not move.
 *
 * Uses [DarkAwareLongScrollPreview] — the cheapest scrolling fixture in the suite — since what is
 * being asserted is which code ran, not what it drew.
 */
class RenderEngineScrollPseudolocaleCacheTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var previousIndexProp: String? = null

  @After
  fun restoreState() {
    if (previousIndexProp == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previousIndexProp!!)
  }

  @Test
  fun scrollModeRendersComposeInsideALocaleScope() {
    val before = RenderEngine.pseudolocaleCacheClears

    renderScrollLong("scroll-cache-guard", localeTag = "en-XA")

    assertTrue(
      "a pseudolocale scroll-long render must go through withPreviewLocale, so it holds the gate " +
        "and drops its transformed string items on the way out — dispatched outside the gate the " +
        "counter never moves (#4384 / #4385 review)",
      RenderEngine.pseudolocaleCacheClears > before,
    )
  }

  /** Drives one `scroll-long` render through the daemon host and asserts it produced its PNG. */
  private fun renderScrollLong(previewId: String, localeTag: String? = null) {
    val functionName = "DarkAwareLongScrollPreview"
    installPreviewIndex(previewId, functionName)
    val outputDir = tempFolder.newFolder("renders-$previewId")
    val dataDir = tempFolder.newFolder("data-$previewId")
    val host = DesktopHost(engine = RenderEngine(outputDir = outputDir, dataDir = dataDir))
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=$functionName;" +
              "widthPx=200;heightPx=200;density=1.0;showBackground=true;" +
              (localeTag?.let { "localeTag=$it;" } ?: "") +
              "previewId=$previewId;outputBaseName=$previewId;mode=scroll-long"
        ),
        timeoutMs = 240_000,
      )
    } finally {
      host.shutdown()
    }
    val stitched = File(File(dataDir, "render-scroll-long"), "$previewId.png")
    assertTrue(
      "the scroll render must actually have run: ${stitched.absolutePath}",
      stitched.exists(),
    )
  }

  /** A `previews.json` advertising the `render/scroll/long` product the daemon resolves. */
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
                "scroll": {"mode":"LONG","axis":"VERTICAL","reduceMotion":true,"maxScrollPx":400}
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
}
