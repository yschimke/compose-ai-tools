package com.example.designcatalogwearm3

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

/**
 * A **Wear-specific preview that shows `TransformingLazyColumn` (TLC) item scaling for a single
 * component**, with the component authored in *exactly the normal TLC-item code* — no
 * preview-specific modifiers, and no parameters on the preview function.
 *
 * Inside a real [TransformingLazyColumn] the Wear M3 [TransformationSpec] scales + fades each row by
 * *where it sits on the round screen*: a centred row is full size, rows drift smaller and more
 * transparent toward the curved edges. An isolated component preview normally can't show that.
 *
 * The trick is **not** to fake the transform. Both `Modifier.transformedHeight(this, spec)` and
 * `SurfaceTransformation(spec)` need a [TransformingLazyColumnItemScope] — the `this` — which is
 * `sealed`, so it only exists inside a real `TransformingLazyColumn`. (A bare
 * [rememberTransformationSpec] gives you the spec but not that scope.) So [TlcScalingHost] hosts a
 * real single-item list, centres the item with padding, and hands its **genuine** scope + spec to
 * [content]. The body is the very code a live list item uses:
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
 * **The override is an ambient [LocalTlcScrollFraction]** — how far up the screen (as a fraction of
 * screen height) to scroll the item from centred. The item sits between tall spacer items, so the
 * list can scroll it anywhere: `0f` (the default — nothing provides it) centres it → full scale, so a
 * plain preview does nothing; larger values ride it up through the real scaling zones and eventually
 * off the top edge. [TlcScalePosition] names the useful still points; [ProvideTlcScrollFraction]
 * takes an arbitrary fraction (the GIF animates it). Either way the component code is unchanged.
 */
enum class TlcScalePosition(
  /** How far up the screen (as a fraction of screen height) to scroll the item from centred. */
  internal val scrollFraction: Float
) {
  /** Centred: full scale, the resting state. */
  Middle(0f),
  /** Just into the top scaling zone — scaling has started but the item is comfortably on screen. */
  Starting(0.24f),
  /** Ridden up to the top edge — high scale, still (mostly) on screen rather than clipped away. */
  Edge(0.4f),
}

/**
 * The ambient scroll fraction [TlcScalingHost] reads: `0f` = centred / full scale (the default),
 * larger = scrolled up toward and past the top edge.
 */
val LocalTlcScrollFraction: ProvidableCompositionLocal<Float> = compositionLocalOf { 0f }

/** Provides an arbitrary scroll [fraction] to [content] for [TlcScalingHost] (used by the GIF sweep). */
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
 * Hosts a real [TransformingLazyColumn] — the item flanked by tall spacer items so the list is
 * genuinely scrollable — and passes its genuine [TransformingLazyColumnItemScope] (the lambda
 * receiver) + [TransformationSpec] into [content]. The list is scrolled to the ambient
 * [LocalTlcScrollFraction] (`0` = centred / full scale).
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
      initialAnchorItemScrollOffset = (screenHeightPx * scrollFraction).toInt(),
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
