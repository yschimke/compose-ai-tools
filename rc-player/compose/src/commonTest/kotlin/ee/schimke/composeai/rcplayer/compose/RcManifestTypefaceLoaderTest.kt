package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.text.font.FontFamily
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The regression these exist to make impossible: the manifest rules — the `default`-role alias, the
 * `google:` prefix strip, lowercased keys — lived in the Wasm host, so they could only ever be
 * exercised in a browser. An iOS host rendering the same catalog got Compose's built-in face for
 * all body text and nothing failed. **This file runs on every target.**
 */
class RcManifestTypefaceLoaderTest {

  private val manifest =
    """
    {
      "version": 1,
      "families": [
        { "name": "Roboto Flex", "role": "default",
          "fonts": [{ "file": "RobotoFlex.ttf", "weight": 400 }] },
        { "name": "serif", "role": "generic",
          "fonts": [{ "file": "NotoSerif-Regular.ttf", "weight": 400 }] },
        { "name": "Orbitron", "role": "named",
          "fonts": [
            { "file": "orbitron-400.ttf", "weight": 400 },
            { "file": "orbitron-700.ttf", "weight": 700, "style": "italic" }
          ] }
      ]
    }
    """
      .trimIndent()

  private class RecordingFetcher(private val manifest: String) {
    val requested = mutableListOf<String>()

    suspend fun fetch(url: String): ByteArray {
      requested += url
      return if (url.endsWith("fonts.json")) manifest.encodeToByteArray() else ByteArray(4)
    }
  }

  @Test
  fun aDocumentNamingNoFamilyResolvesTheDefaultRoleFamily() = runTest {
    val loader = RcManifestTypefaceLoader(RecordingFetcher(manifest)::fetch).load("./fonts/")

    // Both keys, which is the whole point: `"default"` for a document that names nothing, and the
    // family's own name so `google:Roboto Flex` resolves to the same faces.
    assertTrue("default" in loader.families)
    assertTrue("roboto flex" in loader.families)
    assertNotNull(rcResolveTypefaceOrNull(null, loader))
    assertNotNull(rcResolveTypefaceOrNull("google:Roboto Flex", loader))
  }

  @Test
  fun familyKeysAreLowercasedAndGenericRolesAreRegisteredByName() = runTest {
    val loader = RcManifestTypefaceLoader(RecordingFetcher(manifest)::fetch).load("./fonts/")

    assertEquals(setOf("default", "roboto flex", "serif", "orbitron"), loader.families)
    assertNotNull(loader.typeface("orbitron"))
    assertNull(loader.typeface("Orbitron"), "the player lowercases before asking; so does the map")
  }

  @Test
  fun theManifestAndEveryFaceAreFetchedRelativeToTheBase() = runTest {
    val fetcher = RecordingFetcher(manifest)
    RcManifestTypefaceLoader(fetcher::fetch).load("https://cdn.example/assets/fonts")

    // No trailing slash on the base; the loader adds one rather than concatenating a broken URL.
    assertEquals(
      listOf(
        "https://cdn.example/assets/fonts/fonts.json",
        "https://cdn.example/assets/fonts/RobotoFlex.ttf",
        "https://cdn.example/assets/fonts/NotoSerif-Regular.ttf",
        "https://cdn.example/assets/fonts/orbitron-400.ttf",
        "https://cdn.example/assets/fonts/orbitron-700.ttf",
      ),
      fetcher.requested,
    )
  }

  @Test
  fun aSecondLoadOfTheSameBaseRefetchesNothing() = runTest {
    val fetcher = RecordingFetcher(manifest)
    val loader = RcManifestTypefaceLoader(fetcher::fetch)
    val first = loader.load("./fonts/")
    val requestsAfterFirst = fetcher.requested.size

    val second = loader.load("./fonts/")

    // This is what stops a document swap re-fetching a whole catalog's fonts.
    assertSame(first, second)
    assertEquals(requestsAfterFirst, fetcher.requested.size)
  }

  @Test
  fun aDifferentBaseIsNotAnsweredFromTheCache() = runTest {
    val fetcher = RecordingFetcher(manifest)
    val loader = RcManifestTypefaceLoader(fetcher::fetch)
    loader.load("./fonts/")
    val requestsAfterFirst = fetcher.requested.size

    loader.load("./other-fonts/")

    assertTrue(fetcher.requested.size > requestsAfterFirst)
    assertTrue(fetcher.requested.any { it.startsWith("./other-fonts/") })
  }

  @Test
  fun aMissingOrMalformedManifestResolvesNothingRatherThanThrowing() = runTest {
    // "This host supplies no fonts" is a state `composeSupportReport` is designed to refuse a
    // document over; it is not an error for the loader to raise.
    val failing = RcManifestTypefaceLoader { throw IllegalStateException("HTTP 404") }
    assertSame(RcTypefaceLoader.Empty, failing.load("./fonts/"))

    val malformed = RcManifestTypefaceLoader { "{ not json".encodeToByteArray() }
    assertSame(RcTypefaceLoader.Empty, malformed.load("./fonts/"))
  }

