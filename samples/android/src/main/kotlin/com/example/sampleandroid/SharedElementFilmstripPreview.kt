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
 * A deterministic filmstrip of a shared-element container transform: one static, diffable PNG with
 * the transition at five fixed progress fractions (0% → 100%), top to bottom. The static
 * counterpart to [ContainerTransformAnimatedPreview]'s GIF.
 *
 * Not `SeekableTransitionState.seekTo`: it seeks a fraction of `Transition.totalDurationNanos`,
 * which shared-element transitions keep changing as animations register, so the image differed per
 * run.
 *
 * Instead the filmstrip freezes on the paused clock:
 * - `@RoboComposePreviewOptions(ManualClockOptions(advanceTimeMillis = …))` captures exactly at
 *   [FILMSTRIP_CAPTURE_MS] of virtual time, with no further advance or quiescence probe.
 * - Each panel scales all its specs to [panelDurationMillis], so at the capture instant panel `f`
 *   is exactly `f` through its own transition (a tween evaluates to `e(t / d)`).
 *
 * If you add a panel, derive every spec from [panelDurationMillis]: a stray default spring (e.g.
 * `fadeIn()`, `AnimatedContent`'s `SizeTransform()`) breaks the panel's label.
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
 * Virtual capture time in ms, pinned by `@RoboComposePreviewOptions` above (hence a compile-time
 * constant).
 */
internal const val FILMSTRIP_CAPTURE_MS = 600L

/**
 * Virtual time at which a panel's transition actually starts, in ms: the panel waits one frame so
 * the collapsed pose gets a layout pass (else shared elements snap to expanded), and Compose takes
 * two more frames to start the transition. Measured with a linear tween probe; subtracting it lands
 * each panel exactly on its labelled fraction.
 */
internal const val FILMSTRIP_START_MS = 48L

/** Animation time a panel actually gets between [FILMSTRIP_START_MS] and the pinned capture. */
internal const val FILMSTRIP_WINDOW_MS = FILMSTRIP_CAPTURE_MS - FILMSTRIP_START_MS

internal val FILMSTRIP_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/**
 * Duration that puts a panel exactly [fraction] through its transition at [FILMSTRIP_CAPTURE_MS].
 * The 0% panel renders without a transition.
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
 * Every animation spec a panel uses, built from one duration so the bounds transform, content swap
 * and fades can't mix timelines.
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
