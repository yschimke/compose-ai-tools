# Preview daemon — MCP server overview

High-level mapping of daemon ↔ MCP semantics. Implementation notes live in
[MCP-KOTLIN.md](MCP-KOTLIN.md). Researched against
[MCP spec 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/server/resources).

## Why

Push, not poll, daemon-backed agent loop. Without the MCP shim, an agent
must run Gradle (10s+ cold) or read PNGs off disk with no way to request a
re-render or be notified of one. The `:mcp` module exposes the daemon's
JSON-RPC over stdio so MCP clients can render previews on demand and
receive notifications when bytes change.

## Capabilities

```json
{
  "capabilities": {
    "resources": {
      "subscribe": true,
      "listChanged": true
    }
  }
}
```

- **`listChanged`** — server emits `notifications/resources/list_changed`
  when the *set* of available resources mutates.
- **`subscribe`** — server emits `notifications/resources/updated({ uri })`
  every time that one resource's content changes; the client then calls
  `resources/read`.
- **`notifications/progress`** for long-running tool calls.

## Daemon ↔ MCP surface mapping

Implementation lives in
[`mcp/src/main/kotlin/.../DaemonMcpServer.kt`](../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/DaemonMcpServer.kt).

### Resources

Each `@Preview` becomes one MCP `Resource`:

```jsonc
{
  "uri": "compose-preview://<workspaceId>/<encodedModulePath>/<previewFqn>",
  "name": "RedSquare",
  "description": "Red square preview",
  "mimeType": "image/png"
}
```

URI scheme details (parsed by [`PreviewUri`](../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/PreviewResource.kt)):

- **Scheme**: `compose-preview://` for live previews,
  `compose-preview-history://` for historical entries.
- **`workspaceId`**: derived from `(rootProjectName, canonicalPath)` —
  `<sanitised-name>-<8-char-hash>`. Two worktrees of the same repo get
  distinct IDs by construction.
- **`encodedModulePath`**: gradle module path with `:` → `_`
  (`:samples:android` → `_samples_android`).
- **`previewFqn`**: the daemon's preview ID (typically
  `<className>.<methodName>`).
- Optional **`?config=<qualifier>`** for previews with multiple device
  variants.

`resources/list` returns every preview in every supervised daemon's
discovery state. `resources/read` triggers a render via the daemon's
`renderNow` and blocks until `renderFinished` arrives, then returns the
PNG inline as base64-encoded `BlobResourceContents`.

### Subscriptions

Two subscription paths fan into per-URI updates:

1. **`resources/subscribe(uri)`** — explicit per-URI subscription.
2. **`watch` tool** — area-of-interest registration over
   `(workspaceId, modulePath?, fqnGlob?)`. Expanded to a URI set on every
   `discoveryUpdated`.

Both feed `renderFinished` → `notifications/resources/updated`. A session
subscribed AND watching a URI receives one update, not two (set semantics
in [`Subscriptions`](../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/Subscriptions.kt)).

`discoveryUpdated` and `historyAdded` map to
`notifications/resources/list_changed`.

`watch` is non-blocking by default: it records the area of interest and
starts any matching daemons in the background. The tool response includes
per-module readiness (`spawned`, `discoveryReady`, `previewCount`,
`retryAfterMs` when not ready). Agents that need deterministic startup can
call `watch(..., awaitDiscovery=true)`.

`classpathDirty` triggers an MCP-side respawn flow rather than propagating
to the client: the supervisor spawns a fresh daemon, fails any in-flight
render waiters with `{kind:"classpathDirty"}`, and emits `list_changed` so
subscribers re-list. After one self-loop without re-discovery the
supervisor stops respawning.

### Tools

| Tool | Purpose |
|------|---------|
| `register_project(path, rootProjectName?, modules?)` | Add a workspace to the supervisor. Returns the assigned `workspaceId`. |
| `unregister_project(workspaceId)` | Tear down a workspace's daemons. |
| `list_projects()` | List registered projects. |
| `list_devices()` | List the known `@Preview(device=...)` catalog with resolved geometry. |
| `render_preview(uri, overrides?)` | Force-render a preview, returning the PNG inline. |
| `watch(workspaceId, module?, fqnGlob?, awaitDiscovery?, awaitTimeoutMs?)` | Register an area of interest and keep matching previews warm. |
| `unwatch(workspaceId?, module?, fqnGlob?)` | Drop matching watches. |
| `list_watches()` | List watches registered by the current session. |
| `notify_file_changed(workspaceId, path, kind?, changeType?)` | Forward a file-edit notification to daemon(s). |
| `set_visible(workspaceId, module, ids)` | Override the daemon's visible-preview set directly. |
| `set_focus(workspaceId, module, ids)` | Override the focused-preview set for queue priority. |
| `history_list(workspaceId, module, …)` | List historical render entries with `compose-preview-history://` URIs. |
| `history_diff(workspaceId, module, from, to)` | Diff two history entries in metadata mode. |
| `list_data_products(workspaceId?, module?)` | List advertised data-product kinds per daemon. |
| `get_preview_data(uri, kind, params?, inline?)` | Fetch one structured data product for a preview, auto-rendering when needed. |
| `subscribe_preview_data(uri, kind)` | Prime a data product on every render for one preview. |
| `unsubscribe_preview_data(uri, kind)` | Drop a preview data-product subscription. |
| `render_preview_overlay(uri, kind?, inline?, overrides?)` | Render a preview and return an annotated overlay image. |
| `get_preview_extras(uri, kind)` | List non-JSON outputs produced alongside a data product. |
| `record_preview(uri, fps?, scale?, format?, events, overrides?)` | Record a scripted preview interaction to APNG/MP4/WebM. |

