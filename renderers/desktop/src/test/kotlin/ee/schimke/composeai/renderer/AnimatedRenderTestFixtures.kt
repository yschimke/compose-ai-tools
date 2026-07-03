package ee.schimke.composeai.renderer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Test fixture for [DesktopAnimatedRendererTest] — a white dot sweeping left-to-right across a
 * black field once per second, so successive captured frames are visually distinct at any frame
 * interval ≥ ~30ms. Top-level so the renderer can reflect it the way it reflects a consumer's
 * `@Preview` (`Class.forName("…AnimatedRenderTestFixturesKt")` + `getDeclaredComposableMethod`).
 */
@Composable
fun SweepingDot() {
  val transition = rememberInfiniteTransition(label = "sweep")
  val fraction by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
      label = "fraction",
    )
  Box(modifier = Modifier.size(64.dp).background(Color.Black)) {
    Box(
      modifier =
        Modifier.offset(x = 48.dp * fraction, y = 24.dp).size(16.dp).background(Color.White)
    )
  }
}
