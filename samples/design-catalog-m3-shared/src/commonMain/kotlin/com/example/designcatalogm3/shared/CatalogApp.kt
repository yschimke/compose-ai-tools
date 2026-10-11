package com.example.designcatalogm3.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

/**
 * Mounts one catalog component by id inside the M3 theme. `dark`, [fontScale] and [rtl] map the
 * viewer's `uiMode`, font-scale and locale controls. An unknown id renders a visible diagnostic.
 *
 * The body is [CatalogComponent], the same stateful composables the desktop sticker sheet bakes.
 *
 * Snapshot parity is the contract: the baked PNG is `CatalogSticker` (a wrap-content transparent
 * `Surface` with 16dp padding) cropped to its bounds; this reproduces the same sticker and
 * contain-fits it to the frame, which the viewer sizes to the snapshot, so the swap doesn't move a
 * pixel. [onFirstFrame] fires once it is measured, scaled and drawn.
 *
 * The compose-web surface can't be truly transparent, so the area around the sticker paints the
 * stage's own backdrop ([stageColor], or the checkerboard at [checkerPhase]) to continue the page.
 */
@Composable
fun CatalogApp(
  id: String,
  dark: Boolean = false,
  fontScale: Float = 1f,
  rtl: Boolean = false,
  checkerPhase: Offset = Offset.Zero,
  /**
   * The viewer's solid stage colour (`stageBg=#rrggbb`), painted behind the sticker. Null means the
   * page shows its checkerboard, which the app continues.
   */
  stageColor: Color? = null,
  /**
   * Typeface for the whole M3 type scale (URL-loaded Roboto). Null ⇒ the CMP bundled default.
   */
  fontFamily: FontFamily? = null,
  /**
   * Generic-family substitutes (`fonts.json` `role: "generic"`): `serif`, `monospace`, … →
   * URL-loaded [FontFamily], provided as `LocalGenericFonts` for `genericFontFamily`.
   */
  genericFamilies: Map<String, FontFamily> = emptyMap(),
  /**
   * Named GoogleFont substitutes (`fonts.json` `role: "named"`): display name → URL-loaded
   * [FontFamily], provided as `LocalNamedFonts` for `namedFontFamily`.
   */
  namedFamilies: Map<String, FontFamily> = emptyMap(),
  onFirstFrame: (() -> Unit)? = null,
) {
  // Typeface and palette come from the override surface (`knob.theme.*` in `LocalWasmCatalogKnobs`)
  // via the shared catalog choices, so the live render matches the desktop snapshot. A selected
  // face resolves to the URL-loaded family (see [resolveCatalogFont]).
  val scheme =
    catalogColorScheme(catalogOverrideString(CATALOG_COLORS_KNOB, CATALOG_PALETTE_M3), dark)
  val fontName = catalogOverrideString(CATALOG_FONT_KNOB, CATALOG_FONT_ROBOTO_FLEX)
  val resolvedFont = resolveCatalogFont(fontName, fontFamily, namedFamilies)
  // Shapes and typography-metrics overrides resolve through the same shared choices.
  val shapes = catalogShapes(catalogOverrideString(CATALOG_SHAPES_KNOB, ""))
  // Type scale = the `theme.font` face, then per-role-group families from `theme.fonts` (against
  // [namedFamilies]), then the `theme.typography` metrics overlay — mirroring the desktop
  // `CatalogSticker`.
  val typography =
    catalogApplyTypography(
      catalogApplyFontFamilies(
        catalogTypography(resolvedFont),
        parseCatalogFontFamilies(catalogOverrideString(CATALOG_FONTS_KNOB, "")),
        namedFamilies,
        resolvedFont ?: FontFamily.SansSerif,
      ),
      catalogOverrideString(CATALOG_TYPOGRAPHY_KNOB, ""),
    )
  // Override density's fontScale (keeping the real pixel density) and layout direction client-side.
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
    LocalNamedFonts provides namedFamilies,
  ) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
      if (id in catalogComponentIds) {
        Box(
          modifier =
            Modifier.fillMaxSize()
              .stageBackdrop(stageColor, isSystemInDarkTheme(), checkerPhase)
              .onGloballyPositioned { frame = it.size },
          contentAlignment = Alignment.Center,
        ) {
          // Contain-fit with no inset or clamp: the sticker's dp geometry matches the snapshot's,
          // so exact fit reproduces it.
          val scale =
            if (frame == IntSize.Zero || content.width == 0 || content.height == 0) 1f
            else
              minOf(frame.width.toFloat() / content.width, frame.height.toFloat() / content.height)
          Box(
            modifier =
              Modifier.onGloballyPositioned { content = it.size }
                .graphicsLayer(scaleX = scale, scaleY = scale)
          ) {
            // A 1:1 port of `CatalogSticker`: a transparent Surface with 16dp padding. Not
            // `colorScheme.surface`, which would add a panel the snapshot never had.
            Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
              Box(Modifier.padding(16.dp)) { CatalogComponent(id) }
            }
          }
        }
        // Once both boxes are measured the scale is final; let that frame and one settle frame draw
        // before signalling the viewer to swap.
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
 * Resolves a typeface [name] to the URL-loaded family, mirroring the desktop `catalogFont`:
 * * Roboto Flex → the default [family].
 * * Google Sans Flex → its named family if vendored, else `FontFamily.SansSerif` (the desktop's
 *   fallback for this face).
 * * any other named face → its named family, else the default.
 */
internal fun resolveCatalogFont(
  name: String,
  family: FontFamily?,
  named: Map<String, FontFamily>,
): FontFamily? =
  when (name) {
    CATALOG_FONT_ROBOTO_FLEX -> family
    CATALOG_FONT_GOOGLE_SANS_FLEX -> named[name] ?: FontFamily.SansSerif
    else -> named[name] ?: family
  }

/**
 * The stage backdrop the viewer shows behind the snapshot: [stageColor] when solid, else the
 * checkerboard. Something must be painted (the surface can't be transparent), and painting the
 * wrong one would visibly change the background on the swap.
 */
private fun Modifier.stageBackdrop(stageColor: Color?, dark: Boolean, phase: Offset): Modifier =
  if (stageColor != null) drawBehind { drawRect(color = stageColor) }
  else stageCheckerboard(dark, phase)

/**
 * The viewer's stage checkerboard, pixel-for-pixel: CSS `repeating-conic-gradient(<odd> 0% 25%,
 * <even> 0% 50%) / 16px 16px`. [dark] follows the page's `prefers-color-scheme`; [phase] is the
 * tile origin in this frame's CSS px so cells line up with the page.
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
