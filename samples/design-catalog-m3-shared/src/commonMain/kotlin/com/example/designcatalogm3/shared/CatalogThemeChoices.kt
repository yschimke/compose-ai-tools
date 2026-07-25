package com.example.designcatalogm3.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The catalog's selectable **theme-override choices**, shared so every render tier resolves them
 * identically — the desktop `@Preview` sticker sheet ([com.example.designcatalogm3]
 * `CatalogSticker`) and the in-browser Wasm viewer ([com.example.cmpwasmcatalog] `CatalogApp`).
 * Both read the same `theme.font` / `theme.colors` knob and map the selected **name** here, so the
 * two tiers can never drift (the snapshot the desktop bakes and the live Wasm render agree).
 *
 * The names are exactly the declared `@TypographyCatalog` / `@ColorCatalog` labels the catalog
 * advertises, so the override registry stays in lockstep with what a viewer sees to pick.
 */

/** Knob keys the theme wrappers read. */
const val CATALOG_FONT_KNOB = "theme.font"
const val CATALOG_COLORS_KNOB = "theme.colors"

/** Typeface choice names (declared `@TypographyCatalog` labels). Roboto Flex is the default. */
const val CATALOG_FONT_ROBOTO_FLEX = "Roboto Flex"
const val CATALOG_FONT_GOOGLE_SANS_FLEX = "Google Sans Flex"
const val CATALOG_FONT_LOBSTER_TWO = "Lobster Two"

/**
 * Palette choice names (declared `@ColorCatalog` labels). `M3` is the default light/dark scheme.
 */
const val CATALOG_PALETTE_M3 = "M3"
const val CATALOG_PALETTE_CORAL = "Coral"
const val CATALOG_PALETTE_TEAL = "Teal"

/**
 * Prefix marking a `theme.colors` value as a **serialized app palette** rather than a named choice
 * — `scheme:l=<role>:<AARRGGBB>,…;d=<role>:<AARRGGBB>,…`. Lets any consumer (e.g. an app rendering
 * the M3 catalog under its own brand theme) feed a full M3 `ColorScheme` through the existing
 * string knob, so every sticker re-skins with **no per-preview change and no brand hardcoded here**
 * — the resolver just decodes whatever roles it's handed and leaves the rest at the stock M3 tone.
 * See [serializeCatalogColorScheme] / [parseCatalogColorScheme].
 */
const val CATALOG_COLORS_SCHEME_PREFIX = "scheme:"

/**
 * Resolves a selected palette [name] to a [ColorScheme]. A value starting with
 * [CATALOG_COLORS_SCHEME_PREFIX] is a serialized app palette (decoded by
 * [parseCatalogColorScheme]); otherwise [CATALOG_PALETTE_M3] (and any unknown name) is the stock M3
 * light/dark scheme, honouring [dark], and the brand palettes are fixed-tone schemes. Shared by the
 * desktop and Wasm theme wrappers so a `theme.colors` override renders identically in both. An
 * unparseable serialized value falls through to the stock M3 scheme (never an error).
 */
fun catalogColorScheme(name: String, dark: Boolean): ColorScheme {
  if (name.startsWith(CATALOG_COLORS_SCHEME_PREFIX)) {
    parseCatalogColorScheme(name, dark)?.let {
      return it
    }
  }
  return when (name) {
    CATALOG_PALETTE_CORAL ->
      lightColorScheme(
        primary = Color(0xFFFF6F61),
        secondary = Color(0xFFFFB4A9),
        tertiary = Color(0xFFB8860B),
      )
    CATALOG_PALETTE_TEAL ->
      darkColorScheme(
        primary = Color(0xFF4DD0E1),
        secondary = Color(0xFF80CBC4),
        tertiary = Color(0xFFFFE082),
      )
    else -> if (dark) darkColorScheme() else lightColorScheme()
  }
}

/** A display mode a catalog theme can be rendered in. */
enum class CatalogThemeMode {
  LIGHT,
  DARK,
}

/** Light + dark — the both-modes result, shared so the common case allocates once. */
private val CATALOG_THEME_MODES_BOTH = setOf(CatalogThemeMode.LIGHT, CatalogThemeMode.DARK)

