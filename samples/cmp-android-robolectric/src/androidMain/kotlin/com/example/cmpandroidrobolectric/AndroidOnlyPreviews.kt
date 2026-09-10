package com.example.cmpandroidrobolectric

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Reads `android.os.Build` — the point of the fixture. A composable like this compiles only for
 * Android and can only be rendered against a real `android.jar`, so it draws on the Robolectric
 * lane and nowhere else. The Desktop renderer fails it with `NoClassDefFoundError: android/os/Build`.
 */
@Composable
fun ApiLevelBadge(modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    androidx.compose.foundation.layout.Box(
      modifier = Modifier.size(24.dp).background(Color(0xFF3DDC84))
    )
    Text("API ${Build.VERSION.SDK_INT}")
  }
}

@Preview
@Composable
fun ApiLevelBadgePreview() {
  ApiLevelBadge()
}
