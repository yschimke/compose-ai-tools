package com.example.sampleandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The sample app's launcher activity — the screen behind the `activity__MainActivity` hero image
 * and the start of the `getting-started` app tour (`compose-previews/tours/getting-started.json`).
 * The "Open Now Playing" button navigates to [NowPlayingActivity] via a real `startActivity`, which
 * is exactly what the tour's click step exercises.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          HomeScreen(
            onOpenNowPlaying = { startActivity(Intent(this, NowPlayingActivity::class.java)) }
          )
        }
      }
    }
  }
}

@Composable
fun HomeScreen(onOpenNowPlaying: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(text = "Sample Android", style = MaterialTheme.typography.headlineMedium)
    Text(
      text = "A tiny two-screen app used to exercise app-level previews and tours.",
      style = MaterialTheme.typography.bodyMedium,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(text = "Continue listening", style = MaterialTheme.typography.titleMedium)
        Text(
          text = "Midnight City — M83",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenNowPlaying) { Text("Open Now Playing") }
      }
    }
  }
}
