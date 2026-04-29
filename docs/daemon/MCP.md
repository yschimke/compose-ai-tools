# Preview daemon — MCP server design

> **Status:** design proposal. No implementation shipped. Researched
> against [MCP spec 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/server/resources).
> Cross-referenced from [DESIGN.md](DESIGN.md) and
> [PROTOCOL.md](PROTOCOL.md). Out of scope for v1; this captures the
> shape so we don't paint ourselves into a corner.

## Why

An AI agent that wants to "see" what a `@Preview` renders today has
two unappealing options:

1. **Run Gradle.** Invoke `:samples:android:renderAllPreviews`, wait
   for cold-config + sandbox-init + render — 10s+ end to end. Useless
   for an iterative agent loop.
2. **Read PNGs from disk.** Assume a known render-history directory
   exists and is fresh. No way to request a re-render or know when one
   completes.

The preview daemon already solves both for VS Code over JSON-RPC.
Exposing it as an MCP server lets any MCP-aware agent — Claude Code,
the SDK, a custom agent — render previews on demand and receive
notifications when bytes change.

## Push, not poll: how MCP actually does it

This was the user's load-bearing question. **MCP supports server →
client notifications.** Two relevant capabilities under the
`resources` namespace, both optional, both standard:

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

Plus the generic JSON-RPC notification facilities MCP inherits:
progress notifications (`notifications/progress` for long-running
tool calls), logging messages, and sampling/elicitation flows.

**Transports that support push**: stdio (the default for local
servers), SSE, and the newer streamable-HTTP transport. All of these
hold a persistent connection. **No polling required for any of them.**
A long-running MCP server keeps the channel open and pushes
notifications as state changes. Pure HTTP without SSE would force
polling — but that's a transport choice, not an MCP-spec choice, and
no one builds MCP servers that way.

For the daemon, **stdio** is the natural fit: the daemon already
speaks JSON-RPC over stdio for VS Code. An MCP variant of the
daemon's protocol layer would reuse the same framing (LSP-style
`Content-Length`).

## Daemon ↔ MCP surface mapping

The daemon's [PROTOCOL.md v1](PROTOCOL.md) maps cleanly onto MCP
primitives.

### Resources — one per `@Preview`

Each preview becomes an MCP `Resource`:

```jsonc
{
  "uri": "compose-preview://samples-android/com.example.app.RedSquare?config=phone-portrait",
  "name": "RedSquare",
  "title": "Red square preview",
  "mimeType": "image/png",
  "size": 4218,
  "annotations": {
    "audience": ["user", "assistant"],
    "priority": 0.6,
    "lastModified": "2026-04-29T10:23:51Z"
  }
}
```

- **URI scheme**: custom `compose-preview://`. Avoids `file://` because
  the on-disk path is implementation-detail; the daemon owns the
  rendering and may produce bytes without ever writing a file.
- **Module + preview ID**: `compose-preview://<module-id>/<preview-fqn>?config=<qualifier-string>`.
  Module-id matches the launch descriptor's `modulePath`
  (`:samples:android` etc.); the qualifier suffix lets one
  `@Preview` with multiple device variants be addressed independently.
- **Content**: binary PNG via the standard `blob` field (base64).

`resources/list` returns every preview the daemon currently knows
about — same data the existing `discoveryUpdated` notification carries.
Pagination via the standard cursor mechanic for large modules.

`resources/read` triggers a render. If the preview's PNG is current
(no `fileChanged` since the last render), return the cached bytes
immediately. Otherwise enqueue a render and block until
`renderFinished` arrives — same drain semantics the daemon already
honours (no mid-render cancellation; PROTOCOL.md § 3 + DESIGN.md § 9).

### Subscriptions — push instead of poll

Client subscribes to a preview's URI:

```jsonc
{ "jsonrpc": "2.0", "id": 7, "method": "resources/subscribe",
  "params": { "uri": "compose-preview://samples-android/com.example.app.RedSquare" } }
```

The daemon's existing wire signal — `renderFinished({ id, pngPath, … })`
— maps to:

```jsonc
{ "jsonrpc": "2.0", "method": "notifications/resources/updated",
  "params": { "uri": "compose-preview://samples-android/com.example.app.RedSquare" } }
```

The client's next `resources/read` returns fresh bytes.

`discoveryUpdated` (the daemon's "set of previews changed" signal,
emitted on Tier 2 incremental rescan) maps to:

```jsonc
{ "jsonrpc": "2.0", "method": "notifications/resources/list_changed" }
```

