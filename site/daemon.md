---
title: Daemon
layout: default
nav_order: 5
permalink: /daemon/
---

# Daemon

The daemon is an optional speed-up. The Gradle `composePreviewRender` task is
the canonical render path (and what CI uses); the daemon just makes
interactive re-renders fast.

## What it is

A persistent preview server that replaces the per-save Gradle invocation with
a long-lived JVM holding a hot Robolectric (Android) or Compose-Desktop
renderer. A cold Gradle render is 10s+; a warm daemon render is a fraction of
that. It's what the VS Code extension and the [MCP server](./mcp/) drive
behind the scenes.

It's configured with `composePreview.daemon { ... }` and defaults on for
editor use. You don't start or manage it by hand — the extension and MCP
server bring it up as needed.

## What ships

- **`:daemon:core`** — renderer-agnostic JSON-RPC server, protocol types,
  preview index, classloader holder, classpath fingerprint, history manager.
- **`:daemon:android`** — Robolectric backend; holds a sandbox open across
  renders.
- **`:daemon:desktop`** — Compose-Desktop backend; holds a warm render thread.
- **`:mcp`** — multiplexes per-`(workspace, module)` daemons behind one MCP
  surface (see [Agents & MCP](./mcp/)).

## Going deeper

The full design docs live in
[`docs/daemon/`](https://github.com/yschimke/compose-ai-tools/tree/main/docs/daemon):

- [`DESIGN.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/DESIGN.md) — architecture, lifecycle, leak defense, decisions log.
- [`PROTOCOL.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/PROTOCOL.md) — the v1 client ↔ daemon wire format.
- [`CONFIG.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/CONFIG.md) — the `composePreview.daemon { … }` DSL.
- [`MCP.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/MCP.md) — daemon ↔ MCP mapping and tool surface.
</content>
