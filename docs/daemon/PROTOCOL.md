# Preview daemon — IPC protocol

> **Status:** v1 contract. Locked as of this document; further changes require a PR that updates both the daemon (Kotlin) and the VS Code client (TypeScript) in lockstep, plus a `protocolVersion` bump.

This document is the authoritative wire-format spec for the JSON-RPC channel between the VS Code extension and the per-module preview daemon. It is referenced by [DESIGN.md § 5](DESIGN.md) and is the contract that Stream B (daemon core) and Stream C (VS Code client) implement against in parallel — see [TODO.md](TODO.md).

## 1. Transport

- **Channel:** the daemon's stdin / stdout, line-flushed and binary-mode (no text translation).
- **Framing:** LSP-style `Content-Length` headers.

  ```
  Content-Length: 137\r\n
  Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n
  \r\n
  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
  ```

  `Content-Type` is optional and ignored; `Content-Length` is mandatory and counts UTF-8 bytes of the JSON payload.

- **Encoding:** UTF-8 only. No BOM.
- **Daemon stderr** is a free-form log stream (level-prefixed lines), not part of the protocol. VS Code surfaces stderr to the daemon output channel for debugging only.
- **No multiplexing.** One daemon process, one stdio pair, one logical channel. Multi-module support is multi-process.

## 2. Base protocol

