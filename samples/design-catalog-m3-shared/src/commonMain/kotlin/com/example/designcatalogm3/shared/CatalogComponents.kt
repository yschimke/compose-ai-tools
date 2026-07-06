@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.slots.PreviewSlot

/**
 * The **authoritative** Compose Material 3 catalog component set, shared by the desktop `@Preview`
 * sticker sheet (`:samples:design-catalog-m3`, the render source of truth) and the in-browser wasm
 * app (`:samples:cmp-wasm-catalog`). Written against the multiplatform `material3` artifact — which
 * uses the same `androidx.compose.material3.*` package names as the Android one, so the bodies are
 * identical to the Android catalog they replace.
 *
 * **Ids are the catalog's slugged `componentId`** (`slug()` in `scripts/design-artifacts`:
 * lowercase, non-alphanumeric runs → `-`), 1:1 with `samples/design-catalog-m3/catalog.spec.json`,
 * so `/wasm/compose-m3/?id=<slug>` and the desktop preview functions resolve the same component.
 *
 * [interactive] is the **only** axis on which the two surfaces diverge:
 * * `false` (the desktop sticker sheet) reproduces the deterministic baked frame the published
 *   catalog has always shown — a static toggle/slider/progress value, so the render is stable.
 * * `true` (the in-browser tier) uses live, stateful widgets so a visitor can actually toggle a
 *   switch, drag a slider, and watch the indeterminate progress animate.
 *
 * The pressed/focused button states seed a held interaction on **both** surfaces — the resting
 * state-layer is the design contract for that state, not an animation.
 *
 * **Editable knobs.** Each component's author-facing values — labels, the entered text-field value,
 * selection/toggle flags, slider & progress values, the badge count, the slotted card's accent —
 * are declared through the `catalogOverride*` wrappers, the catalog's bridge to the opt-in
 * `previewOverride*` surface (see [catalogOverrideString]). Every knob returns its author default
 * when nothing is seeded, so the baked sticker sheet is pixel-unchanged; a daemon-backed render can
 * seed replacements and the `compose/overrides` producer can enumerate what's editable per sticker.
 *
 * **Fillable slots.** The content region of each card is wrapped in a `PreviewSlot(name)` marker
 * (the Figma slot placeholder added for the structured-screen builder): a no-op in a normal render,
 * it swaps to a labelled placeholder under `LocalSlotMode` so a designer sees exactly where a child
 * drops in.
 */
