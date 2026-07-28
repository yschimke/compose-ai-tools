@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shaders.solidColor
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rdp
import ee.schimke.composeai.daemon.rememberOverridableRemoteColor
import ee.schimke.composeai.daemon.rememberOverridableRemoteDp
import ee.schimke.composeai.daemon.rememberOverridableRemoteFloat
import ee.schimke.composeai.daemon.rememberOverridableRemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.remote.material3.RemoteAppCard
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteButtonDefaults
import androidx.wear.compose.remote.material3.RemoteButtonGroup
import androidx.wear.compose.remote.material3.RemoteCard
import androidx.wear.compose.remote.material3.RemoteCircularProgressIndicator
import androidx.wear.compose.remote.material3.RemoteCompactButton
import androidx.wear.compose.remote.material3.RemoteIcon
import androidx.wear.compose.remote.material3.RemoteIconButton
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteOutlinedCard
import androidx.wear.compose.remote.material3.RemoteText
import androidx.wear.compose.remote.material3.RemoteTextButton
import androidx.wear.compose.remote.material3.RemoteTitleCard
import androidx.wear.compose.remote.material3.buttonSizeModifier

// ---------------------------------------------------------------------------
// Remote Compose design-catalog sticker sheet.
//
// Each `@CatalogRemoteModes` / `@CatalogRemoteLarge`-annotated function is one
// component sticker: the remote content wrapped in the module's `RemoteSticker`
// frame (RemotePreview → RemoteDocument → player raster). The function names below
// are the stable keys `catalog.spec.json` joins on.
//
// The set mirrors the **Wear Compose Remote Material 3**
// (`androidx.wear.compose.remote.material3`) component surface — the port of Wear
// Compose Material 3 to Remote Compose — so every sticker here has a Wear M3
// parallel (declared per-component in `catalog.spec.json`'s `parallel` field, and
// surfaced side-by-side by the branch's cross-system compare page). Only the two
// non-component helpers are omitted, matching the spec: `RemoteContainerPainter`
// (a painter factory, not a drawable component) and `RemoteTypographyTokens` (raw
// token table behind `RemoteTypography`). The `remote-creation-compose` shader
// sticker is the one Remote-only extra with no Wear M3 peer.
// ---------------------------------------------------------------------------

// A shared action used by every sample button — `hostAction(...)` is the Remote
// Compose equivalent of `onClick = { ... }`. The two arguments are a remote string
// payload and a remote-float handler id.
private val testAction = hostAction("catalogAction".rs, 1.rf)

// A simple five-point star used by the icon stickers. Remote Compose has no bundled
// icon set and `RemoteIcon` takes an `ImageVector`, so the catalog carries one
// hand-built vector rather than depending on `material-icons`. `RemoteIcon` re-tints
// it, so the path fill here is a placeholder.
private val starIcon: ImageVector =
  ImageVector.Builder(
      name = "Star",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .apply {
      path(fill = SolidColor(Color.White)) {
        moveTo(12f, 2f)
        lineTo(15.1f, 8.3f)
        lineTo(22f, 9.3f)
        lineTo(17f, 14.1f)
        lineTo(18.2f, 21f)
        lineTo(12f, 17.8f)
        lineTo(5.8f, 21f)
        lineTo(7f, 14.1f)
        lineTo(2f, 9.3f)
        lineTo(8.9f, 8.3f)
        close()
      }
    }
    .build()

// ---------------------------------------------------------------------------
// Buttons — the Remote Material 3 button emphasis family plus the border / shape /
// named-value variants. Parallels of the Wear M3 button family.
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun FilledRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    enabled = true.rb,
    content = { RemoteText("Filled".rs) },
  )
}

