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
    assertEquals((340 * 2.625f).toInt(), resolved.widthPx) // pinned width
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
  }

  @Test
  fun `non-Compose kind pins the frame - no wrap`() {
    // Tile / notification / Glance render helpers consume the concrete widthPx/heightPx; they must
    // not be handed a wrapped sandbox bound.
    val raw =
      """{"id":"tile","className":"X","functionName":"R",""" +
        """"params":{"kind":"TILE","density":2.0}}"""
    val resolved = json.decodeFromString(PreviewManifestEntry.serializer(), raw).resolved()
    assertEquals(false, resolved.wrapWidth)
    assertEquals(false, resolved.wrapHeight)
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
