# Preview daemon — design docs

Design and planning for an experimental persistent preview server that replaces the per-save Gradle invocation with a long-lived JVM holding a hot Robolectric sandbox.

> **Status:** design only. No code shipped yet. v1 will land behind `composePreview.experimental.daemon=true` with a "may eat your laundry" warning.

## What this is

Today, every preview refresh in the VS Code extension runs the Gradle `renderPreviews` task: Gradle config + classpath up-to-date checks + JVM fork + Robolectric sandbox init + render. Cold this is 10–20s; warm with a no-code-change save it's still 5–10s, dominated by Gradle config and Robolectric bootstrap.

The daemon keeps the Robolectric sandbox alive between saves so the per-save hot path collapses to just kotlinc + N renders. Target: **sub-second refresh for a single focused preview after a no-classpath-change save.**

## Files

- **[DESIGN.md](DESIGN.md)** — the full architecture: scope, module layout, staleness cascade, lifecycle, leak defense, validation strategy, decisions log.
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
