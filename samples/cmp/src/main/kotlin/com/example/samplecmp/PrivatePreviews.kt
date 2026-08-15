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
 * `private` CMP `@Preview`s — the shape that used to fail the standalone Desktop renderer.
 *
 * Nothing but tooling ever calls a preview, so declaring one `private` is idiomatic and Android
 * Studio draws it happily. It compiles to a `private static final` method on `PrivatePreviewsKt`:
 * the renderer's `getDeclaredComposableMethod` lookup finds it (it scans `declaredMethods`), but
 * the reflective invoke that follows threw `IllegalAccessException: ComposableMethod cannot access
 * … with modifiers "private static final"` until the desktop renderer opened the method the way the
 * daemon and the Android renderer already did (issue #3873). `compose-preview serve --module`
 * bootstraps through this very renderer, so one private preview anywhere in a module could take the
 * whole server down before its (capable) daemon ever started.
 *
 * These live in the sample so the standing render pipeline covers private previews from now on: the
 * module's `composePreviewRenderAll` draws them on every run, [PrivatePreviewRenderTest] asserts
 * the PNGs landed, and the CI visual-diff bot picks the images up like any other sample preview. No
 * sample exercised a private `@Preview` before, which is exactly how the gap shipped.
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
