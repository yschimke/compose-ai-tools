---
title: Layout inspector
parent: Reference
nav_order: 6
permalink: /reference/layout-inspector/
---

# Layout inspector

The Compose layout / component hierarchy with bounds, constraints,
modifiers, and source refs — plus a sibling `compose/semantics`
projection for testTag / role / mergeMode questions.

## At a glance

| | |
|---|---|
| Kinds | `layout/inspector`, `compose/semantics` |
| Schema version | 1 |
| Modules | `:data-layoutinspector-core` (published) · `:data-layoutinspector-connector` |
| Render mode | default |
| Cost | low |
| Token usage | Inline JSON, not yet benchmarked (scales with hierarchy depth). See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android · Desktop · shared |

## What it answers

- **`layout/inspector`** — what's the parent / child shape of this composable? Measured size? Constraints? Z-order? Which inspectable modifiers are attached, with what values? Which source file / line declared the node?
- **`compose/semantics`** — for each `SemanticsNode`, what is its `testTag`, `role`, `mergeMode`, `bounds`?

It is intentionally separate from [`a11y/hierarchy`](../a11y) — the
question there is "what does an assistive technology see"; the
question here is layout structure or stable test selectors.

## What it does NOT answer

- It does not run accessibility checks — that is `a11y/atf`.
- It does not measure recomposition — that is [`compose/recomposition`](../recomposition).
- The Desktop / shared backend reaches the layout tree through `RootForTest`; non-Compose Android views (interop) are not modelled.

## Use cases

- Pinpoint which composable owns a misaligned region from [`history/diff/regions`](../history).
- Confirm a refactor preserved testTags so existing UI tests keep finding their targets.
- Inspect resolved `Modifier.padding(...)` values when a screenshot says one thing and the source says another.
- Drive the VS Code extension's tree view of a preview.

## Payload shape

`LayoutInspectorPayload`, `LayoutInspectorNode`,
`ComposeSemanticsPayload`, `ComposeSemanticsNode` in
[`:data-layoutinspector-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/layoutinspector/core).

```jsonc
// layout/inspector
{
  "nodes": [
    { "id": 1, "name": "Column", "boundsInScreen": "0,0,1080,1920",
      "measuredSize": "1080x1920",
      "constraints": "minW=0, maxW=1080, minH=0, maxH=1920",
      "modifiers": [{ "name": "padding", "args": "16dp" }],
      "sourceRef": "HomeScreen.kt:42" }
  ]
}

// compose/semantics
{
  "nodes": [
    { "testTag": "submit-button", "role": "Button",
      "mergeMode": "Merged", "boundsInScreen": "48,200,144,232" }
  ]
}
```

## Enabling

Producer runs whenever its owning extension is publicly enabled.
Backed on Android by the daemon's `RootForTest` carried on
`PreviewContext.inspection`. CLI / Gradle output:
`build/compose-previews/data/<id>/layout-inspector.json` and
`compose-semantics.json`.

## Companion products

- [Accessibility](../a11y) — `a11y/hierarchy` for the assistive-technology view of the same nodes.
- [Recomposition](../recomposition) — `compose/recomposition` for per-node recomposition counts keyed off the same node ids.
- [Theme](../theme) — `compose/theme` for the tokens consumed at each node.
