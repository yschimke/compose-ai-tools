@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3.shared

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import com.example.designcatalogm3.shared.generated.resources.Res
import com.example.designcatalogm3.shared.generated.resources.card_elevated
import com.example.designcatalogm3.shared.generated.resources.card_filled
import com.example.designcatalogm3.shared.generated.resources.card_outlined
import com.example.designcatalogm3.shared.generated.resources.label_assist
import com.example.designcatalogm3.shared.generated.resources.label_elevated
import com.example.designcatalogm3.shared.generated.resources.label_filled
import com.example.designcatalogm3.shared.generated.resources.label_filter
import com.example.designcatalogm3.shared.generated.resources.label_focused
import com.example.designcatalogm3.shared.generated.resources.label_outlined
import com.example.designcatalogm3.shared.generated.resources.label_pressed
import com.example.designcatalogm3.shared.generated.resources.label_text
import com.example.designcatalogm3.shared.generated.resources.label_tonal
import com.example.designcatalogm3.shared.generated.resources.m3_body_overflow
import com.example.designcatalogm3.shared.generated.resources.slot_headline
import com.example.designcatalogm3.shared.generated.resources.slot_supporting
import com.example.designcatalogm3.shared.generated.resources.textfield_label
import com.example.designcatalogm3.shared.generated.resources.toggle_off
import com.example.designcatalogm3.shared.generated.resources.toggle_on
import ee.schimke.composeai.preview.slots.PreviewSlot
import org.jetbrains.compose.resources.stringResource

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
 * * `true` (the in-browser tier, and the held Live Compose session) uses live, stateful widgets so
 *   a visitor can actually toggle a switch, drag a slider, type into a text field, and watch the
 *   indeterminate progress animate.
 *
 * **Every component responds to a click on the interactive surfaces.** The ones that carry state —
 * switch, checkbox, radio, filter chip, slider, segmented button, text fields — own it and mutate
 * it. The ones that don't (the button family, the FAB, the assist chip) route their click through
 * [counted], which tallies it into the label, so a click is never a silent no-op. The two
 * deliberate exceptions are the **disabled** button stickers: staying inert is the state they
 * document.
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
    //
    // A plain button has no intrinsic state to show, so on the interactive surfaces its click is
    // made visible by [counted]: the label picks up a click tally. Baked renders are unaffected —
    // see [counted] for why the static frame is byte-identical.
    "button-filled" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_filled)),
          interactive,
        )
      // `enabled` is a knob so the disabled state rides this component as an `@OverrideVariant`
      // rather than a second slug — the same shape the selection controls use for `checked`.
      Button(onClick = onClick, enabled = catalogOverrideBoolean("enabled", true)) { Text(label) }
    }
    "button-tonal" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_tonal)), interactive)
      FilledTonalButton(onClick = onClick) { Text(label) }
    }
    "button-outlined" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_outlined)),
          interactive,
        )
      OutlinedButton(onClick = onClick, enabled = catalogOverrideBoolean("enabled", true)) {
        Text(label)
      }
    }
    "button-elevated" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_elevated)),
          interactive,
        )
      ElevatedButton(onClick = onClick) { Text(label) }
    }
    "button-text" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_text)), interactive)
      TextButton(onClick = onClick) { Text(label) }
    }
    // Deliberately NOT counted: a disabled button must stay inert on every surface — that
    // unresponsiveness is the state this sticker documents.
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
    "radiobutton-selected" -> {
      val selected = catalogOverrideBoolean("selected", true)
      if (interactive) StatefulRadioButton(selected)
      else RadioButton(selected = selected, onClick = {})
    }
    "slider" ->
      Box(Modifier.width(220.dp)) {
        val value = catalogOverrideFloat("value", 0.5f)
        if (interactive) StatefulSlider() else Slider(value = value, onValueChange = {})
      }
    "shape-morph" -> ShapeMorphViewer(interactive)
    "chip-filter-selected" -> {
      val selected = catalogOverrideBoolean("selected", true)
      val label = catalogOverrideString("label", stringResource(Res.string.label_filter))
      if (interactive) StatefulFilterChip(selected, label)
      else FilterChip(selected = selected, onClick = {}, label = { Text(label) })
    }
    // An assist chip is an action, not a selection — like the plain buttons it carries no state of
    // its own, so [counted] gives its click a visible result on the interactive surfaces.
    "chip-assist" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_assist)),
          interactive,
        )
      AssistChip(onClick = onClick, label = { Text(label) })
    }

    // Containment — cards and the FAB. Each card's body is wrapped in a `PreviewSlot("content")`
    // filling the fixed 160×80 box: a no-op in a normal render (draws the — now editable — label,
    // tagged `dp-slot:content`), it swaps to a labelled placeholder under slot mode so a
    // structured-screen builder can drop a child into that exact box.
    // M3's cards — unlike Wear's and Remote's, whose APIs take a required `onClick` — ship both a
    // plain and a clickable overload. The interactive lane picks the clickable one so a tap does
    // something (the label counts, as everywhere else); the baked lane composes the **same plain
    // overload it always did**, so the published capture keeps its exact node tree, not just its
    // pixels — the `a11y/touchTargets` greenlines and the layout wireframe would otherwise gain a
    // clickable node that no longer describes the sticker.
    "card-elevated" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.card_elevated)),
          interactive,
        )
      if (interactive) ElevatedCard(onClick = onClick) { CardContentSlot(label) }
      else ElevatedCard { CardContentSlot(label) }
    }
    "card-outlined" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.card_outlined)),
          interactive,
        )
      if (interactive) OutlinedCard(onClick = onClick) { CardContentSlot(label) }
      else OutlinedCard { CardContentSlot(label) }
    }
    "card-filled" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.card_filled)), interactive)
      if (interactive) Card(onClick = onClick) { CardContentSlot(label) }
      else Card { CardContentSlot(label) }
    }
    // A **slotted** card: each region is wrapped in `PreviewSlot(name) { … }`, a no-op in a normal
    // render (draws the content, tagged `dp-slot:<name>`) that swaps to a labelled placeholder
    // under
    // slot mode. Each slot carries an explicit size, so the box a child fills — and the placeholder
    // shown under slot mode — is well-defined. The structured-screen builder reads these slots from
    // `/render/card-slots.slots` and fills each by rendering another component to that size.
    //
    // Deliberately NOT clickable on either lane, unlike the three plain cards above. This one is a
    // slot **host**: the builder drops real components into those regions, and a card-wide click
    // target sitting over them would swallow the taps meant for the children — making the filled
    // card less interactive, not more. The slots' own contents carry whatever click behaviour they
    // came with.
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
              Text(catalogOverrideString("headline", stringResource(Res.string.slot_headline)))
            }
            PreviewSlot("supporting", Modifier.size(140.dp, 16.dp)) {
              Text(catalogOverrideString("supporting", stringResource(Res.string.slot_supporting)))
            }
          }
        }
      }
    "fab" -> {
      val (label, onClick) = counted(catalogOverrideString("label", "+"), interactive)
      FloatingActionButton(onClick = onClick) { Text(label) }
    }

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

    // Text fields — both the entered value and the floating label are editable knobs. On the
    // interactive surfaces the field owns its value, so a visitor can actually type into it; the
    // baked frame keeps the seeded value with a no-op `onValueChange`.
    "textfield-filled" -> {
      val value = catalogOverrideString("value", stringResource(Res.string.label_filled))
      val label = catalogOverrideString("label", stringResource(Res.string.textfield_label))
      if (interactive) StatefulTextField(value, label)
      else TextField(value = value, onValueChange = {}, label = { Text(label) })
    }
    "textfield-outlined" -> {
      val value = catalogOverrideString("value", stringResource(Res.string.label_outlined))
      val label = catalogOverrideString("label", stringResource(Res.string.textfield_label))
      if (interactive) StatefulOutlinedTextField(value, label)
      else OutlinedTextField(value = value, onValueChange = {}, label = { Text(label) })
    }

    // States — interaction (pressed / focused), disabled, and toggle off↔on. The held interaction
    // source pins the state layer on both surfaces; the click itself still counts, so these stay
    // responsive in a live session rather than reading as frozen images.
    "button-filled-pressed" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_pressed)),
          interactive,
        )
      Button(onClick = onClick, interactionSource = pressedSource()) { Text(label) }
    }
    "button-filled-focused" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_focused)),
          interactive,
        )
      Button(onClick = onClick, interactionSource = focusedSource()) { Text(label) }
    }
    // Content axis (not a state): the same Filled button with a leading icon + label, so the
    // catalog shows the icon-and-text configuration alongside the label-only default. The icon is
    // an inline `ImageVector` (a plus glyph) — this module deliberately carries no icon library,
    // and
    // `Icon` tints it with the button's content color regardless of the vector's own fill.
    "button-filled-icon-label" -> {
      val (label, onClick) =
        counted(
          catalogOverrideString("label", stringResource(Res.string.label_filled)),
          interactive,
        )
      Button(onClick = onClick) {
        Icon(addGlyph, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
      }
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
      val label = catalogOverrideString("label", stringResource(Res.string.label_filter))
      if (interactive) StatefulFilterChip(selected, label)
      else FilterChip(selected = selected, onClick = {}, label = { Text(label) })
    }
    "radiobutton-unselected" -> {
      val selected = catalogOverrideBoolean("selected", false)
      if (interactive) StatefulRadioButton(selected)
      else RadioButton(selected = selected, onClick = {})
    }
    "segmentedbutton" -> SegmentedToggle(interactive)

    // Text options — maxLines + ellipsis overflow. The 128dp box reproduces the wrap/truncation
    // point the Android sticker got from its 160dp preview canvas minus the sticker's 16dp padding
    // (160 − 2·16 = 128), so the baked frame is unchanged and both surfaces share one body.
    "text-maxlines-truncated" ->
      Box(Modifier.width(128.dp)) {
        Text(
          catalogOverrideString("text", stringResource(Res.string.m3_body_overflow)),
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
    // Named downloadable-GoogleFont specimen — where an Android-only component would say
    // `FontFamily(Font(GoogleFont("Orbitron"), provider))`, this uses `namedFontFamily(...)` so the
    // desktop render and the wasm tier resolve the vendored Orbitron faces (`role: "named"` in the
    // fonts manifest). Falls back to the platform sans if the tier didn't vendor the family.
    "text-branded" ->
      Text(catalogOverrideString("text", "Orbitron 0123"), fontFamily = namedFontFamily("Orbitron"))
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
    "checkbox-checked",
    "switch-on",
    "radiobutton-selected",
    "slider",
    "shape-morph",
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
    "switch-off",
    "checkbox-unchecked",
    "chip-filter-unselected",
    "radiobutton-unselected",
    "segmentedbutton",
    "text-maxlines-truncated",
    "text-serif",
    "text-monospace",
    "text-branded",
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

/**
 * Material expressive shape interpolation, shared by the desktop preview and the Wasm catalog.
 *
 * The baked lane is pinned to the midpoint so its screenshot is deterministic. Held Live Compose
 * and Wasm sessions expose the slider and redraw the same [Morph] without a server round trip.
 * [Morph] intentionally accepts [androidx.graphics.shapes.RoundedPolygon] rather than an arbitrary
 * Compose `Shape`, so the two endpoints retain the feature information needed for a stable match.
 */
@Composable
fun ShapeMorphViewer(interactive: Boolean) {
  val initial = catalogOverrideFloat("progress", 0.5f).coerceIn(0f, 1f)
  var liveProgress by remember(initial) { mutableFloatStateOf(initial) }
  val progress = if (interactive) liveProgress else initial
  val morph = remember {
    val rounding = CornerRounding(radius = 0.12f, smoothing = 0.45f)
    Morph(
      start = RoundedPolygon(numVertices = 4, rounding = rounding).normalized(),
      end =
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.72f, rounding = rounding)
          .normalized(),
    )
  }
  val fill = MaterialTheme.colorScheme.primary
  val outline = MaterialTheme.colorScheme.onSurface

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Canvas(Modifier.size(180.dp)) {
      val path = morph.toComposePath(progress)
      path.transform(Matrix().apply { scale(size.width, size.height) })
      drawPath(path, color = fill)
      drawPath(path, color = outline, style = Stroke(width = 1.dp.toPx()))
    }
    Slider(
      value = progress,
      onValueChange = { if (interactive) liveProgress = it },
      modifier = Modifier.width(220.dp),
    )
    Text("Square → Rounded 9-point star · ${(progress * 100).toInt()}%")
  }
}

/** Convert the matched cubic segments to a Compose path without a platform-specific adapter. */
private fun Morph.toComposePath(progress: Float): Path =
  Path().also { path ->
    var first = true
    forEachCubic(progress) { cubic ->
      if (first) {
        path.moveTo(cubic.anchor0X, cubic.anchor0Y)
        first = false
      }
      path.cubicTo(
        cubic.control0X,
        cubic.control0Y,
        cubic.control1X,
        cubic.control1Y,
        cubic.anchor1X,
        cubic.anchor1Y,
      )
    }
    path.close()
  }

@Composable
fun StatefulFilterChip(initial: Boolean, label: String = "Filter") {
  var selected by remember { mutableStateOf(initial) }
  FilterChip(selected = selected, onClick = { selected = !selected }, label = { Text(label) })
}

/**
 * The fixed 160×80 content box the three plain cards share, wrapped in its `PreviewSlot("content")`
 * marker. Factored out so each card can compose the identical body through either its plain or its
 * clickable overload without the body being written twice per card.
 */
@Composable
private fun CardContentSlot(label: String) {
  Box(Modifier.size(160.dp, 80.dp)) {
    PreviewSlot("content", Modifier.fillMaxSize()) { Text(label) }
  }
}

/**
 * A radio button that flips its own selection. A real radio is one of a group and can't be
 * deselected by tapping it again — but a catalog sticker *is* the single control, and both of its
 * states are what a viewer came to see, so here the tap toggles. The static sticker keeps the plain
 * one-way [RadioButton] with its seeded `selected` knob.
 */
@Composable
fun StatefulRadioButton(initial: Boolean) {
  var selected by remember { mutableStateOf(initial) }
  RadioButton(selected = selected, onClick = { selected = !selected })
}

@Composable
fun StatefulTextField(initial: String, label: String) {
  var value by remember { mutableStateOf(initial) }
  TextField(value = value, onValueChange = { value = it }, label = { Text(label) })
}

@Composable
fun StatefulOutlinedTextField(initial: String, label: String) {
  var value by remember { mutableStateOf(initial) }
  OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) })
}

