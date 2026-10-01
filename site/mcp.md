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

## The MCP server

The `:mcp` module exposes the preview daemon's JSON-RPC over stdio, so
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

### Register the local server with OpenCode

OpenCode v2 stores local MCP servers under `mcp.servers`. Register the server
globally in the user config (`$XDG_CONFIG_HOME/opencode/opencode.json` when set,
otherwise `~/.config/opencode/opencode.json`):

```sh
compose-preview mcp install --opencode
```

Use a project-local `opencode.json` instead, or name an explicit config file:

```sh
compose-preview mcp install --opencode --scope project
compose-preview mcp install --opencode --opencode-config /path/to/opencode.json
```

The command preserves existing top-level, `mcp`, and sibling server keys while
adding this current-v2 shape when it is missing or broken (a healthy entry is
left as it is). The launcher is the stable one (for example
`~/.local/bin/compose-preview`), never a versioned
`compose-preview-<version>/bin/` path that the next upgrade deletes. A user-scope
entry has no `--project`: the server finds the project from the client's roots
or working directory. Only `--scope project` adds `--project=<dir>`:

```json
{
  "mcp": {
    "servers": {
      "compose-preview-mcp": {
        "type": "local",
        "command": [
          "/home/you/.local/bin/compose-preview",
          "mcp",
          "serve"
        ],
        "codemode": false
      }
    }
  }
}
```

If `opencode.jsonc` already exists at the selected user or project scope, the
CLI selects that existing file and takes the manual-merge path rather than
creating a competing `opencode.json`.

The older direct `mcp.compose-preview-mcp` shape and `enabled: true` are not
OpenCode v2 configuration. If the target is `.jsonc` or contains comments,
the CLI does not rewrite it because that would discard comments; it prints the
v2 snippet and the exact file to merge manually. Restart OpenCode and run
`opencode mcp list` to verify the connection. OpenCode skill installation is
covered in the
[cross-harness guide](https://github.com/yschimke/compose-ag-plugin/blob/main/docs/opencode.md).

### Register the local server with Antigravity

Prefer the `compose-preview` plugin from
[`yschimke/compose-ag-plugin`](https://github.com/yschimke/compose-ag-plugin): it
registers the server and also carries the skill and hooks. When it is installed
(`~/.gemini/config/plugins/compose-preview`), `mcp install` writes no global
entry, because that would duplicate the plugin's server.

Without the plugin, `mcp install --antigravity` merges `compose-preview-mcp`
into `mcpServers` of whichever Antigravity config already exists:
`~/.gemini/antigravity/mcp_config.json` first, then
`~/.gemini/config/mcp_config.json`. `--antigravity-config <path>` names the file
explicitly. `compose-preview mcp doctor` lists both paths, whether each holds an
entry, whether the plugin is installed, and any global entry that duplicates it.

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
It's the install-and-iterate playbook: it checks for the CLI, bootstraps it
via the [installer](../install/) if missing, and walks the agent through
rendering and verifying. The skill and the installer aren't alternatives —
the installer is just the one command the skill runs to get the CLI in place.
</content>
