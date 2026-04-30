# Preview daemon — MCP server overview

> **Status:** implemented in `:mcp` (top-level module). This doc is the
> high-level mapping of daemon ↔ MCP semantics; concrete implementation
> notes live in [MCP-KOTLIN.md](MCP-KOTLIN.md).
>
> Researched against [MCP spec 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/server/resources).
> Cross-referenced from [DESIGN.md](DESIGN.md) and [PROTOCOL.md](PROTOCOL.md).

## Why

An AI agent that wants to "see" what a `@Preview` renders has two
unappealing options without an MCP server:

1. **Run Gradle.** Invoke `:samples:android:renderAllPreviews`, wait for
   cold-config + sandbox-init + render — 10s+ end to end. Useless for an
   iterative agent loop.
2. **Read PNGs from disk.** Assume a known render-history directory exists
   and is fresh. No way to request a re-render or know when one completes.

The preview daemon already solves both for VS Code over JSON-RPC.
Exposing it as an MCP server lets any MCP-aware agent — Claude Code, the
SDK, a custom agent — render previews on demand and receive notifications
when bytes change.

## Push, not poll: how MCP does it

MCP supports server → client notifications. Two relevant capabilities under
the `resources` namespace, both standard:

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
  when the *set* of available resources mutates (e.g. `discoveryUpdated`
  added a new `@Preview`). Broadcast; one-time signal saying "re-list".
- **`subscribe`** — client calls `resources/subscribe` with a specific
  resource URI; server emits `notifications/resources/updated({ uri })`
  every time that one resource's content changes. The client then calls
  `resources/read` to fetch the new bytes.

Plus `notifications/progress` for long-running tool calls.

The `:mcp` module advertises both capabilities and uses **stdio** as its
transport — the same framing the daemon uses, so the MCP shim and the
underlying daemon JSON-RPC channel are wire-shape-compatible.

## Daemon ↔ MCP surface mapping

The daemon's [PROTOCOL.md v1](PROTOCOL.md) maps onto MCP primitives as
follows. The implementation lives in
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
   `(workspaceId, modulePath?, fqnGlob?)`. The MCP server expands a watch
   to a URI set on every `discoveryUpdated`.

Both feed the daemon's `renderFinished` → `notifications/resources/updated`
translation. A session subscribed AND watching a URI receives one update,
not two (set semantics in [`Subscriptions`](../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/Subscriptions.kt)).