`classpathDirty` (B2.1's daemon → "the world has fundamentally
changed, restart me") doesn't have a clean MCP analogue. The cleanest
mapping is to **exit the MCP server with a non-zero code**; MCP
clients treat a closed connection as "server died, reconnect" and
re-`initialize`. For interactive clients we could also push a
`notifications/message` (the MCP logging notification) before exit so
the user sees *why* the server is going away.

`renderFailed` maps to `notifications/message({ level: "error", … })`
on the subscribed URI's logging channel. The resource itself stays in
the list; the client sees "render failed" as a log event and can
choose to retry (`resources/read` again) or move on.

### Tools — explicit user actions

Pure-`renderNow` style "force a fresh render even if cached" is a tool:

```jsonc
{
  "name": "render_preview",
  "description": "Re-render a preview, bypassing the cache. Returns the rendered PNG.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "uri":  { "type": "string", "description": "compose-preview:// URI" },
      "tier": { "type": "string", "enum": ["fast", "full"], "default": "full" }
    },
    "required": ["uri"]
  }
}
```

Other tools worth considering:

- `discover_previews(module)` — explicit list-now. Mostly redundant with
  `resources/list`, but lets an agent ask for "everything in this
  module" without traversing the resource list.
- `list_modules()` — what modules have a live daemon. Useful when the
  MCP server multiplexes across multiple per-module daemons.
- `set_focus(uri)` — agent declaring "I'm about to read this preview;
  prioritise its render over background work". Maps to the daemon's
  `setFocus` notification. Mostly relevant if the agent is rendering a
  large set and wants to express interactive priority. Could be
  inferred automatically from the most-recent `resources/read` — start
  there.

`setVisible` doesn't have a clean MCP analogue — an agent doesn't
have a viewport. Probably skip in v1; reactive priority alone (focus =
most-recently-read) is enough.

### Prompts — the "look at this preview" pattern

A small `prompts` namespace that templates a "review this preview" or
"compare these two previews side-by-side" agent loop. Optional;
not load-bearing for the core mapping.

## Architecture options

### Option A — separate MCP server process, JSON-RPC client of the daemon

```
MCP client ── stdio ──▶ MCP server ── stdio JSON-RPC ──▶ daemon JVM
                       (Kotlin/JVM             (existing)
                        or TypeScript)
```

The MCP server is a thin shim that translates MCP wire shape ↔ daemon
PROTOCOL.md. Reuses the existing daemon. Multiplexes potentially
multiple per-module daemons behind one MCP surface.

**Pros**: clean separation; the daemon stays a JSON-RPC server with
no MCP-specific code. The MCP server can be in TypeScript (matching
the VS Code extension's existing `daemonClient.ts`) or Kotlin (matching
the daemon's existing JsonRpcServer + Messages.kt). MCP server is
stateless modulo subscription bookkeeping.

**Cons**: two processes; lifecycle gets fiddlier (who spawns the
daemon? does the MCP server own it? what happens on classpathDirty?).
Wire-format translation cost (negligible at the message-rate the
daemon operates at).

### Option B — daemon learns an MCP mode

```
MCP client ── stdio ──▶ daemon JVM (PROTOCOL.md or MCP, decided at startup)
```

The daemon's `JsonRpcServer` gains a `--mcp` flag that switches the
wire format from PROTOCOL.md to MCP semantics. Same framing
(`Content-Length`), different message names + capability handshake +
notification shapes.

**Pros**: one process. Daemon's existing render loop, sandbox holding,
classloader handling all transparent to the wire-format choice.

**Cons**: couples the daemon to MCP's evolution. Two protocol surfaces
to maintain in lockstep. The renderer-agnostic surface invariant
(DESIGN § 4) gets murkier — MCP isn't strictly renderer-agnostic but
it's not Compose-specific either, so probably still fine.

### Option C — VS Code extension exposes the MCP surface

```
MCP client ── stdio ──▶ vscode-extension MCP server ── existing daemonClient.ts ──▶ daemon JVM
```

The existing TypeScript `daemonClient.ts` from Stream C already speaks
PROTOCOL.md to a daemon. Wrap it in an MCP-server adapter; expose via
VS Code's existing MCP-server publishing mechanism. Reuses everything
the extension already does (gating, multi-module, fallback to Gradle).

**Pros**: zero work on the JVM side; reuses existing TypeScript code.
**Cons**: requires VS Code as the host. Doesn't help non-VS Code
agent setups (Claude Code CLI, custom MCP harnesses).

### Recommendation

**Option A for v1** — separate Kotlin MCP server process that JSON-RPC-
clients the existing daemon. Reasons:

- Doesn't constrain the daemon's evolution to MCP's spec cadence.
- Doesn't require VS Code (works for headless agent loops, CI, and
  the harness's eventual MCP integration test).
- Reuses `:daemon:core`'s `Messages.kt` types verbatim — the
  shim translates *between* two strongly-typed Kotlin schemas, which
  is a tiny amount of code (~300 LOC).
- The shim's renderer-agnosticism is automatic since
  `:daemon:core` is.

**Option C as a follow-up** for VS Code-hosted MCP. Lives alongside
Option A, doesn't replace it.

**Option B** stays available if A turns out to be too much shim for
the value; revisit with data, not speculation.

## Lifecycle

### Spawn

The MCP server's `initialize` handshake captures workspace + module
selection from the client (or its environment — `cwd` typically). For
each module the client wants previews from, the MCP server runs
`composePreviewDaemonStart` once (the existing Gradle task that emits
the launch descriptor) and spawns the daemon. Multi-module: one
daemon JVM per module, multiplexed behind one MCP surface.

### `classpathDirty` / daemon death

When the daemon emits `classpathDirty` and exits, the MCP server has
two options:

1. **Eager re-bootstrap** — spawn a fresh daemon for that module
   immediately. Push `notifications/resources/list_changed` so clients
   re-list (the in-flight resource set may have shifted).
2. **Lazy re-bootstrap** — emit `notifications/message({ level: "warn",
   data: "module classpath changed, daemon restarting" })`, wait for
   the next tool call to trigger respawn.

v1 should pick eager — simpler. Lazy is an optimisation if cold spawn
becomes a measurable cost.

If the daemon dies for non-`classpathDirty` reasons (crash, OOM, etc.),
the MCP server logs `notifications/message({ level: "error", … })`
and respawns once. Repeated crashes — back off and surface an error
on the next tool call.

### MCP client disconnect

The MCP server gracefully shuts down its daemon JVMs (sends
`shutdown` + `exit`, awaits drain) before exiting itself. Mirrors
the desktop daemon's existing SIGTERM handler from B-desktop.1.6.

## Caching & resource freshness

The daemon already maintains preview PNGs on disk. The MCP server's
caching layer is then trivial:

1. `resources/read({ uri })` checks a per-preview "current PNG mtime"
   cache.
2. If the PNG mtime is recent enough (no `fileChanged` since the
   last successful render), return cached bytes.
3. Otherwise force a render via the daemon's `renderNow`, await
   `renderFinished`, return fresh bytes.

The "freshness" threshold is the daemon's own staleness cascade
(DESIGN § 8). The MCP server doesn't reimplement it; it just asks the
daemon "is this current?" implicitly by always preferring a fresh
render when subscriptions are active.

## Security & trust model

- The MCP server inherits the daemon's existing trust model:
  parent-PID-bound, no network sockets, stdio only.
- Resource URIs are validated to match the `compose-preview://` scheme
  + a known module ID + a known preview FQN. URI fuzzing doesn't reach
  arbitrary files.
- `resources/read` returns rendered PNG bytes — not source files,
  not project metadata. Read access is bounded to the daemon's render
  surface.
- No write operations exposed via tools in v1. (`render_preview` writes
  PNGs to the daemon's render directory but the client only sees
  bytes back, not write permissions.)

## What this isn't

- **Not a replacement for the VS Code path.** PROTOCOL.md v1 stays the
  authoritative wire format for VS Code; MCP is an additional surface
  for agent loops.
- **Not a renderer rewrite.** Same daemon, same render engines, same
  classloader story. Only the protocol-translation shim is new.
- **Not implemented.** This doc captures the shape; the work lands as
  a Phase 3+ task once the underlying daemon is stable enough to
  expose.

## Decisions to surface

1. **Architecture: A, B, or C** for v1. Recommendation: A. User's call.
2. **URI scheme** — `compose-preview://` (cleanly custom, fits MCP's
   URI guidance) vs `file://` (uses standard scheme but couples to
   on-disk paths). Recommend custom.
3. **Multi-module multiplexing** — one MCP server multiplexing
   per-module daemons (recommended) vs one MCP server per daemon
   (simpler but harder for the agent to reason about).
4. **`classpathDirty` UX** — eager re-bootstrap (recommended) vs
   lazy. Affects cold-spawn-cost vs invariant-stability trade.
5. **Tools surface scope** — minimal (`render_preview` only) or rich
   (`set_focus`, `list_modules`, `discover_previews` as explicit
   tools). Recommend minimal v1; add tools as agent loops surface
   actual needs.
6. **Subscriptions opt-in by default?** — clients that don't
   explicitly subscribe still get fresh bytes via `resources/read`
   (they just don't get push notifications). v1 should advertise
   `subscribe: true` so capable clients can use it; clients that
   don't care simply ignore the capability.

## Cross-references

- [PROTOCOL.md](PROTOCOL.md) — v1 daemon wire format the MCP shim
  translates from.
- [DESIGN.md](DESIGN.md) — daemon architecture; MCP server is one of
  the "consumers" enumerated in § 4.
- [TEST-HARNESS.md](TEST-HARNESS.md) — pattern for an integration test
  spawning the MCP shim as a subprocess + driving via JSON-RPC, once
  the implementation lands.
- [MCP spec — Resources](https://modelcontextprotocol.io/specification/2025-06-18/server/resources)
  — canonical reference for `resources/subscribe`, `notifications/resources/updated`,
  and capability negotiation.
