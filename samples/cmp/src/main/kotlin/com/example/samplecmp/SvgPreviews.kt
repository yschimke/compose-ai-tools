package com.example.samplecmp

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.svg.SvgPreview

/**
 * SVG image previews driven by `:svg-preview-runtime`'s [SvgPreview] helper — the customization
 * escape hatch over the zero-config discovery (the same `svg/badge.svg` also surfaces as a
 * `kind=SVG` still just by living under `src/main/resources/`). The plugin links the asset onto the
 * render classpath and packs it into bundles. Below: the full-color badge at its own colors, and a
 * monochrome `star.svg` icon recolored via `ColorFilter.tint` — `tint` keeps alpha, so on a
 * transparent-background icon it visibly recolors the shape (the intended use).
 */
@Preview
@Composable
fun SvgBadgePreview() {
  SvgPreview(asset = "svg/badge.svg", modifier = Modifier.size(200.dp))
}

@Preview
@Composable
fun SvgStarTintedPreview() {
  SvgPreview(
    asset = "svg/star.svg",
    modifier = Modifier.size(200.dp),
    colorFilter = ColorFilter.tint(Color(0xFFEA4335)),
  )
}
