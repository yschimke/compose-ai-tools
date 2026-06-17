# Published report schemas

Stable, versioned JSON Schemas for the **main reports** the renderer and
daemon produce — the wire/on-disk contract that consumers (VS Code, MCP,
the CLI, `design-parity`, and the [reporting branch](../docs/daemon/REPORTING-BRANCH.md))
parse. The Kotlin `@Serializable` types named in each schema's
`x-composeai.kotlinType` remain the source of truth; these schemas are the
**published mirror** of those types, validated against representative
payloads so the two can't silently drift.

Part of the report-history epic (#1866); this directory is sub-issue #1867.

## Schemas

| Schema file | `kind` | version | Kotlin source of truth |
|---|---|---|---|
| [`history-entry.schema.json`](history-entry.schema.json) | `history/entry` | 1 | `daemon.history.HistoryEntry` |
| [`data-product-envelope.schema.json`](data-product-envelope.schema.json) | `*` | 2 | the `(kind, schemaVersion, payload)` wrapper |
| [`a11y-atf.schema.json`](a11y-atf.schema.json) | `a11y/atf` | 1 | `AccessibilityFindingsPayload` |
| [`a11y-hierarchy.schema.json`](a11y-hierarchy.schema.json) | `a11y/hierarchy` | 1 | `AccessibilityHierarchyPayload` |
| [`a11y-touch-targets.schema.json`](a11y-touch-targets.schema.json) | `a11y/touchTargets` | 1 | `AccessibilityTouchTargetsPayload` |
| [`compose-semantics.schema.json`](compose-semantics.schema.json) | `compose/semantics` | 5 | `ComposeSemanticsPayload` |
| [`compose-semantics-diff.schema.json`](compose-semantics-diff.schema.json) | `compose-semantics-diff/v1` | 1 | `SemanticsDelta` |
| [`compose-theme.schema.json`](compose-theme.schema.json) | `compose/theme` | 2 | `ThemePayload` |
| [`render-trace.schema.json`](render-trace.schema.json) | `render/trace` | 1 | `RenderTraceDataProduct` |
| [`history-diff-regions.schema.json`](history-diff-regions.schema.json) | `history/diff/regions` | 1 | `HistoryDiffPayload` |

[`spatial-scene.schema.json`](spatial-scene.schema.json) predates this set and
is **codegen source-of-truth** (it generates Kotlin/C++ via
`scripts/codegen/gen-spatial-scene.mjs`); it is validated by its own test and
excluded from the validator below.

## Versioning & the bump policy

Each schema declares `x-composeai.schemaVersion`, mirroring the
`schemaVersion` integer the kind owns on the wire (see
[`docs/daemon/DATA-PRODUCTS.md`](../docs/daemon/DATA-PRODUCTS.md) § The primitive).

- **Additive change** (a new optional field) → **no bump**. Consumers ignore
  unknown fields. For this reason the payload object schemas here do **not**
  set `additionalProperties: false`.
- **Incompatible change** (rename/remove a field, change a type, tighten
  `required`) → **bump** `schemaVersion` and revise the schema in the same
  change.

`required` lists only fields guaranteed present in the JSON: non-nullable
properties **without** a Kotlin default. Nullable or defaulted properties are
optional (kotlinx omits values equal to their default), so they are absent
from `required` and their `type` includes `"null"` where applicable.

## Validation

[`scripts/validate-report-schemas.mjs`](../scripts/validate-report-schemas.mjs)
(dependency-free, runs under plain `node`) checks every schema against:

1. its canonical `x-composeai.example` (hand-authored from the Kotlin type — see
   [`examples/`](examples/)), and
2. every matching `{ kind, payload }` embedded in the committed
   `vscode-extension/preview-harness/fixtures/*.json`.

```sh
node scripts/validate-report-schemas.mjs
```

CI runs it on every PR via
[`.github/workflows/report-schemas.yml`](../.github/workflows/report-schemas.yml).

> **Follow-up (#1869):** once the daemon archives data products with each
> render, extend this to validate daemon-produced output, closing the loop
> from "examples conform" to "real renders conform".
