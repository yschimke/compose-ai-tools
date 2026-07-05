package com.example.cmpwasmcatalog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.designcatalogm3.shared.CatalogComponent
import com.example.designcatalogm3.shared.LocalGenericFonts
import com.example.designcatalogm3.shared.catalogComponentIds
import com.example.designcatalogm3.shared.catalogTypography

/**
 * Mounts one catalog component by id inside the M3 theme, centred on the surface. `dark` flips the
 * color scheme so the viewer's `uiMode` deep-link parameter maps straight through; [fontScale] and
 * [rtl] map the viewer's font-scale slider and locale control so those overrides drive the
 * in-browser render too. An unknown id renders a visible diagnostic rather than a blank canvas.
 *
 * The component body itself is [CatalogComponent] from `:samples:design-catalog-m3-shared` — the
 * exact same composables the desktop `:samples:design-catalog-m3` sticker sheet bakes — with
 * `interactive = true` so a visitor can toggle switches, drag sliders and watch progress animate.
 *
 * **Snapshot parity is the contract.** The baked catalog PNG is `CatalogSticker` — a wrap-content
 * `Surface` holding the component behind 16dp padding — cropped to its bounds. This app reproduces
 * exactly that sticker (same dp geometry, same `Surface` default colour) on a **transparent**
 * viewport, and contain-fit scales it to the frame. The embedding viewer sizes the iframe to the
 * snapshot `<img>`'s rendered box, so the same-aspect sticker fills it edge-to-edge and the
 * snapshot→Wasm switch doesn't move a pixel. [showBackground] = false drops the sticker's surface
 * fill (transparent iframe ⇒ the viewer's checkerboard shows through), leaving just the component.
 *
 * [onFirstFrame] fires once, after the sticker has been measured, fit-scaled, and drawn — the
 * embedding viewer keeps the snapshot on-stage until this signal so enabling Wasm never flashes.
 *
 * The area *around* the sticker can't be truly transparent — the compose-web surface paints an
 * opaque base — so the app paints the serve stage's own checkerboard there instead ([checkerPhase]
 * is the stage pattern's tile origin in this frame's CSS-px coordinates, supplied by the viewer),
 * making the canvas visually continue the page behind it. That's also what makes [showBackground] =
 * false read as "component on the checkerboard".
 */
@Composable
fun CatalogApp(
  id: String,
  dark: Boolean = false,
  fontScale: Float = 1f,
  rtl: Boolean = false,
  showBackground: Boolean = true,
  checkerPhase: Offset = Offset.Zero,
  /**
   * Typeface for the whole M3 type scale — the URL-loaded Roboto that matches what the Android
   * renderer baked into the snapshots. Null ⇒ the CMP bundled default (fetch failed/timed out).
   */
  fontFamily: FontFamily? = null,
  /**
   * Generic-family substitutes (`fonts.json` `role: "generic"`): family name (`serif`, `monospace`,
   * …) → the URL-loaded [FontFamily] holding the same files Android's system font table resolves
   * that name to. Provided as `LocalGenericFonts`, which `genericFontFamily` (in the shared module)
   * consults. Empty ⇒ skiko's own (bundled-font) fallback, as before.
   */
  genericFamilies: Map<String, FontFamily> = emptyMap(),
  onFirstFrame: (() -> Unit)? = null,
) {
  val scheme = if (dark) darkColorScheme() else lightColorScheme()
  // Re-point density's fontScale (preserving the real pixel density) and the layout direction, so
  // the viewer's font-scale + locale controls take effect client-side — same overrides the server
  // render honours, just running in the browser sandbox.
  val density = LocalDensity.current
  val scaled = Density(density = density.density, fontScale = fontScale)
  val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
  // Frame + sticker bounds, measured to contain-fit the sticker to the stage (see below).
  var frame by remember { mutableStateOf(IntSize.Zero) }
  var content by remember { mutableStateOf(IntSize.Zero) }
  var signalled by remember { mutableStateOf(false) }
  CompositionLocalProvider(
    LocalDensity provides scaled,
    LocalLayoutDirection provides direction,
    LocalGenericFonts provides genericFamilies,
  ) {
    MaterialTheme(colorScheme = scheme, typography = catalogTypography(fontFamily)) {
      if (id in catalogComponentIds) {
        Box(
          modifier =
            Modifier.fillMaxSize()
              .stageCheckerboard(isSystemInDarkTheme(), checkerPhase)
              .onGloballyPositioned { frame = it.size },
          contentAlignment = Alignment.Center,
        ) {
          // Contain-fit, no inset and no clamp: the sticker's dp geometry matches the snapshot's,
          // so when the viewer sizes this frame to the snapshot's rendered box the exact fit is
          // what
          // reproduces it — any breathing-room factor or cap would reintroduce a visible jump.
          val scale =
            if (frame == IntSize.Zero || content.width == 0 || content.height == 0) 1f
            else
              minOf(frame.width.toFloat() / content.width, frame.height.toFloat() / content.height)
          Box(
            modifier =
              Modifier.onGloballyPositioned { content = it.size }
                .graphicsLayer(scaleX = scale, scaleY = scale)
          ) {
            // The sticker itself — a 1:1 port of the shared `CatalogSticker` (Surface at its
            // default
            // colour + 16dp padding), so the box the snapshot baked is the box we draw.
            Surface(
              color = if (showBackground) MaterialTheme.colorScheme.surface else Color.Transparent,
              contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
              Box(Modifier.padding(16.dp)) { CatalogComponent(id, interactive = true) }
            }
          }
        }
        // First-frame signal: once both boxes are measured the fit scale is final; let that frame
        // (and one settle frame for the scale recomposition) actually draw before telling the
        // embedding viewer it can swap the snapshot out.
        if (
          onFirstFrame != null && !signalled && frame != IntSize.Zero && content != IntSize.Zero
        ) {
          LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            signalled = true
            onFirstFrame()
          }
        }
      } else {
        Surface(modifier = Modifier.fillMaxSize()) {
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
        // Still signal on the diagnostic branch — the viewer must not wait forever on a bad id.
        if (onFirstFrame != null && !signalled) {
          LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            signalled = true
            onFirstFrame()
          }
        }
      }
    }
  }
}

/**
 * The serve viewer's stage checkerboard, replicated pixel-for-pixel: CSS
 * `repeating-conic-gradient(<odd> 0% 25%, <even> 0% 50%) / 16px 16px` — 8px squares where the
 * tile's top-left square is the [even] colour. [dark] follows the *page's* `prefers-color-scheme`
 * (the stage's own media query), not the component's theme. [phase] is the pattern's tile origin in
 * this frame's coordinates (CSS px), so the drawn cells line up exactly with the page's cells
 * outside the iframe.
 */
private fun Modifier.stageCheckerboard(dark: Boolean, phase: Offset): Modifier = drawBehind {
  val even = if (dark) Color(0xFF1D1D20) else Color(0xFFFFFFFF)
  val odd = if (dark) Color(0xFF26262B) else Color(0xFFF4F4F6)
  val cell = 8.dp.toPx()
  val tile = cell * 2
  // First cell at or left of 0, congruent with the tile origin (so parity is origin-anchored).
  val ox = phase.x.dp.toPx().mod(tile) - tile
  val oy = phase.y.dp.toPx().mod(tile) - tile
  var row = 0
  var y = oy
  while (y < size.height) {
    var col = 0
    var x = ox
    while (x < size.width) {
      drawRect(
        color = if ((row + col) % 2 == 0) even else odd,
        topLeft = Offset(x, y),
        size = Size(cell, cell),
      )
      x += cell
      col++
    }
    y += cell
    row++
  }
}
