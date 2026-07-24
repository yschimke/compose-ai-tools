@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.example.designcatalogm3.shared.CatalogComponent
import com.example.designcatalogm3.shared.generated.resources.Res
import com.example.designcatalogm3.shared.generated.resources.msg_deploy
import com.example.designcatalogm3.shared.generated.resources.msg_diff
import com.example.designcatalogm3.shared.generated.resources.msg_lunch
import com.example.designcatalogm3.shared.generated.resources.msg_merged
import com.example.designcatalogm3.shared.generated.resources.msg_specs
import com.example.designcatalogm3.shared.generated.resources.template_title
import ee.schimke.composeai.overrides.previewOverrideString
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogVariant
import ee.schimke.composeai.preview.OverrideVariant
import ee.schimke.composeai.preview.slots.LocalSlotMode
import org.jetbrains.compose.resources.stringResource

// The M3 catalog sticker sheet: one `@Preview` per component, in light + dark (`@CatalogModes`).
// Each is a thin wrapper — `CatalogSticker { CatalogComponent("<slug>", interactive = …) }` — over
// the shared component set in `:samples:design-catalog-m3-shared`, so the bodies live in one place
// (also mounted live by the in-browser wasm tier).
//
// `interactive` is derived from `LocalInspectionMode` rather than hard-coded, so the SAME
// `@Preview`
// serves both lanes correctly (the two share this sticker sheet — see [Sticker]):
//   * baked snapshot / one-shot `/render` (`LocalInspectionMode = true`) → `interactive = false`,
//     the deterministic frame (static toggles / determinate progress) the published catalog shows —
//     pixel-unchanged.
//   * held **Live Compose** daemon session (`LocalInspectionMode = false`) → `interactive = true`,
//     so its click dispatch actually toggles the segmented button / switch / chip and drives the
//     stateful widgets — matching what the in-browser wasm tier already does. Hard-coding `false`
//     left every live-lane click a no-op (the segmented toggle wouldn't flip).
//
// The catalog identity — component id, group, caption, and per-variant tags — lives on each preview
// via `@CatalogComponent` / `@CatalogVariant` (compose-ai-tools' catalog-annotations), so it sits
// next to the composable instead of being restated in `catalog.spec.json`. The design-artifacts
// export builds the inventory from these annotations; `catalog.spec.json` now carries only the
// cover-sheet fields (system / title / breakpoints / referenceKits). A `@CatalogVariant.of` names
// its parent by that parent's `@CatalogComponent.id`, so those ids are the join and must stay
// stable.

// --- Buttons — the five M3 emphasis levels, plus a disabled state. ---

@CatalogComponent(
  id = "Button/Filled",
  group = "Buttons",
  caption = "Highest emphasis; the primary action.",
)
@CatalogModes
@Composable
fun FilledButton() = Sticker("button-filled")

@CatalogComponent(id = "Button/Tonal", group = "Buttons", caption = "Secondary, still prominent.")
@CatalogModes
@Composable
fun FilledTonalButtonSticker() = Sticker("button-tonal")

@CatalogComponent(
  id = "Button/Outlined",
  group = "Buttons",
  caption = "Medium emphasis on a busy surface.",
)
@CatalogModes
@Composable
fun OutlinedButtonSticker() = Sticker("button-outlined")

@CatalogComponent(
  id = "Button/Elevated",
  group = "Buttons",
  caption = "Outlined alternative needing separation.",
)
@CatalogModes
@Composable
fun ElevatedButtonSticker() = Sticker("button-elevated")

@CatalogComponent(
  id = "Button/Text",
  group = "Buttons",
  caption = "Lowest emphasis; inline actions.",
)
@CatalogModes
@Composable
fun TextButtonSticker() = Sticker("button-text")

// `FilledButtonDisabled` (a `Button/Filled` variant) is declared in the States section below,
// between the pressed/focused and content variants, so the annotation-derived variant order matches
// the sheet's intended order (pressed → keyboard-focus → disabled → content axes).

