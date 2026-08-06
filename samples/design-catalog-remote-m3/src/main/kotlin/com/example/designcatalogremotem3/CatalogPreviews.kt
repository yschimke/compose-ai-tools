@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.action.valueChange
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
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteFloat
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteInt
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.selectIfGt
import androidx.compose.remote.creation.compose.state.tween
import ee.schimke.composeai.daemon.rememberOverridableRemoteColor
import ee.schimke.composeai.daemon.rememberOverridableRemoteDp
import ee.schimke.composeai.daemon.rememberOverridableRemoteFloat
import ee.schimke.composeai.daemon.rememberOverridableRemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontVariation
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
import androidx.wear.compose.remote.material3.RemoteIconButtonDefaults
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

// `hostAction(...)` — the Remote Compose way to signal the *host*: it posts a payload out of the
// document and leaves the rendered document untouched. It used to be the `onClick` of every button
// on this sheet, which is exactly why a click in the preview player never did anything visible —
// nothing in the document changed, so there was nothing to repaint. Every clickable sticker now
// uses [countedRemote] / [toggledRemote], which mutate document state so the player resolves the
// click itself. Kept here, unused by the catalog, as the documented counterpart: reach for it when
// a component genuinely means "tell the host", not "change me".
@Suppress("unused") private val hostSignalAction = hostAction("catalogAction".rs, 1.rf)

/**
 * A click counter that lives **inside the RemoteDocument** — Remote Compose's answer to
 * `remember { mutableStateOf(0) }`.
 *
 * Returns the label to draw and the [Action] to hand a component's `onClick`. The action is a
 * [valueChange] that writes `clicks + 1` back into a [rememberMutableRemoteInt], so the player
 * re-evaluates the label expression and repaints on its own — no host round-trip, which is exactly
 * what `hostAction` could not do.
 *
 * The label is a document-level conditional: `clicks > 0` picks `"<base> (n)"`, otherwise plain
 * [base]. A freshly-built document has `clicks == 0`, so the **baked catalog capture renders the
 * bare label it always has** — the counter is only reachable once a player dispatches a real touch.
 */
@Composable
private fun countedRemote(base: String): Pair<RemoteString, Action> = countedRemote(base.rs)

/**
 * [countedRemote] over a label that is itself a remote value — an overridable named string, say —
 * so a sticker whose label is driven from outside still gets the default click tally rather than a
 * bespoke affordance. The counter is appended to whatever the binding resolves to, so the override
 * it demonstrates stays fully visible.
 */
@Composable
private fun countedRemote(base: RemoteString): Pair<RemoteString, Action> {
  val clicks = rememberMutableRemoteInt(0)
  val label = selectIfGt(clicks, 0.ri, base + " (" + clicks.toRemoteString() + ")", base)
  return label to valueChange(clicks, (clicks + 1).createReference())
}

/**
 * The fallback for the one sticker with no label at all to count into: the icon button. Returns a
 * 0→1 [RemoteFloat] and the [Action] that flips it, so the caller can [tween] a colour across it.
 * Everything else on the sheet takes the default [countedRemote] tally.
 *
 * At rest the float is `0f`, and `tween(a, b, 0f)` is `a` — so the baked capture keeps the stock
 * colours and only a live tap moves it.
 */
@Composable
private fun toggledRemote(): Pair<RemoteFloat, Action> {
  val on = rememberMutableRemoteFloat(0f)
  return on to valueChange(on, (1f.rf - on).createReference())
}

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
  val (label, onClick) = countedRemote("Filled")
  RemoteButton(
    onClick = onClick,
    modifier = RemoteModifier.buttonSizeModifier(),
    enabled = true.rb,
    content = { RemoteText(label) },
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
  val (label, onClick) = countedRemote("Outlined")
  RemoteButton(
    onClick = onClick,
    modifier = RemoteModifier.buttonSizeModifier(),
    colors =
      RemoteButtonDefaults.buttonColors(
        containerColor = RemoteColor(Color.Transparent),
        contentColor = RemoteMaterialTheme.colorScheme.onSurface,
      ),
    border = 2.rdp,
    borderColor = RemoteMaterialTheme.colorScheme.outline,
    content = { RemoteText(label) },
  )
}

