package ee.schimke.composeai.daemon

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontResolverRecorderTest {

  @Test
  fun `recording resolver preserves reflexive equals`() {
    val delegate = proxyResolver()
    val resolver = recordingFontFamilyResolver(delegate, FontResolverRecorder())

    assertTrue(resolver.equals(resolver))
    assertFalse(resolver.equals(delegate))
    assertEquals(System.identityHashCode(resolver), resolver.hashCode())
  }

  @Test
  fun `publishes the weight-matched face, not the family's first declared one`() {
    // A branded family appends non-Latin fallbacks after the brand face, so which *family* gets
    // drawn depends on the requested weight: `Orbitron` at 500, `Noto Sans JP` at 400. Publishing
    // the first declared face instead would tell `compose/figma-svg` that Orbitron was used for
    // normal-weight text it never names, marking a reproducible export degraded and boxing
    // unrelated family-less text in the same SVG.
    val branded =
      FontFamily(
        listOf(
          googleFont("Orbitron", FontWeight.Medium),
          googleFont("Noto Sans JP", FontWeight.Normal),
        )
      )
    val recorder = FontResolverRecorder()

    FigmaSvgRenderedFonts.begin()
    recorder.record(branded, FontWeight.Normal, FontStyle.Normal, null)
    assertEquals(setOf("Noto Sans JP"), FigmaSvgRenderedFonts.snapshot())

    FigmaSvgRenderedFonts.begin()
    recorder.record(branded, FontWeight.Medium, FontStyle.Normal, null)
    assertEquals(setOf("Orbitron"), FigmaSvgRenderedFonts.snapshot())
    FigmaSvgRenderedFonts.begin()
  }

  @Test
  fun `publishes nothing for the platform default`() {
    // The default face is the one case that must stay silent: text that legitimately draws in it
    // exports as the default, and warning about that would fire on every stock preview.
    FigmaSvgRenderedFonts.begin()
    FontResolverRecorder().record(null, FontWeight.Normal, FontStyle.Normal, null)
    assertTrue(FigmaSvgRenderedFonts.snapshot().isEmpty())
  }

  private fun googleFont(name: String, weight: FontWeight) =
    Font(
      googleFont = GoogleFont(name),
      fontProvider =
        GoogleFont.Provider(
          providerAuthority = "com.google.android.gms.fonts",
          providerPackage = "com.google.android.gms",
          certificates = 0,
        ),
      weight = weight,
      style = FontStyle.Normal,
    )

  private fun proxyResolver(): FontFamily.Resolver =
    Proxy.newProxyInstance(
      FontFamily.Resolver::class.java.classLoader,
      arrayOf(FontFamily.Resolver::class.java),
    ) { proxy, method, args ->
      when (method.name) {
        "toString" -> "DelegateFontFamilyResolver"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> error("Unexpected resolver method in equals test: ${method.name}")
      }
    } as FontFamily.Resolver
}