// --- Selection controls — checked/selected states (the primary mode to show). ---

// Selection controls carry their unchecked/unselected/off state as an `@OverrideVariant` (seeding
// the shared `checked` / `selected` knob) rather than a duplicated `*Unchecked` / `*Off` /
// `*Unselected`
// wrapper — the render emits a `_VARIANT_<state>` capture that folds under the primary sticker.
@CatalogComponent(
  id = "Checkbox/Checked",
  group = "Selection",
  caption = "Checked; the unchecked state folds in as an @OverrideVariant (checked = false).",
)
@CatalogModes
@OverrideVariant(name = "unchecked", booleans = ["checked=false"])
@Composable
fun CheckboxChecked() = Sticker("checkbox-checked")

@CatalogComponent(
  id = "Switch/On",
  group = "Selection",
  caption = "On; the off state folds in as an @OverrideVariant (checked = false).",
)
@CatalogModes
@OverrideVariant(name = "off", booleans = ["checked=false"])
@Composable
fun SwitchOn() = Sticker("switch-on")

@CatalogComponent(
  id = "RadioButton/Selected",
  group = "Selection",
  caption = "Selected; the unselected state folds in as an @OverrideVariant (selected = false).",
)
@CatalogModes
@OverrideVariant(name = "unselected", booleans = ["selected=false"])
@Composable
fun RadioSelected() = Sticker("radiobutton-selected")

@CatalogComponent(id = "Slider", group = "Selection")
@CatalogModes
@Composable
fun SliderMid() = Sticker("slider")

@CatalogComponent(
  id = "Chip/Filter-Selected",
  group = "Selection",
  caption = "Selected; the unselected state folds in as an @OverrideVariant (selected = false).",
)
@CatalogModes
@OverrideVariant(name = "unselected", booleans = ["selected=false"])
@Composable
fun FilterChipSelected() = Sticker("chip-filter-selected")

@CatalogComponent(id = "Chip/Assist", group = "Selection")
@CatalogModes
@Composable
fun AssistChipSticker() = Sticker("chip-assist")

// --- Containment — cards and the FAB. ---

@CatalogComponent(id = "Card/Elevated", group = "Containment")
@CatalogModes
@Composable
fun ElevatedCardSticker() = Sticker("card-elevated")

@CatalogComponent(id = "Card/Outlined", group = "Containment")
@CatalogModes
@Composable
fun OutlinedCardSticker() = Sticker("card-outlined")

@CatalogComponent(id = "Card/Filled", group = "Containment")
@CatalogModes
@Composable
fun FilledCardSticker() = Sticker("card-filled")

// A slotted card: its regions are `PreviewSlot` markers. The plain sticker renders normally (the
// markers are no-ops); `SlottedCardSlots` provides `LocalSlotMode = true` so each marker draws its
// labelled placeholder — the slot map a structured-screen builder fills. Same body, two modes.
@CatalogComponent(
  id = "Card/Slots",
  group = "Containment",
  caption = "A card with named PreviewSlot regions a structured-screen builder fills.",
)
@CatalogModes
@Composable
fun SlottedCardSticker() = Sticker("card-slots")

@CatalogVariant(
  of = "Card/Slots",
  state = "slot-mode",
  caption = "Slot mode: each PreviewSlot draws its labelled placeholder.",
)
@CatalogModes
@Composable
fun SlottedCardSlotsSticker() = CatalogSticker {
  CompositionLocalProvider(LocalSlotMode provides true) {
    CatalogComponent("card-slots", interactive = false)
  }
}

@CatalogComponent(id = "FAB", group = "Containment")
@CatalogModes
@Composable
fun FabSticker() = Sticker("fab")

// --- Communication — progress + badge. ---

@CatalogComponent(id = "Progress/Linear", group = "Communication")
@CatalogModes
@Composable
fun LinearProgressSticker() = Sticker("progress-linear")

