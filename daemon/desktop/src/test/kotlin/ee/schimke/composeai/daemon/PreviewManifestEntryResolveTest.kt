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
  fun `flat schema — harness shape — reads top-level fields`() {
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
  fun `nested schema — plugin shape — reads params block`() {
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
  fun `bare entry falls back to defaults — never crashes the routing path`() {
    val raw = """{"id":"bare","className":"X","functionName":"R"}"""
    val entry = json.decodeFromString(PreviewManifestEntry.serializer(), raw)
    val resolved = entry.resolved()
    assertEquals(320, resolved.widthPx)
    assertEquals(320, resolved.heightPx)
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
}