/**
 * The display mode(s) the `theme.colors` value [name] is actually designed for. A theme baked or
 * shown in a mode it doesn't define renders an auto-derived variant its author never intended (a
 * light-only brand palette force-darkened to muddy greys), so a consumer that enumerates variants —
 * an exporter baking per-mode PNGs, the landing's Light/Dark selector — should offer only these
 * modes rather than a fixed both, or half the output is unusable.
 * - A **serialized app palette** ([CATALOG_COLORS_SCHEME_PREFIX] blob) is inferred from which mode
 *   segments actually carry usable roles: only `l=…` ⇒ light-only, only `d=…` ⇒ dark-only, both ⇒
 *   both. Inference reuses [parseCatalogColorScheme], so it can never disagree with what
 *   [catalogColorScheme] would render; a blob with no usable mode (malformed/empty) falls back to
 *   both, mirroring [catalogColorScheme]'s stock-M3 fallback for an unparseable value.
 * - A **named palette** is declared: [CATALOG_PALETTE_CORAL] is light-only and
 *   [CATALOG_PALETTE_TEAL] is dark-only (each a fixed-tone scheme [catalogColorScheme] returns
 *   regardless of the requested mode), while [CATALOG_PALETTE_M3] and any unknown name are the
 *   stock M3 scheme — both modes.
 *
 * Always non-empty. Documented per theme in `docs/design/m3-catalog-app-palette.md`.
 */
fun catalogThemeModes(name: String): Set<CatalogThemeMode> {
  if (name.startsWith(CATALOG_COLORS_SCHEME_PREFIX)) {
    val modes = mutableSetOf<CatalogThemeMode>()
    if (parseCatalogColorScheme(name, dark = false) != null) modes += CatalogThemeMode.LIGHT
    if (parseCatalogColorScheme(name, dark = true) != null) modes += CatalogThemeMode.DARK
    return if (modes.isEmpty()) CATALOG_THEME_MODES_BOTH else modes
  }
  return when (name) {
    CATALOG_PALETTE_CORAL -> setOf(CatalogThemeMode.LIGHT)
    CATALOG_PALETTE_TEAL -> setOf(CatalogThemeMode.DARK)
    else -> CATALOG_THEME_MODES_BOTH
  }
}

/**
 * The M3 [ColorScheme] roles carried in a serialized app palette, paired name→value. One list
 * drives both [serializeCatalogColorScheme] (emit) and the round-trip test; [applyColorRoles]
 * consumes the decoded map. Roles omitted from a blob keep their stock M3 tone, so a partial
 * palette still renders (only the supplied roles change).
 */
private fun schemeRoles(s: ColorScheme): List<Pair<String, Color>> =
  listOf(
    "primary" to s.primary,
    "onPrimary" to s.onPrimary,
    "primaryContainer" to s.primaryContainer,
    "onPrimaryContainer" to s.onPrimaryContainer,
    "inversePrimary" to s.inversePrimary,
    "secondary" to s.secondary,
    "onSecondary" to s.onSecondary,
    "secondaryContainer" to s.secondaryContainer,
    "onSecondaryContainer" to s.onSecondaryContainer,
    "tertiary" to s.tertiary,
    "onTertiary" to s.onTertiary,
    "tertiaryContainer" to s.tertiaryContainer,
    "onTertiaryContainer" to s.onTertiaryContainer,
    "background" to s.background,
    "onBackground" to s.onBackground,
    "surface" to s.surface,
    "onSurface" to s.onSurface,
    "surfaceVariant" to s.surfaceVariant,
    "onSurfaceVariant" to s.onSurfaceVariant,
    "surfaceTint" to s.surfaceTint,
    "inverseSurface" to s.inverseSurface,
    "inverseOnSurface" to s.inverseOnSurface,
    "error" to s.error,
    "onError" to s.onError,
    "errorContainer" to s.errorContainer,
    "onErrorContainer" to s.onErrorContainer,
    "outline" to s.outline,
    "outlineVariant" to s.outlineVariant,
    "scrim" to s.scrim,
    "surfaceBright" to s.surfaceBright,
    "surfaceDim" to s.surfaceDim,
    "surfaceContainer" to s.surfaceContainer,
    "surfaceContainerHigh" to s.surfaceContainerHigh,
    "surfaceContainerHighest" to s.surfaceContainerHighest,
    "surfaceContainerLow" to s.surfaceContainerLow,
    "surfaceContainerLowest" to s.surfaceContainerLowest,
    // The M3 "fixed" accent roles — exposed by the current `ColorScheme` API and read by the
    // `compose/theme` token export, so an app that customises them must round-trip too.
    "primaryFixed" to s.primaryFixed,
    "primaryFixedDim" to s.primaryFixedDim,
    "onPrimaryFixed" to s.onPrimaryFixed,
    "onPrimaryFixedVariant" to s.onPrimaryFixedVariant,
    "secondaryFixed" to s.secondaryFixed,
    "secondaryFixedDim" to s.secondaryFixedDim,
    "onSecondaryFixed" to s.onSecondaryFixed,
    "onSecondaryFixedVariant" to s.onSecondaryFixedVariant,
    "tertiaryFixed" to s.tertiaryFixed,
    "tertiaryFixedDim" to s.tertiaryFixedDim,
    "onTertiaryFixed" to s.onTertiaryFixed,
    "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
  )

