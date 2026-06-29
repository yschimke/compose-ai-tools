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
import ee.schimke.composeai.overrides.previewOverrideColor
import ee.schimke.composeai.overrides.previewOverrideInt
import ee.schimke.composeai.overrides.previewOverrideString

/**
 * Demonstrates the opt-in named-override surface (`previewOverride*`): a whole-screen list whose
 * **title**, **accent colour**, **item count**, and **per-row label** are all editable. The item
 * count is an ordinary int knob fed into `repeat(...)`; the row label is an *indexed* knob so each
 * row gets its own editable value. A daemon (or a served bundle) can seed replacements for any of
 * these and the declared set travels in the bundle as `previews/<id>.overrides.json`.
 */
@Preview(name = "Overridable List", showBackground = true)
@Composable
fun OverridableListPreview() {
  val title = previewOverrideString("title", default = "Shopping list")
  val accent = previewOverrideColor("accent", default = Color(0xFF3366FF))
  val itemCount = previewOverrideInt("itemCount", default = 3)

  Surface {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge, color = accent)
      repeat(itemCount) { i ->
        val label = previewOverrideString("rowLabel", default = "Item ${i + 1}", index = i)
        Card(modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.padding(12.dp)) }
      }
    }
  }
}
