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
[`docs/daemon/MCP.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/MCP.md).

## What we tell agents

Point the agent at the
[`compose-preview` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview).
It's the install-and-iterate playbook: it checks for the CLI, bootstraps it
via the [installer](../install/) if missing, and walks the agent through
rendering and verifying. The skill and the installer aren't alternatives —
the installer is just the one command the skill runs to get the CLI in place.
</content>
