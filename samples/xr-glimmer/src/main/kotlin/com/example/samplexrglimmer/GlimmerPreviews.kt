package com.example.samplexrglimmer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.Card
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.TitleChip

// Sample Glimmer composables. Glimmer targets additive displays where pure black is transparent, so
// previews use a black background: the PNG is opaque with RGB (0,0,0) wherever the UI didn't paint,
// the additive-zero baseline an environment compositor can blend onto a backdrop. Preview names
// follow the per-environment family (`Glimmer · Light`, `· Dark`, `· Busy`, …); the card variants
// are identical until the environment connector is wired in.

// Wraps content in `GlimmerTheme` against an additive-zero `Color.Black` base. Standalone helper
// inside the sample so we don't have to depend on a `:glimmer-preview-runtime` that doesn't exist
// yet; the eventual published `GlimmerSurface` from that module is intentionally the same shape.
@Composable
internal fun GlimmerSurface(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
    GlimmerTheme(content = content)
  }
}

@Preview(
  name = "Glimmer · Light",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Preview(
  name = "Glimmer · Dark",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Preview(
  name = "Glimmer · Busy",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Preview(
  name = "Glimmer · VeniceCanalCats",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Composable
fun NowPlayingCard() {
  GlimmerSurface {
    // A standalone TitleChip above a Card, 8.dp apart
    // (`TitleChipDefaults.AssociatedContentSpacing`, written as a literal because the accessor is a
    // @Composable getter).
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      TitleChip { Text("NOW PLAYING") }
      Spacer(Modifier.height(8.dp))
      Card(
        onClick = {},
        title = { Text("Lake of Fire") },
        subtitle = { Text("Nirvana — MTV Unplugged in New York") },
      ) {
        // Empty body content slot — the title / subtitle cover the visible layout. A
        // non-null content lambda is required by the Card API.
      }
    }
  }
}

/**
 * Focusable menu: three stacked `ListItem`s rather than a `GlimmerLazyColumn`, whose focus
 * delegation a static preview can't drive deterministically. 20dp gaps per `VerticalList` guidance.
 */
@Preview(
  name = "Glimmer · Input",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Composable
fun FocusableMenu() {
  GlimmerSurface {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      ListItem(onClick = {}) { Text("Next track") }
      ListItem(onClick = {}) { Text("Add to favourites") }
      ListItem(onClick = {}) { Text("Send to phone") }
    }
  }
}

// Studio's AI-Glasses device preset, calibrated to Glimmer's angular sizing: 30 pixels-per-degree,
// with 18sp == 18px == 0.6° minimum text, which holds only at density 1.0 (dpi 160).
//
// 960×720 (bare integers: `DeviceSpec.resolve` doesn't accept a `px` suffix) at density 1.0 spans
// 32° × 24° at 30 PPD and matches the environment backdrops. Re-pin if Google publishes the AVD's
// exact resolution; keep the 30-PPD identity. Shared with `GlimmerInteractiveMenuPreviews`.
internal const val AI_GLASSES_DEVICE_SPEC: String = "spec:width=960,height=720,dpi=160"

// Opaque pure black — additive-zero base per the SKILL.md mandate. Drawn by the renderer's
// background-fill path so the captured PNG carries `RGB == (0, 0, 0)` in every pixel the
// Glimmer UI didn't paint, ready for an `ADD`-blend env compositor.
internal const val ADDITIVE_ZERO_BACKGROUND: Long = 0xFF000000L
