# Preview data products — design

> **Status:** Proposal. Nothing on the wire yet. Locked-but-additive against
> [PROTOCOL.md § 7](PROTOCOL.md#7-versioning) — landing this surface does
> not bump `protocolVersion`. Phase plan at the end.

## What this is

Today the daemon hands back a PNG path on every `renderFinished` and that's it.
Per-render structured data exists *somewhere* — ATF findings and the
accessibility hierarchy land in `build/compose-previews/accessibility-per-preview/<id>.json`,
the Gradle plugin's `accessibility.json` aggregates them, and the renderer
also bakes a Paparazzi-style overlay PNG. None of it crosses the JSON-RPC
channel. The VS Code extension reads the on-disk JSON via the registry and
draws nothing — it just feeds findings to the diagnostic squigglies.

We want more: the extension would like to draw its own a11y overlay (live,
hover-aware, no second-pass PNG bake), surface a Compose layout inspector,
show recomposition heat maps, jump to source from resources resolved during
the render, and so on. Each of those is "structured data the renderer can
produce alongside the PNG, that the client wants to render in its own UI."

This doc pins a single primitive — the **data product** — and the JSON-RPC
surface for asking for one. Open set: kinds are namespaced strings, additive
across daemon and extension releases. Daemon advertises what it knows how
to produce; extension subscribes to what it knows how to render; unknown
kinds are silently ignored both ways.

## Goals & non-goals

**Goals**:

1. One primitive (`data product`) covers a11y findings, a11y hierarchy,
   layout tree, recomposition heat map, theme resolution, resource refs,
   render trace, and anything we add later.
2. **Default is nothing.** A daemon talking to a default-configured client
   ships only `pngPath` + the existing optional `metrics`. Data products
   ride the wire only when the UI explicitly asks.
3. Three access patterns, picked to match how the data is paid for:
   - **Fetch on demand** for "user clicked a panel that needs it" — pay
     per view, fall back to a re-render if the kind wasn't computed during
     the last pass.
   - **Subscribe** while a panel is open — sticky `(previewId, kind)`
     pair, daemon attaches the payload to that preview's renders until
     unsubscribe.
   - **Global attach** in `initialize` — reserved for "always on
     everywhere" cases (cheap, always-relevant data like `a11y/atf`
     findings that drive diagnostic squigglies). Most clients leave it
     empty.
4. Layering ([LAYERING.md](LAYERING.md)) preserved: data products are a
   Layer 2 (daemon) surface. Renderer-side production sits in
   `renderer-{android,desktop}` and is reused by the CLI; the daemon is
   thin glue that surfaces what the renderer already produces.
5. **No `protocolVersion` bump.** Adding a kind, adding a field to a
   payload, adding a new method in the `data/*` family — all additive
   per PROTOCOL.md § 7. Both sides ignore unknowns.
6. CLI parity, **on-disk only**. Daemon and CLI share the renderer-side
   producers, so a Gradle-driven render of an a11y-enabled module writes
   the same `build/compose-previews/data/<id>/<kind>.json` files the
   daemon would attach to `renderFinished`. CLI consumers (CI scripts,
   non-MCP agents) read those files directly — there is **no
   `--emit kind` flag** and the CLI surface stays as "render to disk,
   look in `build/`." Kinds are selected via the consumer's
   `composePreview { ... }` Gradle config (the same channel that gates
   `previewExtensions { a11y { ... } }`); duplicating that selection on
   the CLI would just create two ways to express the same intent and
   let them drift. The agent ergonomics that justify a programmatic
   surface — `data/fetch` re-render, `data/subscribe` priming, kind
   discovery — live in the daemon protocol and its MCP front-end, not
   the CLI.

Gradle-side selection is preview-extension-scoped:

```kotlin
composePreview {
  previewExtensions {
    // Well-known preview extensions get a typed DSL.
    a11y { enableAllChecks() }

    // Or leave the plugin disabled and enable only specific checks.
    a11y { checks.add("atf") }

    // Unknown/future preview extensions can still be addressed generically.
    extension("layout") { checks.add("tree") }
  }
}
```

The matching one-shot properties are
`-PcomposePreview.previewExtensions.<plugin>.enableAllChecks=true` and
`-PcomposePreview.previewExtensions.<plugin>.checks=checkA,checkB`.

**Non-goals**:

