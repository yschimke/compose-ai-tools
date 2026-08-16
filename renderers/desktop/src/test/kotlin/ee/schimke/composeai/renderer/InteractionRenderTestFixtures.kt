package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

/**
 * Test fixtures for [DesktopInteractionRendererTest].
 *
 * Both own real state and change it from real `clickable` wiring, deliberately: the thing under
 * test is that a *dispatched pointer* reaches the component's own plumbing. A fixture that animated
 * on a timer would pass while the pointer went nowhere.
 *
 * Top-level so the renderer reflects them exactly as it reflects a consumer's `@Preview`.
 */

/**
 * Three clickable cells; the selected one turns white. Selection changes only from `onClick`, so a
 * frame with cell 2 white is proof the pointer landed on cell 2 — which is what makes this a probe
 * for **target addressing**.
 */
@Composable
fun ThreeCellSelector() {
  var selected by remember { mutableIntStateOf(0) }
  Row(modifier = Modifier.size(width = 90.dp, height = 30.dp).background(Color.Black)) {
    repeat(3) { index ->
      Box(
        modifier =
          Modifier.size(30.dp)
            .background(if (index == selected) Color.White else Color.DarkGray)
            .clickable { selected = index }
      )
    }
  }
}

/**
 * White only *while* the pointer is down, read from the same `MutableInteractionSource` a Material
 * component reads for its state layer. Nothing latches and nothing animates, so a white frame can
 * only mean the pointer was down at that instant — which makes this a probe for **press/release
 * timing**, and for the press being real rather than emitted onto the interaction source by hand.
 */
@Composable
fun HoldToLight() {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  Box(
    modifier =
      Modifier.size(30.dp).background(if (pressed) Color.White else Color.DarkGray).clickable(
        interactionSource = interactionSource,
        indication = null,
      ) {}
  )
}

/**
 * 30×30 at rest; a tap grows it to 90×30 and it never shrinks back.
 *
 * The probe for **capture region**: the resting measurement a capture would naturally crop to is
 * *wrong* for this composable from the moment it is tapped, so a renderer that measured once would
 * publish a recording with the expansion sliced off at the frame edge. The revealed half is painted
 * white specifically so a test can assert it survived to the encoded pixels.
 */
@Composable
fun ExpandOnTap() {
  var expanded by remember { mutableStateOf(false) }
  Row(modifier = Modifier.background(Color.Black)) {
    Box(modifier = Modifier.size(30.dp).background(Color.DarkGray).clickable { expanded = true })
    if (expanded) {
      Box(modifier = Modifier.size(width = 60.dp, height = 30.dp).background(Color.White))
    }
  }
}

/** A real separate-owner popup, used to prove the motion surface includes platform-hosted UI. */
@Composable
fun OpenPopupOnTap() {
  var open by remember { mutableStateOf(false) }
  Box(modifier = Modifier.size(30.dp).background(Color.DarkGray).clickable { open = true })
  if (open) {
    Popup(alignment = Alignment.TopStart, offset = IntOffset(40, 0)) {
      Box(modifier = Modifier.size(30.dp).background(Color.White))
    }
  }
}

/** The real Material dialog API, not a catalog-owned Surface reconstruction. */
@Composable
fun OpenAlertDialogOnTap() {
  var open by remember { mutableStateOf(false) }
  Box(modifier = Modifier.size(30.dp).background(Color.DarkGray).clickable { open = true })
  if (open) {
    MaterialTheme {
      AlertDialog(
        onDismissRequest = {},
        title = { Text("Captured dialog") },
        text = { Text("This content lives in the dialog owner.") },
        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
      )
    }
  }
}
