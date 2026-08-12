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
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.overrides.previewOverrideChoice
import ee.schimke.composeai.overrides.previewOverrideColor
import ee.schimke.composeai.overrides.previewOverrideInt
import ee.schimke.composeai.overrides.previewOverrideString

/**
 * Demonstrates the opt-in named-override surface (`previewOverride*`): a whole-screen list whose
 * **title**, **accent colour**, **item count**, **density**, and **per-row label** are all
 * editable. The item count is an ordinary int knob fed into `repeat(...)`; the row label is an
 * *indexed* knob so each row gets its own editable value. A daemon (or a served bundle) can seed
 * replacements for any of these and the declared set travels in the bundle as
 * `previews/<id>.overrides.json`.
 *
 * **Density is a `previewOverrideChoice`** — a knob whose value set is closed, so a viewer renders
 * a picker over the declared values instead of a text field. That is the difference between having
 * to know a knob spells its values `compact`/`cosy`/`comfortable` and being able to see them; the
 * labels shown are the option labels, while the wire value stays the slug the composable reads.
 */
@Preview(name = "Overridable List", showBackground = true)
@Composable
fun OverridableListPreview() {
  val title = previewOverrideString("title", default = "Shopping list")
  val accent = previewOverrideColor("accent", default = Color(0xFF3366FF))
  val itemCount = previewOverrideInt("itemCount", default = 3)
  val density =
    previewOverrideChoice(
      "density",
      default = "cosy",
      options =
        listOf(
          PreviewOverrideOption("compact", "Compact"),
          PreviewOverrideOption("cosy", "Cosy"),
          PreviewOverrideOption("comfortable", "Comfortable"),
        ),
    )
  val rowPadding =
    when (density) {
      "compact" -> 4.dp
      "comfortable" -> 20.dp
      else -> 12.dp
    }

  Surface {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge, color = accent)
      repeat(itemCount) { i ->
        val label = previewOverrideString("rowLabel", default = "Item ${i + 1}", index = i)
        Card(modifier = Modifier.fillMaxWidth()) {
          Text(label, modifier = Modifier.padding(rowPadding))
        }
      }
    }
  }
}
