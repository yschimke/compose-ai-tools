package ee.schimke.composeai.daemon

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolver coverage for the Android [PreviewManifestEntry] — mirror of the desktop module's
 * `PreviewManifestEntryResolveTest`. The focus here is the AS-parity wrap-content resolution: a
 * preview that declares no explicit height (the common `@Preview(widthDp = …)` component shape)
 * renders wrap-content within the 400×800 dp sandbox bound and crops to the composable's intrinsic
 * size, instead of the historical fixed 320 frame.
 *
 * Regression: before this fix the Android daemon defaulted a missing width/height to a hardcoded
 * 320 px. A `Column` taller than 320 px handed each child the *remaining* height, so once the frame
 * was spent the overflow children (text fields, buttons, type-scale rows) measured to zero lines —
 * `TcpConnectPanel`'s figma-svg collapsed to 837×265 with the Port field, Connect button and label
 * missing. The wrap flags below are what keep the daemon capture at the preview's natural size.
 */
class PreviewManifestEntryResolveTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `no explicit size wraps both axes at the sandbox bound`() {
    val raw =
      """{"id":"sticker","className":"X","functionName":"R",""" +
        """"params":{"density":2.625,"showBackground":true}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(true, resolved.wrapWidth)
    assertEquals(true, resolved.wrapHeight)
    assertEquals((400 * 2.625f).toInt(), resolved.widthPx) // sandbox bound, not 320
    assertEquals((800 * 2.625f).toInt(), resolved.heightPx)
  }

  @Test
  fun `widthDp only wraps height - the TcpConnectPanel component shape`() {
    // `@Preview(showBackground = true, name = "TcpConnectPanel — idle", widthDp = 340)` — the exact
    // annotation that collapsed. Width is pinned (340dp); height must wrap so the panel measures its
    // natural ~789 px instead of reflowing everything below the 320 px frame to zero.
    val raw =
      """{"id":"tcp","className":"X","functionName":"R",""" +
        """"params":{"widthDp":340,"density":2.625,"showBackground":true}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(false, resolved.wrapWidth)
    assertEquals(true, resolved.wrapHeight)
    assertEquals(893, resolved.widthPx) // pinned width: 340dp * 2.625, half-up rounded
    assertEquals((800 * 2.625f).toInt(), resolved.heightPx) // sandbox height bound
  }

  @Test
  fun `explicit width and height pins the frame - no wrap`() {
    val raw =
      """{"id":"card","className":"X","functionName":"R",""" +
        """"params":{"widthDp":310,"heightDp":170,"density":2.0}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(false, resolved.wrapWidth)
    assertEquals(false, resolved.wrapHeight)
    assertEquals(620, resolved.widthPx)
    assertEquals(340, resolved.heightPx)
  }

  @Test
  fun `device pins the frame - no wrap`() {
    val raw =
      """{"id":"wear","className":"X","functionName":"R",""" +
        """"params":{"device":"id:wearos_small_round","density":2.0}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(false, resolved.wrapWidth)
    assertEquals(false, resolved.wrapHeight)
    assertEquals("id:wearos_small_round", resolved.device)
    // …and it pins it to the DEVICE's geometry (192dp × 2.0), not the fixed 320px fallback. A
    // device preview carries no explicit widthDp/heightDp, so falling through to DEFAULT_FRAME_PX
    // composed every Wear screen at 320² and exported a 352² figma-svg beside a 454² PNG
    // (issues #2615 / #2883).
    assertEquals(384, resolved.widthPx)
    assertEquals(384, resolved.heightPx)
  }

  @Test
  fun `annotation dp override the device frame when both axes are set`() {
    // `@Preview(device = …, widthDp = 100, heightDp = 100)` — the precedence
    // `DeviceDimensions.resolve(device, w, h)` applies, and therefore the one the standalone
    // renderer's PNG uses. The daemon must agree or the SVG frames a different box than the PNG.
    val raw =
      """{"id":"wear","className":"X","functionName":"R",""" +
        """"params":{"device":"id:wearos_large_round","widthDp":100,"heightDp":100,""" +
        """"density":2.0}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(200, resolved.widthPx)
    assertEquals(200, resolved.heightPx)
  }

  @Test
  fun `a single-axis dp hint does not displace the device frame`() {
    // Only `widthDp` set — `resolve` needs both axes to short-circuit, so the catalog still wins.
    val raw =
      """{"id":"wear","className":"X","functionName":"R",""" +
        """"params":{"device":"id:wearos_large_round","widthDp":100,"density":2.0}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(454, resolved.widthPx)
    assertEquals(454, resolved.heightPx)
  }

  @Test
  fun `a fractional device frame truncates like the standalone renderer`() {
    // `id:pixel_5` is 393dp × 2.75 = 1080.75. The renderer's device-frame branch truncates, so the
    // PNG is 1080px wide; rounding half-up here would frame the SVG at 1081 and defeat the match.
    val raw =
      """{"id":"phone","className":"X","functionName":"R",""" +
        """"params":{"device":"id:pixel_5","density":2.75}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(1080, resolved.widthPx)
    assertEquals(2340, resolved.heightPx) // 851dp × 2.75 = 2340.25
  }

  @Test
  fun `spec device grammar pins the frame at its own dpi`() {
    // Jetchat's `spec:width=340dp,height=800dp,dpi=160` — dpi 160 is density 1.0, so the frame is
    // 340×800 px. The daemon used to render it at 320² regardless.
    val raw =
      """{"id":"chat","className":"X","functionName":"R",""" +
        """"params":{"device":"spec:width=340dp,height=800dp,dpi=160"}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(1.0f, resolved.density, 0.0001f)
    assertEquals(340, resolved.widthPx)
    assertEquals(800, resolved.heightPx)
  }

  @Test
  fun `non-Compose kind pins the frame - no wrap, keeps the 320px frame`() {
    // Tile / notification / Glance render helpers consume the concrete widthPx/heightPx; they must
    // not be handed a wrapped sandbox bound. Regression: an early version of the wrap fix used the
    // 400x800 sandbox as the fallback unconditionally, resizing every no-size notification preview
    // (their baselines all changed). A pinned, no-size preview must keep the historical 320px frame.
    val raw =
      """{"id":"notif","className":"X","functionName":"R",""" +
        """"params":{"kind":"NOTIFICATION","density":2.0}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(false, resolved.wrapWidth)
    assertEquals(false, resolved.wrapHeight)
    assertEquals(320, resolved.widthPx) // fixed frame, NOT the 400dp sandbox bound
    assertEquals(320, resolved.heightPx)
  }

  @Test
  fun `flat explicit px pins the frame - harness shape`() {
    val raw =
      """{"id":"red","className":"X","functionName":"R","widthPx":64,"heightPx":64,"density":1.0}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(false, resolved.wrapWidth)
    assertEquals(false, resolved.wrapHeight)
    assertEquals(64, resolved.widthPx)
    assertEquals(64, resolved.heightPx)
  }
}
