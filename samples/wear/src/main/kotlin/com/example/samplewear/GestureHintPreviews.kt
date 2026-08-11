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
import androidx.wear.compose.material3.onehandedgesture.GestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicator
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.tooling.preview.devices.WearDevices
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions
import kotlinx.coroutines.launch

/**
 * A normal Wear media screen with **two** one-handed gestures: a primary double-pinch that toggles
 * play/pause, and a dismiss wrist-turn mapped to back. Each button wraps its **content** (the label)
 * in [OneHandedGestureClickIndicator], so the public Wear API swaps the label for that gesture's
 * animation on-device while the button pill stays put.
 *
 * [showIndicators] is state injection rather than a second rendering implementation: previews can
 * capture a stable animation frame while production leaves it false and lets
 * `onGestureAvailable` drive the exact same public indicator state.
 */
@Composable
fun MediaGestureScreen(showIndicators: Boolean = false, onDismiss: () -> Unit = {}) {
  var playing by remember { mutableStateOf(false) }
  val playSource = remember { MutableInteractionSource() }
  val backSource = remember { MutableInteractionSource() }
  val playConfiguration =
    rememberGestureConfiguration(GestureAction.Primary, key = "samplewear:media-play")
  val playIndicatorState = rememberGestureIndicatorState(forceShow = showIndicators)
  val backConfiguration =
    rememberGestureConfiguration(GestureAction.Dismiss, key = "samplewear:media-back")
  val backIndicatorState = rememberGestureIndicatorState(forceShow = showIndicators)
  val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
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
          Modifier.oneHandedGesture(
            gestureConfiguration = playConfiguration,
            interactionSource = playSource,
            onGestureLabel = if (playing) "Pause" else "Play",
            onGestureAvailable = {
              coroutineScope.launch { playIndicatorState.showIndicator() }
            },
          ) {
            playing = !playing
          },
      ) {
        OneHandedGestureClickIndicator(playConfiguration, playIndicatorState) {
          Text(if (playing) "Pause" else "Play")
        }
      }
      // Dismiss — wrist turn.
      FilledTonalButton(
        onClick = onDismiss,
        interactionSource = backSource,
        modifier =
          Modifier.oneHandedGesture(
            gestureConfiguration = backConfiguration,
            interactionSource = backSource,
            onGestureLabel = "Back",
            onGestureAvailable = {
              coroutineScope.launch { backIndicatorState.showIndicator() }
            },
          ) {
            onDismiss()
          },
      ) {
        OneHandedGestureClickIndicator(backConfiguration, backIndicatorState) {
          Text("Back")
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// The same full-screen component, captured with its public indicator states at rest and forced on.
// ---------------------------------------------------------------------------

/** The resting screen — no override, so both gesture hints stay hidden (as they would off-watch). */
@Preview(name = "Media — hints off", device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
fun MediaGestureScreenPreview() {
  MaterialTheme { MediaGestureScreen() }
}

/** The same component with its state override showing both inline indicators. */
@Preview(name = "Media — hints on", device = WearDevices.LARGE_ROUND, showBackground = true)
@RoboComposePreviewOptions(
  manualClockOptions = [ManualClockOptions(advanceTimeMillis = 800L)]
)
@Composable
fun MediaGestureScreenHintPreview() {
  MaterialTheme { MediaGestureScreen(showIndicators = true) }
}
