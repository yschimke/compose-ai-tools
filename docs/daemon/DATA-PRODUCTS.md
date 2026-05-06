# Preview data products

Per-render structured data the renderer can produce alongside the PNG
that a client (VS Code, MCP, the CLI) wants to render in its own UI.
Examples: ATF a11y findings, layout-inspector hierarchy, recomposition
heat maps, resource jump-to-source, theme tokens.

The wire surface is additive to PROTOCOL.md v1 — landing it does not
bump `protocolVersion`.

## The primitive

A **data product** is `(kind, schemaVersion, payload)`:

- `kind` — namespaced string. Reserved namespaces: `a11y/*`,
  `layout/*`, `compose/*`, `resources/*`, `text/*`, `render/*`,
  `fonts/*`, `test/*`. New namespaces are fair game; pick one whose
  intent is obvious on the wire.
- `schemaVersion` — positive integer, owned by the kind. Bumped only
  on incompatible payload changes; additive fields don't bump.
- `payload` — JSON. Shape per-kind, documented alongside the kind.

Transports:

- `payload: <JSON>` — inline; use for anything under ~64 KB serialised.
- `path: string` — absolute path to a sibling file the renderer wrote.
  Lifecycle matches the PNG: ephemeral, rewritten per render.
- `bytes: string` — base64; only when the caller passes `inline: true`.

A producer picks one transport per kind, advertised in capabilities.
A producer MAY support both `inline` and `path`; the caller picks via
`inline` on `data/fetch`.

## Documenting a kind

Every kind in the catalogue should follow the template below; product
issues can point at this section instead of restating it.

```markdown
### `<namespace/name>`

Status: proposed | shipped | deprecated
Producer: renderer-android | renderer-desktop | daemon | Gradle task
Mode: default | a11y | instrumented | live | failed render
Cost: low | medium | high
Transport: inline | path | inline-or-path | extra-only
Schema version: 1
Platforms: Android | Desktop | Wear | shared
Availability: fetch | subscribe | global attach | on-disk CLI | failed-render only
Companion products: `kind/a`, `kind/b`

Purpose: what this product answers; what it deliberately does not.

Payload: field names, types, units, coordinate space, stable identifiers.
Correlation keys back to preview ids, source refs, nodes, resources.

Extras: derived files, media types, lifecycle.

Failure / unavailable: when callers see DataProductUnknown,
DataProductNotAvailable, DataProductFetchFailed.

Examples: one small JSON payload; one PR review sentence an agent could
write from it.
```

Human-facing docs explain the stable contract. Agent-facing skill docs
should not duplicate the schema; they explain when to request the
product, which companions improve the evidence, and how to word a
review without overstating what the product proves.

### Image processors and extras

A kind's primary payload is JSON or a JSON-shaped path. Some producers
also ship **derived images** alongside — the Paparazzi-style a11y
overlay PNG is the load-bearing example. Two seams:

- **`extras`** — additive on `DataProductAttachment` and
  `DataFetchResult`. List of `{name, path, mediaType?, sizeBytes?}`
  pointing at sibling files. Pointer-only (no inlining).
- **`ImageProcessor`** (`daemon/core`) — pluggable post-render hook
  the renderer's `RenderEngine` runs after the PNG is captured. Each
  processor returns `Map<kind, List<extra>>` so one derived file can
  attach to multiple kinds (the a11y overlay rides under `a11y/atf`,
  `a11y/hierarchy`, AND the dedicated `a11y/overlay` kind).

The first concrete processor is `AccessibilityImageProcessor`. Output
lands at `<dataDir>/<previewId>/a11y-overlay.png`.

For pure-image kinds (`a11y/overlay`), `transport='path'` and the
fetch returns the PNG path directly. Clients that want both the JSON
and the picture can subscribe once to `a11y/atf` and read the overlay
out of the resulting attachment's `extras` list.

### On-disk layout

```
build/compose-previews/
  renders/<id>.png
  data/<id>/<kind-with-slashes-as-dashes>.json
```

`a11y/hierarchy` for preview `com.example.Foo_bar` lands at
`build/compose-previews/data/com.example.Foo_bar/a11y-hierarchy.json`.
The substitution is mechanical (kind `a/b/c` → file `a-b-c.json`);
kinds MUST NOT contain dashes themselves.

## Wire surface

All TypeScript shapes here are mirrored in
`daemon/core/.../protocol/Messages.kt`; fixtures live in
`docs/daemon/protocol-fixtures/`.

### `initialize`

```ts
capabilities: {
  dataProducts: {
    kind: string;
    schemaVersion: number;
    transport: 'inline' | 'path' | 'both';
    attachable: boolean;        // can ride renderFinished
    fetchable: boolean;         // can be requested via data/fetch
    requiresRerender: boolean;  // true → data/fetch may trigger one
  }[];
}

options: {
  attachDataProducts?: string[];  // default: []
}
```

