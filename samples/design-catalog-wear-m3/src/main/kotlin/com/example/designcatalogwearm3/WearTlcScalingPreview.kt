package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScrollProgress
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import ee.schimke.composeai.preview.DEFAULT_TLC_SCALING_FRAMES
import ee.schimke.composeai.preview.DEFAULT_TLC_SCALING_MIN_CENTER_FRACTION
import ee.schimke.composeai.preview.TlcScalingPreview
import kotlin.math.sqrt

/**
 * A **Wear-specific preview override that fakes `TransformingLazyColumn` (TLC) item scaling for a
 * single component** — no list required.
 *
 * Inside a real [TransformingLazyColumn] the Wear M3 [TransformationSpec] scales and fades each row
 * by *where it sits on the round screen*: a row at the centre is drawn at full size, and rows drift
 * smaller and more transparent as they ride toward the curved top/bottom edges. A `@Preview` of one
 * isolated component (a button, a card) has no list to place it, so it always renders at full
 * scale — you can't see how that component looks half-way off the top of the watch.
 *
 * This override supplies the missing bit of state — *a position on the screen* — and
 * [Modifier.previewTlcScaling] feeds it into the **real** [TransformationSpec] (the exact same
 * `getTransformedHeight` + `applyContainerTransformation` a live TLC row runs), so the component
 * scales identically to how it would in a list, standalone.
 *
 * ## Opting in
 *
 * Three routes, all driving the same [TransformationSpec] math — Wear's own
 * [TransformingLazyColumnItemScope] is `sealed`, so this is a parallel surface rather than an
 * implementation of it:
 * - **Annotation** — [ee.schimke.composeai.preview.TlcScalingPreview] on a component `@Preview` asks
 *   the pipeline for a sweep of scaled stills + a down-and-up GIF.
 * - **Scope** — [ProvidePreviewTlcScaling] opens a [PreviewTlcScaling] scope you pass straight into a
 *   modifier (`Modifier.previewTlcScaling(this)`) or component, like Wear's
 *   `Modifier.transformedHeight(scope, spec)`.
 * - **Ambient** — the scope is also published to [LocalPreviewTlcScaling], so a nested component can
 *   call the no-arg `Modifier.previewTlcScaling()` and pick it up.
 *
 * ## Modelling the position with one number
 *
 * A [TransformationSpec] is driven by a [TransformingLazyColumnItemScrollProgress], which is **two**
 * fractions of the screen height — the screen-space position of the item's `top` edge and of its
 * `bottom` edge (`0f` = screen top, `1f` = screen bottom; negative / `>1f` means that edge is off
 * screen). Those two are not independent: they're one edge apart by exactly the item's own height.
 * So the caller supplies a **single** number, [PreviewTlcScaling.centerFraction] — where the
 * component's *centre* rests, as a fraction of screen height — and the companion edge is computed
 * from it plus the component's **measured height** and the screen height at layout time (see
 * [tlcOffsetFractions]). One knob in, the [TransformationSpec]'s two-number input derived out.
 *
 * That single knob spans the whole range the task cares about:
 * - `0.5f` — centred, **fully on screen**, drawn at full scale (the default, [Centered]).
 * - toward `0f` / `1f` — riding off the **top** / **bottom** edge, progressively **scaled away**
 *   (smaller + more transparent), exactly as a TLC row does approaching that edge.
 */
@JvmInline
value class PreviewTlcScaling(
  /**
   * Where the component's vertical centre rests on the watch screen, as a fraction of screen height:
   * `0f` = the very top edge, `0.5f` = screen centre (full scale / fully on screen), `1f` = the
   * bottom edge. Values below `0f` or above `1f` place the centre off screen (the component is
   * mostly gone), which the [TransformationSpec] renders as its far scaled-away state.
   */
  val centerFraction: Float
) {
  companion object {
    /** Centred on screen: the resting, fully-on-screen state, drawn at full scale. */
    val Centered: PreviewTlcScaling = PreviewTlcScaling(0.5f)
  }
}

/**
 * The current preview TLC scaling position, or `null` when there is no override — in which case
 * [Modifier.previewTlcScaling] is a no-op and the component renders at its natural size (its
 * behaviour in a normal render / production, where this local is never provided).
 */
val LocalPreviewTlcScaling: ProvidableCompositionLocal<PreviewTlcScaling?> = compositionLocalOf {
  null
}