@CatalogComponent(id = "Progress/Circular", group = "Communication")
@CatalogModes
@Composable
fun CircularProgressSticker() = Sticker("progress-circular")

@CatalogComponent(id = "Badge", group = "Communication")
@CatalogModes
@Composable
fun BadgeSticker() = Sticker("badge")

// --- Text fields. ---

@CatalogComponent(id = "TextField/Filled", group = "Text fields")
@CatalogModes
@Composable
fun TextFieldSticker() = Sticker("textfield-filled")

@CatalogComponent(id = "TextField/Outlined", group = "Text fields")
@CatalogModes
@Composable
fun OutlinedTextFieldSticker() = Sticker("textfield-outlined")

// --- Text options — maxLines + ellipsis overflow, generic-family specimens. ---

@CatalogComponent(
  id = "Text/MaxLines-Truncated",
  group = "Text options",
  caption = "maxLines=2 + ellipsis overflow — exercises the textOverflow product.",
)
@CatalogModes
@Composable
fun TextMaxLinesTruncated() = Sticker("text-maxlines-truncated")

@CatalogComponent(
  id = "Text/Serif",
  group = "Text options",
  caption = "Generic serif family (Noto Serif) — pins the Wasm tier’s font interception.",
)
@CatalogModes
@Composable
fun TextSerifSpecimen() = Sticker("text-serif")

@CatalogComponent(
  id = "Text/Monospace",
  group = "Text options",
  caption = "Generic monospace family (Droid Sans Mono) — pins the Wasm tier’s font interception.",
)
@CatalogModes
@Composable
fun TextMonospaceSpecimen() = Sticker("text-monospace")

// `TextBrandedSpecimen` renders but is deliberately NOT in the published catalog inventory (it has
// no `@CatalogComponent`), matching the pre-annotation spec, which omitted it.
@CatalogModes @Composable fun TextBrandedSpecimen() = Sticker("text-branded")

// --- States — interaction (pressed / focused), disabled, and toggle off↔on. ---

@CatalogVariant(
  of = "Button/Filled",
  state = "pressed",
  caption = "Held PressInteraction → pressed state layer.",
)
@CatalogModes
@Composable
fun FilledButtonPressed() = Sticker("button-filled-pressed")

@CatalogVariant(
  of = "Button/Filled",
  state = "keyboard-focus",
  caption =
    "Keyboard focus (focus-visible) → M3 inset focus ring. This is the directional/keyboard " +
      "focus indicator, not the pointer/hover state layer.",
)
@CatalogModes
@Composable
fun FilledButtonFocused() = Sticker("button-filled-focused")

@CatalogVariant(of = "Button/Filled", state = "disabled", caption = "Disabled state.")
@CatalogModes
@Composable
fun FilledButtonDisabled() = Sticker("button-filled-disabled")

@CatalogVariant(
  of = "Button/Filled",
  props = ["content=icon+label"],
  caption = "Content axis (not a state): leading icon + label, vs the label-only default.",
)
@CatalogModes
@Composable
fun FilledButtonIconLabel() = Sticker("button-filled-icon-label")

@CatalogVariant(of = "Button/Outlined", state = "disabled", caption = "Disabled state.")
@CatalogModes
@Composable
fun OutlinedButtonDisabled() = Sticker("button-outlined-disabled")

// (`SwitchOff`, `CheckboxUnchecked`, `FilterChipUnselected`, `RadioUnselected` removed — those
// states now ride their primary selection control via `@OverrideVariant`, seeding the shared
// `checked` / `selected` knob.)

@CatalogComponent(
  id = "SegmentedButton",
  group = "Selection",
  caption = "Single-choice toggle: selected + unselected segments.",
)
@CatalogModes
@Composable
fun SegmentedToggle() = Sticker("segmentedbutton")

