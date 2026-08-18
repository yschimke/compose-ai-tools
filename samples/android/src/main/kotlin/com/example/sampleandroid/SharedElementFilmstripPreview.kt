package com.example.sampleandroid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions
import kotlin.math.roundToInt

/**
 * A **deterministic filmstrip** of a shared-element container transform — one static, diffable PNG
 * that lays the same transition out at five fixed progress fractions (0% → 100%) stacked top to
 * bottom.
 *
 * Where [ContainerTransformAnimatedPreview] uses the `@AnimatedPreview` paused-clock GIF (great for
 * *watching* the motion), this preview is the static counterpart: the in-between bounds
 * interpolation is legible in a single image, and a visual-diff bot can compare it pixel-for-pixel
 * without the frame-timing jitter a GIF carries.
 *
 * ## How a panel is frozen, and why it is not `seekTo`
 *
 * The obvious spelling — give each panel its own `SeekableTransitionState` and `seekTo(fraction =
 * 0.25f, targetState = Expanded)` from a `LaunchedEffect` — renders a *different image on every
 * run* (issue #4097), and no amount of extra settle time fixes it. `seekTo` takes a fraction **of
 * the transition's total duration**, and that total is not a constant here:
 * `Transition.totalDurationNanos` is the max over the child animations that have registered so far,
 * a set that shared-element transitions keep growing (each `sharedBounds`/`sharedElement` match
 * adds its own bounds animation, and seeking adds initial-value animations on top). Measured on
 * this preview with every spec pinned to a fixed `tween`, the five panels still reported totals of
 * 600ms / 787ms / 1050ms / 1387ms / 1800ms — and two of the five moved again between consecutive
 * frames, differently on each run. Seeking a *fraction of a moving total* is a feedback loop: the
 * seek changes which animations exist, which changes the total, which changes what the fraction
 * meant. The fraction was always exactly right; the duration it was a fraction of was not.
 *
 * So the filmstrip freezes on the **clock** instead, which the renderer already runs paused and
 * deterministic:
 * - `@RoboComposePreviewOptions(ManualClockOptions(advanceTimeMillis = …))` pins the capture to
 *   exactly [FILMSTRIP_CAPTURE_MS] of virtual time. A capture with an explicit time is an exact
 *   snapshot — the renderer neither advances past it nor runs its adaptive pixel-quiescence probe.
 * - Each panel scales *all* of its animation specs to `FILMSTRIP_WINDOW_MS / fraction`
 *   ([panelDurationMillis]), so at the capture instant panel `f` sits exactly `f` of the way
 *   through its own transition. A `tween(durationMillis = d, easing = e)` evaluates to `e(t / d)`,
 *   so scaling `d` and reading at a fixed `t` produces the same eased pose the seek was asking for
 *   — while depending on nothing but the paused clock.
 *
 * Every panel starts its transition in the same composition and therefore on the same frame, so
 * nothing here depends on the order Compose happens to recompose the five siblings in — which is
 * what made the `seekTo` version drift a *different* panel on each run.
 *
 * If you add a panel, keep every spec derived from [panelDurationMillis]: a stray default spec
 * (`fadeIn()` and `AnimatedContent`'s default `SizeTransform()` are springs) puts one animation on
 * a different timeline from the rest and the panel stops matching its own label.
 */
@Preview(name = "Shared Element Filmstrip", widthDp = 340, heightDp = 820, showBackground = true)
@RoboComposePreviewOptions(
  manualClockOptions = [ManualClockOptions(advanceTimeMillis = FILMSTRIP_CAPTURE_MS)]
)
@Composable
fun SharedElementFilmstripPreview() {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FILMSTRIP_FRACTIONS.forEach { fraction -> FilmstripPanel(fraction) }
      }
    }
  }
}

/**
 * Virtual time the renderer captures this preview at, in milliseconds — the constant the
 * `@RoboComposePreviewOptions` above pins and [panelDurationMillis] scales against. Must be a
 * compile-time constant to be usable as an annotation argument.
 */
internal const val FILMSTRIP_CAPTURE_MS = 600L

/**
 * Virtual time at which a panel's transition actually starts running, in milliseconds.
 *
 * Two things push it off zero, and both are load-bearing:
 * - the panel waits one frame before flipping its target state, so the collapsed pose gets a layout
 *   pass first. Without that pass the shared elements have no initial bounds to animate *from* and
 *   snap straight to the expanded pose — every panel then renders at ~100% regardless of its label.
 * - Compose needs two more frames to observe the flip and put the transition on its clock.
 *
 * Measured, not guessed: a `tween(600, LinearEasing)` probe on the 100% panel reads 0 at 16/32/48ms
 * and `0.0267 = 16/600` at 64ms, so the animation's own zero is 48ms. Subtracting it from
 * [FILMSTRIP_CAPTURE_MS] is what makes a panel land on *exactly* its labelled fraction rather than
 * 2.7% short of it.
 */
internal const val FILMSTRIP_START_MS = 48L

/** Animation time a panel actually gets between [FILMSTRIP_START_MS] and the pinned capture. */
internal const val FILMSTRIP_WINDOW_MS = FILMSTRIP_CAPTURE_MS - FILMSTRIP_START_MS

