# Render assertions

A catalog says **what** to render. The visual diff says whether a render **changed**. Neither says
whether a render is still **true** to a property the project declared.

That gap is not hypothetical. `:glimmer-catalog` rendered all seven Glimmer type roles at weight 400
for its entire life: the variation axes carrying 520/650/750 were filtered away by
`Paint.setFontVariationSettings` against a static font instance, silently. It passed the render, it
passed `failOnFallback` (the *family* resolved — only the *face* did not), and it passed the visual
diff (it was wrong from the very first render, so nothing ever changed). Every fact needed to catch
it was already on disk: `fonts-used` named the resolved family, `compose-semantics` carried
`typography.fontVariationSettings` per text node. Nothing read either against an expectation.

Render assertions close that. They are declared per module, evaluated against the data-product
sidecars a render **already** writes, and they never inspect a raster — a new assertable property is
a data-product question, answered there.

Design, open questions and the roadmap beyond this first slice:
[#5467](https://github.com/yschimke/compose-ai-tools/issues/5467).

## Declaring them

```json
{
  "assertions": [
    {
      "id": "glimmer-types-in-google-sans-flex",
      "product": "fonts-used",
      "because": "a sticker sheet that silently types in Roboto is not the design system it claims",
      "require": { "everyFont.resolvedFamily": "Google Sans Flex" }
    },
    {
      "id": "scrolling-wear-screens-show-a-position-indicator",
      "product": "compose-semantics",
      "because": "a scrollable Wear screen with no position indicator strands the user mid-list",
      "appliesTo": { "previews": ["Wear*"] },
      "require": { "anyNode.role": "ScrollPositionIndicator" },
      "exceptions": [
        { "preview": "WearSplash", "reason": "single fixed screen, nothing to scroll" }
      ]
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | Unique; names the rule in the report. |
| `product` | `fonts-used` or `compose-semantics`. |
| `because` | Required. What being wrong would mean — a rule nobody can justify later is a rule nobody dares delete. |
| `require` | Exactly one `path: expected`. `"contains X"` is a substring check; anything else is equality. |
| `appliesTo.previews` | Optional `*` globs. Defaults to every preview. |
| `exceptions[]` | Each needs a `preview` and a `reason`. A silent allowlist rots. |

Paths available today:

- `fonts-used` — `everyFont.resolvedFamily`, `everyFont.requestedFamily`, `noFont.fellBackFrom`
- `compose-semantics` — `everyTextNode.typography.fontFamily`,
  `everyTextNode.typography.fontVariationSettings`, `anyNode.role`

`every*` and `noFont.*` are universal (one bad observation fails the preview); `anyNode.*` is
existential (one match satisfies it). An unknown product or path is a hard error, never a
silently-skipped assertion.

## Running them

```
node scripts/design-artifacts/check-render-assertions.mjs \
  --assertions render-assertions.json --bundle build/previews.zip

node scripts/design-artifacts/check-render-assertions.mjs \
  --assertions render-assertions.json --previews-dir build/previews
```

Both flags are repeatable and merge into one render set, so a multi-module catalog asserts across
its whole render rather than once per bundle. `--json` emits the structured result. Exit 0 when
every assertion holds.

## What counts as a failure

The design premise is that an assertion which quietly stops asserting is worse than no assertion —
it reads as coverage that is not there. So these all fail, not just a mismatched value:

- a preview whose product carried **no observations** (`no-data`);
- an assertion that **matched no preview at all** — a renamed preview, a dropped module, an
  `appliesTo` pattern that no longer hits anything;
- an **exception that is stale** — the preview it excuses now passes, or no longer exists;
- a **sidecar that could not be parsed**, which is indistinguishable from having nothing to check.

The last three are the ones that make a rule outlive the thing it was protecting. They are failures
by design; empty is a real state and the one to aim for, the same bar `glimmer-samples/quarantine.json`
sets for its entries.
