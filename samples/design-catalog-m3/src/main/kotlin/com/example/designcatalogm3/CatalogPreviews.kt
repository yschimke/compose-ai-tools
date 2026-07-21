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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
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
// Function names are the join key the export driver matches against `catalog.spec.json`'s `preview`
// field (`PreviewDiscovery` keys off the function name), so they must not change.

// --- Buttons — the five M3 emphasis levels, plus a disabled state. ---

@CatalogModes @Composable fun FilledButton() = Sticker("button-filled")

@CatalogModes @Composable fun FilledTonalButtonSticker() = Sticker("button-tonal")

@CatalogModes @Composable fun OutlinedButtonSticker() = Sticker("button-outlined")

@CatalogModes @Composable fun ElevatedButtonSticker() = Sticker("button-elevated")

@CatalogModes @Composable fun TextButtonSticker() = Sticker("button-text")

@CatalogModes @Composable fun FilledButtonDisabled() = Sticker("button-filled-disabled")

// --- Selection controls — checked/selected states (the primary mode to show). ---

@CatalogModes @Composable fun CheckboxChecked() = Sticker("checkbox-checked")

@CatalogModes @Composable fun SwitchOn() = Sticker("switch-on")

@CatalogModes @Composable fun RadioSelected() = Sticker("radiobutton-selected")

@CatalogModes @Composable fun SliderMid() = Sticker("slider")

@CatalogModes @Composable fun FilterChipSelected() = Sticker("chip-filter-selected")

@CatalogModes @Composable fun AssistChipSticker() = Sticker("chip-assist")

// --- Containment — cards and the FAB. ---

@CatalogModes @Composable fun ElevatedCardSticker() = Sticker("card-elevated")

@CatalogModes @Composable fun OutlinedCardSticker() = Sticker("card-outlined")

@CatalogModes @Composable fun FilledCardSticker() = Sticker("card-filled")

// A slotted card: its regions are `PreviewSlot` markers. The plain sticker renders normally (the
// markers are no-ops); `SlottedCardSlots` provides `LocalSlotMode = true` so each marker draws its
// labelled placeholder — the slot map a structured-screen builder fills. Same body, two modes.
@CatalogModes @Composable fun SlottedCardSticker() = Sticker("card-slots")

@CatalogModes
@Composable
fun SlottedCardSlotsSticker() = CatalogSticker {
  CompositionLocalProvider(LocalSlotMode provides true) {
    CatalogComponent("card-slots", interactive = false)
  }
}

@CatalogModes @Composable fun FabSticker() = Sticker("fab")

// --- Communication — progress + badge. ---

@CatalogModes @Composable fun LinearProgressSticker() = Sticker("progress-linear")

@CatalogModes @Composable fun CircularProgressSticker() = Sticker("progress-circular")

@CatalogModes @Composable fun BadgeSticker() = Sticker("badge")

// --- Text fields. ---

@CatalogModes @Composable fun TextFieldSticker() = Sticker("textfield-filled")

@CatalogModes @Composable fun OutlinedTextFieldSticker() = Sticker("textfield-outlined")

// --- Text options — maxLines + ellipsis overflow, generic-family specimens. ---

@CatalogModes @Composable fun TextMaxLinesTruncated() = Sticker("text-maxlines-truncated")

@CatalogModes @Composable fun TextSerifSpecimen() = Sticker("text-serif")

@CatalogModes @Composable fun TextMonospaceSpecimen() = Sticker("text-monospace")

@CatalogModes @Composable fun TextBrandedSpecimen() = Sticker("text-branded")

// --- States — interaction (pressed / focused), disabled, and toggle off↔on. ---

@CatalogModes @Composable fun FilledButtonPressed() = Sticker("button-filled-pressed")

@CatalogModes @Composable fun FilledButtonFocused() = Sticker("button-filled-focused")

@CatalogModes @Composable fun FilledButtonIconLabel() = Sticker("button-filled-icon-label")

@CatalogModes @Composable fun OutlinedButtonDisabled() = Sticker("button-outlined-disabled")

@CatalogModes @Composable fun SwitchOff() = Sticker("switch-off")

@CatalogModes @Composable fun CheckboxUnchecked() = Sticker("checkbox-unchecked")

@CatalogModes @Composable fun FilterChipUnselected() = Sticker("chip-filter-unselected")

@CatalogModes @Composable fun RadioUnselected() = Sticker("radiobutton-unselected")

@CatalogModes @Composable fun SegmentedToggle() = Sticker("segmentedbutton")

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
//   * **direction** forces `LocalLayoutDirection = Rtl` directly.
//   * **fontScale** provides a `LocalDensity` with `fontScale = 2f` (large-text / dynamic-type).

// Button — filled.
@Preview(name = "Light", locale = "ar-XB", group = "modes")
@Preview(name = "Dark", locale = "ar-XB", uiMode = 32, group = "modes")
@Composable
fun FilledButtonPseudo() = Sticker("button-filled")

@CatalogModes
@Composable
fun FilledButtonRtl() =
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Sticker("button-filled")
  }

@CatalogModes
@Composable
fun FilledButtonLargeFont() =
  CompositionLocalProvider(
    LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 2f)
  ) {
    Sticker("button-filled")
  }

// List row — the on switch (a settings-style selection row).
@Preview(name = "Light", locale = "ar-XB", group = "modes")
@Preview(name = "Dark", locale = "ar-XB", uiMode = 32, group = "modes")
@Composable
fun SwitchOnPseudo() = Sticker("switch-on")

@CatalogModes
@Composable
fun SwitchOnRtl() =
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Sticker("switch-on")
  }

@CatalogModes
@Composable
fun SwitchOnLargeFont() =
  CompositionLocalProvider(
    LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 2f)
  ) {
    Sticker("switch-on")
  }

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