/**
 * Opens a preview TLC scaling **scope** at [centerFraction] and runs [content] in it — the
 * pass-a-scope opt-in, the counterpart to the [ee.schimke.composeai.preview.TlcScalingPreview]
 * annotation and the raw [PreviewTlcScaling] value.
 *
 * Two ways to consume the scope inside [content], both driving the same [previewTlcScaling]:
 * - **Explicit** — the block receiver is the [PreviewTlcScaling] scope, so pass it straight to a
 *   modifier (or a component that accepts one): `Box(Modifier.previewTlcScaling(this))` — mirroring
 *   how Wear's own `Modifier.transformedHeight(scope, spec)` takes an item scope.
 * - **Ambient** — the scope is also published to [LocalPreviewTlcScaling], so a nested component can
 *   just call `Modifier.previewTlcScaling()` with no argument and pick it up.
 *
 * Nest freely — the innermost scope wins for its subtree.
 */
@Composable
fun ProvidePreviewTlcScaling(
  centerFraction: Float,
  content: @Composable PreviewTlcScaling.() -> Unit,
) {
  val scaling = PreviewTlcScaling(centerFraction)
  CompositionLocalProvider(LocalPreviewTlcScaling provides scaling) { scaling.content() }
}

/**
 * Applies an **explicitly passed** [scaling] scope to this component using the **real** Wear
 * [TransformationSpec] — the pass-a-scope form, parallel to Wear's own
 * `Modifier.transformedHeight(scope, spec)`. Use this when you hold a [PreviewTlcScaling] directly
 * (e.g. the receiver inside [ProvidePreviewTlcScaling]); use the no-arg overload to read the ambient
 * [LocalPreviewTlcScaling] instead.
 *
 * @param scaling the on-screen position to simulate.
 * @param spec the transformation to drive; defaults to [rememberTransformationSpec], the same spec
 *   the real Wear list would build for this screen size.
 */
@Composable
fun Modifier.previewTlcScaling(
  scaling: PreviewTlcScaling,
  spec: TransformationSpec = rememberTransformationSpec(),
): Modifier = tlcScalingLayout(scaling, spec)

/**
 * Applies the **ambient** [LocalPreviewTlcScaling] (if any) to this component using the **real** Wear
 * [TransformationSpec], so the component scales/fades exactly as a `TransformingLazyColumn` row at
 * the same on-screen position would.
 *
 * When no scope is in scope this returns the receiver unchanged — the component keeps its natural
 * size, so the same call site is inert in a normal preview / production and only engages under a
 * [ProvidePreviewTlcScaling] wrapper (or when a [PreviewTlcScaling] is passed to the overload above).
 *
 * @param spec the transformation to drive; defaults to [rememberTransformationSpec], i.e. the same
 *   spec the real Wear list would build for this screen size.
 */
@Composable
fun Modifier.previewTlcScaling(spec: TransformationSpec = rememberTransformationSpec()): Modifier {
  val scaling = LocalPreviewTlcScaling.current ?: return this
  return tlcScalingLayout(scaling, spec)
}

/**
 * Shared layout that reproduces a TLC row's treatment for [scaling]: reserve the transformed (shrunk)
 * height like `transformedHeight`, then draw the full-size content through the spec's container
 * transform (scale + alpha + the top-aligning translateY). Both stages read the same derived scroll
 * progress, as the [TransformationSpec] contract requires.
 *
 * The height reference is `LocalConfiguration.screenHeightDp` — the same screen size
 * [rememberTransformationSpec] resolves its zones against — so the derived offset fractions line up
 * with the spec's transformation zones. (When the spec is the reduce-motion no-op — e.g. under a
 * `@ScrollingPreview(reduceMotion = true)` capture — this scales by 1.0, matching a flattened TLC.)
 */
@Composable
private fun Modifier.tlcScalingLayout(
  scaling: PreviewTlcScaling,
  spec: TransformationSpec,
): Modifier {
  // Match rememberTransformationSpec's own reference: the screen height its zones are sized against.
  val screenHeightPx =
    with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
  return this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    // Derive the two edge fractions the spec consumes from the single centre knob + the freshly
    // measured height. This is the crux: `bottom = top + height/screen`, so one number in, two out.
    val (topFraction, bottomFraction) =
      tlcOffsetFractions(scaling.centerFraction, placeable.height, screenHeightPx)
    val progress = TransformingLazyColumnItemScrollProgress(topFraction, bottomFraction)
    val transformedHeight = spec.getTransformedHeight(placeable.height, progress)
    layout(placeable.width, transformedHeight) {
      placeable.placeWithLayer(x = 0, y = 0) {
        with(spec) { applyContainerTransformation(progress) }
      }
    }
  }
}

