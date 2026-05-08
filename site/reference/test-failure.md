---
title: Test failure
parent: Reference
nav_order: 15
permalink: /reference/test-failure/
---

# Test failure

Postmortem bundle delivered after a `renderFailed` notification:
phase, error type / message / top stack frame, a bounded stack trace,
and explicit fallback fields for partial screenshot, pending effects,
animation state, and a redacted snapshot summary.

## At a glance

| | |
|---|---|
| Kind | `test/failure` |
| Schema version | 1 |
| Modules | `:data-render-core` (published) · `:data-render-connector` |
| Render mode | failed render |
| Cost | low |
| Token usage | ~195 tok per query (`test/failure` ~680 chars). See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | inline |
| Availability | fetch-only after `renderFailed` |
| Platforms | Android · Desktop · shared |

## What it answers

- Why did the most recent render of this preview fail?
- What phase failed (`compose`, `capture`, `encode`, `setup`)?
- What error type / message / top stack frame, and a bounded stack trace?
- What was the renderer's last visible state — pending effects, running animations, frame-clock state — at the moment of failure?

## What it does NOT answer

- It does not include a partial screenshot in schema v1 (`partialScreenshotAvailable: false`); the fallback fields are placeholders for a future schema bump.
- It does not capture state object values — `lastSnapshotSummary.redaction` is "state values are not captured in schema v1".
- It is not a live debugger — read the stack and the phase, then iterate; you will not get a step-debugger session out of this.

## Use cases

- Triage a CI failure: fetch `test/failure` for every red preview to cluster failures by error type / phase.
- Distinguish a renderer bug from a consumer bug — a NPE in the consumer's `LaunchedEffect` is theirs; a Robolectric sandbox boot failure is the renderer's.
- Drive a "show me the failure" panel in the VS Code extension when a preview can't render.

## Payload shape

`TestFailureDataProduct` in
[`:data-render-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/render/core).

```jsonc
// test/failure
{
  "status": "failed",
  "phase": "compose",
  "error": {
    "type": "NullPointerException",
    "message": "Cannot invoke \"...\" because \"...\" is null",
    "topFrame": "HomeScreen.kt:42",
    "stackTrace": [
      "com.example.HomeScreenKt.HomeScreen(HomeScreen.kt:42)",
      "..."
    ]
  },
  "partialScreenshot": null,
  "partialScreenshotAvailable": false,
  "pendingEffects": [],
  "runningAnimations": [],
  "frameClockState": { "status": "unknown" },
  "lastSnapshotSummary": {
    "stateObjects": null,
    "valuesCaptured": false,
    "redaction": "state values are not captured in schema v1"
  }
}
```

## Enabling

Always available for the Render-pipeline extension. After a
`renderFailed` notification, call:

```jsonc
{ "method": "tools/call", "params": { "name": "get_preview_data",
  "arguments": { "uri": "compose-preview://<id>/_module/com.example.Foo",
                 "kind": "test/failure" } } }
```

The kind is fetch-only — it does not ride attachments.

## Companion products

- [Render trace](../render) — `render/trace`, `render/composeAiTrace` for successful-render timing context.
- [Layout inspector](../layout-inspector) — `layout/inspector` from a previous *successful* render to compare against the failure point.