/**
 * The recognized M3 role names a serialized palette may carry — the keys [applyColorRoles] reads
 * and [schemeRoles] emits. A blob key outside this set is a typo or a future role: it's skipped, so
 * it never counts as a "usable role" for [parseCatalogColorScheme] (nor a supported mode for
 * [catalogThemeModes]). Role names don't depend on the scheme's tones, so any instance seeds it.
 */
private val CATALOG_SCHEME_ROLE_NAMES: Set<String> =
  schemeRoles(lightColorScheme()).mapTo(HashSet()) { it.first }

/**
 * Serialize a [light] + [dark] [ColorScheme] pair into the `theme.colors` wire form
 * [catalogColorScheme] decodes: `scheme:l=<role>:<AARRGGBB>,…;d=<role>:<AARRGGBB>,…`. A consumer
 * (e.g. an app publishing the M3 catalog under its own theme) calls this on its brand schemes and
 * passes the result as the `theme.colors` knob — no dependency on this module's palette names.
 */
fun serializeCatalogColorScheme(light: ColorScheme, dark: ColorScheme): String {
  fun mode(tag: String, s: ColorScheme) =
    "$tag=" + schemeRoles(s).joinToString(",") { (role, c) -> "$role:${colorToHex(c)}" }
  return CATALOG_COLORS_SCHEME_PREFIX + mode("l", light) + ";" + mode("d", dark)
}

/**
 * Decode a serialized app palette ([serializeCatalogColorScheme]) for the requested [dark] mode
 * into a [ColorScheme], starting from the stock M3 scheme and overriding only the roles the blob
 * carries. Returns null when [value] isn't a `scheme:` blob or carries no usable role for this mode
 * — the caller then falls back to the stock scheme. Tolerant: unknown role names and malformed hex
 * are skipped rather than failing the whole render.
 */
fun parseCatalogColorScheme(value: String, dark: Boolean): ColorScheme? {
  if (!value.startsWith(CATALOG_COLORS_SCHEME_PREFIX)) return null
  val modeTag = if (dark) "d" else "l"
  val segment =
    value
      .removePrefix(CATALOG_COLORS_SCHEME_PREFIX)
      .split(";")
      .map { it.trim() }
      .firstOrNull { it.startsWith("$modeTag=") } ?: return null
  val roles = HashMap<String, Color>()
  for (pair in segment.removePrefix("$modeTag=").split(",")) {
    val entry = pair.trim()
    if (entry.isEmpty()) continue
    val sep = entry.indexOf(':')
    if (sep <= 0) continue
    val color = parseHexColor(entry.substring(sep + 1)) ?: continue
    val role = entry.substring(0, sep).trim()
    // Only a RECOGNIZED role counts — an unknown/typo'd name (e.g. `primry`) is skipped, so a
    // segment of only unknown roles leaves the map empty → null (not a falsely "usable" mode).
    if (role in CATALOG_SCHEME_ROLE_NAMES) roles[role] = color
  }
  if (roles.isEmpty()) return null
  return applyColorRoles(if (dark) darkColorScheme() else lightColorScheme(), roles)
}

/**
 * Overlay the decoded [roles] onto [base], leaving any role the blob didn't carry at its base tone.
 */
