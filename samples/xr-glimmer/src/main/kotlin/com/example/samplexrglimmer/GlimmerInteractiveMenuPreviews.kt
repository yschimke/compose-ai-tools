package com.example.samplexrglimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.FocusedPreview
import ee.schimke.composeai.preview.GlimmerEnvironment
import ee.schimke.composeai.preview.GlimmerEnvironmentPreview

/**
 * Production-shaped Glimmer menu. It knows nothing about preview environments or compositing: the
 * renderer captures its additive RGB output on black, then `:data-glimmer-environment-connector`
 * preserves that raw capture and ADD-composites the annotation-selected environment afterward.
 */
@Composable
fun GlimmerMenu() {
  GlimmerTheme {
    Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ListItem(onClick = {}) { Text("Next track") }
      ListItem(onClick = {}) { Text("Previous track") }
      ListItem(onClick = {}) { Text("Add to favourites") }
      ListItem(onClick = {}) { Text("Send to phone") }
    }
  }
}

@Preview(
  name = "Light",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@GlimmerEnvironmentPreview(GlimmerEnvironment.Light)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuLight() = GlimmerMenu()

@Preview(
  name = "Dark",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@GlimmerEnvironmentPreview(GlimmerEnvironment.Dark)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuDark() = GlimmerMenu()

@Preview(
  name = "Busy",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@GlimmerEnvironmentPreview(GlimmerEnvironment.Busy)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuBusy() = GlimmerMenu()

@Preview(
  name = "VeniceCanalCats",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@GlimmerEnvironmentPreview(GlimmerEnvironment.VeniceCanalCats)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuVeniceCanalCats() = GlimmerMenu()

@Preview(
  name = "Animated Light",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@GlimmerEnvironmentPreview(GlimmerEnvironment.Light)
@AnimatedPreview(durationMs = 100, frameIntervalMs = 50, showCurves = false)
@Composable
fun GlimmerXrMenuAnimated() = GlimmerMenu()

@Preview(
  name = "Overlay Light",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@GlimmerEnvironmentPreview(GlimmerEnvironment.Light)
@FocusedPreview(indices = [0], overlay = true)
@Composable
fun GlimmerXrMenuOverlay() = GlimmerMenu()
