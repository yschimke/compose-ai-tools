package com.example.samplewear

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import ee.schimke.composeai.daemon.GestureHint
import ee.schimke.composeai.daemon.GestureType
import ee.schimke.composeai.daemon.reportedOneHandedGesture
import ee.schimke.composeai.preview.GestureHintPreview

/**
 * A normal Wear media screen — the double-pinch primary action toggles play/pause, and the
 * `GestureHint` wraps the button so the real `OneHandedGestureIndicator` can flash on-device.
 *
 * Crucially this is **ordinary app code**: there is no preview-only flag, no capture mode, nothing
 * that "sets up" a hint. `GestureHint` reads the connector's force-show state, so whether the hint
 * appears is decided entirely from *outside* the screen — by the daemon's
 * `renderNow.overrides.gestures.showHints`, or, for a static `@Preview`, by the `@GestureHintPreview`
 * annotation below. On a real watch the hint plays from the sensor pipeline instead.
 */
@Composable
fun MediaGestureScreen() {
  var playing by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  ScreenScaffold {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      GestureHint(type = GestureType.PRIMARY, interactionSource = interactionSource) {
        Button(
          onClick = { playing = !playing },
          interactionSource = interactionSource,
          modifier =
            Modifier.reportedOneHandedGesture(
              type = GestureType.PRIMARY,
              label = if (playing) "Pause" else "Play",
              interactionSource = interactionSource,
            ) {
              playing = !playing
            },
        ) {
          Text(if (playing) "Pause" else "Play")
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// The same screen, captured hint-off and hint-on. The screen definition is
// identical for both — only the `@GestureHintPreview` annotation differs, which
// force-shows the indicator through `GestureOverrideExtension` at render time.
// (Daemon-driven `renderNow.overrides.gestures.showHints = true` reaches the same
// seam live, with no annotation at all.)
// ---------------------------------------------------------------------------

/** The resting screen — no override, so the gesture hint stays hidden (as it would off-watch). */
@Preview(name = "Media — hint off", device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
fun MediaGestureScreenPreview() {
  MaterialTheme { MediaGestureScreen() }
}

/** The same screen with `@GestureHintPreview` force-showing the hint — no change to the screen. */
@Preview(name = "Media — hint on", device = WearDevices.LARGE_ROUND, showBackground = true)
@GestureHintPreview
@Composable
fun MediaGestureScreenHintPreview() {
  MaterialTheme { MediaGestureScreen() }
}
