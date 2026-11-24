# Published report schemas

Stable, versioned JSON Schemas for the **main reports** the renderer and
daemon produce — the wire/on-disk contract that consumers (VS Code, MCP,
the CLI, `design-parity`, and the [reporting branch](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/REPORTING-BRANCH.md))
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
| [`compose-semantics.schema.json`](compose-semantics.schema.json) | `compose/semantics` | 14 | `ComposeSemanticsPayload` |
| [`compose-semantics-diff.schema.json`](compose-semantics-diff.schema.json) | `compose-semantics-diff/v1` | 1 | `SemanticsDelta` |
| [`compose-theme.schema.json`](compose-theme.schema.json) | `compose/theme` | 2 | `ThemePayload` |
| [`compose-theme-diff.schema.json`](compose-theme-diff.schema.json) | `compose-theme-diff/v1` | 1 | `ThemeDelta` |
| [`a11y-diff.schema.json`](a11y-diff.schema.json) | `a11y-diff/v1` | 1 | `A11yDelta` |
| [`history-data-diff.schema.json`](history-data-diff.schema.json) | `history-data-diff/v1` | 1 | `HistoryDataDelta` |
| [`render-trace.schema.json`](render-trace.schema.json) | `render/trace` | 1 | `RenderTraceDataProduct` |
| [`history-diff-regions.schema.json`](history-diff-regions.schema.json) | `history/diff/regions` | 1 | `HistoryDiffPayload` |

[`spatial-scene.schema.json`](spatial-scene.schema.json) is excluded from the
report-schema validator below. Both `spatial-scene.schema.json` and
`xr-render-service.schema.json` (along with their `gen-spatial-scene.mjs` and
`gen-xr-render-service.mjs` codegen scripts) now live canonically in
[`yschimke/compose-preview-daemon`](https://github.com/yschimke/compose-preview-daemon),
where [`yschimke/compose-preview-xr`](https://github.com/yschimke/compose-preview-xr)'s
`contract-drift` workflow checks its generated C++ and Python mirrors against
them. This repository keeps `spatial-scene.schema.json` only while the
`consumer-contract-notice` surface map still references it.

## Versioning & the bump policy

Each schema declares `x-composeai.schemaVersion`, mirroring the
`schemaVersion` integer the kind owns on the wire (see
[`docs/daemon/DATA-PRODUCTS.md`](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/DATA-PRODUCTS.md) § The primitive).

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
   `schema/fixtures/data-products/*.json`.

```sh
node scripts/validate-report-schemas.mjs
```

CI runs it on every PR via
[`.github/workflows/report-schemas.yml`](../.github/workflows/report-schemas.yml).

> **Follow-up (#1869):** once the daemon archives data products with each
> render, extend this to validate daemon-produced output, closing the loop
> from "examples conform" to "real renders conform".
