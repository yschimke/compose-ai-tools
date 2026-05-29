package ee.schimke.composeai.daemon

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalIndirectPointerApi
import androidx.compose.ui.focus.FocusDirection as ComposeFocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.indirect.IndirectPointerEvent
import androidx.compose.ui.input.indirect.IndirectPointerEventPrimaryDirectionalMotionAxis
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.focus.Material3FocusProduct
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension

/**
 * `AroundComposable` extension that owns the focus / keyboard-traversal around-composable
 * concerns: installs `LocalInputModeManager provides KeyboardInputModeManager` and a
 * `LaunchedEffect`-driven focus walk that observes [FocusController.activeFocus] and dispatches
 * `FocusManager.moveFocus(...)` on every transition.
 *
 * The extension is the seam both render paths share:
 *
 * - **Plugin path** (`composePreviewRenderAll` / `RobolectricRenderTest`): the renderer wraps content
 *   with this extension whenever `@FocusedPreview` discovery emitted any per-capture focus state,
 *   and updates [FocusController.set] from the outer per-capture loop. The `LaunchedEffect`
 *   re-walks each time the controller's state flips.
 * - **Daemon path** (`renderNow.overrides.focus`): [FocusPreviewOverrideExtension] plans the
 *   extension and seeds the controller from the constructor argument so a single-frame render
 *   driven by the daemon picks up the requested focus target without going through the plugin's
 *   per-capture loop.
 *
 * Runs in the [DataExtensionPhase.OuterEnvironment] phase so the input-mode flip happens before
 * the user-environment phase reaches preview content.
 */
