package com.example.samplecmp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * `@Preview(fontScale = ...)` coverage for the Desktop/CMP pipeline. The body sizes its text in
 * `sp` (Material 3 typography), so a non-default `fontScale` visibly enlarges the rendered text —
 * the standalone `DesktopRendererMain` used to drop the annotation's `fontScale` on the floor, so
 * the 1.0x / 1.5x / 2.0x captures came out identical. Rendered side by side these now differ, which
 * is also what the visual-diff harness keys on for regressions.
 */
@Composable
private fun FontScaleSample() {
  Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
    Text("Headline", style = MaterialTheme.typography.headlineSmall)
    Text("Body text scales with fontScale", style = MaterialTheme.typography.bodyMedium)
    Text("Caption", style = MaterialTheme.typography.labelSmall)
  }
}

@Preview(name = "Font scale 1.0x", showBackground = true, fontScale = 1.0f)
@Composable
fun FontScale100Preview() {
  FontScaleSample()
}

@Preview(name = "Font scale 1.5x", showBackground = true, fontScale = 1.5f)
@Composable
fun FontScale150Preview() {
  FontScaleSample()
}

@Preview(name = "Font scale 2.0x", showBackground = true, fontScale = 2.0f)
@Composable
fun FontScale200Preview() {
  FontScaleSample()
}
