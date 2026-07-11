package ee.schimke.composeai.daemon

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolver coverage for [PreviewManifestEntry] — the wear-clip fix lands the `params` nesting that
 * the gradle plugin's `DiscoverPreviewsTask` actually emits, while keeping the harness's flat
 * schema working. Pre-fix the daemon read the production manifest with the flat-schema reader,
 * which made `device` / `widthDp` / `heightDp` / `density` silently null on every render and pinned
 * the daemon to its hardcoded 320×320×2.0 defaults — visibly broken on Wear (no round crop, wrong
 * aspect).
 *
 * The mirror class on the Android side (`:daemon:android`) carries identical logic and is exercised
 * end-to-end by the harness's S3.5 / S4 tests; this unit test sits on the desktop module because
 * desktop tests don't need a Robolectric sandbox to spin up.
 */
class PreviewManifestEntryResolveTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `flat schema - harness shape - reads top-level fields`() {
    val raw =
      """{"id":"red-square","className":"X","functionName":"R","widthPx":64,""" +
        """"heightPx":64,"density":1.0,"showBackground":true,"device":"id:wearos_small_round"}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals(64, resolved.widthPx)
    assertEquals(64, resolved.heightPx)
    assertEquals(1.0f, resolved.density, 0.0001f)
    assertEquals(true, resolved.showBackground)
    assertEquals("id:wearos_small_round", resolved.device)
    assertEquals("red-square", resolved.outputBaseName)
  }

  @Test
  fun `no explicit size - wraps content at the sandbox bound (AS-parity natural size)`() {
    // A preview that declares no widthDp/heightDp/device renders wrap-content: the resolved size is
    // the 400x800dp sandbox bound and the wrap flags are set, so the capture (figma-svg / wireframe
    // /
    // semantics) crops to the composable's intrinsic size instead of a fixed 320 frame that clipped
    // wide content and reflowed text.
    val raw =
      """{"id":"sticker","className":"X","functionName":"R","sourceFile":"P.kt",""" +
        """"params":{"density":2.625,"showBackground":true},""" +
        """"captures":[{"renderOutput":"renders/sticker.png","cost":1.0}]}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals(true, resolved.wrapWidth)
    assertEquals(true, resolved.wrapHeight)
    assertEquals((400 * 2.625f).toInt(), resolved.widthPx) // sandbox bound, not 320
    assertEquals((800 * 2.625f).toInt(), resolved.heightPx)
  }

  @Test
  fun `explicit size or device pins the frame - no wrap`() {
    val sized =
      json.decodeFromString(
        PreviewManifestEntry.serializer(),
        """{"id":"s","className":"X","functionName":"R","widthPx":300,"heightPx":120}""",
      )
    assertEquals(false, sized.resolved().wrapWidth)
    assertEquals(false, sized.resolved().wrapHeight)
    assertEquals(300, sized.resolved().widthPx)

    val device =
      json.decodeFromString(
        PreviewManifestEntry.serializer(),
        """{"id":"d","className":"X","functionName":"R",""" +
          """"params":{"device":"id:wearos_small_round","widthDp":192,"heightDp":192,"density":2.0}}""",
      )
    assertEquals(false, device.resolved().wrapWidth)
    assertEquals(false, device.resolved().wrapHeight)
  }

  @Test
  fun `nested schema - plugin shape - reads params block`() {
    // Mirrors what `DiscoverPreviewsTask` writes for a Wear preview annotated with
    // `@Preview(device = "id:wearos_small_round")` — production manifest the daemon was silently
    // dropping pre-fix. `widthDp` × `density` is the per-render sandbox size; the resolver does
    // the dp→px conversion the plugin's schema requires.
    val raw =
      """{"id":"wear-1","className":"X","functionName":"R","sourceFile":"P.kt",""" +
        """"params":{"device":"id:wearos_small_round","widthDp":192,"heightDp":192,""" +
        """"density":2.625,"showBackground":true,"backgroundColor":4294967295},""" +
        """"captures":[{"renderOutput":"renders/wear-1.png","cost":1.0}]}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals(504, resolved.widthPx) // 192 * 2.625
    assertEquals(504, resolved.heightPx)
    assertEquals(2.625f, resolved.density, 0.0001f)
    assertEquals(true, resolved.showBackground)
    assertEquals("id:wearos_small_round", resolved.device)
    assertEquals(0xFFFFFFFFL, resolved.backgroundColor)
    assertEquals("wear-1", resolved.outputBaseName)
  }

  @Test
  fun `flat fields override nested params when both are set`() {
    // Defensive — if a future tool emits a mixed shape we'd rather honour the explicit flat px
    // than re-derive from dp. Same precedence rule on every field.
    val raw =
      """{"id":"mix","className":"X","functionName":"R","widthPx":100,"heightPx":50,""" +
        """"params":{"widthDp":192,"heightDp":192,"density":2.0}}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals(100, resolved.widthPx)
    assertEquals(50, resolved.heightPx)
  }

  @Test
  fun `bare entry falls back to defaults - never crashes the routing path`() {
    val raw = """{"id":"bare","className":"X","functionName":"R"}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    // A bare (no-size) entry now renders wrap-content at the sandbox bound (400×800 dp × the 2.0
    // default density) rather than a fixed 320² frame, so the capture reflects the composable's
    // natural size. The other defaults are unchanged.
    assertEquals(true, resolved.wrapWidth)
    assertEquals(true, resolved.wrapHeight)
    assertEquals(800, resolved.widthPx) // 400dp × 2.0
    assertEquals(1600, resolved.heightPx) // 800dp × 2.0
    assertEquals(2.0f, resolved.density, 0.0001f)
    assertEquals(true, resolved.showBackground)
    assertNull(resolved.device)
    assertEquals(0L, resolved.backgroundColor)
    assertEquals("bare", resolved.outputBaseName)
  }

  @Test
  fun `unknown plugin-side fields don't break decoding`() {
    // The plugin's PreviewParams carries fields the daemon doesn't yet read (fontScale, locale,
    // uiMode, group, kind, etc.). With ignoreUnknownKeys = true the daemon should accept the full
    // payload and resolve only what it understands.
    val raw =
      """{"id":"full","className":"X","functionName":"R",""" +
        """"params":{"device":"id:wearos_small_round","widthDp":192,"heightDp":192,""" +
        """"fontScale":1.12,"locale":"en-rUS","uiMode":32,"group":"Devices",""" +
        """"showSystemUi":false,"kind":"COMPOSE","previewParameterLimit":2147483647}}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals("id:wearos_small_round", resolved.device)
    assertEquals(384, resolved.widthPx) // 192 * 2.0 default density
  }

  @Test
  fun `nested params wrapperClassName propagates into ResolvedRenderParams`() {
    // Issue #1440 — the gradle plugin emits `params.wrapperClassName` from
    // `@PreviewWrapper(SomeProvider::class)` via class-file annotation scanning (the upstream
    // annotation is `AnnotationRetention.BINARY` and unavailable to runtime reflection). The
    // resolver must surface it so the router can thread it into the `RenderSpec` payload.
    val raw =
      """{"id":"wrapped","className":"X","functionName":"R",""" +
        """"params":{"widthDp":100,"heightDp":100,""" +
        """"wrapperClassName":"com.example.RemotePreviewWrapper"}}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals("com.example.RemotePreviewWrapper", resolved.wrapperClassName)
  }
}
