package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The naming rules used to live inside the player and be re-derived by each host, which is how a
 * catalog's body text once ended up in a fallback face on one lane and the real one on another.
 * They are stated once now, so they are worth pinning once — and on a target that is not Wasm,
 * which is where they could previously only be exercised.
 */
class RcTypefaceLoaderTest {

  private class RecordingLoader(override val families: Set<String>) : RcTypefaceLoader {
    val asked = mutableListOf<String>()
    private val answers = families.associateWith { FontFamily.Cursive }

    override fun typeface(family: String, settings: FontVariation.Settings?): FontFamily? {
      asked += family
      return answers[family]
    }
  }

  @Test
  fun aDocumentNamingNoFamilyAsksForTheLiteralDefaultKey() {
    val loader = RecordingLoader(setOf("default"))
    assertSame(FontFamily.Cursive, resolve(null, loader))
    assertEquals(listOf("default"), loader.asked)
  }

  @Test
  fun theGooglePrefixIsASourceMarkerAndIsStripped() {
    val loader = RecordingLoader(setOf("roboto flex"))
    assertSame(FontFamily.Cursive, resolve("google:Roboto Flex", loader))
    assertEquals(listOf("roboto flex"), loader.asked)
  }

  @Test
  fun genericFamiliesTryTheHostThenFallBackToComposesBuiltIns() {
    val empty = RecordingLoader(emptySet())
    assertSame(FontFamily.SansSerif, resolve("sans-serif", empty))
    assertSame(FontFamily.Serif, resolve("serif", empty))
    assertSame(FontFamily.Monospace, resolve("monospace", empty))
    assertEquals(listOf("sans-serif", "serif", "monospace"), empty.asked)

    // A host that does supply a generic wins — overriding one is allowed, supplying one is not
    // required.
    val overriding = RecordingLoader(setOf("serif"))
    assertSame(FontFamily.Cursive, resolve("serif", overriding))
  }

  @Test
  fun aDocumentsOwnEmbeddedFaceWinsOverTheHost() {
    val loader = RecordingLoader(setOf("acme"))
    val embedded = FontFamily.Monospace
    assertSame(
      embedded,
      rcResolveTypeface(
        "Acme",
        fontFamilyId = 12,
        embedded = mapOf(12 to embedded),
        loader = loader,
      ),
    )
    assertTrue(loader.asked.isEmpty(), "the host should not be consulted at all")
  }

  @Test
  fun anUnresolvableNameEndsAtComposesDefaultRatherThanFailing() {
    assertSame(FontFamily.Default, resolve("acme", RecordingLoader(emptySet())))
  }

  @Test
  fun theBundledLoaderLowercasesItsKeysSoAManifestReadVerbatimStillResolves() {
    val faces = RcFontFaces(RcFontFace(identity = "acme", data = ByteArray(0)))
    val loader = RcBundledTypefaceLoader(mapOf("Acme Grotesk" to faces))
    assertEquals(setOf("acme grotesk"), loader.families)
    assertNotNull(loader.typeface("acme grotesk"))
    assertNull(loader.typeface("acme"))
  }

  @Test
  fun theDefaultLoaderResolvesNothingForNow() {
    // #4061 decides what a real platform default is. Until then this is `Empty`, and a consumer can
    // rely on documents rendering in Compose's built-in faces rather than something host-specific.
    assertEquals(emptySet(), RcTypefaceLoader.Default.families)
    assertNull(RcTypefaceLoader.Default.typeface("anything"))
    assertEquals(emptySet(), RcTypefaceLoader.Empty.families)
  }

  private fun resolve(recordedName: String?, loader: RcTypefaceLoader): FontFamily =
    rcResolveTypeface(recordedName, fontFamilyId = -1, embedded = emptyMap(), loader = loader)
}