private fun applyColorRoles(base: ColorScheme, roles: Map<String, Color>): ColorScheme =
  base.copy(
    primary = roles["primary"] ?: base.primary,
    onPrimary = roles["onPrimary"] ?: base.onPrimary,
    primaryContainer = roles["primaryContainer"] ?: base.primaryContainer,
    onPrimaryContainer = roles["onPrimaryContainer"] ?: base.onPrimaryContainer,
    inversePrimary = roles["inversePrimary"] ?: base.inversePrimary,
    secondary = roles["secondary"] ?: base.secondary,
    onSecondary = roles["onSecondary"] ?: base.onSecondary,
    secondaryContainer = roles["secondaryContainer"] ?: base.secondaryContainer,
    onSecondaryContainer = roles["onSecondaryContainer"] ?: base.onSecondaryContainer,
    tertiary = roles["tertiary"] ?: base.tertiary,
    onTertiary = roles["onTertiary"] ?: base.onTertiary,
    tertiaryContainer = roles["tertiaryContainer"] ?: base.tertiaryContainer,
    onTertiaryContainer = roles["onTertiaryContainer"] ?: base.onTertiaryContainer,
    background = roles["background"] ?: base.background,
    onBackground = roles["onBackground"] ?: base.onBackground,
    surface = roles["surface"] ?: base.surface,
    onSurface = roles["onSurface"] ?: base.onSurface,
    surfaceVariant = roles["surfaceVariant"] ?: base.surfaceVariant,
    onSurfaceVariant = roles["onSurfaceVariant"] ?: base.onSurfaceVariant,
    surfaceTint = roles["surfaceTint"] ?: base.surfaceTint,
    inverseSurface = roles["inverseSurface"] ?: base.inverseSurface,
    inverseOnSurface = roles["inverseOnSurface"] ?: base.inverseOnSurface,
    error = roles["error"] ?: base.error,
    onError = roles["onError"] ?: base.onError,
    errorContainer = roles["errorContainer"] ?: base.errorContainer,
    onErrorContainer = roles["onErrorContainer"] ?: base.onErrorContainer,
    outline = roles["outline"] ?: base.outline,
    outlineVariant = roles["outlineVariant"] ?: base.outlineVariant,
    scrim = roles["scrim"] ?: base.scrim,
    surfaceBright = roles["surfaceBright"] ?: base.surfaceBright,
    surfaceDim = roles["surfaceDim"] ?: base.surfaceDim,
    surfaceContainer = roles["surfaceContainer"] ?: base.surfaceContainer,
    surfaceContainerHigh = roles["surfaceContainerHigh"] ?: base.surfaceContainerHigh,
    surfaceContainerHighest = roles["surfaceContainerHighest"] ?: base.surfaceContainerHighest,
    surfaceContainerLow = roles["surfaceContainerLow"] ?: base.surfaceContainerLow,
    surfaceContainerLowest = roles["surfaceContainerLowest"] ?: base.surfaceContainerLowest,
    primaryFixed = roles["primaryFixed"] ?: base.primaryFixed,
    primaryFixedDim = roles["primaryFixedDim"] ?: base.primaryFixedDim,
    onPrimaryFixed = roles["onPrimaryFixed"] ?: base.onPrimaryFixed,
    onPrimaryFixedVariant = roles["onPrimaryFixedVariant"] ?: base.onPrimaryFixedVariant,
    secondaryFixed = roles["secondaryFixed"] ?: base.secondaryFixed,
    secondaryFixedDim = roles["secondaryFixedDim"] ?: base.secondaryFixedDim,
    onSecondaryFixed = roles["onSecondaryFixed"] ?: base.onSecondaryFixed,
    onSecondaryFixedVariant = roles["onSecondaryFixedVariant"] ?: base.onSecondaryFixedVariant,
    tertiaryFixed = roles["tertiaryFixed"] ?: base.tertiaryFixed,
    tertiaryFixedDim = roles["tertiaryFixedDim"] ?: base.tertiaryFixedDim,
    onTertiaryFixed = roles["onTertiaryFixed"] ?: base.onTertiaryFixed,
    onTertiaryFixedVariant = roles["onTertiaryFixedVariant"] ?: base.onTertiaryFixedVariant,
  )

/** `#AARRGGBB`/`AARRGGBB`/`RRGGBB` (opaque) → [Color], or null when unparseable. */
private fun parseHexColor(hex: String): Color? {
  val h = hex.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
  val argb =
    when (h.length) {
      6 -> "FF$h"
      8 -> h
      else -> return null
    }
  val value = argb.toLongOrNull(16) ?: return null
  return Color(value)
}

