# Layering — keeping CLI, daemon, and MCP additive

> **Status:** active architectural rule. The daemon and MCP server
> ([DESIGN.md](DESIGN.md), [MCP.md](MCP.md), [MCP-KOTLIN.md](MCP-KOTLIN.md))
> have shipped without entangling the existing Gradle/CLI paths because
> these constraints held. Mandatory reading before adding a new
> cross-layer hook.

## What this doc fixes

The project today has one path that works end-to-end: invoke the
Gradle `renderPreviews` task (or its `:samples:android:renderAllPreviews`
variant) from VS Code, the CLI, or an agent — get PNGs out. We're
about to add two more capabilities:

1. **A persistent preview daemon** ([DESIGN.md](DESIGN.md)) that holds
   a hot Robolectric sandbox to make per-save refresh sub-second.
2. **An MCP server** ([MCP.md](MCP.md), [MCP-KOTLIN.md](MCP-KOTLIN.md))
   that exposes preview rendering to MCP-aware agents with push
   notifications instead of polling.

The trap is that "add" turns into "entangle". A bug in MCP becomes a
bug in the Gradle path. A daemon flag changes CLI behaviour. The
existing simple flow — Gradle config → render — accumulates branching
logic for cases the user never asked for. This document defines the
isolation rules that prevent that.

## The three layers

```
   ┌──────────────────────────────────────────────────────────────┐
   │ Layer 3 — MCP server                          [opt-in, v2+]  │
   │   :mcp — top-level module, separate process by default.      │
   │   default. JSON-RPC client of Layer 2.                       │
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
   │   :gradle-plugin renderPreviews task; existing samples;      │
   │   the CLI shipped today. Authoritative for CI.               │
   └──────────────────────────────────────────────────────────────┘
```

**The layers are strictly additive.** Layer 1 works with Layer 2 and
Layer 3 absent. Layer 2 works with Layer 3 absent. Layer 3 cannot
exist without Layer 2.

**Each layer lives in its own modules.** No layer's code compiles
into another layer's modules. The integration seams are listed
explicitly in § Integration seams below — anything not on that list
is forbidden.

## Layer 1 — what stays untouched

The Gradle `renderPreviews` task and CLI binary that exist today are
the contract for "the simple way". Constraints:

- **No daemon requirement for CI.** `renderPreviews` remains the
  existing behaviour and does not require a running daemon. Editor
  integrations use the top-level `daemon { … }` DSL, which defaults to
  enabled and can be disabled temporarily.
- **No daemon code on the Gradle plugin classpath.** `:gradle-plugin`
  does not depend on `:daemon:core`. The daemon-bootstrap task the
  plugin registers (`composePreviewDaemonStart`) emits a descriptor
  file; it does not import or instantiate anything from the daemon
  modules.
- **CLI daemon-library access is narrow and renderer-agnostic.**
  `:cli` may depend on `:daemon:core` for pure catalog/protocol helpers
  such as `DeviceDimensions`, `KnownDevice`, protocol DTOs, and local
  history models. The CLI must not depend on `:daemon:android`,
  `:daemon:desktop`, `:renderer-android`, or `:renderer-desktop`.
- **No conditional branches in `renderPreviews`** for "is the daemon
  running?". The Gradle task path is unaware of the daemon's
  existence. (The daemon can read state Gradle wrote; Gradle does not
  read state the daemon wrote.)
- **CI keeps using Layer 1.** The daemon is not a CI dependency.
  `renderPreviews` remains the canonical render path for golden-image
  baselines, regression tests, and release builds.

What Layer 1 gained for editor integrations:

- A `composePreview.daemon { enabled = true ; … }` DSL block that
  emits the `composePreviewDaemonStart` launch descriptor. When
  disabled, the descriptor is still written with `"enabled": false`
  so editor clients can warn and use the Gradle path temporarily.
- A new task `composePreviewDaemonStop` symmetric to `…Start`.
  Optional; only makes sense for editor-supervised daemon sessions.

