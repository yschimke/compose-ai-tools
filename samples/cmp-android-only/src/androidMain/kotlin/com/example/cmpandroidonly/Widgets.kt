package com.example.cmpandroidonly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Deliberately NO `@Preview` — this fixture mirrors the consumer's `:meshcore-mobile`, a
// non-UI / preview-less `com.android.kotlin.multiplatform.library` module. It still pulls
// `*-android` Compose onto `androidRuntimeClasspath` (the 12-variant shape that trips #1852),
// but discovery finds zero previews, so the desktop pipeline must skip it fail-soft AND must
// keep discovering the renderable sample modules (#1855).
@Composable
fun AndroidOnlyWidget() {
  Box(modifier = Modifier.size(48.dp).background(Color.Magenta)) { Text("android-only") }
}