/** [Color] → 8-digit uppercase `AARRGGBB`, the form [parseHexColor] reads back. */
private fun colorToHex(c: Color): String {
  fun channel(f: Float) = ((f * 255f) + 0.5f).toInt().coerceIn(0, 255)
  val argb =
    (channel(c.alpha).toLong() shl 24) or
      (channel(c.red).toLong() shl 16) or
      (channel(c.green).toLong() shl 8) or
      channel(c.blue).toLong()
  return argb.toString(16).uppercase().padStart(8, '0')
}

// --- Shapes -------------------------------------------------------------------------------------

/** Knob key the theme wrappers read for the M3 [Shapes] override. */
const val CATALOG_SHAPES_KNOB = "theme.shapes"

/**
 * Prefix marking a `theme.shapes` value as a **serialized app shape set** rather than a named
 * choice — `shapes:xs=<dp>,s=<dp>,m=<dp>,l=<dp>,xl=<dp>`. The five M3 size tokens as corner radii
 * in dp. See [serializeCatalogShapes] / [catalogShapes].
 */
const val CATALOG_SHAPES_PREFIX = "shapes:"

/**
 * Resolve a `theme.shapes` value to a [Shapes]. A `shapes:`-prefixed value overrides only the
 * corner sizes it carries (omitted tokens keep the stock M3 corner); any other / absent /
 * unparseable value yields the stock M3 [Shapes] — never throws. Only uniform [RoundedCornerShape]
 * dp corners are expressed here; an app's per-component shape overrides still apply where a
 * component sets its own. Shared by the desktop and Wasm theme wrappers so a `theme.shapes`
 * override renders identically.
 */
fun catalogShapes(value: String): Shapes {
  if (!value.startsWith(CATALOG_SHAPES_PREFIX)) return Shapes()
  val sizes = HashMap<String, Dp>()
  for (pair in value.removePrefix(CATALOG_SHAPES_PREFIX).split(",")) {
    val entry = pair.trim()
    if (entry.isEmpty()) continue
    val sep = entry.indexOf('=')
    if (sep <= 0) continue
    val dp = entry.substring(sep + 1).trim().toFloatOrNull() ?: continue
    sizes[entry.substring(0, sep).trim()] = dp.dp
  }
  if (sizes.isEmpty()) return Shapes()
  val base = Shapes()
  return base.copy(
    extraSmall = sizes["xs"]?.let { RoundedCornerShape(it) } ?: base.extraSmall,
    small = sizes["s"]?.let { RoundedCornerShape(it) } ?: base.small,
    medium = sizes["m"]?.let { RoundedCornerShape(it) } ?: base.medium,
    large = sizes["l"]?.let { RoundedCornerShape(it) } ?: base.large,
    extraLarge = sizes["xl"]?.let { RoundedCornerShape(it) } ?: base.extraLarge,
  )
}

/**
 * Serialize an app's five M3 corner sizes into the `theme.shapes` wire form [catalogShapes]
 * decodes. Takes the dp values directly — a built
 * [androidx.compose.foundation.shape.CornerBasedShape] doesn't expose its size portably, so a
 * consumer passes the sizes it built its `RoundedCornerShape`s from.
 */
fun serializeCatalogShapes(
  extraSmall: Dp,
  small: Dp,
  medium: Dp,
  large: Dp,
  extraLarge: Dp,
): String =
  CATALOG_SHAPES_PREFIX +
    "xs=${extraSmall.value},s=${small.value},m=${medium.value}," +
    "l=${large.value},xl=${extraLarge.value}"

// --- Typography (metrics) -----------------------------------------------------------------------

/** Knob key the theme wrappers read for the M3 [Typography] **metrics** override. */
const val CATALOG_TYPOGRAPHY_KNOB = "theme.typography"

/**
 * Prefix marking a `theme.typography` value as **serialized app type metrics** —
 * `typo:<role>=<sizeSp>/<lineHeightSp>/<letterSpacingSp>/<weight>,…`, one entry per M3 type role.
 * Carries only the numeric scale (size / line-height / letter-spacing / weight); the **typeface**
 * still comes from the `theme.font` knob, because a font *file* can't ride a string knob. `-` in
 * any slot means "leave the base role's value". See [serializeCatalogTypography] /
 * [catalogApplyTypography].
 */