  @Test
  fun aFaceThatWalksOutOfTheManifestsDirectoryIsDropped() {
    val faces =
      parseFontManifest(
        """
        {"families":[{"name":"Escape","role":"named","fonts":[
          {"file":"../../etc/passwd"},
          {"file":"https://elsewhere.example/face.ttf"},
          {"file":"ok.ttf"}
        ]}]}
        """
          .trimIndent()
      )

    assertEquals(listOf("ok.ttf"), faces.map { it.file })
  }

  /**
   * The `/`-only, undecoded check above let two shapes through that a real fetcher still resolves
   * as a parent segment: a Windows filesystem fetcher walks out of `..\secret.ttf`, and the browser
   * normalizes `%2e%2e/` as a parent *after* this check has already passed the name.
   */
  @Test
  fun aFaceEscapingByBackslashOrPercentEncodingIsAlsoDropped() {
    val faces =
      parseFontManifest(
        """
        {"families":[{"name":"Escape","role":"named","fonts":[
          {"file":"..\\secret.ttf"},
          {"file":"sub\\..\\..\\secret.ttf"},
          {"file":"%2e%2e/secret.ttf"},
          {"file":"%2E%2E/secret.ttf"},
          {"file":"ok.ttf"},
          {"file":"sub/ok.ttf"},
          {"file":"not%2Ea%2Eparent.ttf"}
        ]}]}
        """
          .trimIndent()
      )

    // The last two are legitimate: a subdirectory, and a name whose decoded form is not `..` at
    // all. Rejecting every `%` outright would cost a catalog faces it is entitled to ship.
    assertEquals(listOf("ok.ttf", "sub/ok.ttf", "not%2Ea%2Eparent.ttf"), faces.map { it.file })
  }

  /**
   * Compose's font cache keys on the identity, so a filename-only identity made two catalogs that
   * both ship `Roboto-Regular.ttf` share one cached typeface — the second silently rendering the
   * first's bytes even though the loader had refetched the right ones. `RcFontFaces` already
   * appends the variation axes for exactly this reason.
   */
  @Test
  fun facesFromDifferentBasesGetDifferentIdentities() = runTest {
    val shared =
      """{"families":[{"name":"Shared","role":"default","fonts":[""" +
        """{"file":"Roboto-Regular.ttf","weight":400}]}]}"""
    val fetcher: suspend (String) -> ByteArray = { url ->
      if (url.endsWith("fonts.json")) shared.encodeToByteArray() else ByteArray(4)
    }
    val a = RcManifestTypefaceLoader(fetcher).load("./catalog-a/fonts/")
    val b = RcManifestTypefaceLoader(fetcher).load("./catalog-b/fonts/")

    assertEquals(
      listOf("./catalog-a/fonts/Roboto-Regular.ttf"),
      (a as RcBundledTypefaceLoader).facesFor("shared")?.identities,
    )
    assertEquals(
      listOf("./catalog-b/fonts/Roboto-Regular.ttf"),
      (b as RcBundledTypefaceLoader).facesFor("shared")?.identities,
    )
  }

  /**
   * `runCatching` catches `CancellationException` too, so a host cancelling a screen load mid-fetch
   * used to come back as "this manifest has no fonts" — the caller then carrying on with post-load
   * state updates inside an already-cancelled coroutine.
   */
  @Test
  fun cancellingTheManifestFetchPropagatesRatherThanYieldingAnEmptyLoader() = runTest {
    val loader = RcManifestTypefaceLoader { throw CancellationException("host navigated away") }

    assertFailsWith<CancellationException> { loader.load("./fonts/") }
  }

  /** Same for a *face* fetch, whose ordinary failures deliberately drop only one family. */
  @Test
  fun cancellingAFaceFetchPropagatesRatherThanDroppingTheFamily() = runTest {
    val loader = RcManifestTypefaceLoader { url ->
      if (url.endsWith("fonts.json")) manifest.encodeToByteArray()
      else throw CancellationException("host navigated away")
    }

    assertFailsWith<CancellationException> { loader.load("./fonts/") }
  }

  @Test
  fun weightAndStyleAreReadAndClamped() {
    val faces =
      parseFontManifest(
        """
        {"families":[{"name":"Clamp","fonts":[
          {"file":"a.ttf","weight":9000},
          {"file":"b.ttf","weight":0},
          {"file":"c.ttf","style":"italic"}
        ]}]}
        """
          .trimIndent()
      )

    assertEquals(listOf(1000, 1, 400), faces.map { it.weight })
    assertEquals(listOf(false, false, true), faces.map { it.italic })
    // No `role` recorded means the default role, which is how a hand-written manifest reads.
    assertTrue(faces.all { it.role == "default" })
  }

  private fun rcResolveTypefaceOrNull(name: String?, loader: RcTypefaceLoader): FontFamily? =
    rcResolveTypeface(name, fontFamilyId = -1, embedded = emptyMap(), loader = loader).takeIf {
      it != FontFamily.Default
    }
}
