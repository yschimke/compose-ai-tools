# compose-preview-mcp

A [Model Context Protocol](https://modelcontextprotocol.io/) server that
exposes [`@Preview`](https://developer.android.com/jetpack/compose/tooling/previews)
composables — Jetpack Compose (Android via Robolectric) and Compose
Multiplatform (Desktop via Skiko) — to MCP-aware agents over stdio.

The server is a thin, transport-agnostic shim around the per-module
[preview daemon](../docs/daemon/DESIGN.md): it spawns daemons on demand,
routes their wire notifications into the MCP resource / subscription model,
and lets a single MCP-aware agent host previews from many distinct projects
(or worktrees of the same repo) at once.

## What it gives an agent

- **Resources** — every `@Preview` is a [`compose-preview://`
  URI](src/main/kotlin/ee/schimke/composeai/mcp/PreviewResource.kt) the
  agent can `resources/list` / `resources/read` to fetch a fresh PNG.
  History entries are exposed via a sibling
  `compose-preview-history://` scheme.
- **Subscriptions** — push `notifications/resources/updated` whenever a
  preview re-renders, and `notifications/resources/list_changed` when
  the discovery set or history grows. No polling.
- **Tools** — `register_project`, `watch`, `set_focus`, `render_preview`,
  `notify_file_changed`, `history_list`, `history_diff`, plus the obvious
  inspectors. See [`Tool reference`](#tool-reference) below.
- **Multi-workspace** — one MCP server, many registered projects /
  worktrees, identified by stable `<rootProjectName>-<8-char-path-hash>`
  ids. Two worktrees of the same repo come up as distinct workspaces by
  construction.
- **Progress notifications** — opt in via `_meta.progressToken` on
  `resources/read` to get a periodic beat for slow renders.

The MCP surface is documented at protocol level in
[`docs/daemon/MCP.md`](../docs/daemon/MCP.md) and at implementation level
in [`docs/daemon/MCP-KOTLIN.md`](../docs/daemon/MCP-KOTLIN.md).

## Quick start

The CLI bundles `:mcp` so the consumer-facing path is one command —
`compose-preview mcp install` registers the server with every locally
installed agent host it detects (Claude Code, Codex, Antigravity). The
standalone `:mcp:installDist` launcher is the alternative path for embedders
that don't ship the CLI.

### Path A: via the bundled CLI (recommended)

```bash
# Run from the project root. Bootstraps descriptors + previews.json for every
# plugin-applied module, then registers compose-preview-mcp with every locally
# installed agent host. Idempotent — re-running upserts each registration.
compose-preview mcp install

# Verify per-module state.
compose-preview mcp doctor
```

Per-host opt-in/out flags: `--claude` / `--no-claude`, `--codex` /
`--no-codex` / `--codex-config <path>`, `--antigravity` / `--no-antigravity`
/ `--antigravity-config <path>`. Defaults to "on if detected" for each host;
detection rules are documented in
[`skills/compose-preview/design/MCP.md`](../skills/compose-preview/design/MCP.md#setup).

`compose-preview mcp serve` runs the MCP server in-process; status goes
to stderr and stdout is reserved for JSON-RPC framing. If no `--project` is
passed, it defaults to the current Gradle root. Generated host config files
still include an absolute `--project=...` because each host's launch
directory is not project-scoped.

The consumer-facing skill doc is
[`skills/compose-preview/design/MCP.md`](../skills/compose-preview/design/MCP.md);
the PR-review variant (two workspaces, base + head) is
[`skills/compose-preview-review/design/MCP_REVIEW.md`](../skills/compose-preview-review/design/MCP_REVIEW.md).

### Path B: standalone `:mcp:installDist`

Useful when embedding the server outside the CLI launcher (a custom
agent host, a downstream IDE plugin, etc.).

```bash
./gradlew :mcp:installDist
```

Produces `mcp/build/install/compose-preview-mcp/` with a launcher script
(`bin/compose-preview-mcp`) and all runtime jars under `lib/`.

Bootstrap descriptors per module the same way — either via
`compose-preview mcp install` (still the easiest, even when you're not
using its `serve` subcommand) or by hand:

```bash
./gradlew :samples:wear:composePreviewDaemonStart \
          :samples:wear:discoverPreviews
sed -i 's/"enabled": false/"enabled": true/' \
  samples/wear/build/compose-previews/daemon-launch.json
```

The descriptor's `enabled: false` field is load-bearing — flip it to
`true` either in the build script
(`composePreview { daemon { enabled = true } }`) or by
editing the JSON directly. (Direct `-P` propagation is intentionally not
wired; see `DaemonExtension.kt` KDoc for rationale.)

Then attach:

```bash
claude mcp add compose-preview-mcp \
  -- /abs/path/to/mcp/build/install/compose-preview-mcp/bin/compose-preview-mcp \
  --project=/abs/path/to/your-repo
```

`--project=<path>[:<rootProjectName>]` pre-registers a workspace at
startup; you can also call the `register_project` tool from the agent
later.

### 4. From the agent

```jsonc
// Register project (skip if already passed via --project)
{ "method": "tools/call", "params": { "name": "register_project",
  "arguments": { "path": "/abs/path/to/your-repo",
                 "modules": [":samples:wear", ":samples:cmp"] } } }

// Watch every preview in :samples:cmp
{ "method": "tools/call", "params": { "name": "watch",
  "arguments": { "workspaceId": "your-repo-a1b2c3d4",
                 "module": ":samples:cmp" } } }

// Read a preview's PNG
{ "method": "resources/read",
  "params": { "uri": "compose-preview://your-repo-a1b2c3d4/_samples_cmp/com.example.RedSquare" } }

// After editing a source file, tell the daemon
{ "method": "tools/call", "params": { "name": "notify_file_changed",
  "arguments": { "workspaceId": "your-repo-a1b2c3d4",
                 "path": "/abs/path/.../Previews.kt", "kind": "source" } } }
```

## Tool reference

| Tool | Args | Purpose |
|---|---|---|
| `register_project` | `path, rootProjectName?, modules?` | Register a workspace by absolute path. Returns the assigned `workspaceId`. Idempotent. |
| `unregister_project` | `workspaceId` | Tear down all daemons for that workspace. |
| `list_projects` | — | List registered workspaces with paths + branches. |
| `list_devices` | — | List the `@Preview(device = ...)` ids the daemon's catalog recognises with resolved geometry (`widthDp`, `heightDp`, `density`). Use these as the `device` field of `render_preview.overrides` to flip a preview to a catalog device without editing annotations. |
| `render_preview` | `uri, overrides?` | Force-render bypassing cache. Returns PNG inline. `overrides` applies per-call display-property overrides (size, density, locale, fontScale, uiMode, orientation, device) — see PROTOCOL.md § 5. |
| `watch` | `workspaceId, module?, fqnGlob?` | Register an "area of interest" — propagates to daemon's `setVisible`/`setFocus`, fires `resources/updated` per render. Eagerly spawns matching daemons. |
| `unwatch` | `workspaceId?, module?, fqnGlob?` | Remove watches matching the predicate (no args = remove all from this session). |
| `list_watches` | — | This session's registered watches. |
| `set_visible` | `workspaceId, module, ids[]` | Direct-forward to the daemon's `setVisible`. Overrides watch-derived sets until the next propagator recompute. |
| `set_focus` | `workspaceId, module, ids[]` | Direct-forward to the daemon's `setFocus`. |
| `notify_file_changed` | `workspaceId, path, kind?, changeType?` | Forward `fileChanged` to the daemon + re-issue `renderNow` for any watched / subscribed URIs in that workspace. Use after editing source files outside the MCP server's view. |
| `history_list` | `workspaceId, module, previewId?, since?, until?, limit?, branch?, …` | Proxy daemon `history/list`. Each result entry is decorated with its `compose-preview-history://` URI. |
| `history_diff` | `workspaceId, module, from, to` | Proxy daemon `history/diff` (METADATA mode). Cross-source: `from` may live on FS, `to` on a `preview/<branch>` ref. |
| `list_data_products` | `workspaceId?, module?` | List the structured data kinds (a11y findings, a11y hierarchy, layout tree, recomposition heat-map, …) each spawned daemon advertises alongside its PNGs. See [`docs/daemon/DATA-PRODUCTS.md`](../docs/daemon/DATA-PRODUCTS.md) for the catalogue and per-kind schemas. |
| `get_preview_data` | `uri, kind, params?, inline?` | Fetch one data product (e.g. `kind: "a11y/hierarchy"`) for a preview. Returns the per-kind JSON payload. Defaults `inline: true` so the agent gets the JSON inline rather than a sibling-file path. **Cache short-circuit:** when the kind has been subscribed (`subscribe_preview_data`), the latest `renderFinished` payload is served from the supervisor's in-memory cache with zero daemon round-trip — the response carries `cached: true`. Auto-renders the preview if it hasn't rendered yet (no need to call `render_preview` first). Re-render-on-demand kinds may pay a render cost; bounded by the daemon's per-request budget. |
| `subscribe_preview_data` | `uri, kind` | Prime the daemon to compute `kind` on every render of `uri` (sticky-while-visible). Cuts subsequent `get_preview_data` latency. |
| `unsubscribe_preview_data` | `uri, kind` | Drop a subscription. |

## Data products

Beyond the PNG, the daemon can produce structured data alongside each render —
ATF accessibility findings, the a11y semantic hierarchy, the layout tree, a
recomposition heat-map, theme resolution, and so on. Each is identified by a
namespaced *kind* string (`a11y/hierarchy`, `compose/recomposition`, …).

The agent flow is two calls:

```jsonc
// 1. Discover what kinds the daemon advertises (empty list = pre-D2 daemon
//    with no producer wired yet).
{ "method": "tools/call", "params": { "name": "list_data_products",
  "arguments": { "workspaceId": "your-repo-a1b2c3d4" } } }

// 2. Fetch one kind for one preview. The preview must have rendered at least
//    once — otherwise the call returns DataProductNotAvailable and you should
//    `resources/read` (or `render_preview`) first.
{ "method": "tools/call", "params": { "name": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://your-repo-a1b2c3d4/_samples_cmp/com.example.RedSquare",
    "kind": "a11y/hierarchy"
  } } }
```

For long-running flows where the agent expects to ask about the same preview
repeatedly, call `subscribe_preview_data` first — the daemon then computes the
kind on every render of that preview, so the next `get_preview_data` resolves
without paying the re-render cost. Subscriptions auto-drop when the preview
leaves the daemon's `setVisible` set, so re-subscribe when it comes back into
view (or use `set_visible` to keep it warm).

The full kind catalogue, per-kind payload schemas, and re-render semantics are
in [`docs/daemon/DATA-PRODUCTS.md`](../docs/daemon/DATA-PRODUCTS.md).

## URI schemes

Both schemes carry the workspace id as a path segment so a single MCP
server can host previews from multiple distinct projects / worktrees
without ambiguity.

- **Live preview**:
  `compose-preview://<workspace>/<module>/<previewFqn>?config=<qualifier>`.
  `<module>` is the Gradle module path with `:` replaced by `_`
  (`:samples:android` → `_samples_android`). `<previewFqn>` is the same
  id the gradle plugin's `discoverPreviews` task emits (typically
  `<className>.<methodName>` or
  `<className>.<methodName>_<config-name>` for parameterised previews).
- **History entry**:
  `compose-preview-history://<workspace>/<module>/<previewFqn>/<entryId>`.
  `<entryId>` is the stable hash-based id the daemon attaches to each
  render — opaque to MCP, passed back to `history/read` /
  `history/diff` verbatim.

## Resources

`resources/list` enumerates every preview every supervised daemon
currently knows about, plus their history entries on demand. Dynamic:
the catalog updates as `discoveryUpdated` notifications arrive from
spawned daemons; `notifications/resources/list_changed` fires when
the set changes.

`resources/read` returns the PNG inline as a `BlobResourceContents` (mime
`image/png`, base64). On a live URI it triggers a render; on a history
URI it proxies `history/read` with `inline=true`.

`resources/subscribe` per the MCP spec — push
`notifications/resources/updated` for the subscribed URI as it
re-renders.

## Subscriptions and watch sets

Both are session-scoped — a session is one connected MCP client (in v0,
one stdio stream). On disconnect the supervisor drops everything that
session registered.

`subscribe(uri)` is the cheap per-URI hook. `watch(workspaceId,
module?, fqnGlob?)` is the area-of-interest hook — the supervisor
expands it to the matching URI set, forwards as `setVisible`/`setFocus`
to the appropriate daemons (so the daemon's render queue prioritises
those previews), and pushes `resources/updated` per render. Watches
re-expand on `discoveryUpdated`.

## Multi-workspace + worktrees

`WorkspaceId.derive(rootProjectName, path)` is
`<rootProjectName>-<8-hex-chars-of-sha256(canonical-path)>`. The hash
is always present, so two worktrees of the same repo at different
paths get distinct ids by construction. `list_projects` exposes the
absolute path + best-effort branch (read from `.git/HEAD`) so an agent
or a human can disambiguate.

Daemons are owned per `(workspaceId, modulePath)`. Two worktrees of the
same module run two independent daemon JVMs against their own `build/`
output — no cross-contamination of PNGs or caches.

## End-to-end smoke

`scripts/real_e2e_smoke.py` drives a live MCP server JVM against
`:samples:cmp`'s real Skiko daemon, edits a Compose `@Preview` source,
runs `compileKotlin`, calls `notify_file_changed`, re-reads, then
reverts via `git checkout`. Asserts the rendered bytes round-trip.
~30s on a warm machine.

`RealMcpEndToEndTest` (gated on `-Pmcp.real=true`) is the JUnit-shaped
equivalent that integrates with `:mcp:test`.

## Operational notes

- **Daemon enabled flag.** `composePreviewDaemonStart` always runs and
  writes a descriptor; `enabled: false` means the daemon was explicitly disabled, and the
  supervisor's `SubprocessDaemonClientFactory` refuses to spawn a
  daemon unless the descriptor reports `enabled: true`. Flip via
  `composePreview.daemon { enabled = true }` in the
  consumer's build script.
- **Multi-session.** A single MCP server process can serve N agents
  over N stdio connections (HTTP transport later may multiplex).
  Daemons are shared across sessions when they target the same
  `(workspace, module)`, so two agents reading the same preview both
  benefit from the warm sandbox.
- **Cold start.** Robolectric daemon: ~5–10 s. Desktop daemon: ~600 ms.
  The `watch` tool spawns asynchronously so the McpSession reader
  thread doesn't block; clients see a `(spawning: N)` count in the
  watch response and should react to `notifications/resources/list_changed`
  when the seed lands.
- **classpathDirty.** A daemon emits `classpathDirty` when its
  classpath fingerprint changes (e.g. dependency bump). The supervisor
  drops the dying daemon and respawns asynchronously; clients see
  `notifications/resources/list_changed` and can re-list.

## Module layout

```
mcp/
├── build.gradle.kts
├── README.md                          # this file
├── scripts/
│   ├── README.md
│   └── real_e2e_smoke.py              # end-to-end Python driver
└── src/
    ├── main/kotlin/.../mcp/
    │   ├── DaemonClient.kt            # JSON-RPC client of one daemon JVM
    │   ├── DaemonMcpMain.kt           # stdio entry point + subprocess factory
    │   ├── DaemonMcpServer.kt         # tools, resources, notification routing
    │   ├── DaemonSupervisor.kt        # workspace + daemon map, lazy spawn
    │   ├── HistoryStore.kt            # historical seam (no-op default)
    │   ├── McpServer.kt               # McpSession + Session interface + framing
    │   ├── PreviewResource.kt         # WorkspaceId / PreviewUri / HistoryUri
    │   ├── Subscriptions.kt           # subscribe + watch bookkeeping
    │   ├── WatchPropagator.kt         # watch → setVisible/setFocus translation
    │   └── protocol/
    │       └── McpMessages.kt         # MCP wire shapes (JSON-RPC envelope, etc.)
    └── test/kotlin/.../mcp/
        ├── DaemonMcpServerTest.kt     # in-process e2e via piped streams
        ├── FakeDaemon.kt              # test-only fake daemon
        ├── PreviewResourceTest.kt     # URI / glob / WorkspaceId unit tests
        └── RealMcpEndToEndTest.kt     # opt-in real-daemon JUnit (use -Pmcp.real=true)
```

## See also

- [`docs/daemon/MCP.md`](../docs/daemon/MCP.md) — protocol-level design
- [`docs/daemon/MCP-KOTLIN.md`](../docs/daemon/MCP-KOTLIN.md) — implementation design
- [`docs/daemon/PROTOCOL.md`](../docs/daemon/PROTOCOL.md) — daemon's wire format
- [`docs/daemon/HISTORY.md`](../docs/daemon/HISTORY.md) — history feature design
- [`docs/daemon/DESIGN.md`](../docs/daemon/DESIGN.md) — daemon architecture
