package com.example.samplecmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The **secondary override format**: the same editable list as [OverridableListPreview], with its
 * knobs declared as the preview function's own defaulted value parameters instead of
 * `previewOverride*` lookups in the body.
 *
 * Read the two files side by side — that comparison is what this sample is for.
 *
 * |                            |`previewOverride*`                |parameters                       |
 * |----------------------------|----------------------------------|---------------------------------|
 * |declared by                 |executing a lookup while composing|the function signature           |
 * |enumerable without rendering|no                                |**yes**                          |
 * |exhaustive                  |only where hand-wired             |**every parameter**              |
 * |types                       |eight hand-rolled kinds           |the Kotlin type system           |
 * |default                     |an argument to the lookup         |the default expression, in source|
 * |body                        |carries a harness call per knob   |**plain Compose**                |
 *
 * The last row is the one to look at. There is no import from this project below, no controller and
 * no knob call: this is exactly the code a developer would write, which is why the playground can
 * publish it as a runnable snippet without rewriting anything out of it first.
 *
 * **It renders unchanged with no daemon.** Every parameter declares a default, which is already a
 * supported preview shape — the renderer invokes with no arguments and `ComposableMethod` fills
 * each one from Kotlin's synthetic `$default` bridge. A daemon seeds a *subset* by passing an
 * argument array with `null` in the positions it is not seeding, so an unseeded knob still takes
 * its author default. That is the property `DesktopKnobRendererTest` pins in pixels.
 *
 * **What it does not cover yet.** `Color` and `Dp` are not seedable kinds in this first cut, so the
 * accent is a plain `Long` ARGB and the row padding an `Int` of dp — honest about the current
 * boundary rather than hiding it. Nor is there an equivalent of the *indexed* knob
 * (`previewOverrideString(..., index = i)`) that gives each row its own value: a parameter list is
 * fixed-arity and a per-row value is not, so `rowLabelPrefix` numbers the rows instead. Both are
 * real gaps in the format, and both are why `previewOverride*` stays supported rather than being
 * replaced.
 */
@Preview(name = "Parameter Knob List", showBackground = true)
@Composable
fun ParameterKnobListPreview(
  /** The list's heading. */
  title: String = "Shopping list",
  /** Heading colour, as ARGB — `Color` is not a seedable knob kind yet. */
  accentArgb: Long = 0xFF3366FF,
  /** How many rows to draw. */
  itemCount: Int = 3,
  /** Vertical padding inside each row, in dp. */
  rowPaddingDp: Int = 12,
  /** Each row is this followed by its number. */
  rowLabelPrefix: String = "Item",
) {
  Surface {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        // `Color(Int)` reads its argument as packed ARGB, which is what the knob carries.
        color = Color(accentArgb.toInt()),
      )
      repeat(itemCount) { i ->
        Card(modifier = Modifier.fillMaxWidth()) {
          Text("$rowLabelPrefix ${i + 1}", modifier = Modifier.padding(rowPaddingDp.dp))
        }
      }
    }
  }
}

/**
 * The knob kinds are open sets — any string, any int. Some parameters are not: a component's
 * emphasis is one of three things and nothing else. [Emphasis] is that closed set, and declaring
 * the knob as an `enum class` is what lets a viewer draw a **picker** rather than a text box.
 *
 * The distinction is the whole reason the kind exists. A text box shows the current value and hides
 * every alternative, so `Outlined` is reachable only by someone who has read the source — which is
 * why `previewOverrideChoice` has always been able to say "these and no others", and why, until the
 * enum kind, migrating one of those to a parameter knob silently downgraded its control.
 */
enum class Emphasis {
  Filled,
  Tonal,
  Outlined,
}

/**
 * A closed-set knob beside an open one, so the two controls can be compared in the same panel:
 * [emphasis] renders as a picker of exactly three values, [label] as a free text field.
 *
 * The seed crosses as the constant's **name** — `Enum.valueOf`'s own currency — and becomes the
 * constant at the renderer's invoke seam, which is the first point that holds the enum's `Class`. A
 * name that is not one of the constants is dropped and the author default renders, which is the
 * honest answer to a stale client asking for a constant the enum no longer has.
 */
@Preview(name = "Parameter Knob Emphasis", showBackground = true)
@Composable
fun ParameterKnobEmphasisPreview(emphasis: Emphasis = Emphasis.Tonal, label: String = "Continue") {
  Surface {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(emphasis.name, style = MaterialTheme.typography.labelMedium)
      when (emphasis) {
        Emphasis.Filled -> Button(onClick = {}) { Text(label) }
        Emphasis.Tonal -> FilledTonalButton(onClick = {}) { Text(label) }
        Emphasis.Outlined -> OutlinedButton(onClick = {}) { Text(label) }
      }
    }
  }
}
