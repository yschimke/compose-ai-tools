package com.example.designcatalogm3.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

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
  )

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
    roles[entry.substring(0, sep).trim()] = color
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
