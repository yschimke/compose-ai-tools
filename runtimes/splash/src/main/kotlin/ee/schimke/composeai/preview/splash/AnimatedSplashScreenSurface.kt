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
 * Motion companion to [SplashScreenSurface]: the same splash window, with the centre icon pulsing
 * so a `@Preview` annotated `@AnimatedPreview` captures the launch as a GIF / APNG instead of a
 * still.
 *
 * ## Why this exists as a separate surface
 *
 * The resource-preview path already animates a `windowSplashScreenAnimatedIcon` **in isolation** —
 * an `<animated-vector>` drawable is discovered as `ANIMATED_VECTOR` and rendered as a GIF plus a
 * keyframe filmstrip, at the drawable's intrinsic size on a bare canvas. What that never shows is
 * the icon moving *inside the splash window*: at splash proportions, over
 * `windowSplashScreenBackground`, with the optional backdrop ring and branding image in frame. That
 * composition is what a reviewer actually needs to judge, and it is what this composable captures.
 *
 * Handing an `AnimatedVectorDrawable` to [SplashScreenSurface] via `painterResource` does not get
 * you there. `@AnimatedPreview` drives motion by advancing Compose's paused test clock and
 * discovers what to plot through `PreviewAnimationClock`; an AVD's animation lives in a platform
 * `ObjectAnimator` that neither the clock advances nor the inspector can see, so every captured
 * frame would be the drawable's t=0 state. The pulse here is therefore a *Compose* animation — a
 * deliberate re-expression of the drawable's motion, not a replay of it. Match [SplashIconPulse] to
 * the AVD's own `objectAnimator` values and the capture reads like the real launch; the on-device
 * truth for the drawable itself stays the resource-preview GIF.
 *
 * ## Pairing with `@AnimatedPreview`
 *
 * The pulse is an `InfiniteTransition`, which has no inherent duration, so `@AnimatedPreview`'s
 * auto-detect falls back to its generic 1500ms window and the GIF ends mid-cycle. Set the
 * annotation's `durationMs` to a whole number of cycles instead — one full out-and-back of a
 * [SplashIconPulse.durationMs] pulse is `2 × durationMs`, so an 800ms pulse wants `durationMs =
 * 1600` for a seamless loop.
 *
 * @param icon the foreground drawable rendered at the centre — the same
 *   `windowSplashScreenAnimatedIcon` foreground the static surface takes. Pass the *base* vector
 *   (Confetti's `ic_splash_logo`), not the `<animated-vector>` wrapper: the wrapper's motion is
 *   inert under the preview clock, and the pulse below supplies it.
 * @param background full-bleed colour drawn behind everything. Defaults to opaque white.
 * @param iconBackground optional colour for the circular backdrop behind the icon. Static — see
 *   [SplashScreenSurface] for why the ring doesn't pulse with the icon.
 * @param brandingImage optional bottom-centre branding asset. `null` (default) omits it.
 * @param pulse the icon's scale animation. Defaults to [SplashIconPulse], which mirrors the
 *   platform's own `windowSplashScreenAnimationDuration` default of 1000ms.
 * @param modifier modifier applied to the outer full-bleed `Box`.
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
 * The centre icon's scale animation, expressed the way an `<animated-vector>` expresses it so the
 * two can be kept in step by eye.
 *
 * A `<target>`'s paired `scaleX` / `scaleY` `objectAnimator` with `repeatCount="infinite"` and
 * `repeatMode="reverse"` maps onto this one-to-one: [scaleFrom] is `android:valueFrom`, [scaleTo]
 * is `android:valueTo`, [durationMs] is `android:duration`. The easing is fixed at
 * `FastOutSlowInEasing` — the Compose equivalent of `@android:interpolator/fast_out_slow_in`, which
 * is what a splash pulse conventionally uses and what the platform's own splash-exit animation runs
 * on.
 *
 * @param scaleFrom scale at the start of each half-cycle. `1f` (the default) starts at the icon's
 *   natural size, so the first captured frame matches the static [SplashScreenSurface] render.
 * @param scaleTo scale at the end of each half-cycle. Keep the growth modest — the icon is already
 *   sized to ~75% of the splash-icon canvas, and a large [scaleTo] pushes it past the footprint the
 *   platform reserves, which reads as wrong even though nothing clips.
 * @param durationMs one half-cycle (natural size → [scaleTo]) in milliseconds. Mirrors
 *   `windowSplashScreenAnimationDuration`; the platform caps that attribute at 1000ms, and values
 *   above it are worth a second look because the real splash would not run that long.
 */
data class SplashIconPulse(
  val scaleFrom: Float = 1f,
  val scaleTo: Float = 1.15f,
  val durationMs: Int = DEFAULT_SPLASH_PULSE_DURATION_MS,
)

/**
 * Default half-cycle for [SplashIconPulse], matching the platform's documented
 * `windowSplashScreenAnimationDuration` ceiling of 1000ms.
 */
const val DEFAULT_SPLASH_PULSE_DURATION_MS: Int = 1000
