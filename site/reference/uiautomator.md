---
title: UIAutomator hierarchy
parent: Reference
nav_order: 14
permalink: /reference/uiautomator/
---

# UIAutomator hierarchy

A selector-shaped projection of Compose semantics: just enough metadata
per node to formulate a UIAutomator-style `By.text(...)` /
`By.contentDescription(...)` selector that uniquely targets it.

## At a glance

| | |
|---|---|
| Kind | `uia/hierarchy` |
| Schema version | 1 |
| Modules | `:data-uiautomator-core` (published) · `:data-uiautomator-hierarchy-android` (published) · `:data-uiautomator-connector` |
| Render mode | default |
| Cost | low |
| Token usage | Inline JSON, not yet benchmarked (scales with selector node count). See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Android |

## What it answers

- For each actionable node, what selector inputs are available — `text`, `contentDescription`, `testTag`, `role`?
- Which `uia.*` actions does it support — `uia.click`, `uia.scrollForward`, `uia.inputText`, …?
- What is the node's bounds in source-bitmap pixels (same shape as `AccessibilityNode`'s `boundsInScreen`)?
- Does the node have testTag ancestors that a `hasParent({testTag: …})` / `hasAncestor` selector chain could resolve?

The default filter keeps only nodes that expose at least one of the
supported actions, dropping ~80% of layout-wrapper / pure-text nodes
while still emitting everything an agent's click / scroll / type
could plausibly target. Snapshots can be taken against the merged
semantics tree (the on-device UIAutomator default — `Button { Text("Submit") }`
collapses into one node) or the unmerged tree.

## What it does NOT answer

- It is not a full SemanticsNode dump — for that, use [`compose/semantics`](../layout-inspector).
- It does not execute a selector — it gives you what is *targetable*; running a `uia.click` is the daemon's `renderNow` path, not a data product fetch.
- It does not include modifier or constraint detail; pair with [`layout/inspector`](../layout-inspector) when you need positioning context.

## Use cases

- Generate a UIAutomator-like end-to-end test plan for a screen without touching an emulator.
- Confirm every actionable node has *at least one* stable selector input — fail review if a button has neither `text`, `contentDescription`, nor `testTag`.
- Train an agent's "which selector should I use" heuristic on the same shape it will see at runtime.

## Payload shape

`UiAutomatorHierarchyNode`, `Selector` in
[`:data-uiautomator-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/uiautomator/core).

```jsonc
// uia/hierarchy
{
  "merged": true,
  "nodes": [
    { "text": "Submit", "contentDescription": null,
      "testTag": "submit-button",
      "testTagAncestors": ["checkout-screen"],
      "role": "Button",
      "actions": ["uia.click"],
      "boundsInScreen": "48,200,144,232" }
  ]
}
```

## Enabling

Producer runs once the UIAutomator extension is publicly enabled.
Hierarchy snapshot is emitted by `UiAutomatorHierarchyExtractor` in
`:data-uiautomator-hierarchy-android` (Android-only).

## Companion products

- [Layout inspector](../layout-inspector) — `compose/semantics` for the full SemanticsNode shape and `layout/inspector` for positioning.
- [Focus](../focus) — `compose/focus` for the focused-node subset that a directional traversal would target.
- [Accessibility](../a11y) — `a11y/hierarchy` for the assistive-technology view of the same tree.
