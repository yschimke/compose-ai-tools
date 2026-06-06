# Preview data products

Per-render structured data the renderer can produce alongside the PNG
that a client (VS Code, MCP, the CLI) wants to render in its own UI.
Examples: ATF a11y findings, layout-inspector hierarchy, recomposition
heat maps, resource jump-to-source, theme tokens.

Wire surface is locked at PROTOCOL v2. As of v2, kinds are gated by the
extension activation system (PROTOCOL.md § 3a): a daemon registers every
data-product kind via an [`Extension`][ext], but advertises and serves
only those whose owning extension is **publicly enabled** by the client
via `extensions/enable`. The wire shapes below are unchanged; what
changed is which kinds appear in `initialize.capabilities.dataProducts`
at handshake time and which kinds round-trip through `data/fetch` and
`data/subscribe`.

[ext]: ../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/Extension.kt

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

### Derived images and extras

A kind's primary payload is JSON or a JSON-shaped path. Some producers
also ship **derived images** alongside — the Paparazzi-style a11y
overlay PNG is the load-bearing example. Two seams:

- **`extras`** — additive on `DataProductAttachment` and
  `DataFetchResult`. List of `{name, path, mediaType?, sizeBytes?}`
  pointing at sibling files. Pointer-only (no inlining).
- **`PostCaptureProcessor`** extensions (`:data-render-extensions`) —
  typed post-render hooks the renderer plans and runs after the PNG is
  captured. Each extension reads/writes a typed `DataProductStore`; the
  data-product registry then surfaces the resulting files as `extras`
  on subscribed kinds (the a11y overlay rides under `a11y/atf`,
  `a11y/hierarchy`, AND the dedicated `a11y/overlay` kind).

The a11y overlay is produced by `OverlayExtension` inside
`runAccessibilityPostCapturePipeline`. Output lands at
`<dataDir>/<previewId>/a11y-overlay.png`.

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
knob. Reserved for genuinely cheap kinds (today: `a11y/atf` only). The
set is filtered at initialize time against the **publicly enabled**
capability set (see § Activation below); calling `extensions/enable`
afterwards does not retroactively widen the global attach configuration.

`initialize.capabilities.dataProducts` lists only kinds whose owning
extension is publicly enabled. Until the client has called
`extensions/enable`, this list is empty — UIs should treat that the same
as "no kinds advertised" rather than as a fatal handshake.

### Activation (extensions gate)

Every data-product kind is owned by a single [`Extension`][ext]
registered at daemon startup. Extensions are inactive by default;
clients call `extensions/enable {ids}` (PROTOCOL.md § 3a) to opt in.
Three states:

- **inactive** — the kind does not appear in `initialize.capabilities.dataProducts`,
  `data/fetch` returns `DataProductUnknown`, `data/subscribe` is rejected,
  the producer's `onRender` does not run, and `renderFinished` carries no
  attachment for the kind.
- **active as dependency** — the producer's `onRender` runs in-process so
  the depending extension can read its derived state. Client-visible
  surfaces still treat the kind as inactive: it does not appear in
  capability lists, `data/fetch` returns `DataProductUnknown`, attachments
  skip it. This matches the rule "dependencies don't contribute to
  responses directly, just via the extension that depends on them."
- **publicly enabled** — full surface: capability advertisement, fetch,
  subscribe, attachments, render-time hooks all wired.

The MCP supervisor enables a configured allowlist on every spawned daemon
(`DaemonSupervisor.defaultExtensions`). The VS Code extension currently
enables every advertised extension on connect as a transitional step;
lazy per-card enable/disable is the long-term direction.

[ext]: ../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/Extension.kt

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

`data/fetch` only routes to **publicly enabled** extensions. A kind whose
owning extension is inactive — including dep-only-active extensions —
returns `DataProductUnknown` (-32020), even if a producer is registered.
Clients that need to fetch a kind must enable the owning extension first.

### `data/subscribe` / `data/unsubscribe`

```ts
params: { previewId: string; kind: string }
result: { ok: true }
```

