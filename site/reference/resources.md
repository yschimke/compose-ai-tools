---
title: Resources
parent: Reference
nav_order: 9
permalink: /reference/resources/
---

# Resources

Every `R.*` reference resolved during render — drawables, strings,
colours, dimens, plurals, attrs, and theme attributes — with the
resolved value and the source qualifier (default vs. `night`,
`w600dp`, locale-specific, …).

## At a glance

| | |
|---|---|
| Kind | `resources/used` |
| Schema version | 1 |
| Modules | `:data-resources-core` (published) · `:data-resources-connector` |
| Render mode | default |
| Cost | low |
| Token usage | ~175 tok per query (`resources/used` ~610 chars). See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android |

## What it answers

- Which `R.drawable.*` / `R.string.*` / `R.color.*` references actually got read while rendering this preview?
- Which qualifier folder did each value come from (e.g. `values-night/colors.xml` vs. `values/colors.xml`)?
- Are any references dead — declared but never read on this preview?
- Did a refactor accidentally introduce a reference to a removed resource id?

## What it does NOT answer

- It does not enumerate resources the preview *could* reference under a different configuration (different locale, dark mode) — only what was read on this render.
- It does not validate XML schema correctness — that is `aapt2`'s job.
- Compose Multiplatform resources flow through a different path; this kind is Android-only.

## Use cases

- Jump-to-source from VS Code: click a colour in the rendered PNG, get the `R.color.foo` it came from and the `values*/colors.xml` it was defined in.
- Dead-code audits: render every preview and diff `resources/used` against the union of declared `R.*` ids.
- Verify a string id rename was thorough — every preview that previously cited `R.string.old_name` now cites `R.string.new_name`.

## Payload shape

`ResourcesUsedPayload`, `ResourceUsedReference` in
[`:data-resources-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/resources/core).

```jsonc
// resources/used
{
  "references": [
    { "type": "string", "name": "submit", "id": "0x7f110042",
      "value": "Submit",
      "sourceFile": "values/strings.xml" },
    { "type": "color", "name": "primary", "id": "0x7f060010",
      "value": "#FF6750A4",
      "sourceFile": "values-night/colors.xml" }
  ]
}
```

## Enabling

Producer runs once the Resources extension is publicly enabled. CLI /
Gradle output: `build/compose-previews/data/<id>/resources-used.json`.

The plugin also separately renders Android XML resources directly
(vector drawables, adaptive icons, animated-vector drawables) — that
is a different feature; see
[`skills/compose-preview/design/RESOURCE_PREVIEWS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/design/RESOURCE_PREVIEWS.md).

## Companion products

- [Strings](../strings) — `text/strings` for the actual drawn copy resolved from `R.string.*` references.
- [Theme](../theme) — `compose/theme` for the Material 3 tokens that consumed `R.color.*` values.
- [Fonts](../fonts) — `fonts/used` for the typeface side of the same render.
