package com.example.samplecmp

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.lottie.LottiePreview

/**
 * Lottie animation previews driven by `:lottie-preview-runtime`'s [LottiePreview] helper. The
 * `lottie/spin.json` asset lives under `src/main/resources/`; the preview plugin links it onto the
 * render classpath and packs it into bundles. `progress` is the configured value baked into each
 * captured frame — the two previews below show the same animation at the start and a quarter
 * through.
 */
@Preview
@Composable
fun LottieSpinStartPreview() {
  LottiePreview(asset = "lottie/spin.json", progress = 0f, modifier = Modifier.size(200.dp))
}

@Preview
@Composable
fun LottieSpinQuarterPreview() {
  LottiePreview(asset = "lottie/spin.json", progress = 0.25f, modifier = Modifier.size(200.dp))
}
