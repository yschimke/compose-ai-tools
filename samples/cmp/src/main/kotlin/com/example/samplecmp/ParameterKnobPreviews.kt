package com.example.samplecmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
