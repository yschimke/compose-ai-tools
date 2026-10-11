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
import ee.schimke.composeai.preview.slots.PreviewSlotConstraints
import ee.schimke.composeai.preview.slots.PreviewSlotScope
import ee.schimke.composeai.preview.slots.PreviewSlotSizing

// Buttons — the Wear M3 emphasis levels plus the screen-hugging EdgeButton.

// `disabled` rides this function via `@OverrideVariant` (seeding `enabled`), folding under this
// sticker as a `_VARIANT_disabled` capture. `pressed` / `focused` are separate functions below,
// since `@FocusedPreview` is a per-function capture annotation driven by real focus and input.
// Clicks are made visible by [wearCounted] tallying into the label; the disabled variant stays
// inert.
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

// A workout history long enough that, scrolled to the end, it fills the space above the edge
// button.
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

// EdgeButton's curved shape is its placement in `ScreenScaffold(edgeButton = …)`, so it is a
// full-screen component with a real ScreenScaffold + scaling TransformingLazyColumn, captured at
// every breakpoint. ScreenScaffold only reveals the button once the list settles at the bottom, so
// the sticker uses @ScrollingPreview(END) to capture the settled, revealed frame.
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

// Lists — the Wear M3 scaling TransformingLazyColumn: items scale and fade toward the curved edges.

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

// Scaffold templates — full-screen screen skeletons, captured at every breakpoint. The TimeText
// strip comes from [WearScaffoldTemplate] and is frozen at "10:10" so the bundle doesn't churn.

private val templateListItems =
  listOf(
    R.string.title_morning_run to "5.2 km · 28 min",
    R.string.activity_heart_rate to "72 bpm",
    R.string.activity_sleep to "7h 14m",
    R.string.activity_steps to "6,482",
  )

// The canonical Wear list screen: TimeText, a ListHeader and a scaling column of TitleCards.
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
          // The slot wraps the header's content, not the `ListHeader`: wrapping the surface puts a
          // `Box` between the list item and the composable carrying `SurfaceTransformation`, and
          // the header stops filling the item width. `Lazy` is explicit because a
          // `TransformingLazyColumn` item body is not `LazyItemScope`.
          PreviewSlot(
            name = "header",
            scope = PreviewSlotScope.Lazy,
            constraints =
              PreviewSlotConstraints(
                horizontal = PreviewSlotSizing.Hug,
                vertical = PreviewSlotSizing.Hug,
              ),
          ) {
            Text(previewOverrideString("header", stringResource(R.string.header_activity)))
          }
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

// A horizontal pager with HorizontalPageIndicator on the bottom curve, seeded on the middle page.
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

// Selection controls.

// The off state rides this function via `@OverrideVariant` (seeding `checked = false`).
//
// The interaction capture rides it too: `targets = [0, 0]` taps off and back on. This is also the
// regression net for `@InteractionPreview` on the Robolectric backend — if the backend stops
// honouring the script, the `.apng` goes missing and the missing-renders gate fails.
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

// Containment.

// Card content regions are wrapped in `PreviewSlot(name)` markers: a no-op in a normal render, a
// labelled placeholder under `LocalSlotMode`. Each slot is `fillMaxWidth` so its `dp-slot:*` bounds
// are the card's full content width.
@CatalogComponent(id = "Card", group = "Containment")
@CatalogWearModes
@Composable
fun CardSticker() = WearSticker {
  // A Wear card's `onClick` is required, so it gets the same click tally as the buttons.
  val (label, onClick) =
    wearCounted(previewOverrideString("label", stringResource(R.string.label_card)))
  Card(onClick = onClick) { PreviewSlot("content", Modifier.fillMaxWidth()) { Text(label) } }
}

// The outlined card variant; only the outlined-vs-filled treatment differs from `Card`.
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

// Communication.

@CatalogComponent(id = "Progress/Circular", group = "Communication")
@CatalogWearModes
@Composable
fun CircularProgressSticker() =
  // Determinate at a fixed 66% so the static capture is deterministic.
  WearSticker { CircularProgressIndicator(progress = { 0.66f }, modifier = Modifier.size(72.dp)) }

// The indeterminate counterpart to [CircularProgressSticker]. It animates in the live interactive
// stream; a static capture is deterministic because the renderer parks infinite animations at a
// fixed advance.
@CatalogComponent(
  id = "Progress/Circular/Indeterminate",
  group = "Communication",
  caption =
    "Indeterminate (animated) progress ring — the no-progress overload sweeps continuously; " +
      "animates in the live preview.",
  // Claims [IndeterminateCircularProgressGif] for this component's Motion lane (a GIF needs one
  // pinned canvas, so the annotation can't sit on this fanned-out function). A recording no
  // component claims is silently dropped from the catalog, so this is also the end-to-end net for
  // `motionPreview`.
  motionPreview = "IndeterminateCircularProgressGif",
)
@CatalogWearModes
@Composable
fun IndeterminateCircularProgressSticker() = WearSticker {
  CircularProgressIndicator(modifier = Modifier.size(72.dp))
}

// The same ring as an animated GIF. `@AnimatedPreview` drives the paused clock and encodes
// `renders/<id>.gif`; `showCurves = false` omits the curve panel, and the duration auto-detects
// from the `InfiniteTransition` so the loop is seamless. Not a catalog component itself: it is
// published under `motion/` because [IndeterminateCircularProgressSticker] claims it.
@Preview(showBackground = false)
@AnimatedPreview(showCurves = false)
@Composable
fun IndeterminateCircularProgressGif() = WearSticker {
  CircularProgressIndicator(modifier = Modifier.size(72.dp))
}

// Text options — exercises maxLines / overflow on a round screen.

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

// States — pressed and focused (focus matters on Wear for rotary / D-pad).
//
// Both are driven by real input via `@FocusedPreview`, not a forged interaction on a
// `MutableInteractionSource` (which paints a state layer without the focus system owning the node).
// On Robolectric it runs a real `FocusManager.moveFocus` traversal in Keyboard input mode. `indices
// = [0]` is the single Button; a single capture keeps the plain `renders/<id>.png` name.
//
// `pressed = true` takes the path where the renderer settles the platform `RippleDrawable` (Wear
// M3's only press affordance); a hand-seeded press never reaches the PNG.
// `WearFocusedPressPixelTest` pins that pressed differs from both focused and resting.
//
// The function names and `@CatalogVariant` ids are the join into `catalog.spec.json` — don't
// rename.

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

// This catalog is the Wear harness for preview-pipeline features, not an exhaustive Wear M3
// inventory (that's wear-m3-catalog): `@CatalogWearModes`, `perBreakpoint` fan-out,
// `@ScrollingPreview`, `@AnimatedPreview`, `@FocusedPreview`, the `@OverrideVariant` fold,
// `PreviewSlot`, `@ThemeCatalog` + `themeProvider`, and the scaling captures in
// `CardScalingPreview.kt`. The remote-m3 parallels below are no longer depended on elsewhere and
// could be cut (confirm against a published `matches.html` first; see DESIGN_CATALOGS.md).

// Parallels of the Remote Compose Material 3 catalog (IconButton, CompactButton, ButtonGroup,
// AppCard, Icon and the theme specimens).

// A hand-built star so the catalog doesn't need material-icons; `Icon` re-tints it.
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
  // No label to tally into, so a click toggles the star to the primary colour instead.
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

// Theme specimens — the Wear M3 type ramp and colour-scheme swatches from MaterialTheme.
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
