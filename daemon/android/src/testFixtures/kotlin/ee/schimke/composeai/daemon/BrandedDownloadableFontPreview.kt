package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
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
 * A preview whose text draws in a **branded downloadable face** — `Font(GoogleFont("Orbitron"), …)`,
 * the exact shape a consumer's production typography uses (see meshcore-mobile's `MeshcoreFonts`).
 *
 * Exists so [FigmaSvgDownloadableFontFamilyTest] can prove the `compose/figma-svg` export names the
 * branded family on its `<text>` rather than collapsing to the Roboto default. The certificate
 * array is `0` because nothing here talks to the real GMS provider: under Robolectric
 * `ShadowFontsContractCompat` intercepts the request, and the export reads the family name off the
 * `FontFamily` the composition declared, not off a resolved typeface.
 */
private val DownloadableFontProvider =
  GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = 0,
  )

private val BrandedFamily: FontFamily =
  FontFamily(
    Font(
      googleFont = GoogleFont("Orbitron"),
      fontProvider = DownloadableFontProvider,
      weight = FontWeight.Medium,
      style = FontStyle.Normal,
    )
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
