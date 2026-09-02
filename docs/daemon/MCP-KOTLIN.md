# MCP server — implementation reference

Implementation specifics for `:mcp`. See [MCP.md](MCP.md) for the
high-level mapping.

## Module location

`:mcp` is a top-level module (NOT `:daemon:mcp`), parallel to
`:daemon:core` etc. It depends only on `:daemon:core` for protocol
message types and spawns daemon JVMs over stdio via launch descriptors
emitted by `composePreviewDaemonStart`.

## SDK boundary

The official [`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk)
owns MCP framing, negotiation, sessions, stdio, and Streamable HTTP. `DaemonMcpServer` still owns
the dynamic catalog and installs its handlers directly on each SDK session so early pipelined calls
cannot race the handler registration.

## Transport

The local preview-daemon profile uses **stdio**.

The shared UI-builder profile additionally uses the SDK's stateful **Streamable HTTP** transport at
`/mcp`. It exposes only the remote UI-builder tools: a hosted service cannot safely make local
filesystem paths or project-daemon controls meaningful. POST, GET/SSE and DELETE all require the
same preview-server agent grant. The first authenticated request validates the grant through the
Design API, then the MCP session id is bound to its fingerprint so another valid grant cannot take
over that session.

This is not the legacy standalone SSE transport. SSE is used only as Streamable HTTP's response and
notification stream when negotiated by the MCP client.

In both transports `UiBuilderMcpAdapter` is an authenticated HTTP client of preview-server's
versioned Design API. It depends only on the released protocol artifact and deliberately owns no
reducer, persistence, catalog, or render implementation.

## Module layout

```
mcp/
├── build.gradle.kts
├── scripts/
│   ├── README.md
│   └── real_e2e_smoke.py
└── src/main/kotlin/ee/schimke/composeai/mcp/
    ├── DaemonMcpMain.kt        — entry point; arg parsing; supervisor wiring
    ├── DaemonMcpServer.kt      — load-bearing wiring layer
    ├── McpServer.kt            — McpSession + handler interface
    ├── UiBuilderStreamableHttp.kt — authenticated hosted `/mcp` transport
    ├── DaemonClient.kt         — JSON-RPC client of one daemon JVM
    ├── DaemonSupervisor.kt     — owns per-(workspace, module) daemons
    ├── PreviewResource.kt      — URI parsing (PreviewUri / HistoryUri / WorkspaceId / FqnGlob)
    ├── Subscriptions.kt        — per-session subscription bookkeeping + watch entries
    ├── WatchPropagator.kt      — translates watch unions to per-daemon setVisible/setFocus
    ├── HistoryStore.kt         — pluggable seam (NOOP default; daemon owns history today)
    └── protocol/
        └── McpMessages.kt      — MCP wire-shape types
```

Tests cover protocol conformance (`DaemonMcpServerTest`), fake-daemon
end-to-end (`FakeDaemon` + `RealMcpEndToEndTest` gated on
`-Pmcp.real=true`), and resource-read flows (`PreviewResourceTest`).

## Key classes

### `DaemonSupervisor`

Owns the per-(workspace, module) daemon map. Workspaces are registered
explicitly via `register_project` (or `--project <path>` CLI args).
Daemons within a workspace spawn lazily on first reference.

`DescriptorProvider` is a pluggable seam — production reads
`<moduleDir>/build/compose-previews/daemon-launch.json`; tests substitute
an in-memory provider. `DaemonClientFactory` is the spawn seam —
production (`SubprocessDaemonClientFactory`) forks a JVM via
`ProcessBuilder` and pipes stdio into a `DaemonClient`; tests inject an
in-memory factory.

The supervisor demultiplexes every daemon's notification stream by
method name through `NotificationRouter`. `DaemonMcpServer` registers
handlers for `discoveryUpdated`, `renderFinished`, `renderFailed`,
`classpathDirty`, `historyAdded` plus an `onClose` for daemon death.

**Sandbox-count knob.** `DaemonSupervisor` passes
`composeai.daemon.sandboxCount = 1 + replicasPerDaemon` on the launch
descriptor instead of spawning N+1 JVM subprocesses. Default
`replicasPerDaemon = 3`. See [SANDBOX-POOL.md](SANDBOX-POOL.md) and
[CONFIG.md](CONFIG.md).

### `DaemonMcpServer`

The load-bearing wiring layer. Owns:

- The per-(workspace, module) preview catalog populated from daemon
  `discoveryUpdated`.
- The MCP resources surface (`list`, `read`, `subscribe`, `unsubscribe`).
- The MCP tools surface (see [MCP.md § Tools](MCP.md#tools)).
- The translation of daemon `renderFinished` →
  `notifications/resources/updated` and `discoveryUpdated` →
  `notifications/resources/list_changed`.
- Watch propagation back to daemons via `WatchPropagator`.
- Multi-waiter dedup map (`pendingRenders`) so concurrent reads of the
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
4. Reads the PNG from disk, encodes as base64, returns as
   `BlobResourceContents`.

History URIs bypass step 1 and call `history/read({ inline: true })`
directly — historical bytes are immutable.

### `McpSession`

One connected MCP client. Owns a reader thread (drains stdin, dispatches
to handlers) and serialises writes through a synchronised `OutputStream`.
Method handlers are pluggable via the `McpHandlers` interface so tests
can drive the session without a supervisor.

Capability negotiation advertises:

```kotlin
ServerCapabilities(
  tools = ToolsCapability(listChanged = false),
  resources = ResourcesCapability(subscribe = true, listChanged = true),
)
```

`notifications/initialized` is optional — the response to `initialize`
already flips the gating flag.

### `Subscriptions` + `WatchPropagator`

Two-tier subscription model:

- **Per-URI subscriptions** (`resources/subscribe(uri)`) — direct.
- **Watch entries** (`watch` tool) — `(workspaceId, modulePath?,
  fqnGlob?)` matchers, expanded against the live catalog on every
  `discoveryUpdated`.

On every `renderFinished`, both per-URI subscribers and watch-matchers
fire. A session subscribed AND watching gets one notification (set
semantics).

`WatchPropagator` aggregates the union of all sessions' watches per
daemon and forwards as `setVisible` + `setFocus`. Idempotent — caches the
last sent set per daemon.

The MCP-side `set_visible` / `set_focus` tools provide direct passthrough.
The next `WatchPropagator.recompute` replaces whatever the explicit tool
set.

### `PreviewUri` / `HistoryUri` / `WorkspaceId`

URI shape:

- `compose-preview://<workspaceId>/<encodedModulePath>/<previewFqn>?config=<qualifier>`
- `compose-preview-history://<workspaceId>/<encodedModulePath>/<previewFqn>/<entryId>`

`WorkspaceId` is `<sanitised-rootProjectName>-<8-char-sha256>` over the
canonical absolute path. `encodedModulePath` swaps `:` for `_`. `FqnGlob`
compiles a glob — `*` matches non-dot, `**` matches anything, `?`
matches one non-dot.

## Multi-workspace lifecycle

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
`initialize`. Initial preview set comes from `initialize.manifest.path`.
Spawn cost: ~5–10 s for Robolectric, ~600 ms for desktop.

### `classpathDirty`

The daemon emits exactly once per lifetime then exits within
`classpathDirtyGraceMs` (default 2 s). The MCP server:

1. Fails any in-flight render waiters with
   `RenderOutcome.Failed("classpathDirty", …)`.
2. Removes the daemon from the supervisor and clears catalog +
   `WatchPropagator` state.
3. Emits `notifications/resources/list_changed`.
4. Schedules a respawn on `daemonLifecycleExecutor`. After one self-loop
   without re-discovery the supervisor stops respawning — the descriptor
   is genuinely stale.

The respawn cap recovers when the user re-registers via
`register_project`.

### MCP client disconnect

The SDK stdio transport closes on stdin EOF. `DaemonMcpMain.main` calls
`supervisor.shutdown()` after the session exits, which sends `shutdown`
+ `exit` to every daemon and waits for them to drain.

## Reliability invariants

- **Multi-waiter dedup.** `CompletableFuture` is published BEFORE
  `renderNow` is sent so the daemon's notification can't race ahead of
  the wait.
- **Render-result completion outside the daemon's reader-thread.**
  `pendingRenders.remove(key)` returns the list atomically; futures are
  completed afterwards so a slow consumer can't block the daemon's
  reader thread.
- **Single-threaded daemon-lifecycle executor.** Classpath-dirty
  respawns serialise so concurrent saves can't double-spawn.
- **Daemon-flagged executors for everything.** Lifecycle and progress-beat
  executors don't delay JVM shutdown.

## Cross-references

- [MCP.md](MCP.md) — high-level mapping.
- [PROTOCOL.md](PROTOCOL.md) — daemon wire format.
- [LAYERING.md](LAYERING.md) — module-boundary rules; MCP is Layer 3.
- [TEST-HARNESS.md](TEST-HARNESS.md) — pattern for spawning real daemons.
- [HISTORY.md § "Layer 3 — MCP mapping"](HISTORY.md#layer-3--mcp-mapping)
  — history-resource mapping details.
