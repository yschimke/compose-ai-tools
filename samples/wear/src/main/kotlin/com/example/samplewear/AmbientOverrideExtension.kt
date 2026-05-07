package com.example.samplewear

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import androidx.wear.compose.foundation.LocalAmbientModeManager
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Composable extension that owns the ambient state for its [content] subtree and exposes it
 * through [LocalAmbientModeManager] — the same composition-local
 * `androidx.wear.compose.foundation.samples.AmbientModeBasicSample` reads from
 * `rememberAmbientModeManager()`.
 *
 * The extension supports three roles in one place:
 *
 * - **Query.** [content] reads the current state via `LocalAmbientModeManager.current
 *   ?.currentAmbientMode`.
 * - **Override.** Callers seed the state with [initial] and adjust it through the [override] handle
 *   passed to [content]. This is the seam previews use to render under a deterministic state and
 *   recording sessions use to drive `renderNow.overrides.ambient` end-to-end.
 * - **Wake on interaction.** Touch and rotary-scroll input flips the state back to
 *   [AmbientMode.Interactive] — matching the AOSP `AmbientLifecycleObserver` wake semantics
 *   (touch click / pointer-down, RSB rotary scroll). `pointerMove` / `pointerUp` are intentionally
 *   ignored so a multi-pointer drag inside ambient mode doesn't flip state on its own intermediate
 *   events.
 */
@Composable
fun AmbientOverrideExtension(
  initial: AmbientMode = AmbientMode.Interactive,
  content: @Composable (override: AmbientOverrideHandle) -> Unit,
) {
  var ambientMode by remember { mutableStateOf(initial) }
  // If the caller swaps `initial` (e.g. a recording session changes the override mid-flight)
  // re-seed the state so the subtree reflects the new requested mode.
  DisposableEffect(initial) {
    ambientMode = initial
    onDispose {}
  }

  val manager = remember {
    object : AmbientModeManager {
      override val currentAmbientMode: AmbientMode
        get() = ambientMode

      override suspend fun withAmbientTick(block: () -> Unit) {
        // No system tick under previews / recordings — suspend forever, the Compose runtime
        // cancels the coroutine when [content] leaves composition or the state flips back to
        // Interactive (callers gate their LaunchedEffect on `currentAmbientMode is Ambient`,
        // matching AmbientTickEffect).
        suspendCancellableCoroutine<Unit> {}
        block()
      }
    }
  }

  val handle = remember {
    object : AmbientOverrideHandle {
      override fun set(mode: AmbientMode) {
        ambientMode = mode
      }
    }
  }

  CompositionLocalProvider(LocalAmbientModeManager provides manager) {
    Box(
      modifier =
        Modifier.pointerInput(Unit) {
            // Activating gestures only — flip back to Interactive on the down edge so a
            // single tap wakes immediately. We don't consume the event; downstream handlers
            // see it unchanged.
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.pressed && it.previousPressed.not() }) {
                  ambientMode = AmbientMode.Interactive
                }
              }
            }
          }
          .onRotaryScrollEvent {
            ambientMode = AmbientMode.Interactive
            false
          }
    ) {
      content(handle)
    }
  }
}

/** Imperative handle supplied to [AmbientOverrideExtension]'s [content] for explicit overrides. */
interface AmbientOverrideHandle {
  fun set(mode: AmbientMode)
}
