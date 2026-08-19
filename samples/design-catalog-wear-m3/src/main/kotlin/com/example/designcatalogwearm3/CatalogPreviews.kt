package com.example.designcatalogwearm3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.OutlinedCard
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import ee.schimke.composeai.overrides.previewOverrideBoolean
import ee.schimke.composeai.overrides.previewOverrideString
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogVariant
import ee.schimke.composeai.preview.FocusedPreview
import ee.schimke.composeai.preview.InteractionPreview
import ee.schimke.composeai.preview.OverrideVariant
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview
import ee.schimke.composeai.preview.slots.PreviewSlot

// ---------------------------------------------------------------------------
// Buttons — the Wear M3 emphasis levels plus the screen-hugging EdgeButton.
// ---------------------------------------------------------------------------

// The `disabled` state rides this same function via `@OverrideVariant` (seeding the `enabled` knob)
// instead of a duplicated `ButtonDisabled` wrapper — the render emits a `_VARIANT_disabled` capture
// that folds under this sticker. `pressed` / `focused` stay separate functions below: they are
// driven by `@FocusedPreview`, a per-function capture annotation rather than a `previewOverride*`
// knob — `focused` through real focus traversal, and `pressed` through the focused component's
// real input path.
// The button family carries no state of its own, so each click is made
// visible by [wearCounted] tallying into the label; the baked capture is unchanged. The `disabled`
// variant is the deliberate exception — it stays inert, because that's the state it documents, and
// `enabled = false` means the counter could never move anyway.
@CatalogComponent(id = "Button/Filled", group = "Buttons")
@CatalogWearModes
@OverrideVariant(name = "disabled", booleans = ["enabled=false"])
@Composable
fun FilledButton() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_filled)))
  Button(onClick = onClick, enabled = previewOverrideBoolean("enabled", true)) { Text(label) }
}

@CatalogComponent(id = "Button/Tonal", group = "Buttons")
@CatalogWearModes
@Composable
fun FilledTonalButtonSticker() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_tonal)))
  FilledTonalButton(onClick = onClick) { Text(label) }
}

@CatalogComponent(id = "Button/Outlined", group = "Buttons")
@CatalogWearModes
@Composable
fun OutlinedButtonSticker() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_outlined)))
  OutlinedButton(onClick = onClick) { Text(label) }
}

@CatalogComponent(id = "Button/Child", group = "Buttons")
@CatalogWearModes
@Composable
fun ChildButtonSticker() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_child)))
  ChildButton(onClick = onClick) { Text(label) }
}

// A workout history the EdgeButton sticker scrolls through. Long enough to
// overflow the viewport by a few screens so, scrolled to the end, the list fills
// the space above the edge button.
private val edgeButtonHistory =
  listOf(
    R.string.title_morning_run to "5.2 km · 28 min",
    R.string.activity_heart_rate to "72 bpm",
    R.string.activity_sleep to "7h 14m",
    R.string.activity_steps to "6,482",
    R.string.activity_calories to "412 kcal",
    R.string.activity_cycle to "18 km · 41 min",
    R.string.activity_swim to "1.2 km · 32 min",
    R.string.activity_hike to "9.4 km · 1h 52m",
    R.string.activity_strength to "45 min",
    R.string.activity_stretch to "12 min",
    R.string.activity_yoga to "30 min",
    R.string.activity_row to "2.0 km · 9 min",
  )

