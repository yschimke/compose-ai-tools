package com.example.samplexrglimmer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import ee.schimke.composeai.preview.FocusedPreview

/**
 * Sample previews replicating two of the three visual states the Jetpack Compose Glimmer
 * focus documentation calls out — `developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/focus`.
 *
 * Each capture is driven by *real* interactions through the preview-rendering pipeline:
 * `@Preview` for the default-state capture, `@FocusedPreview` for the focused-state capture
 * (which the renderer translates into a real `FocusManager.moveFocus(Enter)` + `moveFocus(Next)`
 * call under `LocalInputModeManager provides KeyboardInputModeManager`, mirroring what a Glasses
 * device's focus traversal does). Nothing here forges interaction events onto a
 * `MutableInteractionSource` to fake a visual state — Glimmer's `Modifier.surface` ignores
 * `FocusInteraction.Focus` emitted to a held source and only renders its focus border for a node
 * the focus system actually owns. A faked-focus capture matches a no-focus capture byte-for-byte
 * and is worse than useless.
 *
 * **States covered here:**
 *
 *  1. **Default** ([GlimmerListItemDefault]) — plain `@Preview`, no focus annotation. A reviewer
 *     reading the captured PNG sees the un-styled `ListItem` baseline: surface fill from
 *     `GlimmerTheme.colors.surface`, default border width, no focused-state overlays.
 *
 *  2. **Focused** ([GlimmerListItemFocused]) — `@Preview` + `@FocusedPreview(indices = [0])`.
 *     The renderer's focus walk lands real focus on the single `ListItem`; Glimmer's surface draws
 *     its focused-state border (the doc's "border width increased to communicate focus"). No
 *     interaction-source fakery — Glimmer's own focusable wiring is what raises the state.
 *
 *  3. **Focused + Pressed** — *not captured here.* Reaching this state requires a synthetic
 *     down-event landing on the focused item (Glimmer's pressed-overlay listens to
 *     `interactionSource.collectIsPressedAsState()` against its real source, and Robolectric's
 *     renderer has no touchpad). The clean path is to extend `@FocusedPreview` with a `pressed`
 *     parameter that the renderer translates into `rule.onAllNodes(hasFocus()).performTouchInput
 *     { down(center) }` after the focus walk settles — tracked as a follow-up, out of scope for
 *     this PR.
 *
 * **focusGroup + order control — investigated, not shipped.** The Glimmer doc's documented
 * pattern —
 *
 * ```
 * Modifier.focusProperties { onEnter = { initialFocus.requestFocus(); cancelFocusChange() } }.focusGroup()
 * ```
 *
 * — didn't capture as expected in this Compose 1.11 + Robolectric + Glimmer alpha13 environment.
 * Built the preview, drove focus via `@FocusedPreview(indices = [0])`, the renderer's
 * `moveFocus(Enter)` ran, but the focus ring rendered on the *last* `ListItem` rather than the
 * `initialFocus`-requested middle one. Added a `println` inside `onEnter` to verify dispatch — it
 * didn't fire at all, meaning `focusProperties.onEnter` wasn't called by Compose 1.11's
 * `focusGroup()` in this setup despite matching the official sample's modifier order. Real
 * upstream investigation; the demo is parked until either Compose's behavior changes or a
 * renderer-side workaround surfaces.
 *
 * The plumbing that *would* support a working `focusGroup` demo did land alongside this PR
 * for forward-compatibility:
 *
 *  - New `@FocusedPreview(enterPlacesFocus = …)` boolean parameter — opt-in for previews whose
 *    root carries the `focusGroup` + `onEnter` pattern. Tells the renderer's focus walk to skip
 *    its historical `+1 Next` compensation after `moveFocus(Enter)`, because in that pattern
 *    Enter is supposed to place focus directly on the chosen child.
 *  - Wired annotation → discovery (`PreviewDiscovery.readFocusSteps`) → wire-shape
 *    (`FocusOverride.enterPlacesFocus`) → connector
 *    (`:data-focus-connector`'s `FocusOverrideExtension`).
 *
 * When the upstream `onEnter` dispatch is unblocked, the demo composable + a single
 * `@FocusedPreview(indices = [0], enterPlacesFocus = true)` annotation will produce the doc's
 * intended capture — no further renderer work required.
 *
 * **`ComposeUiFlags.isInitialFocusOnFocusableAvailable` is not set in this repository** (verified
 * via `grep -rn ComposeUiFlags`, zero hits). The Glimmer doc flags it as a *temporary requirement*
 * for real Glasses activities — set in `Activity.onCreate` before `super.onCreate(savedInstanceState)`
 * so the system auto-focuses the first focusable on screen load. Renderer-side these previews
 * drive focus explicitly via `@FocusedPreview`, so the flag's absence doesn't change captures;
 * an on-device sample app embedding these composables would still want to set it. Wiring it
 * renderer-wide (in `RobolectricRenderTest`) would affect every preview in the repo — notification,
 * glance, splash, Wear — none of which expect auto-focus on the first focusable; out of scope.
 *
 * **Indirect-pointer interaction** — out of scope for a static `@Preview`. Glimmer's
 * `Modifier.onIndirectPointerGesture(enabled, onSwipeForward, onSwipeBackward, onClick)`
 * (`developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/indirect-pointer`)
 * only fires from real Glasses-touchpad input, which Robolectric can't synthesise. The renderer's
 * focus-walk path (`@FocusedPreview`) already covers the *effect* of a `onSwipeForward` /
 * `onSwipeBackward` gesture on the focused composable, which is the part previews can capture;
 * the gesture-indicator design in `docs/design/glimmer-preview/` is the right surface for
 * annotating which gesture drove each step.
 */

@Preview(
  name = "Glimmer · Default",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@Composable
fun GlimmerListItemDefault() {
  GlimmerSurface {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      ListItem(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Default") }
    }
  }
}

@Preview(
  name = "Glimmer · Focused",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@FocusedPreview(indices = [0])
@Composable
fun GlimmerListItemFocused() {
  GlimmerSurface {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      ListItem(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Focused") }
    }
  }
}
