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
    leakDetection: ("light" | "heavy")[];
  };
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
}

// result
{
  queued: string[];                  // IDs accepted into the render queue
  rejected: { id: string; reason: string }[];   // unknown preview, etc.
}
```

The result resolves as soon as the request is queued, **not** when rendering completes. Per-render progress arrives as `renderStarted` / `renderFinished` / `renderFailed` notifications keyed by ID.

Errors:
- `ClasspathDirty` (-32002) — daemon will not render until restarted.
- `SandboxRecycling` (-32003) — retry after the next `daemonReady` notification.

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

### `classpathDirty`

```ts
{
  reason: "fingerprintMismatch" | "fileChanged" | "manifestMissing";
  detail: string;                    // human-readable
  changedPaths?: string[];
}
```

Sent at most once per daemon lifetime. After this notification the daemon refuses all `renderNow` (returning `ClasspathDirty`) and exits within `daemon.classpathDirtyGraceMs` (default 2000ms) to give the client time to consume the message and re-bootstrap.

### `sandboxRecycle`

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

### `daemonWarming`

```ts
{ etaMs: number }                    // best-effort estimate; client shows spinner
```

Sent when no warm spare is ready and the next render is blocked on sandbox build. Followed by `daemonReady` when render service resumes.

### `daemonReady`

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

Stream B owns Kotlin unit tests for message serialisation under [renderer-android-daemon/src/test/...](../../). Stream C owns TypeScript unit tests under [vscode-extension/src/daemon/](../../vscode-extension/src/daemon/). A shared golden-message corpus lives in [`docs/daemon/protocol-fixtures/`](protocol-fixtures/) (one JSON file per message kind) and is consumed by both test suites. Adding a new message ⇒ add the fixture in the same PR.
