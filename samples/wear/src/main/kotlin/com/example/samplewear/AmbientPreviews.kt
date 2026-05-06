package com.example.samplewear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.google.android.horologist.compose.ambient.AmbientAware
import com.google.android.horologist.compose.ambient.AmbientState

/**
 * Ambient-aware Wear OS preview. Wraps the watchface body in horologist's [AmbientAware] — the
 * de-facto helper that drives `androidx.wear.ambient.AmbientLifecycleObserver` under the hood. In
 * a preview render the connector's `ShadowAmbientLifecycleObserver` (see
 * `:data-ambient-connector`) consults [AmbientStateController][ee.schimke.composeai.daemon.AmbientStateController]
 * so the body's `state` parameter reflects whichever override the daemon's
 * `renderNow.overrides.ambient` set — `Inactive` for the unmodified `@Preview` path, `Ambient` /
 * `Interactive` when an override is in flight.
 *
 * The body records its own state transitions in a `mutableStateListOf` and surfaces the rolling
 * trail underneath the timestamp. With `mainClock.autoAdvance = false` (the default for renderNow)
 * the trail captures one entry per applied state during a single render — the trail itself
 * doubles as a regression sentinel: an unexpected `[Interactive]` after an `Ambient` override
 * pinpoints a regression in the controller / shadow / `AmbientAware` chain at a glance.
 */
@Composable
fun AmbientStatusBody(now: () -> Long = System::currentTimeMillis) {
  AmbientAware { state ->
    val transitions = remember { mutableStateListOf<String>() }
    LaunchedEffect(state) {
      // Snapshot every state delivery so the body itself records the
      // interactive ↔ ambient ↔ inactive transitions the daemon drove.
      snapshotFlow { state }
        .collect { snap -> transitions += snap.label() }
    }
    val isAmbient = state is AmbientState.Ambient
    val background = if (isAmbient) Color.Black else Color(0xFF101820)
    val textColor = if (isAmbient) Color(0xFFCFD8DC) else Color.White

    Box(
      modifier =
        Modifier.fillMaxSize().background(background).padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(text = state.label(), style = MaterialTheme.typography.titleMedium, color = textColor)
        Text(
          text = "t=${now()}",
          style = MaterialTheme.typography.labelSmall,
          color = textColor,
        )
        // Most recent few transitions, rendered newest-first. With paused-clock renders the list
        // contains the single "current" state; live-clock or recording sessions append entries on
        // every onEnter / onExit / onUpdate fan-out.
        transitions.takeLast(3).forEach { entry ->
          Text(
            text = "→ $entry",
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
          )
        }
      }
    }
  }
}

private fun AmbientState.label(): String =
  when (this) {
    is AmbientState.Ambient -> {
      val suffixes = buildList {
        if (burnInProtectionRequired) add("burnIn")
        if (deviceHasLowBitAmbient) add("lowBit")
      }
      if (suffixes.isEmpty()) "Ambient" else "Ambient(${suffixes.joinToString(",")})"
    }
    is AmbientState.Interactive -> "Interactive"
    is AmbientState.Inactive -> "Inactive"
  }

@Preview(name = "Ambient body — interactive", device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
fun AmbientStatusInteractivePreview() {
  AmbientStatusBody(now = { 1_700_000_000_000L })
}

@Preview(name = "Ambient body — ambient", device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
fun AmbientStatusAmbientPreview() {
  // Drives the connector controller directly so the static @Preview path renders under the
  // ambient state without going through the daemon's renderNow.overrides.ambient field.
  // Production uses the override; this preview gives Android Studio's preview pane a stable
  // ambient capture too.
  ee.schimke.composeai.daemon.AmbientStateController.set(
    ee.schimke.composeai.daemon.protocol.AmbientOverride(
      state = ee.schimke.composeai.daemon.protocol.AmbientStateOverride.AMBIENT,
      burnInProtectionRequired = true,
      deviceHasLowBitAmbient = false,
      updateTimeMillis = 1_700_000_000_000L,
    )
  )
  AmbientStatusBody(now = { 1_700_000_000_000L })
}
