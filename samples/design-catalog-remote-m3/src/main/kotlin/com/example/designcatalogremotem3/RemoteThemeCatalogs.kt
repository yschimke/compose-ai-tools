@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * The catalog's declared themes — the same five names, in the same groups, as
 * `:samples:design-catalog-wear-m3`'s `WearThemeCatalogs.kt`, so the two catalogs' Theme selects
 * read as one set and the design-artifacts cross-system compare pairs them off.
 *
 * ## A theme is applied at replay, not at capture
 *
 * This is the load-bearing difference from the Wear sibling, and the reason these are only
 * *declarations* here. Every sticker is recorded **once, under the default theme**; a selected theme
 * is applied afterwards, to the already-recorded document, by overriding **named values**.
 *
 * That works because `RemoteMaterialTheme`'s scheme is not constant-folded into the document. Each
 * role it draws through is emitted as named state — `USER:WearM3.primary`,
 * `USER:WearM3.surfaceContainer`, `USER:WearM3.onSurface`, and so on — and the player's
 * `setNamedColorOverride` reaches exactly those. Applying [remoteCatalogThemeColors] to a replayed
 * document therefore re-themes it with no recomposition:
 * ```
 * /render/button-filled__ideal__default__compact.png
 *     ?rc.WearM3.primary=color:%23FF6F61&rc.WearM3.onPrimary=color:%23210F48
 * ```
 * returns a coral button from the *published* catalog, whose bytecode was dropped at pack time.
 *
 * Recording under the default theme is what makes that possible. A document captured with a theme
 * baked in would carry that theme's values as its constants, so every theme would need its own
 * capture and a published catalog could only ever show the one it was packed with. One
 * theme-independent document plus a small map of overrides replaces N documents.
 *
 * So each provider below `Wrap`s nothing. It exists to **declare** the theme — the annotation is
 * what populates the preview server's Theme select — while the values it stands for live in
 * [remoteCatalogThemeColors] as plain data, ready to be seeded by whichever lane is drawing.
 *
 * ## The typeface half is a host choice, not a named value
 *
 * A named value can carry a colour, a float, an int or a bool — not a face. The typeface is
 * host-side in a different way: the stickers emit the built-in **default family id**, and which face
 * that id resolves to is decided by the player at draw time. So [remoteCatalogFont] /
 * [remoteCatalogDisplayFont] are published here as data for that lane to configure its resolver
 * with, rather than being installed into the document. Until a lane does, a type-moving theme
 * (Google Sans Flex, KotlinConf) is declared and its colours apply while its face does not.
 *
 * Nothing is vendored for them yet, deliberately. A face only has to reach
 * `:samples:cmp-wasm-catalog`'s `fonts.json` once a *document* names it — that lane is manifest-only
 * and never fetches, so an unlisted family a document asks for fails
 * `RcComposeSupport.fontFamilyIssue`'s availability check rather than degrading to a substitute. No
 * document here names Inter or Google Sans Flex any more (recording is default-themed), so vendoring
 * Inter now would add ~651 KB to the Wasm player's size ratchet to buy nothing. It lands with the
 * lane that resolves it. Google Sans Flex stays vendored — it was already there, and the ratchet was
 * already raised for it.
 *
 * ## What a theme deliberately does not reach
 *
 * The named-family stickers keep the exact faces they declare: [BrandedTextRemote],
 * [TypefaceSpecimenRemote], [VariableWeightRemote] and [VariableWidthRemote] are untouched under
 * every theme, because they exist to keep the named-family and font-variation-axis paths rendered
 * and diffed, and a theme that overrode them would delete the one place each capability is covered.
 *
 * ## A caveat on the synthesised specimen sheets
 *
 * `@WearThemeCatalog` also makes the renderer synthesise a specimen sheet per theme, read
 * reflectively off `androidx.wear.compose.material3.MaterialTheme` — a *phone/watch* Compose theme
 * these providers never install. Those five sheets are byte-identical to each other and are not
 * evidence of anything here; a sticker re-rendered under a seeded theme is.
 */

/** The declared theme names, in display order. */
val REMOTE_THEME_NAMES: List<String> =
  listOf("M3", "Coral", "Teal", "Google Sans Flex", "KotlinConf")

