package ee.schimke.composeai.daemon

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LocalContentColor

/**
 * How the Wear one-handed-gesture hint should appear in a **preview capture**.
 *
 * The real `OneHandedGestureIndicator` only flashes its hint in response to on-device sensor
 * interactions, and its show → play → hide coroutine settles during a Robolectric render's pre-roll —
 * so a plain `@Preview` never catches the hint mid-flight and an `@AnimatedPreview` catches only
 * identical, already-settled frames. [GestureHintShowcase] sidesteps that by compositing the *same
 * shipped indicator drawable* ([GestureIndicatorIcon]) over the target and driving its visibility
 * deterministically, so each capture mode yields a stable, reproducible image:
 * - [HIDDEN] — the target with no hint (the resting composable).
 * - [SHOWN] — the indicator frozen fully visible: a single still frame showing the hint.
 * - [ANIMATED] — the indicator's grow → shrink pulse driven as a Compose animation, captured as a
 *   GIF by `@AnimatedPreview`.
 */
enum class GestureHintCapture {
  HIDDEN,
  SHOWN,
  ANIMATED,
}

/**
 * Half-period of the [GestureHintCapture.ANIMATED] pulse, in milliseconds — the time the indicator
 * takes to grow from hidden to fully shown (it then shrinks back over the same span).
 */
const val GESTURE_HINT_PULSE_MS: Int = 700

/**
 * Full grow-then-shrink cycle of the [GestureHintCapture.ANIMATED] pulse. Pass this as the
 * `@AnimatedPreview(durationMs = …)` window: the underlying `InfiniteTransition` has no inherent
 * duration, so the capture needs an explicit span, and one full cycle shows the complete show → hide
 * motion without redundant repeats.
 */
const val GESTURE_HINT_PULSE_CYCLE_MS: Int = GESTURE_HINT_PULSE_MS * 2

/**
 * Renders wear-compose-material3's shipped gesture-indicator AVD
 * (`wear_one_handed_gesture_{primary,dismiss}_indicator_animation`) as a static, tinted icon via the
 * official `androidx.compose.animation.graphics` API — the same drawable the real
 * `OneHandedGestureIndicator` draws internally, shown at its resting frame so the gesture
 * illustration is visible in a still capture. `SCROLL` / `PAGE` ride the primary indicator, matching
 * the framework.
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun GestureIndicatorIcon(
  type: GestureType,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  tint: Color = LocalContentColor.current,
) {
  val resId =
    when (type) {
      GestureType.DISMISS ->
        androidx.wear.compose.material3.R.drawable
          .wear_one_handed_gesture_dismiss_indicator_animation
      else ->
        androidx.wear.compose.material3.R.drawable
          .wear_one_handed_gesture_primary_indicator_animation
    }
  val avd = AnimatedImageVector.animatedVectorResource(resId)
  Image(
    painter = rememberAnimatedVectorPainter(avd, atEnd = false),
    contentDescription = null,
    colorFilter = ColorFilter.tint(tint),
    modifier = modifier.size(size),
  )
}

/**
 * Overlays [content] with the shipped gesture-indicator hint, captured per [capture].
 *
 * This is the one-line seam for the three gesture-hint capture modes: render the same target three
 * ways by only flipping [capture].
 * - [GestureHintCapture.HIDDEN] captures the resting composable (no hint).
 * - [GestureHintCapture.SHOWN] captures a still with the hint frozen fully visible.
 * - [GestureHintCapture.ANIMATED] drives the hint's grow → shrink pulse; annotate the enclosing
 *   `@Preview` with `@AnimatedPreview(durationMs = GESTURE_HINT_PULSE_CYCLE_MS)` so the renderer
 *   steps the clock across the window and encodes a GIF.
 *
 * The hint is centred over [content] (the "icon on the target" treatment from the Wear design
 * guide). Unlike [GestureHint], this does not go through the interaction-driven real indicator, so it
 * captures identically every run regardless of subscription timing — it is a preview tool, not the
 * on-device affordance.
 *
 * @param iconTint defaults to [LocalContentColor]; when overlaying a filled control pass its content
 *   colour (e.g. `MaterialTheme.colorScheme.onPrimary`) so the hint reads against the target.
 */
@Composable
fun GestureHintShowcase(
  type: GestureType,
  capture: GestureHintCapture,
  modifier: Modifier = Modifier,
  iconSize: Dp = 32.dp,
  iconTint: Color = LocalContentColor.current,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    content()
    if (capture != GestureHintCapture.HIDDEN) {
      val scale =
        when (capture) {
          GestureHintCapture.ANIMATED -> {
            val transition = rememberInfiniteTransition(label = "gestureHint")
            val pulse by
              transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                  infiniteRepeatable(
                    animation = tween(GESTURE_HINT_PULSE_MS),
                    repeatMode = RepeatMode.Reverse,
                  ),
                label = "pulse",
              )
            pulse
          }
          // SHOWN — frozen fully visible. (HIDDEN never reaches here.)
          else -> 1f
        }
      GestureIndicatorIcon(
        type = type,
        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = scale),
        size = iconSize,
        tint = iconTint,
      )
    }
  }
}
