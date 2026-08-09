@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.remote.material3.RemoteTypography
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * The catalog's **typeface themes** — the Remote Compose sibling of
 * `:samples:design-catalog-wear-m3`'s `WearThemeCatalogs.kt`, and the only themes this catalog
 * declares. They populate the preview server's **Theme** select, so any sticker can be re-rendered
 * in either face without a preview change.
 *
 * ## Where the Theme select actually offers them
 *
 * On a session that **re-runs these composables** — `compose-preview serve` against this module, or
 * any daemon carrying its classes. A provider is a `PreviewWrapperProvider`: applying one means
 * composing the preview again inside `Wrap`, which authors a *new* `RemoteDocument`.
 *
 * The **published catalog** (`design-artifacts/remote-m3`, served at `preview.coo.ee/remote-m3/`)
 * is not such a session. Every sticker here emits a Remote Compose document, so `bundle pack`
 * carries the captured `ir/<id>.rc` and drops the module bytecode that authored it
 * (`BundlePreviewTask` — "an IR preview contributes no module bytecode"), and the bundle daemon
 * redraws by replaying that document. There is no composition left to wrap, so the server refuses a
 * `?themeProvider=` render with a terminal 409 and the preview server omits these chips there
 * rather than offering a click that can only fail. What still carries the evidence on the published
 * catalog is the pair of synthesised specimen sheets below — one baked render per theme.
 *
 * Making the published catalog themable means capturing a document **per declared theme** at pack
 * time; nothing in the replay path can substitute a typeface after the fact.
 *
 * ## What they change, and what they deliberately don't
 *
 * A Remote Compose document names its typeface in one of three ways (see
 * `docs/design/RC_PLAYER_TYPEFACES.md`): a **built-in family id** — `0 = default`, `1 = sans-serif`,
 * `2 = serif`, `3 = monospace` — a **named family** (`RemoteFontFamily.Named("google:Orbitron")`),
 * or an **embedded face**.
 *
 * These themes move the **built-in default family**: every sticker that draws through
 * `RemoteMaterialTheme.typography` — which is every sticker that doesn't name a family for itself —
 * and nothing else. The named-family stickers keep the exact faces they declare:
 * [BrandedTextRemote], [TypefaceSpecimenRemote], [VariableWeightRemote] and [VariableWidthRemote]
 * are untouched under either theme. That is the point rather than an omission — those stickers
 * exist to keep the named-family and font-variation-axis paths rendered and diffed, and a theme
 * that overrode them would delete the one place each capability is covered.
 *
 * The generic ids 1–3 are equally out of scope, and for the same reason: a family is chosen at the
 * `RemoteText` call site, so a sticker that asked for `RemoteFontFamily.Monospace` asked for
 * monospace. No sticker in this catalog does, so in practice id 0 is the whole surface a theme has
 * to move.
 *
 * ## Why the choice travels as a CompositionLocal
 *
 * `RemoteMaterialTheme` is `@RemoteComposable`: it can only be installed *inside* the document,
 * i.e. inside [RemoteSticker]'s `RemoteOverridablePreview`. A `PreviewWrapperProvider` wraps the
 * whole `@Preview`, which is outside it. So a provider publishes its choice as a plain
 * [LocalRemoteCatalogFont] and [RemoteSticker] installs the matching [RemoteTypography] once it is
 * in remote scope. With no provider the local is null and the sticker composes exactly as before,
 * so the vanilla `composePreviewRenderAll` output is byte-for-byte unchanged.
 *
 * ## A caveat on the synthesised specimen sheets
 *
 * `@WearThemeCatalog` also makes the renderer synthesise a specimen sheet per theme, read
 * reflectively off `androidx.wear.compose.material3.MaterialTheme` — a *phone/watch* Compose theme
 * these providers never install, because a Remote Compose theme isn't one. Those two sheets are
 * therefore not evidence of anything here; the stickers re-rendered under a selected theme are.
 * (The Wear sheet could not show a typeface anyway: it lays its type rows out past the bottom of
 * its fixed canvas — see the note in `docs/design/evidence/wear-m3-theme-fonts/`.)
 */

/** The declared typeface-theme names, in display order. Each is a Google Fonts family name. */
val REMOTE_THEME_FONTS: List<String> = listOf("Roboto Flex", "Google Sans Flex")

/**
 * The selected theme's Google Fonts family, or null when no theme provider is installed — the plain
 * `composePreviewRenderAll` render, which stays on the stock built-in default family.
 */
internal val LocalRemoteCatalogFont = compositionLocalOf<String?> { null }

/**
 * The Remote type scale for a selected typeface [family]: every role re-pointed via
 * `defaultFontFamily`, which is what turns the built-in default id into a `google:`-namespaced
 * lookup — for theme-styled text only.
 *
 * `google:` namespaces the name the same way the catalog's named-family stickers do, so every lane
 * knows where the face comes from instead of guessing: the browser fetches it from the CSS API, the
 * wasm lane reads the vendored copy, and the server-side lanes resolve it through the shared font
 * cache.
 *
 * Both faces have to be **vendored into `:samples:cmp-wasm-catalog`'s `fonts.json`** for that
 * middle lane, which is manifest-only and never fetches — an unlisted family fails
 * `RcComposeSupport.fontFamilyIssue`'s availability check rather than degrading to a substitute
 * face. Roboto Flex was already there as the `role: "default"` family; Google Sans Flex was added
 * alongside these themes, under the redistribution clearance its `fonts/README.md` bullet records.
 */
fun remoteCatalogTypography(family: String): RemoteTypography =
  RemoteTypography(defaultFontFamily = RemoteFontFamily.Named("google:$family"))

/** Publishes [family] to [RemoteSticker], which installs it once inside the remote document. */
@Composable
private fun RemoteFontThemeOverride(family: String, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalRemoteCatalogFont provides family, content = content)
}

/**
 * Roboto Flex — the catalog's own default face (`role: "default"` in the fonts manifest) and the
 * variable font its `wght` / `wdth` axis stickers already exercise.
 */
@WearThemeCatalog(name = "Roboto Flex", group = "Typeface")
class RemoteRobotoFlexThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    RemoteFontThemeOverride("Roboto Flex", content)
}

/** Google Sans Flex — the Material 3 Expressive brand face, served by the Google Fonts CSS API. */
@WearThemeCatalog(name = "Google Sans Flex", group = "Typeface")
class RemoteGoogleSansFlexThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    RemoteFontThemeOverride("Google Sans Flex", content)
}