const val CATALOG_TYPOGRAPHY_PREFIX = "typo:"

/** The 15 M3 type roles, paired name→getter — drives emit and the round-trip test. */
private val TYPE_ROLES: List<Pair<String, (Typography) -> TextStyle>> =
  listOf(
    "displayLarge" to { it.displayLarge },
    "displayMedium" to { it.displayMedium },
    "displaySmall" to { it.displaySmall },
    "headlineLarge" to { it.headlineLarge },
    "headlineMedium" to { it.headlineMedium },
    "headlineSmall" to { it.headlineSmall },
    "titleLarge" to { it.titleLarge },
    "titleMedium" to { it.titleMedium },
    "titleSmall" to { it.titleSmall },
    "bodyLarge" to { it.bodyLarge },
    "bodyMedium" to { it.bodyMedium },
    "bodySmall" to { it.bodySmall },
    "labelLarge" to { it.labelLarge },
    "labelMedium" to { it.labelMedium },
    "labelSmall" to { it.labelSmall },
  )

/** A specified sp [TextUnit] as its float value; `-` otherwise (so the decoder keeps the base). */
private fun spOrDash(tu: TextUnit): String =
  if (tu.type == TextUnitType.Sp) tu.value.toString() else "-"

/**
 * Serialize a [Typography]'s per-role **metrics** into the `theme.typography` wire form
 * [catalogApplyTypography] overlays. A consumer calls this on its brand `Typography`; the faces are
 * carried separately via `theme.font`, so only the scale travels here.
 */
fun serializeCatalogTypography(typography: Typography): String =
  CATALOG_TYPOGRAPHY_PREFIX +
    TYPE_ROLES.joinToString(",") { (name, get) ->
      val s = get(typography)
      "$name=${spOrDash(s.fontSize)}/${spOrDash(s.lineHeight)}/${spOrDash(s.letterSpacing)}/" +
        (s.fontWeight?.weight?.toString() ?: "-")
    }

/**
 * Overlay serialized type metrics ([serializeCatalogTypography]) onto [base] — which already
 * carries the typeface from the `theme.font` knob — replacing only the slots a role supplies.
 * Returns [base] unchanged when [value] isn't a `typo:` blob or carries nothing usable. Tolerant:
 * an unparseable slot (`-` or garbage) keeps the base role's value.
 */
fun catalogApplyTypography(base: Typography, value: String): Typography {
  if (!value.startsWith(CATALOG_TYPOGRAPHY_PREFIX)) return base
  val specs = HashMap<String, String>()
  for (pair in value.removePrefix(CATALOG_TYPOGRAPHY_PREFIX).split(",")) {
    val entry = pair.trim()
    if (entry.isEmpty()) continue
    val sep = entry.indexOf('=')
    if (sep <= 0) continue
    specs[entry.substring(0, sep).trim()] = entry.substring(sep + 1).trim()
  }
  if (specs.isEmpty()) return base
  return base.copy(
    displayLarge = applyRoleMetrics(base.displayLarge, specs["displayLarge"]),
    displayMedium = applyRoleMetrics(base.displayMedium, specs["displayMedium"]),
    displaySmall = applyRoleMetrics(base.displaySmall, specs["displaySmall"]),
    headlineLarge = applyRoleMetrics(base.headlineLarge, specs["headlineLarge"]),
    headlineMedium = applyRoleMetrics(base.headlineMedium, specs["headlineMedium"]),
    headlineSmall = applyRoleMetrics(base.headlineSmall, specs["headlineSmall"]),
    titleLarge = applyRoleMetrics(base.titleLarge, specs["titleLarge"]),
    titleMedium = applyRoleMetrics(base.titleMedium, specs["titleMedium"]),
    titleSmall = applyRoleMetrics(base.titleSmall, specs["titleSmall"]),
    bodyLarge = applyRoleMetrics(base.bodyLarge, specs["bodyLarge"]),
    bodyMedium = applyRoleMetrics(base.bodyMedium, specs["bodyMedium"]),
    bodySmall = applyRoleMetrics(base.bodySmall, specs["bodySmall"]),
    labelLarge = applyRoleMetrics(base.labelLarge, specs["labelLarge"]),
    labelMedium = applyRoleMetrics(base.labelMedium, specs["labelMedium"]),
    labelSmall = applyRoleMetrics(base.labelSmall, specs["labelSmall"]),
  )
}