`attachDataProducts` is the "always on, every render, every preview"
knob. Reserved for genuinely cheap kinds (today: `a11y/atf` only).

A daemon MUST advertise the kinds it can produce even when no client
subscribes — so the UI can grey out unavailable panels.

### `data/fetch`

```ts
params: {
  previewId: string;
  kind: string;
  params?: Record<string, unknown>;  // per-kind options
  inline?: boolean;
}
result: {
  kind: string;
  schemaVersion: number;
  payload?: unknown;
  path?: string;
  bytes?: string;
}
```

Resolves against the latest render. Three outcomes: read from cache;
recompute against cached state; trigger a re-render in the right mode
(see Re-render semantics).

### `data/subscribe` / `data/unsubscribe`

```ts
params: { previewId: string; kind: string }
result: { ok: true }
```

While subscribed, every `renderFinished` for `previewId` carries a
`dataProducts` entry for `kind`. Subscriptions are per-(previewId,
kind), idempotent, and drop automatically when `previewId` leaves the
most recent `setVisible` set. Reset across daemon restarts.

### `renderFinished` — additive `dataProducts` field

```ts
dataProducts?: {
  kind: string;
  schemaVersion: number;
  payload?: unknown;
  path?: string;
}[];
```

Populated only with currently-subscribed `(id, kind)` pairs plus
`initialize.options.attachDataProducts`. Empty / omitted when nothing
applies — clients MUST treat absent and `[]` identically.

### Error codes

`-32020 .. -32029` reserved for the data-product family:

| Code   | Name                       | Meaning |
|--------|----------------------------|---------|
| -32020 | DataProductUnknown         | Kind not advertised by daemon. |
| -32021 | DataProductNotAvailable    | Preview has never rendered; render first. |
| -32022 | DataProductFetchFailed     | Re-render or projection failed; details in `data`. |
| -32023 | DataProductBudgetExceeded  | Re-render budget tripped before payload landed. |

`error.data.kind: string` for machine-routable subcategories.

## Re-render semantics

A `data/fetch` that needs a re-render:

1. Picks the smallest render mode that produces the kind. `a11y/*`
   wants "a11y mode" (the same `composeai.a11y.enabled` toggle);
   `compose/recomposition` wants "default mode + recomposition
   instrumentation". Modes compose: a single re-render covers as many
   requested kinds as the modes overlap.
2. Charges the re-render against the per-request budget, not the
   global render queue's fairness rules. Default budget
   `daemon.dataFetchRerenderBudgetMs = 30000`.
3. Emits a normal `renderStarted` / `renderFinished` so the panel UI
   updates the PNG if it changed.
4. On budget exceeded: `DataProductBudgetExceeded`. The render that
   was triggered is not cancelled — Robolectric mid-render
   cancellation is unsafe — but the `data/fetch` gives up waiting.

## Catalogue (open set)

| Kind | Mode | Cost | Notes |
|---|---|---|---|
| `a11y/atf` | a11y | low | `AccessibilityFinding[]` from ATF. Overlay PNG as extra. |
| `a11y/hierarchy` | a11y | low | `AccessibilityNode[]` (label, role, states, bounds). |
| `a11y/overlay` | a11y | low | Path to annotated PNG. Pure-image. |
| `a11y/touchTargets` | a11y | low | 48dp + overlap detection. |
| `layout/inspector` | default | low | Compose layout/component hierarchy with bounds, constraints, modifiers, source refs. |
| `compose/semantics` | default | low | SemanticsNode projection — testTag, role, mergeMode, bounds. |
| `compose/recomposition` | instrumented | medium | `[{nodeId, count, sinceFrameStreamId?}]`. Heat map. Snapshot or click-delta. |
| `compose/theme` | default | medium | Resolved `MaterialTheme.*` values + which nodes consumed them. |
| `resources/used` | default | low | `R.*` references resolved during render. |
| `text/strings` | default | low | Drawn text with locale, fontScale, fontSize, colors, bounds, plus per-entry `truncated` / `overflow` / `lineCount` / `maxLines` / `didOverflowWidth/Height` from the Compose `TextLayoutResult`. |
| `i18n/translations` | default | low | Per-string locale coverage from `values*/strings.xml`. Android only. |
| `render/composeAiTrace` | default/live | low | Render pipeline trace as Perfetto-importable Chrome trace JSON. |
| `render/trace` | default | low | Phase breakdown from render metrics. |
| `fonts/used` | default | low | Font families with weight/style fallback chain. |
| `history/diff/regions` | default | low | Per-pixel bbox of changed regions vs. another history entry. |
| `test/failure` | failed render | low | Postmortem bundle: phase, error type/message/stack, fallback fields for what's not yet captured. Fetch-only after `renderFailed`. |

