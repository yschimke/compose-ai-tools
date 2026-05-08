---
title: Render trace
parent: Reference
nav_order: 8
permalink: /reference/render/
---

# Render trace

Pipeline-level kinds emitted by the renderer itself: a Perfetto-importable
Chrome trace, a phase breakdown, the device clip mask, and the device
background.

## At a glance

| | |
|---|---|
| Kinds | `render/composeAiTrace`, `render/trace`, `render/deviceClip`, `render/deviceBackground` |
| Schema version | 1 |
| Modules | `:data-render-core` (published) · `:data-render-connector` · `:data-render-compose` |
| Render mode | default · live |
| Cost | low |
| Transport | inline (JSON) · path (PNG for clip / background) |
| Platforms | Android · Desktop · shared |

## What it answers

- **`render/composeAiTrace`** — a full Perfetto / Chrome-trace JSON of the render pipeline (discovery → setup → compose → capture → encode). Drop it into [`ui.perfetto.dev`](https://ui.perfetto.dev) to see exactly where time went.
- **`render/trace`** — a flatter, summary-shaped phase breakdown (`totalMs`, ordered phases, free-form metrics map). Cheap to read in a CI log.
- **`render/deviceClip`** — the device-shape clip mask used for round Wear watches and other non-rectangular previews. Pure-image.
- **`render/deviceBackground`** — the device chrome / wallpaper layer composited under the preview. Pure-image.

## What it does NOT answer

- `render/trace` is a renderer pipeline trace, not a Compose composition trace — for that, use [`compose/recomposition`](../recomposition).
- It does not contain on-device frame timing (Choreographer, jank stats) — the renderer runs under Robolectric / `ImageComposeScene`, not on a real Choreographer.
- The Perfetto trace is sampled at function boundaries, not at the JVM-method granularity of an async-profiler dump.

## Use cases

- Diagnose a slow render that nudges past `daemon.dataFetchRerenderBudgetMs` — open the Perfetto trace and find the phase that ran long.
- Build a CI dashboard of `totalMs` per preview to catch gradual regression.
- Verify a Wear round-device clip mask matches the watch face's intended bezel.
- Composite a custom device background under a preview for marketing screenshots.

## Payload shape

`PerfettoTraceDataProducer`, `RenderTraceDataProduct` in
[`:data-render-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/render/core).

```jsonc
// render/trace
{
  "totalMs": 412,
  "phases": [
    { "name": "render", "startMs": 0, "durationMs": 412 }
  ],
  "metrics": { "tookMs": 412, "previewWidthPx": 1080, "previewHeightPx": 1920 }
}

// render/composeAiTrace — Chrome trace JSON, importable into ui.perfetto.dev
{ "traceEvents": [ { "ph": "X", "name": "compose", "ts": 1234, "dur": 89, … } ] }
```

## Enabling

`render/composeAiTrace` is gated by the JVM property
`composeai.daemon.perfettoTrace=true`. The other three kinds run
whenever their extension is publicly enabled. CLI / Gradle output:

- `build/compose-previews/data/<id>/render-perfetto-trace.json`
- `build/compose-previews/data/<id>/render-trace.json`
- `build/compose-previews/data/<id>/render-deviceClip.png`
- `build/compose-previews/data/<id>/render-deviceBackground.png`

## Companion products

- [Test failure](../test-failure) — `test/failure` for postmortems when a render fails before producing a trace.
- [Recomposition](../recomposition) — `compose/recomposition` for the per-node side of the wall-clock story.
