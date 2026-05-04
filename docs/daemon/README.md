# Preview daemon — design docs

A persistent preview server that replaces the per-save Gradle invocation
with a long-lived JVM holding a hot Robolectric (Android) or
Compose-Desktop renderer. Plus an MCP server that exposes the daemon
to MCP-aware agents.

## What ships today

- **`:daemon:core`** — renderer-agnostic JSON-RPC server, protocol
  types, preview index, classloader holder, classpath fingerprint,
  history manager.
- **`:daemon:android`** — Robolectric backend; `RobolectricHost` holds
  a sandbox open across renders.
- **`:daemon:desktop`** — Compose-Desktop backend; `DesktopHost` holds
  a warm render thread.
- **`:daemon:harness`** — end-to-end harness driving real daemons over
  JSON-RPC; ten scenarios (S1–S10) covering lifecycle, drain,
  render-after-edit, visibility, failures, latency-record,
  classpath-dirty, history.
- **`:mcp`** — MCP server multiplexing per-(workspace, module) daemons
  behind one MCP surface; tools for project registration, watches,
  render, history; resource subscriptions for push.

The daemon is configured with `composePreview.daemon { ... }` and
defaults on for editor use. The Gradle `renderPreviews` task remains
the CI-canonical render path. Open work is tracked in
[ROADMAP.md](ROADMAP.md).

## Files

### Architecture

- **[DESIGN.md](DESIGN.md)** — daemon architecture: scope, module
  layout, staleness cascade, lifecycle, leak defense, decisions log.
- **[LAYERING.md](LAYERING.md)** — the rule that keeps Gradle/CLI,
  daemon, and MCP additive. Module-boundary list, integration seams.
- **[PROTOCOL.md](PROTOCOL.md)** — locked v1 wire format between
  client (VS Code, harness, MCP shim) and daemon.
- **[CONFIG.md](CONFIG.md)** — `composePreview.daemon { … }` DSL.

### Subsystems

- **[CLASSLOADER.md](CLASSLOADER.md)** — disposable user classloader.
  The save-loop fix.
- **[CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md)** — short
  runbook for the classloader forensic dump tools.
- **[ROBOLECTRIC-PRIMER.md](ROBOLECTRIC-PRIMER.md)** — primer on
  Robolectric internals (sandbox lifecycle, classloader delegation,
  bytecode instrumentation, shadows).
- **[SANDBOX-POOL.md](SANDBOX-POOL.md)** — in-JVM sandbox pool.
- **[STARTUP.md](STARTUP.md)** — daemon startup latency analysis.
- **[HISTORY.md](HISTORY.md)** — preview history archive: on-disk
  schema, JSON-RPC API, MCP mappings, `HistorySource` backends.
- **[INTERACTIVE.md](INTERACTIVE.md)** — focus-mode live stream and
  scripted recording protocol.
- **[INTERACTIVE-ANDROID.md](INTERACTIVE-ANDROID.md)** — Android
  Robolectric-specific architecture for held-rule interactive sessions.
- **[DATA-PRODUCTS.md](DATA-PRODUCTS.md)** — per-render structured
  data (a11y, layout inspector, recomposition, etc.) — wire surface
  and catalogue.
- **[PREDICTIVE.md](PREDICTIVE.md)** — predictive prefetch decisions.

### MCP

- **[MCP.md](MCP.md)** — server overview: how the daemon's JSON-RPC
  surface maps onto MCP resources, subscriptions, and tools.
- **[MCP-KOTLIN.md](MCP-KOTLIN.md)** — implementation reference for
  the `:mcp` module.

### Reference data

- **[baseline-latency.md](baseline-latency.md)** +
  **[baseline-latency.csv](baseline-latency.csv)** — captured
  per-target / per-scenario timing baselines.
- **[protocol-fixtures/](protocol-fixtures/)** — golden JSON message
  corpus consumed by both the Kotlin and TypeScript test suites.
- **[TEST-HARNESS.md](TEST-HARNESS.md)** — harness scenarios,
  FakeHost, image-baseline strategy, CI workflow.

- **[ROADMAP.md](ROADMAP.md)** — open work, without historical task
  diaries.

## Non-goals (v1)

- Per-project (multi-module) sandbox sharing — each module gets its
  own daemon.
- Replacing the Gradle `renderPreviews` task — kept as fallback and
  CI-canonical path indefinitely.
- Hot kotlinc / compile daemon integration — v2.
- Tier-3 dependency-graph reachability index — v1 ships with
  conservative "module-changed = all previews stale, filtered by
  visibility."
