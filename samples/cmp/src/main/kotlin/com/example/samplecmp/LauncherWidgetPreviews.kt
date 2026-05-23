package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.launcher.LauncherWidgetSize
import ee.schimke.composeai.preview.launcher.launcherWidgetCells

/**
 * Smallest cell shape supported by [launcherWidgetCells] — the 1×1 starting state from the spec's
 * `1x1 → 4x2` example.
 */
@Preview(name = "Launcher widget 1x1", showBackground = true)
@Composable
fun LauncherWidget1x1Preview() {
  WidgetBody(label = "1×1", modifier = Modifier.launcherWidgetCells(LauncherWidgetSize(1, 1)))
}

/**
 * Final cell shape from the spec's `1x1 → 4x2` example. Renders statically because the renderer
 * captures a single frame — the stepped resize animation only kicks in when `cells` changes at
 * runtime (live preview, interactive host).
 */
@Preview(name = "Launcher widget 4x2", showBackground = true)
@Composable
fun LauncherWidget4x2Preview() {
  WidgetBody(label = "4×2", modifier = Modifier.launcherWidgetCells(LauncherWidgetSize(4, 2)))
}

/**
 * Demonstrates the min/max-cells clamp: the requested `5×5` is pegged into the `1×3` to `4×5`
 * range, so the modifier sizes the body at `4×5`. Mirrors a real launcher's `minResizeWidth` /
 * `minResizeHeight` behaviour.
 */
@Preview(name = "Launcher widget clamped to max 4x5", showBackground = true)
@Composable
fun LauncherWidgetClampedPreview() {
  WidgetBody(
    label = "clamped → 4×5",
    modifier =
      Modifier.launcherWidgetCells(
        cells = LauncherWidgetSize(5, 5),
        minCells = LauncherWidgetSize(1, 3),
        maxCells = LauncherWidgetSize(4, 5),
      ),
  )
}

@Composable
private fun WidgetBody(label: String, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.padding(4.dp).background(MaterialTheme.colorScheme.primaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer)
  }
}
