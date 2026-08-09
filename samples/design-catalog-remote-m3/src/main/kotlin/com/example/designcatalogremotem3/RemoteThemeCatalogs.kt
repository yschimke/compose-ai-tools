@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteTypography
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * The catalog's declared themes — the Remote Compose port of
 * `:samples:design-catalog-wear-m3`'s `WearThemeCatalogs.kt`, deliberately **the same five names in
 * the same groups** so the two catalogs' Theme selects read as one set and the design-artifacts
 * cross-system compare pairs them off.
 *
 * ## What each one moves
 *
 * Four are **palettes**, ported role-for-role from the sibling's `wearColorScheme`: the JetBrains
 * seed purple of [RemoteKotlinConfThemeCatalog], and the single-role [RemoteCoralThemeCatalog] /
 * [RemoteTealThemeCatalog] edits. One is a **typeface**, [RemoteGoogleSansFlexThemeCatalog], which
 * keeps the M3 palette exactly so a side-by-side against [RemoteM3ThemeCatalog] reads as a pure
 * type comparison rather than a type *and* colour change — the same contract the sibling documents.
 *
 * [RemoteM3ThemeCatalog] is the stock scheme unchanged. Selecting it is a no-op by construction,
 * which is the point: it is the baseline the other four are read against.
 *
 * ## Where the colours end up, and why that matters
 *
 * `RemoteMaterialTheme`'s scheme is not constant-folded into the document. Each role is emitted as
 * **named remote state** — `USER:WearM3.primary`, `USER:WearM3.surfaceContainer`,
 * `USER:WearM3.onSurface` and the rest — which is what the player's `setNamedColorOverride` reaches.
 * A palette is therefore expressible on a *replayed* document, with no recomposition: the same seam
 * the preview server's `rc.<name>=color:…` overrides already drive. The typeface is host-side in a
 * different way — the stickers emit the built-in default family id, and which face that resolves to
 * is the player's choice at draw time, not a property of the document.
 *
 * That is the whole reason these are worth having here rather than only on the Wear sibling: they
 * give the Remote lane's theming a payload that a published, IR-backed catalog can actually apply.
 *
 * ## Typeface scope, and the vendoring constraint behind it
 *
 * The palettes keep the catalog's default face. Only [RemoteGoogleSansFlexThemeCatalog] moves the
 * typeface, and the sibling's KotlinConf type pairing (JetBrains Mono on titles, Inter on body) is
 * deliberately **not** ported with its palette. A family this catalog names has to be vendored into
 * `:samples:cmp-wasm-catalog`'s `fonts.json` — that lane is manifest-only and never fetches, so an
 * unlisted family fails `RcComposeSupport.fontFamilyIssue`'s availability check rather than
 * degrading to a substitute — and each addition carries its own redistribution clearance in
 * `fonts/README.md`. Porting two more faces is a separate change with a separate licence question;
 * the palette lands here without waiting on it.
 *
 * A theme moves the **built-in default family** only: every sticker drawing through
 * `RemoteMaterialTheme.typography`, and nothing else. The named-family stickers keep the exact faces
 * they declare — [BrandedTextRemote], [TypefaceSpecimenRemote], [VariableWeightRemote] and
 * [VariableWidthRemote] are untouched under every theme. That is the point rather than an omission:
 * those stickers exist to keep the named-family and font-variation-axis paths rendered and diffed,
 * and a theme that overrode them would delete the one place each capability is covered. The generic
 * ids 1–3 are equally out of scope, and for the same reason — a family is chosen at the `RemoteText`
 * call site, so a sticker that asked for `RemoteFontFamily.Monospace` asked for monospace.
 *
 * ## Why the choice travels as a CompositionLocal
 *
 * `RemoteMaterialTheme` is `@RemoteComposable`: it can only be installed *inside* the document, i.e.
 * inside [RemoteSticker]'s `RemoteOverridablePreview`. A `PreviewWrapperProvider` wraps the whole
 * `@Preview`, which is outside it. So a provider publishes its choice as a plain
 * [LocalRemoteCatalogTheme] and [RemoteSticker] installs the matching scheme + type scale once it is
 * in remote scope. With no provider the local is null, nothing is installed, and the vanilla
 * `composePreviewRenderAll` output is byte-for-byte unchanged.
 *
 * ## A caveat on the synthesised specimen sheets
 *
 * `@WearThemeCatalog` also makes the renderer synthesise a specimen sheet per theme, read
 * reflectively off `androidx.wear.compose.material3.MaterialTheme` — a *phone/watch* Compose theme
 * these providers never install, because a Remote Compose theme isn't one. Those sheets are
 * therefore not evidence of anything here; the stickers re-rendered under a selected theme are.
 */

/** The declared theme names, in display order. */
val REMOTE_THEME_NAMES: List<String> =
  listOf("M3", "Coral", "Teal", "Google Sans Flex", "KotlinConf")