1. **Not a transport for PNG bytes.** PNGs still travel as paths on the
   filesystem. Data products are JSON or, for already-on-disk blobs,
   pointers. `bytes: base64` is opt-in per call (mirrors `history/read`).
2. **Not a way to push live state.** The reserved `data/subscribe`
   surface is sticky-by-preview, not streaming-by-frame. Frame-rate
   streams belong to the interactive-mode work
   ([INTERACTIVE.md](INTERACTIVE.md)) and reuse its `frameStreamId`
   plumbing if/when they need it.
3. **Not a render cache.** A `data/fetch` against a preview that has
   never rendered returns `DataProductNotAvailable` rather than secretly
   queuing a render. Re-render-on-demand is only triggered when the
   preview *has* rendered but the latest pass didn't compute the
   requested kind (see § Re-render semantics).
4. **No authentication.** Same trust model as the rest of the protocol
   — daemon trusts its parent process.

## The primitive

A **data product** is `(kind, schemaVersion, payload)` where:

- `kind` is a namespaced string. Reserved namespaces:
  `a11y/*`, `layout/*`, `compose/*`, `resources/*`, `text/*`,
  `render/*`, `fonts/*`, `test/*`. New namespaces are fair game; pick
  one that makes intent obvious from the wire.
- `schemaVersion` is a positive integer, owned by the kind. Bumped only
  when the payload shape changes incompatibly. Additive fields don't
  bump.
- `payload` is JSON. Shape is per-kind; documented alongside the kind.

Transports:

- `payload: <JSON>` — inline. Use for anything under ~64 KB serialised
  (typical a11y hierarchy: ~5 KB).
- `path: string` — absolute path to a sibling file the renderer wrote
  (the existing accessibility-per-preview JSON, or a future
  `layout-tree.json`). Lifecycle matches the PNG: ephemeral, rewritten
  per render. Extension reads it from disk.
- `bytes: string` — base64. Only populated when the caller passes
  `inline: true` on a `data/fetch`. For non-local clients (none today;
  reserved for future remote-daemon scenarios).

A producer picks one transport per kind, advertised in capabilities. A
producer MAY support both `inline` and `path`; the caller picks via
the `inline` flag on `data/fetch`.

## Documenting a kind

Every data product added to the catalogue should have a human-facing contract
that follows the same shape. Keep this section stable enough that individual
product issues can point at it instead of restating the whole contract.

Template:

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

Purpose:

- What question this product answers for a human or agent.
- What it deliberately does not answer.

Payload:

- Field names, types, units, coordinate space, and stable identifiers.
- Correlation keys back to preview ids, source refs, nodes, resources, or
  screenshot regions.
- Redaction, privacy, and truncation behavior.

Extras:

- Any derived files, media types, and lifecycle.

Failure and unavailable behavior:

- When callers get `DataProductUnknown`, `DataProductNotAvailable`,
  `DataProductFetchFailed`, or a partial payload.
- Whether a missing payload is a product bug, a disabled producer, or a normal
  platform limitation.

Examples:

- One small JSON payload.
- One review sentence an agent could write from the payload.
```

Human-facing docs should explain the stable contract above. Agent-facing skill
docs should not duplicate the full schema; they should explain when to request
the product, which companion products improve the evidence, and how to word a
PR comment without overstating what the product proves.

### Image processors and extras

A kind's primary payload is JSON or a JSON-shaped path. Some producers
also want to ship **derived images** alongside — the Paparazzi-style
a11y overlay PNG is the load-bearing example. Two seams cover this:

- **`extras` field** — additive on `DataProductAttachment` and
  `DataFetchResult`. List of `{name, path, mediaType?, sizeBytes?}`
  entries pointing at sibling files the producer wrote. Pointer-only
  (no inlining) because the files are typically tens of KB and the
  daemon already lives on the client's filesystem. Empty / absent
  are interchangeable on the wire.
- **`ImageProcessor` interface** (`daemon/android`) — pluggable
  post-render hook the renderer's [`RenderEngine`] runs after the PNG
  is captured. Each processor gets `(previewId, pngFile, dataDir,
  isRound, accessibility?)` and returns a `Map<kind, List<extra>>` so
  the registry can attach the same derived file to multiple kinds (the
  a11y overlay rides under `a11y/atf`, `a11y/hierarchy`, AND the
  dedicated `a11y/overlay` kind).

The first concrete processor is `AccessibilityImageProcessor`, which
adapts the existing renderer-side `AccessibilityOverlay.generate(...)`
into the new contract. Output lands at
`<dataDir>/<previewId>/a11y-overlay.png`; same directory the JSON
artefacts live in. The Gradle / CLI path keeps its own overlay bake
via `AccessibilityChecker.writePerPreviewReport` — same generator,
different writer — so on-disk consumers are unchanged.

For pure-image kinds (`a11y/overlay`), `transport='path'` and the
fetch returns the PNG path directly. Clients that want both the JSON
and the picture can subscribe once to `a11y/atf` and read the overlay
out of the resulting attachment's `extras` list — no second round-trip.

### On-disk layout

Today a11y-per-preview lives at
`build/compose-previews/accessibility-per-preview/<id>.json`. Generalising:

```
build/compose-previews/
  renders/<id>.png
  data/<id>/<kind-with-slashes-as-dashes>.json
