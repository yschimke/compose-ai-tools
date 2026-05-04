# Layering — keeping CLI, daemon, and MCP additive

Active architectural rule. Mandatory reading before adding a new
cross-layer hook.

## The three layers

```
   ┌──────────────────────────────────────────────────────────────┐
   │ Layer 3 — MCP server                          [opt-in, v2+]  │
   │   :mcp — top-level module, separate process by default.      │
   │   JSON-RPC client of Layer 2.                                │
   └─────────────────────────┬────────────────────────────────────┘
                             │  PROTOCOL.md JSON-RPC over stdio
   ┌─────────────────────────▼────────────────────────────────────┐
   │ Layer 2 — Preview daemon                       [opt-in, v1]  │
   │   :daemon:core + :daemon:android + :daemon:desktop           │
   │   Long-lived JVM, hot sandbox, JSON-RPC server.              │
   └─────────────────────────┬────────────────────────────────────┘
                             │  reuses
   ┌─────────────────────────▼────────────────────────────────────┐
   │ Layer 1 — Gradle tasks + CLI                   [always on]   │
   │   :gradle-plugin renderPreviews task; CLI. Authoritative for │
   │   CI.                                                        │
   └──────────────────────────────────────────────────────────────┘
```

**Strictly additive.** Layer 1 works with Layer 2 and Layer 3 absent.
Layer 2 works with Layer 3 absent. Layer 3 cannot exist without Layer 2.

**Each layer lives in its own modules.** No layer's code compiles into
another layer's modules. The only allowed seams are listed in
§ Integration seams.

## Layer 1 — what stays untouched

- **No daemon requirement for CI.** `renderPreviews` does not require a
  running daemon. Editor integrations use the top-level `daemon { … }`
  DSL, which defaults to enabled and can be disabled temporarily.
- **No daemon code on the Gradle plugin classpath.** `:gradle-plugin`
  does not depend on `:daemon:core`. The `composePreviewDaemonStart`
  task emits a descriptor file; it does not import or instantiate
  anything from daemon modules.
- **CLI daemon-library access is narrow and renderer-agnostic.** `:cli`
  may depend on `:daemon:core` for pure catalog/protocol helpers (e.g.
  `DeviceDimensions`, `KnownDevice`, protocol DTOs, history models).
  The CLI must not depend on `:daemon:android`, `:daemon:desktop`,
  `:renderer-android`, or `:renderer-desktop`.
- **No conditional branches in `renderPreviews`** for "is the daemon
  running?". The Gradle task path is unaware of the daemon's existence.
- **CI keeps using Layer 1.** The daemon is not a CI dependency.
  `renderPreviews` remains the canonical render path for golden-image
  baselines, regression tests, and release builds.

What Layer 1 must **not** gain: auto-spawning a daemon, or reading from
a daemon to satisfy `renderPreviews`.

## Layer 2 — what the daemon owns

- **Daemon runtime code lives only in `:daemon:core` and the two
  per-target modules** (`:daemon:android`, `:daemon:desktop`). The
  Gradle plugin does not import these modules.
- **Daemon ↔ Gradle plumbing is a one-way file handoff.** The
  `composePreviewDaemonStart` task writes a launch descriptor JSON
  file. The daemon reads that file at boot. No bi-directional API.
- **Daemon does not call back into Gradle** at runtime. Daemons fail to
  a `classpathDirty` notification + graceful exit when the world
  changes underneath them. The client is responsible for restart.
- **Daemon trust model is parent-PID-bound, stdio-only.** No network
  sockets, no listeners.

What Layer 2 *may* be consumed by:

- The VS Code extension's `daemonClient.ts`.
- The harness in `:daemon:harness`.
- Layer 3's MCP server, as a JSON-RPC client.
- The CLI as a **local library** for renderer-agnostic helpers from
  `:daemon:core` only (protocol DTOs, known-device catalog,
  override-field names, local history value types).

## CLI local-library boundary

Two valid daemon-adjacent modes:

1. **Local-library mode** for pure data. The command imports
   `:daemon:core` classes directly. `compose-preview devices` is the
   reference example.
2. **Daemon-client mode** for runtime state. The command obtains a
   launch descriptor from Gradle and connects to a daemon over JSON-RPC.
   Required for render queues, live preview state, data products that
   require a render, interactive sessions.

Prefer local-library mode for repeated one-shot CLI workflows. Starting
a daemon per CLI invocation can easily cost more than the work being
asked for. When a command needs daemon state, prefer amortized
daemon-client access (connect to a daemon already started by VS
Code/MCP, or to a long-lived supervisor).

Allowed CLI compile-time dependencies:

- `:daemon:core` for protocol/catalog/history DTOs and other
  renderer-agnostic helpers.
- `:mcp` for the bundled `compose-preview mcp serve` entry point.
- Data-product `core` modules when renderer-agnostic.

Forbidden CLI compile-time/runtime dependencies (Layer-2-only DTO):

