package ee.schimke.composeai.daemon

import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.onehandedgesture.GestureAction

/**
 * Renders wear-compose-material3's shipped gesture-indicator AVD as a static, tinted peak frame.
 *
 * Alpha06's explicit indicator state triggers a finite animation and is reset by the real
 * indicator. Robolectric completes that animation during idle pre-roll, so [GestureHint] uses this
 * replica only for a forced still capture. Normal on-device rendering uses the real state-backed
 * indicator.
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun GestureIndicatorIcon(
  action: GestureAction,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  tint: Color = LocalContentColor.current,
) {
  val resId =
    when (action) {
      GestureAction.Dismiss ->
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