// Remote Material 3's outlined-emphasis button. Remote Compose alpha06 has no
// separate `RemoteOutlinedButton` (it ships `RemoteOutlinedCard`, but not the
// button), so we build it the same way Wear's own `OutlinedButton` does under the
// hood: a `RemoteButton` with a **transparent container** + a border. Overriding
// `containerColor` is the key — the default `buttonColors()` is `primary`-filled,
// so a bare `RemoteButton` + border would render as a *filled* button with an
// outline, not an outlined one. Every other colour is pulled straight from the
// theme (`buttonColors()` leaves un-passed colours at their exact defaults) rather
// than re-encoded here: the content is `onSurface` and the border is the theme's
// `outline` token — the same tokens Wear's `outlinedButtonColors()` uses — so the
// two systems' outlined buttons stay in lockstep with the theme. Wear M3 parallel:
// `OutlinedButton` (`Button/Outlined`).
@CatalogRemoteModes
@Composable
fun OutlinedRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    colors =
      RemoteButtonDefaults.buttonColors(
        containerColor = RemoteColor(Color.Transparent),
        contentColor = RemoteMaterialTheme.colorScheme.onSurface,
      ),
    border = 2.rdp,
    borderColor = RemoteMaterialTheme.colorScheme.outline,
    content = { RemoteText("Outlined".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun CustomShapeRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    shape = RemoteRoundedCornerShape(4.rdp),
    // Same label as its `Button/Filled` parallel — only the corner shape differs, so the
    // cross-system comparison isolates that one attribute.
    content = { RemoteText("Filled".rs) },
  )
}

/**
 * Reads its label from a Remote Compose named-value binding ([rememberNamedRemoteString]). The
 * default render shows `"Filled"` — the same label as its `Button/Filled` parallel, so the static
 * capture lines up apples-to-apples; the connector's override path
 * (`renderNow.overrides.remoteCompose.namedValues = {"label": …}`) flips the label live without
 * rebuilding the document — the interactive story the `:data-remotecompose-connector` demonstrates.
 */
@CatalogRemoteModes
@Composable
fun NamedLabelRemoteButton() = RemoteSticker {
  val label = rememberOverridableRemoteString("label", "Filled")
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    content = { RemoteText(label) },
  )
}

// A low-emphasis round text button (`RemoteTextButton`), the Remote parallel of Wear
// M3's `TextButton`.
@CatalogRemoteModes
@Composable
fun TextRemoteButton() = RemoteSticker {
  RemoteTextButton(onClick = testAction, content = { RemoteText("Child".rs) })
}

// A round icon button (`RemoteIconButton`) carrying a single `RemoteIcon`. Inside the
// button the icon inherits the button's (contrasting) content colour, so no explicit
// tint is needed. Wear M3 parallel: `IconButton`.
@CatalogRemoteModes
@Composable
fun IconRemoteButton() = RemoteSticker {
  RemoteIconButton(onClick = testAction, content = { RemoteIcon(starIcon, "Favourite".rs) })
}

// The compact, single-line button (`RemoteCompactButton`) — Wear M3 parallel:
// `CompactButton`.
@CatalogRemoteModes
@Composable
fun CompactRemoteButton() = RemoteSticker {
  RemoteCompactButton(onClick = testAction, label = { RemoteText("Compact".rs) })
}

// A pair of buttons laid out edge-to-edge by `RemoteButtonGroup`, each taking an equal
// share of the row via `weight`. Wear M3 parallel: `ButtonGroup`.
@CatalogRemoteLarge
@Composable
fun ButtonGroupRemote() = RemoteSticker {
  RemoteButtonGroup {
    RemoteButton(onClick = testAction, modifier = RemoteModifier.weight(1f.rf)) {
      RemoteText("Yes".rs)
    }
    RemoteButton(onClick = testAction, modifier = RemoteModifier.weight(1f.rf)) {
      RemoteText("No".rs)
    }
  }
}

// ---------------------------------------------------------------------------
// Containment — the Remote Material 3 card family. Parallels of the Wear M3 cards.
// ---------------------------------------------------------------------------

@CatalogRemoteLarge
@Composable
fun CardRemote() = RemoteSticker {
  RemoteCard(onClick = testAction, content = { RemoteText("Card".rs) })
}

@CatalogRemoteLarge
@Composable
fun OutlinedCardRemote() = RemoteSticker {
  // Same label as its `Card` parallel — only the outlined (vs filled) treatment differs. Unlike the
  // filled `RemoteCard` (whose surface carries a light content colour), the outlined card's
  // transparent container leaves the default content colour invisible on the sticker canvas, so pin
  // the label to the theme's `onSurface` token — the same token the outlined button uses.
  RemoteOutlinedCard(
    onClick = testAction,
    content = { RemoteText("Card".rs, color = RemoteMaterialTheme.colorScheme.onSurface) },
  )
}

@CatalogRemoteLarge
@Composable
fun TitleCardRemote() = RemoteSticker {
  RemoteTitleCard(
    onClick = testAction,
    title = { RemoteText("Morning run".rs) },
    subtitle = { RemoteText("5.2 km · 28 min".rs) },
  )
}

