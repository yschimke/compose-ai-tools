package com.example.samplexrglimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import ee.schimke.composeai.preview.FocusedPreview

/**
 * Previews of the Jetpack Compose Glimmer focus states
 * (`developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/focus`): default,
 * focused, and focused + pressed.
 *
 * Every state is driven by real interaction: `@FocusedPreview` makes the renderer run a real
 * `FocusManager.moveFocus` walk in keyboard input mode, as Glasses focus traversal does. Forging
 * `FocusInteraction.Focus` onto a held `MutableInteractionSource` doesn't work — Glimmer's
 * `Modifier.surface` only draws its focus border for a node the focus system actually owns.
 *
 * `ComposeUiFlags.isInitialFocusOnFocusableAvailable` (which the Glimmer doc requires on real
 * Glasses activities) is a process-wide `var`, so each preview sets it at the top of its own body
 * rather than globally, which would leak into other previews in the shared Robolectric JVM.
 *
 * Not covered: the doc's `focusProperties { onEnter = … }.focusGroup()` initial-focus pattern
 * (`onEnter` wasn't dispatched in this Compose 1.11 + Robolectric setup; once it is,
 * `@FocusedPreview(indices = [0], enterPlacesFocus = true)` supports it), and touchpad
 * `onIndirectPointerGesture` swipes, whose effect on focus the focus walk already shows.
 */
@Preview(
  name = "Glimmer · Default",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlimmerListItemDefault() {
  // Opt out of auto-focus so the capture shows the un-styled baseline. Set before any focusable
  // composes, since `focusable` reads the flag at modifier creation.
  ComposeUiFlags.isInitialFocusOnFocusableAvailable = false
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlimmerListItemFocused() {
  // Mirrors the on-device requirement; the explicit focus walk would focus the item either way.
  ComposeUiFlags.isInitialFocusOnFocusableAvailable = true
  GlimmerSurface {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      ListItem(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Focused") }
    }
  }
}

@Preview(
  name = "Glimmer · Pressed Walk",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@FocusedPreview(indices = [0, 1], pressed = true)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlimmerListPressedWalk() {
  // Capture 1 must release item 0's held press before focusing item 1, or both would render pressed
  // — visually verifies the connector's `pressHeld` Release.
  ComposeUiFlags.isInitialFocusOnFocusableAvailable = true
  GlimmerSurface {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
      ListItem(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Item 0") }
      ListItem(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Item 1") }
    }
  }
}

@Preview(
  name = "Glimmer · Pressed",
  device = AI_GLASSES_DEVICE_SPEC,
  showBackground = true,
  backgroundColor = ADDITIVE_ZERO_BACKGROUND,
)
@FocusedPreview(indices = [0], pressed = true)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlimmerListItemPressed() {
  // `pressed = true` dispatches an indirect-pointer press after the focus walk, as the Glasses
  // temple touchpad does; Glimmer's `surface()` turns it into `PressInteraction.Press`.
  ComposeUiFlags.isInitialFocusOnFocusableAvailable = true
  GlimmerSurface {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      ListItem(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Pressed") }
    }
  }
}
