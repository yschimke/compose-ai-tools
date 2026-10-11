package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

/**
 * `private` CMP `@Preview`s: idiomatic, but they compile to `private static final` methods the
 * desktop renderer must open before invoking reflectively. Covered by the standing render pipeline
 * and [PrivatePreviewRenderTest].
 */
@Preview(name = "Private badge", backgroundColor = 0xFFFFFFFF, showBackground = true)
@Composable
private fun PrivateBadgePreview() {
  MaterialTheme {
    Box(modifier = Modifier.padding(16.dp)) {
      Box(
        modifier =
          Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)).background(Color(0xFF6750A4)),
        contentAlignment = Alignment.Center,
      ) {
        Text(text = "private", color = Color.White, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/** Rows for [PrivateTonePreview]. `label` is what the fan-out filename suffix is derived from. */
internal data class PrivateTone(val label: String, val color: Long)

/**
 * A `private` provider as well: it compiles to a package-private JVM class whose nullary
 * constructor and `getValues()` both need opening before they can be called from the renderer's
 * package. Serve derives a preview's row entries from this fan-out's PNGs, so a row that fails to
 * render is a row missing from the catalog.
 */
private class PrivateToneProvider : PreviewParameterProvider<PrivateTone> {
  override val values: Sequence<PrivateTone> =
    sequenceOf(PrivateTone("Indigo", 0xFF3F51B5), PrivateTone("Moss", 0xFF4C7A3F))
}

@Preview(name = "Private tone", backgroundColor = 0xFFFFFFFF, showBackground = true)
@Composable
private fun PrivateTonePreview(@PreviewParameter(PrivateToneProvider::class) tone: PrivateTone) {
  MaterialTheme {
    Box(modifier = Modifier.padding(16.dp)) {
      Box(
        modifier =
          Modifier.size(160.dp, 80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(tone.color)),
        contentAlignment = Alignment.Center,
      ) {
        Text(text = tone.label, color = Color.White, style = MaterialTheme.typography.titleMedium)
      }
    }
  }
}
