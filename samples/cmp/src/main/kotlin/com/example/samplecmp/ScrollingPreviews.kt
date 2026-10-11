package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

/**
 * `@ScrollingPreview(modes = [TOP, END])`: a self-checking pair proving the desktop renderer drives
 * the scrollable — `_top` shows `Row 1` without the footer, `_end` shows `Row 40` and the footer.
 *
 * Separate from [ScrollingListPreview] because `@ScrollingPreview` is non-repeatable and applies to
 * every `@Preview` on the function.
 */
@Preview(name = "Scroll To End", widthDp = 240, heightDp = 240, showBackground = true)
@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END])
@Composable
fun ScrollToEndPreview() {
  Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
      items((1..40).toList()) { index ->
        Text(
          text = "Row $index",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
      }
      item {
        Text(
          text = "That's everything",
          style = MaterialTheme.typography.titleMedium,
          modifier =
            Modifier.fillMaxWidth().background(Color(0xFF6750A4)).padding(16.dp).semantics {
              contentDescription = "footer"
            },
          color = Color.White,
        )
      }
    }
  }
}

/**
 * `@ScrollingPreview(modes = [LONG, GIF])` on the CMP Desktop renderer, producing:
 * - `renders/ScrollingListPreview.png` — the unscrolled first viewport.
 * - `data/render-scroll-long/ScrollingListPreview.png` — the stitched full list, driving
 *   `SemanticsActions.ScrollBy` under a paused clock and stitching via
 *   `ScrollSliceStitcher.stitchSlices`.
 * - `data/render-scroll-gif/ScrollingListPreview.gif` — the scroll-through GIF.
 *
 * 100 items give several slice steps (stride ≈ 80% of the viewport) and a few fling bursts.
 */
@Preview(name = "Scrolling List", widthDp = 240, heightDp = 240, showBackground = true)
@ScrollingPreview(modes = [ScrollMode.LONG, ScrollMode.GIF])
@Composable
fun ScrollingListPreview() {
  Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
      items((1..100).toList()) { index ->
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .background(if (index % 2 == 0) Color(0xFFEEEEEE) else Color.White)
              .padding(12.dp)
        ) {
          Text(text = "Row $index", style = MaterialTheme.typography.titleMedium)
          Text(text = "Item content for row $index", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

/**
 * Night/light pair for a driven END capture. Its scheme follows `isSystemInDarkTheme()` (unlike
 * [ScrollToEndPreview]'s default `MaterialTheme`), so the captures differ only if `uiMode` reached
 * the driven composition; `showBackground = true` makes a fallback to white obvious.
 */
@Preview(
  name = "Scroll To End Night",
  widthDp = 240,
  heightDp = 240,
  showBackground = true,
  uiMode = 32,
)
@Preview(name = "Scroll To End Day", widthDp = 240, heightDp = 240, showBackground = true)
@ScrollingPreview(modes = [ScrollMode.END])
@Composable
fun ScrollToEndThemedPreview() {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
      LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items((1..30).toList()) { index ->
          Text(
            text = "Row $index",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
          )
        }
        item {
          Text(
            text = "That's everything",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier =
              Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(16.dp),
          )
        }
      }
    }
  }
}
