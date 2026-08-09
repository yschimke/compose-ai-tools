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
 * Three are **palettes**, ported role-for-role from the sibling's `wearColorScheme`: the single-role
 * [RemoteCoralThemeCatalog] / [RemoteTealThemeCatalog] edits, and the JetBrains seed purple ramp of
 * [RemoteKotlinConfThemeCatalog] — which also carries a type pairing, so it moves both axes exactly
 * as the sibling's does. [RemoteGoogleSansFlexThemeCatalog] is the pure **typeface** theme: it keeps
 * the M3 palette exactly, so a side-by-side against [RemoteM3ThemeCatalog] reads as a type
 * comparison and nothing else — the same contract the sibling documents.
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
 * ## Typeface scope, and the vendoring it requires
 *
 * [RemoteCoralThemeCatalog] and [RemoteTealThemeCatalog] keep the catalog's default face, so a
 * palette comparison isn't also a type comparison. Two themes move type:
 * [RemoteGoogleSansFlexThemeCatalog], and [RemoteKotlinConfThemeCatalog], which ports the sibling's
 * **pairing** — Inter on body, JetBrains Mono on the display / title / numeral roles.
 *
 * A family named here has to be vendored into `:samples:cmp-wasm-catalog`'s `fonts.json`: that lane
 * is manifest-only and never fetches, so an unlisted family fails
 * `RcComposeSupport.fontFamilyIssue`'s availability check rather than degrading to a substitute
 * face. JetBrains Mono was already there; Inter was added alongside this theme (SIL OFL-1.1, from
 * the google/fonts corpus — see that directory's `README.md`).
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
 * The Google Fonts family a theme draws its **body** text in. Coral and Teal keep the catalog's own
 * default face (`role: "default"` in the fonts manifest), so a palette comparison isn't also a type
 * comparison; the two type-moving themes name their own.
 */
fun remoteCatalogFont(name: String): String =
  when (name) {
    "Google Sans Flex" -> "Google Sans Flex"
    // KotlinConf's body face; its display / title / numeral roles pair against JetBrains Mono.
    "KotlinConf" -> "Inter"
    else -> "Roboto Flex"
  }

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

/**
 * The type scale a theme installs — a **pairing** where it declares one, else the single-family
 * scale from [remoteCatalogTypography].
 *
 * KotlinConf pairs its body face with JetBrains Mono on the display / title / numeral roles, the
 * same split the Wear sibling's `wearTypography(body =, display =)` makes. Every paired role is
 * re-pointed **explicitly** via `copy`, rather than leaning on `defaultFontFamily` — that parameter
 * fills the whole scale with one family, which is exactly what a pairing must not do.
 */
fun remoteCatalogTypeScale(name: String): RemoteTypography {
  val body = remoteCatalogTypography(remoteCatalogFont(name))
  val display = remoteCatalogDisplayFont(name) ?: return body
  val face = RemoteFontFamily.Named("google:$display")
  return body.copy(
    displayLarge = body.displayLarge.copy(fontFamily = face),
    displayMedium = body.displayMedium.copy(fontFamily = face),
    displaySmall = body.displaySmall.copy(fontFamily = face),
    titleLarge = body.titleLarge.copy(fontFamily = face),
    titleMedium = body.titleMedium.copy(fontFamily = face),
    titleSmall = body.titleSmall.copy(fontFamily = face),
    numeralExtraLarge = body.numeralExtraLarge.copy(fontFamily = face),
    numeralLarge = body.numeralLarge.copy(fontFamily = face),
    numeralMedium = body.numeralMedium.copy(fontFamily = face),
    numeralSmall = body.numeralSmall.copy(fontFamily = face),
    numeralExtraSmall = body.numeralExtraSmall.copy(fontFamily = face),
  )
}

/** The display/title/numeral face a theme pairs against its body face, or null when it uses one. */
private fun remoteCatalogDisplayFont(name: String): String? =
  if (name == "KotlinConf") "JetBrains Mono" else null

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
 * Confetti Wear's dark KotlinConf identity: its JetBrains purple seed palette **and** its typeface
 * pairing — JetBrains Mono on the display / title / numeral roles, Inter on the body.
 */
@WearThemeCatalog(name = "KotlinConf", group = "Confetti Wear")
class RemoteKotlinConfThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) = RemoteThemeOverride("KotlinConf", content)
}
