---
title: Strings & i18n
parent: Reference
nav_order: 11
permalink: /reference/strings/
---

# Strings & i18n

Drawn text on the rendered frame (`text/strings`) and per-string
locale coverage from `values*/strings.xml` (`i18n/translations`).

## At a glance

| | |
|---|---|
| Kinds | `text/strings`, `i18n/translations` |
| Schema version | 1 |
| Modules | `:data-strings-core` (published) · `:data-strings-connector` |
| Render mode | default |
| Cost | low |
| Token usage | ~135 tok inline (`text/strings` ~470 chars); +~1.5 k per rendered PNG read. See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android (i18n is Android-only) · Desktop · shared (`text/strings`) |

## What it answers

- **`text/strings`** — every text run that ended up on screen, with locale, fontScale, fontSize, color, bounds, and per-entry `truncated` / `overflow` / `lineCount` / `maxLines` / `didOverflowWidth` / `didOverflowHeight` taken straight from Compose's `TextLayoutResult`.
- **`i18n/translations`** — for each visible string id, which locales have a translation and which fall through to the default. Highlights missing translations *that actually matter on this screen* — not the entire `strings.xml`.

## What it does NOT answer

- It does not score copy quality (clarity, brand voice, jargon) — that is a manual review.
- It does not measure RTL correctness — pair with a render at `locale=ar` and compare bounds.
- `i18n/translations` does not include Compose Multiplatform string resources (different resolution path, Android-specific for now).

## Use cases

- Catch text overflow before it ships: filter `text/strings` for `didOverflowWidth=true` across every preview.
- Audit a feature's locale coverage by listing visible strings whose missing-locale set is non-empty.
- Verify a `fontScale=2.0` accessibility test does not push critical CTAs off-screen.
- Drive jump-to-source on a drawn label in the VS Code extension.

## Payload shape

`TextStringsPayload`, `TextStringEntry`, `I18nTranslationsPayload`,
`I18nVisibleString` in
[`:data-strings-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/strings/core).

```jsonc
// text/strings
{
  "entries": [
    { "text": "Submit", "locale": "en-US", "fontScale": 1.0,
      "fontSize": 14, "color": "#FFFFFFFF",
      "boundsInScreen": "48,200,144,232",
      "truncated": false, "didOverflowWidth": false, "didOverflowHeight": false,
      "lineCount": 1, "maxLines": 1 }
  ]
}

// i18n/translations
{
  "strings": [
    { "id": "submit", "default": "Submit",
      "locales": { "en-US": "Submit", "fr": "Envoyer" },
      "missing": ["de", "es"] }
  ]
}
```

## Enabling

Producer runs once the Strings extension is publicly enabled. CLI /
Gradle output: `build/compose-previews/data/<id>/text-strings.json`
and `i18n-translations.json`.

## Companion products

- [Fonts](../fonts) — `fonts/used` for the typeface side of the same drawn text.
- [Resources](../resources) — `resources/used` for which `R.string.*` references resolved here.
- [Accessibility](../a11y) — `a11y/hierarchy` for assistive-technology labels alongside drawn copy.
