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

Current tools:

| Tool | Purpose | CLI fit |
|------|---------|---------|
| `register_project(path, rootProjectName?, modules?)` | Add a workspace to the supervisor. Returns the assigned `workspaceId`. | Already covered by `compose-preview mcp install` / `serve --project`. |
| `unregister_project(workspaceId)` | Tear down a workspace's daemons. | MCP lifecycle only. |
| `list_projects()` | List registered projects. | MCP lifecycle only. |
| `list_devices()` | List the known `@Preview(device=...)` catalog with resolved geometry. | **Good CLI fit:** pure query, no daemon spawn needed. |
| `render_preview(uri, overrides?)` | Force-render a preview, returning the PNG inline. | Partly covered by `render`; daemon-backed overrides are a larger follow-up. |
| `watch(workspaceId, module?, fqnGlob?)` | Register an area of interest and keep matching previews warm. | MCP/session only. |
| `unwatch(workspaceId?, module?, fqnGlob?)` | Drop matching watches. | MCP/session only. |
| `list_watches()` | List watches registered by the current session. | MCP/session only. |
| `notify_file_changed(workspaceId, path, kind?, changeType?)` | Forward a file-edit notification to daemon(s). | MCP/session only; CLI already uses Gradle tasks for one-shot freshness. |
| `set_visible(workspaceId, module, ids)` | Override the daemon's visible-preview set directly. | MCP/session only. |
| `set_focus(workspaceId, module, ids)` | Override the focused-preview set for queue priority. | MCP/session only. |
| `history_list(workspaceId, module, …)` | List historical render entries and decorate them with `compose-preview-history://` URIs. | **Good CLI fit:** local history is already file-backed. |
| `history_diff(workspaceId, module, from, to)` | Diff two history entries in metadata mode. | **Good CLI fit:** useful in CI and simple if scoped to metadata/local FS first. |
| `list_data_products(workspaceId?, module?)` | List advertised data-product kinds per daemon. | **Best CLI fit:** exposes available structured outputs to scripts. |
| `get_preview_data(uri, kind, params?, inline?)` | Fetch one structured data product for a preview, auto-rendering when needed. | **Best CLI fit:** generalizes `a11y` into kind-addressable data. |
| `subscribe_preview_data(uri, kind)` | Prime a data product on every render for one preview. | MCP/session only; not useful for one-shot CLI. |
| `unsubscribe_preview_data(uri, kind)` | Drop a preview data-product subscription. | MCP/session only. |
| `render_preview_overlay(uri, kind?, inline?, overrides?)` | Render a preview and return an annotated overlay image. | Good later CLI fit, probably as `data get --extra overlay --output`. |
| `get_preview_extras(uri, kind)` | List non-JSON outputs produced alongside a data product. | Good later CLI fit once `data get` exists. |
| `record_preview(uri, fps?, scale?, format?, events, overrides?)` | Record a scripted preview interaction to APNG/MP4/WebM. | High value but not simple; keep MCP-first for now. |

Note: the daemon's render queue is still single-priority FIFO today, so
`setVisible` / `setFocus` traffic flows through the wire but doesn't yet
reorder the queue. The plumbing is in place for B2.5 / predictive
prefetch ([PREDICTIVE.md](PREDICTIVE.md)) to start honouring focus.

## CLI candidates from the MCP surface

The CLI is best at one-shot, scriptable commands that write stable JSON to
stdout or files to `--output`. Long-lived MCP concepts (`watch`,
subscriptions, `set_visible`, `set_focus`) should stay MCP-only unless
there is a concrete non-interactive workflow.

Highest-value simple additions:

1. **`compose-preview data-products [--module M] [--json]`**
   - Maps to MCP `list_data_products`.
   - Value: lets agents and CI discover which structured outputs are
     available before asking for them.
   - Simple first implementation: daemon-backed, using the same launch
     descriptors that `mcp serve` already consumes. It can spawn the
     daemon, read `InitializeResult.capabilities.dataProducts`, print an
     envelope such as `compose-preview-data-products/v1`, then shut down.
   - Lower-fidelity fallback: a static catalogue from
     [DATA-PRODUCTS.md](DATA-PRODUCTS.md#catalogue-open-set) is less useful
     because it cannot distinguish enabled vs unavailable producers.

2. **`compose-preview data get --id PREVIEW --kind KIND [--module M] [--json|--output PATH]`**
   - Maps to MCP `get_preview_data`.
   - Value: generalizes the current `a11y` command into a stable,
     kind-addressable surface for `a11y/atf`, `a11y/hierarchy`,
     `a11y/touchTargets`, `compose/semantics`, `text/strings`,
     `resources/used`, traces, and future products.
   - Simple first implementation: run `renderAllPreviews` for the selected
     module(s), then read already-produced files under
     `build/compose-previews/data/<previewId>/<kind-with-slashes-as-dashes>.json`.
     This covers cheap/ambient products without adding a daemon lifecycle to
     the command.
   - Better second implementation: daemon-backed fetch with the MCP
     supervisor so the command can auto-render one preview and handle
     re-render-on-demand products.

3. **`compose-preview devices [--json]`**
   - Maps to MCP `list_devices`.
   - Value: gives scripts and agents the legal `id:*` device strings and
     resolved dimensions for override planning.
   - Simple implementation: read `DeviceDimensions.KNOWN_DEVICE_IDS`
     directly. The CLI already bundles `:mcp`, which depends on daemon
     protocol/core, so no new process or Gradle invocation is required.

4. **`compose-preview history list|diff [--module M] [--id PREVIEW] [--json]`**
   - Maps to MCP `history_list` and `history_diff`.
   - Value: useful outside MCP for CI summaries and local "what changed?"
     checks.
   - Simple first implementation: support only the local
     `.compose-preview-history` source and metadata diff. Defer git-ref
     history and pixel diff until there is a CLI workflow that needs them.

Useful but not first:

- **`compose-preview overlay --id PREVIEW --kind a11y/overlay --output out.png`**
  is valuable once `data get` exists, but it should reuse the same data
  plumbing rather than become a separate command first.
- **Daemon-backed `render --override ...`** would expose MCP
  `render_preview.overrides`, but it changes the CLI from Gradle-task
  rendering to daemon rendering for this path. That is worthwhile, but
  larger than data-product reads.
- **`record_preview` as `compose-preview record`** is compelling for
  demos/regression artifacts, but it depends on held sessions, scripted
  event parsing, format validation, and binary output handling. Keep it
  MCP-first until the daemon recording surface has more CLI demand.

Recommended first PR sequence:

1. Add `devices` because it is self-contained and validates the JSON
   envelope pattern for new CLI read-only commands.
2. Add `data-products` backed by daemon initialize capabilities.
3. Add `data get` in offline mode for products already emitted by
   `renderAllPreviews`.
4. Upgrade `data get` to daemon-backed `data/fetch` when callers need
   auto-render or products that require a different render mode.

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