```

So `a11y/hierarchy` for preview `com.example.Foo_bar` lands at
`build/compose-previews/data/com.example.Foo_bar/a11y-hierarchy.json`, and
`a11y/touchTargets` lands beside it as `a11y-touchTargets.json`.
The slash-to-dash substitution is mechanical (kind `a/b/c` → file
`a-b-c.json`); kinds MUST NOT contain dashes themselves to keep the
mapping reversible. The existing accessibility-per-preview directory
stays for one release as a back-compat read alias, then retires.

## Wire surface

All additive to PROTOCOL.md v1. The TypeScript shapes here are mirrored
in Kotlin under `daemon/core/.../protocol/Messages.kt`; fixtures live
in `docs/daemon/protocol-fixtures/`.

### `initialize` — capability advertising and global attach

```ts
// initialize result, additive field on `capabilities`
capabilities: {
  // ...existing fields...
  dataProducts: {
    kind: string;
    schemaVersion: number;
    transport: 'inline' | 'path' | 'both';
    attachable: boolean;       // can ride renderFinished
    fetchable: boolean;        // can be requested via data/fetch
    requiresRerender: boolean; // true → data/fetch may trigger one
  }[];
}

// initialize params, additive field on `options`
options: {
  // ...existing fields...
  attachDataProducts?: string[];  // default: []
}
```

`attachDataProducts` is the "always on, every render, every preview"
knob. Reserved for genuinely cheap kinds where the diagnostics surface
needs them ambient (today: `a11y/atf` only). Most clients leave it `[]`
and use `data/subscribe` instead.

A daemon MUST advertise the kinds it can produce even when the client
didn't subscribe — so the UI can grey out unavailable panels. A client
asking for an unadvertised kind gets `DataProductUnknown` (-32020).

### `data/fetch` (request, client → daemon)

```ts
// params
{
  previewId: string;
  kind: string;
  params?: Record<string, unknown>;  // per-kind options (e.g. { nodeId } for layout/inspector)
  inline?: boolean;                  // true → daemon inlines payload (or bytes for blobs)
}

// result
{
  kind: string;
  schemaVersion: number;
  payload?: unknown;                 // per-kind JSON; undefined if path/bytes used
  path?: string;                     // when transport='path' and inline=false
  bytes?: string;                    // when inline=true and transport=blob
}
```

Resolves against the latest render of `previewId`. Three outcomes:

1. **Latest render already produced the kind** (because the client is
   subscribed, or because it's globally attached) — daemon reads from
   disk / cache and returns. Cheap.
2. **Latest render didn't compute the kind, the kind is producible
   without a re-render** (e.g. `compose/semantics` derives from the
   same View tree the renderer kept around) — daemon recomputes against
   the cached state and returns. Bounded cost.
3. **Latest render didn't compute the kind, the kind needs a different
   render mode** (e.g. `compose/recomposition` needs instrumented
   composables) — daemon queues a re-render of just `previewId` in the
   required mode, returns the payload when it lands, and emits a
   normal `renderFinished` if the PNG also changed. Subject to a
   per-request budget (`daemon.dataFetchRerenderBudgetMs`,
   default 30000) — exceeded calls return `DataProductFetchFailed`.

Errors:

- `DataProductUnknown` (-32020) — `kind` not advertised by this daemon.
- `DataProductNotAvailable` (-32021) — `previewId` has never rendered;
  caller should issue `renderNow` first.
- `DataProductFetchFailed` (-32022) — re-render or projection failed;
  details in `data`.
- `UnknownPreview` (-32004) — `previewId` not in the discovery set.

### `data/subscribe` / `data/unsubscribe` (request, client → daemon)

```ts
// params (both)
{ previewId: string; kind: string }

