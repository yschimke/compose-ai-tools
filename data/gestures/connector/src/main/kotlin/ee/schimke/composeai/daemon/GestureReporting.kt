package ee.schimke.composeai.daemon

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.onehandedgesture.GestureAction
import androidx.wear.compose.material3.onehandedgesture.GestureIndicatorSize
import androidx.wear.compose.material3.onehandedgesture.GesturePriority
import androidx.wear.compose.material3.onehandedgesture.LocalOneHandedGestureEnabled
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureInteraction
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Consumer-facing kind of one-handed gesture, mapped to the Wear framework [GestureAction] and the
 * `compose/gestures` wire spelling ([GestureKindOverride]). `SCROLL` and `PAGE` are
 * [GestureAction.Primary] gestures (the primary "double pinch" also drives scroll / paging per the
 * Wear design guide) but stay distinct in the data product so an agent can tell a play button from a
 * scroll surface.
 */
enum class GestureType(val wire: GestureKindOverride) {
  PRIMARY(GestureKindOverride.PRIMARY),
  DISMISS(GestureKindOverride.DISMISS),
  SCROLL(GestureKindOverride.SCROLL),
  PAGE(GestureKindOverride.PAGE);

  internal fun toGestureAction(): GestureAction =
    when (this) {
      PRIMARY,
      SCROLL,
      PAGE -> GestureAction.Primary
      DISMISS -> GestureAction.Dismiss
    }
}

/**
 * The [GestureStateController] a previewed tree reports into. Installed by [GestureOverrideExtension]
 * during a daemon render; defaults to the process-static singleton so a plain `@Preview` (rendered
 * without the daemon extension chain) still registers handlers harmlessly.
 */
val LocalGestureRegistry: ProvidableCompositionLocal<GestureStateController> =
  staticCompositionLocalOf {
    GestureStateController
  }

/**
 * Registers a Wear one-handed-gesture handler with the real framework **and** reports it to the
 * `compose/gestures` data product.
 *
 * This is the seam a previewable Wear screen uses instead of calling `Modifier.oneHandedGesture`
 * directly: the on-device behaviour is identical (it delegates to the real modifier), but the
 * handler also becomes visible to `data/fetch?kind=compose/gestures` and invokable via
 * `renderNow.overrides.gestures.invoke` / an `input.gesture` recording event — neither of which the
 * framework's internal, Pixel-Watch-only registry exposes.
 *
 * @param type gesture kind (drives the framework [GestureAction] and the reported wire kind).
 * @param label accessibility / hint label, forwarded to `oneHandedGesture(gestureLabel = …)` and
 *   used as the handler's identity in the data product.
 * @param interactionSource shared with the matching [GestureHint] so the real indicator can
 *   visualise the gesture.
 * @param hintAvailable whether a [GestureHint] is wired for this handler (reported, not enforced).
 * @param onGesture the action to run when the gesture fires (on-device) or is invoked (data product).
 */
@Composable
fun Modifier.reportedOneHandedGesture(
  type: GestureType,
  label: String,
  interactionSource: MutableInteractionSource,
  priority: GesturePriority = GesturePriority.Clickable,
  enabledInAmbient: Boolean = false,
  hintAvailable: Boolean = true,
  onGesture: suspend () -> Unit,
): Modifier {
  val controller = LocalGestureRegistry.current
  val scope = rememberCoroutineScope()
  val latestOnGesture by rememberUpdatedState(onGesture)
  // Effective recognition state for this subtree — reported so a `LocalOneHandedGestureEnabled =
  // false` opt-out (the disabled-gesture screen) surfaces as `enabled = false`, not the override
  // default. The real `oneHandedGesture` below reads the same local for its own gating.
  val recognitionEnabled = LocalOneHandedGestureEnabled.current
  DisposableEffect(controller, type, label, hintAvailable, recognitionEnabled) {
    controller.register(type.wire, label, hintAvailable, recognitionEnabled) {
      scope.launch { latestOnGesture() }
    }
    onDispose { controller.unregister(type.wire, label) }
  }
  return this.oneHandedGesture(
    action = type.toGestureAction(),
    priority = priority,
    enabledInAmbient = enabledInAmbient,
    interactionSource = interactionSource,
    gestureLabel = label,
    onGesture = onGesture,
  )
}

/**
 * Wraps [content] with the real Wear [OneHandedGestureIndicator] hint affordance.
 *
 * The hint shows when the gesture emits an `Indicate` interaction on [interactionSource] (on-device
 * cadence) — or, in a preview, when [forceShow] is set or the daemon applied
 * `overrides.gestures.showHints = true`. In those force paths the indicator is fed a replay-backed
 * interaction source pre-seeded with an `Indicate`, so the hint renders deterministically in a
 * single captured frame regardless of subscription ordering.
 */
@Composable
fun GestureHint(
  type: GestureType,
  interactionSource: InteractionSource,
  modifier: Modifier = Modifier,
  forceShow: Boolean = false,
  gestureIndicatorSize: GestureIndicatorSize = GestureIndicatorSize.Medium,
  gestureIndicatorTint: Color = LocalContentColor.current,
  content: @Composable BoxScope.() -> Unit,
) {
  val hintsRequested by LocalGestureRegistry.current.hintsShownState
  val forced = forceShow || hintsRequested
  val forcedSource = rememberForcedGestureHintSource(type)
  OneHandedGestureIndicator(
    interactionSource = if (forced) forcedSource else interactionSource,
    modifier = modifier,
    gestureIndicatorSize = gestureIndicatorSize,
    gestureIndicatorTint = gestureIndicatorTint,
    content = content,
  )
}

/**
 * A [MutableInteractionSource] whose stream replays a single `Indicate` for [type], so a late
 * subscriber (a Wear gesture indicator collecting after composition settles) always observes it and
 * shows the hint deterministically in a single captured frame.
 *
 * [GestureHint] uses it on its force-show paths; the scroll / page indicators
 * (`OneHandedGestureScrollIndicator`, `OneHandedGestureHorizontalPageIndicator`) don't go through
 * [GestureHint], so a preview that wants their hint force-shown feeds them this source directly.
 */
@Composable
fun rememberForcedGestureHintSource(type: GestureType): MutableInteractionSource =
  remember(type) {
    object : MutableInteractionSource {
      private val shared =
        MutableSharedFlow<Interaction>(replay = 1, extraBufferCapacity = 16).apply {
          tryEmit(OneHandedGestureInteraction.Indicate(type.toGestureAction(), key = "forced"))
        }

      override val interactions: Flow<Interaction> = shared

      override suspend fun emit(interaction: Interaction) {
        shared.emit(interaction)
      }

      override fun tryEmit(interaction: Interaction): Boolean = shared.tryEmit(interaction)
    }
  }
