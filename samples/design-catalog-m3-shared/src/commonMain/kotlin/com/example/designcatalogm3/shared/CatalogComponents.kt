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
import org.jetbrains.compose.resources.stringResource

/**
 * The authoritative Compose Material 3 catalog component set, shared by the desktop `@Preview`
 * sticker sheet (`:samples:design-catalog-m3`) and the in-browser wasm app
 * (`:samples:cmp-wasm-catalog`). The multiplatform `material3` artifact uses the same package names
 * as Android, so bodies match the Android catalog.
 *
 * **Ids are the catalog's slugged `componentId`** (lowercase, non-alphanumeric runs → `-`), 1:1
 * with `samples/design-catalog-m3/catalog.spec.json`.
 *
 * **One composable per id, on every surface.** The baked sticker, the live session and the wasm
 * tier all compose the same stateful control, seeded from a `catalogOverride*` knob, so the first
 * frame is the published capture and a live click actually moves it.
 *
 * **Coverage is by feature, not component.** [m3-catalog](https://github.com/yschimke/m3-catalog)
 * is the exhaustive inventory; this catalog keeps one or two carriers per pipeline feature (e.g.
 * selection controls chosen for their knob types: `Boolean` checkbox, `Boolean` + interaction +
 * i18n/a11y switch, `Float` slider).
 *
 * **Almost every component responds to a click**: stateful controls mutate, and buttons tally
 * clicks via [counted] (drawing the bare label at `0`, so unclicked renders are unchanged).
 * Exceptions: the disabled button and `card-slots` (a slot host).
 *
 * **Pressed / focused states come from real input** via `@FocusedPreview` on the sticker previews,
 * not forged interactions.
 *
 * **Editable knobs** are declared through `catalogOverride*` wrappers (see
 * [catalogOverrideString]), which return their author defaults when nothing is seeded.
 *
 * **Fillable slots**: `card-slots` regions are wrapped in `PreviewSlot(name)` markers, swapping to
 * a labelled placeholder under `LocalSlotMode`.
 */
