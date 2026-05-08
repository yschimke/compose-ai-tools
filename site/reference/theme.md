---
title: Theme
parent: Reference
nav_order: 13
permalink: /reference/theme/
---

# Theme

Resolved Material 3 design tokens (colour scheme, typography, shapes)
plus a back-pointer to which nodes consumed each token.

## At a glance

| | |
|---|---|
| Kind | `compose/theme` |
| Schema version | 1 |
| Modules | `:data-theme-core` (published) · `:data-theme-connector` |
| Render mode | default |
| Cost | medium |
| Token usage | Inline JSON, not yet benchmarked (scales with consuming nodes). See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android · Desktop · shared |

## What it answers

- What Material 3 colour scheme was active during this render? Light or dark? Seed colour? Contrast level?
- What typography scale resolved for `headlineLarge`, `bodyMedium`, etc.?
- Which composables actually *consumed* `MaterialTheme.colorScheme.primary` — i.e. read the token, not just inherited a `LocalContentColor`?
- Did a theme refactor accidentally break a node's intended token (e.g. a `Surface` that now reads `surfaceVariant` instead of `surface`)?

## What it does NOT answer

- It does not opine on Material 3 best practice (don't use `tertiary` on a primary-action button) — it tells you *what* was used; the design intent is on the reviewer.
- It does not enumerate tokens declared but unused in the theme — only what was read.

## Use cases

- Verify a Material You seed-colour change propagated correctly: render under two seeds and diff the resolved schemes.
- Catch a stray hard-coded hex that a refactor was supposed to replace with a token reference.
- Drive a theme-token inspector panel in the VS Code extension.

## Payload shape

`ThemePayload`, `ResolvedThemeTokens`, `TypographyToken` in
[`:data-theme-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/theme/core).

```jsonc
// compose/theme
{
  "isDark": false,
  "seedColor": "#FF6750A4",
  "contrastLevel": 0.0,
  "colorScheme": {
    "primary": "#FF6750A4",
    "onPrimary": "#FFFFFFFF",
    "surface": "#FFFFFBFE"
    // …
  },
  "typography": [
    { "name": "headlineLarge", "fontFamily": "Roboto",
      "fontWeight": 400, "fontSize": 32, "lineHeight": 40 }
  ],
  "consumers": [
    { "token": "primary", "nodeIds": [12, 14] }
  ]
}
```

## Enabling

Producer runs once the Theme extension is publicly enabled. May
require a small re-render to attribute consumers to nodes — the
default `daemon.dataFetchRerenderBudgetMs = 30000` covers it. CLI /
Gradle output: `build/compose-previews/data/<id>/compose-theme.json`.

## Companion products

- [Wallpaper](../wallpaper) — `compose/wallpaper` for the Material You seed → derived scheme upstream of `compose/theme`.
- [Layout inspector](../layout-inspector) — `layout/inspector` to map a `nodeId` consumer back to source.
- [Resources](../resources) — `resources/used` for `R.color.*` references that fed the scheme.
