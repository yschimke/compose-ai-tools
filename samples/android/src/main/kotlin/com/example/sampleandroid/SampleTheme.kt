package com.example.sampleandroid

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The sample app's screens' theme: Material 3's dark scheme while the system's dark theme is on
 * (`cmd uimode night yes`, the simulator's `qualifiers +night`, Android Studio's Dark Theme), its
 * light one otherwise. The activities' window theme, `Theme.Sample`, switches with it.
 */
@Composable
fun SampleTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    content = content,
  )
}