/**
 * Gives a stateless action component — a button, a FAB, an assist chip — something visible to do
 * when clicked, by tallying clicks into its label: `Filled` → `Filled (1)` → `Filled (2)`.
 *
 * Returns the label to draw and the `onClick` to wire. When [interactive] is `false` (the baked
 * sticker sheet and every one-shot `/render`) it returns [base] verbatim and a no-op handler, so
 * the published capture is byte-identical to the one this catalog has always produced. The counter
 * only ever moves on a surface where a real pointer is dispatching into a held composition.
 *
 * The `remember` is unconditional so the composition's slot table is the same shape on both
 * surfaces — only the values read out of it differ.
 */
@Composable
fun counted(base: String, interactive: Boolean): Pair<String, () -> Unit> {
  var clicks by remember { mutableIntStateOf(0) }
  if (!interactive) return base to {}
  return (if (clicks == 0) base else "$base ($clicks)") to { clicks++ }
}

/**
 * The single-choice segmented toggle. [interactive] lets a visitor flip the selection in the
 * browser; the baked sticker pins "On" selected so the static frame matches the published capture.
 */
@Composable
fun SegmentedToggle(interactive: Boolean) {
  var selected by remember { mutableStateOf(0) }
  val onLabel = catalogOverrideString("onLabel", stringResource(Res.string.toggle_on))
  val offLabel = catalogOverrideString("offLabel", stringResource(Res.string.toggle_off))
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
