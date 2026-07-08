package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.PreviewWrapperClass

/**
 * Multi-preview meta-annotation that fans a preview out to light + dark **and** installs
 * [FontPreviewWrapper] via `@PreviewWrapperClass`, so the wrapped body renders in the downloaded
 * Lobster Two default without any per-function `@PreviewWrapper` line.
 *
 * This is the "reuse a preview wrapper in a multi-preview" shape. androidx's `@PreviewWrapper` is
 * `@Target(FUNCTION)`-only, so it can't ride on an annotation class; our [PreviewWrapperClass]
 * (which also targets `ANNOTATION_CLASS`) can. Discovery hoists the wrapper onto every `@Preview`
 * this annotation expands to — see `PreviewDiscovery.extractWrapperFqn`.
 *
 * Tag any composable with `@FontPreview` and it gets both variants, each rendered through the font
 * wrapper — no theme wiring, no repeated wrapper annotation.
 */
@Preview(
  name = "Light",
  uiMode = Configuration.UI_MODE_NIGHT_NO,
  showBackground = true,
  widthDp = 360,
)
@Preview(
  name = "Dark",
  uiMode = Configuration.UI_MODE_NIGHT_YES,
  showBackground = true,
  widthDp = 360,
)
@PreviewWrapperClass("com.example.sampleandroid.FontPreviewWrapper")
annotation class FontPreview

/**
 * Showcase for [FontPreviewWrapper] applied through the [FontPreview] multi-preview annotation. The
 * body carries **no** font wiring — every line inherits Lobster Two from the wrapper, both when a
 * style comes from `MaterialTheme.typography` and when `Text` falls back to `LocalTextStyle`.
 *
 * If the wrapper ever fails to load (or the annotation reuse regresses), these lines render in the
 * platform sans-serif instead of the script face, so the diff is unmistakable.
 */
@FontPreview
@Composable
fun FontWrapperShowcasePreview() {
  Column(modifier = Modifier.padding(16.dp)) {
    Text(text = "Display — MaterialTheme.typography", style = MaterialTheme.typography.displaySmall)
    Spacer(modifier = Modifier.size(8.dp))
    Text(
      text = "Headline — inherited default font",
      style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
      text = "Body picks up the wrapper's typography role.",
      style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(modifier = Modifier.size(8.dp))
    // No explicit style at all — resolves through LocalTextStyle, which the wrapper also seeds.
    Text(text = "Plain Text() with no style — still Lobster Two.")
  }
}
