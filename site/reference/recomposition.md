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
| Schema version | 1 |
| Modules | `:data-recomposition-core` (published) · `:data-recomposition-connector` |
| Render mode | instrumented |
| Cost | medium |
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
// compose/recomposition
{
  "mode": "snapshot",         // or "clickDelta"
  "frameStreamId": "f-7a1c",  // when click-delta
  "nodes": [
    { "nodeId": 12, "name": "Row", "count": 3,
      "boundsInScreen": "0,80,1080,160" },
    { "nodeId": 14, "name": "Text", "count": 12,
      "boundsInScreen": "16,96,1064,144" }
  ]
}
```

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
[`compose-preview-review/design/AGENT_AUDITS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview-review/design/AGENT_AUDITS.md).

## Companion products

- [Layout inspector](../layout-inspector) — `layout/inspector` to map `nodeId` to source.
- [Render trace](../render) — `render/trace`, `render/composeAiTrace` for the wall-clock side of the same render.
