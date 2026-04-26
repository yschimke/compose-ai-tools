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
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

/**
 * Demo fixture for `@ScrollingPreview`. Stacked bands going red (top)
 * → blue (bottom), each taller than the preview viewport so the
 * top-of-list capture is dominantly red and the scrolled-to-end capture
 * is dominantly blue. Pixel assertions in `:gradle-plugin:functionalTest`
 * / manual eyes both key off this gradient.
 *
 * [count] defaults to 40 for the full-viewport TOP/END fixture; callers
 * driving a smaller viewport (e.g. the GIF preview below) can pass a
 * smaller value so the scroll extent fits inside the renderer's default
 * iteration budget.
 */
@Composable
fun RedToBlueList(count: Int = 40) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items((0 until count).toList()) { index ->
            val t = index.toFloat() / (count - 1).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(lerp(Color.Red, Color.Blue, t)),
            )
        }
    }
}

/**
 * Multi-mode scroll capture from a single preview function. Produces two
 * PNGs — `..._SCROLL_top.png` (initial unscrolled frame, mostly red) and
 * `..._SCROLL_end.png` (after driving the LazyColumn to its content end,
 * mostly blue). Pixel assertions in [ScrollPreviewPixelTest] key off this
 * gradient to prove both captures land on disk distinctly.
 */
@Preview(name = "Scroll", showBackground = true)
@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END])
@Composable
fun RedToBlueScrollPreview() {
    RedToBlueList()
}

/**
 * Animated-GIF capture of the same scroll. Produces a single `.gif`
 * showing the scroll from top (mostly red) to end (mostly blue). The
 * pixel test in [ScrollPreviewPixelTest] decodes the GIF and asserts
 * that frame 0 is red-dominant while the last frame is blue-dominant —
 * proving both that we scroll, and that frames round-trip through the
 * GIF encoder/decoder intact.
 *
 * Sized down via `widthDp`/`heightDp`: the default sandbox (400×800dp,
 * ≈1050×2100px at 2.625×) produces a ~600KB demo GIF, which is more
 * than this fixture needs. A 160×320dp viewport still shows 5 bands
 * per frame — plenty to prove the scroll moves through the gradient —
 * at ~1/6 the pixel budget. List length drops to 16 so the total scroll
 * extent fits inside [driveScrollByViewport]'s default iteration budget;
 * the animation therefore terminates at a fully blue-dominant last frame.
 */
@Preview(name = "ScrollGif", showBackground = true, widthDp = 160, heightDp = 320)
@ScrollingPreview(modes = [ScrollMode.GIF])
@Composable
fun RedToBlueScrollGifPreview() {
    RedToBlueList(count = 16)
}

/**
 * Regression fixture for #154. All captures in a multi-mode
 * `@ScrollingPreview` share a single `setContent` composition and run in
 * enum ordinal order (TOP → END → LONG → GIF), so when GIF follows END
 * the scrollable is already at content end by the time GIF starts. Before
 * the fix, the resulting `.gif` was a single frame indistinguishable
 * from the END capture (scrolled-to-bottom, blue-dominant) — see issue.
 * The fix scrolls back to the top before the frame walk, so frame 0
 * should be red-dominant again while the last frame is blue.
 */
@Preview(name = "EndThenGif", showBackground = true, widthDp = 160, heightDp = 320)
@ScrollingPreview(modes = [ScrollMode.END, ScrollMode.GIF])
@Composable
fun RedToBlueEndThenGifPreview() {
    RedToBlueList(count = 16)
}
