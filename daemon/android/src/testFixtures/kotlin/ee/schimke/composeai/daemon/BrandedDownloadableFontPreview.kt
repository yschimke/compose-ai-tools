package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

/**
 * Previews whose text draws in a **branded downloadable face** — `Font(GoogleFont("Orbitron"), …)`,
 * the shape a consumer's production typography uses (see meshcore-mobile's `MeshcoreFonts`).
 *
 * These back [FigmaSvgDownloadableFontFamilyTest], which asserts the `compose/figma-svg` export
 * names the branded family on its `<text>` rather than collapsing to the Roboto default. Two
 * fixtures, because a one-face family is not what actually ships:
 * - [BrandedDownloadableText] — one face, the style applied straight on the `Text`.
 * - [BrandedThemeTypographyText] — the production shape: several weights of the branded face
 *   followed by non-Latin `Noto` fallbacks (declared at weight 400, so a weight the branded face
 *   doesn't carry can match a fallback instead), consumed through `MaterialTheme.typography`.
 *
 * The certificate array is `0` because nothing here talks to the real GMS provider: under
 * Robolectric `ShadowFontsContractCompat` intercepts the request, and the export reads the family
 * name off the `FontFamily` the composition declared, not off a resolved typeface.
 */
private val DownloadableFontProvider =
  GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = 0,
  )

private fun brandedFont(name: String, weight: FontWeight) =
  Font(
    googleFont = GoogleFont(name),
    fontProvider = DownloadableFontProvider,
    weight = weight,
    style = FontStyle.Normal,
  )

private val BrandedFamily: FontFamily = FontFamily(brandedFont("Orbitron", FontWeight.Medium))

/**
 * The non-Latin fallbacks a real branded family appends so a glyph the brand face lacks resolves
 * within the app's own typography. All declared at [FontWeight.Normal] — which is what makes this
 * shape worth covering: the family carries a 400 face that is *not* the branded one.
 */
private val NotoFallback =
  listOf(
    brandedFont("Noto Sans JP", FontWeight.Normal),
    brandedFont("Noto Sans SC", FontWeight.Normal),
    brandedFont("Noto Sans", FontWeight.Normal),
  )

private val ProductionBrandedFamily: FontFamily =
  FontFamily(
    listOf(
      brandedFont("Orbitron", FontWeight.Medium),
      brandedFont("Orbitron", FontWeight.SemiBold),
      brandedFont("Orbitron", FontWeight.Bold),
    ) + NotoFallback
  )

@Composable
fun BrandedDownloadableText() {
  Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
    Text(
      text = "MeshCore",
      color = Color.Black,
      style =
        TextStyle(fontFamily = BrandedFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    )
  }
}

@Composable
fun BrandedThemeTypographyText() {
  val base = Typography()
  val branded =
    base.copy(
      displaySmall =
        base.displaySmall.copy(
          fontFamily = ProductionBrandedFamily,
          fontWeight = FontWeight.Medium,
          fontSize = 16.sp,
        )
    )
  MaterialTheme(typography = branded) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
      Text(text = "MeshCore", color = Color.Black, style = MaterialTheme.typography.displaySmall)
    }
  }
}
