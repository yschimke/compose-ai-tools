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

/**
 * Renders wear-compose-material3's shipped gesture-indicator AVD
 * (`wear_one_handed_gesture_{primary,dismiss}_indicator_animation`) as a static, tinted icon via the
 * official `androidx.compose.animation.graphics` API — the same drawable the real
 * `OneHandedGestureIndicator` draws internally, shown at its resting frame.
 *
 * [GestureHint] overlays this on its force-show path: the interactive indicator's show/hide
 * coroutine settles to hidden during a Robolectric render's pre-roll, so a forced preview draws the
 * drawable directly to make the hint visible in a single captured frame. `SCROLL` / `PAGE` ride the
 * primary indicator, matching the framework.
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
