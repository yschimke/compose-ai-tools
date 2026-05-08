---
title: Wallpaper
parent: Reference
nav_order: 14
permalink: /reference/wallpaper/
---

# Wallpaper

Material You seed colour → derived `ColorScheme`, computed by the same
algorithm Android uses for system wallpaper-driven theming
(`material-kolor` / `dynamic-color-utilities`).

## At a glance

| | |
|---|---|
| Kind | `compose/wallpaper` |
| Schema version | 1 |
| Modules | `:data-wallpaper-core` (published) · `:data-wallpaper-connector` |
| Render mode | default |
| Cost | low |
| Token usage | Inline JSON, not yet benchmarked. See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android · Wear |

## What it answers

- Given a seed colour and palette style, what Material 3 `ColorScheme` does the system derive?
- How does the scheme change across `TonalSpot`, `Vibrant`, `Expressive`, `Monochrome`, etc.?
- What does the same composable look like for three different wallpaper seeds without booting an emulator?

The connector primes a `WallpaperOverride` so consumer code that
reads the dynamic colour scheme sees the requested seed and palette
style.

## What it does NOT answer

- It does not extract a seed from a real wallpaper bitmap — you supply the seed; that is upstream of this product.
- It does not opine on contrast accessibility — pair with [`a11y/atf`](../a11y) under the chosen scheme.
- The schema of `derivedColorScheme` matches `compose/theme`'s `colorScheme`; for *which composables consumed which token*, fetch `compose/theme` instead.

## Use cases

- Render Material You previews of a "what does this app look like for users with different wallpapers" PR review.
- Catch seeds that produce inaccessible contrast pairings on a critical CTA before they ship.
- Build a marketing screenshot set across the standard Material You palette styles.

## Payload shape

`Material3WallpaperProduct.KIND` /  `WallpaperPayload` in
[`:data-wallpaper-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/wallpaper/core).

```jsonc
// compose/wallpaper
{
  "seedColor": "#FF6750A4",
  "isDark": false,
  "paletteStyle": "TONAL_SPOT",
  "contrastLevel": 0.0,
  "derivedColorScheme": {
    "primary": "#FF6750A4",
    "onPrimary": "#FFFFFFFF",
    "surface": "#FFFFFBFE"
    // …
  }
}
```

## Enabling

Pass a `WallpaperOverride` on `renderNow.overrides.wallpaper` (MCP)
to drive the seed / style / contrast / dark mode.

## Companion products

- [Theme](../theme) — `compose/theme` for the full Material 3 token set after the wallpaper-derived scheme is plugged in.
- [Ambient](../ambient) — `compose/ambient` for the Wear ambient-state side of the same render.
