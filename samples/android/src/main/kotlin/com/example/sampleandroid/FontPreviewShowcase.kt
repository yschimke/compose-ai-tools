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
 * Multi-preview annotation: light + dark, each rendered through [FontPreviewWrapper]. androidx's
 * `@PreviewWrapper` can't target annotation classes, but our [PreviewWrapperClass] can, and
 * discovery hoists it onto every expanded `@Preview` (`PreviewDiscovery.extractWrapperFqn`).
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
 * Showcase for [FontPreview]: no font wiring in the body, so if the wrapper fails to apply, the
 * text renders in the platform sans instead of Lobster Two.
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
