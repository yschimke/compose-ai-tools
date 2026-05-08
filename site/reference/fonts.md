---
title: Fonts
parent: Reference
nav_order: 4
permalink: /reference/fonts/
---

# Fonts

Report every font family used during render, with weight, style, and
the resolved fallback chain Compose actually picked.

## At a glance

| | |
|---|---|
| Kind | `fonts/used` |
| Schema version | 1 |
| Modules | `:data-fonts-core` (published) · `:data-fonts-connector` |
| Render mode | default |
| Cost | low |
| Transport | inline |
| Platforms | Android · Desktop · shared |

## What it answers

- Which font families ended up on screen?
- For each `Text(...)` call site, what weight / style was requested vs. what the resolver returned?
- Did Compose fall back to a system font because a custom font failed to load?
- Are any glyphs being served by a font you didn't expect to ship in the APK / app bundle?

## What it does NOT answer

- It does not measure font file size, CFF subsetting, or runtime download cost — that is a build-tools / Play asset delivery question.
- It does not validate whether a glyph is *visually* correct (Han ideograph variants, complex script shaping) — that requires designer review against the rendered PNG.

## Use cases

- Catch accidental fallback to `sans-serif` after a `FontFamily.Resolver` refactor.
- Verify a paid brand font is the one actually drawing your headings, not a system stand-in.
- Audit a Wear app's text for legibility-critical weights (Medium / SemiBold) the design system requires.

## Payload shape

`FontsUsedPayload`, `FontUsedEntry` in
[`:data-fonts-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/fonts/core).

```jsonc
// fonts/used
{
  "fonts": [
    { "family": "Roboto", "weight": 500, "style": "Normal",
      "source": "system", "fallbackFrom": null },
    { "family": "Inter", "weight": 700, "style": "Italic",
      "source": "asset", "fallbackFrom": "InterVariable" }
  ]
}
```

## Enabling

Producer runs by default once the Fonts extension is publicly enabled
(`extensions/enable`). On the CLI / Gradle path the JSON lands at
`build/compose-previews/data/<id>/fonts-used.json`.

## Companion products

- [Strings](../strings) — `text/strings` for the actual drawn text the fonts rendered.
- [Theme](../theme) — `compose/theme` for the resolved typography tokens that picked these fonts.
