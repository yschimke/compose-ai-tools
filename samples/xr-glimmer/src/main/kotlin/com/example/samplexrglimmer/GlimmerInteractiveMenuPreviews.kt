package com.example.samplexrglimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.TitleChip
import ee.schimke.composeai.preview.FocusedPreview

/**
 * Interactive XR navigation demo — a Glimmer menu plus a touchpad-gesture overlay, captured as
 * an animated GIF by `@FocusedPreview(indices = [...], gif = true)`. Each frame shows focus on
 * one menu item; the bottom-edge "▲ swipe up" affordance is what drove the transition. Glimmer's
 * input model is **1-D** (one finger on the touchpad, axis is contextual), so a static
 * swipe-up indicator is the honest representation across the whole sequence: every step is one
 * swipe.
 *
 * The four-frame GIF maps to the wearer-visible flow: glance at the menu, swipe up to walk down
 * the action list, repeat. A future `@GlimmerPreviewInput` + `:data-glimmer-input-connector`
 * (per `docs/design/GLIMMER_PREVIEW.md`) will replace the hand-rolled overlay with a planner-
 * driven `AroundComposable` reading `renderNow.overrides.glimmerInput` — at that point the
 * gesture per frame becomes dynamic (`▲ Next` / `▼ Previous` / `● Tap` / `↺ Back`) and the
 * indicator below moves into the connector. For now the static arrow + per-step `_FOCUS_<n>`
 * file naming carries the demo end-to-end.
 *
 * Item count: four — the AI Glasses canvas (640×480dp) seats four `ListItem`s plus the
 * bottom gesture indicator with ~14dp of slack at 16-dp inter-item spacing. A header chip
 * over the menu was the first cut but pushed the fourth item under the indicator and ate
 * the focus ring on frame 4; standalone chips live in the `NowPlayingCard` sample, this
 * demo's purpose is the focus walk + gesture overlay. The design's eventual
 * `GlimmerLazyColumn` Stack variant is what makes longer menus viable on glasses; for a
 * demo this size, fixed at four.
 *
 * Discovery side: `@FocusedPreview` flips `LocalInputModeManager` to Keyboard so Compose's
 * focusable system honours the renderer's `moveFocus(...)` calls — Robolectric's host
 * environment is permanently Touch otherwise and Glimmer's `ListItem(onClick)` focusables
 * would refuse focus. `indices` mode lands one capture per focus index; `gif = true` stitches
 * the captures into a single `<basename>.gif` instead of writing five PNG siblings.
 *
 * Env fan-out: four stacked `@Preview` annotations, one per backdrop the design doc names
 * (`docs/design/GLIMMER_PREVIEW.md` § "Data extension: `:data-glimmer-environment-connector`")
 * — Light / Dark / Busy / VeniceCanalCats. Each annotation produces an independent
 * `PreviewInfo.id` (distinct `name`) so discovery treats them as four separate captures and
 * the `@FocusedPreview` walk is replayed per env. Mirrors the pattern `NowPlayingCard` already
 * uses in the same module. Encoding B (`showBackground = true, backgroundColor = 0xFF000000`)
 * is shared across all four — the env intent is in the `name` suffix and travels into the
 * future env compositor, not into the capture itself. The four GIFs are byte-identical today
 * (the compositor module doesn't exist yet); they'll diverge once `:data-glimmer-environment-
 * connector` lands and pastes each capture onto its Studio-parity backdrop. The pixel-identity
 * assertion in `GlimmerInteractiveMenuTest` locks this contract down so any drift surfaces
 * during the connector's rollout, not silently afterwards.
 */
@Preview(
  name = "Glimmer XR Menu · Light",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Preview(
  name = "Glimmer XR Menu · Dark",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Preview(
  name = "Glimmer XR Menu · Busy",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Preview(
  name = "Glimmer XR Menu · VeniceCanalCats",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuNavigation() {
  GlimmerSurface {
    Box(Modifier.fillMaxSize()) {
      // No standalone TitleChip header — the AI-Glasses canvas (640×480dp) seats four
      // ListItems + the bottom gesture indicator with ~14dp of slack at 16-dp spacing,
      // and adding the chip pushes the fourth item under the indicator. Standalone chips
      // are exercised by the NowPlayingCard sample; this demo's purpose is the focus walk
      // + gesture overlay, not chip presentation.
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        ListItem(onClick = {}) { Text("Next track") }
        ListItem(onClick = {}) { Text("Previous track") }
        ListItem(onClick = {}) { Text("Add to favourites") }
        ListItem(onClick = {}) { Text("Send to phone") }
      }

      // XR touchpad-gesture indicator pinned to the bottom-centre. Mirrors the design
      // doc's `:data-glimmer-input-connector` arrow-glyph affordance — the same place an
      // overlay extension would paint, just baked into the composable until that connector
      // module exists. Drawn in primary tint so it reads against the additive-zero base.
      XrTouchpadGestureIndicator(
        gesture = XrGesture.SwipeUp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
      )
    }
  }
}

/**
 * Pill-shaped indicator showing which 1-D touchpad gesture the wearer would use to advance.
 * Drawn as a Glimmer `TitleChip` with the gesture glyph + label so it picks up the theme's
 * pill shape, surface tint, and outline border without us having to recreate that styling
 * by hand. The chip is non-interactive — purely a visual annotation on the capture.
 */
@Composable
private fun XrTouchpadGestureIndicator(gesture: XrGesture, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    TitleChip {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(gesture.glyph)
        Spacer(Modifier.width(8.dp))
        Text(gesture.label)
      }
    }
  }
}

/**
 * The 1-D touchpad gestures Glimmer's input model exposes — see SKILL.md § "Map input
 * controls". Kept as an enum so a future `:data-glimmer-input-connector` can encode the
 * planner's override directly as one of these and the overlay reads the field with no
 * string-parsing in between. `Tap` and `Back` aren't exercised by the GIF above but are
 * here for the eventual dynamic-gesture variant.
 */
internal enum class XrGesture(val glyph: String, val label: String) {
  SwipeUp(glyph = "▲", label = "swipe up"),
  SwipeDown(glyph = "▼", label = "swipe down"),
  Tap(glyph = "●", label = "tap"),
  Back(glyph = "↺", label = "back"),
}
