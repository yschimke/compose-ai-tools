package ee.schimke.composeai.daemon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.onehandedgesture.GestureAction
import androidx.wear.compose.material3.onehandedgesture.GestureIndicatorSize
import androidx.wear.compose.material3.onehandedgesture.LocalOneHandedGestureEnabled
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureConfiguration
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureIndicatorState
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
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

  fun toGestureAction(): GestureAction =
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
 * @param label accessibility / hint label, forwarded to `oneHandedGesture(onGestureLabel = …)` and
 *   used as the handler's identity in the data product.
 * @param gestureConfiguration persistent action/key/priority specification shared with the matching
 *   [GestureHint].
 * @param indicatorState explicit indicator state shared with the matching [GestureHint], or `null`
 *   when this handler has no indicator.
 * @param interactionSource forwarded to the framework so gesture activation produces the same
 *   pressed/ripple feedback as a touch interaction.
 * @param hintAvailable whether a [GestureHint] is wired for this handler (reported, not enforced).
 * @param onGesture the action to run when the gesture fires (on-device) or is invoked (data product).
 */
@Composable
fun Modifier.reportedOneHandedGesture(
  type: GestureType,
  label: String,
  gestureConfiguration: OneHandedGestureConfiguration,
  indicatorState: OneHandedGestureIndicatorState? = null,
  interactionSource: MutableInteractionSource,
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
    gestureConfiguration = gestureConfiguration,
    enabledInAmbient = enabledInAmbient,
    interactionSource = interactionSource,
    onGestureLabel = label,
    onGestureAvailable = { indicatorState?.isIndicatorActive = true },
    onGesture = onGesture,
  )
}

/**
 * Wraps [content] with the real Wear [OneHandedGestureIndicator] hint affordance.
 *
 * The matching [reportedOneHandedGesture] sets [indicatorState] active when the framework reports
 * the gesture as available. In a forced still preview, [forceShow] or the daemon's
 * `overrides.gestures.showHints = true` renders the indicator's peak frame directly because the real
 * finite animation completes during Robolectric idle pre-roll.
 */
@Composable
fun GestureHint(
  gestureConfiguration: OneHandedGestureConfiguration,
  indicatorState: OneHandedGestureIndicatorState,
  modifier: Modifier = Modifier,
  forceShow: Boolean = false,
  gestureIndicatorSize: GestureIndicatorSize = GestureIndicatorSize.Medium,
  gestureIndicatorTint: Color = LocalContentColor.current,
  content: @Composable () -> Unit,
) {
  val hintsRequested by LocalGestureRegistry.current.hintsShownState
  val forced = forceShow || hintsRequested
  if (forced) {
    // The real indicator resets its finite animation before an idle still capture. Preserve the
    // peak frame deterministically while still measuring the original content.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
      Box(modifier = Modifier.graphicsLayer { alpha = 0f }) { content() }
      GestureIndicatorIcon(
        action = gestureConfiguration.action,
        tint = gestureIndicatorTint,
      )
    }
    return
  }
  OneHandedGestureIndicator(
    gestureConfiguration = gestureConfiguration,
    indicatorState = indicatorState,
    modifier = modifier,
    gestureIndicatorSize = gestureIndicatorSize,
    gestureIndicatorTint = gestureIndicatorTint,
    content = content,
  )
}