// EdgeButton hugs the bottom edge of the round screen via the
// ScreenScaffold(edgeButton = …) slot — its curved shape *is* that placement, so
// it's a full-screen component: placed via [FullScreenWear] + a real
// ScreenScaffold + TransformingLazyColumn with the Wear M3 scaling transformation
// (SurfaceTransformation), mirroring samples/wear's ActivityListScreen so the
// button measures at its resting size, and captured at every size breakpoint.
//
// ScreenScaffold reveals the edge button from its scroll state: at the resting
// top it's collapsed, expanding only once the list settles at the bottom. A
// static capture freezes the hidden initial frame (the renderer pauses the
// clock), so the sticker uses @ScrollingPreview(END) — scroll the overflowing
// list to the end (the renderer settles post-scroll animations, so the EdgeButton
// reveal lands at rest) and capture the single settled frame.
@CatalogComponent(
  id = "EdgeButton",
  group = "Buttons",
  caption = "Screen-hugging bottom action unique to Wear.",
  perBreakpoint = true,
)
@CatalogWearBreakpoints
@ScrollingPreview(modes = [ScrollMode.END])
@Composable
fun EdgeButtonSticker() = FullScreenWear {
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  val (edgeLabel, onEdgeClick) =
    wearCounted(previewOverrideString("edgeLabel", stringResource(R.string.label_start)))
  ScreenScaffold(
    scrollState = listState,
    edgeButton = {
      EdgeButton(onClick = onEdgeClick, buttonSize = EdgeButtonSize.Large) { Text(edgeLabel) }
    },
  ) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize(),
    ) {
      item {
        ListHeader(
          modifier = Modifier.transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        ) {
          Text(previewOverrideString("header", stringResource(R.string.header_workout)))
        }
      }
      items(edgeButtonHistory) { (titleRes, subtitle) ->
        val (title, onClick) = wearCounted(stringResource(titleRes))
        TitleCard(
          onClick = onClick,
          title = { Text(title) },
          subtitle = { Text(subtitle) },
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Lists — the Wear M3 scaling TransformingLazyColumn. Items scale + fade toward
// the curved top/bottom edges via the SurfaceTransformation, the signature Wear
// list treatment. Full-screen, captured at every size breakpoint.
// ---------------------------------------------------------------------------

private val scalingListItems =
  listOf(
    R.string.title_morning_run to "5.2 km · 28 min",
    R.string.activity_heart_rate to "72 bpm",
    R.string.activity_sleep to "7h 14m",
    R.string.activity_steps to "6,482",
    R.string.activity_calories to "412 kcal",
    R.string.activity_cycle to "18 km · 41 min",
  )

@CatalogComponent(
  id = "TransformingLazyColumn",
  group = "Lists",
  caption = "Scaling list — items scale + fade toward the curved edges (SurfaceTransformation).",
  perBreakpoint = true,
)
@CatalogWearBreakpoints
@Composable
fun ScalingListSticker() = FullScreenWear {
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize(),
    ) {
      item {
        ListHeader(
          modifier = Modifier.transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        ) {
          Text(previewOverrideString("header", stringResource(R.string.header_activity)))
        }
      }
      items(scalingListItems) { (titleRes, subtitle) ->
        val (title, onClick) = wearCounted(stringResource(titleRes))
        TitleCard(
          onClick = onClick,
          title = { Text(title) },
          subtitle = { Text(subtitle) },
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Scaffold templates — full-screen, pre-built screen skeletons an app copies
// whole, captured at every breakpoint. Like every other full-screen capture they
// carry the curved TimeText status strip, supplied once by [WearScaffoldTemplate]
// (an alias of [FullScreenWear]) and frozen at "10:10" so the weekly
// design-artifacts bundle doesn't churn on the live system time.
//
// Two variants mirror the Wear status-strip archetypes: the base list screen and
// a horizontal pager with a page indicator. A third, `Template/EdgeButton`, was
// dropped in the feature-scoping pass — it was a second full-screen
// `@ScrollingPreview(END)` × breakpoint capture, which `EdgeButtonSticker`
// already carries, and one of the two most expensive renders on the sheet.
// ---------------------------------------------------------------------------

private val templateListItems =
  listOf(
    R.string.title_morning_run to "5.2 km · 28 min",
    R.string.activity_heart_rate to "72 bpm",
    R.string.activity_sleep to "7h 14m",
    R.string.activity_steps to "6,482",
  )

// Base template: the canonical Wear list screen — TimeText status strip at the
// curved top, a ListHeader, and a scaling TransformingLazyColumn of TitleCards.
@CatalogComponent(
  id = "Template/TimeText",
  group = "Scaffold templates",
  caption =
    "Full-screen list scaffold with the curved TimeText status strip — the base Wear screen.",
  perBreakpoint = true,
)
@CatalogWearBreakpoints
@Composable
fun TimeTextScaffoldTemplate() = WearScaffoldTemplate {
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize(),
    ) {
      item {
        ListHeader(
          modifier = Modifier.transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        ) {
          Text(previewOverrideString("header", stringResource(R.string.header_activity)))
        }
      }
      items(templateListItems) { (titleRes, subtitle) ->
        val (title, onClick) = wearCounted(stringResource(titleRes))
        TitleCard(
          onClick = onClick,
          title = { Text(title) },
          subtitle = { Text(subtitle) },
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        )
      }
    }
  }
}

// Page-indicator template: a horizontal pager with the Wear M3
// HorizontalPageIndicator hugging the bottom curve. Seeded on the middle page so
// the indicator reads as a real multi-page carousel, under the TimeText strip.
@CatalogComponent(
  id = "Template/PageIndicator",
  group = "Scaffold templates",
  caption =
    "Horizontal pager scaffold with an edge-hugging HorizontalPageIndicator under the TimeText " +
      "strip.",
  perBreakpoint = true,
)
@CatalogWearBreakpoints
@Composable
fun PageIndicatorScaffoldTemplate() = WearScaffoldTemplate {
  val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
  Box(Modifier.fillMaxSize()) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          previewOverrideString("page", stringResource(R.string.label_page, page + 1), index = page)
        )
      }
    }
    HorizontalPageIndicator(
      pagerState = pagerState,
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

// ---------------------------------------------------------------------------
// Selection controls.
// ---------------------------------------------------------------------------

// The off state rides this function via `@OverrideVariant` (seeding `checked = false`) instead of a
// duplicated `SwitchButtonOff` — the render emits a `_VARIANT_off` capture that folds under this
// sticker as the off state.
//
// The interaction capture rides the same function too, mirroring the mobile sheet's `SwitchOn`:
// `targets = [0, 0]` is how a toggle is spelled — one tap off, one tap back on. It is also this
// repo's standing regression net for `@InteractionPreview` on the Robolectric backend (issue
// #4215): this is an Android module, CI renders every module, and if the backend stops honouring
// the script the `.apng` stops being written and the missing-renders gate says so.
@CatalogComponent(
  id = "SwitchButton/On",
  group = "Selection",
  caption = "On state; the off state folds in as an @OverrideVariant (checked = false).",
)
@CatalogWearModes
@OverrideVariant(name = "off", booleans = ["checked=false"])
@InteractionPreview(
  targets = [0, 0],
  caption =
    "Toggle off and back on. The thumb rides Wear Material 3's own spatial spec — the travel " +
      "and its settle are what a still frame of either end state cannot show.",
)
@Composable
fun SwitchButtonOn() = WearSticker {
  val (checked, onCheckedChange) = wearChecked(previewOverrideBoolean("checked", true))
  SwitchButton(
    checked = checked,
    onCheckedChange = onCheckedChange,
    label = { Text(previewOverrideString("label", stringResource(R.string.label_wifi))) },
  )
}

// ---------------------------------------------------------------------------
// Containment.
// ---------------------------------------------------------------------------

// The card content regions are wrapped in `PreviewSlot(name)` markers: a no-op in a normal render
// (the label draws unchanged, tagged `dp-slot:<name>`), swapping to a labelled placeholder under
// `LocalSlotMode`. Each slot is `fillMaxWidth` so its captured `dp-slot:*` bounds are the card's
// full fillable content width — the region a structured-screen fill targets — not just the label
// box. Height wraps the content, and Wear card/title content is already start-aligned and
// full-width, so the baked render is unchanged.
@CatalogComponent(id = "Card", group = "Containment")
@CatalogWearModes
@Composable
fun CardSticker() = WearSticker {
  // A Wear card is a clickable surface (`onClick` is required, unlike M3's plain `Card`), so it
  // gets the same click tally the buttons do rather than a dead handler.
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_card)))
  Card(onClick = onClick) { PreviewSlot("content", Modifier.fillMaxWidth()) { Text(label) } }
}

