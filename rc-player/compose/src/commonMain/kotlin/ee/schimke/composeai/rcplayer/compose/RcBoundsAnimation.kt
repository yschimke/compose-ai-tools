package ee.schimke.composeai.rcplayer.compose

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.DeferredTargetAnimation
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.ExperimentalAnimatableApi
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.runtime.RcAnimationTimeline
import kotlin.math.roundToInt

/** Animates measured x/y/width/height toward the Lookahead result like AndroidX AnimateMeasure. */
internal fun Modifier.animateRcBounds(
  lookaheadScope: LookaheadScope,
  spec: RcAnimationSpec,
): Modifier = if (!spec.isEnabled) this else this.then(RcAnimateBoundsElement(lookaheadScope, spec))

private data class RcAnimateBoundsElement(
  val lookaheadScope: LookaheadScope,
  val spec: RcAnimationSpec,
) : ModifierNodeElement<RcAnimateBoundsNode>() {
  override fun create(): RcAnimateBoundsNode = RcAnimateBoundsNode(lookaheadScope, spec)

  override fun update(node: RcAnimateBoundsNode) {
    node.lookaheadScope = lookaheadScope
    node.spec = spec
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "animateRcBounds"
    properties["animationId"] = spec.animationId
  }
}

@OptIn(ExperimentalAnimatableApi::class)
private class RcAnimateBoundsNode(var lookaheadScope: LookaheadScope, var spec: RcAnimationSpec) :
  ApproachLayoutModifierNode, Modifier.Node() {
  private val sizeAnimation =
    DeferredTargetAnimation<IntSize, AnimationVector2D>(IntSize.VectorConverter)
  private val offsetAnimation =
    DeferredTargetAnimation<IntOffset, AnimationVector2D>(IntOffset.VectorConverter)

  override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
    sizeAnimation.updateTarget(lookaheadSize, coroutineScope, sizeSpec())
    return !sizeAnimation.isIdle
  }

  override fun Placeable.PlacementScope.isPlacementApproachInProgress(
    lookaheadCoordinates: LayoutCoordinates
  ): Boolean {
    val target =
      with(lookaheadScope) {
        lookaheadScopeCoordinates.localLookaheadPositionOf(lookaheadCoordinates).round()
      }
    offsetAnimation.updateTarget(target, coroutineScope, offsetSpec())
    return !offsetAnimation.isIdle
  }

  override fun ApproachMeasureScope.approachMeasure(
    measurable: Measurable,
    constraints: Constraints,
  ): MeasureResult {
    val animatedSize = sizeAnimation.updateTarget(lookaheadSize, coroutineScope, sizeSpec())
    val placeable = measurable.measure(Constraints.fixed(animatedSize.width, animatedSize.height))
    return layout(placeable.width, placeable.height) {
      val coordinates = coordinates
      if (coordinates == null) {
        placeable.place(0, 0)
      } else {
        val target =
          with(lookaheadScope) {
            lookaheadScopeCoordinates.localLookaheadPositionOf(coordinates).round()
          }
        val animatedOffset = offsetAnimation.updateTarget(target, coroutineScope, offsetSpec())
        val placementOffset =
          with(lookaheadScope) {
            lookaheadScopeCoordinates.localPositionOf(coordinates, Offset.Zero).round()
          }
        val delta = animatedOffset - placementOffset
        placeable.place(delta.x, delta.y)
      }
    }
  }

  private fun sizeSpec(): FiniteAnimationSpec<IntSize> =
    tween(motionDurationMillis(), easing = motionEasing())

  private fun offsetSpec(): FiniteAnimationSpec<IntOffset> =
    tween(motionDurationMillis(), easing = motionEasing())

  private fun motionDurationMillis(): Int =
    spec.motionDurationMillis.value.takeIf { it.isFinite() && it > 0f }?.roundToInt() ?: 0

  private fun motionEasing(): Easing {
    val duration = motionDurationMillis().toFloat()
    val timeline = RcAnimationTimeline(spec)
    return Easing { fraction -> timeline.progress(fraction * duration).motion }
  }
}
