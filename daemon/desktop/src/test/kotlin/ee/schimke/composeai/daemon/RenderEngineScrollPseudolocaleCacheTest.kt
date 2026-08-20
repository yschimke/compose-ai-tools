package ee.schimke.composeai.daemon

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A `scroll-long` render must run the pseudolocale string-cache guard (#4384 review).
 *
 * `RenderEngine.render` dispatches the scroll / long-SVG / Lottie modes **before** `setUp` and
 * returns from there, so a guard placed alongside the single-frame setup never ran for them. Each
 * of those modes still composes the user's preview in this JVM through `:renderer-desktop`, so a
 * stitched PNG or GIF requested right after an `en-XA` render could be served the transformed
 * strings that render left in CMP's process-wide `stringItemsCache` — accented text in a capture
 * that asked for no locale at all.
 *
 * The flag the guard keeps is the observable: arm it, drive a real `scroll-long` render through the
 * daemon host, and ask the guard again. A `false` answer means the scroll render already consumed
 * the transition — i.e. it cleared the cache on its way in. With the guard left after the dispatch
 * this comes back `true`, because nothing on the scroll path ever touched it.
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
    // Leave the process-wide guard disarmed however this test ended, so it can't colour a later
    // test in the same JVM.
    RenderEngine.guardPseudolocaleStringCache(null)
  }

  @Test
  fun scrollModeRendersRunThePseudolocaleCacheGuard() {
    // A pseudolocale render happened: the cache now holds transformed string items.
    RenderEngine.enterPreviewLocale("en-XA").close()

    renderScrollLong("scroll-cache-guard")

    assertFalse(
      "a scroll-long render must clear the pseudolocalised string items on its way in — the " +
        "guard has to run ahead of the special-mode dispatch, not after it (#4384 review)",
      RenderEngine.guardPseudolocaleStringCache(null),
    )
  }

  /** Drives one `scroll-long` render through the daemon host and asserts it produced its PNG. */
  private fun renderScrollLong(previewId: String) {
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
