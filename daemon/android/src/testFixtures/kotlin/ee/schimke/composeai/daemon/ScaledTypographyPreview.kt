package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The JetNews article-header shape that exposed issue #3024: a **display-sized heading** and **body
 * text** in the same column, both wrapped hard against a fixed width.
 *
 * The pairing is the point. Compose resolves `sp` through the platform `FontScaleConverter` on API
 * 34+, and that curve is *non-linear* in the font scale — 14sp body text takes the full multiplier
 * while a 32sp heading takes almost none. A capture that reports only `sp` and lets the exporter
 * recompute `sp × density × fontScale` therefore agrees with the render on the body and is wildly
 * wrong on the heading: on JetNews's `fontScale = 1.5` render the export declared the 32sp title at
 * 126px where the render had drawn ~84px, so the captured line breaks no longer fit the card they
 * were measured in and the last line ran past its right edge. A heading-only or body-only fixture
 * misses it — one of the two always looks right.
 *
 * The width ([PARAGRAPH_WIDTH_DP]) is deliberately tight so both runs wrap and every line sits near
 * its width boundary, which is where an over-sized run stops being a cosmetic difference and starts
 * overflowing. Backs [ScaledTypographyExportTest].
 */
const val PARAGRAPH_WIDTH_DP: Int = 200

/** The heading size: display-scale, where the font-scale curve flattens toward identity. */
const val HEADING_SP: Int = 32

/** The body size: text-scale, where the curve applies the font scale in full. */
const val BODY_SP: Int = 14

@Composable
fun ScaledHeadingParagraph() {
  Column(
    modifier =
      Modifier.fillMaxSize().background(Color.White).width(PARAGRAPH_WIDTH_DP.dp).padding(8.dp)
  ) {
    Text(
      text = "From Java Programming Language to Kotlin",
      color = Color.Black,
      style =
        TextStyle(
          fontFamily = FontFamily.SansSerif,
          fontSize = HEADING_SP.sp,
          fontWeight = FontWeight.Normal,
        ),
    )
    Text(
      text = "Learn how to get started converting Java Programming Language code to Kotlin",
      color = Color.Black,
      style =
        TextStyle(
          fontFamily = FontFamily.SansSerif,
          fontSize = BODY_SP.sp,
          fontWeight = FontWeight.Normal,
        ),
    )
  }
}