/**
 * The selected theme's name, or null when no provider is installed — the plain
 * `composePreviewRenderAll` render, which stays on the stock scheme and the stock default family.
 */
internal val LocalRemoteCatalogTheme = compositionLocalOf<String?> { null }

/**
 * The Google Fonts family a theme draws its theme-styled text in. Only the typeface theme moves it;
 * every palette keeps the catalog's own default face (`role: "default"` in the fonts manifest), so a
 * palette comparison isn't also a type comparison.
 */
fun remoteCatalogFont(name: String): String =
  if (name == "Google Sans Flex") "Google Sans Flex" else "Roboto Flex"

/**
 * [base] with the theme's roles replaced — the Remote counterpart of the Wear sibling's
 * `wearColorScheme`, role-for-role, so a component rendered under "Coral" here and there differs
 * only by what the two libraries draw, never by what the theme asked for.
 *
 * Reads its base from the *installed* scheme rather than a constant, so a role this catalog doesn't
 * touch keeps whatever the library's dark-first default supplies.
 */
fun remoteCatalogColorScheme(name: String, base: RemoteColorScheme): RemoteColorScheme =
  when (name) {
    // Confetti Wear's KotlinConf identity built from the JetBrains seed purple (#7F52FF). The full
    // primary + secondary ramp rather than the two-role edits below, matching the sibling.
    "KotlinConf" ->
      base.copy(
        primary = RemoteColor(Color(0xFF7F52FF)),
        primaryDim = RemoteColor(Color(0xFF633BDB)),
        primaryContainer = RemoteColor(Color(0xFF3D247F)),
        onPrimary = RemoteColor(Color.White),
        onPrimaryContainer = RemoteColor(Color(0xFFE8DDFF)),
        secondary = RemoteColor(Color(0xFFFF8DA1)),
        secondaryDim = RemoteColor(Color(0xFFD96C81)),
        secondaryContainer = RemoteColor(Color(0xFF652936)),
        onSecondary = RemoteColor(Color(0xFF3A0715)),
        onSecondaryContainer = RemoteColor(Color(0xFFFFD9E0)),
      )
    "Coral" ->
      base.copy(
        primary = RemoteColor(Color(0xFFFF6F61)),
        secondary = RemoteColor(Color(0xFFFFB4A9)),
      )
    "Teal" ->
      base.copy(
        primary = RemoteColor(Color(0xFF4DD0E1)),
        secondary = RemoteColor(Color(0xFF80CBC4)),
      )
    else -> base
  }

/**
 * The Remote type scale for a selected typeface [family]: every role re-pointed via
 * `defaultFontFamily`, which is what turns the built-in default id into a `google:`-namespaced
 * lookup — for theme-styled text only.
 *
 * `google:` namespaces the name the same way the catalog's named-family stickers do, so every lane
 * knows where the face comes from instead of guessing: the browser fetches it from the CSS API, the
 * wasm lane reads the vendored copy, and the server-side lanes resolve it through the shared font
 * cache.
 */
fun remoteCatalogTypography(family: String): RemoteTypography =
  RemoteTypography(defaultFontFamily = RemoteFontFamily.Named("google:$family"))

/** Publishes [name] to [RemoteSticker], which installs it once inside the remote document. */
@Composable
private fun RemoteThemeOverride(name: String, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalRemoteCatalogTheme provides name, content = content)
}

/**
 * The stock Wear Remote Material 3 scheme and the catalog's default face, unchanged — the baseline
 * the other four are read against, and by construction pixel-identical to an un-themed render.
 */
@WearThemeCatalog(name = "M3", group = "Wear")
class RemoteM3ThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = RemoteThemeOverride("M3", content)
}

/** Warm coral primary over the stock dark scheme. */
@WearThemeCatalog(name = "Coral", group = "Wear")
class RemoteCoralThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) = RemoteThemeOverride("Coral", content)
}

/** Cool teal primary over the stock dark scheme. */
@WearThemeCatalog(name = "Teal", group = "Wear")
class RemoteTealThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) = RemoteThemeOverride("Teal", content)
}

/**
 * Google Sans Flex — the Material 3 Expressive brand face, served by the Google Fonts CSS API.
 * Palette-identical to [RemoteM3ThemeCatalog] on purpose: it isolates the typeface.
 */
@WearThemeCatalog(name = "Google Sans Flex", group = "Wear")
class RemoteGoogleSansFlexThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    RemoteThemeOverride("Google Sans Flex", content)
}

/**
 * Confetti Wear's dark KotlinConf identity — its JetBrains purple seed palette. The sibling's
 * typeface pairing is not ported; see the vendoring note in this file's header.
 */
@WearThemeCatalog(name = "KotlinConf", group = "Confetti Wear")
class RemoteKotlinConfThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) = RemoteThemeOverride("KotlinConf", content)
}
