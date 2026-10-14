---
title: Agents & MCP
layout: default
nav_order: 4
permalink: /mcp/
---

# Agents & MCP

You don't need any of this to use compose-ai-tools — rendering `@Preview`s to
PNG already lets an agent see its work. This page is for when you want a
tighter, push-based loop than "run a command, read a file."

Configuring an MCP client? The hosted server's one URL is
`https://preview.coo.ee/mcp`; the local server is `compose-preview mcp serve`.
See [Two MCP servers](#two-mcp-servers-local-and-hosted) before adding either.

## The agent loop

A token-frugal feedback loop over Compose UI — the way
[Playwright](https://playwright.dev) gave web agents one over the DOM: act by
a stable reference, observe structure rather than pixels, and turn
exploration into durable tests. These are exposed by the preview daemon's MCP
server (and the `compose-preview` CLI where noted).

- **Target by semantic ref, not pixels.** `interactive/input` and
  `record_preview` accept a `target` (`testTag` / `role`+`text` / a stable
  node `ref`) that the daemon resolves to the node's centre, so a click
  survives layout changes instead of breaking on a coordinate. Android
  (Robolectric) and Desktop (Skiko).
- **Token-frugal observation.** `render_preview observe=semantics|hash`
  returns the `compose/semantics` tree + a hash + dimensions instead of a
  base64 PNG — typically a few hundred tokens versus ~1.5k. Fetch pixels only
  when you actually need to look.
- **Semantics diff.** `diff_semantics` (MCP) and `compose-preview
  diff-semantics` (CLI) diff two semantics trees and report what changed
  *semantically* (text, label, role, testTag, overflow…), matched by stable
  ref — a deterministic, pixel-free regression signal, the Compose analogue
  of Playwright's aria-snapshot diff.
- **Matrix render.** `render_matrix` (and `compose-preview render-matrix`)
  renders one preview across a cross-product of
  `device × locale × uiMode × fontScale` in a single call, returning a
  per-cell hash and which cells changed — "does this survive small screen +
  RTL + large font?" without N screenshots. Opt into a stitched contact-sheet
  image when you want to eyeball every cell at once.
- **Recording → test.** `record_preview emitTest=true` turns a scripted
  interaction into a runnable Compose UI test (semantic targets become
  `onNodeWithTag(...).performClick()` steps; each `recording.probe` is diffed
  against the previous probe's captured semantics into `assertExists()` /
  `assertDoesNotExist()` assertions).
- **Structured failures.** A failed render reports a typed `kind` plus a
  one-line fix hint for recognized signatures (classpath skew, Robolectric
  SDK mismatch, missing `@Composable`, …) instead of an opaque message.

Cost budget for these in
[`docs/TOKEN_USAGE.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md).

## Two MCP servers: local and hosted

There are two different MCP servers. Configure each at most once.

| | Local daemon MCP | Hosted catalog MCP |
|---|---|---|
| What it renders | **Your** project's `@Preview`s, on your machine | Published design-system catalogs (Material 3, Wear M3, …) and the UI Builder |
| How it runs | stdio: `compose-preview mcp serve` | Streamable HTTP at **`https://preview.coo.ee/mcp`** |
| Server name in configs | `compose-preview-mcp` | `compose-preview-catalog` |
| Needs | The CLI on `PATH`, Java 17+, a Gradle project | Nothing local |

**`https://ui.coo.ee/mcp` is an alias of the same hosted server**, not a second
one: same process, same tools. Never configure both. Two entries give the agent
every tool twice, and because the CLI keys access grants by origin they also
mean two separate grants to approve and refresh. If a config already has a
`ui.coo.ee` entry, replace its URL with `https://preview.coo.ee/mcp` rather than
adding a second server. (`https://ui.coo.ee/start` is the UI Builder start page
for people; it is not an MCP endpoint.)

The per-harness plugins in
[`yschimke/compose-agent-plugins`](https://github.com/yschimke/compose-agent-plugins)
wire both servers for you; how each host is configured is in its
[host setup notes](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/host-setup.md).
The rest of this page is about the local server.

## The local MCP server

`compose-preview mcp serve` launches the local server (published from
[`compose-preview-mcp`](https://github.com/yschimke/compose-preview-mcp) and
fetched on first use). It exposes the preview daemon's JSON-RPC over stdio, so
MCP-aware agents can render previews on demand and be *notified* when bytes
change — instead of running Gradle (10s+ cold) or polling PNGs off disk with
no way to request a re-render.

Each `@Preview` becomes one MCP `Resource` under a stable
`compose-preview://<workspaceId>/<module>/<previewFqn>` URI. The server
supports `subscribe` (per-resource update notifications) and `listChanged`
(the set of resources mutated), plus `notifications/progress` for
long-running calls. Data products are read via `list_data_products`,
`subscribe_preview_data`, and `get_preview_data`.

For the full tool surface, URI scheme, and wire protocol see
[`docs/daemon/MCP.md`](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/MCP.md).

### Register the local server

Run `compose-preview mcp install` once from each Compose project's root. It
writes daemon descriptors and runs discovery for every module with the plugin
applied, then registers `compose-preview-mcp` with each agent host it detects
(Claude Code, Codex, OpenCode, Antigravity; `--<host>` / `--no-<host>` force or
skip one). `compose-preview mcp register` does only the host registration,
without touching Gradle; `compose-preview mcp doctor` reports what is set up.

- **One global entry per host.** Each entry is `<stable launcher> mcp serve`
  (for example `~/.local/bin/compose-preview`), never a versioned
  `compose-preview-<version>/bin/` path that the next upgrade deletes, and has no
  `--project`: the server finds the project from the client's roots or working
  directory. Only OpenCode's `--scope project` writes a project-local
  `opencode.json` with `--project=<dir>`.
- **Healthy entries are left alone.** `mcp install` only adds a missing entry or
  repairs a broken one, preserving sibling keys and servers.
  `compose-preview mcp repair` (run by `compose-preview update`) points existing
  entries at the stable launcher and drops a global `--project`, for example
  after an upgrade removed an old versioned directory.
- **Plugins win.** With the Antigravity `compose-preview` plugin installed
  (`~/.gemini/config/plugins/compose-preview`), `mcp install` and `mcp register`
  write no global Antigravity entry, since the plugin already provides the
  server; `mcp doctor` names any older global `compose-preview-mcp` entry (in
  `~/.gemini/antigravity/mcp_config.json` or `~/.gemini/config/mcp_config.json`)
  to remove. `--antigravity` writes the global entry anyway. When Antigravity,
  Claude Code or Codex is detected, `mcp install` prints the matching
  wiring-plugin commands; `--no-plugin-hint` suppresses them.
- **Comments are never rewritten.** An `opencode.jsonc`, or any config with
  comments, gets the snippet and the file to merge it into printed instead.
  OpenCode entries use the v2 `mcp.servers` shape; the older
  `mcp.compose-preview-mcp` shape with `enabled: true` is not v2 configuration.

`--codex-config`, `--opencode-config` and `--antigravity-config` name an
explicit config file. Full flag list: `compose-preview mcp --help`. Per-host
paths and verification steps (`opencode mcp list`, …):
[host setup notes](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/host-setup.md#registering-the-local-mcp-server)
and the [OpenCode guide](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/opencode.md).

### Connect a remote UI-builder session

The native MCP profile can expose eight additional tools backed by the same
persisted preview-server UI-builder session a browser is editing. Supply the
server URL as an argument and the temporary agent grant as an environment
variable, keeping the secret out of process arguments:

```sh
COMPOSE_PREVIEW_UI_BUILDER_TOKEN='<grant>' \
  compose-preview mcp serve --ui-builder-url https://preview.example/
```

Grants keep `ui-builder-read`, `ui-builder-write`, and `ui-builder-export`
independent. The adapter is a remote protocol client only: it does not copy the
server's reducer, catalog, renderer, or persistence into this repository.

## Shared settings

The MCP server's `settings_read` / `settings_update` tools (the native
settings page in ChatGPT and Codex) persist their defaults to one file,
`~/.compose-preview/settings.json` — or wherever `COMPOSE_PREVIEW_SETTINGS_FILE`
points:

```json
{ "schema": "compose-preview-settings/v1", "values": { "darkTheme": true, "locale": "fr" } }
```

The `compose-preview` CLI reads the same file (it never writes it) with the
server's precedence: **an explicit flag beats a setting, which beats the
built-in default.**

| Setting | Where the CLI applies it | The flag that wins over it |
|---|---|---|
| `device` (`id:<device>`, or `preview` for none) | `render-matrix` device axis, `record` overrides | `--device`, `--overrides device=…` |
| `darkTheme` | `render-matrix` ui-mode axis, `record` overrides | `--ui-mode`, — |
| `fontScale` (`0` for none) | `render-matrix` font-scale axis, `record` overrides | `--font-scale`, `--overrides fontScale=…` |
| `locale` (BCP-47, empty for none) | `render-matrix` locale axis, `record` overrides | `--locale`, `--overrides localeTag=…` |

A setting fills only what the command line leaves unset: `render-matrix
--locale en,ar` with `darkTheme` on renders both locales dark. `show` and
`render` drive the Gradle render, which draws each preview exactly as its
`@Preview` declares, so they cannot apply these four; when any is set they
say so on stderr rather than ignore it quietly. The server-only keys
(`renderResult`, `imageToModel`, `replicasPerDaemon`, `uiBuilderMcpAppLayout`)
and any key the CLI does not know are ignored. A bad value falls back to its
default with a warning and keeps the other keys; a file that is not valid JSON
warns once and every default applies.

## Deep links into the ChatGPT / Codex sidebar

The local server's `previews_library` tool is a sidebar app, and
`compose-preview show --link` prints a link per preview that opens it there,
rendered on open:

```
compose-preview show --id com.example.HomeKt.HomePreview --link \
  --openai-plugin-id <plugin id> --openai-marketplace <marketplace>
```

| Flag | Opens in | Link |
|---|---|---|
| `--link` (or `--link=desktop`) | ChatGPT / Codex desktop | `codex://plugins/<id>@<marketplace>/app/previews_library?path=…` |
| `--link=mobile` | ChatGPT mobile | `chatgpt://plugins/<id>@<marketplace>/app/previews_library?path=…` |
| `--link=web` | chatgpt.com | `https://chatgpt.com/plugins/<id>/app/previews_library?path=…` |

The `path` is the library route `/preview/<compose-preview URI>`,
percent-encoded (RFC 3986: a space is `%20`, and `/ ? & = : @` inside a value
are escaped). The plugin id and marketplace depend on how the plugin was
published, so they are never guessed: pass the flags, or set
`COMPOSE_PREVIEW_OPENAI_PLUGIN_ID` (and `COMPOSE_PREVIEW_OPENAI_MARKETPLACE`
for a plugin installed from a custom marketplace; a flag beats the variable).
Without a plugin id, `show --link` prints that guidance instead of a link and
still shows the previews. Text output adds a `link:` line under each preview;
`--json` adds a `link` field to each one.

The link's workspace segment is derived the way the server derives it for a
project registered by path, from the project directory's name and canonical
path — a project registered under a custom `rootProjectName` has a different
id, and its links will not resolve.

## What we tell agents

Point the agent at the
[`compose-preview` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview).
It's the install-and-iterate playbook: it checks for the CLI, bootstraps it if
missing ([setup](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/setup.md)),
and walks the agent through rendering and verifying.