/**
 * Overlay one role's `<sizeSp>/<lineHeightSp>/<letterSpacingSp>/<weight>` [spec] onto [style],
 * leaving any slot the spec didn't carry (`-` / missing / unparseable) at the base value.
 */
private fun applyRoleMetrics(style: TextStyle, spec: String?): TextStyle {
  if (spec.isNullOrEmpty()) return style
  val parts = spec.split("/")
  return style.copy(
    fontSize = parts.getOrNull(0)?.toFloatOrNull()?.sp ?: style.fontSize,
    lineHeight = parts.getOrNull(1)?.toFloatOrNull()?.sp ?: style.lineHeight,
    letterSpacing = parts.getOrNull(2)?.toFloatOrNull()?.sp ?: style.letterSpacing,
    // `FontWeight(int)` requires 1..1000; this knob is query-driven, so an out-of-range weight must
    // fall back rather than throw and sink the whole render.
    fontWeight =
      parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 1..1000 }?.let { FontWeight(it) }
        ?: style.fontWeight,
  )
}

// --- Typography (font families) -----------------------------------------------------------------

/**
 * Knob key the theme wrappers read for the M3 [Typography] **font-family** override, keyed by role
 * group. Complements `theme.typography` (metrics only): a font *family* — unlike a size — is a name
 * the tier can resolve to a vendored face, so this carries the typeface the metrics knob cannot.
 */
const val CATALOG_FONTS_KNOB = "theme.fonts"

/**
 * Prefix marking a `theme.fonts` value as a **serialized per-role-group family map** —
 * `families:<group>=<GoogleFont family>,…` where `<group>` ∈ [CATALOG_FONT_ROLE_GROUPS]. Each named
 * family is resolved via [namedFontFamily] against the tier's vendored faces, so an app can brand
 * the catalog's whole type scale (e.g. `display=Orbitron,body=Space Grotesk`) through the existing
 * string knob with **no per-preview change and no face hardcoded here** — exactly like
 * [CATALOG_COLORS_SCHEME_PREFIX] does for colours. A group the blob omits keeps the base typeface
 * (the `theme.font` face); an unvendored family degrades to that same base. See
 * [parseCatalogFontFamilies] / `catalogApplyFontFamilies`.
 */
const val CATALOG_FONTS_PREFIX = "families:"

/**
 * The five M3 type-role groups a [CATALOG_FONTS_PREFIX] blob keys — each covers its three sizes.
 */
val CATALOG_FONT_ROLE_GROUPS: List<String> = listOf("display", "headline", "title", "body", "label")

/**
 * Serialize a role-group→family map into the `theme.fonts` wire form (only the five known groups
 * with a non-blank family are emitted, in canonical order). Empty ⇒ empty string (no override). A
 * consumer calls this on its brand type scale's per-group faces; the metrics ride
 * `theme.typography` separately.
 */
fun serializeCatalogFontFamilies(families: Map<String, String>): String {
  val kept = CATALOG_FONT_ROLE_GROUPS.mapNotNull { g ->
    families[g]?.trim()?.takeIf(String::isNotEmpty)?.let { g to it }
  }
  return if (kept.isEmpty()) ""
  else CATALOG_FONTS_PREFIX + kept.joinToString(",") { (g, f) -> "$g=$f" }
}

/**
 * Parse a `families:` blob ([serializeCatalogFontFamilies]) into role-group→family. A value without
 * the [CATALOG_FONTS_PREFIX], or one carrying no recognised group, yields an empty map (⇒ no
 * override). Unknown groups and blank families are skipped, never thrown on — the knob is
 * query-driven, so a bad entry must degrade rather than sink the render.
 */
fun parseCatalogFontFamilies(value: String): Map<String, String> {
  if (!value.startsWith(CATALOG_FONTS_PREFIX)) return emptyMap()
  val out = LinkedHashMap<String, String>()
  for (pair in value.removePrefix(CATALOG_FONTS_PREFIX).split(",")) {
    val entry = pair.trim()
    if (entry.isEmpty()) continue
    val sep = entry.indexOf('=')
    if (sep <= 0) continue
    val group = entry.substring(0, sep).trim()
    val family = entry.substring(sep + 1).trim()
    if (group in CATALOG_FONT_ROLE_GROUPS && family.isNotEmpty()) out[group] = family
  }
  return out
}
