package com.example.sampleandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The sample app's second screen, reached from [MainActivity]'s "Open Now Playing" button or via
 * the `sample://nowplaying` deep link declared in the manifest — the screen the `getting-started`
 * tour navigates to (by click and by deep-link intent) and back from.
 */
class NowPlayingActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          NowPlayingScreen(onClose = { finish() })
        }
      }
    }
  }
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(text = "Now Playing", style = MaterialTheme.typography.titleLarge)
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(24.dp))
          .background(
            Brush.linearGradient(listOf(Color(0xFF3730A3), Color(0xFF7C3AED), Color(0xFFDB2777)))
          )
    )
    Text(text = "Midnight City", style = MaterialTheme.typography.headlineSmall)
    Text(
      text = "M83 — Hurry Up, We're Dreaming",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onClose) { Text("Close") }
  }
}