What Layer 1 must **not** gain:

- Auto-spawning a daemon "if helpful". User opts in explicitly.
- Reading from a daemon to satisfy `renderPreviews`. The Gradle path
  renders from cold, every time — that is its purpose.

## Layer 2 — what the daemon owns

The daemon is a separate JVM started by an explicit task. It speaks
JSON-RPC ([PROTOCOL.md](PROTOCOL.md)) to one client at a time over
stdio. Constraints:

- **Daemon runtime code lives only in `:daemon:core` and the two
  per-target modules** (`:daemon:android`, `:daemon:desktop`). The
  Gradle plugin does not import these modules. The CLI may import
  renderer-agnostic `:daemon:core` catalog/protocol helpers, but it
  must not instantiate daemon runtime services or renderer hosts.
- **Daemon ↔ Gradle plumbing is a one-way file handoff.** The
  `composePreviewDaemonStart` task writes a launch descriptor JSON
  file (classpath, JVM args, target). The daemon reads that file at
  boot. No bi-directional Gradle ↔ daemon API.
- **Daemon does not call back into Gradle** at runtime. Daemons fail
  to a `classpathDirty` notification + graceful exit when the world
  changes underneath them.
  The client (extension or supervisor) is responsible for restart.
- **Daemon trust model is parent-PID-bound, stdio-only.** No network
  sockets, no listeners. Layer 3's MCP server, if used, is the thing
  that exposes a network surface — Layer 2 itself does not.

What Layer 2 *may* be consumed by:

- The VS Code extension's `daemonClient.ts` (Stream C work).
- The harness in `:daemon:harness`.
- Layer 3's MCP server, as a JSON-RPC client.
- The CLI as a **local library** for pure, renderer-agnostic helpers
  from `:daemon:core` only. Examples: protocol DTOs, known-device
  catalog entries, override-field names, and local file-backed history
  value types.

What Layer 2 must **not** be consumed by:

- `renderPreviews`. (See Layer 1 constraint above.)
- The CLI as an embedded daemon runtime. Commands that need module or
  runtime state must either use the existing Gradle task path or client
  a daemon process through the protocol; they must not call renderer
  implementations in-process.

## CLI local-library boundary

The CLI has two valid daemon-adjacent modes:

1. **Local-library mode** for pure data. The command imports
   `:daemon:core` classes directly and does not run Gradle or spawn a
   daemon. `compose-preview devices` is the reference example: it reads
   `DeviceDimensions` and emits protocol-shaped `KnownDevice` rows.
2. **Daemon-client mode** for runtime state. The command obtains a
   launch descriptor from Gradle and connects to a daemon process through
   JSON-RPC using [PROTOCOL.md](PROTOCOL.md). It may start the daemon
   when explicitly asked, but the efficient path for repeated CLI calls is
   to attach to an already-running daemon or supervisor. This is required
   for render queues, live preview state, data products that require a
   render, interactive sessions, and backend-specific capabilities.

Prefer local-library mode for repeated one-shot CLI workflows. Starting a
daemon per CLI invocation can easily cost more than the work being asked
for, especially in scripts that call the CLI several times. Before adding a
daemon-backed CLI command, first ask whether the needed data product can be
split into a reusable `core` model/producer and consumed from the CLI after
the existing Gradle render path has written its files.

When a command does need daemon state, prefer amortized daemon-client
access over per-command startup:

- connect to a daemon already started by VS Code, MCP, or an explicit CLI
  warmup command;
- or connect to a long-lived supervisor that owns daemon lifecycles across
  modules/workspaces.

Hosting that supervisor in the MCP process is allowed as an optimization:
the CLI still talks over a protocol boundary and does not import renderer
implementations. The hosting choice must be treated as lifecycle/packaging,
not as permission for CLI code to reach into daemon internals. A Unix
domain socket is a plausible local transport for that future mode, but it
is not part of the foundation contract; stdio JSON-RPC and local-library
access remain the supported baseline until a concrete repeated-call
workflow proves the extra lifecycle surface is worth it.

