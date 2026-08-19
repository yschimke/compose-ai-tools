package ee.schimke.composeai.renderer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Fixtures for [SettledPreviewRenderTest] — the shapes issue #4202 reported, reduced to a solid
 * fill so a single pixel read is the whole assertion.
 *
 * [DelayedReveal] is Wear's `ConfirmationDialogContent` in miniature: children start at `alpha = 0`
 * and are animated in from a `LaunchedEffect` after a delay. Captured at the renderer's default
 * advance it is a transparent box — the "empty container ring" the report describes.
 */

/** Delay before [DelayedReveal] starts its fade, in ms. */
internal const val REVEAL_DELAY_MS = 200L

/** Duration of [DelayedReveal]'s fade, in ms. */
internal const val REVEAL_DURATION_MS = 300

/** The colour [DelayedReveal] arrives at, once its fade has run. */
internal const val REVEAL_ARGB: Int = 0xFFEF5350.toInt()

@Suppress("unused") // invoked reflectively by the renderer
@Composable
fun DelayedReveal() {
  val alpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    delay(REVEAL_DELAY_MS)
    alpha.animateTo(1f, tween(durationMillis = REVEAL_DURATION_MS, easing = LinearEasing))
  }
  Box(Modifier.fillMaxSize().background(Color(REVEAL_ARGB).copy(alpha = alpha.value)))
}

/**
 * An animation with no end, the case a settle can only ever bound rather than resolve. Pins that
 * pointing `@SettledPreview` at a spinner costs the window and still produces a frame, instead of
 * hanging the render.
 */
@Suppress("unused") // invoked reflectively by the renderer
@Composable
fun NeverSettles() {
  val transition = rememberInfiniteTransition(label = "never")
  val alpha by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
      label = "alpha",
    )
  Box(Modifier.fillMaxSize().background(Color(REVEAL_ARGB).copy(alpha = alpha)))
}