// --- Internationalisation / accessibility axes ---
//
// The same two representative components — the filled button and the on switch — rendered under the
// i18n/a11y dimensions, declared as named `props` variants (`locale` / `direction` / `fontScale`)
// on their parent sticker in `catalog.spec.json`, mirroring the `content: icon+label` content-axis
// variant. Pure Compose, no renderer change:
//   * **pseudolocale** reuses the repo's existing pseudolocale-in-previews mechanism —
//     `@Preview(locale = "ar-XB")`, the `Pseudolocale.BIDI` tag the desktop renderer recognises and
//     flips to RTL (see `:samples:cmp`'s `CmpPseudoBidi`). Desktop CMP pseudolocalises layout
//     direction, not text (`org.jetbrains.compose.resources` doesn't go through
//     `LocalContext.resources`), so `en-XA` accent-expansion isn't visible here; the `ar-XB` bidi
//     pseudolocale is, so it's the one that carries visible evidence.
//   * **direction** forces `LocalLayoutDirection = Rtl` directly (layout direction is a composition
//     property the renderer captures, so an override is faithful in both the PNG and the SVG
// export).
//   * **fontScale** is set on the `@Preview` itself (`fontScale = 2f`), not via a `LocalDensity`
//     override: the design-artifacts SVG export reads `fontScale` from the render spec (the preview
//     params), so driving it from the annotation keeps the PNG and the exported SVG/text metadata
// in
//     lockstep at 2.0 (large-text / dynamic-type).

// Button — filled.
@CatalogVariant(
  of = "Button/Filled",
  props = ["locale=ar-XB"],
  caption =
    "i18n axis: the ar-XB bidi pseudolocale — flips the button to RTL layout so mirroring bugs " +
      "surface (desktop CMP pseudolocalises layout direction, not text).",
)
@Preview(name = "Light", locale = "ar-XB", group = "modes")
@Preview(name = "Dark", locale = "ar-XB", uiMode = 32, group = "modes")
@Composable
fun FilledButtonPseudo() = Sticker("button-filled")

@CatalogVariant(
  of = "Button/Filled",
  props = ["direction=rtl"],
  caption = "i18n axis: forced RTL layout direction (LocalLayoutDirection = Rtl).",
)
@CatalogModes
@Composable
fun FilledButtonRtl() =
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Sticker("button-filled")
  }

@CatalogVariant(
  of = "Button/Filled",
  props = ["fontScale=2.0"],
  caption =
    "Accessibility axis: 2× font scale (LocalDensity fontScale = 2.0) — large-text / dynamic-type " +
      "stress.",
)
@Preview(name = "Light", fontScale = 2f, group = "modes")
@Preview(name = "Dark", fontScale = 2f, uiMode = 32, group = "modes")
@Composable
fun FilledButtonLargeFont() = Sticker("button-filled")

// List row — the on switch (a settings-style selection row).
@CatalogVariant(
  of = "Switch/On",
  props = ["locale=ar-XB"],
  caption =
    "i18n axis: the ar-XB bidi pseudolocale — flips the row to RTL layout (desktop CMP " +
      "pseudolocalises layout direction, not text).",
)
@Preview(name = "Light", locale = "ar-XB", group = "modes")
@Preview(name = "Dark", locale = "ar-XB", uiMode = 32, group = "modes")
@Composable
fun SwitchOnPseudo() = Sticker("switch-on")

@CatalogVariant(
  of = "Switch/On",
  props = ["direction=rtl"],
  caption = "i18n axis: forced RTL layout direction (LocalLayoutDirection = Rtl).",
)
@CatalogModes
@Composable
fun SwitchOnRtl() =
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Sticker("switch-on")
  }

@CatalogVariant(
  of = "Switch/On",
  props = ["fontScale=2.0"],
  caption =
    "Accessibility axis: 2× font scale (LocalDensity fontScale = 2.0) — large-text / dynamic-type " +
      "stress.",
)
@Preview(name = "Light", fontScale = 2f, group = "modes")
@Preview(name = "Dark", fontScale = 2f, uiMode = 32, group = "modes")
@Composable
fun SwitchOnLargeFont() = Sticker("switch-on")

