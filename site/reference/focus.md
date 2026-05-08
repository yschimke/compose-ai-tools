---
title: Focus
parent: Reference
nav_order: 3
permalink: /reference/focus/
---

# Focus

Snapshot the Compose focus state of a preview and drive directional
focus traversal (`Up`, `Down`, `Left`, `Right`, `Next`, `Previous`) so
agents can verify keyboard / D-pad navigation without an emulator.

## At a glance

| | |
|---|---|
| Kind | `compose/focus` |
| Schema version | 1 |
| Modules | `:data-focus-core` (published) · `:data-focus-connector` |
| Render mode | default |
| Cost | low |
| Transport | inline |
| Platforms | Android · Desktop · Wear · shared |

## What it answers

- Which node holds focus right now? What `testTag`, role, label?
- After a directional `moveFocus(FocusDirection.Down)`, where does focus land?
- Is there a focus trap, dead end, or unexpected wrap?
- Does the focus ring overlay match the focusable bounds the developer expects?

The connector ships a `FocusController` that drives Compose's
`FocusManager` and a `FocusOverlay` that renders the focused-node
bounding box on top of the captured PNG.

## What it does NOT answer

- It does not test physical input devices (rotary, gamepad, IME hardware) — it operates on the abstract `FocusDirection` enum.
- It does not score focus order against a designer-defined intent — it reports what the framework chose; reading whether that's right is on the reviewer.

## Use cases

- TV / Wear traversal: render every `Up`/`Down`/`Left`/`Right` step from a known starting node and confirm the path lands on the intended target.
- Dialog focus restoration: verify focus comes back to the trigger button after a dialog dismisses.
- Form regression: catch a refactor that accidentally makes a `TextField` non-focusable.

## Payload shape

`Material3FocusProduct.KIND` /  `FocusOverride` and `FocusDirection`
in [`:data-focus-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/focus/core)
and `daemon:core`.

```jsonc
// compose/focus
{
  "focused": {
    "testTag": "submit-button",
    "role": "Button",
    "label": "Submit",
    "boundsInScreen": "48,200,144,232"
  },
  "history": [
    { "direction": "Down", "from": "name-field", "to": "submit-button" }
  ]
}
```

## Enabling

Pass an override on `renderNow.overrides.focus` (MCP) to drive
traversal, or enable the extension and read focused-node snapshots on
every render.

## Companion products

- [UIAutomator hierarchy](../uiautomator) — `uia/hierarchy` for the broader set of selector-shaped nodes a click / scroll / type could target.
- [Layout inspector](../layout-inspector) — `compose/semantics` for testTag / role / mergeMode of the focused node.