// The outlined card variant (`OutlinedCard`) — the Wear parallel of remote-m3's `Card/Outlined`.
// Same "Card" label as the filled `Card` above; only the outlined-vs-filled treatment differs.
@CatalogComponent(
  id = "Card/Outlined",
  group = "Containment",
  caption = "Outlined card variant (OutlinedCard).",
)
@CatalogWearModes
@Composable
fun OutlinedCardSticker() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_card)))
  OutlinedCard(onClick = onClick) {
    PreviewSlot("content", Modifier.fillMaxWidth()) { Text(label) }
  }
}

@CatalogComponent(id = "TitleCard", group = "Containment")
@CatalogWearModes
@Composable
fun TitleCardSticker() = WearSticker {
  val (title, onClick) =
    wearCounted(previewOverrideString("title", stringResource(R.string.title_morning_run)))
  TitleCard(
    onClick = onClick,
    title = { PreviewSlot("title", Modifier.fillMaxWidth()) { Text(title) } },
  ) {
    PreviewSlot("subtitle", Modifier.fillMaxWidth()) {
      Text(previewOverrideString("subtitle", "5.2 km · 28 min"))
    }
  }
}

// ---------------------------------------------------------------------------
// Communication.
// ---------------------------------------------------------------------------

@CatalogComponent(id = "Progress/Circular", group = "Communication")
@CatalogWearModes
@Composable
fun CircularProgressSticker() =
  // Determinate at a fixed 66% (matching the remote `Progress/Circular` parallel) rather than the
  // animated indeterminate overload, so the static capture is deterministic and the pair lines up.
  WearSticker { CircularProgressIndicator(progress = { 0.66f }, modifier = Modifier.size(72.dp)) }