/**
 * Turns the single [centerFraction] knob into the `(topOffsetFraction, bottomOffsetFraction)` pair a
 * [TransformationSpec] consumes, given the component's [measuredHeightPx] and the [screenHeightPx] to
 * measure against.
 *
 * The centre sits [centerFraction] of the way down the screen; the two edges are each half the
 * component's screen-relative height away from it:
 * ```
 * halfHeightFraction = (measuredHeightPx / screenHeightPx) / 2
 * top    = centerFraction - halfHeightFraction
 * bottom = centerFraction + halfHeightFraction
 * ```
 * which is the centre-anchored form of `TransformingLazyColumnItemScrollProgress`'s own
 * `downwardMeasuredItemScrollProgress(offset, height, container)` (`top = offset/H`,
 * `bottom = (offset + height)/H`) with `offset = centre - height/2`. Pure and Compose-free so the
 * derivation is unit-testable on its own.
 */
internal fun tlcOffsetFractions(
  centerFraction: Float,
  measuredHeightPx: Int,
  screenHeightPx: Int,
): Pair<Float, Float> {
  val halfHeightFraction =
    if (screenHeightPx <= 0) 0f else (measuredHeightPx.toFloat() / screenHeightPx) / 2f
  return (centerFraction - halfHeightFraction) to (centerFraction + halfHeightFraction)
}

/**
 * The [frames] simulated centre positions the demo sweep frames and the down-and-up GIF step
 * through, from **unscaled** (`0.5`, centred / full scale) to **most scaled** ([minCenterFraction],
 * riding off the top edge).
 *
 * The steps are eased toward the edge (`sqrt`) rather than linear, because a real TLC barely scales
 * across the middle band and only ramps hard near the curved edges — a linear sweep would waste half
 * its frames at full scale. So `f_i = 0.5 - (0.5 - minCenterFraction) * sqrt(i / (frames - 1))`,
 * which for the default `frames = 4`, `min = 0.07` gives ≈ `[0.50, 0.25, 0.15, 0.07]` — a visibly
 * even ramp. Pure so the sweep is unit-testable.
 */
internal fun tlcSweepFractions(
  frames: Int,
  minCenterFraction: Float,
): List<Float> {
  val steps = frames.coerceAtLeast(2)
  val full = PreviewTlcScaling.Centered.centerFraction
  return (0 until steps).map { i ->
    val t = i.toFloat() / (steps - 1)
    full - (full - minCenterFraction) * sqrt(t)
  }
}

// ---------------------------------------------------------------------------
// Demonstration — the four sweep stills a `@TlcScalingPreview` produces for one
// component (a TitleCard), unscaled -> most scaled, each a full-screen device
// frame so they share a size. The plugin renders them; `TlcScalingGifTest`
// stitches them into the down-and-up GIF. No TransformingLazyColumn present —
// the scale is entirely the `previewTlcScaling` modifier.
// ---------------------------------------------------------------------------

/** The four sweep positions the demo frames and the GIF assembler share (unscaled -> most scaled). */
internal val tlcDemoSweep: List<Float> =
  tlcSweepFractions(DEFAULT_TLC_SCALING_FRAMES, DEFAULT_TLC_SCALING_MIN_CENTER_FRACTION)

/**
 * Fixed large-round black frame for the sweep stills, so the four render to identical-size PNGs the
 * GIF can be assembled from directly.
 */
@Preview(
  name = "Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
annotation class TlcScalingSweepFrame

/** The demo [TitleCard] at [centerFraction], centred on the watch face and scaled with no list. */
@Composable
private fun TlcScalingSweepCard(centerFraction: Float) =
  MaterialTheme {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
      ProvidePreviewTlcScaling(centerFraction) {
        // `this` is the PreviewTlcScaling scope — pass it explicitly to the modifier.
        TitleCard(
          onClick = {},
          title = { Text("Activity") },
          modifier = Modifier.fillMaxWidth().previewTlcScaling(this),
        )
      }
    }
  }

// Frame 0 carries `@TlcScalingPreview` to declare the sweep the four frames realise; the pipeline
// extension that consumes the annotation to generate them automatically is the follow-up (for now
// they're authored, and `TlcScalingGifTest` turns them into the GIF).
@TlcScalingSweepFrame
@TlcScalingPreview
@Composable
fun TlcScalingSweep0() = TlcScalingSweepCard(tlcDemoSweep[0])

@TlcScalingSweepFrame @Composable fun TlcScalingSweep1() = TlcScalingSweepCard(tlcDemoSweep[1])

@TlcScalingSweepFrame @Composable fun TlcScalingSweep2() = TlcScalingSweepCard(tlcDemoSweep[2])

@TlcScalingSweepFrame @Composable fun TlcScalingSweep3() = TlcScalingSweepCard(tlcDemoSweep[3])