@Composable
fun CatalogComponent(id: String, interactive: Boolean) {
  when (id) {
    // Buttons — the five M3 emphasis levels, plus disabled. The label of each is an editable
    // `catalogOverrideString("label", …)` knob, so a daemon-backed render can retitle the button
    // from the `compose/overrides` surface; with no seed the author default renders unchanged.
    "button-filled" -> Button(onClick = {}) { Text(catalogOverrideString("label", "Filled")) }
    "button-tonal" ->
      FilledTonalButton(onClick = {}) { Text(catalogOverrideString("label", "Tonal")) }
    "button-outlined" ->
      OutlinedButton(onClick = {}) { Text(catalogOverrideString("label", "Outlined")) }
    "button-elevated" ->
      ElevatedButton(onClick = {}) { Text(catalogOverrideString("label", "Elevated")) }
    "button-text" -> TextButton(onClick = {}) { Text(catalogOverrideString("label", "Text")) }
    "button-filled-disabled" ->
      Button(onClick = {}, enabled = false) { Text(catalogOverrideString("label", "Disabled")) }

    // Selection controls — primary (checked/selected) state. The checked/selected flag is a
    // `catalogOverrideBoolean` knob so a render can flip the state; it also seeds the interactive
    // widget's initial value.
    "checkbox-checked" -> {
      val checked = catalogOverrideBoolean("checked", true)
      if (interactive) StatefulCheckbox(checked) else Checkbox(checked, {})
    }
    "switch-on" -> {
      val on = catalogOverrideBoolean("checked", true)
      if (interactive) StatefulSwitch(on) else Switch(checked = on, onCheckedChange = {})
    }
    "radiobutton-selected" ->
      RadioButton(selected = catalogOverrideBoolean("selected", true), onClick = {})
    "slider" ->
      Box(Modifier.width(220.dp)) {
        val value = catalogOverrideFloat("value", 0.5f)
        if (interactive) StatefulSlider() else Slider(value = value, onValueChange = {})
      }
    "chip-filter-selected" -> {
      val selected = catalogOverrideBoolean("selected", true)
      val label = catalogOverrideString("label", "Filter")
      if (interactive) StatefulFilterChip(selected, label)
      else FilterChip(selected = selected, onClick = {}, label = { Text(label) })
    }
    "chip-assist" ->
      AssistChip(onClick = {}, label = { Text(catalogOverrideString("label", "Assist")) })

    // Containment — cards and the FAB. Each card's body is wrapped in a `PreviewSlot("content")`
    // filling the fixed 160×80 box: a no-op in a normal render (draws the — now editable — label,
    // tagged `dp-slot:content`), it swaps to a labelled placeholder under slot mode so a
    // structured-screen builder can drop a child into that exact box.
    "card-elevated" ->
      ElevatedCard {
        Box(Modifier.size(160.dp, 80.dp)) {
          PreviewSlot("content", Modifier.fillMaxSize()) {
            Text(catalogOverrideString("label", "Elevated card"))
          }
        }
      }
    "card-outlined" ->
      OutlinedCard {
        Box(Modifier.size(160.dp, 80.dp)) {
          PreviewSlot("content", Modifier.fillMaxSize()) {
            Text(catalogOverrideString("label", "Outlined card"))
          }
        }
      }
    "card-filled" ->
      Card {
        Box(Modifier.size(160.dp, 80.dp)) {
          PreviewSlot("content", Modifier.fillMaxSize()) {
            Text(catalogOverrideString("label", "Filled card"))
          }
        }
      }
    // A **slotted** card: each region is wrapped in `PreviewSlot(name) { … }`, a no-op in a normal
    // render (draws the content, tagged `dp-slot:<name>`) that swaps to a labelled placeholder
    // under
    // slot mode. Each slot carries an explicit size, so the box a child fills — and the placeholder
    // shown under slot mode — is well-defined. The structured-screen builder reads these slots from
    // `/render/card-slots.slots` and fills each by rendering another component to that size.
    "card-slots" ->
      ElevatedCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          PreviewSlot("leadingIcon", Modifier.size(40.dp)) {
            Box(
              Modifier.size(40.dp).background(catalogOverrideColor("iconColor", Color(0xFF6750A4)))
            )
          }
          Column(Modifier.padding(start = 12.dp)) {
            PreviewSlot("headline", Modifier.size(140.dp, 20.dp)) {
              Text(catalogOverrideString("headline", "Headline"))
            }
            PreviewSlot("supporting", Modifier.size(140.dp, 16.dp)) {
              Text(catalogOverrideString("supporting", "Supporting text"))
            }
          }
        }
      }
    "fab" -> FloatingActionButton(onClick = {}) { Text(catalogOverrideString("label", "+")) }

    // Communication — progress + badge. The baked sticker keeps the deterministic `0.6` frame; the
    // in-browser tier runs the indeterminate (animated) variant so it's visibly live.
    "progress-linear" ->
      Box(Modifier.width(220.dp)) {
        val progress = catalogOverrideFloat("progress", 0.6f)
        if (interactive) LinearProgressIndicator()
        else LinearProgressIndicator(progress = { progress })
      }
    "progress-circular" -> {
      val progress = catalogOverrideFloat("progress", 0.6f)
      if (interactive) CircularProgressIndicator()
      else CircularProgressIndicator(progress = { progress })
    }
    "badge" -> Badge { Text(catalogOverrideInt("count", 8).toString()) }

    // Text fields — both the entered value and the floating label are editable knobs.
    "textfield-filled" ->
      TextField(
        value = catalogOverrideString("value", "Filled"),
        onValueChange = {},
        label = { Text(catalogOverrideString("label", "Label")) },
      )
    "textfield-outlined" ->
      OutlinedTextField(
        value = catalogOverrideString("value", "Outlined"),
        onValueChange = {},
        label = { Text(catalogOverrideString("label", "Label")) },
      )

    // States — interaction (pressed / focused), disabled, and toggle off↔on.
    "button-filled-pressed" ->
      Button(onClick = {}, interactionSource = pressedSource()) {
        Text(catalogOverrideString("label", "Pressed"))
      }
    "button-filled-focused" ->
      Button(onClick = {}, interactionSource = focusedSource()) {
        Text(catalogOverrideString("label", "Focused"))
      }
    // Content axis (not a state): the same Filled button with a leading icon + label, so the
    // catalog shows the icon-and-text configuration alongside the label-only default. The icon is
    // an inline `ImageVector` (a plus glyph) — this module deliberately carries no icon library,
    // and
    // `Icon` tints it with the button's content color regardless of the vector's own fill.
    "button-filled-icon-label" ->
      Button(onClick = {}) {
        Icon(addGlyph, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(catalogOverrideString("label", "Filled"))
      }
    "button-outlined-disabled" ->
      OutlinedButton(onClick = {}, enabled = false) {
        Text(catalogOverrideString("label", "Disabled"))
      }
    "switch-off" -> {
      val on = catalogOverrideBoolean("checked", false)
      if (interactive) StatefulSwitch(on) else Switch(checked = on, onCheckedChange = {})
    }
    "checkbox-unchecked" -> {
      val checked = catalogOverrideBoolean("checked", false)
      if (interactive) StatefulCheckbox(checked) else Checkbox(checked, {})
    }
    "chip-filter-unselected" -> {
      val selected = catalogOverrideBoolean("selected", false)
      val label = catalogOverrideString("label", "Filter")
      if (interactive) StatefulFilterChip(selected, label)
      else FilterChip(selected = selected, onClick = {}, label = { Text(label) })
    }
    "segmentedbutton" -> SegmentedToggle(interactive)

    // Text options — maxLines + ellipsis overflow. The 128dp box reproduces the wrap/truncation
    // point the Android sticker got from its 160dp preview canvas minus the sticker's 16dp padding
    // (160 − 2·16 = 128), so the baked frame is unchanged and both surfaces share one body.
    "text-maxlines-truncated" ->
      Box(Modifier.width(128.dp)) {
        Text(
          catalogOverrideString(
            "text",
            "This body text is deliberately long so it overflows two lines and truncates.",
          ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    // Generic-family specimens — where the Android catalog said `FontFamily.Serif`/`.Monospace`,
    // this uses `genericFontFamily(...)` so both the desktop render and the wasm tier can
    // substitute
    // the URL-loaded copy of the same file the platform's system font table resolves that name to.
    "text-serif" ->
      Text(
        catalogOverrideString("text", "Serif specimen 0123"),
        fontFamily = genericFontFamily("serif"),
      )
    "text-monospace" ->
      Text(
        catalogOverrideString("text", "Mono specimen 0123"),
        fontFamily = genericFontFamily("monospace"),
      )
  }
}

/**
 * Every catalog component id, in sticker-sheet order — 1:1 with `catalog.spec.json`. The wasm app
 * uses it to tell a known id from the "unknown component" diagnostic branch.
 */
val catalogComponentIds: List<String> =
  listOf(
    "button-filled",
    "button-tonal",
    "button-outlined",
    "button-elevated",
    "button-text",
    "button-filled-disabled",
    "checkbox-checked",
    "switch-on",
    "radiobutton-selected",
    "slider",
    "chip-filter-selected",
    "chip-assist",
    "card-elevated",
    "card-outlined",
    "card-filled",
    "card-slots",
    "fab",
    "progress-linear",
    "progress-circular",
    "badge",
    "textfield-filled",
    "textfield-outlined",
    "button-filled-pressed",
    "button-filled-focused",
    "button-filled-icon-label",
    "button-outlined-disabled",
    "switch-off",
    "checkbox-unchecked",
    "chip-filter-unselected",
    "segmentedbutton",
    "text-maxlines-truncated",
    "text-serif",
    "text-monospace",
  )

/**
 * A minimal "add" (plus) glyph as an inline [ImageVector], for the `button-filled-icon-label`
 * content variant. Built by hand because this catalog module carries no `material-icons`
 * dependency; `Icon` recolors it to the button's content color, so the vector's own fill is
 * irrelevant. A 12×12 plus centered in the standard 24dp icon viewport.
 */
private val addGlyph: ImageVector =
  ImageVector.Builder(
      name = "Add",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .apply {
      path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 5f)
        lineTo(13f, 5f)
        lineTo(13f, 11f)
        lineTo(19f, 11f)
        lineTo(19f, 13f)
        lineTo(13f, 13f)
        lineTo(13f, 19f)
        lineTo(11f, 19f)
        lineTo(11f, 13f)
        lineTo(5f, 13f)
        lineTo(5f, 11f)
        lineTo(11f, 11f)
        close()
      }
    }
    .build()

// --- Interactive state holders: a browser visitor can actually toggle these. ---

@Composable
fun StatefulCheckbox(initial: Boolean) {
  var checked by remember { mutableStateOf(initial) }
  Checkbox(checked = checked, onCheckedChange = { checked = it })
}

@Composable
fun StatefulSwitch(initial: Boolean) {
  var on by remember { mutableStateOf(initial) }
  Switch(checked = on, onCheckedChange = { on = it })
}

@Composable
fun StatefulSlider() {
  var value by remember { mutableFloatStateOf(0.5f) }
  Slider(value = value, onValueChange = { value = it })
}

@Composable
fun StatefulFilterChip(initial: Boolean, label: String = "Filter") {
  var selected by remember { mutableStateOf(initial) }
  FilterChip(selected = selected, onClick = { selected = !selected }, label = { Text(label) })
}

/**
 * The single-choice segmented toggle. [interactive] lets a visitor flip the selection in the
 * browser; the baked sticker pins "On" selected so the static frame matches the published capture.
 */
@Composable
fun SegmentedToggle(interactive: Boolean) {
  var selected by remember { mutableStateOf(0) }
  val onLabel = catalogOverrideString("onLabel", "On")
  val offLabel = catalogOverrideString("offLabel", "Off")
  SingleChoiceSegmentedButtonRow {
    SegmentedButton(
      selected = if (interactive) selected == 0 else true,
      onClick = { if (interactive) selected = 0 },
      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
    ) {
      Text(onLabel)
    }
    SegmentedButton(
      selected = if (interactive) selected == 1 else false,
      onClick = { if (interactive) selected = 1 },
      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
    ) {
      Text(offLabel)
    }
  }
}

// --- Held interaction sources: seed a state so the resting state layer matches the catalog. ---

@Composable
fun pressedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(PressInteraction.Press(Offset.Zero)) }
  return source
}

@Composable
fun focusedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(FocusInteraction.Focus()) }
  return source
}
