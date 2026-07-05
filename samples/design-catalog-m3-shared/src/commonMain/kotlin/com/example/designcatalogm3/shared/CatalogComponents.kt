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
 */
@Composable
fun CatalogComponent(id: String, interactive: Boolean) {
  when (id) {
    // Buttons — the five M3 emphasis levels, plus disabled.
    "button-filled" -> Button(onClick = {}) { Text("Filled") }
    "button-tonal" -> FilledTonalButton(onClick = {}) { Text("Tonal") }
    "button-outlined" -> OutlinedButton(onClick = {}) { Text("Outlined") }
    "button-elevated" -> ElevatedButton(onClick = {}) { Text("Elevated") }
    "button-text" -> TextButton(onClick = {}) { Text("Text") }
    "button-filled-disabled" -> Button(onClick = {}, enabled = false) { Text("Disabled") }

    // Selection controls — primary (checked/selected) state.
    "checkbox-checked" -> if (interactive) StatefulCheckbox(true) else Checkbox(true, {})
    "switch-on" ->
      if (interactive) StatefulSwitch(true) else Switch(checked = true, onCheckedChange = {})
    "radiobutton-selected" -> RadioButton(selected = true, onClick = {})
    "slider" ->
      Box(Modifier.width(220.dp)) {
        if (interactive) StatefulSlider() else Slider(value = 0.5f, onValueChange = {})
      }
    "chip-filter-selected" ->
      if (interactive) StatefulFilterChip(true)
      else FilterChip(selected = true, onClick = {}, label = { Text("Filter") })
    "chip-assist" -> AssistChip(onClick = {}, label = { Text("Assist") })

    // Containment — cards and the FAB.
    "card-elevated" -> ElevatedCard { Box(Modifier.size(160.dp, 80.dp)) { Text("Elevated card") } }
    "card-outlined" -> OutlinedCard { Box(Modifier.size(160.dp, 80.dp)) { Text("Outlined card") } }
    "card-filled" -> Card { Box(Modifier.size(160.dp, 80.dp)) { Text("Filled card") } }
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
            Box(Modifier.size(40.dp).background(Color(0xFF6750A4)))
          }
          Column(Modifier.padding(start = 12.dp)) {
            PreviewSlot("headline", Modifier.size(140.dp, 20.dp)) { Text("Headline") }
            PreviewSlot("supporting", Modifier.size(140.dp, 16.dp)) { Text("Supporting text") }
          }
        }
      }
    "fab" -> FloatingActionButton(onClick = {}) { Text("+") }

    // Communication — progress + badge. The baked sticker keeps the deterministic `0.6` frame; the
    // in-browser tier runs the indeterminate (animated) variant so it's visibly live.
    "progress-linear" ->
      Box(Modifier.width(220.dp)) {
        if (interactive) LinearProgressIndicator() else LinearProgressIndicator(progress = { 0.6f })
      }
    "progress-circular" ->
      if (interactive) CircularProgressIndicator()
      else CircularProgressIndicator(progress = { 0.6f })
    "badge" -> Badge { Text("8") }

    // Text fields.
    "textfield-filled" -> TextField(value = "Filled", onValueChange = {}, label = { Text("Label") })
    "textfield-outlined" ->
      OutlinedTextField(value = "Outlined", onValueChange = {}, label = { Text("Label") })

    // States — interaction (pressed / focused), disabled, and toggle off↔on.
    "button-filled-pressed" ->
      Button(onClick = {}, interactionSource = pressedSource()) { Text("Pressed") }
    "button-filled-focused" ->
      Button(onClick = {}, interactionSource = focusedSource()) { Text("Focused") }
    // Content axis (not a state): the same Filled button with a leading icon + label, so the
    // catalog shows the icon-and-text configuration alongside the label-only default. The icon is
    // an inline `ImageVector` (a plus glyph) — this module deliberately carries no icon library,
    // and
    // `Icon` tints it with the button's content color regardless of the vector's own fill.
    "button-filled-icon-label" ->
      Button(onClick = {}) {
        Icon(addGlyph, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Filled")
      }
    "button-outlined-disabled" -> OutlinedButton(onClick = {}, enabled = false) { Text("Disabled") }
    "switch-off" ->
      if (interactive) StatefulSwitch(false) else Switch(checked = false, onCheckedChange = {})
    "checkbox-unchecked" -> if (interactive) StatefulCheckbox(false) else Checkbox(false, {})
    "chip-filter-unselected" ->
      if (interactive) StatefulFilterChip(false)
      else FilterChip(selected = false, onClick = {}, label = { Text("Filter") })
    "segmentedbutton" -> SegmentedToggle(interactive)

    // Text options — maxLines + ellipsis overflow. The 128dp box reproduces the wrap/truncation
    // point the Android sticker got from its 160dp preview canvas minus the sticker's 16dp padding
    // (160 − 2·16 = 128), so the baked frame is unchanged and both surfaces share one body.
    "text-maxlines-truncated" ->
      Box(Modifier.width(128.dp)) {
        Text(
          "This body text is deliberately long so it overflows two lines and truncates.",
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    // Generic-family specimens — where the Android catalog said `FontFamily.Serif`/`.Monospace`,
    // this uses `genericFontFamily(...)` so both the desktop render and the wasm tier can
    // substitute
    // the URL-loaded copy of the same file the platform's system font table resolves that name to.
    "text-serif" -> Text("Serif specimen 0123", fontFamily = genericFontFamily("serif"))
    "text-monospace" -> Text("Mono specimen 0123", fontFamily = genericFontFamily("monospace"))
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
fun StatefulFilterChip(initial: Boolean) {
  var selected by remember { mutableStateOf(initial) }
  FilterChip(selected = selected, onClick = { selected = !selected }, label = { Text("Filter") })
}

/**
 * The single-choice segmented toggle. [interactive] lets a visitor flip the selection in the
 * browser; the baked sticker pins "On" selected so the static frame matches the published capture.
 */
@Composable
fun SegmentedToggle(interactive: Boolean) {
  var selected by remember { mutableStateOf(0) }
  SingleChoiceSegmentedButtonRow {
    SegmentedButton(
      selected = if (interactive) selected == 0 else true,
      onClick = { if (interactive) selected = 0 },
      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
    ) {
      Text("On")
    }
    SegmentedButton(
      selected = if (interactive) selected == 1 else false,
      onClick = { if (interactive) selected = 1 },
      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
    ) {
      Text("Off")
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