- `:daemon:android` and `:daemon:desktop`.
- `:renderer-android` and `:renderer-desktop`.
- Any connector module that exists only to adapt renderer output into
  daemon runtime hooks, unless it has first been split into a reusable
  `core` module.

The `:cli:checkCliDaemonLibraryBoundary` verification task enforces
this on the CLI runtime classpath.

## Layer 3 — what the MCP server owns

- **MCP code lives only in `:mcp`.** Neither `:daemon:core` nor any
  per-target daemon module imports the Kotlin MCP SDK or Ktor.
- **MCP server is a JSON-RPC client of the daemon.** It uses the same
  `Messages.kt` types and framing the daemon serves.
- **MCP server is a separate process by default.** Same-process mode
  is allowed but explicit: a deliberate launch flag, implemented with
  the same JSON-RPC link over an in-memory transport.

What Layer 3 *may* depend on:

- `:daemon:core` for the wire-format types only (`Messages.kt`).

What Layer 3 must **not** depend on:

- `:daemon:android` or `:daemon:desktop`.
- `:gradle-plugin`. The MCP server invokes Gradle (or the CLI bootstrap
  path) as a subprocess.

## Integration seams

These are the only places the layers talk to each other.

| Seam | Direction | Mechanism | Owner |
|------|-----------|-----------|-------|
| `composePreview.daemon { enabled }` DSL | user → L1 | Gradle DSL property | L1 |
| `composePreviewDaemonStart` task → launch descriptor JSON | L1 → L2 | file in `build/preview-daemon/launch.json` | L1 emits, L2 reads |
| Daemon JSON-RPC over stdio | L2 ↔ extension/supervisor | `PROTOCOL.md` | L2 |
| MCP wire format ↔ daemon JSON-RPC translation | L3 ↔ L2 | `:mcp` shim | L3 |
| `:daemon:core` `Messages.kt` types | shared by L2 + L3 | Kotlin data classes | L2 |
| `:daemon:core` catalog/protocol helpers → CLI | L2 → L1 CLI | local-library dependency, no daemon process | L1 CLI |
| `composePreviewDaemonStart` → spawn-daemon helper used by L3's `DaemonSupervisor` | L3 → L1 | shells out to Gradle | L3 |

If a future change wants to add a new seam, it goes here first. If it
can't be expressed as one of "DSL property, file handoff, JSON-RPC
exchange, shared wire-format type", it's probably a layering violation.

## Forbidden imports

Cited from `PreviewIndex.kt`, `IncrementalDiscovery.kt` as
"Layer-2-only DTO":

- Layer 1 / CLI must not import from `:daemon:android`, `:daemon:desktop`,
  `:renderer-android`, `:renderer-desktop`.
- Layer 3 (`:mcp`) must not import from `:daemon:android`,
  `:daemon:desktop`, or `:gradle-plugin`.
- Layer 2 must not import the Kotlin MCP SDK or Ktor.

## Removal procedures

**Removing Layer 3 (MCP):**
- Delete `:mcp` and its task wiring.
- No other module imports it.
- Daemon and Layer 1 unaffected.

**Removing Layer 2 (daemon):**
- Delete `:daemon:core`, `:daemon:android`, `:daemon:desktop`,
  `:daemon:harness`, `:mcp`.
- Move or replace CLI commands that use local-library helpers from
  `:daemon:core`.
- Remove the `daemon` DSL block and the `composePreviewDaemonStart` task
  registration from `:gradle-plugin`.
- The base `composePreview { … }` DSL and `renderPreviews` task work
  unchanged. CI still passes.

**Adding a fourth layer (hypothetical, e.g. a web UI):**
- New module under `:tools:` or a sibling.
- Layer 4 either consumes Layer 2 directly (over JSON-RPC) or Layer 3
  (over MCP).
- Does not modify Layer 1 or Layer 2.

## Complexity boundaries

- **No `if (daemonAvailable) … else …` branches in Layer 1 code.**
- **No "smart" daemon auto-spawn.**
- **No conditional MCP imports in the daemon.** Layer 2 has no
  awareness of whether an MCP server is talking to it.
- **One protocol owner.** [PROTOCOL.md](PROTOCOL.md) is owned by Layer
  2. MCP wire shape is owned by Layer 3.
- **Tests live in the layer they test.** Layer 1 unit tests don't spin
  up a daemon. Layer 2 harness tests don't speak MCP. Layer 3 tests use
  `ChannelTransport` to drive the MCP server in-process with a fake
  `DaemonClient`.

## Cross-references

- [DESIGN.md](DESIGN.md) — Layer 2 architecture in full.
- [MCP.md](MCP.md) — Layer 3 architecture overview.
- [MCP-KOTLIN.md](MCP-KOTLIN.md) — Layer 3 implementation.
- [PROTOCOL.md](PROTOCOL.md) — the L2 ↔ L3 wire contract.
- [CONFIG.md](CONFIG.md) — `composePreview.daemon { … }` DSL reference.