@Composable
fun CatalogComponent(id: String) {
  when (id) {
    // The filled button — one emphasis level is enough to carry the label knob, the `enabled`
    // `@OverrideVariant` knob, and the pressed / focused / icon-label ids below. Clicks are tallied
    // into the label by [counted].
    "button-filled" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_filled)))
      // `enabled` is a knob so the disabled state rides this component as an `@OverrideVariant`.
      Button(onClick = onClick, enabled = catalogOverrideBoolean("enabled", true)) { Text(label) }
    }

    // Selection controls. The checked flag is a `catalogOverrideBoolean` knob — what the
    // `@OverrideVariant` folds (`off`, `unchecked`) seed — and the control's initial value, so the
    // first frame is the seeded state and a tap moves it from there. The slider carries a `Float`
    // knob.
    "checkbox-checked" -> StatefulCheckbox(catalogOverrideBoolean("checked", true))
    "switch-on" -> StatefulSwitch(catalogOverrideBoolean("checked", true))
    "slider" -> Box(Modifier.width(220.dp)) { StatefulSlider(catalogOverrideFloat("value", 0.5f)) }
    "shape-morph" -> ShapeMorphViewer()

    // Containment — the slotted card. Each region is wrapped in `PreviewSlot(name) { … }` with an
    // explicit size, so the structured-screen builder (reading `/render/card-slots.slots`) knows
    // the box each child fills. Deliberately not clickable: a card-wide click target would swallow
    // taps meant for the slotted children.
    "card-slots" ->
      ElevatedCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          CatalogPreviewSlot(
            "leadingIcon",
            Modifier.size(40.dp),
            horizontal = CatalogSlotSizing.Fixed,
            vertical = CatalogSlotSizing.Fixed,
          ) {
            Box(
              Modifier.size(40.dp).background(catalogOverrideColor("iconColor", Color(0xFF6750A4)))
            )
          }
          Column(Modifier.padding(start = 12.dp)) {
            CatalogPreviewSlot(
              "headline",
              Modifier.width(140.dp),
              horizontal = CatalogSlotSizing.Fixed,
              vertical = CatalogSlotSizing.Hug,
            ) {
              Text(catalogOverrideString("headline", stringResource(Res.string.slot_headline)))
            }
            CatalogPreviewSlot(
              "supporting",
              Modifier.width(140.dp),
              horizontal = CatalogSlotSizing.Fixed,
              vertical = CatalogSlotSizing.Hug,
            ) {
              Text(catalogOverrideString("supporting", stringResource(Res.string.slot_supporting)))
            }
          }
        }
      }

    // Communication — the linear progress indicator (the `Float` `progress` knob) and the badge
    // (the sheet's only `catalogOverrideInt`). The indicator is determinate on every surface so the
    // live lane composes what the sticker publishes; the animated ring lives on the Wear sheet as
    // its own id.
    "progress-linear" -> {
      val progress = catalogOverrideFloat("progress", 0.6f)
      Box(Modifier.width(220.dp)) { LinearProgressIndicator(progress = { progress }) }
    }
    "badge" -> Badge { Text(catalogOverrideInt("count", 8).toString()) }

    // Text field — the sheet's only text state, seeded from the `value` knob so a visitor can type
    // and an un-typed render shows the seeded text.
    "textfield-filled" ->
      StatefulTextField(
        catalogOverrideString("value", stringResource(Res.string.label_filled)),
        catalogOverrideString("label", stringResource(Res.string.textfield_label)),
      )

    // States — pressed and focused are plain buttons; the render harness supplies the state through
    // `@FocusedPreview` (real focus traversal and pointer press) on the sticker previews in
    // `:samples:design-catalog-m3`, so a live lane shows it when actually focused or pressed.
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
    // Content axis: the filled button with a leading icon + label. The icon is an inline
    // `ImageVector` (no icon library here); `Icon` tints it with the button's content color.
    "button-filled-icon-label" -> {
      val (label, onClick) =
        counted(catalogOverrideString("label", stringResource(Res.string.label_filled)))
      Button(onClick = onClick) {
        Icon(addGlyph, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
      }
    }

    // Text options — maxLines + ellipsis. 128dp matches the Android sticker's 160dp canvas minus
    // 2×16dp padding, so the baked frame is unchanged.
    "text-maxlines-truncated" ->
      Box(Modifier.width(128.dp)) {
        Text(
          catalogOverrideString("text", stringResource(Res.string.m3_body_overflow)),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    // `genericFontFamily(...)` rather than `FontFamily.Serif`/`.Monospace`, so the desktop render
    // and wasm tier substitute the URL-loaded copy of the font the platform would resolve.
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
    // `namedFontFamily(...)` rather than a `GoogleFont` provider, so desktop and wasm resolve the
    // vendored Orbitron faces; falls back to the platform sans if not vendored.
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
 * A minimal plus glyph for `button-filled-icon-label`, hand-built because this module has no
 * `material-icons`. `Icon` recolors it, so the fill is irrelevant.
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

// State holders: every control takes its initial value as an argument (the seeded knob), so the
// first frame is the seeded state and a real click moves it from there.

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

/** The slider, seeded from the `value` knob. */
@Composable
fun StatefulSlider(initial: Float) {
  var value by remember { mutableFloatStateOf(initial) }
  Slider(value = value, onValueChange = { value = it })
}

/**
 * Material expressive shape interpolation, shared by the desktop preview and the wasm catalog. The
 * first frame is pinned to the midpoint for a deterministic screenshot. [Morph] takes
 * [androidx.graphics.shapes.RoundedPolygon] endpoints, which keep the feature information needed
 * for a stable match.
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
 * Gives a button something visible to do when clicked, by tallying clicks into its label: `Filled`
 * → `Filled (1)` → `Filled (2)`. Returns the label and `onClick`. At `0` it draws [base] verbatim,
 * so any render nothing has clicked is unchanged.
 */
@Composable
fun counted(base: String): Pair<String, () -> Unit> {
  var clicks by remember { mutableIntStateOf(0) }
  return (if (clicks == 0) base else "$base ($clicks)") to { clicks++ }
}

// Don't add held interaction sources here: a forged interaction documents the state layer, not the
// component. If a sticker needs a state real input can't reach, add a capture mechanism to the
// renderer instead.
