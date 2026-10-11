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

// The catalog's selectable theme-override choices, shared so the desktop sticker sheet and the
// in-browser Wasm viewer map the same `theme.*` knob values identically. Names match the declared
// `@TypographyCatalog` / `@ColorCatalog` labels.

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
 * Prefix marking a `theme.colors` value as a serialized app palette rather than a named choice:
 * `scheme:l=<role>:<AARRGGBB>,…;d=<role>:<AARRGGBB>,…`. Lets a consumer re-skin every sticker with
 * its own full M3 `ColorScheme`; roles not supplied keep the stock M3 tone. See
 * [serializeCatalogColorScheme] / [parseCatalogColorScheme].
 */
const val CATALOG_COLORS_SCHEME_PREFIX = "scheme:"

/**
 * Resolves a selected palette [name] to a [ColorScheme]: a [CATALOG_COLORS_SCHEME_PREFIX] blob is
 * decoded; the brand palettes are fixed-tone schemes; [CATALOG_PALETTE_M3], unknown names and
 * unparseable blobs give the stock M3 scheme for [dark].
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
 * The display mode(s) the `theme.colors` value [name] is designed for, so exporters and mode
 * selectors don't offer an auto-derived variant the author never intended.
 * - A serialized palette supports the modes whose segments carry usable roles (inferred via
 *   [parseCatalogColorScheme], so it matches what renders); none usable falls back to both.
 * - [CATALOG_PALETTE_CORAL] is light-only, [CATALOG_PALETTE_TEAL] dark-only; [CATALOG_PALETTE_M3]
 *   and unknown names are both.
 *
 * Always non-empty. See `docs/design/m3-catalog-app-palette.md`.
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
 * The M3 [ColorScheme] roles a serialized palette carries, name → value. Drives
 * [serializeCatalogColorScheme] and the round-trip test.
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
    // The M3 "fixed" accent roles, read by the `compose/theme` token export, so they must
    // round-trip.
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
 * The recognized role names a serialized palette may carry. Unknown keys (typos, future roles) are
 * skipped and never count as a usable role.
 */
private val CATALOG_SCHEME_ROLE_NAMES: Set<String> =
  schemeRoles(lightColorScheme()).mapTo(HashSet()) { it.first }

/**
 * Serialize a [light] + [dark] [ColorScheme] pair into the `theme.colors` form [catalogColorScheme]
 * decodes. A consumer passes the result as the `theme.colors` knob to theme the catalog.
 */
fun serializeCatalogColorScheme(light: ColorScheme, dark: ColorScheme): String {
  fun mode(tag: String, s: ColorScheme) =
    "$tag=" + schemeRoles(s).joinToString(",") { (role, c) -> "$role:${colorToHex(c)}" }
  return CATALOG_COLORS_SCHEME_PREFIX + mode("l", light) + ";" + mode("d", dark)
}

/**
 * Decode a serialized palette for the requested [dark] mode, overriding only the roles it carries
 * on top of the stock M3 scheme. Null when [value] isn't a `scheme:` blob or has no usable role for
 * this mode. Unknown roles and malformed hex are skipped.
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
    // Only recognized roles count, so a segment of only typos yields null rather than a "usable"
    // mode.
    if (role in CATALOG_SCHEME_ROLE_NAMES) roles[role] = color
  }
  if (roles.isEmpty()) return null
  return applyColorRoles(if (dark) darkColorScheme() else lightColorScheme(), roles)
}

/** Overlay the decoded [roles] onto [base], leaving any other role at its base tone. */
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

/** Knob key the theme wrappers read for the M3 [Shapes] override. */
const val CATALOG_SHAPES_KNOB = "theme.shapes"

/**
 * Prefix marking a `theme.shapes` value as a serialized shape set:
 * `shapes:xs=<dp>,s=<dp>,m=<dp>,l=<dp>,xl=<dp>` (the five M3 corner radii). See
 * [serializeCatalogShapes] / [catalogShapes].
 */
const val CATALOG_SHAPES_PREFIX = "shapes:"