Allowed CLI compile-time dependencies:

- `:daemon:core` for protocol/catalog/history DTOs and other
  renderer-agnostic helpers.
- `:mcp` for the bundled `compose-preview mcp serve` entry point.
- Data-product `core` modules when they are renderer-agnostic or expose
  reusable file/model helpers for outputs the Gradle path already writes.

Forbidden CLI compile-time/runtime dependencies:

- `:daemon:android` and `:daemon:desktop`.
- `:renderer-android` and `:renderer-desktop`.
- Any connector module that exists only to adapt renderer output into
  daemon runtime hooks, unless it has first been split into a reusable
  `core` module.

The `:cli:checkCliDaemonLibraryBoundary` verification task enforces the
renderer-module part of this rule on the CLI runtime classpath.

## Layer 3 — what the MCP server owns

The MCP server is a thin JSON-RPC ↔ MCP translation shim, in its own
module `:mcp`. Constraints:

- **MCP code lives only in `:mcp`.** Neither
  `:daemon:core` nor any per-target daemon module imports
  the Kotlin MCP SDK or Ktor. The daemon stays a JSON-RPC server
  with no MCP awareness.
- **MCP server is a JSON-RPC client of the daemon.** It uses the same
  `Messages.kt` types and framing the daemon serves. Translation
  happens at the MCP boundary; below that, everything is daemon-
  native.
- **MCP server is a separate process by default.** Spawned
  independently from the daemon, possibly multiplexing across multiple
  per-module daemons. The cross-process boundary is what enforces the
  module separation at runtime.
- **Same-process mode is allowed but explicit.** The user has noted
  there may be reasons to run MCP and daemon co-located in one JVM
  (resource cost, packaging convenience). When this happens, it must
  be:
  - A deliberate launch mode flag (`--embed-daemon` or equivalent),
    not the default.
  - Implemented with the *same* `:mcp` ↔ daemon
    JSON-RPC link, just over an in-memory channel transport instead
    of stdio. The MCP-side code never reaches into daemon internals
    even when colocated.
  - Documented as a packaging choice, not an architectural one. From
    the daemon's perspective, the MCP module is still an external
    JSON-RPC client.

What Layer 3 *may* depend on:

- `:daemon:core` for the wire-format types only (`Messages.kt`).
  Specifically: it reads the same Kotlin data classes the daemon
  serializes, so JSON parsing stays type-safe. It does not reach into
  the daemon's render engine, sandbox holder, or classloader code.

What Layer 3 must **not** depend on:

- `:daemon:android` or `:daemon:desktop` (the
  per-target render engines). These are concrete renderers; the MCP
  server treats them as opaque processes spawned via `composePreviewDaemonStart`.
- `:gradle-plugin`. The MCP server invokes Gradle (or the existing
  CLI bootstrap path) as a subprocess; it does not embed the plugin.

## Integration seams — the whole list

These are the only places the layers talk to each other. Anything
not on this list is a layering violation.

| Seam | Direction | Mechanism | Owner |
|------|-----------|-----------|-------|
| `composePreview.daemon { enabled }` DSL | user → L1 | Gradle DSL property | L1 |
| `composePreviewDaemonStart` task → launch descriptor JSON | L1 → L2 | file in `build/preview-daemon/launch.json` | L1 emits, L2 reads |
| Daemon JSON-RPC over stdio | L2 ↔ extension/supervisor | `PROTOCOL.md` | L2 |
| MCP wire format ↔ daemon JSON-RPC translation | L3 ↔ L2 | `:mcp` shim | L3 |
| `:daemon:core` `Messages.kt` types | shared by L2 + L3 | Kotlin data classes | L2 |
| `:daemon:core` catalog/protocol helpers → CLI | L2 → L1 CLI | local-library dependency, no daemon process | L1 CLI |
| `composePreviewDaemonStart` → spawn-daemon helper used by L3's `DaemonSupervisor` | L3 → L1 | shells out to Gradle | L3 |