While subscribed, every `renderFinished` for `previewId` carries a
`dataProducts` entry for `kind`. Subscriptions are per-(previewId,
kind), idempotent, and drop automatically when `previewId` leaves the
most recent `setVisible` set. Reset across daemon restarts.

Subscribing to a kind whose owning extension is inactive is rejected
with `DataProductUnknown`. Calling `extensions/disable` on an extension
with live subscriptions tears them down — the producer's `onUnsubscribe`
fires through the active path so per-subscription state is cleared.

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
| -32020 | DataProductUnknown         | Kind not advertised by daemon, or its owning extension is not publicly enabled. |
| -32021 | DataProductNotAvailable    | Preview has never rendered; render first. |
| -32022 | DataProductFetchFailed     | Re-render or projection failed; details in `data`. |
| -32023 | DataProductBudgetExceeded  | Re-render budget tripped before payload landed. |

`error.data.kind: string` for machine-routable subcategories.

## Re-render semantics

A `data/fetch` that needs a re-render:

1. Picks the smallest render mode that produces the kind. `a11y/*`
   wants "a11y mode" (`renderMode = "a11y"` on the `RenderSpec`);
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
| `compose/semantics` | default | low | SemanticsNode projection — testTag, role, mergeMode, bounds. Each node also carries a stable, content-independent `ref` (assigned by `SemanticsRefs`) used for ref/testTag/role targeting (#1784) and as the match key for the semantics text diff (#1785). Adding `ref` is additive — `schemaVersion` stays 2. |
| `compose/semantics-wireframe` | default | low | Standalone 2D wireframe of the semantics tree, derived from the same captured root. SVG primary (path); baked PNG rides as a `png` extra. Depth-cycled stroke hue, accent fill/stroke for clickable stops, dashed stroke for `clearAndSet`, top-left labels. |
| `compose/spatial-semantics` | default | low | Unified **3D-over-2D** semantics tree (`SpatialSemanticsTree`): the subspace layout with each panel carrying its 2D `compose/semantics` tree. Ordinary previews emit the degenerate single-panel case (one `panel` at identity pose); XR previews emit the real multi-panel layout. The accessibility/structure view of [SPATIAL_SEMANTICS_TREE.md](../design/SPATIAL_SEMANTICS_TREE.md) / [XR_A11Y.md](../design/xr-spatial/XR_A11Y.md). |
| `compose/recomposition` | instrumented | medium | schemaVersion 2: `[{nodeId, count, reason, bounds?, sourceFile?…}]` + `sinceFrameStreamId`/`inputSeq`. `reason` (PARAMETER_CHANGE/STATE_READ/BOTH/UNKNOWN) attributes each scope; `bounds`/source markers nullable until their joins land (#1605). Heat map. Snapshot or click-delta. |
| `compose/theme` | default | medium | Resolved `MaterialTheme.*` values + which nodes consumed them. |
| `compose/permissions` | default | low | Android runtime-permissions surface: `{ grants: { "android.permission.X" -> "granted" \| "denied" }, queried: ["android.permission.X", …] }`. Around-composable seeds `ShadowApplication.grantPermissions/denyPermissions` from `renderNow.overrides.permissions.grants` so `ContextCompat.checkSelfPermission(...)` / `Activity.checkSelfPermission(...)` return the requested value through the standard platform path; the matching shadow on `ContextWrapper.checkPermission(...)` records the queried permission for the panel's "queried but no grant pinned" surface. Android-only. |
| `resources/used` | default | low | `R.*` references resolved during render. |
| `text/strings` | default | low | Drawn text with locale, fontScale, fontSize, colors, bounds, plus per-entry `truncated` / `overflow` / `lineCount` / `maxLines` / `didOverflowWidth/Height` from the Compose `TextLayoutResult`. |
| `i18n/translations` | default | low | Per-string locale coverage from `values*/strings.xml`. Android only. |
| _(pseudolocale, no kind)_ | default | low | Triggered by `localeTag` in `{en-XA, ar-XB}` on a `@Preview` or `renderNow.overrides`. Visual-only — Android wraps `LocalContext.resources` to pseudolocalise `getString*` returns and (for `ar-XB`) provides `LayoutDirection.Rtl`; CMP Desktop provides `LayoutDirection.Rtl` only (string-resource interception not viable through `compose.components.resources`). No build-time `pseudoLocalesEnabled` / `resConfigs` required. Modules: `:data-pseudolocale-core`, `:data-pseudolocale-connector` (Android), `:data-pseudolocale-connector-desktop` (CMP Desktop). |
| `render/composeAiTrace` | default/live | low | Render pipeline trace as Perfetto-importable Chrome trace JSON. |
| `render/trace` | default | low | Phase breakdown from render metrics. |
| `fonts/used` | default | low | Font families with weight/style fallback chain. |
| `history/diff/regions` | default | low | Per-pixel bbox of changed regions vs. another history entry. |
| `test/failure` | failed render | low | Postmortem bundle: phase, error type/message/stack, fallback fields for what's not yet captured. Fetch-only after `renderFailed`. |

## Per-backend support matrix (issue #1201)

Which `kind` strings each backend's `extensions/list` advertises and serves through `data/fetch` / `data/subscribe`. The first column is the wire-level `kind` the client passes — **not** the daemon-side `Extension.id` (those differ: e.g. the extension registered as `data/theme` exposes the kind `compose/theme`). Read this together with `initialize.capabilities.dataProducts`: the daemon never lies about what it can advertise, so a kind that shows up in the capability list will round-trip through `data/fetch` (possibly returning `NotAvailable` when no producer has written yet, but **never** `-32020 DataProductUnknown`).

| `kind` | Android | Desktop (CMP) | Notes |
|---|---|---|---|
| `render/deviceClip` / `render/deviceBackground` | ✅ | ✅ | Renderer-agnostic device-bound previewer. |
| `render/trace` | ✅ | ✅ | Phase breakdown from render metrics. |
| `render/composeAiTrace` | ✅ | ✅ | Both backends, gated on `composeai.perfetto.enabled` + `composeai.render.outputDir`. |
| `test/failure` | ✅ | ✅ | Postmortem bundle on `renderFailed`. Renderer-agnostic. |
| `compose/theme` | ✅ | ✅ | Material 3 theme tokens. Override-extension shape. |
| `compose/wallpaper` | ✅ | ✅ | Wallpaper override shape. |
| `compose/permissions` | ✅ | ❌ Android-only | Runtime-permissions surface — Robolectric `ShadowApplication` grant seed + `ContextWrapper.checkPermission` query tracker. Desktop has no Android permission model; CMP panel chip greys out on `serverCapabilities.backend == "desktop"`. |
| `compose/recomposition` | ✅ producer | ✅ producer | Compose-runtime observer install on both backends (desktop in-process; Android via the in-sandbox bridge). schemaVersion 2 adds the per-scope `reason` (#1605), derived symmetrically from `onScopeInvalidated`. |
| `displayfilter/variants` | ✅ | ✅ | Both backends, gated on `composeai.displayfilter.filters`. Producer is pure `BufferedImage` post-capture. |
| `fonts/used` | ✅ producer | 📁 registry-only | Android: `GoogleFontInterceptor` + Typeface accounting. Desktop: registry returns `NotAvailable` until a Skia-side font producer ports. |
| `compose/semantics` / `layout/inspector` | ✅ producer | 📁 registry-only | Android producer reads the Robolectric semantics tree. Desktop registry serves the file once a CMP-portable producer driving `LocalView` / `SemanticsOwner` lands. |
| `compose/semantics-wireframe` | ✅ producer | ✅ producer | Android via the always-on post-capture extension; desktop via the `RenderEngine` block that reads `ImageComposeScene.semanticsOwners`' unmerged root (the CMP-portable `SemanticsOwner` read the `compose/semantics` row is still waiting on). SVG is backend-agnostic; the PNG is baked by `AndroidSemanticsWireframe` / `DesktopSemanticsWireframe`. |
| `compose/spatial-semantics` | ✅ producer | ✅ producer | Degenerate single-panel tree from the same captured root, alongside the wireframe (Android post-capture extension / desktop `RenderEngine` block). The real multi-panel XR tree is written separately by the `:renderer-xr` batch render task (`SubspaceSceneRecorder.recordTree`). Both write `compose-spatial-semantics.json`. |
| `text/strings` | ✅ producer | 📁 registry-only | Synthesised from `compose/semantics`; ports along with that kind. |
| `i18n/translations` | ✅ producer | 📁 registry-only | Reads `values*/strings.xml`. Desktop equivalent reads `org.jetbrains.compose.resources.ResourceEnvironment` once that producer ports. |
| `data/navigation` | ✅ producer | 📁 registry-only | Android producer reads `Activity.intent` + `OnBackPressedDispatcher`. Desktop equivalent watches `NavController`; same registry file shape. |
| `history/diff/regions` + history JSON-RPC | 🔒 1.1 | 🔒 1.1 | Both backends gated behind `HistoryFeature.ENABLED` (post-1.0). See `daemon/core/.../HistoryFeature.kt`. |
| `a11y/atf` / `a11y/hierarchy` / `a11y/touchTargets` / `a11y/overlay` | ✅ | ❌ Android-only | Producer depends on ATF + Robolectric `AccessibilityNodeInfo` walks. CMP-portable subset (geometric touch-target + contrast against `SemanticsNode`) is a future track; not blocked on registry. |
| `resources/used` | ✅ | ❌ Android-only | Producer intercepts `Resources.getValue`; no Compose-Multiplatform analogue ships. |
| `uia/hierarchy` | ✅ | ❌ Android-only | UIAutomator is Android-API surface; CMP-desktop panel chips should grey out on `serverCapabilities.backend == "desktop"`. |
| `compose/ambient` | ✅ Wear-only | ❌ | Robolectric shadow of `AmbientLifecycleObserver` — Wear OS only. |

Legend: ✅ producer = backend writes the artefact each render. 📁 registry-only = backend advertises the kind and serves `data/fetch` against the file when present; producer has not ported yet, so the kind returns `NotAvailable` until either a port lands or an Android render writes into the same `dataRoot` (which only happens if Android and Desktop daemons share an output dir — an unusual deployment). ❌ = not advertised; panel should grey out on `serverCapabilities.backend == "desktop"`. 🔒 = gated behind a feature flag.

> The CMP-desktop panel additionally surfaces several override extensions that don't ship a `kind` and therefore aren't `data/fetch`-able: `data/pseudolocale` (locale override + `LayoutDirection.Rtl`) and `render/overlay-legend` (preview overlay only). They appear in `extensions/list` but their `dataProducts` capability set is empty by design.



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
  `DataProductRegistry` on top of the core primitives and
  `:daemon:core` wire types. Not published — internal to the daemon
  process.

Why split: cores are reusable in non-daemon contexts (`:gradle-plugin`,
`:cli`, third-party Robolectric tests, MCP clients in any language that
pull just the schema artifact). Connectors are thin adapters that
depend on `:daemon:core`.

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
| `compose/semantics` | `:data-layoutinspector-core` | `ComposeSemanticsPayload`, `ComposeSemanticsNode` (carries stable `ref`) |
| `compose/spatial-semantics` | `:preview-data-api` | `SpatialSemanticsTree`, `SpatialSemanticsNode`, `Size3dDp` (panels carry `ComposeSemanticsNode`) |
| _(semantics diff, derived)_ | `:data-layoutinspector-core` | `SemanticsDelta`, `SemanticsNodeChange`, `SemanticsFieldChange` (schema `compose-semantics-diff/v1`) |
| `compose/theme` | `:data-theme-core` | `ThemePayload`, `ResolvedThemeTokens`, `TypographyToken` |
| `compose/wallpaper` | `:data-wallpaper-core` | `WallpaperPayload` |
| `compose/permissions` | `:data-permissions-core` | `PermissionsPayload`, `PermissionGrantWire` |
| `fonts/used` | `:data-fonts-core` | `FontsUsedPayload`, `FontUsedEntry` |
| `history/diff/regions` | `:data-history-core` | `HistoryDiffPayload`, `HistoryDiffRegion` |
| `i18n/translations` | `:data-strings-core` | `I18nTranslationsPayload`, `I18nVisibleString` |
| `layout/inspector` | `:data-layoutinspector-core` | `LayoutInspectorPayload`, `LayoutInspectorNode` |
| _(pseudolocale, no payload)_ | `:data-pseudolocale-core` | `Pseudolocale`, `Pseudolocalizer` |
| `resources/used` | `:data-resources-core` | `ResourcesUsedPayload`, `ResourceUsedReference` |
| `text/strings` | `:data-strings-core` | `TextStringsPayload`, `TextStringEntry` |

Each `core` module advertises its kind identity and schemaVersion via a
`<Feature>Product` object (`HistoryDiffRegionsProduct.KIND`,
`Material3ThemeProduct.SCHEMA_VERSION`, etc.). Connectors and consumers
both refer to those constants — never inline the string literals.

### `data/scroll` is daemon-produced via `data/fetch` (issue #1528)

`data/scroll/core` carries the scroll-scenario primitives — `ScrollDriver`,
`ScrollGifEncoder`, the LONG / GIF frame-driver extensions — that the renderer
composes when `@ScrollingPreview(modes = [LONG | GIF])` runs. `data/scroll/connector`
wires those primitives into the daemon's data-product surface: it advertises
`render/scroll/long` (PNG) and `render/scroll/gif` (GIF) on
`initialize.capabilities.dataProducts` as `requiresRerender = true` producers, so
a missing scroll artefact returns `Outcome.RequiresRerender("scroll-long" |
"scroll-gif")` and `JsonRpcServer.handleDataFetchWithRerender` queues a
per-preview re-render in the right scenario via
`RenderEngine.runScrollScenario`. The engine resolves the annotation's intent
(axis, maxScrollPx, frameIntervalMs) from `PreviewIndex.scrollCaptureFor` —
populated from the gradle plugin's `dataProducts[].scroll` field in
`previews.json` — and delegates the heavy lifting to the renderer's public
`renderer.handleLongCapture` / `renderer.handleGifCapture` entry points.

**On-disk layout is intentionally identical to the Gradle path.** The daemon
writes to `<modulePreviewsDir>/data/render-scroll-long/<previewId>.png` and
`<modulePreviewsDir>/data/render-scroll-gif/<previewId>.gif` — the same files
`composePreviewRenderAll` writes — so `gradleService.readPreviewImage` and
`data/fetch` resolve to the exact same path regardless of which side produced
it. Binary artefacts (PNG / animated GIF) override `allowInlineUpgrade` to
`false` so an `inline = true` fetch still returns the path (matching the
a11y overlay PNG's existing behaviour).

| Kind | Transport | Source | Notes |
| --- | --- | --- | --- |
| `render/scroll/long` | `PATH` | `data/scroll/connector` + `RenderEngine.runScrollScenario` | One stitched PNG per `@ScrollingPreview(modes = [LONG])`. `requiresRerender = true`. |
| `render/scroll/gif` | `PATH` | same as above | One animated GIF per `@ScrollingPreview(modes = [GIF])`. `requiresRerender = true`. |

**Host backfill.** The VS Code extension's viewport-driven backfill
(`triggerViewportScrollBackfill` in `extension.ts`) calls
`daemonScheduler.fetchScrollDataProduct` per-`(module, previewId, kind)` against
the daemon; on daemon-side failure it falls back to the historical
`composePreviewRender('full')` Gradle round-trip so older daemons that don't
advertise the kinds still work. The fallback gate lives at the
fetch-result boundary rather than `initialize.capabilities.dataProducts` so an
in-flight rollout doesn't need a daemon version bump in the host.