@CatalogRemoteLarge
@Composable
fun AppCardRemote() = RemoteSticker {
  RemoteAppCard(
    onClick = testAction,
    appName = { RemoteText("App".rs) },
    title = { RemoteText("Morning run".rs) },
    appImage = { RemoteIcon(starIcon, null, modifier = RemoteModifier.size(16.rdp)) },
    content = { RemoteText("5.2 km · 28 min".rs) },
  )
}

// ---------------------------------------------------------------------------
// Scaffold templates — a full-screen Remote Compose watch screen rather than a
// single component sticker: the whole reason the catalog exists is that a
// RemoteDocument drives a real surface (watch face / tile / widget), and one
// button on transparency doesn't show that. This is the catalog's declared hero
// (`display.hero` in catalog.spec.json), so it is what the preview server's front
// door features for `remote-m3`.
//
// Unlike every sticker above, the screen paints its own `background` fill: a
// screen IS a surface plus its content, so rasterising it onto transparency would
// defeat the point. The dark fill comes from `RemoteMaterialTheme`'s own
// background token, so the screen stays in lockstep with the (dark-first) scheme
// the rest of the sheet reads from.
//
// The status clock is a plain `RemoteText`, NOT `RemoteTimeText`: as the note by
// the text stickers records, curved text is a document op the bundled player
// can't replay yet, so a curved strip would fail the render outright. The time is
// frozen at "10:10" (the same literal the Wear M3 sibling's templates use) so the
// weekly design-artifacts render doesn't churn on the system clock.
//
// Wear M3 parallel: `Template/TimeText` — the base Wear list screen, which this
// mirrors slot for slot (status strip, list header, a stack of TitleCards).
// ---------------------------------------------------------------------------

// Kept to one short line each: at the 150dp list width a wrapping subtitle grows its card past the
// round crop, so the second card would fall off the bottom of the screen.
private val screenActivities = listOf("Morning run" to "5.2 km", "Heart rate" to "72 bpm")

