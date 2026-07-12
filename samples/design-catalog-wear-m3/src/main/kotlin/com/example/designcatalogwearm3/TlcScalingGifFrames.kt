package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// Frame source for the scroll-out-and-back GIF (`TlcScalingGifTest` stitches these). Each is the same
// HeartRateCard scrolled a bit further up — centred (0.0) to mostly off the top edge (0.6). They're
// plain full-screen @Preview frames (no @PreviewParameter), rendered by the plugin like any other
// preview; the numeric suffix orders them for the GIF.

private const val GIF_DEVICE = "id:wearos_large_round"

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll0() = HeartRateCardAt(0.0f)

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll1() = HeartRateCardAt(0.1f)

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll2() = HeartRateCardAt(0.2f)

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll3() = HeartRateCardAt(0.3f)

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll4() = HeartRateCardAt(0.4f)

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll5() = HeartRateCardAt(0.5f)

@Preview(name = "Large Round", device = GIF_DEVICE, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScroll6() = HeartRateCardAt(0.6f)