class FocusOverrideExtension(private val seed: FocusOverride? = null) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(Material3FocusProduct.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    if (seed != null) {
      DisposableEffect(seed) {
        FocusController.set(seed)
        onDispose { FocusController.set(null) }
      }
    }
    CompositionLocalProvider(LocalInputModeManager provides KeyboardInputModeManager) {
      val focusManager = LocalFocusManager.current
      val view = LocalView.current
      val active by FocusController.activeFocus
      val lastIndex = remember { mutableIntStateOf(-1) }
      val entered = remember { mutableStateOf(false) }
      val pressHeld = remember { mutableStateOf(false) }
      LaunchedEffect(active) {
        val cap = active ?: return@LaunchedEffect
        // Release any indirect-pointer Press from the prior capture *before* walking focus to the
        // new target. Indirect-pointer events route to the focused composable, so a Release
        // dispatched after the focus walk would land on the new target and leave the previous
        // target's `PressInteraction.Press` active — surfacing as two simultaneously-pressed items
        // in a multi-index capture (`@FocusedPreview(indices = [0, 1], pressed = true)`).
        if (pressHeld.value) {
          view.dispatchIndirectRelease()
          pressHeld.value = false
        }
        val direction = cap.direction
        val tabIndex = cap.tabIndex
        if (direction != null) {
          if (!entered.value) {
            focusManager.moveFocus(ComposeFocusDirection.Enter)
            entered.value = true
          }
          focusManager.moveFocus(direction.toCompose())
        } else if (tabIndex != null) {
          // `moveFocus(Enter)` lands the owner on an internal root that sits *before* the first
          // focusable, so the first walk needs `tabIndex + 1` Next steps to land on button
          // `tabIndex`. Subsequent calls walk only the delta.
          //
          // Exception: when the preview's root layout carries
          // `Modifier.focusProperties { onEnter = { initialFocus.requestFocus() } }.focusGroup()`
          // (the order-control pattern documented at
          // `developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/focus`),
          // `Enter` already lands focus directly on the requested child and the `+1 Next` advances
          // past it. The preview opts into the alternative walk by setting
          // [FocusOverride.enterPlacesFocus] (driven by `@FocusedPreview(enterPlacesFocus = true)`).
          val enterPlacesFocus = cap.enterPlacesFocus
          val from = lastIndex.value
          if (from < 0) {
            focusManager.moveFocus(ComposeFocusDirection.Enter)
            val steps = if (enterPlacesFocus) tabIndex else tabIndex + 1
            repeat(steps) { focusManager.moveFocus(ComposeFocusDirection.Next) }
          } else if (tabIndex > from) {
            repeat(tabIndex - from) { focusManager.moveFocus(ComposeFocusDirection.Next) }
          }
          lastIndex.value = tabIndex
        }
        // `@FocusedPreview(pressed = true)` — after the focus walk lands, dispatch an
        // indirect-pointer Press onto the focused composable so its `PressInteraction.Press` fires
        // before the renderer captures pixels. Glimmer's `Modifier.onIndirectPointerGesture`
        // observes this event on the focused-target path and emits the pressed-state interaction;
        // Material's `Modifier.clickable` does the same through its indirect-pointer fallback. See
        // [dispatchIndirectPress] for the platform rationale. The matching Release is held off
        // until the NEXT capture's LaunchedEffect runs (above) — held-press across the capture
        // window is exactly the "finger held on touchpad" shape we want pixels to show.
        if (cap.pressed) {
          view.dispatchIndirectPress()
          pressHeld.value = true
        }
      }
      content()
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(Material3FocusProduct.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.focus` to a [FocusOverrideExtension]. No-op when the
 * field is null — matches the wallpaper / theme / ambient planners.
 */
class FocusPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = FocusOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.focus?.let(::FocusOverrideExtension)
}

/** Helper for the renderer's per-capture loop: seed the controller without constructing FQNs. */
fun FocusManager.applyFocusOverride(override: FocusOverride?) {
  // Renderer-side helper kept here so it can evolve alongside the connector's walk semantics
  // without the renderer reaching into controller internals.
  FocusController.set(override)
}

/**
 * Dispatches a single indirect-pointer Press event onto the focused composable through Compose UI's
 * `AndroidComposeView.sendIndirectPointerEvent` — the same dispatch path real XR Glasses touchpads
 * route through. The composable's `IndirectPointerInputModifierNode`s (Glimmer's
 * `onIndirectPointerGesture`, Material `clickable`'s indirect-pointer fallback) observe the event
 * on the focused-target path and emit `PressInteraction.Press` to their `InteractionSource` — the
 * focused element's pressed visual then renders before the renderer's per-capture clock advance
 * elapses.
 *
 * The matching Release isn't sent here — it's deferred to the next capture's `LaunchedEffect`
 * pass (via the `pressHeld` flag) so the composable stays in its pressed state for *this*
 * capture window (the "finger held on the touchpad" shape) and deliberately doesn't fire the
 * `onClick` lambda (a tap = Press+Release). The next capture, if any, dispatches
 * [dispatchIndirectRelease] before walking focus, clearing the prior target's
 * `PressInteraction.Press` while focus is still on it — without that step, a multi-index pressed
 * walk (`@FocusedPreview(indices = [0, 1], pressed = true)`) would leave item 0 still visually
 * pressed in the index-1 capture. After the final capture the JVM is recycled, so no terminal
 * Release is needed.
 *
 * Reflection rather than a direct call: `AndroidComposeView` is `internal` at the Kotlin source
 * level (compiles to `public final class` at the JVM level — `internal` is module-scoped in the
 * Kotlin compiler only), so a direct `as AndroidComposeView` cast would fail to compile from
 * outside `androidx.compose.ui`. The bytecode-public `sendIndirectPointerEvent` is callable via
 * reflection without taking a compile-time dep on the internal class — same pattern the renderer's
 * focus-overlay reflection uses to read `AndroidComposeView` internals for the post-capture
 * stroke. We identify the view by class name rather than `isAssignableFrom` checks so the resolver
 * stays focused on the concrete platform class — wrappers and test stand-ins skip the dispatch
 * cleanly.
 *
 * The motion event sets `source = SOURCE_TOUCHPAD` to match what real Glasses input carries;
 * coordinates are `(0, 0)` because indirect-pointer events have no screen position (the consumer
 * routes by focused target, not by hit-test). Axis is `X` — matches Glimmer's primary swipe axis
 * — but no axis is read for a Press with no motion, so the value is documentation more than
 * mechanism. We don't recycle the `MotionEvent` because Compose retains it as
 * `IndirectPointerEvent.nativeEvent` for the event lifetime, and recycling would null out fields
 * the consumer may still read.
 */
@OptIn(ExperimentalIndirectPointerApi::class)
private fun View.dispatchIndirectPress() {
  sendIndirectPointer(MotionEvent.ACTION_DOWN)
}

/**
 * Matching Release for [dispatchIndirectPress] — clears the held `PressInteraction.Press` on the
 * focused composable so a subsequent capture (next focus walk) doesn't leave the prior target
 * visually pressed. Must dispatch *before* `FocusManager.moveFocus(...)` walks focus to the next
 * target: indirect-pointer events route to whatever's currently focused, so a Release after the
 * walk would land on the new target and leave the previous target's interaction source dangling.
 */
@OptIn(ExperimentalIndirectPointerApi::class)
private fun View.dispatchIndirectRelease() {
  sendIndirectPointer(MotionEvent.ACTION_UP)
}

@OptIn(ExperimentalIndirectPointerApi::class)
private fun View.sendIndirectPointer(action: Int) {
  if (javaClass.name != "androidx.compose.ui.platform.AndroidComposeView") return
  val now = SystemClock.uptimeMillis()
  val motionEvent =
    MotionEvent.obtain(
      /* downTime = */ now,
      /* eventTime = */ now,
      /* action = */ action,
      /* x = */ 0f,
      /* y = */ 0f,
      /* metaState = */ 0,
    )
  motionEvent.source = InputDevice.SOURCE_TOUCHPAD
  val event =
    IndirectPointerEvent(
      motionEvent = motionEvent,
      primaryDirectionalMotionAxis = IndirectPointerEventPrimaryDirectionalMotionAxis.X,
      previousMotionEvent = null,
    )
  val send = javaClass.getMethod("sendIndirectPointerEvent", IndirectPointerEvent::class.java)
  send.invoke(this, event)
}
