package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
 * **The override is an ambient composition local, [LocalTlcScalingLevel].** It sets the item's scroll
 * position: `0f` (the default — nothing provides it) centres the item → full scale, so a plain
 * preview does nothing; larger values scroll it up through the real scaling zones. Wrap a preview (or
 * a producer / viewer control) in [ProvideTlcScalingLevel] to dial it, with no change to the
 * component code or the preview signature.
 */
val LocalTlcScalingLevel: ProvidableCompositionLocal<Float> = compositionLocalOf { 0f }

/** Provides [level] (0 = at rest / full scale, 1 = most scaled) to [content] for [TlcScalingHost]. */
@Composable
fun ProvideTlcScalingLevel(level: Float, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalTlcScalingLevel provides level, content = content)
}

/**
 * Hosts a real single-item [TransformingLazyColumn] and passes its genuine
 * [TransformingLazyColumnItemScope] (the lambda receiver) + [TransformationSpec] into [content],
 * with the item scrolled to the ambient [LocalTlcScalingLevel] (`0` = centred / full scale).
 */
@Composable
fun TlcScalingHost(content: @Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit) {
  val level = LocalTlcScalingLevel.current
  val screenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
  val state =
    rememberTransformingLazyColumnState(
      initialAnchorItemIndex = 0,
      initialAnchorItemScrollOffset = tlcScrollOffsetPx(level, screenHeightPx),
    )
  val spec = rememberTransformationSpec()
  MaterialTheme {
    // Vertical padding of ~0.7 screens gives the single item room above/below, so level 0 centres it
    // (full scale) and a positive level scrolls it up toward the top edge (real TLC scaling).
    TransformingLazyColumn(
      state = state,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(vertical = (LocalConfiguration.current.screenHeightDp * 0.7f).dp),
    ) {
      item { content(spec) }
    }
  }
}

/**
 * Maps a scaling [level] (`0f` = at rest, `1f` = most scaled) to the list's anchor scroll offset in
 * pixels, on a screen [screenHeightPx] px tall.
 *
 * The peak offset is ~0.46 of the screen — enough to ride the item up to the top scaling zone while
 * it stays mostly on screen. The curve is eased toward the edge (`level^0.75`) because a real TLC
 * barely scales across the middle band and only ramps hard near the edge, so a linear sweep would
 * bunch its early frames at full scale. Pure so the mapping is unit-testable.
 */
internal fun tlcScrollOffsetPx(level: Float, screenHeightPx: Int): Int {
  val clamped = level.coerceIn(0f, 1f)
  val peak = screenHeightPx * 0.46f
  return (peak * clamped.pow(0.75f)).toInt()
}