// The **indeterminate** counterpart to [CircularProgressSticker]: the animated Wear M3 progress
// ring — the no-`progress` overload — sweeping continuously rather than sitting at a fixed value.
// In the live interactive stream the held composition's clock advances by the wall-clock delta, so
// the sweep actually animates (`CircularProgressIndicator`'s indeterminate mode is a
// `rememberInfiniteTransition`); a static capture freezes it at the paused-clock frame, which is
// deterministic because the renderer parks infinite animations at a fixed advance (see AGENTS.md —
// "indeterminate CircularProgressIndicator" is a called-out case). Sized to match the determinate
// sticker so the pair frames alike.
@CatalogComponent(
  id = "Progress/Circular/Indeterminate",
  group = "Communication",
  caption =
    "Indeterminate (animated) progress ring — the no-progress overload sweeps continuously; " +
      "animates in the live preview.",
)
@CatalogWearModes
@Composable
fun IndeterminateCircularProgressSticker() = WearSticker {
  CircularProgressIndicator(modifier = Modifier.size(72.dp))
}

// The same indeterminate ring captured as an **animated GIF** — the shareable, self-playing form of
// the spinner (the static sticker above only moves in the live interactive lane).
// `@AnimatedPreview`
// drives the paused clock across the animation window and encodes `renders/<id>.gif`;
// `showCurves = false` keeps it a screenshot-only GIF (no debug curve-plot panel), and the duration
// auto-detects from the indeterminate `InfiniteTransition`'s iteration so the loop is seamless.
// Standalone, not a `catalog.spec` component: the sticker-sheet join represents each component as a
// static PNG, so a GIF-primary preview travels in the bundle as `previews/<id>.gif` (same treatment
// as `CardScalingScrollGif`) rather than becoming a grid sticker.
@Preview(showBackground = false)
@AnimatedPreview(showCurves = false)
@Composable
fun IndeterminateCircularProgressGif() = WearSticker {
  CircularProgressIndicator(modifier = Modifier.size(72.dp))
}

// ---------------------------------------------------------------------------
// Text options — exercises the maxLines / overflow product on a round screen.
// ---------------------------------------------------------------------------