/**
 * The named-value overrides that apply [name] to an already-recorded document, keyed by the
 * document's own state names (`WearM3.<role>`, which the player qualifies to `USER:WearM3.<role>`).
 *
 * Ported role-for-role from the Wear sibling's `wearColorScheme`, so a component themed "Coral"
 * here and there differs only by what the two libraries draw, never by what the theme asked for.
 *
 * Empty for the themes that move no colour: **M3** is the stock scheme (selecting it is a no-op by
 * construction — it is the baseline the others are read against), and **Google Sans Flex** is
 * palette-identical to it on purpose, so a side-by-side of the two is a type comparison and nothing
 * else.
 *
 * A key absent from a given document is simply not overridden — each sticker emits only the roles it
 * actually draws through — so one map applies unchanged across the whole catalog.
 */
fun remoteCatalogThemeColors(name: String): Map<String, Color> =
  when (name) {
    // Confetti Wear's KotlinConf identity, built from the JetBrains seed purple (#7F52FF): the full
    // primary + secondary ramp rather than the two-role edits below, matching the sibling.
    "KotlinConf" ->
      mapOf(
        "WearM3.primary" to Color(0xFF7F52FF),
        "WearM3.primaryDim" to Color(0xFF633BDB),
        "WearM3.primaryContainer" to Color(0xFF3D247F),
        "WearM3.onPrimary" to Color.White,
        "WearM3.onPrimaryContainer" to Color(0xFFE8DDFF),
        "WearM3.secondary" to Color(0xFFFF8DA1),
        "WearM3.secondaryDim" to Color(0xFFD96C81),
        "WearM3.secondaryContainer" to Color(0xFF652936),
        "WearM3.onSecondary" to Color(0xFF3A0715),
        "WearM3.onSecondaryContainer" to Color(0xFFFFD9E0),
      )
    "Coral" ->
      mapOf(
        "WearM3.primary" to Color(0xFFFF6F61),
        "WearM3.secondary" to Color(0xFFFFB4A9),
      )
    "Teal" ->
      mapOf(
        "WearM3.primary" to Color(0xFF4DD0E1),
        "WearM3.secondary" to Color(0xFF80CBC4),
      )
    else -> emptyMap()
  }

/**
 * The Google Fonts family [name] draws its **body** text in — data for a player lane to point its
 * default-family resolution at, not something installed into the document.
 *
 * Coral and Teal keep the catalog's own default face (`role: "default"` in the fonts manifest), so a
 * palette comparison isn't also a type comparison; the two type-moving themes name their own.
 */
fun remoteCatalogFont(name: String): String =
  when (name) {
    "Google Sans Flex" -> "Google Sans Flex"
    // KotlinConf's body face; its display / title / numeral roles pair against JetBrains Mono.
    "KotlinConf" -> "Inter"
    else -> "Roboto Flex"
  }

/**
 * The face [name] pairs against its body face on the display / title / numeral roles, or null when
 * it uses one family throughout. Only KotlinConf pairs, matching the sibling.
 */
fun remoteCatalogDisplayFont(name: String): String? =
  if (name == "KotlinConf") "JetBrains Mono" else null

// Each provider declares its own `Wrap`, identical and empty, rather than inheriting one from a
// shared base: the renderer resolves the method reflectively **on the concrete class**, so an
// inherited implementation is a `NoSuchMethodException` and every specimen sheet fails to render.
//
// Empty because a theme is not applied while recording — see this file's header. The class exists so
// `@WearThemeCatalog` has something to annotate, which is what puts the theme in the server's Theme
// select; the values it stands for are in [remoteCatalogThemeColors].

/** The stock scheme and default face — the baseline the other four are read against. */
@WearThemeCatalog(name = "M3", group = "Wear")
class RemoteM3ThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = content()
}

/** Warm coral primary over the stock dark scheme. */
@WearThemeCatalog(name = "Coral", group = "Wear")
class RemoteCoralThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = content()
}

/** Cool teal primary over the stock dark scheme. */
@WearThemeCatalog(name = "Teal", group = "Wear")
class RemoteTealThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = content()
}

/**
 * Google Sans Flex — the Material 3 Expressive brand face. Palette-identical to
 * [RemoteM3ThemeCatalog] on purpose: it isolates the typeface.
 */
@WearThemeCatalog(name = "Google Sans Flex", group = "Wear")
class RemoteGoogleSansFlexThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = content()
}

/**
 * Confetti Wear's dark KotlinConf identity: its JetBrains purple seed palette, plus the typeface
 * pairing — JetBrains Mono on the display / title / numeral roles, Inter on the body.
 */
@WearThemeCatalog(name = "KotlinConf", group = "Confetti Wear")
class RemoteKotlinConfThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = content()
}
