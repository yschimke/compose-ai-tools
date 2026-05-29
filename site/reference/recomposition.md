---
title: Recomposition
parent: Reference
nav_order: 7
permalink: /reference/recomposition/
---

# Recomposition

Per-node recomposition counts — the agent-facing performance signal
for unnecessary recomposition. Source for heat maps and "this node
recomposed N times for one click" reviews.

## At a glance

| | |
|---|---|
| Kind | `compose/recomposition` |
| Schema version | 2 |
| Modules | `:data-recomposition-core` (published) · `:data-recomposition-connector` |
| Render mode | instrumented |
| Cost | medium |
| Token usage | ~1 000–1 500 tok per query (`compose/recomposition` ~3–5 KB). See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android · Desktop · shared |

## What it answers

- How many times did each composable recompose during this render?
- Which subtree is the hot spot — a leaf re-running, or a parent re-running and dragging its children with it?
- After a click / scroll / value change, what is the **delta** in recomposition counts compared to the steady-state snapshot?

Two modes:

- **Snapshot** — a single instrumented render reports cumulative counts.
- **Click delta** — drive the input, then diff before / after counts to attribute work to the interaction.

## What it does NOT answer

- It does not measure wall-clock time spent in composition — pair with [`render/trace`](../render).
- It does not detect the *cause* (unstable lambda, missing `remember`, key churn) — it points at the location; reading the source is on the reviewer.
- It runs the renderer in instrumented mode, so absolute counts are not directly comparable to a non-instrumented production build.

## Use cases

- Catch a `Modifier` factory that captures a new lambda each composition and forces the whole row to recompose.
- Verify a `derivedStateOf` / `remember` change actually trimmed the recomposition count it was supposed to.
- Build a heat-map overlay for a "performance review" PR.

## Payload shape

`RecompositionPayload`, `RecompositionNode` in
[`:data-recomposition-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/recomposition/core).

```jsonc
// compose/recomposition (schemaVersion 2)
{
  "mode": "delta",                  // or "snapshot"
  "sinceFrameStreamId": "f-7a1c",   // populated in delta mode
  "inputSeq": 2,                    // monotonic per flushed delta
  "nodes": [
    // reason: PARAMETER_CHANGE | STATE_READ | BOTH | UNKNOWN
    { "nodeId": "1a2b3c4d", "count": 1, "reason": "STATE_READ" },
    { "nodeId": "5e6f7a8b", "count": 1, "reason": "PARAMETER_CHANGE" }
  ]
}
```

Each node's `reason` is attributed from the Compose runtime's
`onScopeInvalidated(scope, value)` signal compared against the recompose
count: a scope invalidated by a snapshot write it subscribed to reads
`STATE_READ`, one that re-ran only because its caller did reads
`PARAMETER_CHANGE`, and a mix reads `BOTH`. This is the
"why did this recompose?" signal — a parent reading state and forwarding
it as parameters shows up as one `STATE_READ` dragging a fan of
`PARAMETER_CHANGE` children.

Nodes also carry nullable `bounds` (`{x, y, width, height}`) and
source-marker fields (`sourceFile` / `sourceLine` / `sourceColumn` /
`functionName`) for the heat-map and source-overlay surfaces. These are
present on the v2 wire but not yet populated — the post-layout bounds
join and slot-table reflection that fill them are deferred increments
(see [issue #1605](https://github.com/yschimke/compose-ai-tools/issues/1605)).

## Enabling

Producer requires *instrumented* render mode and runs when its
extension is publicly enabled. From an MCP client:

```jsonc
{ "method": "tools/call", "params": { "name": "subscribe_preview_data",
  "arguments": { "uri": "compose-preview://<id>/_module/com.example.Foo",
                 "kind": "compose/recomposition" } } }
```

For PR-review style "did this PR add unnecessary recomposition?"
guidance, see
[`compose-preview-review/references/agent-audits.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/agent-audits.md).

## Companion products

- [Layout inspector](../layout-inspector) — `layout/inspector` to map `nodeId` to source.
- [Render trace](../render) — `render/trace`, `render/composeAiTrace` for the wall-clock side of the same render.