If a future change wants to add a new seam, it goes here first. If
it can't be expressed as one of "DSL property, file handoff, JSON-RPC
exchange, shared wire-format type", it's probably a layering violation
in disguise.

## Removal procedures

Each layer can be removed without breaking the layers below it.

**Removing Layer 3 (MCP):**
- Delete `:mcp` and its task wiring.
- No other module imports it. No other module references its types.
- Daemon and Layer 1 unaffected.

**Removing Layer 2 (daemon):**
- Delete `:daemon:core`, `:daemon:android`,
  `:daemon:desktop`, `:daemon:harness`,
  `:mcp`.
- Move or replace CLI commands that intentionally use local-library
  helpers from `:daemon:core` (for example the known-device catalog).
- Remove the `daemon` DSL block and the
  `composePreviewDaemonStart` task registration from `:gradle-plugin`.
- The base `composePreview { … }` DSL and `renderPreviews` task work
  unchanged. CI still passes.

**Adding a fourth layer (hypothetical, e.g. a web UI):**
- New module under `:tools:` or a sibling.
- Layer 4 either consumes Layer 2 directly (over JSON-RPC) or Layer 3
  (over MCP).
- It does not modify Layer 1 or Layer 2.

## Complexity boundaries

Concrete rules to keep the existing paths simple:

- **No `if (daemonAvailable) … else …` branches in Layer 1 code.**
  Layer 1 does its work as if Layer 2 doesn't exist. (Layer 1 may
  emit data Layer 2 *consumes*, but that's a one-way producer
  relationship, not a branching consumer.)
- **No "smart" daemon auto-spawn.** Daemon presence is opt-in via the
  DSL flag. The plugin does not detect "the user might benefit from a
  daemon" and start one.
- **No conditional MCP imports in the daemon.** Layer 2 has no
  awareness of whether an MCP server is talking to it; MCP traffic
  arrives as ordinary JSON-RPC, and the daemon answers it the same
  way it would answer the VS Code extension.
- **One protocol owner.** [PROTOCOL.md](PROTOCOL.md) is owned by
  Layer 2. MCP wire shape is owned by Layer 3. Neither edits the
  other's spec without an explicit cross-layer change.
- **Tests live in the layer they test.** Layer 1 unit tests don't
  spin up a daemon. Layer 2 harness tests
  ([TEST-HARNESS.md](TEST-HARNESS.md)) don't speak MCP. Layer 3
  tests use `ChannelTransport` to drive the MCP server in-process
  with a fake `DaemonClient`.

## What this enables

- A user who only uses Gradle/CI never compiles or downloads the
  daemon or MCP code. The plugin's footprint stays small.
- A user who opts into the daemon for VS Code never compiles the
  MCP module.
- An agent author who wants MCP gets it via `:mcp` —
  one extra module to publish, one extra process to spawn, no
  changes to anyone else's flow.
- The "may eat your laundry" daemon rollout (see DESIGN § 17) doesn't
  threaten Layer 1's reliability. If the daemon proves unworkable,
  it's deleted as one self-contained excision.

## Cross-references

- [DESIGN.md](DESIGN.md) — Layer 2 architecture in full.
- [MCP.md](MCP.md) — Layer 3 architecture overview; this doc pins
  Option A's recommendation as the layering-correct choice.
- [MCP-KOTLIN.md](MCP-KOTLIN.md) — Layer 3 Kotlin/Ktor implementation.
- [PROTOCOL.md](PROTOCOL.md) — the L2 ↔ L3 wire contract.
- [CONFIG.md](CONFIG.md) — `composePreview.daemon { … }` DSL reference,
  the user-visible Layer 1 ↔ Layer 2 seam.