## Worked example: `a11y/hierarchy`

The first kind to ship; mirrors the renderer-side type:

```ts
// schemaVersion: 1
{
  nodes: {
    label: string;
    role: string | null;
    states: string[];
    merged: boolean;
    boundsInScreen: string;     // 'left,top,right,bottom' in PNG pixels
  }[];
}
```

When subscribed, the renderer runs the existing a11y pass and writes
JSON to `build/compose-previews/data/<id>/a11y-hierarchy.json`. When
not requested, daemon-driven renders skip
`AccessibilityChecker.writePerPreviewReport`'s overlay-baking step.
The CLI / Gradle path keeps baking the overlay since it has no client
to draw it locally.

## Module split (D2.2)

Each data product is a **pair of modules** under `data/<product>/`:

- **`:data-<product>-core`** — generic Android / Compose /
  AndroidX-test API code. No daemon coupling. **Published to Maven
  Central** so consumers can pull the primitives without standing up
  the daemon. For a11y: `AccessibilityChecker`, `AccessibilityOverlay`,
  the JSON models, round-device helpers. Coordinates:
  `ee.schimke.composeai:data-a11y-core`.
- **`:data-<product>-connector`** — daemon glue. Implements
  `DataProductRegistry` / `ImageProcessor` on top of the core
  primitives and `:daemon:core` wire types. Not published — internal
  to the daemon process.

Why split: cores are reusable in non-daemon contexts (`:gradle-plugin`,
`:cli`, third-party Robolectric tests, MCP clients in any language that
pull just the schema artifact). Connectors are thin adapters that
depend on `:daemon:core`.

The `ImageProcessor` interface lives in `:daemon:core` so every
connector can implement it without circular module dependencies.

### Schema source-of-truth

Each on-the-wire payload kind has exactly one `@Serializable` definition,
in the corresponding `data-<product>-core` module. MCP clients in other
languages can generate parsers from these without depending on the
Compose runtime, daemon, or AndroidX:

| Kind | Schema module | Schema type |
|---|---|---|
| `a11y/atf` | `:data-a11y-core` | `AccessibilityFindingsPayload`, `AccessibilityFinding` |
| `a11y/hierarchy` | `:data-a11y-core` | `AccessibilityHierarchyPayload`, `AccessibilityNode` |
| `a11y/touchTargets` | `:data-a11y-core` | `AccessibilityTouchTargetsPayload`, `AccessibilityTouchTarget` |
| `a11y/overlay` | `:data-a11y-core` | `AccessibilityOverlayArtifact` (path-only) |
| `compose/recomposition` | `:data-recomposition-core` | `RecompositionPayload`, `RecompositionNode` |
| `compose/semantics` | `:data-layoutinspector-core` | `ComposeSemanticsPayload`, `ComposeSemanticsNode` |
| `compose/theme` | `:data-theme-core` | `ThemePayload`, `ResolvedThemeTokens`, `TypographyToken` |
| `compose/wallpaper` | `:data-wallpaper-core` | `WallpaperPayload` |
| `fonts/used` | `:data-fonts-core` | `FontsUsedPayload`, `FontUsedEntry` |
| `history/diff/regions` | `:data-history-core` | `HistoryDiffPayload`, `HistoryDiffRegion` |
| `i18n/translations` | `:data-strings-core` | `I18nTranslationsPayload`, `I18nVisibleString` |
| `layout/inspector` | `:data-layoutinspector-core` | `LayoutInspectorPayload`, `LayoutInspectorNode` |
| `resources/used` | `:data-resources-core` | `ResourcesUsedPayload`, `ResourceUsedReference` |
| `text/strings` | `:data-strings-core` | `TextStringsPayload`, `TextStringEntry` |

Each `core` module advertises its kind identity and schemaVersion via a
`<Feature>Product` object (`HistoryDiffRegionsProduct.KIND`,
`Material3ThemeProduct.SCHEMA_VERSION`, etc.). Connectors and consumers
both refer to those constants — never inline the string literals.

### `data/scroll` is renderer-side only

`data/scroll/core` carries scroll-scenario drivers (`ScrollDriver`,
`ScrollGifEncoder`, `ScrollPreviewExtension`) that the renderer
composes through the regular extension pipeline. There is no
`data-scroll-connector` because scroll is not a daemon-side data
product — it produces image artifacts (GIFs, long PNGs), not a JSON
payload, so it has no `kind` and never appears on the
`initialize.capabilities.dataProducts` list. The renderer drives it
directly via the `PreviewPipelineStep` / scenario-driver hooks.
