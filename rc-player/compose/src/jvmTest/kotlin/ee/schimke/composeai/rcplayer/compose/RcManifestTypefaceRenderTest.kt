package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.junit.Assume.assumeTrue

/**
 * The manifest loader against the repo's **real** `fonts.json`, on a non-Wasm target.
 *
 * This is the test #4061 exists to make possible. The manifest rules used to live in the Wasm host,
 * so the only thing that could exercise them was a browser; an iOS or desktop host rendering the
 * same catalog silently got Compose's built-in face for every CoreText that names no family — which
 * in the remote-m3 catalog is all of the body text.
 *
 * Both cases are asserted by *ink width*, not by a resolved object: the failure mode is a fallback
 * face, which draws perfectly good text at a different width. A run that resolves the manifest face
 * and a run that falls back both look fine on their own; only the comparison distinguishes them.
 */
class RcManifestTypefaceRenderTest {

  @Test
  fun aDocumentNamingNoFamilyDrawsTheManifestsDefaultFaceRatherThanComposesOwn() = runTest {
    val fonts = File(FONTS_DIR)
    assumeTrue("vendored catalog fonts not found at $FONTS_DIR", fonts.isDirectory)
    val loader = RcManifestTypefaceLoader { url -> File(url).readBytes() }.load(fonts.path)

    assertTrue("default" in loader.families, "the default-role alias was not registered")
    assertTrue("roboto flex" in loader.families, "the family's own name was not registered")

    val withManifest = inkWidth(document(family = null), loader)
    val withoutManifest = inkWidth(document(family = null), RcTypefaceLoader.Empty)

    assertTrue(withManifest > 0, "the manifest run drew nothing")
    assertTrue(
      withManifest != withoutManifest,
      "a document naming no family drew the same ink with and without the host's manifest, so it " +
        "fell through to Compose's built-in face (manifest=$withManifest, fallback=$withoutManifest)",
    )
  }

  @Test
  fun aGooglePrefixedNameResolvesToTheSameFacesAsTheDefaultAlias() = runTest {
    val fonts = File(FONTS_DIR)
    assumeTrue("vendored catalog fonts not found at $FONTS_DIR", fonts.isDirectory)
    val loader = RcManifestTypefaceLoader { url -> File(url).readBytes() }.load(fonts.path)

    // Same face reached by both names the wire uses for it, so a document is free to be explicit.
    assertTrue(
      inkWidth(document("google:Roboto Flex"), loader) == inkWidth(document(null), loader),
      "`google:Roboto Flex` and the default alias resolved to different faces",
    )
  }

  private fun inkWidth(document: RcDocument, typefaces: RcTypefaceLoader): Int {
    val scene =
      ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
        RcComposePlayer(document, typefaces = typefaces)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }
      check(scene.render(0L).readPixels(bitmap))
      return (0 until WIDTH).count { x -> (0 until HEIGHT).any { y -> bitmap.getColor(x, y) != 0 } }
    } finally {
      scene.close()
    }
  }

  /** One text run, naming [family] or naming nothing at all — which asks for the `default` key. */
  private fun document(family: String?): RcDocument =
    RcDocument(
      RcHeader(RcVersion(1, 0, 0), legacyWidth = WIDTH, legacyHeight = HEIGHT, modern = false),
      listOf(
        RcRootLayout(-2),
        RcBoxLayout(-3, -1, 2, 2),
        RcLayoutContent(-4),
        RcTextData(42, "Remote Compose"),
        RcTextData(43, family.orEmpty()),
        RcCoreText(
          textId = 42,
          properties =
            listOfNotNull(
              RcTextStyleProperty.IntValue(3, 0xff101828.toInt()),
              RcTextStyleProperty.FloatValue(5, RcFloatWord.literal(40f)),
              family?.let { RcTextStyleProperty.IntValue(8, 43) },
            ),
        ),
        RcLayoutContent(-5),
      ) + List(5) { RcNoArg(RcOpcodes.CONTAINER_END) },
    )

  private companion object {
    const val WIDTH = 600
    const val HEIGHT = 80

    /** The catalog's own manifest and faces, the ones the browser lane fetches at runtime. */
    const val FONTS_DIR = "../../samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts"
  }
}
