@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.designcatalogm3.shared.generated.resources.label_filled
import com.example.designcatalogm3.shared.generated.resources.label_focused
import com.example.designcatalogm3.shared.generated.resources.label_pressed
import com.example.designcatalogm3.shared.generated.resources.m3_body_overflow
import com.example.designcatalogm3.shared.generated.resources.slot_headline
import com.example.designcatalogm3.shared.generated.resources.slot_supporting
import com.example.designcatalogm3.shared.generated.resources.textfield_label
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
 * **One composable per id, on every surface.** There is no preview-vs-live branch here: the baked
 * desktop sticker, the held Live Compose session, and the in-browser wasm tier all compose the
 * *same* control. The catalog used to take an `interactive` flag derived from `LocalInspectionMode`
 * and swap a stateful control for an inert one, which meant the published capture was not always
 * the composable that runs live (issue #3674). Every control is now unconditionally stateful and
 * seeds its initial state from an ordinary argument — the `catalogOverride*` knob below — so the
 * first composed frame is byte-identical to the frame the inert branch used to produce, while a
 * click in a live lane actually moves it.
 *
 * **Coverage is by FEATURE, not by component.** This set is deliberately not an exhaustive Material
 * 3 inventory — [m3-catalog](https://github.com/yschimke/m3-catalog) is that, and this catalog
 * exists to exercise the preview pipeline. So each pipeline feature keeps one or two carriers and
 * no more: one emphasis level of button (which also hosts the pressed / focused / disabled /
 * icon-label captures), one card (the slotted one), one text field, one progress indicator, and
 * three selection controls chosen for their knob *types* — `Boolean` (checkbox), `Boolean` +
 * `@InteractionPreview` + the i18n/a11y axes (switch), `Float` (slider). The emphasis-level
 * buttons, plain cards, outlined text field, circular progress, radio button, chips and segmented
 * button were each a second spelling of a feature already covered.
 *
 * **Almost every component responds to a click.** The ones that carry state — switch, checkbox,
 * slider, text field — own it and mutate it. The button family routes its click through [counted],
 * which tallies it into the label, so a click is never a silent no-op. The counter starts at `0`
 * and [counted] draws the bare label at `0`, so a never-clicked render is unchanged.
 *
 * Two deliberate exceptions: the **disabled** button variant (staying inert is the state it
 * documents) and `card-slots` (a slot host — see its branch).
 *
 * **The pressed / focused button states are driven by real input, not forged here.** Both compose a
 * plain `Button`; the state comes from `@FocusedPreview` on the sticker preview (a real focus
 * traversal, and a real pointer press for the pressed one), so the capture proves the component
 * receives the interaction instead of proving its state layer can be painted (issue #3672).
 *
 * **Editable knobs.** Each component's author-facing values — labels, the entered text-field value,
 * toggle flags, slider & progress values, the badge count, the slotted card's accent — are declared
 * through the `catalogOverride*` wrappers, the catalog's bridge to the opt-in `previewOverride*`
 * surface (see [catalogOverrideString]). Every knob returns its author default when nothing is
 * seeded, so the baked sticker sheet is pixel-unchanged; a daemon-backed render can seed
 * replacements and the `compose/overrides` producer can enumerate what's editable per sticker.
 *
 * **Fillable slots.** Each region of `card-slots` is wrapped in a `PreviewSlot(name)` marker (the
 * Figma slot placeholder added for the structured-screen builder): a no-op in a normal render, it
 * swaps to a labelled placeholder under `LocalSlotMode` so a designer sees exactly where a child
 * drops in.
 */
@Composable
fun CatalogComponent(id: String) {
  when (id) {
    // The filled button — one emphasis level, not five. This catalog covers the preview
    // pipeline's FEATURES, not Material's component surface (m3-catalog is the exhaustive
    // reference), and the four other emphasis levels re-proved nothing this one doesn't: the label
    // is an editable `catalogOverrideString("label", …)` knob so a daemon-backed render can retitle
    // it from the `compose/overrides` surface, `enabled` is the `@OverrideVariant` knob, and the
    // pressed / focused / icon-label ids below hang off this same button.
    //
    // A plain button has no intrinsic state to show, so its click is made visible by [counted]: the
    // label picks up a click tally. A never-clicked render is unaffected — see [counted] for why
    // the first frame is byte-identical.
    "button-filled" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_filled)))
      // `enabled` is a knob so the disabled state rides this component as an `@OverrideVariant`
      // rather than a second slug — the same shape the selection controls use for `checked`.
      Button(onClick = onClick, enabled = catalogOverrideBoolean("enabled", true)) { Text(label) }
    }

    // Selection controls — primary (checked/selected) state. The checked/selected flag is a
    // `catalogOverrideBoolean` knob: it is what the `@OverrideVariant` folds (`off`, `unchecked`)
    // seed, and it is the control's **initial** value, so the first composed frame is exactly the
    // seeded state on every surface. A tap then moves it from there.
    //
    // Two controls carry that knob, not five: the radio button and the filter chip were a third
    // and fourth spelling of the same `@OverrideVariant`-seeds-a-boolean feature, and the
    // segmented button and assist chip carried no feature at all. The slider stays because its
    // knob is a `Float` rather than a `Boolean`.
    "checkbox-checked" -> StatefulCheckbox(catalogOverrideBoolean("checked", true))
    "switch-on" -> StatefulSwitch(catalogOverrideBoolean("checked", true))
    "slider" -> Box(Modifier.width(220.dp)) { StatefulSlider(catalogOverrideFloat("value", 0.5f)) }
    "shape-morph" -> ShapeMorphViewer()

    // Containment — one card, and it is the SLOTTED one. The three plain cards (elevated /
    // outlined / filled) differed only in Material's own surface treatment, which is m3-catalog's
    // job; this one is here for a pipeline feature the others don't touch. Each region is wrapped
    // in `PreviewSlot(name) { … }`, a no-op in a normal render (draws the content, tagged
    // `dp-slot:<name>`) that swaps to a labelled placeholder under slot mode. Each slot carries an
    // explicit size, so the box a child fills — and the placeholder shown under slot mode — is
    // well-defined. The structured-screen builder reads these slots from `/render/card-slots.slots`
    // and fills each by rendering another component to that size.
    //
    // Deliberately NOT clickable. This one is a slot **host**: the builder drops real components
    // into those regions, and a card-wide click target sitting over them would swallow the taps
    // meant for the children — making the filled card less interactive, not more. The slots' own
    // contents carry whatever click behaviour they came with.
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

    // Communication — one progress indicator (the `Float` `progress` knob) and the badge (the
    // `Int` `count` knob). The circular indicator drew the same knob as the linear one, so it went;
    // the badge stays because it is the only `catalogOverrideInt` on the sheet.
    //
    // The indicator is **determinate** on every surface. This is the one place where dropping the
    // `interactive` axis (issue #3674) genuinely removed behaviour rather than a redundant branch:
    // the in-browser tier used to compose the no-`progress` (indeterminate, animated) overload,
    // which is a different composable drawing different pixels from the sticker the catalog
    // publishes — the exact "the capture isn't what runs live" split the issue is about. The
    // animated ring lives on the Wear sheet, which models it as its own catalog id
    // (`Progress/Circular/Indeterminate`) rather than as a hidden lane flag.
    "progress-linear" -> {
      val progress = catalogOverrideFloat("progress", 0.6f)
      Box(Modifier.width(220.dp)) { LinearProgressIndicator(progress = { progress }) }
    }
    "badge" -> Badge { Text(catalogOverrideInt("count", 8).toString()) }

    // Text field — the entered value and the floating label are both editable knobs, and this is
    // the sheet's only component that owns *text* state. It owns its value everywhere, seeded from
    // the `value` knob, so a visitor can actually type into it and an un-typed render still shows
    // exactly the seeded text. (The outlined twin was the same knobs behind a different border.)
    "textfield-filled" ->
      StatefulTextField(
        catalogOverrideString("value", stringResource(Res.string.label_filled)),
        catalogOverrideString("label", stringResource(Res.string.textfield_label)),
      )

    // States — interaction (pressed / focused), disabled, and toggle off↔on.
    //
    // The two interaction states are **plain buttons** (issue #3672). They used to hold a
    // hand-emitted `PressInteraction.Press` / `FocusInteraction.Focus` on a
    // `MutableInteractionSource`, which forged the visual: nothing was really focused or pressed,
    // the emission was never paired with a `Release` / `Unfocus`, and the capture only worked
    // because `Button` happens to read its indication off the interaction source. The state now
    // comes from the render harness instead — `@FocusedPreview` on the sticker previews next door
    // in `:samples:design-catalog-m3` walks real focus and dispatches a real pointer press — so
    // these ids compose the same button the rest of the catalog does, and a live lane shows the
    // state when a visitor actually focuses or presses it.
    "button-filled-pressed" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_pressed)))
      Button(onClick = onClick) { Text(label) }
    }
    "button-filled-focused" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_focused)))
      Button(onClick = onClick) { Text(label) }
    }
    // Content axis (not a state): the same Filled button with a leading icon + label, so the
    // catalog shows the icon-and-text configuration alongside the label-only default. The icon is
    // an inline `ImageVector` (a plus glyph) — this module deliberately carries no icon library,
    // and
    // `Icon` tints it with the button's content color regardless of the vector's own fill.
    "button-filled-icon-label" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_filled)))
      Button(onClick = onClick) {
        Icon(addGlyph, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
      }
    }

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
 * Every catalog component id, in sticker-sheet order. The wasm app uses it to tell a known id from
 * the "unknown component" diagnostic branch. All but `text-branded` carry a `@CatalogComponent` /
 * `@CatalogVariant` next door in `:samples:design-catalog-m3`; that one renders and mounts but is
 * deliberately absent from the published inventory.
 */