@CatalogComponent(
  id = "Text/MaxLines-Truncated",
  group = "Text options",
  caption = "maxLines=2 + ellipsis on a round screen.",
)
@CatalogWearModes
@Composable
fun TextMaxLinesTruncated() = WearSticker {
  Text(
    previewOverrideString("text", stringResource(R.string.wear_body_overflow)),
    modifier = Modifier.width(140.dp),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

// ---------------------------------------------------------------------------
// States — interaction (pressed / focused; focus matters on Wear for rotary /
// D-pad), disabled, and toggle off↔on.
//
// Both stickers are driven by REAL input, not by a forged interaction (issue
// #3672). They used to seed a held Press / Focus interaction onto a
// `MutableInteractionSource` from a `LaunchedEffect`, which paints a state layer
// without anything actually being focused or pressed: the focus system doesn't
// own the node, no `Unfocus` / `Release` ever pairs the emission, and any
// component whose indication reads the focus system rather than the interaction
// source captures identically to an untouched one.
//
// `@FocusedPreview` is the repo's mechanism for this and it works here because
// this catalog renders on Robolectric: it runs a real `FocusManager.moveFocus`
// traversal and flips `LocalInputModeManager` to Keyboard mode — which Robolectric
// needs, since its host environment is permanently Touch and `Modifier.clickable`
// registers its focusable as `Focusability.SystemDefined` (refused while in touch
// mode). `indices = [0]` is the single Button in either sticker; a single-capture
// `@FocusedPreview` keeps the plain
// `renders/<id>.png` filename (see `emitStaticCross` in PreviewDiscovery.kt), so
// the design-artifacts fold by function name is untouched.
//
// The pressed sticker adds `pressed = true` and does NOT seed its own
// `MutableInteractionSource`. Seeding one is what it used to do, and the capture
// it produced was pixel-identical to the resting `FilledButton` — the reason is
// the renderer, not the emission: Wear M3's only press affordance is
// `material-ripple`, which on Android is a platform `RippleDrawable` animated on
// the Choreographer rather than Compose's `mainClock`, and `RobolectricRenderTest`
// idles the main looper so that drawable settles ONLY for a `focus.pressed`
// capture. A hand-seeded press never gets that settle, so it never reaches the
// PNG. `@FocusedPreview(pressed = true)` takes the path that does, and is also
// what a real Wear press looks like: focus arrives first over rotary / D-pad,
// then the press lands on the focused component.
//
// That settle had to be resized to make this specimen trustworthy — see
// `PRESS_SETTLE_MS` in `RobolectricRenderTest`. It used to reuse the Compose-side
// `FocusController.SETTLE_MS` (250ms), which under-settles the ripple in
// proportion to how long the Robolectric sandbox has already been running, so
// this capture rendered a full press when it happened to be early in its shard
// and no press at all behind the whole catalog. `WearFocusedPressPixelTest` pins
// the result — the pressed capture must differ from BOTH the focused and the
// resting one — and it now holds at any shard count.
//
// The function names `ButtonPressed` / `ButtonFocused` and the `@CatalogVariant`
// ids are the join into `catalog.spec.json` — do not rename either.
// ---------------------------------------------------------------------------

@CatalogVariant(
  of = "Button/Filled",
  state = "pressed",
  caption = "Real D-pad press on the focused button → pressed state layer.",
)
@CatalogWearModes
@FocusedPreview(indices = [0], pressed = true)
@Composable
fun ButtonPressed() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_pressed)))
  Button(onClick = onClick) { Text(label) }
}

@CatalogVariant(
  of = "Button/Filled",
  state = "focused",
  caption = "Real focus traversal → focus indicator (rotary / D-pad).",
)
@CatalogWearModes
@FocusedPreview(indices = [0])
@Composable
fun ButtonFocused() = WearSticker {
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_focused)))
  Button(onClick = onClick) { Text(label) }
}

// (`ButtonDisabled`, `SwitchButtonOff`, `CheckboxButtonUnchecked` removed — those states now ride
// their primary function via `@OverrideVariant`, seeding the `enabled` / `checked` knob.)

// --- What this sheet deliberately does NOT carry -----------------------------
//
// This catalog is compose-ai-tools' Wear harness, not an exhaustive Wear Material 3 inventory —
// that is what the wear-m3-catalog project will be. So the sheet is scoped to preview-pipeline
// FEATURES, with one or two carriers each: `@CatalogWearModes`, `perBreakpoint` fan-out,
// `@ScrollingPreview`, `@AnimatedPreview`, `@FocusedPreview` (pressed + focused), the
// `@OverrideVariant` knob fold, `PreviewSlot`, `@ThemeCatalog` + `themeProvider`, and the
// `TlcScalingHost` scaling captures in `CardScalingPreview.kt`.
//
// `Layout/List`, `Template/EdgeButton`, `CheckboxButton/Checked` and `ListHeader` were dropped on
// exactly that test. The by-component redundancy that LOOKS cuttable — the four other button
// emphasis levels, `IconButton`, `CompactButton`, `ButtonGroup`, `AppCard`, `TitleCard`,
// `Card/Outlined`, `Icon`, `Typography`, `ColorScheme` — is load-bearing elsewhere and stays:
// `samples/design-catalog-remote-m3` declares `compareWith: "wear-m3"` and authors a `parallel`
// into each of those ids, so deleting one silently unpairs a row on the published remote-m3
// cross-system compare page. Cut them when remote-m3's `compareWith` moves to the new
// wear-m3-catalog, not before.

// ---------------------------------------------------------------------------
// Parallels of the Remote Compose Material 3 catalog. These mirror the extra
// components the remote-m3 sheet carries (IconButton, CompactButton, ButtonGroup,
// AppCard, Icon, and the theme specimens), so the cross-system compare page pairs
// every remote sticker with a real Wear M3 counterpart rather than a placeholder.
// ---------------------------------------------------------------------------

