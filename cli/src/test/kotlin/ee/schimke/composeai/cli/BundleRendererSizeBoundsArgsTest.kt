package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the desktop bundle-render arg wiring for the wrapped-axis size bounds (Max / Min / Within),
 * so a bundle whose `previews.json` carries `PreviewParams.{min,max}{Width,Height}Px` re-renders
 * the same way `compose-preview serve` does. The bounds land at [DesktopRendererMain]
 * positional-arg indices 28–31; the intervening optional slots (14–27) are padded with empty
 * strings. An off-by-one would silently drop the bound, so pin the exact positions and the "no
 * bound ⇒ short arg list" fast path.
 */
class BundleRendererSizeBoundsArgsTest {

  private val renderer =
    BundleRenderer(bundleFile = File("unused.png"), outputDir = File("unused-out"))

  private fun preview(params: PreviewParams): PreviewInfo =
    PreviewInfo(id = "a.Foo", functionName = "Foo", className = "a.Kt", params = params)

  @Test
  fun `no bounds keeps the short positional arg list`() {
    val args = renderer.buildRendererArgs(preview(PreviewParams()), File("/tmp/out.png"))
    // className, functionName, widthPx, heightPx, density, showBackground, backgroundColor,
    // outputFile, wrapperClassName, wrapWidth, wrapHeight, provider, limit, locale = 14.
    assertEquals(14, args.size)
  }

  @Test
  fun `size bounds land at indices 28-31 with 14-27 padded`() {
    val args =
      renderer.buildRendererArgs(
        preview(
          PreviewParams(minWidthPx = 120, minHeightPx = 48, maxWidthPx = 400, maxHeightPx = 800)
        ),
        File("/tmp/out.png"),
      )
    assertEquals(32, args.size)
    // Intervening optional slots (scroll / kind / fontScale / systemUi / anim / siblings) are
    // unset.
    for (i in 14..27) assertTrue(
      args[i].isEmpty(),
      "arg $i should be padded empty, was '${args[i]}'",
    )
    assertEquals("120", args[28]) // minWidthPx
    assertEquals("48", args[29]) // minHeightPx
    assertEquals("400", args[30]) // maxWidthPx
    assertEquals("800", args[31]) // maxHeightPx
  }

  @Test
  fun `a single bound still emits the full padded list with the others blank`() {
    val args =
      renderer.buildRendererArgs(preview(PreviewParams(maxWidthPx = 240)), File("/tmp/out.png"))
    assertEquals(32, args.size)
    assertEquals("", args[28])
    assertEquals("", args[29])
    assertEquals("240", args[30])
    assertEquals("", args[31])
  }
}
