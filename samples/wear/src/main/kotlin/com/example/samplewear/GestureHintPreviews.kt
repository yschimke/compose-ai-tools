package com.example.samplewear

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import ee.schimke.composeai.daemon.GestureHint
import ee.schimke.composeai.daemon.GestureType
import ee.schimke.composeai.daemon.reportedOneHandedGesture
import ee.schimke.composeai.preview.GestureHintPreview

/**
 * A normal Wear media screen with **two** one-handed gestures: a primary double-pinch that toggles
 * play/pause, and a dismiss wrist-turn mapped to back. Each button wraps its **content** (the label)
 * in `GestureHint`, so the real `OneHandedGestureIndicator` swaps the label for that gesture's
 * animation on-device while the button pill stays put — the design guide's on-button hint.
 *
 * Crucially this is **ordinary app code**: there is no preview-only flag, no capture mode, nothing
 * that "sets up" a hint. Each `GestureHint` reads the connector's force-show state, so whether the
 * hints appear is decided entirely from *outside* the screen — by the daemon's
 * `renderNow.overrides.gestures.showHints`, or, for a static `@Preview`, by the `@GestureHintPreview`
 * annotation below. Because the override flips one shared state, **both** hints show together, which
 * is what a full-screen preview wants: every gesture the screen offers, illustrated at once.
 */
@Composable
fun MediaGestureScreen(onDismiss: () -> Unit = {}) {
  var playing by remember { mutableStateOf(false) }
  val playSource = remember { MutableInteractionSource() }
  val backSource = remember { MutableInteractionSource() }
  ScreenScaffold {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
      // Primary — double pinch.
      Button(
        onClick = { playing = !playing },
        interactionSource = playSource,
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.PRIMARY,
            label = if (playing) "Pause" else "Play",
            interactionSource = playSource,
          ) {
            playing = !playing
          },
      ) {
        GestureHint(type = GestureType.PRIMARY, interactionSource = playSource) {
          Text(if (playing) "Pause" else "Play")
        }
      }
      // Dismiss — wrist turn.
      FilledTonalButton(
        onClick = onDismiss,
        interactionSource = backSource,
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.DISMISS,
            label = "Back",
            interactionSource = backSource,
          ) {
            onDismiss()
          },
      ) {
        GestureHint(type = GestureType.DISMISS, interactionSource = backSource) { Text("Back") }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// The same full-screen screen, captured hint-off and hint-on. The screen
// definition is identical for both — only the `@GestureHintPreview` annotation
// differs, which force-shows the indicators through `GestureOverrideExtension` at
// render time. (Daemon-driven `renderNow.overrides.gestures.showHints = true`
// reaches the same seam live, with no annotation at all.)
// ---------------------------------------------------------------------------

/** The resting screen — no override, so both gesture hints stay hidden (as they would off-watch). */
@Preview(name = "Media — hints off", device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
fun MediaGestureScreenPreview() {
  MaterialTheme { MediaGestureScreen() }
}

/** The same screen with `@GestureHintPreview` force-showing both hints — no change to the screen. */
@Preview(name = "Media — hints on", device = WearDevices.LARGE_ROUND, showBackground = true)
@GestureHintPreview
@Composable
fun MediaGestureScreenHintPreview() {
  MaterialTheme { MediaGestureScreen() }
}
