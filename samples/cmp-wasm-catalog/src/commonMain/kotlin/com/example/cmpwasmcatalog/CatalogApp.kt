@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.cmpwasmcatalog

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Mounts one catalog component by id inside the M3 theme, centred on the surface. `dark` flips the
 * color scheme so the viewer's `uiMode` deep-link parameter maps straight through; [fontScale] and
 * [rtl] map the viewer's font-scale slider and locale control so those overrides drive the
 * in-browser render too. An unknown id renders a visible diagnostic rather than a blank canvas.
 */
@Composable
fun CatalogApp(id: String, dark: Boolean = false, fontScale: Float = 1f, rtl: Boolean = false) {
  val scheme = if (dark) darkColorScheme() else lightColorScheme()
  // Re-point density's fontScale (preserving the real pixel density) and the layout direction, so
  // the viewer's font-scale + locale controls take effect client-side — same overrides the server
  // render honours, just running in the browser sandbox.
  val density = LocalDensity.current
  val scaled = Density(density = density.density, fontScale = fontScale)
  val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
  // Frame + component bounds, measured to scale the component to fill the stage (see below).
  var frame by remember { mutableStateOf(IntSize.Zero) }
  var content by remember { mutableStateOf(IntSize.Zero) }
  CompositionLocalProvider(LocalDensity provides scaled, LocalLayoutDirection provides direction) {
    MaterialTheme(colorScheme = scheme) {
      Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val component = catalogComponents[id]
        if (component != null) {
          // Frame parity with the baked snapshot: the snapshot crops to the component's bounds and
          // the viewer scales that up to fill the stage, so a lone component looks large. The Wasm
          // app instead renders into the full iframe, leaving the component small and adrift. Scale
          // the component to fill the frame (preserving aspect, with a cap so a tiny component
          // isn't
          // absurdly magnified, and shrinking oversized ones so nothing overflows), so switching to
          // the in-browser tier no longer makes it jump in size.
          Box(
            modifier = Modifier.fillMaxSize().onGloballyPositioned { frame = it.size },
            contentAlignment = Alignment.Center,
          ) {
            // Fit against the *full* frame with a fractional inset for breathing room — a fixed dp
            // padding would exceed a short frame (the iframe is sized to the snapshot's box, which
            // is
            // tiny for a linear progress bar or badge) and collapse the scale to the 0.25 floor.
            val scale =
              if (frame == IntSize.Zero || content.width == 0 || content.height == 0) 1f
              else
                (minOf(
                    frame.width.toFloat() / content.width,
                    frame.height.toFloat() / content.height,
                  ) * 0.9f)
                  .coerceIn(0.25f, 4f)
            Box(
              modifier =
                Modifier.onGloballyPositioned { content = it.size }
                  .graphicsLayer(scaleX = scale, scaleY = scale)
            ) {
              component()
            }
          }
        } else {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Unknown component id", style = MaterialTheme.typography.titleMedium)
              Text(id, style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
    }
  }
}

// --- Interactive state holders: a browser visitor can actually toggle these. ---

@Composable
fun StatefulCheckbox(initial: Boolean) {
  var checked by remember { mutableStateOf(initial) }
  Checkbox(checked = checked, onCheckedChange = { checked = it })
}

@Composable
fun StatefulSwitch(initial: Boolean) {
  var on by remember { mutableStateOf(initial) }
  Switch(checked = on, onCheckedChange = { on = it })
}

@Composable
fun StatefulSlider() {
  var value by remember { mutableFloatStateOf(0.5f) }
  Slider(value = value, onValueChange = { value = it })
}

@Composable
fun StatefulFilterChip(initial: Boolean) {
  var selected by remember { mutableStateOf(initial) }
  FilterChip(selected = selected, onClick = { selected = !selected }, label = { Text("Filter") })
}

// --- Held interaction sources: seed a state so the resting state layer matches the catalog. ---

@Composable
fun pressedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(PressInteraction.Press(Offset.Zero)) }
  return source
}

@Composable
fun focusedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(FocusInteraction.Focus()) }
  return source
}

@Composable
fun SegmentedToggle() {
  var selected by remember { mutableStateOf(0) }
  SingleChoiceSegmentedButtonRow {
    SegmentedButton(
      selected = selected == 0,
      onClick = { selected = 0 },
      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
    ) {
      Text("On")
    }
    SegmentedButton(
      selected = selected == 1,
      onClick = { selected = 1 },
      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
    ) {
      Text("Off")
    }
  }
}