`discoveryUpdated` (the daemon's "set of previews changed" signal) maps
to `notifications/resources/list_changed`.

`historyAdded` (a new history entry landed for a preview) also maps to
`notifications/resources/list_changed` — the entry-set for that preview
grew by one, and a subscriber filtering on the `compose-preview-history://`
prefix can re-list.

`classpathDirty` triggers an MCP-side respawn flow rather than
propagating to the client: the supervisor spawns a fresh daemon for the
affected module, fails any in-flight render waiters with
`{kind:"classpathDirty"}`, and emits `list_changed` so subscribers
re-list. After one self-loop the supervisor stops respawning to avoid
thrashing on a permanently-stale descriptor.

### Tools

Twelve tools today:

| Tool | Purpose | Maps to daemon's |
|------|---------|------------------|
| `register_project(path, rootProjectName?, modules?)` | Add a workspace to the supervisor. Returns the assigned `workspaceId`. | — (supervisor-side) |
| `unregister_project(workspaceId)` | Tear down a workspace's daemons. | — (supervisor-side) |
| `list_projects()` | List registered projects. | — (supervisor-side) |
| `render_preview(uri)` | Force-render a preview, returning the PNG inline. | `renderNow` |
| `watch(workspaceId, module?, fqnGlob?)` | Register an area of interest. | — (subscription bookkeeping) |
| `unwatch(workspaceId?, module?, fqnGlob?)` | Drop matching watches. | — |
| `list_watches()` | List the current session's watches. | — |
| `set_visible(workspaceId, module, ids)` | Override the daemon's visible-preview set directly (without a long-lived watch). | `setVisible` |
| `set_focus(workspaceId, module, ids)` | Override the daemon's focused-preview set (higher-priority slice for queue draining). | `setFocus` |
| `notify_file_changed(workspaceId, path, kind?, changeType?)` | Forward a file-edit notification to every daemon in the workspace. | `fileChanged` |
| `history_list(workspaceId, module, …)` | List history entries; decorates each with a `compose-preview-history://` URI. | `history/list` |
| `history_diff(workspaceId, module, from, to)` | Diff two entries (metadata mode). | `history/diff` |

Note: the daemon's render queue is still single-priority FIFO today, so
`setVisible` / `setFocus` traffic flows through the wire but doesn't yet
reorder the queue. The plumbing is in place for B2.5 / predictive
prefetch ([PREDICTIVE.md](PREDICTIVE.md)) to start honouring focus.

## Architecture

The MCP server runs as a separate process that JSON-RPC-clients the
existing daemon:

```
MCP client ── stdio ──▶ MCP server ── stdio JSON-RPC ──▶ daemon JVM (per module)
                       (`:mcp`)                        (`:daemon:android` / `:daemon:desktop`)
```

The MCP server is renderer-agnostic — it depends only on `:daemon:core`
for protocol message types and spawns daemon JVMs via launch descriptors
emitted by `composePreviewDaemonStart`. It never depends on
`:daemon:android` or `:daemon:desktop` directly.

One MCP server multiplexes multiple per-(workspace, module) daemons. A
single agent session can list resources from multiple workspaces (e.g.
two worktrees of the same repo) without re-running Gradle bootstrap for
each.

See [LAYERING.md](LAYERING.md) for the architectural rules that keep this
clean.

## Lifecycle

### Spawn

The MCP server starts with no daemons. Daemons spawn lazily on first
`render_preview` / `resources/read` / `watch` reference for a module. The
supervisor reads `<moduleDir>/build/compose-previews/daemon-launch.json`
written by `composePreviewDaemonStart` (the user must run this once
per module before driving the MCP surface).

### `classpathDirty` / daemon death

When a daemon emits `classpathDirty` and exits, the supervisor:

1. Forgets the daemon and clears cached state (catalog, watch propagator
   memo, in-flight render waiters).
2. Pushes `notifications/resources/list_changed` so connected clients
   re-list.
3. Schedules a respawn on a single-threaded worker. After one self-loop
   without successful re-discovery, gives up and surfaces the failure
   on the next tool call.

If the daemon dies for non-`classpathDirty` reasons (crash, OOM), the
supervisor logs and respawns once. Repeated crashes back off; the user
sees an error on the next tool call.

### MCP client disconnect

The MCP server gracefully shuts down its daemon JVMs (sends `shutdown` +
`exit`, awaits drain) before exiting itself.

## Caching & resource freshness

The daemon already serialises render output to disk. The MCP server does
not maintain its own cache:

1. `resources/read({ uri })` always issues `renderNow` to the daemon.
2. Multi-waiter dedup: concurrent reads of the same URI all wake up on
   the next `renderFinished` (per-key `CompletableFuture` list in
   `pendingRenders`).
3. The daemon decides whether the render is genuinely needed (B2.0+
   classloader swap on `fileChanged`, etc.); MCP just asks for fresh
   bytes.

History resource URIs (`compose-preview-history://…`) short-circuit to
`history/read` rather than triggering a render — historical entries are
immutable.

## Security & trust model

- The MCP server inherits the daemon's trust model: parent-PID-bound,
  stdio-only.
- Resource URIs are validated to match the `compose-preview://` /
  `compose-preview-history://` schemes plus a known workspace ID +
  module path + preview FQN.
- `resources/read` returns rendered PNG bytes — not source files, not
  project metadata.
- No write operations exposed via tools.

## What this isn't

- **Not a replacement for the VS Code path.** PROTOCOL.md v1 stays the
  authoritative wire format for VS Code; MCP is an additional surface for
  agent loops.
- **Not a renderer rewrite.** Same daemon, same render engines, same
  classloader story. Only the protocol-translation shim is new.

## Cross-references

- [MCP-KOTLIN.md](MCP-KOTLIN.md) — implementation reference for the
  `:mcp` module.
- [PROTOCOL.md](PROTOCOL.md) — daemon wire format the MCP shim translates
  from.
- [LAYERING.md](LAYERING.md) — how MCP sits above the daemon without
  entangling either.
- [HISTORY.md § "Layer 3 — MCP mapping"](HISTORY.md#layer-3--mcp-mapping)
  — history-resource mapping details.
- [MCP spec — Resources](https://modelcontextprotocol.io/specification/2025-06-18/server/resources)
  — canonical reference for `resources/subscribe`,
  `notifications/resources/updated`, and capability negotiation.
