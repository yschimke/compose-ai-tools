package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
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
 * preview-specific modifiers.
 *
 * Inside a real [TransformingLazyColumn] the Wear M3 [TransformationSpec] scales + fades each row by
 * *where it sits on the round screen*: a centred row is full size, rows drift smaller and more
 * transparent toward the curved edges. An isolated component preview normally can't show that.
 *
 * The trick here is **not** to fake the transform. [TransformingLazyColumnItemScope] is `sealed`, so
 * the only way to hand a component the genuine scope + spec is to host a **real**
 * [TransformingLazyColumn]. [TlcScalingHost] hosts one, with a single item centred by padding, and
 * exposes its real `TransformingLazyColumnItemScope` + [TransformationSpec] to the caller. So the
 * body is the very code you'd write in a live list:
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
 * **The override is the item's scroll position.** [TlcScalingHost]'s [level] drives the list's
 * `anchorItemScrollOffset`: `0f` centres the item → full scale (the default, so a plain use does
 * nothing), and larger values scroll it up through the real scaling zones. Sweep [level] across a
 * [TlcScaleLevels] `@PreviewParameter` and one preview renders the whole ramp (see [TlcScalingSweep]).
 */
@Composable
fun TlcScalingHost(
  level: Float = 0f,
  content: @Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit,
) {
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

/**
 * The scaling levels a [TlcScalingSweep] steps through as a `@PreviewParameter`, from **unscaled**
 * (`0f`, centred / full scale) to **most scaled** (`1f`, riding the top edge). Five by default; the
 * plugin renders one frame per value, so a single annotated preview yields the whole sweep.
 */
class TlcScaleLevels : PreviewParameterProvider<Float> {
  override val values: Sequence<Float> = sequenceOf(0f, 0.25f, 0.5f, 0.75f, 1f)
}
