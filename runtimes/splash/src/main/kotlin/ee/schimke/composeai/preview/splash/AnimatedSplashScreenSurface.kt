package ee.schimke.composeai.preview.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter

/**
 * Motion companion to [SplashScreenSurface]: the same splash window with the centre icon pulsing,
 * so an `@AnimatedPreview` captures the launch as a GIF / APNG.
 *
 * The resource-preview path already animates an `<animated-vector>` icon in isolation; this shows
 * it inside the splash window. An AVD's `ObjectAnimator` isn't driven by the paused preview clock
 * (every frame would be t=0), so the pulse is a Compose re-expression of the motion; match
 * [SplashIconPulse] to the AVD's `objectAnimator` values.
 *
 * The pulse is an `InfiniteTransition`, so set `@AnimatedPreview(durationMs = …)` to whole cycles
 * (`2 × pulse.durationMs`) for a seamless loop; auto-detect would fall back to 1500ms.
 *
 * @param icon the base vector (e.g. `ic_splash_logo`), not the `<animated-vector>` wrapper. @param
 * background full-bleed colour drawn behind everything. Defaults to opaque white. @param
 * iconBackground optional static circular backdrop (see [SplashScreenSurface]). @param
 * brandingImage optional bottom-centre branding asset. `null` (default) omits it. @param pulse the
 * icon's scale animation; defaults to [SplashIconPulse]. @param modifier modifier applied to the
 * outer full-bleed `Box`.
 */
@Composable
fun AnimatedSplashScreenSurface(
  icon: Painter,
  background: Color = Color.White,
  iconBackground: Color? = null,
  brandingImage: Painter? = null,
  pulse: SplashIconPulse = SplashIconPulse(),
  modifier: Modifier = Modifier,
) {
  val transition = rememberInfiniteTransition(label = "splash-icon-pulse")
  val scale by
    transition.animateFloat(
      initialValue = pulse.scaleFrom,
      targetValue = pulse.scaleTo,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = pulse.durationMs, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "scale",
    )
  SplashSurfaceLayout(
    icon = icon,
    background = background,
    iconBackground = iconBackground,
    brandingImage = brandingImage,
    modifier = modifier,
    iconScale = { scale },
  )
}

/**
 * The centre icon's scale animation, mapping one-to-one onto an `<animated-vector>`
 * `scaleX`/`scaleY` `objectAnimator` with `repeatMode="reverse"`: [scaleFrom] = `valueFrom`,
 * [scaleTo] = `valueTo`, [durationMs] = `duration`. Easing is `FastOutSlowInEasing`
 * (`fast_out_slow_in`).
 *
 * @param scaleFrom scale at the start of each half-cycle; `1f` makes the first frame match the
 *   static render.
 * @param scaleTo scale at the end of each half-cycle; keep it modest, the icon is already ~75% of
 *   the canvas.
 * @param durationMs one half-cycle in ms, mirroring `windowSplashScreenAnimationDuration` (platform
 *   cap 1000ms).
 */
data class SplashIconPulse(
  val scaleFrom: Float = 1f,
  val scaleTo: Float = 1.15f,
  val durationMs: Int = DEFAULT_SPLASH_PULSE_DURATION_MS,
)

/** Default half-cycle for [SplashIconPulse]: the platform's 1000ms splash animation ceiling. */
const val DEFAULT_SPLASH_PULSE_DURATION_MS: Int = 1000
