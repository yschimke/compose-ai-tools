package ee.schimke.composeai.wear.preview

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import kotlin.math.pow

/**
 * Shows Wear `TransformingLazyColumn` (TLC) item scaling — scaled and faded toward the curved edges —
 * for a single component in an isolated `@Preview`, with the component authored in the **exact normal
 * list-item code**.
 *
 * Both `Modifier.transformedHeight(this, spec)` and `SurfaceTransformation(spec)` need a
 * [TransformingLazyColumnItemScope] (the `this`), which is `sealed` and only exists inside a real
 * `TransformingLazyColumn`. (A bare [rememberTransformationSpec] gives you the spec but not that
 * scope.) So [TlcScalingHost] hosts a real single-item list — the item flanked by tall spacer items
 * so it can genuinely scroll — and hands its **genuine** scope + spec to [content]:
 * ```
 * TlcScalingHost { spec ->
 *   TitleCard(
 *     onClick = {},
 *     modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),  // real Wear API
 *     transformation = SurfaceTransformation(spec),                     // real Wear API
 *   ) { … }
 * }
 * ```
 *
 * How to drive it in a preview:
 * - **A still** — a plain `@Preview`. Nothing provides [LocalTlcScrollFraction], so the item is
 *   centred at full scale (a no-op). Wrap in [ProvideTlcScalePosition] to pin
 *   [TlcScalePosition.Starting] / [TlcScalePosition.Edge] instead.
 * - **A scaling GIF** — add `@ScrollingPreview(modes = [ScrollMode.GIF], reduceMotion = false)`. The
 *   compose-preview scroll harness drives the real list scroll and captures the item scaling as it
 *   rides through the viewport — one preview, harness-controlled scroll, no custom pipeline.
 */
@Composable
fun TlcScalingHost(content: @Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit) {
  val scrollFraction = LocalTlcScrollFraction.current
  val screenHeightDp = LocalConfiguration.current.screenHeightDp
  val screenHeightPx = with(LocalDensity.current) { screenHeightDp.dp.roundToPx() }
  val state =
    rememberTransformingLazyColumnState(
      // Anchor the item (index 1, between the spacers) and scroll it up from centred by the fraction.
      initialAnchorItemIndex = 1,
      initialAnchorItemScrollOffset = tlcScrollOffsetPx(scrollFraction, screenHeightPx),
    )
  val spec = rememberTransformationSpec()
  MaterialTheme {
    TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
      // A full-screen spacer above and below gives the item clear room to scroll to any position.
      item { Spacer(Modifier.height(screenHeightDp.dp)) }
      item { content(spec) }
      item { Spacer(Modifier.height(screenHeightDp.dp)) }
    }
  }
}

/**
 * Named still positions for [TlcScalingHost] — how far up the screen (as a fraction of screen height)
 * to scroll the item from centred.
 */
enum class TlcScalePosition(internal val scrollFraction: Float) {
  /** Centred: full scale, the resting state. */
  Middle(0f),
  /** Just into the top scaling zone — scaling has started but the item is comfortably on screen. */
  Starting(0.5f),
  /** Ridden up to the top edge — high scale + fade, still (mostly) on screen rather than clipped. */
  Edge(0.75f),
}

/**
 * The ambient scroll fraction [TlcScalingHost] reads for its initial position: `0f` = centred / full
 * scale (the default), larger = scrolled up toward and past the top edge. `@ScrollingPreview` drives
 * the scroll itself, so leave this at `0f` for the GIF; use it for a pinned still.
 */
val LocalTlcScrollFraction: ProvidableCompositionLocal<Float> = compositionLocalOf { 0f }

/** Provides an arbitrary scroll [fraction] to [content] for [TlcScalingHost]. */
@Composable
fun ProvideTlcScrollFraction(fraction: Float, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalTlcScrollFraction provides fraction, content = content)
}

/** Provides a named [position] to [content] for [TlcScalingHost]. */
@Composable
fun ProvideTlcScalePosition(position: TlcScalePosition, content: @Composable () -> Unit) {
  ProvideTlcScrollFraction(position.scrollFraction, content)
}

/**
 * Maps a scroll [fraction] (`0f` = centred, `1f` = the item ridden to the top edge) to the list's
 * anchor scroll offset in pixels, on a screen [screenHeightPx] px tall.
 *
 * The peak offset is ~0.46 of the screen — enough to ride the item into the top scaling zone while it
 * stays mostly on screen. The curve is eased toward the edge (`fraction^0.75`) because a real TLC
 * barely scales across the middle band and only ramps hard near the edge. Pure so it's unit-testable.
 */
internal fun tlcScrollOffsetPx(fraction: Float, screenHeightPx: Int): Int {
  val clamped = fraction.coerceIn(0f, 1f)
  val peak = screenHeightPx * 0.46f
  return (peak * clamped.pow(0.75f)).toInt()
}
