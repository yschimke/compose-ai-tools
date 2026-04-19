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
 * Demo fixture for `@ScrollingPreview`. 40 stacked bands going red (top)
 * → blue (bottom), each taller than the preview viewport so the top-of-list
 * capture is dominantly red and the scrolled-to-end capture is dominantly
 * blue. Pixel assertions in `:gradle-plugin:functionalTest` / manual eyes
 * both key off this gradient.
 */
@Composable
fun RedToBlueList() {
    val count = 40
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