// result (both)
{ ok: true }
```

While subscribed, every `renderFinished` for `previewId` carries a
`dataProducts` entry for `kind`. Subscriptions are per-client and
per-(previewId, kind); calling subscribe twice is idempotent.
Subscriptions drop automatically when `previewId` leaves the most
recent `setVisible` set — the UI is invited to re-subscribe when the
preview comes back into view, rather than the daemon retaining state
for unseen cards.

Subscriptions reset across daemon restarts; the client re-subscribes
on `initialize`.

Errors:

- `DataProductUnknown` (-32020) — kind not advertised, or kind is
  advertised with `attachable: false`.
- `UnknownPreview` (-32004) — `previewId` not in the discovery set.

### `renderFinished` — additive field

```ts
// existing fields unchanged
{
  id: string;
  pngPath: string;
  tookMs: number;
  metrics?: { ... };
  // additive
  dataProducts?: {
    kind: string;
    schemaVersion: number;
    payload?: unknown;
    path?: string;
  }[];
}
```

Populated only with the `(id, kind)` pairs the client has currently
subscribed to, plus everything in `initialize.options.attachDataProducts`.
Empty / omitted when nothing applies — clients MUST treat absent and
`[]` identically.

### Error codes

Reserves `-32020 .. -32029` for the data-product family:

| Code   | Name                       | Meaning |
|--------|----------------------------|---------|
| -32020 | DataProductUnknown         | Kind not advertised by daemon. |
| -32021 | DataProductNotAvailable    | Preview has never rendered; render first. |
| -32022 | DataProductFetchFailed     | Re-render or projection failed; details in `data`. |
| -32023 | DataProductBudgetExceeded  | Re-render budget tripped before payload landed. |

`error.data` follows the existing convention — `data.kind: string` for
machine-routable subcategories.

## Re-render semantics

A `data/fetch` that needs a re-render:

1. Picks the smallest render mode that produces the kind. For
   `compose/recomposition` that's "default mode + recomposition
   instrumentation"; for `a11y/hierarchy` that's "a11y mode" — same
   one `composeai.a11y.enabled` already toggles. Modes compose: a
   single re-render covers as many of the requested kinds as the
   modes overlap.
2. Charges the re-render against the per-request budget, not the
   global render queue's fairness rules — fetches are user-initiated
   and should not get starved by streaming `setVisible` traffic.
3. Emits a regular `renderStarted` / `renderFinished` so the panel UI
   updates the PNG if it changed. The `data/fetch` response resolves
   when the payload is ready, which is normally the same wall-clock
   moment as the `renderFinished`.
4. On budget exceeded, returns `DataProductBudgetExceeded`. The render
   that was triggered is not cancelled — Robolectric mid-render
   cancellation is unsafe (PROTOCOL.md § 8) — but the `data/fetch`
   gives up waiting for it.

## Catalogue (open set)

Documented for orientation. The first-released kinds are flagged
SHIPPED; everything else is "we know the shape, no code yet."

| Kind                       | Mode | Cost | Notes |
|----------------------------|------|------|-------|
| `a11y/atf`                 | a11y | low  | `AccessibilityFinding[]` from ATF. Drives diagnostic squigglies. Cheap enough for global attach. Carries the `overlay` PNG as an extra when one was generated. |
| `a11y/hierarchy`           | a11y | low  | `AccessibilityNode[]` (label, role, states, bounds). Powers a local overlay in VS Code. **First implementation.** Carries the `overlay` PNG as an extra. |
| `a11y/overlay`             | a11y | low  | Path to the Paparazzi-style annotated PNG produced by `AccessibilityImageProcessor`. Pure-image kind — `transport='path'`, no JSON payload. **D2.1 — image-processor surface.** |
| `a11y/touchTargets`        | a11y | low  | Derived from hierarchy; 48dp + overlap detection. **D2.2 — shipped inline payload.** Carries the `overlay` PNG as an extra. |
| `layout/inspector`         | default | low | Compose layout/component hierarchy for layout-inspector style inspection: component tree, bounds, measured size, constraints, z-order, inspectable modifier values, and source refs. **Android daemon implementation:** walks Compose `RootForTest` → `LayoutNode` and correlates slot-table source information collected with `collectParameterInformation()`. |
| `compose/semantics`        | default | low | SemanticsNode projection — testTag, role, mergeMode, bounds. **Android daemon implementation.** |
| `compose/recomposition`    | instrumented | medium | `[{ nodeId, count, sinceFrameStreamId? }]`. Heat-map overlay. Static snapshot answers "what recomposed during initial composition"; the load-bearing case is **delta after a click** in interactive mode (see § Recomposition + interactive). Needs an instrumented re-render. |
| `compose/theme`            | default | medium | Resolved `MaterialTheme.*` values + which nodes consumed which tokens. |
| `resources/used`           | default | low | `R.*` references resolved during render. Jump-to-source. |
| `text/strings`             | default | low | Text drawn on screen with locale, fontScale, fontSize, foregroundColor, backgroundColor, bounds. **Android daemon implementation:** v1 prefers `GetTextLayoutResult` literal text and unambiguous style values, also emits semantics text/labels so visible text and accessibility text can differ; resource entry names belong to `resources/used`. |
| `i18n/translations`        | default | low | Per-visible-string locale coverage and translations from Android `values*/strings.xml`. **Android daemon implementation.** Desktop returns `DataProductUnknown`. |
| `render/composeAiTrace`    | default/live | low | compose-ai-tools render pipeline trace as Perfetto-importable Chrome trace JSON. Path transport with the same file also listed as the `perfetto` extra. Enabled by `composePreview.previewExtensions.composeAiTrace`. Android daemon launches also require `androidx.compose.runtime:runtime-tracing` on the test runtime classpath so Compose compiler trace hooks can participate when platform tracing is active. |
| `render/trace`             | default | low | Phase breakdown. **Android + desktop daemon implementation:** v1 exposes the latest render as a trace-shaped payload from render metrics; nested `Trace.beginSection` markers are a follow-up. |
| `fonts/used`               | default | low | Font families with weight/style fallback chain. |
| `history/diff/regions`     | default | low | Per-pixel bbox of changed regions vs. another history entry. **D6 — shipped inline payload when daemon history is enabled.** |
| `test/failure`             | failed render | low | Failed-render postmortem bundle. **Android + desktop daemon implementation:** v1 stores the latest failed render's error type, message, top stack frame, bounded stack trace, and explicit fallback fields for data that cannot yet be captured (`partialScreenshotAvailable=false`, redacted snapshot summary, unknown frame-clock/effect state). Fetch-only after `renderFailed`; successful renders clear the stale failure for that preview. |

"Mode" picks which render configuration produces the data; "cost" is
relative — the medium-cost kinds are why we default to opt-in.

### Recomposition + interactive mode

Static `compose/recomposition` (counts during initial composition) is
useful but limited — every node recomposes at least once on the first
frame, so the signal is dominated by "did this node exist." The
question that actually pays off is **"what recomposed in response to
this user action, and is the count higher than it should be?"** That's
the inspector pattern Android Studio uses, and it lines up cleanly
with the reserved interactive-mode surface
([INTERACTIVE.md](INTERACTIVE.md), PROTOCOL.md § "Interactive mode").

The integration:

1. Client opens an interactive session via `interactive/start` and
   gets a `frameStreamId`.
2. Client subscribes to `compose/recomposition` for the same
   `previewId` *and* passes `params: { frameStreamId, mode: "delta" }`.
   Daemon resets per-node counters at subscribe time.
3. On each `interactive/input` (click, keypress), the daemon flushes
   counters at the next stable frame and emits the recomposition
   payload alongside the post-input render. The payload carries
   counts *since the previous input*, with `sinceFrameStreamId` and
   an `inputSeq` matching the input that triggered it.
4. The VS Code overlay paints a heat map keyed to the click —
   "tapping this button caused these 14 nodes to recompose; 3 of
   them more than once." Excess recompositions (count > 1 per
   delta) light up red.

`mode: "snapshot"` (the default) keeps the static behaviour for the
non-interactive case — useful for catching unstable parameters that
trigger excess recomposition during the first frame, even before any
input.

The delta mode does NOT require a separate instrumented sandbox per
input: the instrumentation runs for the lifetime of the interactive
session, and counters reset at flush points rather than at sandbox
boundaries. This keeps interactive-mode latency unchanged when the
client isn't subscribed.

## Worked example: `test/failure`

`test/failure` is fetch-only and exists to make a `renderFailed`
notification actionable. Agents should request it after a failed render
instead of scraping daemon stderr; the payload is the structured
postmortem for the latest failed render of that preview.

```ts
// schemaVersion: 1
{
  status: "failed";
  phase: "unknown" | "composition" | "layout" | "draw" | "capture" | "timeout";
  error: {
    type: string;
    message: string;
    topFrame: string | null;      // e.g. "ExamplePreview.kt:37"
    stackTrace: string[];         // bounded to the first 32 frames
  };
  partialScreenshot: string | null;
  partialScreenshotAvailable: boolean;
  pendingEffects: string[];
  runningAnimations: string[];
  frameClockState: { status: string };
  lastSnapshotSummary: {
    stateObjects: number | null;
    valuesCaptured: boolean;
    redaction: string;
  };
}
```

Schema v1 deliberately does not capture state values. Until renderer-side
partial screenshot and Compose runtime state hooks land, those fields are
present as explicit fallback values so callers can distinguish "not
captured" from "missing due to an older daemon."

## Worked example: `layout/inspector`

`layout/inspector` is for layout structure, not meaning. Use it for
padding, alignment, clipping, parentage, size, and overlap questions.
Use `compose/semantics` for semantic intent and `a11y/hierarchy` for
assistive-technology output.

Android v1 is backed by Compose's `RootForTest` carried on
`PreviewContext.inspection`: the producer starts at
`root.semanticsOwner.unmergedRootSemanticsNode`, resolves the backing
`LayoutNode`, then walks Compose's layout tree. Modifier information is
read from `LayoutInfo.modifierInfo`; when a modifier implements
`InspectableValue`, its inspector name, value override, and named
properties are emitted. Source information is correlated from captured
slot-table `CompositionData` on the same preview inspection context.

```ts
// schemaVersion: 1
{
  root: {
    nodeId: string;              // Compose semantics id when available
    component: string;           // source component or measure-policy fallback
    source?: string;             // e.g. "SettingsScreen.kt:42"
    sourceInfo?: string;         // raw Compose slot-table sourceInfo
    bounds: { left: number; top: number; right: number; bottom: number };
    size: { width: number; height: number };
    constraints?: {
      minWidth: number;
      maxWidth?: number;         // absent means infinity/unbounded
      minHeight: number;
      maxHeight?: number;
    };
    placed: boolean;
    attached: boolean;
    zIndex?: number;
    modifiers: {
      name: string;
      value?: string;
      properties?: Record<string, string>;
      bounds?: { left: number; top: number; right: number; bottom: number };
    }[];
    children: LayoutInspectorNode[];
  }
}
```

## Worked example: `a11y/hierarchy`

The first kind to ship. Shape mirrors the existing renderer-side type:

```ts
// schemaVersion: 1
{
  nodes: {
    label: string;
    role: string | null;
    states: string[];           // 'clickable', 'long-clickable', 'scrollable',
                                // 'editable', 'disabled', 'checked', 'unchecked',
                                // verbatim stateDescription, 'hint: <text>'
    merged: boolean;
    boundsInScreen: string;     // 'left,top,right,bottom' in PNG pixels
  }[];
}
```

Migration impact on the renderer:

- When `a11y/hierarchy` is requested (subscribed or fetched), the
  renderer runs the existing a11y pass at `a11yCaptureIndex()` exactly
  as today, and writes the JSON to the new
  `build/compose-previews/data/<id>/a11y-hierarchy.json` location.
- When the kind is NOT requested, the renderer skips
  `AccessibilityChecker.writePerPreviewReport`'s overlay-baking step
  for daemon-driven renders. The CLI / Gradle path keeps baking the
  overlay since it has no client to draw it locally.
- `a11y/atf` ships in the same release because the data already
  flows together — splitting them later would mean the overlay path
  has to recompute one without the other.

Migration impact on the VS Code extension:

- `previewA11yDiagnostics.ts` keeps its current contract — it consumes
  findings via the `PreviewRegistry`. The registry now sources them
  from the wire field (when subscribed) instead of the on-disk JSON
  via the existing watcher. Same data, different path in.
- The preview panel grows a new opt-in toggle "Show a11y overlay"
  that, when on, calls `data/subscribe('a11y/hierarchy')` for the
  currently focused preview, draws a `<canvas>` over the existing
  `<img>` using the node bounds, and routes hover/click to the
  selected node. Toggling off → `data/unsubscribe`.
- The bundled overlay PNG (the Paparazzi-style annotated screenshot)
  is no longer the primary surface in daemon mode. It still gets
  produced for CLI consumers and stays in `accessibility.json` for
  back-compat — but VS Code stops reading it.

## Module split (D2.2)

Each data product is a **pair of modules** under `data/<product>/`:

- **`:data-<product>-core`** — generic Android / Compose / AndroidX-test API
  code. No daemon coupling. **Published to Maven Central** so consumers can
  pull the primitives from a Robolectric / JUnit setup without standing up the
  daemon. For `a11y` this is `AccessibilityChecker` (ATF wrapper),
  `AccessibilityOverlay` (Paparazzi-style annotated PNG generator), the JSON
  models (`AccessibilityFinding` / `AccessibilityNode` / `AccessibilityEntry`
  / `AccessibilityReport`), and the round-device helpers
  (`isRoundDevice` / `applyCircularClip`). Coordinates:
  `ee.schimke.composeai:data-a11y-core`.

- **`:data-<product>-connector`** — daemon glue. Implements the data-product
  APIs (`DataProductRegistry`, `ImageProcessor`) on top of the core's
  primitives and the daemon's wire types from `:daemon:core`. Not
  published — internal to the daemon process. For `a11y` this is
  `AccessibilityDataProducer` (writes per-render JSON sidecars +
  invokes registered image processors), `AccessibilityDataProductRegistry`
  (advertises `a11y/atf`, `a11y/hierarchy`, `a11y/touchTargets`,
  `a11y/overlay` and serves fetch / attach), and `AccessibilityImageProcessor`
  (drives the overlay PNG into the data-product extras list).

Why split: the core is reusable in non-daemon contexts (`:gradle-plugin`,
`:cli`, third-party Robolectric tests) and benefits from a stable Maven
coordinate. The connector is a thin adapter and ships with the daemon AAR.
The two also have different dependency profiles: the core only needs ATF +
AndroidX, while the connector adds `:daemon:core` (protocol types) and
exposes `ImageProcessor` to `RenderEngine`.

The same split now applies to the built-in daemon products with small,
product-named modules:

- `data/fonts/{core,connector}` for `fonts/used`.
- `data/render/{core,connector}` for `render/trace`,
  `render/composeAiTrace`, and `render/deviceClip`.
- `data/history/connector` for `history/diff/regions`.
- `data/layoutinspector/{core,connector}` for `compose/semantics`.
- `data/resources/{core,connector}` for `resources/used`.
- `data/strings/{core,connector}` for `text/strings` and `i18n/translations`.
- `data/theme/connector` for `compose/theme`.
- `data/recomposition/connector` for `compose/recomposition`.

When a product has reusable producer/model code that does not need daemon
protocol types, that code belongs in a `core` module. Connector modules are
kept as daemon adapters and may depend on `:daemon:core`.

The framework-level `ImageProcessor` interface lives in `:daemon:core` so
every connector can implement it without circular module dependencies; the
generic `ImageProcessorInput.context: Any?` field carries connector-specific
typed payloads (e.g. `AccessibilityImageContext` for the a11y connector)
that the matching processor downcasts at runtime.

## Built-in render metadata products

`render/deviceClip` is a cheap, inline, fetchable and attachable data product
derived from `PreviewContext.device`. The registry seeds that context from the
daemon's `PreviewIndex`, so clients can fetch it before the first render; after
a render completes, the actual render context replaces the seed so overrides and
backend-resolved dimensions win. Its payload is:

```json
{ "clip": null }
```

for rectangular/default devices, or:

```json
{
  "clip": {
    "shape": "circle",
    "centerXDp": 113.5,
    "centerYDp": 113.5,
    "radiusDp": 113.5
  }
}
```

for round Wear-style devices. It does not require a render pass and is
available on both Android and desktop daemons.

`render/scroll/long` and `render/scroll/gif` are annotation-sourced render
products produced from `@ScrollingPreview(modes = [LONG, GIF])`. The typed
annotation remains the authoring API; discovery maps those modes to data-product
requests instead of adding long/GIF artefacts to the primary capture list. Gradle
renders write path-backed product files under `build/compose-previews/data/`:

```json
{
  "kind": "render/scroll/long",
  "schemaVersion": 1,
  "path": ".../build/compose-previews/data/render-scroll-long/Foo.png"
}
```

`render/scroll/long` is a stitched PNG. `render/scroll/gif` is an animated GIF
showing the scroll from top to bottom. Non-scrollable previews no-op through the
same fallback behaviour as the renderer's scroll capture path.

`history/diff/regions` is a cheap, inline, fetchable and attachable data product
derived from the daemon history archive. It is advertised only when
`HistoryManager` is active. Fetch and subscribe calls must pass an explicit
baseline:

```json
{
  "baselineHistoryId": "20260502-100000-1234abcd",
  "thresholdAlphaDelta": 4
}
```

The producer reads the latest history entry for the requested `previewId`,
compares its PNG against the baseline entry, and returns connected changed-pixel
regions capped to the largest 50:

```json
{
  "baselineHistoryId": "20260502-100000-1234abcd",
  "totalPixelsChanged": 42,
  "changedFraction": 0.0012,
  "regions": [
    {
      "bounds": "10,20,40,32",
      "pixelCount": 360,
      "avgDelta": { "r": 12.0, "g": -4.0, "b": 0.0, "a": 0.0 }
    }
  ]
}
```

## Phase plan

- **D1 — primitive on the wire.** `initialize.capabilities.dataProducts`,
  `initialize.options.attachDataProducts`, `data/fetch`, `data/subscribe`,
  `data/unsubscribe`, additive `renderFinished.dataProducts`. Wire types
  in TS + Kotlin, fixtures, JSON-RPC dispatcher entries that error
  `DataProductUnknown` for everything (no producers yet). Tests
  round-trip the new shapes and assert unknown-kind paths.
- **D2 — `a11y/hierarchy` + `a11y/atf` end-to-end.** Renderer-side
  producer wraps existing `AccessibilityChecker` plumbing; daemon-side
  surfacing reads from the new on-disk location; client-side overlay
  draws the hierarchy locally; the existing diagnostic squigglies move
  off the file watcher onto the wire field.
- **D3 — `data/fetch` re-render on demand.** Implements the budgeted
  re-render path for kinds the latest pass didn't compute. First
  consumer: agent-driven MCP calls that ask for `a11y/hierarchy`
  without first subscribing.
- **D4 — `layout/inspector`.** Android daemon ships a Compose-rooted
  layout inspector product backed by `RootForTest`, `LayoutNode`, slot-table
  source information, and inspectable modifier values. Desktop parity is a
  follow-up once its render surface exposes an equivalent root tree.
- **D5+** — pick from the catalogue based on demand. `compose/recomposition`
  in `mode: "delta"` is the highest-value next step for VS Code parity
  with Android Studio, but it's gated on interactive mode landing so the
  click-driven path has a `frameStreamId` to bind to. Static `mode:
  "snapshot"` recomposition can ship earlier as a stepping stone.
- **D2.2 — module split.** Per-product `:data-<product>-core` (published) +
  `:data-<product>-connector` (daemon-only) pair. Lifts the a11y wrapper
  and overlay generator into a standalone Maven artifact so non-daemon
  consumers (Gradle plugin, CLI, third-party Robolectric tests) can pull
  the primitives without the daemon dependency tree. See § "Module split
  (D2.2)" above for the rationale and pattern.

## Test coverage

- Round-trip fixtures under `docs/daemon/protocol-fixtures/`:
  `client-dataFetch.json`, `daemon-dataFetchResult.json`,
  `client-dataSubscribe.json`, plus an updated
  `daemon-renderFinished.json` carrying a populated `dataProducts`.
  Both Kotlin and TypeScript test suites consume the same files.
- Kotlin unit tests under `daemon/core/src/test/kotlin/` cover:
  unknown-kind rejection, subscribe/unsubscribe idempotency,
  attach-on-renderFinished correctness for subscribed pairs only,
  re-render-budget enforcement.
- TypeScript unit tests under `vscode-extension/src/daemon/` cover:
  client-side subscription bookkeeping (re-subscribe on visibility
  return, drop on unsubscribe), payload routing into the registry
  for each shipped kind.
- Renderer-side functional tests exercise the producer for each
  shipped kind against the synthetic preview corpus; assert byte-stable
  JSON output so the schema doesn't drift silently.
