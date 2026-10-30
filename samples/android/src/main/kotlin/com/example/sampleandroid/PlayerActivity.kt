package com.example.sampleandroid

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A video player that goes into picture-in-picture: on HOME by itself (`setAutoEnterEnabled`), or
 * from its button (`enterPictureInPictureMode`). In picture-in-picture it shows only the video, as
 * `onPictureInPictureModeChanged` tells it. What the simulator's PiP is shown with; started with
 * `startActivity`.
 */
class PlayerActivity : ComponentActivity() {
  private val inPip = mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setPictureInPictureParams(params())
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
          if (inPip.value) {
            Video(Modifier.fillMaxSize())
          } else {
            Column(
              modifier = Modifier.fillMaxSize().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              Video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
              Text(
                "Midnight City — live",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
              )
              Button(onClick = { enterPictureInPictureMode(params()) }) {
                Text("Picture-in-picture")
              }
            }
          }
        }
      }
    }
  }

  override fun onPictureInPictureModeChanged(
    inPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
    inPip.value = inPictureInPictureMode
  }

  private fun params(): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true)
    return builder.build()
  }
}

@androidx.compose.runtime.Composable
private fun Video(modifier: Modifier) {
  Box(
    modifier =
      modifier.background(Brush.linearGradient(listOf(Color(0xFF3949AB), Color(0xFFD81B60)))),
    contentAlignment = Alignment.Center,
  ) {
    Text("▶ 01:24", color = Color.White, style = MaterialTheme.typography.headlineSmall)
  }
}