@CatalogRemoteModes
@Composable
fun CustomShapeRemoteButton() = RemoteSticker {
  // Same label as its `Button/Filled` parallel — only the corner shape differs, so the
  // cross-system comparison isolates that one attribute.
  val (label, onClick) = countedRemote("Filled")
  RemoteButton(
    onClick = onClick,
    modifier = RemoteModifier.buttonSizeModifier(),
    shape = RemoteRoundedCornerShape(4.rdp),
    content = { RemoteText(label) },
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
  // The counter composes over the override rather than replacing it: `countedRemote` takes the
  // bound `RemoteString` itself, so the label still resolves from the named value and only picks up
  // a `(n)` suffix once tapped. Both the override path and the click stay demonstrable.
  val (label, onClick) = countedRemote(rememberOverridableRemoteString("label", "Filled"))
  RemoteButton(
    onClick = onClick,
    modifier = RemoteModifier.buttonSizeModifier(),
    content = { RemoteText(label) },
  )
}

// A low-emphasis round text button (`RemoteTextButton`), the Remote parallel of Wear
// M3's `TextButton`.
@CatalogRemoteModes
@Composable
fun TextRemoteButton() = RemoteSticker {
  val (label, onClick) = countedRemote("Child")
  RemoteTextButton(onClick = onClick, content = { RemoteText(label) })
}

// A round icon button (`RemoteIconButton`) carrying a single `RemoteIcon`. Inside the
// button the icon inherits the button's (contrasting) content colour, so no explicit
// tint is needed. Wear M3 parallel: `IconButton`.
@CatalogRemoteModes
@Composable
fun IconRemoteButton() = RemoteSticker {
  // No label to count into, so this one reads as a favourite toggle instead: the container colour
  // tweens to the theme's primary across the in-document flag. At rest the flag is 0f and
  // `tween(a, b, 0f)` is `a`, so the baked sticker keeps the stock icon-button colours.
  val (on, onClick) = toggledRemote()
  val stock = RemoteIconButtonDefaults.iconButtonColors()
  RemoteIconButton(
    onClick = onClick,
    colors =
      RemoteIconButtonDefaults.iconButtonColors(
        containerColor = tween(stock.containerColor, RemoteMaterialTheme.colorScheme.primary, on)
      ),
    content = { RemoteIcon(starIcon, "Favourite".rs) },
  )
}

// The compact, single-line button (`RemoteCompactButton`) — Wear M3 parallel:
// `CompactButton`.
@CatalogRemoteModes
@Composable
fun CompactRemoteButton() = RemoteSticker {
  val (label, onClick) = countedRemote("Compact")
  RemoteCompactButton(onClick = onClick, label = { RemoteText(label) })
}

// A pair of buttons laid out edge-to-edge by `RemoteButtonGroup`, each taking an equal
// share of the row via `weight`. Wear M3 parallel: `ButtonGroup`.
@CatalogRemoteLarge
@Composable
fun ButtonGroupRemote() = RemoteSticker {
  // Each half counts independently, so a live tap tells you which one it landed on.
  val (yes, onYes) = countedRemote("Yes")
  val (no, onNo) = countedRemote("No")
  RemoteButtonGroup {
    RemoteButton(onClick = onYes, modifier = RemoteModifier.weight(1f.rf)) { RemoteText(yes) }
    RemoteButton(onClick = onNo, modifier = RemoteModifier.weight(1f.rf)) { RemoteText(no) }
  }
}

// ---------------------------------------------------------------------------
// Containment — the Remote Material 3 card family. Parallels of the Wear M3 cards.
// ---------------------------------------------------------------------------

@CatalogRemoteLarge
@Composable
fun CardRemote() = RemoteSticker {
  val (label, onClick) = countedRemote("Card")
  RemoteCard(onClick = onClick, content = { RemoteText(label) })
}

@CatalogRemoteLarge
@Composable
fun OutlinedCardRemote() = RemoteSticker {
  // Same label as its `Card` parallel — only the outlined (vs filled) treatment differs. Unlike the
  // filled `RemoteCard` (whose surface carries a light content colour), the outlined card's
  // transparent container leaves the default content colour invisible on the sticker canvas, so pin
  // the label to the theme's `onSurface` token — the same token the outlined button uses.
  val (label, onClick) = countedRemote("Card")
  RemoteOutlinedCard(
    onClick = onClick,
    content = { RemoteText(label, color = RemoteMaterialTheme.colorScheme.onSurface) },
  )
}

@CatalogRemoteLarge
@Composable
fun TitleCardRemote() = RemoteSticker {
  val (title, onClick) = countedRemote("Morning run")
  RemoteTitleCard(
    onClick = onClick,
    title = { RemoteText(title) },
    subtitle = { RemoteText("5.2 km · 28 min".rs) },
  )
}

@CatalogRemoteLarge
@Composable
fun AppCardRemote() = RemoteSticker {
  val (title, onClick) = countedRemote("Morning run")
  RemoteAppCard(
    onClick = onClick,
    appName = { RemoteText("App".rs) },
    title = { RemoteText(title) },
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
        screenActivities.forEach { (rowTitle, subtitle) ->
          val (title, onClick) = countedRemote(rowTitle)
          RemoteTitleCard(
            onClick = onClick,
            title = { RemoteText(title) },
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

// The text primitive carrying a *named* font family rather than one of the four generic
// typefaces — the Remote parallel of the compose-m3 catalog's `text-branded` specimen, which says
// `namedFontFamily("Orbitron")` for the same reason. This is the only sticker in any catalog whose
// `.rc` document names a family, and it exists to keep that path rendered and diffed: a named family
// reaches the player as a *text id*, not a name, so it is resolved by a code path no other document
// exercises.
//
// `google:` namespaces the name as a Google Fonts family — `RemoteFontFamily.Named` carries an
// opaque string, so the prefix is what tells both render lanes where the face comes from rather than
// leaving them to guess from the name. Orbitron because the catalog already vendors its faces
// (`role: "named"` in the fonts manifest), so the snapshot renderer resolves it locally while the
// browser fetches the same family, and the parity page compares like with like.
@CatalogRemoteModes
@Composable
fun BrandedTextRemote() = RemoteSticker {
  // Short copy because Orbitron is a wide face and the frame is only 200dp. Note this does not
  // fully avoid clipping in the browser lane, and cannot: the document carries geometry the
  // *authoring* renderer measured, and that renderer resolves this family to Roboto today, so the
  // player draws a wider face into a box measured for a narrower one. That clip is the renderer gap
  // showing through, not a player bug — it closes when the snapshot lane resolves the family too.
  RemoteText("Orbitron".rs, fontFamily = RemoteFontFamily.Named("google:Orbitron"))
}

// A *specimen sheet*: four branded families, each drawing its own name. One sticker, four different
// typefaces — which is the point. A lane that cannot resolve a named family substitutes one face for
// all four, and four identical-looking lines is a failure nobody has to measure to see; a single
// branded line (`BrandedTextRemote` above) only looks "a bit off" unless you already know the face.
//
// The four are the ones the catalog vendors (`role: "named"` in the wasm app's fonts manifest), and
// they are deliberately unalike — geometric, script, grotesk, monospace — so a substitution cannot
// hide in a family resemblance. `google:` namespaces each name as a Google Fonts family: the browser
// lane fetches it from the CSS API, the wasm lane reads the vendored copy, and the server-side lanes
// resolve it through the shared font cache.
@CatalogRemoteLarge
@Composable
fun TypefaceSpecimenRemote() = RemoteSticker {
  RemoteColumn {
    RemoteText(
      "Orbitron".rs,
      fontSize = 22.rsp,
      fontFamily = RemoteFontFamily.Named("google:Orbitron"),
    )
    RemoteText(
      "Lobster Two".rs,
      fontSize = 22.rsp,
      fontFamily = RemoteFontFamily.Named("google:Lobster Two"),
    )
    RemoteText(
      "Space Grotesk".rs,
      fontSize = 22.rsp,
      fontFamily = RemoteFontFamily.Named("google:Space Grotesk"),
    )
    RemoteText(
      "JetBrains Mono".rs,
      fontSize = 22.rsp,
      fontFamily = RemoteFontFamily.Named("google:JetBrains Mono"),
    )
  }
}

// The `wght` axis of a **variable** font, four instances of one face.
//
// This is a different capability from the specimen above, and no other document in any catalog
// carries it: `RemoteText` writes `fontVariationSettings` into the document as an axis-tag/value
// pair list, so the player must both resolve the family *and* apply the axes to the instance it
// draws. A lane that resolves the family but drops the axes renders four lines in one weight —
// visibly wrong, and wrong in a way that a static-weight specimen cannot expose.
//
// Roboto Flex because it is the catalog's own default face (`role: "default"` in the fonts
// manifest) and a genuine variable font: one file serving `wght` 100..1000, so the four lines below
// are four *instances* of a single downloaded file rather than four separate faces.
@CatalogRemoteLarge
@Composable
fun VariableWeightRemote() = RemoteSticker {
  RemoteColumn {
    for (weight in listOf(100, 400, 700, 1000)) {
      RemoteText(
        "wght $weight".rs,
        fontSize = 20.rsp,
        fontFamily = RemoteFontFamily.Named("google:Roboto Flex"),
        fontVariationSettings = FontVariation.Settings(FontVariation.weight(weight)),
      )
    }
  }
}

// The `wdth` axis of the same variable font — the axis a weight-only implementation misses.
//
// Weight is the axis every text stack can fake: a player that ignores `wght` but honours
// `FontWeight` still lands near the right thickness, so a `wght` ramp alone cannot tell "applied the
// axis" from "synthesised a bold". Width can't be faked — nothing in a text API asks for a narrower
// face — so three lines at `wdth` 25 / 100 / 151 either differ in set width or the axes were
// dropped. Roboto Flex serves that whole range from the one file.
@CatalogRemoteLarge
@Composable
fun VariableWidthRemote() = RemoteSticker {
  RemoteColumn {
    for (width in listOf(25f, 100f, 151f)) {
      RemoteText(
        // The same word on every line. Three different strings would each set to their own length
        // and the axis would be invisible next to that difference; sharing a word makes the narrow
        // instance unmistakably narrower, which is the whole claim the sticker exists to make.
        "Hamburg · wdth ${width.toInt()}".rs,
        fontSize = 22.rsp,
        fontFamily = RemoteFontFamily.Named("google:Roboto Flex"),
        fontVariationSettings = FontVariation.Settings(FontVariation.width(width)),
      )
    }
  }
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