The daemon's render queue is single-priority FIFO today, so `setVisible` /
`setFocus` traffic flows through the wire but doesn't yet reorder the queue.

### Scripted interaction recordings

Use `record_preview` when an agent needs to prove that a preview changes after
input, capture an interaction artifact, or exercise a Wear/scroll/toggle flow.
It drives the daemon's `recording/start` → `recording/script` →
`recording/stop` → `recording/encode` sequence against a held scene, so
Compose state can survive across scripted events.

Typical agent flow:

1. Install or verify the MCP server with `compose-preview mcp install --module
   <module>` and `compose-preview mcp doctor --json`.
2. Call `register_project` or start `mcp serve --project <root>`, then `watch`
   the workspace/module of interest.
3. Wait for `notifications/resources/list_changed`, then call
   `resources/list`. Daemon discovery can still be warming up when `watch`
   returns, so retry `resources/list` until the target URI appears.
4. Call `record_preview` with image-natural pixel coordinates:

```json
{
  "uri": "compose-preview://workspace/_samples_android/com.example.TogglePreview",
  "fps": 12,
  "format": "apng",
  "events": [
    { "tMs": 0, "kind": "click", "pixelX": 120, "pixelY": 80 },
    { "tMs": 400, "kind": "recording.probe", "label": "after-first-click" },
    { "tMs": 600, "kind": "click", "pixelX": 120, "pixelY": 80 }
  ]
}
```

Events sharing a `tMs` are treated as one script step. Control markers in the
step are applied before the frame for that timestamp is captured.

`record_preview` accepts two kinds of events:

1. **Built-in input kinds** — `click`, `pointerDown`, `pointerMove`, `pointerUp`,
   `rotaryScroll`, `keyDown`, `keyUp`. Sourced from `InteractiveInputKind`.
2. **Namespaced data-extension events** — advertised by the daemon via
   `capabilities.dataExtensions[].recordingScriptEvents[]`. Only entries with
   `supported = true` are accepted. Today the only supported extension event
   is `recording.probe`.

On success the tool returns an inline media block plus a JSON metadata text
block containing `recordingId`, `videoPath`, `mimeType`, `sizeBytes`,
`frameCount`, `durationMs`, `framesDir`, `frameWidthPx`, and
`frameHeightPx`. It also includes `scriptEvents[]`, a per-event evidence list
with `applied` or `unsupported` status. Raw PNG frames live at
`<framesDir>/frame-00000.png`, `<framesDir>/frame-00001.png`, etc.

Sandbox note: Android recordings run through Robolectric. Restricted agent
sandboxes must allow Robolectric to create its host cache/lock files,
including `$HOME/.robolectric-download-lock`.

## Architecture

```
MCP client ── stdio ──▶ MCP server ── stdio JSON-RPC ──▶ daemon JVM (per module)
                       (`:mcp`)                        (`:daemon:android` / `:daemon:desktop`)
```

The MCP server depends only on `:daemon:core` for protocol message types and
spawns daemon JVMs via launch descriptors emitted by
`composePreviewDaemonStart`. One MCP server multiplexes multiple
per-(workspace, module) daemons. See [LAYERING.md](LAYERING.md).

## Lifecycle

The MCP server starts with no daemons. Daemons spawn lazily on first
`render_preview` / `resources/read` / `watch` reference for a module. The
supervisor reads `<moduleDir>/build/compose-previews/daemon-launch.json`.

On `classpathDirty` the supervisor forgets the daemon, clears cached state,
emits `list_changed`, and respawns once. On non-`classpathDirty` death
(crash, OOM) it logs and respawns once with backoff. On client disconnect,
the MCP server gracefully shuts down its daemon JVMs (sends `shutdown` +
`exit`, awaits drain) before exiting.

## Caching & resource freshness

The daemon serialises render output to disk; the MCP server does not maintain
its own cache. `resources/read({ uri })` always issues `renderNow`.
Concurrent reads of the same URI all wake on the next `renderFinished`
(per-key `CompletableFuture` list in `pendingRenders`).

History resource URIs (`compose-preview-history://…`) short-circuit to
`history/read` rather than triggering a render.

## Security & trust model

- Inherits the daemon's trust model: parent-PID-bound, stdio-only.
- Resource URIs are validated to match the `compose-preview://` /
  `compose-preview-history://` schemes plus a known workspace ID + module
  path + preview FQN.
- `resources/read` returns rendered PNG bytes — not source files, not
  project metadata.
- No write operations exposed via tools.

## Cross-references

- [MCP-KOTLIN.md](MCP-KOTLIN.md) — implementation reference for `:mcp`.
- [PROTOCOL.md](PROTOCOL.md) — daemon wire format the MCP shim translates from.
- [LAYERING.md](LAYERING.md) — architectural rules.
- [DATA-PRODUCTS.md](DATA-PRODUCTS.md#catalogue-open-set) — data product catalogue.
- [MCP spec — Resources](https://modelcontextprotocol.io/specification/2025-06-18/server/resources).
