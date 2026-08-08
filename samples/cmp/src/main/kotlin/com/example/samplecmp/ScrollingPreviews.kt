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
 * `@ScrollingPreview(modes = [TOP, END])` — the pair that proves the desktop renderer actually
 * *drives* a scrollable rather than approximating.
 *
 * The two captures differ only in scroll position, so they are a self-checking fixture: `_top` must
 * show `Row 1` with the footer nowhere in sight, `_end` must show `Row 40` and the `That's
 * everything` footer. The footer exists to make the difference unmistakable — before END was driven
 * on this backend both files were the same unscrolled first viewport.
 *
 * Deliberately a different function from [ScrollingListPreview]: `@ScrollingPreview` is
 * non-repeatable and applies to every `@Preview` on the function, so LONG/GIF (data products) and
 * TOP/END (captures) can't share one without also crossing the fan-outs.
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
 * `@ScrollingPreview(modes = [LONG, GIF])` smoke test for the CMP Desktop renderer (issue #1207).
 *
 * The discovery task fans this preview out into:
 * - `renders/ScrollingListPreview.png` — primary capture (TOP — the unscrolled first viewport).
 * - `data/render-scroll-long/ScrollingListPreview.png` — stitched tall PNG of the full list,
 *   produced by `DesktopRendererMain.renderScrollPreview` driving `SemanticsActions.ScrollBy` on
 *   the inner `LazyColumn` against a `runComposeUiTest` paused clock and stitching the per-viewport
 *   slices via `ScrollSliceStitcher.stitchSlices`.
 * - `data/render-scroll-gif/ScrollingListPreview.gif` — animated scroll-through GIF using the same
 *   scroll-drive path plus the `buildGifScrollScript` cadence + `ScrollGifEncoder.encode`.
 *
 * 100 items is enough to ensure several slice steps (default stride is 80% of viewport ≈ 192 px per
 * step on a 240 px tall sandbox) and a few fling bursts in the GIF script.
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
 * The night/light pair for a DRIVEN END capture — the surface that regressed when END stopped
 * inheriting the framing an ordinary render gets.
 *
 * [ScrollToEndPreview] above cannot see that class of bug: it renders under the default
 * `MaterialTheme`, which is light whatever `LocalSystemTheme` says, so its `_end` capture looks the
 * same whether `uiMode` was honoured or dropped entirely. This one resolves its scheme from
 * `isSystemInDarkTheme()`, so the two captures differ if and only if the night bit reached the
 * driven composition — and the visual-diff bot then reports any future regression as moved pixels
 * rather than as nothing at all.
 *
 * `showBackground = true` is load-bearing too: on a night preview the resolved ground is dark, so a
 * capture that fell back to white shows up here as a white surround rather than a subtle shift.
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
