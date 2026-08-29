package ee.schimke.composeai.data.theme

import ee.schimke.composeai.daemon.protocol.ThemeDelta
import ee.schimke.composeai.daemon.protocol.ThemeTokenChange
import ee.schimke.composeai.daemon.protocol.ThemeTypographyChange
import ee.schimke.composeai.daemon.protocol.TypographyToken

/**
 * A structural diff of two [ThemePayload]s' resolved Material 3 tokens (issue #1873) — the theme
 * analogue of [ee.schimke.composeai.data.layoutinspector.SemanticsDelta]. It answers "which design
 * tokens changed between two history entries" ("`primary` 0xFF6750A4 → 0xFFB3261E", "the `large`
 * shape corner went 16dp → 28dp") without reading pixels.
 *
 * Tokens are matched by their **name** (the stable map key Material 3 owns — `primary`,
 * `onSurface`, `bodyLarge`, `large`, …). A token present on only one side reports with the absent
 * side `null` (added when only `to` has it, removed when only `from` does); a token on both sides
 * with a differing value reports `from` → `to`. Only the resolved tokens are diffed —
 * [ThemePayload.consumers] (node → token attribution) is deliberately ignored, the way
 * `SemanticsDiff` ignores volatile bounds.
 */
public object ThemeDiff {

  public fun diff(base: ThemePayload, head: ThemePayload): ThemeDelta {
    val b = base.resolvedTokens
    val h = head.resolvedTokens
    return ThemeDelta(
      colorScheme = diffStringTokens(b.colorScheme, h.colorScheme),
      shapes = diffStringTokens(b.shapes, h.shapes),
      typography = diffTypographyTokens(b.typography, h.typography),
    )
  }

  private fun diffStringTokens(
    base: Map<String, String>,
    head: Map<String, String>,
  ): List<ThemeTokenChange> =
    (base.keys + head.keys).sorted().mapNotNull { token ->
      val from = base[token]
      val to = head[token]
      if (from != to) ThemeTokenChange(token = token, from = from, to = to) else null
    }

  private fun diffTypographyTokens(
    base: Map<String, TypographyToken>,
    head: Map<String, TypographyToken>,
  ): List<ThemeTypographyChange> =
    (base.keys + head.keys).sorted().mapNotNull { token ->
      val from = base[token]
      val to = head[token]
      if (from != to) ThemeTypographyChange(token = token, from = from, to = to) else null
    }
}
