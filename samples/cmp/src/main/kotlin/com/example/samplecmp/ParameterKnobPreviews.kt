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
import ee.schimke.composeai.preview.KnobValue

/**
 * The secondary override format: the same editable list as [OverridableListPreview], with knobs
 * declared as the preview function's own defaulted value parameters instead of `previewOverride*`
 * lookups. Compare the two files side by side.
 *
 * |                            |`previewOverride*`                |parameters
 * |
 * |----------------------------|----------------------------------|---------------------------------|
 * |declared by                 |executing a lookup while composing|the function signature
 * | |enumerable without rendering|no                                |**yes**
 * | |exhaustive                  |only where hand-wired             |**every parameter**
 * | |types                       |eight hand-rolled kinds           |the Kotlin type system
 * | |default                     |an argument to the lookup         |the default expression, in
 * source| |body                        |carries a harness call per knob   |**plain Compose**
 * |
 *
 * The body is plain Compose, publishable as a runnable snippet. It renders unchanged with no daemon
 * (the `$default` bridge fills every parameter); a daemon seeds a subset by passing `null` in the
 * other positions (`DesktopKnobRendererTest` pins this).
 *
 * Gaps: `Color` and `Dp` aren't seedable kinds yet (hence `Long` ARGB and `Int` dp), and there is
 * no per-row indexed knob, which is why `previewOverride*` stays supported.
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
 * A closed-set knob: declaring it as an `enum class` lets a viewer draw a picker rather than a text
 * box.
 *
 * `@KnobValue` makes migrating from `previewOverrideChoice` a drop-in: existing
 * `@OverrideVariant(strings = ["style=tonal"])` seeds and design-kit mappings keep their lowercase
 * vocabulary, including values that aren't valid identifiers (`extra-large`, `0.0`).
 */
enum class Emphasis {
  @KnobValue("filled") Filled,
  @KnobValue("tonal") Tonal,
  @KnobValue("outlined") Outlined,
}

/**
 * A closed-set knob ([emphasis], a picker) beside an open one ([label], a text field). The seed
 * crosses as the constant's name and is converted at the renderer's invoke seam; an unknown name
 * falls back to the author default.
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
