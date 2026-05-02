# Preview daemon — design docs

A persistent preview server that replaces the per-save Gradle invocation with a long-lived JVM holding a hot Robolectric (Android) or Compose-Desktop renderer. Plus an MCP server that exposes the daemon to MCP-aware agents.

## What ships today

- **`:daemon:core`** — renderer-agnostic JSON-RPC server, protocol types, preview index, classloader holder, classpath fingerprint, history manager.
- **`:daemon:android`** — Robolectric backend; `RobolectricHost` holds a sandbox open across renders.
- **`:daemon:desktop`** — Compose-Desktop backend; `DesktopHost` holds a warm render thread.
- **`:daemon:harness`** — end-to-end harness driving real daemons over JSON-RPC; ten scenarios (S1–S10) covering lifecycle, drain, render-after-edit, visibility, failures, latency-record, classpath-dirty, history.
- **`:mcp`** — top-level MCP server multiplexing per-(workspace, module) daemons behind one MCP surface; tools for project registration, watches, render, history; resource subscriptions for push.

The daemon is configured with `composePreview.daemon { ... }` and defaults on for editor use. The Gradle `renderPreviews` task remains the CI-canonical render path.

## What's Open

Open work lives in [ROADMAP.md](ROADMAP.md). Keep this index focused on stable design and reference
documents.

## Files

### Architecture

- **[DESIGN.md](DESIGN.md)** — daemon architecture: scope, module layout, staleness cascade, lifecycle, leak defense, validation strategy, decisions log.
- **[LAYERING.md](LAYERING.md)** — the rule that keeps Gradle/CLI, daemon, and MCP additive. Module-boundary list, integration seams, removal procedures.
- **[PROTOCOL.md](PROTOCOL.md)** — locked v1 wire format between client (VS Code, harness, MCP shim) and daemon.
- **[CONFIG.md](CONFIG.md)** — `composePreview.daemon { … }` DSL reference.

### Subsystems

- **[CLASSLOADER.md](CLASSLOADER.md)** — disposable user classloader (B2.0). The save-loop fix.
- **[CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md)** — short runbook for the classloader forensic dump tools.
- **[ROBOLECTRIC-PRIMER.md](ROBOLECTRIC-PRIMER.md)** — primer on Robolectric internals (sandbox lifecycle, classloader delegation, bytecode instrumentation, shadows). Read when reasoning about classloader behaviour while debugging.
- **[STARTUP.md](STARTUP.md)** — daemon startup latency analysis. Where the time goes; menu of options to attack each cost.
- **[HISTORY.md](HISTORY.md)** — preview history archive: on-disk schema, JSON-RPC API, MCP and VS Code mappings, branch/worktree provenance, pluggable `HistorySource` backends.

### MCP

- **[MCP.md](MCP.md)** — MCP server overview: how the daemon's JSON-RPC surface maps onto MCP resources, subscriptions, and tools.
- **[MCP-KOTLIN.md](MCP-KOTLIN.md)** — concrete implementation reference for the `:mcp` module.

### Future work

- **[ROADMAP.md](ROADMAP.md)** — current open work, without historical task diaries.
- **[PREDICTIVE.md](PREDICTIVE.md)** — predictive prefetch design (v1.1+).
- **[TEST-HARNESS.md](TEST-HARNESS.md)** — harness design: scenarios, FakeHost vs real-mode, image-baseline strategy, CI workflow.

### Reference data

- **[baseline-latency.md](baseline-latency.md)** + **[baseline-latency.csv](baseline-latency.csv)** — captured per-target / per-scenario timing baselines from the bench harnesses.
- **[protocol-fixtures/](protocol-fixtures/)** — golden JSON message corpus consumed by both the Kotlin and TypeScript test suites.

## Non-goals (v1)

- Per-project (multi-module) sandbox sharing — each module gets its own daemon.
- Replacing the Gradle `renderPreviews` task — kept as fallback and CI-canonical path indefinitely.
- Hot kotlinc / compile daemon integration — v2.
- Tier-3 dependency-graph reachability index — v1 ships with conservative "module-changed = all previews stale, filtered by visibility."

## How to use these docs

If you're reviewing the architecture: read [DESIGN.md](DESIGN.md) end to end (~30 min), then [LAYERING.md](LAYERING.md).

If you're implementing or reviewing daemon code: [PROTOCOL.md](PROTOCOL.md) is the wire-format authority; [CLASSLOADER.md](CLASSLOADER.md) is the save-loop story.

If you're working on the MCP surface: [MCP.md](MCP.md) for the high-level mapping, [MCP-KOTLIN.md](MCP-KOTLIN.md) for the implementation specifics.

If you're triaging a daemon bug: [`daemon/desktop/CONTRIBUTING.md`](../../daemon/desktop/CONTRIBUTING.md) has the no-mid-render-cancellation reviewer checklist.