// A simple five-point star shared by the icon stickers — the catalog doesn't pull
// in material-icons, so it carries one hand-built vector. `Icon` re-tints it, so
// the path fill here is a placeholder.
private val catalogIcon: ImageVector =
  ImageVector.Builder(
      name = "Star",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .apply {
      path(fill = SolidColor(Color.White)) {
        moveTo(12f, 2f)
        lineTo(15.1f, 8.3f)
        lineTo(22f, 9.3f)
        lineTo(17f, 14.1f)
        lineTo(18.2f, 21f)
        lineTo(12f, 17.8f)
        lineTo(5.8f, 21f)
        lineTo(7f, 14.1f)
        lineTo(2f, 9.3f)
        lineTo(8.9f, 8.3f)
        close()
      }
    }
    .build()

@CatalogComponent(id = "IconButton", group = "Buttons", caption = "Round icon button.")
@CatalogWearModes
@Composable
fun IconButtonSticker() = WearSticker {
  // No label to tally into, so the icon button's click toggles a "favourited" reading instead:
  // the star fills with the theme's primary. Untapped — every baked capture — it renders in the
  // stock content colour exactly as before.
  val (favourite, onFavouriteChange) = wearChecked(false)
  IconButton(onClick = { onFavouriteChange(!favourite) }) {
    Icon(
      catalogIcon,
      "Favourite",
      tint = if (favourite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
    )
  }
}

@CatalogComponent(id = "CompactButton", group = "Buttons", caption = "Compact single-line button.")
@CatalogWearModes
@Composable
fun CompactButtonSticker() = WearSticker {
  val (label, onClick) = wearCounted(previewOverrideString("label", "Compact"))
  CompactButton(onClick = onClick, label = { Text(label) })
}

@CatalogComponent(
  id = "ButtonGroup",
  group = "Buttons",
  caption = "Two buttons laid out edge-to-edge.",
)
@CatalogWearModes
@Composable
fun ButtonGroupSticker() = WearSticker {
  // Both members tally independently, so a live session can tell which half it hit.
  val (yes, onYes) = wearCounted("Yes")
  val (no, onNo) = wearCounted("No")
  ButtonGroup {
    Button(onClick = onYes, modifier = Modifier.weight(1f)) { Text(yes) }
    Button(onClick = onNo, modifier = Modifier.weight(1f)) { Text(no) }
  }
}

@CatalogComponent(
  id = "AppCard",
  group = "Containment",
  caption = "Card with app name, icon, title and content slots.",
)
@CatalogWearModes
@Composable
fun AppCardSticker() = WearSticker {
  val (title, onClick) =
    wearCounted(previewOverrideString("title", stringResource(R.string.title_morning_run)))
  AppCard(
    onClick = onClick,
    appName = { Text("App") },
    title = { Text(title) },
    appImage = { Icon(catalogIcon, null, Modifier.size(16.dp)) },
  ) {
    Text("5.2 km · 28 min")
  }
}

@CatalogComponent(id = "Icon", group = "Iconography", caption = "The standalone Icon primitive.")
@CatalogWearModes
@Composable
fun IconSticker() = WearSticker { Icon(catalogIcon, "Star", Modifier.size(48.dp)) }

// Theme specimens — the Wear M3 type ramp and colour-scheme swatches read straight
// from MaterialTheme, parallels of the remote-m3 theme stickers.
@CatalogComponent(
  id = "Typography",
  group = "Theme",
  caption = "A type ramp read from MaterialTheme.typography.",
)
@CatalogWearModes
@Composable
fun TypographySpecimen() = WearSticker {
  Column {
    Text("Body Large", style = MaterialTheme.typography.bodyLarge)
    Text("Label Medium", style = MaterialTheme.typography.labelMedium)
    Text("Label Small", style = MaterialTheme.typography.labelSmall)
  }
}

@CatalogComponent(
  id = "ColorScheme",
  group = "Theme",
  caption = "Colour-scheme swatches read from MaterialTheme.colorScheme.",
)
@CatalogWearModes
@Composable
fun ColorSchemeSpecimen() = WearSticker {
  Row {
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary))
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainer))
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.onBackground))
  }
}
