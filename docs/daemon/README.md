# Preview daemon — design docs

Design and planning for an experimental persistent preview server that replaces the per-save Gradle invocation with a long-lived JVM holding a hot Robolectric sandbox.

> **Status:** design only. No code shipped yet. v1 will land behind `composePreview.experimental.daemon=true` with a "may eat your laundry" warning.

## What this is

Today, every preview refresh in the VS Code extension runs the Gradle `renderPreviews` task: Gradle config + classpath up-to-date checks + JVM fork + Robolectric sandbox init + render. Cold this is 10–20s; warm with a no-code-change save it's still 5–10s, dominated by Gradle config and Robolectric bootstrap.

The daemon keeps the Robolectric sandbox alive between saves so the per-save hot path collapses to just kotlinc + N renders. Target: **sub-second refresh for a single focused preview after a no-classpath-change save.**

## Files

- **[DESIGN.md](DESIGN.md)** — the full architecture: scope, module layout, staleness cascade, lifecycle, leak defense, validation strategy, decisions log.
- **[PROTOCOL.md](PROTOCOL.md)** — locked JSON-RPC wire-format contract between the VS Code extension and the daemon. Stream B (daemon) and Stream C (extension) implement against this in parallel.
- **[CONFIG.md](CONFIG.md)** — `composePreview.experimental.daemon { … }` DSL reference: defaults, ranges, and effects for `enabled` / `maxHeapMb` / `maxRendersPerSandbox` / `warmSpare`.
- **[CLASSLOADER.md](CLASSLOADER.md)** — design for the disposable user classloader (B2.0). The save-loop blocker; without it the daemon's "warm render" numbers don't apply across recompiles. Compose Hot Reload prior-art analysis included.
- **[CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md)** — design for the classloader / Robolectric-config dump tool that produces a diffable manifest of the working standalone vs broken daemon path. Empirical diagnostic for the Android S3.5 mystery — until we run the dumps we're guessing about which classloader skew is the actual blocker.
- **[ROBOLECTRIC-PRIMER.md](ROBOLECTRIC-PRIMER.md)** — a primer on how Robolectric itself works: sandbox lifecycle, `SandboxClassLoader` delegation rules, bytecode instrumentation + shadows, `@Config` / qualifiers / native code. Read this when reasoning about classloader behaviour while debugging the daemon. Distinct from this project's design — it covers Robolectric upstream, not our integration with it.
- **[MCP.md](MCP.md)** — overview design for exposing the daemon as an MCP server: resource subscriptions for push (no polling), tools for explicit render requests, three architecture options compared. Empirical answer to "can MCP push instead of poll?" — yes, via `notifications/resources/updated`.
- **[MCP-KOTLIN.md](MCP-KOTLIN.md)** — concrete Kotlin/Ktor implementation design for the MCP server (Option A from MCP.md): module layout, code samples using `io.modelcontextprotocol:kotlin-sdk`, what an MCP server unlocks that the existing CLI can't.
- **[LAYERING.md](LAYERING.md)** — the architectural rule that keeps Gradle/CLI, daemon, and MCP additive. Explicit module-boundary list, integration seams, removal procedures, and the "no `if (daemonAvailable) …`" complexity rule. Read after DESIGN if you want the whole picture; mandatory before adding a new cross-layer hook.
- **[HISTORY.md](HISTORY.md)** — preview history design: on-disk schema, JSON-RPC API (`history/list`, `history/read`, `history/diff`), MCP and VS Code consumer mappings, branch + worktree provenance, pluggable `HistorySource` backends (LocalFs / `preview/<branch>` git refs / HTTP mirrors). Same archive consumed by Gradle, daemon, MCP, and VS Code; cross-worktree merging happens above Layer 2.
- **[STARTUP.md](STARTUP.md)** — daemon startup latency analysis: where the time actually goes (JVM start / Robolectric instrumentation / android-all jar download), the in-flight `RobolectricHost.start()` blocking fix, and the menu of options to attack each cost (machine-resident daemon, AppCDS, instrumented-bytecode cache, JVM checkpoint/restore, Layoutlib swap). Plus the wire-format for the `StartupTimings` instrumentation that makes the timeline observable per-boot.
- **[PREDICTIVE.md](PREDICTIVE.md)** — predictive prefetch design (v1.1+). Adds a multi-tier render queue + speculative renders on scroll-ahead / dropdown signals; observability built in.
- **[TEST-HARNESS.md](TEST-HARNESS.md)** — the harness's design: what scenarios it covers, FakeHost vs real-mode, image-baseline strategy, CI workflow.
- **[TODO.md](TODO.md)** — work breakdown with parallelisation guidance: which streams can run in parallel, which agents own what, definition-of-done per chunk.

## Non-goals (v1)

- Per-project (multi-module) sandbox sharing — deferred. Each module gets its own daemon.
- CLI / MCP daemon mode — CLI keeps using the Gradle task.
- Replacing the Gradle `renderPreviews` task — kept as fallback and CI-canonical path indefinitely.
- Hot kotlinc / compile daemon integration — v2.
- Tier-3 dependency-graph reachability index — v1 ships with conservative "module-changed = all previews stale, filtered by visibility."

## How to use these docs

If you're reviewing the proposal: read [DESIGN.md](DESIGN.md) end to end (~30 min).

If you're implementing or about to assign work to agents: read [DESIGN.md](DESIGN.md) for context, then drive the work from [TODO.md](TODO.md) which has the dependency graph and parallel work streams.

If you're triaging a daemon bug: future `docs/daemon/TROUBLESHOOTING.md` once the thing exists.
