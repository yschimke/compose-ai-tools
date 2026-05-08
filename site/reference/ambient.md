---
title: Ambient (Wear)
parent: Reference
nav_order: 2
permalink: /reference/ambient/
---

# Ambient

Drive Wear OS `AmbientMode` overrides through the preview pipeline so a
single `@Preview` can be rendered in *Interactive* and *Ambient* modes
without touching the on-device Wear Services SDK.

## At a glance

| | |
|---|---|
| Kind | `compose/ambient` |
| Schema version | 1 |
| Modules | `:data-ambient-core` (published) · `:data-ambient-connector` |
| Render mode | default |
| Cost | low |
| Token usage | Inline JSON, not yet benchmarked. See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Platforms | Wear OS (Android) |

## What it answers

- What does this composable look like in *Ambient* mode (low-power, monochrome) vs. *Interactive*?
- Does the consumer's `LocalAmbientModeManager.current?.currentAmbientMode` read site behave correctly under each state?
- Did wake-on-input transitions (touch, RSB rotary scroll) flip the state back to `Interactive` as expected?

The connector primes an `AmbientStateController` and installs a
`LocalAmbientModeManager` composition local backed by it, so consumer
code that wraps its UI in horologist's `AmbientAware { ... }` — or
reads the manager directly — sees the requested state without any
real Wear Services dependency. The legacy
`androidx.wear.ambient.AmbientLifecycleObserver` callback fan-out is
preserved through `ShadowAmbientLifecycleObserver` under Robolectric.

## What it does NOT answer

- It does not measure battery impact or screen-burn-in risk of a given Ambient design — those need on-device measurement.
- It does not enforce always-on display constraints (≤15% pixels lit, no animation); use a linter or design-review checklist for that.

## Use cases

- Render an Ambient mode baseline of every Wear watch-face preview alongside the Interactive baseline.
- Verify a watch-face redesign keeps the time legible after the state flip.
- Test wake-on-input handling for a custom complication tap target.

## Payload shape

`AmbientPayload` and the matching `AmbientOverride` /
`AmbientStateOverride` types in
[`:data-ambient-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/ambient/core).

```jsonc
// renderNow.overrides.ambient
{
  "state": { "value": "Ambient" },          // or "Interactive"
  "lowBitAmbient": false,
  "burnInProtection": false
}
```

## Enabling

The producer's `onRender` runs whenever its owning extension is
publicly enabled. From the daemon side, that is the
`extensions/enable` handshake; from Gradle, the extension is wired
when the Wear sample (or a consumer with the Wear toolkit on the
classpath) applies the plugin.

## Companion products

- [Wallpaper](../wallpaper) — `compose/wallpaper` for Material You seed colours that drive the Ambient palette.
- [Theme](../theme) — `compose/theme` for the resolved Material 3 tokens at the active Ambient state.

See also
[`skills/compose-preview/design/WEAR_UI.md`](https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/design/WEAR_UI.md).
