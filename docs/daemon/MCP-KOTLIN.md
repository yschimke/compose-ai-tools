# MCP server — implementation reference

> **Status:** implemented in `:mcp` (top-level module).
> See [MCP.md](MCP.md) for the high-level mapping; this doc covers the
> implementation specifics: module layout, transport, wire format, key
> classes, multi-workspace lifecycle.

## Module location

`:mcp` is a top-level module (NOT `:daemon:mcp`), parallel to
`:daemon:core` etc. It depends only on `:daemon:core` for the protocol
message types and spawns daemon JVMs over stdio via launch descriptors
emitted by `composePreviewDaemonStart`.

## Why we don't use the MCP Kotlin SDK

The implementation is hand-rolled rather than built on
[`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk).
Rationale (per `mcp/build.gradle.kts:40-45`):

- The SDK auto-registers internal handlers for `resources/list` /
  `resources/read` / `subscribe`, making the dynamic catalog + push
  subscription model we want awkward to bolt on.
- Rolling our own keeps the wire layer self-contained, mirrors the
  proven `:daemon:core` `JsonRpcServer` framing, and removes a
  0.x-version-pin risk.
- The SDK can be reintroduced as an internal refactor once the surface
  stabilises.

The wire-shape work the SDK would do is small: capability negotiation,
JSON-RPC envelope, LSP-style framing. The daemon already does all three
in `:daemon:core` and we reuse the patterns.

## Transport

**stdio only.** The MCP server reads framed JSON-RPC from stdin, writes
to stdout. Same `Content-Length:` framing as the daemon
([PROTOCOL.md § 1](PROTOCOL.md#1-transport)).

HTTP / SSE / streamable-HTTP transports are out of scope today. If a
remote-agent use case materialises, the right next step is to factor
[`McpSession`](../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/McpServer.kt)'s
read/write loops behind a transport interface and add an HTTP variant
alongside. The wire-shape code (request dispatch, notification fan-out)
stays.

## Module layout

```
mcp/
├── build.gradle.kts
├── scripts/
│   ├── README.md
│   └── real_e2e_smoke.py
└── src/main/kotlin/ee/schimke/composeai/mcp/
    ├── DaemonMcpMain.kt        — entry point; arg parsing; supervisor wiring
    ├── DaemonMcpServer.kt      — the load-bearing wiring layer
    ├── McpServer.kt            — McpSession (one connected client) + handler interface
    ├── DaemonClient.kt         — JSON-RPC client of one daemon JVM
    ├── DaemonSupervisor.kt     — owns per-(workspace, module) daemons
    ├── PreviewResource.kt      — URI parsing (PreviewUri / HistoryUri / WorkspaceId / FqnGlob)
    ├── Subscriptions.kt        — per-session subscription bookkeeping + watch entries
    ├── WatchPropagator.kt      — translates watch unions to per-daemon setVisible/setFocus
    ├── HistoryStore.kt         — pluggable seam (NOOP default; daemon owns history today)
    └── protocol/
        └── McpMessages.kt      — MCP wire-shape types
```

Test sources cover protocol conformance (`DaemonMcpServerTest`),
fake-daemon end-to-end (`FakeDaemon` + `RealMcpEndToEndTest` gated on
`-Pmcp.real=true`), and resource-read flows (`PreviewResourceTest`).

## Key classes

### `DaemonSupervisor`

Owns the per-(workspace, module) daemon map. Workspaces are registered
explicitly via the `register_project` MCP tool (or `--project <path>`
CLI args at startup). Daemons within a workspace spawn lazily on first
reference.

`DescriptorProvider` is a pluggable seam — production reads
`<moduleDir>/build/compose-previews/daemon-launch.json` from disk; tests
substitute an in-memory provider.

`DaemonClientFactory` is the spawn seam — production
([`SubprocessDaemonClientFactory`](../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/DaemonMcpMain.kt))
forks a JVM via `ProcessBuilder` and pipes its stdio into a
`DaemonClient`; tests inject an in-memory factory wiring the client to a
fake daemon over piped streams.

The supervisor demultiplexes every daemon's notification stream by method
name through `NotificationRouter`. `DaemonMcpServer` registers handlers
for `discoveryUpdated`, `renderFinished`, `renderFailed`,
`classpathDirty`, `historyAdded` plus an `onClose` for daemon death.

### `DaemonMcpServer`

The load-bearing wiring layer. Owns:

- The per-(workspace, module) preview catalog populated from daemon
  `discoveryUpdated`.
- The MCP resources surface (`list`, `read`, `subscribe`, `unsubscribe`).
- The MCP tools surface (12 tools — see [MCP.md § Tools](MCP.md#tools)).
- The translation of daemon `renderFinished` →
  `notifications/resources/updated`.
- The translation of daemon `discoveryUpdated` →
  `notifications/resources/list_changed`.
- Watch propagation back to daemons via `WatchPropagator`.
- A multi-waiter dedup map (`pendingRenders`) so concurrent reads of the
  same URI all wake up on one `renderFinished`.
- Two single-thread executors: `daemonLifecycleExecutor` for
  classpath-dirty respawns, `progressBeatExecutor` for periodic
  `notifications/progress` beats during slow renders.

The render path:

1. `resources/read(uri)` parses the URI, picks the right daemon, adds a
   `CompletableFuture<RenderOutcome>` to `pendingRenders` BEFORE sending
   `renderNow` (so the daemon's notification can't arrive ahead of the
   wait).
2. Optionally schedules `notifications/progress` beats every 500 ms if
   the client opted in via `_meta.progressToken`.
3. Awaits the future with `renderTimeoutMs` (default 60 s).
4. Reads the PNG from disk (the daemon writes it; the MCP server reads
   it back), encodes as base64, returns as a `BlobResourceContents`.

History URIs (`compose-preview-history://…`) bypass step 1 and call
`history/read({ inline: true })` against the daemon directly — historical
bytes are immutable so there's no render path involved.

### `McpSession`

One connected MCP client. Owns a reader thread (drains stdin, dispatches
to handlers) and serialises writes through a synchronised `OutputStream`.

Method handlers are pluggable via the `McpHandlers` interface so tests
can drive the session without a supervisor. `DaemonMcpServer.newSession`
wires the production handler set.

Capability negotiation advertises:

```kotlin
ServerCapabilities(
  tools = ToolsCapability(listChanged = false),
  resources = ResourcesCapability(subscribe = true, listChanged = true),
)
```

`notifications/initialized` is optional — the response to `initialize`
already flips the gating flag, since some clients omit the
follow-up notification.

### `Subscriptions` + `WatchPropagator`

Two-tier subscription model:

- **Per-URI subscriptions** (`resources/subscribe(uri)`) — direct.
- **Watch entries** (`watch` tool) — `(workspaceId, modulePath?,
  fqnGlob?)` matchers. Expanded against the live catalog on every
  `discoveryUpdated`.

On every `renderFinished`, both the per-URI subscribers and the
watch-matchers fire. A session subscribed AND watching gets one
notification (set semantics).

`WatchPropagator` aggregates the union of all sessions' watches per
daemon and forwards it as `setVisible` + `setFocus`. Idempotent — caches
the last sent set per daemon and skips the wire call when unchanged.

The MCP-side `set_visible` / `set_focus` tools (#332) provide a direct
passthrough alongside the watch-driven path: an agent can express
"render this one ahead of others" without registering a long-lived
watch. The next `WatchPropagator.recompute` (e.g. on the next
`discoveryUpdated` or `watch`/`unwatch`) replaces whatever the explicit
tool set.

Note: the daemon's render queue is still single-priority FIFO today, so
the wire calls flow through but don't yet reorder the queue. The
plumbing is there for B2.5 / predictive prefetch to start honouring
focus.

### `PreviewUri` / `HistoryUri` / `WorkspaceId`

URI shape:

- `compose-preview://<workspaceId>/<encodedModulePath>/<previewFqn>?config=<qualifier>`
- `compose-preview-history://<workspaceId>/<encodedModulePath>/<previewFqn>/<entryId>`

`WorkspaceId` is `<sanitised-rootProjectName>-<8-char-sha256>` over the
canonical absolute path. Two worktrees of the same repo get distinct IDs
by construction; symlink aliases collapse.

`encodedModulePath` swaps `:` for `_` so `:samples:android` rides as
`_samples_android` inside a URI hostname segment.

`FqnGlob` compiles a glob over preview FQNs — `*` matches non-dot
characters, `**` matches anything including dots, `?` matches one
non-dot. Used by the `watch` tool's `fqnGlobPattern` field.

## Lifecycle

### Server start

`DaemonMcpMain.main` builds a supervisor with disk-reading descriptor
provider + subprocess factory, instantiates `DaemonMcpServer`, registers
any `--project <path>[:<rootProjectName>]` CLI args, hooks a JVM shutdown
hook for graceful supervisor teardown, and starts a single `McpSession`
on stdin/stdout. Blocks main thread on `awaitClose()` until stdin EOF.

### First preview request for a module

Supervisor's `daemonFor(workspaceId, modulePath)` does a
`computeIfAbsent` over the per-project daemon map; on miss, reads the
descriptor, spawns the JVM, wires the notification stream, calls
`initialize`. Initial preview set comes from
`initialize.manifest.path` — supervisor reads that file and synthesises
a `discoveryUpdated` notification through the router so the catalog
populates on the same code path as incremental updates.

Spawn cost is the daemon's cold-start time: ~5–10 s for Robolectric,
~600 ms for desktop.

### `classpathDirty`

The daemon emits exactly once per lifetime then exits within
`classpathDirtyGraceMs` (default 2 s). The MCP server:

1. Fails any in-flight render waiters for that daemon with
   `RenderOutcome.Failed("classpathDirty", …)`.
2. Removes the daemon from the supervisor and clears catalog +
   `WatchPropagator` state.
3. Emits `notifications/resources/list_changed` so connected clients
   re-list.
4. Schedules a respawn on `daemonLifecycleExecutor`. If the new daemon
   also emits `classpathDirty` before any successful render, the
   supervisor stops respawning (cap of 1) — the descriptor is genuinely
   stale and the user needs to re-run `composePreviewDaemonStart`.

The respawn cap is a workspace-lifetime counter; it doesn't decay. A
workspace stuck against the cap recovers when the user re-registers via
`register_project` (which clears the supervisor's cached entry).

### Daemon dies for other reasons

`onClose` handler runs catalog + propagator cleanup; the next reference
to the same `(workspaceId, modulePath)` triggers a fresh spawn via
`computeIfAbsent`. No automatic respawn — the next user request drives
recovery.

### MCP client disconnect

`McpSession.runReader` exits on stdin EOF; `onClose` handler unregisters
from the session registry and forgets the session's subscriptions /
watches. `DaemonMcpMain.main` calls `supervisor.shutdown()` after the
session exits, which sends `shutdown` + `exit` to every daemon and waits
for them to drain.

## Tools surface

See [MCP.md § Tools](MCP.md#tools) for the full table. Implementation
notes:

- `register_project` canonicalises the path, derives the `WorkspaceId`,
  and stores the optional `modules` hint on the project record. Does not
  spawn daemons.
- `watch` eagerly spawns daemons matching the watch (synchronously for
  daemons already up, asynchronously via `daemonLifecycleExecutor` for
  fresh ones) so the catalog populates without the client having to
  speculatively `read` first.
- `notify_file_changed` forwards the `fileChanged` notification to every
  daemon in the workspace AND re-issues `renderNow` for every URI any
  session has watched/subscribed in this workspace. The daemon's
  `fileChanged({source})` is a classloader swap, not an auto-render —
  the explicit re-render is what drives push notifications back out.
- `history_list` decorates each entry with a
  `compose-preview-history://` URI so clients can call `resources/read`
  on it directly.
- `history_diff` is metadata-mode only (pixel-mode reserved for daemon
  phase H5).

## Reliability invariants

- **Multi-waiter dedup.** A `CompletableFuture` is published BEFORE
  `renderNow` is sent so the daemon's notification can't race ahead of
  the wait.
- **Render-result completion outside the daemon's reader-thread compute
  lambda.** `pendingRenders.remove(key)` returns the list atomically;
  futures are completed afterwards so a slow consumer can't block the
  daemon's reader thread.
- **Single-threaded daemon-lifecycle executor.** Classpath-dirty
  respawns serialise so concurrent saves can't double-spawn (the
  supervisor's `daemonFor` is already `computeIfAbsent`-safe; the
  executor adds belt-and-braces).
- **Daemon-flagged executors for everything.** Lifecycle and progress-beat
  executors don't delay JVM shutdown.

## What this provides that the CLI can't

| Capability | CLI | MCP server |
|------------|-----|------------|
| **Push on render-complete** | ❌ Process exits; no notification channel | ✅ `notifications/resources/updated` |
| **Push on discovery change** | ❌ Re-run the CLI | ✅ `notifications/resources/list_changed` |
| **Sub-second warm renders** | ❌ Cold Gradle config + sandbox init every invocation | ✅ Daemon stays warm |
| **Subscription back-pressure** | ❌ No subscription concept | ✅ Per-URI + watch-glob subscriptions |
| **Multi-module multiplexing** | ❌ One CLI call → one Gradle module | ✅ Supervisor multiplexes per-module daemons |
| **Multi-workspace** | ❌ One CLI per project root | ✅ Multiple `register_project` calls; one MCP server hosts many |
| **Progress notifications** | ❌ stdout text; client parses | ✅ `notifications/progress` typed events |
| **Structured failures** | ❌ Non-zero exit + stderr text | ✅ `RenderOutcome.Failed` typed back to caller |
| **Image bytes inline** | ❌ Writes PNG; client reads disk | ✅ `BlobResourceContents` returns base64 PNG inline |
| **History resources** | ❌ Walks `.compose-preview-history/` directly | ✅ `compose-preview-history://` URIs + `history_list` / `history_diff` tools |

## Phasing

**v0 — shipped (#309).** Stdio transport. Tools: `register_project`,
`unregister_project`, `list_projects`, `render_preview`. Resources:
list + read. Single MCP server can supervise per-(workspace, module)
daemons across multiple distinct projects (the path-hashed
`WorkspaceId` makes worktrees of the same repo distinct by
construction).

**v1 — shipped (#317, #321, #328, this branch).** Subscriptions and
the push story:

- Resource subscribe / unsubscribe; `renderFinished` →
  `notifications/resources/updated`; `discoveryUpdated` →
  `notifications/resources/list_changed`.
- `watch` / `unwatch` / `list_watches` tools: register an
  area-of-interest set (workspace + module + FQN glob); the supervisor
  expands to URIs and forwards as `setVisible` / `setFocus` to the
  matched daemons. Re-expansion on `discoveryUpdated`.
- `notify_file_changed` tool — forwards `fileChanged` to all daemons
  in the matched workspace plus re-issues `renderNow` for any watched
  / subscribed URI so the daemon produces fresh bytes that flow back
  through the existing push path.
- `set_visible` / `set_focus` explicit tools — direct passthrough to
  the daemon for "render this one ahead of others" UX. Originally
  filed as v4; landed early because the wiring was trivial once watch
  + propagator were in place.
- `notifications/progress` for slow renders. Clients opt in via
  `_meta.progressToken` in the `resources/read` request; the server
  schedules periodic beats during the render wait and self-cancels on
  completion.
- Multi-waiter `pendingRenders` dedup — concurrent reads of the same
  URI all wake on the same `renderFinished`.
- `classpathDirty` respawn: dropping the dying daemon, async spawn of
  the replacement, in-flight read-waiter failure with a typed reason,
  re-emit `setVisible` to the new daemon once its initial discovery
  seeds. One-retry cap to prevent stale-descriptor thrashing.
- Async daemon spawn from `watch` so the McpSession reader thread
  never blocks on cold start (Robolectric ~5–10 s, desktop ~600 ms).
- Real-daemon `RealMcpEndToEndTest` opt-in on `-Pmcp.real=true`,
  driving a live desktop daemon JVM through edit → recompile →
  `notify_file_changed` → re-read → `git checkout` → re-read.

**v1.5 — shipped (#318, #322, this branch).** H6 history mapping.
Daemon ships H1+H2 (`history/list` / `history/read` /
`historyAdded`) and H3 metadata diff + H10a git-ref read. The MCP
layer wires:

- New URI scheme `compose-preview-history://<workspace>/<module>/<previewFqn>/<entryId>`.
- `history_list` and `history_diff` tools (METADATA mode only;
  pixel mode reserved for daemon H5).
- `resources/read` on a history URI → daemon `history/read` with
  `inline = true`, falls back to disk read on git-ref sources.
- `historyAdded` daemon notification → `notifications/resources/list_changed`
  fired only to sessions subscribed to / watching the matching live URI
  (not a global broadcast).

**v2 — open.** Ktor streamable-HTTP transport. Remote agents work.
Auth / TLS / rate-limiting decisions land here as a sibling design
(out of scope for v0/v1). Once HTTP lands, `Subscriptions` already
types its session refs against the [`Session`](
../../mcp/src/main/kotlin/ee/schimke/composeai/mcp/McpServer.kt)
interface (not the concrete `McpSession`), so an HTTP session type
plugs in without an unsafe cast.

**v3 — open.** Sampling/elicitation hooks for cases where the server
wants to ask the client mid-render (e.g. preview-parameter
selection). Speculative; gate on actual demand.

**Pixel-mode history diff — open.** When daemon H5 lands the
`mode = "pixel"` path, `history_diff` flips a flag from METADATA to
PIXEL and surfaces the `diffPx` / `ssim` / `diffPngPath` fields the
wire shape already reserves.

## Cross-references

- [MCP.md](MCP.md) — high-level mapping.
- [PROTOCOL.md](PROTOCOL.md) — the daemon wire format the MCP shim
  translates from.
- [LAYERING.md](LAYERING.md) — module-boundary rules; MCP is Layer 3.
- [TEST-HARNESS.md](TEST-HARNESS.md) — pattern the harness follows for
  spawning real daemons; MCP's `RealMcpEndToEndTest` reuses the same
  shape.
- [HISTORY.md § "Layer 3 — MCP mapping"](HISTORY.md#layer-3--mcp-mapping)
  — history-resource mapping details.
