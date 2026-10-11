package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

/**
 * Demo fixture for `@ScrollingPreview`: bands from red (top) to blue (bottom), so the top capture
 * is mostly red and the end capture mostly blue. [count] is smaller for small viewports so the
 * scroll fits the renderer's iteration budget.
 */
@Composable
fun RedToBlueList(count: Int = 40) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    items((0 until count).toList()) { index ->
      val t = index.toFloat() / (count - 1).toFloat()
      Box(
        modifier = Modifier.fillMaxWidth().height(60.dp).background(lerp(Color.Red, Color.Blue, t))
      )
    }
  }
}

/**
 * Multi-mode scroll capture: `..._SCROLL_top.png` (mostly red) and `..._SCROLL_end.png` (mostly
 * blue), checked by [ScrollPreviewPixelTest].
 */
@Preview(name = "Scroll", showBackground = true)
@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END])
@Composable
fun RedToBlueScrollPreview() {
  RedToBlueList()
}

/**
 * Animated-GIF capture of the scroll; [ScrollPreviewPixelTest] checks frame 0 is red-dominant and
 * the last blue-dominant. 160×320dp keeps the GIF small; 16 bands fit [driveScrollByViewport]'s
 * default iteration budget.
 */
@Preview(name = "ScrollGif", showBackground = true, widthDp = 160, heightDp = 320)
@ScrollingPreview(modes = [ScrollMode.GIF])
@Composable
fun RedToBlueScrollGifPreview() {
  RedToBlueList(count = 16)
}

/**
 * Regression fixture: captures in a multi-mode `@ScrollingPreview` share one composition and run
 * TOP → END → LONG → GIF, so GIF must scroll back to the top first (frame 0 red, last frame blue).
 */
@Preview(name = "EndThenGif", showBackground = true, widthDp = 160, heightDp = 320)
@ScrollingPreview(modes = [ScrollMode.END, ScrollMode.GIF])
@Composable
fun RedToBlueEndThenGifPreview() {
  RedToBlueList(count = 16)
}

/**
 * Regression fixture for [#4247](https://github.com/yschimke/compose-ai-tools/issues/4247): a
 * non-frame-aligned `advanceTimeMillis` (500ms; the clock rounds to 512) shared by a capture and a
 * scroll data product. Conflating requested and physical time would drift or abort with "output
 * advanceTimeMillis must be ascending"; if so, this preview stops producing output.
 */
@Preview(name = "ScrollTimed", showBackground = true, widthDp = 160, heightDp = 320)
@ScrollingPreview(modes = [ScrollMode.LONG])
@RoboComposePreviewOptions(manualClockOptions = [ManualClockOptions(advanceTimeMillis = 500L)])
@Composable
fun RedToBlueScrollTimedPreview() {
  RedToBlueList(count = 16)
}
