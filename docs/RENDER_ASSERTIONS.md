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

Paths available in the declarative form today:

- `fonts-used` — `everyFont.resolvedFamily`, `everyFont.requestedFamily`, `noFont.fellBackFrom`,
  `noFont.droppedVariationSettings`
- `compose-semantics` — `everyTextNode.typography.fontFamily`,
  `everyTextNode.typography.fontVariationSettings`, `anyNode.role`

`noFont.droppedVariationSettings` is the one that catches the weight collapse this feature came
from, and it needs compose-preview-daemon ≥ the release carrying the field
([#124](https://github.com/yschimke/compose-preview-daemon/issues/124)). It is worth knowing why no
other path can: `Paint.setFontVariationSettings` filters each requested axis against
`Typeface.isSupportedAxes` and, when nothing survives, returns `false` and leaves the typeface
untouched. `resolvedFamily` is still right — the *family* resolved, only the *face* did not — and
`fellBackFrom` is still empty, because nothing fell back. Against an older bundle the field is
absent, which reads as null and **passes**: an archived render cannot retroactively prove its axes
applied, and failing it would buy nothing.

`every*` and `noFont.*` are universal (one bad observation fails the preview); `anyNode.*` is
existential (one match satisfies it). An unknown product or path is a hard error, never a
silently-skipped assertion.

## Assertions as code

That vocabulary is closed, and it is closed in the *wrong repository*: "Glimmer types in Google Sans
Flex" is a fact about `m3-catalog`, a consumer, so needing a change here to state it is backwards
across a layer boundary. And `"contains 'wght'"` is already a string-encoded predicate — the next
asks are `not`, `oneOf`, `matches`, `>`, and the sum of those is a programming language with no
types and no debugger.

So an assertion may instead supply a **`check(data)`** returning `null` when it holds, or a string
naming what was observed. Point `--assertions` at a `.mjs` next to the catalog's spec:

```js
// m3-catalog/render-assertions.mjs
export const assertions = [
  {
    id: "bold-faces-come-from-a-variable-file",
    product: "fonts-used",
    because: "a static instance at weight 750 is the silent fallback this check exists for",
    // Two fields at once, conditionally — no `path: value` pair can state this.
    check: (data) => {
      const bad = data.fonts.filter((f) => f.weight > 500 && f.variable !== true);
      return bad.length === 0 ? null : bad.map((f) => `${f.resolvedFamily} ${f.weight}`).join(", ");
    },
  },
];
```

An assertion gives **either** `require` **or** `check`, never both — two sources of truth for one
verdict is a bug waiting for the day they disagree. `id`, `because`, `product`, `appliesTo` and
`exceptions` are unchanged and still belong to the framework.

**The declarative form is sugar, not a second engine.** A `require` is compiled by `compileRequire`
into exactly the `check` contract above and then evaluated by the same code path a hand-written one
takes. Two evaluators is how the two forms would start disagreeing about what "stale exception"
means.

Prefer `require` where it fits. JSON is enumerable — you can list every assertion, generate docs,
compute coverage and audit exceptions across catalogs — and code is opaque to all of that. `check`
is the escape hatch; if everything reaches for it, the vocabulary is wrong and that is the signal to
widen it rather than to keep writing code.

## Running them

```
node scripts/design-artifacts/check-render-assertions.mjs \
  --assertions render-assertions.json --bundle build/previews.zip

node scripts/design-artifacts/check-render-assertions.mjs \
  --assertions render-assertions.mjs --previews-dir build/previews
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
- a **sidecar that could not be parsed**, which is indistinguishable from having nothing to check;
- a **`check` that throws**, which fails rather than being skipped — fail-closed has to cover the
  escape hatch, or the escape hatch is the hole;
- a **module that exports no usable `assertions`**, so a typo in the export name cannot report green.

A `check` is never even consulted for a preview whose product came back empty: whether a render
produced data is the framework's judgement, not the assertion's, because a `check` that forgets the
empty case would return "holds" and report a pass over nothing.

The staleness cases are the ones that make a rule outlive the thing it was protecting. They are failures
by design; empty is a real state and the one to aim for, the same bar `glimmer-samples/quarantine.json`
sets for its entries.
