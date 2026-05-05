# MCP server (agent integration)

Driving compose-preview from an MCP-aware agent host (Claude Code, the Agent
SDK, custom hosts) instead of from a shell. Companion to the
[contributor README](https://github.com/yschimke/compose-ai-tools/blob/main/mcp/README.md);
this file covers what the consumer of the published skill bundle needs.

## When to read this

You want any of:

- An agent that can re-render a `@Preview` on demand and **read the PNG inline**
  without spawning Gradle per call.
- **Push notifications** when a render finishes (no polling) — for a chat-style
  loop where the user edits source and the agent reacts.
- **Multi-workspace fan-out** — one MCP server serving previews from multiple
  projects, or two worktrees of the same repo at once (the bread-and-butter of
  PR review; see [`compose-preview-review/design/MCP_REVIEW.md`](../../compose-preview-review/design/MCP_REVIEW.md)).

If you just want a one-shot render or a CI diff comment, the CLI
(`compose-preview show --json`) is simpler and has the same render engine
behind it. Reach for MCP when the loop is long-lived.

## Setup

The `compose-preview` CLI bundles the MCP server. There is no second
download, no manual classpath, no `claude mcp add` argument to compose by
hand.

```bash
# Run from the project root. Bootstraps descriptors + previews.json for every
# plugin-applied module, then registers the MCP server with every locally
# installed agent host it detects (Claude Code, Codex, Antigravity).
compose-preview mcp install

# Force a specific host even if it's not auto-detected, or opt out.
compose-preview mcp install --antigravity        # force Antigravity write
compose-preview mcp install --no-claude          # skip claude mcp add
compose-preview mcp install --codex              # force Codex write
compose-preview mcp install --codex-config /path/to/config.toml

# Verify per-module state (descriptor present, enabled=true).
compose-preview mcp doctor
```

`compose-preview mcp serve` defaults `--project` to the current Gradle root
when run from a project checkout. Detection per host:

- **Claude Code**: `claude` on PATH, or `~/.claude/` exists. Registered via
  `claude mcp add --scope user` (idempotent — the install upserts).
- **Codex**: `codex` on PATH, or `~/.codex/` exists. The
  `[mcp_servers.compose-preview-mcp]` table is replaced in place (or appended)
  in `~/.codex/config.toml`.
- **Antigravity**: `__CFBundleIdentifier=com.google.antigravity`,
  `ANTIGRAVITY_CLI_ALIAS`, or `~/.gemini/antigravity/` exists. The MCP server
  entry is merged into `~/.gemini/antigravity/mcp_config.json`.

All three host writers store the absolute launcher path because the host may
launch the server from a different working directory than the Gradle root.
Beyond host registration, `mcp install` does three things behind the scenes:

1. Runs `composePreviewDaemonStart` for every module that applies the plugin,
   so each `<module>/build/compose-previews/daemon-launch.json` exists.
2. Patches each descriptor's `enabled` flag to `true`. (The Gradle DSL knob
   `composePreview.daemon { enabled = true }` is intentionally
   not propagated via `-P`, so the on-disk patch is the canonical way to flip
   it from outside the build script.)
3. Runs `discoverPreviews` so `previews.json` sits alongside.

`mcp doctor` reads the descriptors back without mutating, and exits non-zero
if any module's descriptor is missing or has `enabled: false`. Use it from a
SessionStart hook, CI, or a watchdog.

## What an attached agent sees

Once the host is wired, the agent gets a stable resource catalogue:

- Each `@Preview` becomes one resource:
  `compose-preview://<workspaceId>/<encodedModulePath>/<previewFqn>`.
- `resources/list` enumerates every preview every supervised daemon knows.
- `resources/read` triggers a render and returns the PNG inline (base64
  `image/png`).
- `resources/subscribe(uri)` pushes `notifications/resources/updated` whenever
  that one preview re-renders.
- `notifications/resources/list_changed` fires when the discovery set or the
  workspace set mutates.

