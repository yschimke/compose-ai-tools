# Emitting `design-map.json`

`design-map.json` is [design-parity](https://github.com/yschimke/design-parity)'s correspondence
file: it says which design node a code component is meant to look like. This repo now **writes**
one, from the catalog annotations it already defines.

```
./gradlew :<module>:composePreviewDiscover
node scripts/design-artifacts/emit-design-map.mjs \
  --previews <module>/build/compose-previews/previews.json
```

Both outputs are generated — regenerate rather than edit. `--check` regenerates in memory and exits
non-zero if a committed copy has drifted, which is the CI posture.

## Why the producer lives here

Every field the projection reads is defined in this repository:

| Field on `previews.json` | Declared by |
| --- | --- |
| `catalog.reference`, `referenceSet`, `noReference`, `referenceContentsOnly` | [`@CatalogComponent`](../../../api/preview-annotations/src/commonMain/kotlin/ee/schimke/composeai/preview/CatalogComponent.kt) |
| `catalog.props`, `catalog.state` | `@CatalogVariant` |
| `overrides.seeds`, `overrides.props` | `@OverrideVariant` / `@PreviewAxis` |

Rename one of those and the projection has to change in the same commit. Keeping the two on
opposite sides of a repo boundary is how a manifest reader goes quietly stale — and the reference
belongs on the annotation rather than in a JSON map for the same reason: a map keyed on preview
names drifts the moment a preview is renamed, and fails silently when it does.

The consuming half already lived here too — [`design-references.mjs`](../design-references.mjs)
reads a `design-map.json` to build a published catalog's `references/index.json`. Until now nothing
in the ecosystem wrote one except a hand-maintained script in a downstream catalog repo.

## Where the split falls

A catalog picturing `Button` at three sizes and two shapes has six renders and **one** reference.
Pairing the other five means answering "which kit node is `size=l`?" — and that is not a question
this repo can answer:

```
  previews.json
      │
      │  emit-design-map.mjs           ← THIS REPO. Knows what the annotations mean.
      │
      ├──▶ design-map.json             base references, one per component. Valid on its own.
      │
      └──▶ design-map-variants.json    "these previews are the same component with these knobs
                                        turned" — unresolved, because `size=l` is a fact about
                                        the Compose API and `Size=Large` is a fact about somebody's
                                        design kit
                │
                │  @design-parity/kit-index      ← THE OTHER REPO. Knows what the KIT means.
                ▼
           design-map.json with a tagged ref/previewId pair per variant
```

`size=l` → `Size=Large` is a translation against a kit's published vocabulary. That vocabulary is a
Figma concern, it needs a Figma credential to derive, and it differs per kit — none of which this
repo has any business holding. So the variant renders come out as **declarations** and a resolver
that owns a kit index turns them into node ids.

The two halves are separable because the first is useful alone: a repo with no kit index still gets
a valid map of base references, which is most of the value at none of the cost.

## The sidecar

`design-map-variants.json` carries `schema: "compose-preview-design-map-variants/v1"`; a resolver
must match that string before reading it. One entry per component that has variant renders:

```jsonc
{
  "schema": "compose-preview-design-map-variants/v1",
  "components": [
    {
      "code": "catalog/Catalog.kt#FilledButton",   // the design-map entry these belong to
      "componentId": "Button/Filled",
      "reference": "figma:AbCdEf/1:2",              // the node a resolver walks from
      "basePreviewId": "…FilledButton_Light",
      "renders": [
        { "previewId": "…FilledButton_Light_VARIANT_l", "name": "l",
          "seeds": [{ "key": "size", "raw": "l" }, { "key": "shape", "raw": "round" }] }
      ]
    }
  ]
}
```

It is a separate file rather than another key on the map because the design-map schema sets
`additionalProperties: false` — a map carrying an extra key would fail its own validator. No file is
written when nothing declares an axis.

## Two things worth knowing

**Only the light capture is mapped.** One entry per component, not per rendered mode, and the light
one — because that is the mode design kits draw their frames in. Diffing a dark render against a
light reference reports the whole palette as a finding.

**`overrides.props` beats `overrides.seeds` where both exist.** They are not the same list. `seeds`
holds only the values that differ from the composable's defaults; `props` — emitted for a
`@PreviewAxis` cross product — carries the full axis assignment, defaults included. A cell that
knows its own axes pairs by construction, which is exactly what `OverrideVariantSpec.props` was
added for. A cell described only by its non-default seeds is missing the axes it happens to sit at,
and a kit that spells its default size explicitly in a combination cell then has nothing to match.
