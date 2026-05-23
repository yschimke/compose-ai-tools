package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

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
