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
 * Mounts one catalog component by id inside the M3 theme, centred on the surface. `dark` flips the
 * color scheme so the viewer's `uiMode` deep-link parameter maps straight through; [fontScale] and
 * [rtl] map the viewer's font-scale slider and locale control so those overrides drive the
 * in-browser render too. An unknown id renders a visible diagnostic rather than a blank canvas.
 *
 * The component body itself is [CatalogComponent] from `:samples:design-catalog-m3-shared` — the
 * exact same composables the desktop `:samples:design-catalog-m3` sticker sheet bakes. Its controls
 * are unconditionally stateful (they used to be selected by an `interactive` flag this tier passed
 * as `true`, see issue #3674), so a visitor can toggle switches, drag sliders and type into the
 * text fields, and the sticker this tier draws is exactly the one the catalog published.
 *
 * **Snapshot parity is the contract.** The baked catalog PNG is `CatalogSticker` — a wrap-content
 * **transparent** `Surface` holding the component behind 16dp padding — cropped to its bounds. This
 * app reproduces exactly that sticker (same dp geometry, same transparent surface) on a transparent
 * viewport, and contain-fit scales it to the frame. The embedding viewer sizes the iframe to the
 * snapshot `<img>`'s rendered box, so the same-aspect sticker fills it edge-to-edge and the
 * snapshot→Wasm switch doesn't move a pixel — and, since the sticker paints no fill of its own,
 * doesn't add a panel the snapshot never had either.
 *
 * [onFirstFrame] fires once, after the sticker has been measured, fit-scaled, and drawn — the
 * embedding viewer keeps the snapshot on-stage until this signal so enabling Wasm never flashes.
 *
 * The area *around* the sticker can't be truly transparent — the compose-web surface paints an
 * opaque base — so the app paints the serve stage's own checkerboard there instead ([checkerPhase]
 * is the stage pattern's tile origin in this frame's CSS-px coordinates, supplied by the viewer),
 * making the canvas visually continue the page behind it. With the sticker itself transparent, that
 * checkerboard is what shows through behind the component — exactly what the baked PNG looks like
 * on the same stage.
 */
@Composable
fun CatalogApp(
  id: String,
  dark: Boolean = false,
  fontScale: Float = 1f,
  rtl: Boolean = false,
  checkerPhase: Offset = Offset.Zero,
  /**
   * The viewer's resolved solid stage colour (`stageBg=#rrggbb`), painted behind the transparent
   * sticker so the swap doesn't change what the component sits on. Null ⇒ the page is in its
   * transparent/checkerboard mode and the app continues that pattern instead.
   */
  stageColor: Color? = null,
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
  /**
   * Named downloadable-GoogleFont substitutes (`fonts.json` `role: "named"`): the font's display
   * name (`Orbitron`, `Space Grotesk`, …) → the URL-loaded [FontFamily] holding the vendored faces.
   * Provided as `LocalNamedFonts`, which `namedFontFamily` (in the shared module) consults. Empty ⇒
   * the shared fallback (platform sans), as before.
   */
  namedFamilies: Map<String, FontFamily> = emptyMap(),
  onFirstFrame: (() -> Unit)? = null,
) {
  // Typeface + palette are read from the override surface (the viewer pushes `knob.theme.*` into
  // `LocalWasmCatalogKnobs`) and resolved via the *shared* catalog choices, so the live Wasm render
  // agrees with the desktop-baked snapshot. No seed ⇒ Roboto Flex + the M3 light/dark scheme (the
  // baked default). A selected typeface resolves to the URL-loaded family: the default `fontFamily`
  // for Roboto Flex, else the matching `role: "named"` family from `fonts.json` (falling back to
  // the
  // default when a face isn't vendored, e.g. Google Sans Flex).
  val scheme =
    catalogColorScheme(catalogOverrideString(CATALOG_COLORS_KNOB, CATALOG_PALETTE_M3), dark)
  val fontName = catalogOverrideString(CATALOG_FONT_KNOB, CATALOG_FONT_ROBOTO_FLEX)
  val resolvedFont = resolveCatalogFont(fontName, fontFamily, namedFamilies)
  // Shapes + typography-metrics overrides resolve through the same shared choices, so the live Wasm
  // corners / type scale track the desktop-baked snapshot. No seed ⇒ stock M3 shapes + the
  // font-only
  // type scale (the baked default).
  val shapes = catalogShapes(catalogOverrideString(CATALOG_SHAPES_KNOB, ""))
  // Type scale = the `theme.font` single face, then per-role-group families from `theme.fonts`
  // (e.g. display=Orbitron, body=Space Grotesk — resolved against the URL-loaded [namedFamilies],
  // the same `role: "named"` faces `fonts.json` lists), then the `theme.typography` metrics
  // overlay.
  // Mirrors the desktop `CatalogSticker` so the live Wasm render brands identically to the baked
  // sticker; absent the fonts knob the middle step is a no-op, so an un-overridden render is
  // pixel-identical.
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
            // The sticker itself — a 1:1 port of the shared `CatalogSticker`: a TRANSPARENT
            // Surface + 16dp padding, so the box the snapshot baked is the box we draw. The
            // colour is deliberately not `colorScheme.surface`: the desktop `CatalogStickerFrame`
            // renders component stickers on `Color.Transparent` so each reads as a silhouette on
            // the viewer's backing, and painting a surface fill here put a solid `#FFFBFF` panel
            // behind the component the moment the viewer handed the render to this tier.
            Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
              Box(Modifier.padding(16.dp)) { CatalogComponent(id) }
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
 * Resolves a selected typeface [name] to the URL-loaded family, mirroring the desktop `catalogFont`
 * so the live Wasm render matches the baked snapshot:
 * * Roboto Flex → the default [family] (`fonts.json` `role: "default"`).
 * * Google Sans Flex → its `role: "named"` family if the dist vendors it, else
 *   `FontFamily.SansSerif` — the **same** fallback the desktop catalog uses for the unvendored
 *   brand face (not the default), so the two tiers agree on that choice.
 * * any other named face (Lobster Two, …) → its named family, else the default.
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
 * The serve viewer's stage checkerboard, replicated pixel-for-pixel: CSS
 * `repeating-conic-gradient(<odd> 0% 25%, <even> 0% 50%) / 16px 16px` — 8px squares where the
 * tile's top-left square is the [even] colour. [dark] follows the *page's* `prefers-color-scheme`
 * (the stage's own media query), not the component's theme. [phase] is the pattern's tile origin in
 * this frame's coordinates (CSS px), so the drawn cells line up exactly with the page's cells
 * outside the iframe.
 */
/**
 * The stage backdrop the embedding viewer is showing behind the snapshot, painted here so the
 * transparent sticker sits on the same thing it sits on in the PNG lane. [stageColor] is the
 * viewer's resolved solid stage (`#fff` for a light preview, `#1d1d20` for a dark one); null means
 * the page is in its transparent/checkerboard mode, so we continue that pattern instead.
 *
 * This has to track the page: the app's own surface can't be truly transparent, so *something* is
 * painted here either way, and painting the wrong one is a visible background change on the
 * snapshot⇄Wasm swap — a checkerboard appearing behind a component the snapshot showed on flat
 * white.
 */
private fun Modifier.stageBackdrop(stageColor: Color?, dark: Boolean, phase: Offset): Modifier =
  if (stageColor != null) drawBehind { drawRect(color = stageColor) }
  else stageCheckerboard(dark, phase)

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