JSON-RPC 2.0 (https://www.jsonrpc.org/specification) with the LSP framing above.

Three message kinds:

- **Request** — has `id`, expects a matching response.
- **Response** — `result` xor `error`, matched to a request by `id`.
- **Notification** — no `id`, no response.

`id` is a positive integer chosen by the sender; each side maintains its own monotonically increasing counter starting at 1.

### Error codes

Standard JSON-RPC codes plus daemon-specific extensions in the reserved `-32000..-32099` range:

| Code     | Name                  | Meaning |
|----------|-----------------------|---------|
| -32700   | ParseError            | Invalid JSON received. |
| -32600   | InvalidRequest        | JSON is not a valid Request object. |
| -32601   | MethodNotFound        | Method name unknown to receiver. |
| -32602   | InvalidParams         | Method exists but params are wrong shape. |
| -32603   | InternalError         | Receiver's fault, not the caller's. |
| -32001   | NotInitialized        | Request arrived before `initialize` completed. |
| -32002   | ClasspathDirty        | Daemon refuses work; restart required. |
| -32003   | SandboxRecycling      | Daemon is between sandboxes; retry shortly. |
| -32004   | UnknownPreview        | Preview ID not in the current discovery set. |
| -32005   | RenderFailed          | Render itself failed; details in `data`. |
| -32010   | HistoryEntryNotFound  | `history/read` or `history/diff` referenced a missing entry id. |
| -32011   | HistoryDiffMismatch   | `history/diff` was given two entries from different previews. |
| -32012   | HistoryPixelNotImplemented | `history/diff` was called with `mode = "pixel"`; reserved for phase H5. |
| -32020   | DataProductUnknown    | `data/*` referenced a kind not advertised by the daemon. See [DATA-PRODUCTS.md](DATA-PRODUCTS.md). |
| -32021   | DataProductNotAvailable | `data/fetch` against a preview that has never rendered. |
| -32022   | DataProductFetchFailed  | `data/fetch` re-render or projection failed; details in `data`. |
| -32023   | DataProductBudgetExceeded | `data/fetch` re-render budget tripped before the payload landed. |

`error.data` is an object; daemon-specific errors include `data.kind: string` for machine-routable subcategories.

## 3. Lifecycle

```
VS Code spawns daemon
        │
        ▼
   ──► initialize (request) ──►
   ◄── initialize (response) ◄──   capabilities exchange
        │
   ──► initialized (notification) ──►   protocol open for use
        │
        │   normal traffic
        │
   ──► shutdown (request) ──►
   ◄── shutdown (response) ◄──     daemon stops accepting new work
        │
   ──► exit (notification) ──►     daemon process exits
```

`shutdown` is a request so the client can wait for in-flight renders to drain. `exit` is fire-and-forget; daemon exits with code 0 if `shutdown` preceded it, code 1 otherwise.

If the client closes stdin without `shutdown`+`exit`, the daemon exits with code 1 within `daemon.idleTimeoutMs` (default 5000ms).

### `initialize` (request, client → daemon)

Params:

```ts
{
  protocolVersion: number;           // currently 1
  clientVersion: string;             // e.g. extension semver "0.8.6"
  workspaceRoot: string;             // absolute path
  moduleId: string;                  // Gradle path, e.g. ":samples:android"
  moduleProjectDir: string;          // absolute path to the module
  capabilities: {
    visibility: boolean;             // client will send setVisible/setFocus
    metrics: boolean;                // client wants per-render metrics in renderFinished
  };
  options?: {
    maxHeapMb?: number;              // overrides daemon.maxHeapMb
    warmSpare?: boolean;
    detectLeaks?: "off" | "light" | "heavy";
    foreground?: boolean;            // true when launched via `--foreground`
    maxRenderMs?: number;            // per-render host.submit timeout. Default 5*60_000;
                                     // values ≤ 0 are ignored.
  };
}
```

Result:

```ts
{
  protocolVersion: number;           // daemon's understanding; must equal client's or daemon errors out
  daemonVersion: string;             // semver
  pid: number;
  capabilities: {
    incrementalDiscovery: boolean;   // false in v1.0; true once Tier-2 lands
    sandboxRecycle: boolean;
    leakDetection: ("light" | "heavy")[];   // reserved; always [] today (TODO B2.4)
    knownDevices?: KnownDevice[];    // catalog of @Preview(device=...) ids the daemon recognises
    supportedOverrides?: string[];   // PreviewOverrides field names this host actually applies
    backend?: "desktop" | "android"; // which renderer backend this daemon implements
    androidSdk?: number | null;      // fixed Robolectric SDK, absent/null on non-Android backends
  };
  // KnownDevice — one entry per id in DeviceDimensions.KNOWN_DEVICE_IDS, projected to wire shape.
  // `id` is the string a caller passes via renderNow.overrides.device; widthDp/heightDp/density
  // let a UI label the device ("Pixel 5 — 393×851 dp @ 2.75x") without re-resolving. isRound marks
  // circular Wear-style displays.
  // Empty list = pre-feature daemon; treat absent and `[]` identically.
  // The `spec:width=…,height=…,dpi=…` grammar is not enumerable — clients pass it as a
  // free-form `device` override and the daemon parses it at resolve-time.
  //
  // supportedOverrides — the `PreviewOverrides` field names this daemon's host actually applies
  // (subset of {"widthPx","heightPx","density","localeTag","fontScale","uiMode","orientation",
  // "device"}). Lets clients grey out unsupported sliders. Empty list = pre-feature daemon;
  // treat absent and `[]` identically and assume any field might be ignored.
  // Today: Robolectric advertises all eight; Desktop omits "localeTag" (no `LocalLocale`
  // CompositionLocal + `Locale.setDefault(...)` is JVM-thread-unsafe) and "orientation"
  // (no rotation concept on `ImageComposeScene`).
  //
  // androidSdk — fixed Android SDK level the backend renders against. Populated by the
  // Robolectric backend from its pinned @Config(sdk = ...) value; absent/null on Desktop and
  // other non-Android backends.
  classpathFingerprint: string;      // SHA-256 hex of the resolved test classpath
  manifest: {
    path: string;                    // absolute path to the daemon's working previews.json
    previewCount: number;
  };
}
```

Mismatched `protocolVersion` → daemon responds with `InvalidRequest` and exits.

### `initialized` (notification, client → daemon)

No params. Signals the client has processed the `initialize` response and is ready to receive notifications. Daemon must not send notifications before this.

### `shutdown` (request, client → daemon)

No params. Result is `null`. Daemon stops accepting `renderNow`, drains the in-flight queue, then resolves.

### `exit` (notification, client → daemon)

No params. Daemon exits.

## 4. Client → daemon notifications

### `setVisible`

```ts
{ ids: string[] }
```

Currently visible preview cards in the panel. Replaces the prior visible set (not a delta). Daemon uses this to filter Tier-3 stale sets.

### `setFocus`

```ts
{ ids: string[] }
```

Active selection (click, hover, file scope change). Subset of the most recent `setVisible`. Daemon renders these first when the queue drains.

### `fileChanged`

```ts
{
  path: string;                      // absolute
  kind: "source" | "resource" | "classpath";
  changeType: "modified" | "created" | "deleted";
}
```

The client is the source of truth for file events (the extension already runs the watcher per [extension.ts](../../vscode-extension/src/extension.ts)). The daemon does **not** run its own watcher in v1.

`kind` is a hint, not authoritative — the daemon still classifies internally:

- `source` — `*.kt` or `*.java` under the module's source set.
- `resource` — anything under `src/**/res/**`.
- `classpath` — `libs.versions.toml`, `*.gradle.kts`, `gradle.properties`, `local.properties`, or `settings.gradle.kts`.

A `classpath` event triggers Tier-1 fingerprint recomputation; on mismatch the daemon emits `classpathDirty` (§ 5) and refuses further `renderNow` requests.

## 5. Client → daemon requests

### `renderNow`

```ts
// params
{
  previews: string[];                // preview IDs; empty = render all visible-and-stale
  tier: "fast" | "full";             // "fast" = single best-effort frame; "full" = full advanceTimeBy loop
  reason?: string;                   // free-form, surfaces in logs (e.g. "user clicked refresh")
  overrides?: PreviewOverrides;      // optional per-call display-property overrides
}

// PreviewOverrides — every field optional; null falls back to the discovery-time RenderSpec.
{
  widthPx?: number;
  heightPx?: number;
  density?: number;                  // 1.0 = mdpi/160dpi, 2.0 = xhdpi/320dpi
  localeTag?: string;                // BCP-47 (e.g. "en-US", "fr", "ja-JP"). Android-only today.
  fontScale?: number;                // 1.0 = system default
  uiMode?: "light" | "dark";         // Android-only today.
  orientation?: "portrait" | "landscape";  // Android-only today.
  device?: string;                   // "id:pixel_5", "id:wearos_small_round", "spec:width=400dp,height=800dp,dpi=320".
                                     // Resolved by the daemon's catalog into widthPx/heightPx/density;
                                     // explicit widthPx/heightPx/density above take precedence.
  captureAdvanceMs?: number;         // Paused-clock advance (ms) before capture. Android-only;
                                     // default ≈ 32ms. Bump for animation-heavy previews.
}

// result
{
  queued: string[];                  // IDs accepted into the render queue
  rejected: { id: string; reason: string }[];   // unknown preview, coalesced, etc.
}
```

The result resolves as soon as the request is queued, **not** when rendering completes. Per-render progress arrives as `renderStarted` / `renderFinished` / `renderFailed` notifications keyed by ID.

`overrides` are merged onto the discovery-time `RenderSpec` per-call. A subsequent `renderNow` for the same preview **without** `overrides` reverts to the discovery-time defaults — overrides are not sticky across calls. Backends that don't model a particular field ignore it (e.g. desktop has no Android resource qualifier system, so `uiMode` / `localeTag` / `orientation` are no-ops on the desktop render path today).

**Coalescing.** When `overrides` is non-null and a prior override-bearing render is still in-flight for the same `previewId`, the new request is rejected with `reason = "coalesced: …"` rather than queued. The client (panel, MCP, etc.) is responsible for resubmitting on the next `renderFinished` if the latest override values still differ from what was rendered. Plain (no-overrides) `renderNow` is unaffected — the existing save-debounce loop continues to coalesce upstream.

Errors:
- `ClasspathDirty` (-32002) — daemon will not render until restarted.
- `SandboxRecycling` (-32003) — retry after the next `daemonReady` notification.

### `history/list` (phase H2)

```ts
// params
{
  previewId?: string;
  since?: string;                   // ISO timestamp lower bound
  until?: string;                   // ISO timestamp upper bound
  limit?: number;                   // default 50, max 500
  cursor?: string;                  // opaque pagination token
  branch?: string;
  branchPattern?: string;           // regex
  commit?: string;                  // long or short SHA
  worktreePath?: string;
  agentId?: string;
  sourceKind?: "fs" | "git" | "http";
  sourceId?: string;
}

// result
{
  entries: HistoryEntry[];          // newest first
  nextCursor?: string;
  totalCount: number;
}
```

`HistoryEntry` is the sidecar JSON shape from [HISTORY.md § "Sidecar metadata schema"](HISTORY.md#sidecar-metadata-schema).

### `history/read` (phase H2)

```ts
// params
{ id: string; inline?: boolean }

// result
{
  entry: HistoryEntry;
  previewMetadata?: PreviewMetadataSnapshot;
  pngPath: string;                  // absolute
  pngBytes?: string;                // base64; populated when inline=true
}
```

Errors:
- `HistoryEntryNotFound` (-32010) — `id` does not match any entry in the configured sources.

### `history/diff` (phase H3 — metadata mode)

```ts
// params
{
  from: string;                     // entry id
  to: string;                       // entry id
  mode?: "metadata" | "pixel";      // default "metadata"
}

// result
{
  pngHashChanged: boolean;
  fromMetadata: HistoryEntry;       // full sidecar
  toMetadata: HistoryEntry;
  // Pixel-mode fields — null in METADATA mode; populated by phase H5.
  diffPx?: number;
  ssim?: number;
  diffPngPath?: string;
}
```

`mode = "metadata"` (default) is cheap: hash compare + sidecar return. The pixel-mode fields
(`diffPx`, `ssim`, `diffPngPath`) stay `null` in METADATA mode by design.

`mode = "pixel"` is **reserved for phase H5** and not implemented in H3 — calls with
`mode = "pixel"` return `-32012` (`HistoryPixelNotImplemented`) so callers can distinguish
"asked for pixel and the daemon isn't ready" from "asked for metadata and got null pixel fields
by design."

The diff resolves `from` and `to` across all configured `HistorySource`s — `from` may live in
`LocalFsHistorySource` while `to` lives on a `preview/main` ref via `GitRefHistorySource`. This is
the load-bearing case for "did my edits change how this preview renders compared to main?" — see
[HISTORY.md § "Diff across branches"](HISTORY.md#diff-across-branches).

Errors:
- `HistoryEntryNotFound` (-32010) — either `from` or `to` does not match any entry.
- `HistoryDiffMismatch` (-32011) — `from` and `to` belong to different previews; pixel diff would
  be meaningless.
- `HistoryPixelNotImplemented` (-32012) — `mode = "pixel"` was requested but the pixel pass is
  reserved for phase H5.

### `data/fetch`, `data/subscribe`, `data/unsubscribe` (phase D1)

Surfaces structured per-render data (a11y findings + hierarchy, layout
tree, recomposition counts, …) without baking it into the PNG. The full
spec — wire shape per method, transports, re-render budgets,
on-disk layout — lives in [DATA-PRODUCTS.md](DATA-PRODUCTS.md). Default
behaviour: a daemon advertising no kinds (pre-D2) rejects every
`data/fetch` and `data/subscribe` with `DataProductUnknown` (-32020); the
methods exist so clients can probe capability without a `protocolVersion`
bump.

### `history/prune` (phase H4)

```ts
params: {
  maxEntriesPerPreview?: number;     // null → use daemon-configured default
  maxAgeDays?: number;               // null → use daemon-configured default
  maxTotalSizeBytes?: number;        // null → use daemon-configured default
  dryRun?: boolean;                  // default false
}
result: {
  removedEntries: string[];          // entry ids removed (or would be in dry-run)
  freedBytes: number;                // sum of pngSize for entries whose PNG actually got deleted
  sourceResults: {                   // per-source breakdown; only writable sources are listed
    [sourceId: string]: {
      removedEntryIds: string[];
      freedBytes: number;
    };
  };
}
```

Each per-call parameter overrides the daemon's configured default for THIS call only — the
auto-prune scheduler keeps using its configured defaults. Set any value to `0` or negative to
disable that knob (e.g. `maxAgeDays: 0` → no age-based pruning).

`dryRun = true` returns the would-remove set without touching disk; does NOT emit
`historyPruned`. `dryRun = false` (default) mutates and emits a `historyPruned` notification
when the prune removed at least one entry.

**Pass order.** Age → per-preview count → total size, with the "never drop the most recent
entry per preview" floor enforced throughout. See HISTORY.md § "Pruning policy".

**Read-only sources.** `GitRefHistorySource` and any other read-only backend skip pruning
entirely (read-only from the daemon's perspective; cleanup is the producer's concern). Their
ids do not appear in `sourceResults`.

**No history configured.** When the daemon has no writable history source, returns an empty
result rather than an error.

## 6. Daemon → client notifications

### `discoveryUpdated`

```ts
{
  added: PreviewInfo[];
  removed: string[];                 // IDs
  changed: PreviewInfo[];            // ID present, metadata differs
  totalPreviews: number;
}
```

`PreviewInfo` mirrors the JSON shape emitted by [DiscoverPreviewsTask](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt) plus the `sourceFile` field added in P0.2:

```ts
{
  id: string;
  className: string;
  methodName: string;
  displayName: string;
  group?: string;
  sourceFile?: string;               // absolute path; null if kotlinc didn't emit SourceFile
  // ... other PreviewInfo fields, schema-stable with previews.json
}
```

Emitted after every Tier-2 incremental discovery that changed the set.

### `renderStarted`

```ts
{ id: string; queuedMs: number }
```

`queuedMs` is wall-clock between accept and start.

### `renderFinished`

```ts
{
  id: string;
  pngPath: string;                   // absolute; existing render strategy decides directory
  tookMs: number;                    // wall-clock for the render body
  metrics?: {
    heapAfterGcMb: number;
    nativeHeapMb: number;
    sandboxAgeRenders: number;
    sandboxAgeMs: number;
  };
}
```

`metrics` is present iff the client set `capabilities.metrics: true` in `initialize`.

`dataProducts` (phase D1, additive) carries per-kind structured payloads
for the `(id, kind)` pairs the client subscribed to via `data/subscribe`,
plus everything in `initialize.options.attachDataProducts`. Absent and
empty are interchangeable on the wire. See
[DATA-PRODUCTS.md](DATA-PRODUCTS.md) for the per-kind shapes.

### `renderFailed`

```ts
{
  id: string;
  error: {
    kind: "compile" | "runtime" | "capture" | "timeout" | "internal";
    message: string;
    stackTrace?: string;             // present for kind="runtime" | "internal"
  };
}
```

Render failures are not protocol errors — `renderNow` succeeded in queueing the work. A failure here means the render itself blew up.

> **Implementation gap:** the daemon currently emits `kind: "internal"` for every render failure regardless of the underlying cause; the `compile` / `runtime` / `capture` / `timeout` discriminants are reserved on the wire but not yet populated. Clients should treat any `kind` value as opaque text until this lands.

### `classpathDirty`

```ts
{
  reason: "fingerprintMismatch" | "fileChanged" | "manifestMissing";
  detail: string;                    // human-readable
  changedPaths?: string[];
}
```

Sent at most once per daemon lifetime. After this notification the daemon refuses all `renderNow` (returning `ClasspathDirty`) and exits within `daemon.classpathDirtyGraceMs` (default 2000ms) to give the client time to consume the message and re-bootstrap.

### `sandboxRecycle` (reserved; not yet emitted)

> Reserved for the sandbox-recycle work (TODO B2.4 / B2.5 / B2.6). The
> wire shape is locked so a future client can be coded against it; the
> daemon does not currently emit it. Sandbox age counters keep growing
> for the host's lifetime today.

```ts
{
  reason: "heapCeiling" | "heapDrift" | "renderTimeDrift" | "histogramDrift"
        | "renderCount" | "leakSuspected" | "manual";
  ageMs: number;
  renderCount: number;
  warmSpareReady: boolean;           // false → next render blocks; expect daemonWarming
}
```

Informational. Always followed by either an immediate resumption or a `daemonWarming`.

### `daemonWarming` (reserved; not yet emitted)

> Reserved alongside `sandboxRecycle`. Not emitted today.

```ts
{ etaMs: number }                    // best-effort estimate; client shows spinner
```

Sent when no warm spare is ready and the next render is blocked on sandbox build. Followed by `daemonReady` when render service resumes.

### `daemonReady` (reserved; not yet emitted)

> Reserved alongside `daemonWarming`. Not emitted today.

```ts
{}
```

Render service is available again after a `daemonWarming` interval.

### `log`

```ts
{
  level: "debug" | "info" | "warn" | "error";
  message: string;
  category?: string;                 // e.g. "discovery", "sandbox", "render"
  context?: Record<string, unknown>;
}
```

Optional channel; client routes to the daemon output channel. Stderr remains the unstructured fallback.

### `historyAdded` (phase H2)

```ts
{ entry: HistoryEntry }
```

Emitted whenever a render lands a new entry on disk via the configured `HistoryManager`. Mirrors
the shape of `discoveryUpdated`. Clients that subscribe avoid polling `history/list`.

The `pngHash` field on `entry` lets a subscriber decide cheaply whether the bytes are different
from the previous render — if not, skip re-fetching. See [HISTORY.md § "historyAdded"](HISTORY.md#historyadded-notification-daemon--client).

### `historyPruned` (phase H4)

```ts
{
  removedIds: string[];               // entry ids removed
  freedBytes: number;                 // sum of pngSize for entries whose PNG actually got deleted
  reason: "auto" | "manual";          // AUTO = scheduler; MANUAL = history/prune RPC
}
```

Emitted after each NON-EMPTY prune pass. Empty (no-op) passes produce no notification.
`dryRun` calls never emit. See HISTORY.md § "Pruning policy".

## 7. Versioning

- `protocolVersion: 1` is this document.
- Non-breaking additions (new optional fields, new notification methods) **do not** bump the version. Daemon and client must ignore unknown fields and unknown notifications.
- Breaking changes (renamed/removed fields, changed semantics, new required fields) bump `protocolVersion` and require a coordinated daemon + extension release.
- `initialize` is the only handshake; mismatched versions fail closed with `InvalidRequest`.

## 8. Out of scope (v1)

- Streaming render output (chunked PNG bytes over the wire) — the client reads `pngPath` from disk.
- Bidirectional cancellation of in-flight renders — Robolectric mid-render cancellation is unsafe; we let renders complete and dedupe via the "needs another pass" flag (DESIGN § 8 Tier 4).
- Authentication. Daemon trusts its parent process; never bind a network socket.
- Multiple concurrent clients per daemon. One stdio pair, one client.

## 9. Test coverage

Stream B owns Kotlin unit tests for message serialisation under [daemon/android/src/test/...](../../). Stream C owns TypeScript unit tests under [vscode-extension/src/daemon/](../../vscode-extension/src/daemon/). A shared golden-message corpus lives in [`docs/daemon/protocol-fixtures/`](protocol-fixtures/) (one JSON file per message kind) and is consumed by both test suites. Adding a new message ⇒ add the fixture in the same PR.