internal val FILMSTRIP_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/**
 * Duration that puts a panel exactly [fraction] of the way through its transition at
 * [FILMSTRIP_CAPTURE_MS]. The 0% panel never leaves its start state, so it has no duration to scale
 * and is rendered without a transition at all.
 */
internal fun panelDurationMillis(fraction: Float): Int =
  (FILMSTRIP_WINDOW_MS / fraction.coerceAtLeast(MIN_FRACTION)).roundToInt()

/**
 * Guard so a `0f` panel can't divide by zero if one is ever driven through [panelDurationMillis].
 */
private const val MIN_FRACTION = 0.001f

private enum class FilmScreen {
  Collapsed,
  Expanded,
}

@Composable
private fun FilmstripPanel(fraction: Float) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      "${(fraction * 100).toInt()}%",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
    )
    val durationMs = remember(fraction) { panelDurationMillis(fraction) }
    val specs = remember(durationMs) { FilmstripSpecs(durationMs) }
    val transitionState = remember { MutableTransitionState(FilmScreen.Collapsed) }
    // One frame of collapsed pose before the flip, on every panel alike, so the shared elements
    // have bounds to animate from and all five transitions start on the same frame — see
    // [FILMSTRIP_START_MS]. The 0% panel stays put: its "frozen at 0" pose is the start state.
    LaunchedEffect(Unit) {
      withFrameNanos {}
      if (fraction > 0f) transitionState.targetState = FilmScreen.Expanded
    }
    val transition = rememberTransition(transitionState, label = "filmstrip-$fraction")
    Box(modifier = Modifier.fillMaxWidth().height(132.dp)) {
      SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        transition.AnimatedContent(transitionSpec = specs.contentSpec) { target ->
          when (target) {
            FilmScreen.Collapsed ->
              FilmCollapsed(this@SharedTransitionLayout, this@AnimatedContent, specs)
            FilmScreen.Expanded ->
              FilmExpanded(this@SharedTransitionLayout, this@AnimatedContent, specs)
          }
        }
      }
    }
  }
}

/**
 * Every animation spec a panel uses, all built from one duration.
 *
 * Bundled rather than passed one by one so a panel physically cannot mix timelines: the bounds
 * transform, the content swap and the shared-element fades are the three places a default spring
 * would otherwise slip in.
 */
private class FilmstripSpecs(durationMillis: Int) {
  val bounds = BoundsTransform { _, _ -> tween(durationMillis = durationMillis) }
  val enter = fadeIn(tween(durationMillis = durationMillis))
  val exit = fadeOut(tween(durationMillis = durationMillis))
  val contentSpec: AnimatedContentTransitionScope<FilmScreen>.() -> ContentTransform = {
    fadeIn(tween(durationMillis = durationMillis)) togetherWith
      fadeOut(tween(durationMillis = durationMillis)) using
      SizeTransform(clip = false) { _, _ -> tween(durationMillis = durationMillis) }
  }
}

@Composable
private fun FilmCollapsed(
  sharedScope: SharedTransitionScope,
  visibilityScope: AnimatedVisibilityScope,
  specs: FilmstripSpecs,
) =
  with(sharedScope) {
    Row(
      modifier =
        Modifier.sharedBounds(
            rememberSharedContentState(key = "container"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = specs.bounds,
            enter = specs.enter,
            exit = specs.exit,
          )
          .clip(RoundedCornerShape(16.dp))
          .background(Color(0xFFE8DEF8))
          .padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.sharedElement(
              rememberSharedContentState(key = "avatar"),
              animatedVisibilityScope = visibilityScope,
              boundsTransform = specs.bounds,
            )
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF6750A4))
      )
      Spacer(Modifier.size(10.dp))
      Text(
        "Aurora ridge",
        modifier =
          Modifier.sharedBounds(
            rememberSharedContentState(key = "title"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = specs.bounds,
            enter = specs.enter,
            exit = specs.exit,
          ),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }

@Composable
private fun FilmExpanded(
  sharedScope: SharedTransitionScope,
  visibilityScope: AnimatedVisibilityScope,
  specs: FilmstripSpecs,
) =
  with(sharedScope) {
    Row(
      modifier =
        Modifier.sharedBounds(
            rememberSharedContentState(key = "container"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = specs.bounds,
            enter = specs.enter,
            exit = specs.exit,
          )
          .clip(RoundedCornerShape(22.dp))
          .background(Color(0xFFE8DEF8))
          .fillMaxSize()
          .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.sharedElement(
              rememberSharedContentState(key = "avatar"),
              animatedVisibilityScope = visibilityScope,
              boundsTransform = specs.bounds,
            )
            .size(88.dp)
            .clip(CircleShape)
            .background(Color(0xFF6750A4))
      )
      Spacer(Modifier.size(14.dp))
      Text(
        "Aurora ridge",
        modifier =
          Modifier.sharedBounds(
            rememberSharedContentState(key = "title"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = specs.bounds,
            enter = specs.enter,
            exit = specs.exit,
          ),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
    }
  }