/**
 * Resolve a `theme.shapes` value to [Shapes]. A `shapes:` value overrides only the corners it
 * carries; anything else yields stock M3 [Shapes]. Never throws. Only uniform [RoundedCornerShape]
 * dp corners are expressible.
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
 * Serialize an app's five M3 corner sizes into the `theme.shapes` form. Takes dp values because a
 * built [androidx.compose.foundation.shape.CornerBasedShape] doesn't expose its size portably.
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

/** Knob key the theme wrappers read for the M3 [Typography] **metrics** override. */
const val CATALOG_TYPOGRAPHY_KNOB = "theme.typography"

/**
 * Prefix marking a `theme.typography` value as serialized type metrics:
 * `typo:<role>=<sizeSp>/<lineHeightSp>/<letterSpacingSp>/<weight>,…`. Numbers only — the typeface
 * comes from `theme.font`, since a font file can't ride a string knob. `-` keeps the base value.
 * See [serializeCatalogTypography] / [catalogApplyTypography].
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
 * Serialize a [Typography]'s per-role metrics into the `theme.typography` form; faces travel
 * separately via `theme.font`.
 */
fun serializeCatalogTypography(typography: Typography): String =
  CATALOG_TYPOGRAPHY_PREFIX +
    TYPE_ROLES.joinToString(",") { (name, get) ->
      val s = get(typography)
      "$name=${spOrDash(s.fontSize)}/${spOrDash(s.lineHeight)}/${spOrDash(s.letterSpacing)}/" +
        (s.fontWeight?.weight?.toString() ?: "-")
    }

/**
 * Overlay serialized type metrics onto [base] (which already carries the `theme.font` typeface),
 * replacing only supplied slots. Returns [base] when [value] isn't a usable `typo:` blob.
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

/** Overlay one role's metrics [spec] onto [style]; missing or unparseable slots keep the base. */
private fun applyRoleMetrics(style: TextStyle, spec: String?): TextStyle {
  if (spec.isNullOrEmpty()) return style
  val parts = spec.split("/")
  return style.copy(
    fontSize = parts.getOrNull(0)?.toFloatOrNull()?.sp ?: style.fontSize,
    lineHeight = parts.getOrNull(1)?.toFloatOrNull()?.sp ?: style.lineHeight,
    letterSpacing = parts.getOrNull(2)?.toFloatOrNull()?.sp ?: style.letterSpacing,
    // `FontWeight(int)` requires 1..1000; the knob is query-driven, so fall back rather than throw.
    fontWeight =
      parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 1..1000 }?.let { FontWeight(it) }
        ?: style.fontWeight,
  )
}

/**
 * Knob key for the M3 [Typography] font-family override, keyed by role group. Complements
 * `theme.typography` (metrics only): a family name can be resolved to a vendored face.
 */
const val CATALOG_FONTS_KNOB = "theme.fonts"

/**
 * Prefix marking a `theme.fonts` value as a per-role-group family map:
 * `families:<group>=<GoogleFont family>,…` with `<group>` in [CATALOG_FONT_ROLE_GROUPS]. Families
 * resolve via [namedFontFamily] against vendored faces; omitted groups and unvendored families keep
 * the `theme.font` base. See [parseCatalogFontFamilies] / `catalogApplyFontFamilies`.
 */
const val CATALOG_FONTS_PREFIX = "families:"

/** The five M3 type-role groups a [CATALOG_FONTS_PREFIX] blob keys, each covering three sizes. */
val CATALOG_FONT_ROLE_GROUPS: List<String> = listOf("display", "headline", "title", "body", "label")

/**
 * Serialize a role-group → family map into the `theme.fonts` form (known groups with non-blank
 * families, in canonical order). Empty map ⇒ empty string.
 */
fun serializeCatalogFontFamilies(families: Map<String, String>): String {
  val kept = CATALOG_FONT_ROLE_GROUPS.mapNotNull { g ->
    families[g]?.trim()?.takeIf(String::isNotEmpty)?.let { g to it }
  }
  return if (kept.isEmpty()) ""
  else CATALOG_FONTS_PREFIX + kept.joinToString(",") { (g, f) -> "$g=$f" }
}

/**
 * Parse a `families:` blob into role-group → family. Unknown groups and blank families are skipped;
 * anything unparseable yields an empty map, since the knob is query-driven.
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