Tool calls (12 today, see [`mcp/README.md` § Tool reference](https://github.com/yschimke/compose-ai-tools/blob/main/mcp/README.md#tool-reference)
for the full table) cover the rest:

- `register_project`, `unregister_project`, `list_projects` — manage
  workspaces at runtime.
- `watch(workspaceId, module?, fqnGlob?)` — area-of-interest registration.
  The supervisor expands the watch into a URI set and forwards it to the
  daemon as `setVisible` / `setFocus`, so render queue prioritisation
  matches what the agent is looking at.
- `notify_file_changed(workspaceId, path, kind?)` — tell the daemon that the
  source on disk changed. Use after editing source files outside the host's
  view (e.g. the agent invoked the Edit tool from a different process). The
  daemon's classloader-swap fast path picks up bytecode changes without a
  full reboot.
- `render_preview(uri, overrides?)` — force-render bypassing cache. Use
  `overrides` to flip device, locale, fontScale, uiMode, orientation, or
  density per call without editing the `@Preview` annotation.
- `history_list` / `history_diff` — proxy the daemon's history feature for
  comparing rendered output across runs.
- `list_data_products` / `get_preview_data` / `subscribe_preview_data` —
  fetch structured per-render data (a11y findings, layout tree, recomposition
  heat-map, …) alongside the PNG. See
  [`design/DATA_PRODUCTS.md`](./DATA_PRODUCTS.md) for the kind catalogue.

## Multi-workspace and worktrees

`WorkspaceId.derive(rootProjectName, path)` is
`<rootProjectName>-<8-hex-chars-of-sha256(canonical-path)>`. The hash is
always present, so two worktrees of the same repo at different paths get
distinct ids by construction — not just distinct names.

Daemons are owned per `(workspaceId, modulePath)`. Two worktrees of the
same module run two independent daemon JVMs against their own `build/`
output. No PNG or cache cross-contamination.

For PR-review-style flows (base + head worktrees, render both, diff client-side)
see the dedicated [`compose-preview-review/design/MCP_REVIEW.md`](../../compose-preview-review/design/MCP_REVIEW.md).

## Lifecycle and operational notes

- **Cold start.** Robolectric daemon: ~5–10 s. Desktop daemon: ~600 ms.
  `watch` spawns asynchronously; the host's reader thread doesn't block.
- **One server, N clients.** A single MCP server JVM can serve N agents over
  N stdio connections. Daemons are shared across sessions when they target
  the same `(workspace, module)`, so two agents reading the same preview
  both benefit from one warm sandbox.
- **`classpathDirty`.** When a daemon's classpath fingerprint changes (e.g.
  dependency bump), it emits `classpathDirty` and exits. The supervisor
  drops the dying daemon and respawns; clients see
  `notifications/resources/list_changed`.
- **Disconnect.** On stdin EOF the server tears down every supervised daemon
  cleanly (`shutdown` + `exit`, drain wait) before exiting itself.
- **Cloud sandboxes.** The `install.sh` bootstrap covers the CLI; the MCP
  server runs out of the same launcher with no extra steps. JVM toolchain
  story is unchanged from the CLI — see
  [`design/CLAUDE_CLOUD.md`](./CLAUDE_CLOUD.md).

## See also

- [`mcp/README.md`](https://github.com/yschimke/compose-ai-tools/blob/main/mcp/README.md)
  — full tool reference, URI scheme details, end-to-end smoke script.
- [`docs/daemon/MCP.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/MCP.md)
  — protocol-level design (subscriptions, notifications, capability
  negotiation).
- [`docs/daemon/PROTOCOL.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/PROTOCOL.md)
  — daemon JSON-RPC wire format the MCP shim translates from.
- [`compose-preview-review/design/MCP_REVIEW.md`](../../compose-preview-review/design/MCP_REVIEW.md)
  — agent-driven PR review using two MCP workspaces (base + head).
