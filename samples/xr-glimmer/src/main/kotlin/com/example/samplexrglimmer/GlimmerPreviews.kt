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

/**
 * Sample Glimmer composables exercised by `:samples:xr-glimmer:composePreviewRenderAll`.
 *
 * Glimmer (`androidx.xr.glimmer:glimmer`) renders for additive display AI glasses — pure black
 * pixels render as 100% transparent on-device, so the SKILL.md mandates a `Color.Black`
 * background on the root projected-activity container. Captures here mirror that intent:
 * `showBackground = true, backgroundColor = 0xFF000000` so the rendered PNG is opaque RGB
 * (Encoding B in `docs/design/GLIMMER_PREVIEW.md`) with `RGB == (0, 0, 0)` everywhere the
 * Glimmer UI didn't paint — that's the additive-zero baseline an env compositor would later
 * `ADD`-blend onto a Light / Dark / Busy / Venice-canal-cats backdrop.
 *
 * Preview `name` values track the per-env family proposed by the design doc
 * (`Glimmer · Light`, `Glimmer · Dark`, `Glimmer · Busy`, `Glimmer · VeniceCanalCats`,
 * `Glimmer · Input`) so when the `@GlimmerPreview*` meta-annotations from
 * `:glimmer-preview-runtime` land, this file's previews fold in as a drop-in replacement. The
 * four card variants produce identical captures today — they will diverge once
 * `:data-glimmer-environment-connector` wires the env compositor in.
 */

// Wraps content in `GlimmerTheme` against an additive-zero `Color.Black` base. Standalone helper
// inside the sample so we don't have to depend on a `:glimmer-preview-runtime` that doesn't exist
// yet; the eventual published `GlimmerSurface` from that module is intentionally the same shape.
@Composable
private fun GlimmerSurface(content: @Composable () -> Unit) {
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
    // Standalone TitleChip sitting above a Card — SKILL.md § Title Chips: "Pill-shaped
    // specialized labeling component sitting above Card or content groups." 8.dp is the
    // documented `TitleChipDefaults.AssociatedContentSpacing` between a standalone chip
    // and its associated card; hard-coded as a literal here to avoid pulling the composable
    // accessor (it's a @Composable getter that can't be inlined from a regular `val`) and
    // to keep the sample's dependency list to Glimmer + Compose foundation only.
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
 * Focusable-menu scenario. Drawn as three `ListItem`s stacked vertically (not a
 * `GlimmerLazyColumn` — the lazy container plays games with focus delegation that the static
 * `@Preview` clock can't drive deterministically; the design's `@GlimmerPreviewInput` overlay
 * is the right surface for that). Same additive-zero encoding as [NowPlayingCard]; SKILL.md's
 * documented `verticalArrangement = Arrangement.spacedBy(20.dp)` for `VerticalList` is the
 * source of the 20-dp gap here.
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
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      ListItem(onClick = {}) { Text("Next track") }
      ListItem(onClick = {}) { Text("Add to favourites") }
      ListItem(onClick = {}) { Text("Send to phone") }
    }
  }
}

// Studio's AI-Glasses preview-pane device preset (4:3 at 240dpi). Values are expressed in dp
// without a suffix — the discovery-side `DeviceSpec.resolve(...)` reads `width=` / `height=`
// as bare integers (`spec:width=…px` silently falls back to the 400×800dp default) and
// applies `dpi/160` as the density. Kept as a named const so the previews above all share
// one source of truth — bump in lockstep when the eventual `@GlimmerPreview` meta-annotation
// adopts a different spec.
private const val AI_GLASSES_DEVICE_SPEC: String = "spec:width=640,height=480,dpi=240"

// Opaque pure black — additive-zero base per the SKILL.md mandate. Drawn by the renderer's
// background-fill path so the captured PNG carries `RGB == (0, 0, 0)` in every pixel the
// Glimmer UI didn't paint, ready for an `ADD`-blend env compositor.
private const val ADDITIVE_ZERO_BACKGROUND: Long = 0xFF000000L
