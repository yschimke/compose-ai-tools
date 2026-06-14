package com.example.cmpandroidonly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// An `androidMain`-only @Preview on a module with no `jvm("desktop")` target. The desktop
// renderer can't drive it (the deps are `*-android` Compose), so the plugin must SKIP this
// module fail-soft — without breaking discovery of the renderable sample modules (#1855).
@Preview
@Composable
fun AndroidOnlyPreview() {
  Box(modifier = Modifier.size(48.dp).background(Color.Magenta)) { Text("android-only") }
}
