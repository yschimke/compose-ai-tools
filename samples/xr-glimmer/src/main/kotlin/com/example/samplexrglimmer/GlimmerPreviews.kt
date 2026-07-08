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
 * pixels render as 100% transparent on-device, so the SKILL.md mandates a `Color.Black` background
 * on the root projected-activity container. Captures here mirror that intent: `showBackground =
 * true, backgroundColor = 0xFF000000` so the rendered PNG is opaque RGB (Encoding B) with `RGB ==
 * (0, 0, 0)` everywhere the Glimmer UI didn't paint — that's the additive-zero baseline an env
 * compositor would later `ADD`-blend onto a Light / Dark / Busy / Venice-canal-cats backdrop.
 *
 * Preview `name` values track the per-env family proposed by the design doc (`Glimmer · Light`,
 * `Glimmer · Dark`, `Glimmer · Busy`, `Glimmer · VeniceCanalCats`, `Glimmer · Input`) so when the
 * `@GlimmerPreview*` meta-annotations from `:glimmer-preview-runtime` land, this file's previews
 * fold in as a drop-in replacement. The four card variants produce identical captures today — they
 * will diverge once `:data-glimmer-environment-connector` wires the env compositor in.
 */

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
    // Standalone TitleChip sitting above a Card — SKILL.md § Title Chips: "Pill-shaped
    // specialized labeling component sitting above Card or content groups." 8.dp is the
    // documented `TitleChipDefaults.AssociatedContentSpacing` between a standalone chip
    // and its associated card; hard-coded as a literal here to avoid pulling the composable
    // accessor (it's a @Composable getter that can't be inlined from a regular `val`) and
    // to keep the sample's dependency list to Glimmer + Compose foundation only.
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
 * Focusable-menu scenario. Drawn as three `ListItem`s stacked vertically (not a `GlimmerLazyColumn`
 * — the lazy container plays games with focus delegation that the static `@Preview` clock can't
 * drive deterministically; the design's `@GlimmerPreviewInput` overlay is the right surface for
 * that). Same additive-zero encoding as [NowPlayingCard]; SKILL.md's documented
 * `verticalArrangement = Arrangement.spacedBy(20.dp)` for `VerticalList` is the source of the 20-dp
 * gap here.
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

// Studio's AI-Glasses preview-pane device preset, calibrated to Glimmer's published **angular**
// sizing model rather than a phone-style dp/dpi guess. Glimmer measures UI in visual angle, not
// pixels: the design guidance (developer.android.com/design/ui/ai-glasses/guides/styles/type)
// pins the display at **30 pixels-per-degree (PPD)** and a minimum readable text size of
// **0.6° = 18px**, and the official skill restates that as **18sp** — an identity (18sp == 18px
// == 0.6°) that only holds when **density == 1.0** (dpi 160). At the old `dpi=240` (density 1.5)
// every `.sp`/`.dp` Glimmer component rendered 1.5× larger in angle than Studio shows (18sp →
// 27px → 0.9°), so contrast/legibility read optimistically. Pinning **dpi=160 ⇒ density 1.0**
// makes our sp/px/degree mapping numerically identical to Studio's.
//
// Canvas: **960×720 px** (width/height are bare dp integers — `DeviceSpec.resolve(...)` reads
// `width=` / `height=` as integers and `spec:width=…px` silently falls back to the 400×800dp
// default, so no `px` suffix — and at density 1.0 dp == px). That is the same pixel canvas the
// old `640×480 @ dpi240` produced (640·1.5 = 960, 480·1.5 = 720), so the 960×720 env backdrops
// still fit exactly; only the density (and thus the angular size of the Glimmer UI) changes. At
// 30 PPD the canvas spans **32° × 24°** field-of-view (960/30, 720/30) — a plausible 4:3 display-
// glasses HUD. Re-pin width/height/dpi here if Google publishes the AI Glasses AVD's exact
// resolution / FoV / densityDpi; the 30-PPD identity above is the anchor to preserve.
//
// Kept as a named const so the previews above (and `GlimmerInteractiveMenuPreviews`) share one
// source of truth — bump in lockstep when the eventual `@GlimmerPreview` meta-annotation lands.
internal const val AI_GLASSES_DEVICE_SPEC: String = "spec:width=960,height=720,dpi=160"

// Opaque pure black — additive-zero base per the SKILL.md mandate. Drawn by the renderer's
// background-fill path so the captured PNG carries `RGB == (0, 0, 0)` in every pixel the
// Glimmer UI didn't paint, ready for an `ADD`-blend env compositor.
internal const val ADDITIVE_ZERO_BACKGROUND: Long = 0xFF000000L
