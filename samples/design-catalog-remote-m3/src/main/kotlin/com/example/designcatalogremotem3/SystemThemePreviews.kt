@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteConstantCacheKey
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The catalog's coverage of the **second** Remote Compose colour-theming mechanism.
 *
 * A document can carry theming two unrelated ways, and until this sticker the published catalogs
 * only exercised one:
 * - **named colour state** (`USER:WearM3.<role>`) — a slot the host overwrites outright, which is
 *   what every themed sticker in [CatalogPreviews] uses and what a `themeProvider` request seeds
 *   into. 16 of the catalog's documents declare it.
 * - **`ColorTheme` operations** — a light and a dark colour captured *in the document*, which the
 *   player picks between from the requested theme. **No published document emitted one**, so
 *   everything downstream that reads it — `RcDocumentCapabilities`, and the replay-override routing
 *   built on it (compose-ai-tools#3936) — had only synthetic fixtures to test against.
 *
 * `homeassistant-remotecompose` now ships both: its Wear widgets take the named path and its
 * launcher widgets take this one, via Android's system theme resources. This sticker is the
 * catalog's copy of that second shape so the parity and capability work has a real document.
 *
 * The two are deliberately *not* interchangeable. A `ColorTheme` op holds its colours inline, so a
 * palette override has no slot to write into; it answers light/dark and nothing else. That
 * distinction is the one this sticker exists to keep honest.
 */
/**
 * A colour the **host** resolves from Android's system theme, written into the document as a
 * `ColorTheme` operation and referenced by id.
 *
 * Built without a constant value, and that is load-bearing. `BackgroundModifier` branches on
 * `hasConstantValue`: a colour carrying one is written as literal red/green/blue by
 * `SolidBackgroundModifier` and its id provider is never asked for. Subclassing
 * `RemoteColor(fallbackArgb)` and overriding `writeToDocument` therefore emits **no** `ColorTheme`
 * operation at all — the first two revisions of this sticker did exactly that, rendered three
 * correct-looking swatches, and produced a document with zero themed colours in it. The fallback
 * that wins is the light value a host without the resources would draw anyway, so the loss is
 * invisible in pixels and only the decoded document shows it. `homeassistant-remotecompose` shipped
 * the same mistake in its launcher palette (yschimke/homeassistant-remotecompose#573).
 *
 * The id-provider constructor and `RemoteConstantCacheKey` are `internal` upstream — there is no
 * public route to a document-written colour at `remote-compose` 1.0.0-alpha17 — hence the
 * file-level suppression.
 */
private fun systemThemeColor(
  lightResource: Short,
  darkResource: Short,
  lightFallback: Color,
  darkFallback: Color,
): RemoteColor {
  val lightArgb = lightFallback.toArgb()
  val darkArgb = darkFallback.toArgb()
  return RemoteColor(
    RemoteConstantCacheKey("SystemTheme:$lightResource/$darkResource:$lightArgb/$darkArgb")
  ) { creationState ->
    creationState.document
      .addThemedColor(Rc.AndroidColors.GROUP, lightResource, darkResource, lightArgb, darkArgb)
      .toInt()
  }
}

/**
 * Three swatches painted from system-theme resources rather than named state, so the document
 * carries `ColorTheme` operations and no colour-typed `NamedVariable`.
 *
 * The fallbacks are what a host without the resources draws, and are what this sticker's baked PNG
 * shows — the point of the fixture is the operations in the bytes, not the pixels.
 */
@CatalogRemoteModes
@Composable
fun SystemThemeSwatchesRemote() = RemoteSticker { RemoteColumn { RemoteRow { SwatchTriple() } } }

@Composable
private fun SwatchTriple() {
  RemoteBoxSwatch(
    systemThemeColor(
      Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_HIGH_LIGHT,
      Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_HIGH_DARK,
      Color(0xFFE6E0E9),
      Color(0xFF2B2930),
    )
  )
  RemoteBoxSwatch(
    systemThemeColor(
      Rc.AndroidColors.SYSTEM_PRIMARY_LIGHT,
      Rc.AndroidColors.SYSTEM_PRIMARY_DARK,
      Color(0xFF65558F),
      Color(0xFFD0BCFF),
    )
  )
  RemoteBoxSwatch(
    systemThemeColor(
      Rc.AndroidColors.SYSTEM_ON_SURFACE_LIGHT,
      Rc.AndroidColors.SYSTEM_ON_SURFACE_DARK,
      Color(0xFF1D1B20),
      Color(0xFFE6E0E9),
    )
  )
}

/**
 * The `background(RemoteColor)` overload, deliberately — **not**
 * `background(RemoteBrush.solidColor(color))`.
 *
 * They are not equivalent for a colour that writes itself into the document. The brush overload
 * takes the colour's constant value, so a `SystemThemedRemoteColor` reaches the bytes as a baked
 * `PaintData` and its `writeToDocument` is never called: the first cut of this sticker rendered
 * three correct-looking swatches whose document contained zero `ColorTheme` operations. The
 * `RemoteColor` overload resolves the colour through the document, which is what emits the op — and
 * is what `cardChrome` in `homeassistant-remotecompose` uses on the launcher path.
 */
@Composable
private fun RemoteBoxSwatch(color: RemoteColor) =
  androidx.compose.remote.creation.compose.layout.RemoteBox(
    modifier = RemoteModifier.size(44.rdp).background(color),
    content = {},
  )