val catalogComponentIds: List<String> =
  listOf(
    "button-filled",
    "checkbox-checked",
    "switch-on",
    "slider",
    "shape-morph",
    "card-slots",
    "progress-linear",
    "badge",
    "textfield-filled",
    "button-filled-pressed",
    "button-filled-focused",
    "button-filled-icon-label",
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

// --- State holders. Every catalog control is one of these, on every surface: the initial value is
// --- an ordinary argument (the seeded `catalogOverride*` knob), so the first frame is the seeded
// --- state and a real click moves it from there. ---

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

/**
 * The slider, seeded from the `value` knob. It used to hard-code its own `0.5f` start and ignore
 * that knob entirely — harmless while the two lanes were separate composables (only the inert lane
 * read the knob), but a live seed of `value` silently did nothing. With one composable per id the
 * knob has to be the initial value.
 */
@Composable
fun StatefulSlider(initial: Float) {
  var value by remember { mutableFloatStateOf(initial) }
  Slider(value = value, onValueChange = { value = it })
}

/**
 * Material expressive shape interpolation, shared by the desktop preview and the Wasm catalog.
 *
 * The first frame is pinned to the midpoint so its screenshot is deterministic. Every lane exposes
 * the same stateful slider and redraws the [Morph] without a server round trip. [Morph]
 * intentionally accepts [androidx.graphics.shapes.RoundedPolygon] rather than an arbitrary Compose
 * `Shape`, so the two endpoints retain the feature information needed for a stable match.
 */
@Composable
fun ShapeMorphViewer() {
  val initial = catalogOverrideFloat("progress", 0.5f).coerceIn(0f, 1f)
  var progress by remember(initial) { mutableFloatStateOf(initial) }
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
    Slider(value = progress, onValueChange = { progress = it }, modifier = Modifier.width(220.dp))
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
fun StatefulTextField(initial: String, label: String) {
  var value by remember { mutableStateOf(initial) }
  TextField(value = value, onValueChange = { value = it }, label = { Text(label) })
}

/**
 * Gives a stateless action component — a button — something visible to do when clicked, by tallying
 * clicks into its label: `Filled` → `Filled (1)` → `Filled (2)`.
 *
 * Returns the label to draw and the `onClick` to wire. The counter starts at `0` and the `0` case
 * draws [base] verbatim, so a render that nothing has clicked — the baked sticker sheet and every
 * one-shot `/render` — is byte-identical to the one this catalog has always produced. It moves only
 * where a real pointer dispatches into a held composition.
 *
 * That `clicks == 0` fold is what let the preview-vs-live `interactive` flag go (issue #3674): the
 * inert branch it used to take (`base to {}`) was already the same first frame this returns.
 */
@Composable
fun counted(base: String): Pair<String, () -> Unit> {
  var clicks by remember { mutableIntStateOf(0) }
  return (if (clicks == 0) base else "$base ($clicks)") to { clicks++ }
}

// --- No held interaction sources live here any more (issue #3672). ---
//
// `pressedSource()` / `focusedSource()` used to sit at the bottom of this file, emitting a
// `PressInteraction.Press` / `FocusInteraction.Focus` from a `LaunchedEffect` so the two
// interaction-state stickers would render with a state layer. They were marked as a stopgap and
// they are gone: the desktop renderer now drives `@FocusedPreview` the way the Android one does —
// a real `FocusManager.moveFocus` traversal under a synthetic keyboard input mode, plus a real
// pointer press dispatched onto the focused element — so `button-filled-pressed` /
// `button-filled-focused` compose a plain `Button` and the harness supplies the state.
//
// If a future sticker needs a state this catalog can't reach through real input, add the capture
// mechanism to the renderer rather than a held interaction source here: a forged interaction
// documents the state layer, not the component, and silently keeps documenting it after the
// component stops being able to enter that state at all.
