package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Regression fixture for issue #2974: a dark `@Preview(showBackground = true)` whose only drawn
 * child is a thin divider centred in a taller fixed-size `Box`.
 *
 * The `showBackground` fill must cover the **whole** 100×26dp root, not shrink-wrap to the 1dp
 * divider. The bug sized the layered-SVG background rect to the divider's bounds, so the SVG was
 * transparent almost everywhere while the PNG correctly filled the whole preview — the two
 * disagreed only on the dark variant, which is why it slipped past when the night annotation used
 * to render as light. Kept deliberately `Surface`-free so the backing colour (not a surface fill)
 * is what covers the frame. Pair-asserted end-to-end by [DividerShowBackgroundPreviewPixelTest].
 */
@Preview(
  name = "Divider Dark",
  showBackground = true,
  widthDp = 100,
  heightDp = 26,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun DividerShowBackgroundDarkPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(modifier = Modifier.size(width = 100.dp, height = 26.dp)) {
      // A hairline divider — the only thing that paints. Translucent white, like a Material
      // `HorizontalDivider`, so the dark backing shows through and the two must agree
      // pixel-for-pixel.
      Box(
        modifier =
          Modifier.align(Alignment.Center).fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF))
      )
    }
  }
}
