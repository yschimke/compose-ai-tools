package com.example.agp8min

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// One @Preview is enough — the integration job only verifies that
// `discoverPreviews` finds it and writes `previews.json`. Adding a second
// preview just to "cover edge cases" is wasted CI time; the in-repo
// sample-android module exercises every renderer feature already.
@Preview
@Composable
fun GreetingPreview() {
  Surface { Text(text = "agp8-min fixture") }
}