/**
 * Every sticker is the shared component (deterministic frame) inside the catalog theme wrapper. All
 * stickers render on a transparent surface — the interactive viewers (preview server, catalog
 * index) paint their own backing behind the PNG, so the sticker is a component silhouette rather
 * than carrying a baked-in surface of its own.
 */
@Composable
// A clean one-liner: the theme (and the font / palette override) lives entirely in
// [CatalogSticker], so a preview never spells the typeface or knows an override exists.
//
// `interactive = !LocalInspectionMode.current`, so a single sticker serves both render lanes:
//   * one-shot / baked render — `LocalInspectionMode = true` (Compose's preview signal, and what
//     the daemon's one-shot `/render` lane sets) → `interactive = false` → a deterministic static
//     frame, pixel-unchanged from before.
//   * held Live Compose daemon session — `DesktopHost.acquireInteractiveSession` seeds
//     `inspectionMode = false` → `interactive = true` → live, stateful widgets whose click dispatch
//     actually mutates state (the segmented toggle flips, the switch/chip toggle).
// This is the one lever on which the baked and live lanes diverge, exactly as `CatalogComponent`
// documents.
private fun Sticker(id: String) = CatalogSticker {
  CatalogComponent(id, interactive = !LocalInspectionMode.current)
}

// ---------------------------------------------------------------------------
// Scaffold templates — full-screen, pre-built screen skeletons an app copies
// whole. Rendered on a phone with `showSystemUi = true` (see [CatalogTemplate])
// so the capture reads as a real screenshot: the OS status bar at the top and
// the gesture-pill nav bar at the bottom, drawn by the renderer's
// SystemBarsFrame, framing the template's own Material chrome.
// ---------------------------------------------------------------------------

// Sender names stay literal (proper nouns aren't translated); each preview line is a string
// resource so a `localeTag` override renders the message copy in the target language.
private val templateMessages =
  listOf(
    "Alex Kim" to Res.string.msg_lunch,
    "Design team" to Res.string.msg_specs,
    "Priya Patel" to Res.string.msg_diff,
    "Sam Rivera" to Res.string.msg_merged,
    "On-call" to Res.string.msg_deploy,
  )

/**
 * Full-screen app scaffold: an edge-to-edge TopAppBar, a scrolling list of ListItems, and a
 * FloatingActionButton — the canonical M3 screen an app starts a new surface from. The render
 * environment has no real window insets behind the renderer's synthetic OS bars, so the scaffold
 * supplies them itself ([SYSTEM_BAR_INSET]): the app bar paints under the status bar with its title
 * below the OS clock, and the content/FAB clear the gesture pill.
 */
@CatalogComponent(
  id = "Template/AppScaffold",
  group = "Scaffold templates",
  caption =
    "Full-screen layout with the OS status bar — TopAppBar, a list, and a FAB, captured with " +
      "showSystemUi on a phone.",
)
@CatalogTemplate
@Composable
fun AppScaffoldTemplate() = FullScreenM3 {
  Scaffold(
    contentWindowInsets = WindowInsets(bottom = SYSTEM_BAR_INSET),
    topBar = {
      TopAppBar(
        title = { Text(previewOverrideString("title", stringResource(Res.string.template_title))) },
        windowInsets = WindowInsets(top = SYSTEM_BAR_INSET),
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = {}) { Text(previewOverrideString("fab", "+")) }
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      templateMessages.forEachIndexed { index, (sender, previewRes) ->
        // Each row's sender + preview are indexed override knobs (`sender[i]` / `preview[i]`), so a
        // daemon-backed render can reseed any individual row from the `compose/overrides` surface.
        // The preview copy's author default is a string resource so a `localeTag` override
        // translates it; the sender name stays a literal proper noun.
        ListItem(
          headlineContent = { Text(previewOverrideString("sender", sender, index = index)) },
          supportingContent = {
            Text(previewOverrideString("preview", stringResource(previewRes), index = index))
          },
        )
        if (index < templateMessages.lastIndex) HorizontalDivider()
      }
    }
  }
}