@CatalogRemoteScreen
@Composable
fun WatchScreenRemote() = RemoteSticker {
  RemoteBox(
    // Clipped to a circle, not left square: the watch host crops the document to the round display,
    // so a square capture would advertise pixels the device never shows. `clip` before `background`
    // so the fill is what gets cropped.
    modifier =
      RemoteModifier.fillMaxSize()
        .clip(RemoteCircleShape)
        .background(RemoteBrush.solidColor(RemoteMaterialTheme.colorScheme.background)),
    contentAlignment = RemoteAlignment.Center,
    content = {
      // Narrower than the 227dp screen so the cards clear the round crop at their widest, the same
      // inset a Wear `ScreenScaffold` applies to its list content.
      RemoteColumn(
        modifier = RemoteModifier.width(150.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
      ) {
        RemoteText("10:10".rs, style = RemoteMaterialTheme.typography.labelMedium)
        screenActivities.forEach { (title, subtitle) ->
          RemoteTitleCard(
            onClick = testAction,
            title = { RemoteText(title.rs) },
            subtitle = { RemoteText(subtitle.rs) },
          )
        }
      }
    },
  )
}

// ---------------------------------------------------------------------------
// Communication — the determinate circular progress indicator at a fixed 66%, so the
// static capture is deterministic (the indeterminate overload animates off the
// document clock). Wear M3 parallel: `CircularProgressIndicator` (`Progress/Circular`).
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun CircularProgressRemote() = RemoteSticker {
  // The 0..1 fill is an editable `progress` float knob: the viewer's number field reseeds the arc
  // live (`rc.progress=float:<0..1>`) without re-capturing the document. Default 0.66 keeps the
  // static sticker deterministic.
  val progress = rememberOverridableRemoteFloat("progress", 0.66f)
  RemoteCircularProgressIndicator(progress = progress, modifier = RemoteModifier.size(72.rdp))
}

// ---------------------------------------------------------------------------
// Iconography — the standalone `RemoteIcon` primitive. Left at its default near-white
// content tint (the dark-first `RemoteMaterialTheme` scheme), which is why the catalog is
// tagged `display.surface: "dark"` — on a white stage this sticker is invisible.
// Wear M3 parallel: `Icon`.
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun IconRemote() = RemoteSticker {
  // An editable `iconSize` dp knob: reseeding `rc.iconSize=dp:<value>` resizes the icon live. dp is
  // carried distinctly from a bare float so the connector binds it as a density-independent value.
  val iconSize = rememberOverridableRemoteDp("iconSize", 48.dp)
  RemoteIcon(starIcon, "Star".rs, modifier = RemoteModifier.size(iconSize))
}

// ---------------------------------------------------------------------------
// Text — the Remote Material 3 text primitive at its default near-white content colour
// (the dark-first `RemoteMaterialTheme` scheme). Don't override it to a dark colour to
// "fix" a washed-out sticker: the catalog declares `display.surface: "dark"`, so the
// stage is what backs it.
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun RemoteTextSticker() = RemoteSticker {
  // Same default copy as the truncated sticker (and Wear's) — here it flows in full; the
  // `Text/MaxLines-Truncated` pair below shows the same string clipped. The body is an editable
  // `text` string knob, so the viewer can retype it live (`rc.text=<string>`).
  val text =
    rememberOverridableRemoteString(
      "text",
      "This body text is long enough to overflow two lines and truncate.",
    )
  RemoteText(text)
}

// The text primitive exercising the maxLines / overflow product on a narrow column —
// the Remote parallel of Wear M3's `Text/MaxLines-Truncated`. `RemoteText` carries the
// same `maxLines` + `overflow` knobs as Wear's `Text`.
@CatalogRemoteLarge
@Composable
fun TruncatedTextRemote() = RemoteSticker {
  RemoteText(
    // Identical copy to Wear's `Text/MaxLines-Truncated` parallel, so the pair is apples-to-apples.
    "This body text is long enough to overflow two lines and truncate.".rs,
    modifier = RemoteModifier.width(150.rdp),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

// NOTE: `RemoteTimeText` is intentionally NOT catalogued. It draws the time as
// *curved* text (a `DrawTextOnCircle` document op) that the Remote Compose player
// bundled in the renderer can't replay ("Operation 57 is not supported for this
// version" — an alpha writer/player version skew), so it fails the render outright.
// Re-add a `TimeText` sticker once the player supports the curved-text op.

// ---------------------------------------------------------------------------
// Theme — the Remote Material 3 theme surfaced as design specimens (the "even themes"
// parallels): a typography ramp and a colour-scheme swatch row, read straight from
// `RemoteMaterialTheme`. Parallels of the M3 typography / colour token sheets.
// ---------------------------------------------------------------------------

@CatalogRemoteLarge
@Composable
fun TypographyRemote() = RemoteSticker {
  RemoteColumn {
    RemoteText("Body Large".rs, style = RemoteMaterialTheme.typography.bodyLarge)
    RemoteText("Label Medium".rs, style = RemoteMaterialTheme.typography.labelMedium)
    RemoteText("Label Small".rs, style = RemoteMaterialTheme.typography.labelSmall)
  }
}

@CatalogRemoteLarge
@Composable
fun ColorSchemeRemote() = RemoteSticker {
  RemoteRow {
    RemoteBox(
      modifier =
        RemoteModifier.size(44.rdp)
          .background(RemoteBrush.solidColor(RemoteMaterialTheme.colorScheme.primary)),
      content = {},
    )
    RemoteBox(
      modifier =
        RemoteModifier.size(44.rdp)
          .background(RemoteBrush.solidColor(RemoteMaterialTheme.colorScheme.surfaceContainer)),
      content = {},
    )
    RemoteBox(
      modifier =
        RemoteModifier.size(44.rdp)
          .background(RemoteBrush.solidColor(RemoteMaterialTheme.colorScheme.onBackground)),
      content = {},
    )
  }
}

// ---------------------------------------------------------------------------
// Shaders — a document-level gradient fill (`remote-creation-compose` shaders),
// serialised into the RemoteDocument and rasterised by the player rather than an
// app-side `ShaderBrush`. The one Remote-only sticker with no Wear M3 component peer
// (it's a creation-compose primitive, not a `remote-material3` component). The middle
// stop is a named-value binding so the connector can recolour it live.
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun ShaderGradientSticker() = RemoteSticker {
  val shaderColor = rememberOverridableRemoteColor("shaderColor", Color(0xFF7DE2FF))
  val brush =
    RemoteBrush.linearGradient(
      listOf(RemoteColor(Color(0xFF101820)), shaderColor, RemoteColor(Color(0xFFFFB86C)))
    )
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize().background(brush),
    contentAlignment = RemoteAlignment.Center,
    content = { RemoteText("Shader".rs) },
  )
}
