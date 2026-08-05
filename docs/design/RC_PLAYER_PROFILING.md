# Profiling the CMP Remote Compose player

The CMP Remote Compose player ([`:rc-player-*`](RC_CMP_WASM_PLAYER.md)) is instrumented with
`androidx.tracing` 2.x, and this document is what that instrumentation says about four typical
documents. It covers what is traced, why the tracing seam is shaped the way it is, how to reproduce
a run, and what the numbers currently show.

## What is traced

Spans are opened by the player itself, through [`:rc-player-trace`](../../rc-player/trace). Nothing in
the player decides whether they are *recorded* — that is the embedding process's call.

| Span | Category | Fires |
| --- | --- | --- |
| `rc:decode` | `rc-player.document` | wire bytes → `RcDocument` (`RcDocumentCodec.decode`) |
| `rc:encode` | `rc-player.document` | `RcDocument` → wire bytes |
| `rc:link` | `rc-player.document` | flat operation stream → nested nodes (`RcDocumentLinker`) |
| `rc:layoutTree` | `rc-player.document` | linked nodes → component tree (`RcLayoutTree`) |
| `rc:decodeImages` / `rc:decodeFonts` | `rc-player.document` | inline `BitmapData` / `FontData` decode |
| `rc:fetchDocument` | `rc-player.document` | the Wasm host's `fetch` of the `.rc` document |
| `rc:beginFrame` | `rc-player.frame` | per-frame state reset and time base |
| `rc:drawRoot` | `rc-player.frame` | the raw-document paint pass (no layout root) |
| `rc:drawCanvas` | `rc-player.frame` | one canvas component's paint pass |
| `rc:measureText` | `rc-player.frame` | canvas-drawn text measurement |
| `rc:clickAreaHitTest` | `rc-player.input` | legacy `ClickArea` hit test |
| `rc:actions` | `rc-player.input` | a click / touch / run-action block executing |

There is also an `rc:operations` counter carrying the document's operation count, so a timeline shows
document size next to the phases it drives.

The vendored JVM embedded player ([`:third-party-rc-embedded-player-jvm`](../../third_party/rc-embedded-player-jvm))
writes to the *same* tracer under `rc-embedded.document` / `rc-embedded.frame`
(`rcEmbedded:parseDocument`, `rcEmbedded:initContext`, `rcEmbedded:renderFrame`,
`rcEmbedded:encodePng`), so one capture can hold both render lanes side by side. It calls
`androidx.tracing` directly rather than through our facade — it renders AndroidX's player, so it
should not acquire a dependency on ours.

## Why there is a facade rather than direct `androidx.tracing` calls

`androidx.tracing:tracing:2.x` is a Kotlin Multiplatform library, but as of `2.0.0-rc01` it publishes
only `androidJvm` and `jvm` (desktop) variants — there is no wasmJs klib and no Apple klib. The
player targets desktop, wasmJs, and three iOS architectures, so `commonMain` cannot name
`androidx.tracing.Tracer` at all.

`RcTracePlatform` is the `expect` seam that resolves this, one actual per target:

- **desktop / JVM** — `androidx.tracing.Tracer.global`. Note this is *not* `androidx.tracing.Trace`:
  the desktop actual of `Trace.beginSection` is an explicit no-op, while `Tracer` carries through to
  a real Perfetto trace when a driver is installed.
- **wasmJs** — `performance.mark` / `performance.measure`, so the same span names appear on a DevTools
  performance timeline. Off until the host opts in with `?rcTrace=1`.
- **iOS** — a no-op. `os_signpost` is a C macro needing a cinterop `.def` and a Mac-only build; the
  recorder below still works there.

Alongside the platform tracer, `RcTrace.recorder` is a common-code span collector. It exists because
the platform tracer is unavailable or unreadable on exactly the targets whose numbers are hardest to
get, and because a profiling *run* wants per-section statistics rather than a timeline. It is what
produces the tables below, identically on every target.

**No player module ever calls `Tracer.setGlobalTracer`.** androidx documents that as something
libraries must not do — installing a tracer overrides a decision belonging to the process. The only
caller in this repository is `:rc-player-profile`'s `main`.

## Reproducing a run

```sh
./gradlew :rc-player-profile:rcPlayerProfile
```

It runs headless: `ImageComposeScene` rasterizes through skiko's software path with no `DISPLAY`
(see [AGENTS.md](../AGENTS.md)). Output lands in `rc-player/profile/build/profile/`:

- `profile.md` — the tables reproduced below.
- `<scenario>.json` — Chrome Trace Event timelines, openable at
  [ui.perfetto.dev](https://ui.perfetto.dev/) or `chrome://tracing`.
- `<scenario>.png` — what each document actually rendered.
- `perfetto/*.perfetto-trace` — the same spans as Perfetto `TracePacket`s, written by
  `androidx.tracing:tracing-wire`'s `TraceDriver`.

If the run dies with `UnsatisfiedLinkError: … libskiko-linux-x64.so: libGL.so.1`, that is the
sandbox's missing desktop natives, not the profile — see
[DESKTOP_NATIVE_DEPS.md](../DESKTOP_NATIVE_DEPS.md).

## The four documents

Small and typical rather than a stress test: the first question a profile has to answer is where the
time goes for the shapes of document people actually ship. All four are 320×180 and are defined in
[`RcProfileDocuments`](../../rc-player/profile/src/main/kotlin/ee/schimke/composeai/rcplayer/profile/RcProfileDocuments.kt).

| | |
| --- | --- |
| **`static-button-text`** — a rounded, filled box with a centred text label. The layout-tree path, no canvas operations. | ![static-button-text](../renders/rc-player-profile/static-button-text.png) |
| **`static-canvas`** — fill, plate, circle, two strokes and a drawn text run. No layout root, so the player takes its raw draw path. | ![static-canvas](../renders/rc-player-profile/static-canvas.png) |
| **`animated-canvas`** — the same canvas plus a document-load clock sweeping a highlight (visible at the left edge). | ![animated-canvas](../renders/rc-player-profile/animated-canvas.png) |
| **`interactive-button`** — the button made clickable: ripple, host action, and a float the canvas below reads back. Captured after a tap, so the ripple and the confirmation dot are both showing. | ![interactive-button](../renders/rc-player-profile/interactive-button.png) |

Each scenario runs 12 fresh loads × 30 frames, after an untraced warm-up. Loads and frames are
separate knobs because they answer separate questions: a document is decoded, linked and turned into
a layout tree once, and drawn every frame.

## Results

Measured on the numbers below; re-run the task for your own machine.

- **JVM** — OpenJDK 64-Bit Server VM 17.0.19
- **OS** — Linux amd64
- **CPUs** — 4
- **Renderer** — Compose Desktop `ImageComposeScene` (skiko software raster), density 1

## `static-button-text`

Rounded button with a centred text label — layout tree, no canvas operations

21 operations, 242 B on the wire, 12 loads × 30 frames.

### Load (once per document)

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:decode` | 12 | 4.38 ms | 365.2 µs | 118.6 µs | 2.94 ms | 2.94 ms |
| `rc:layoutTree` | 12 | 2.63 ms | 219.3 µs | 200.4 µs | 467.0 µs | 467.0 µs |
| `rc:link` | 12 | 818.2 µs | 68.2 µs | 51.4 µs | 292.4 µs | 292.4 µs |
| `rc:decodeImages` | 12 | 87.5 µs | 7.29 µs | 6.53 µs | 11.2 µs | 11.2 µs |
| `rc:decodeFonts` | 12 | 67.2 µs | 5.60 µs | 5.24 µs | 8.44 µs | 8.44 µs |

### Frame and input

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:beginFrame` | 24 | 343.9 µs | 14.3 µs | 13.8 µs | 26.6 µs | 28.6 µs |

## `static-canvas`

Fill, plate, circle, two strokes and a drawn text run

12 operations, 256 B on the wire, 12 loads × 30 frames.

### Load (once per document)

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:decode` | 12 | 2.10 ms | 175.0 µs | 136.7 µs | 589.9 µs | 589.9 µs |
| `rc:link` | 12 | 286.2 µs | 23.8 µs | 18.8 µs | 60.3 µs | 60.3 µs |
| `rc:decodeImages` | 12 | 97.0 µs | 8.08 µs | 5.18 µs | 38.9 µs | 38.9 µs |
| `rc:layoutTree` | 12 | 86.4 µs | 7.20 µs | 6.96 µs | 12.0 µs | 12.0 µs |
| `rc:decodeFonts` | 12 | 77.9 µs | 6.50 µs | 3.58 µs | 31.1 µs | 31.1 µs |

### Frame and input

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:drawRoot` | 12 | 95.40 ms | 7.95 ms | 7.97 ms | 8.39 ms | 8.39 ms |
| `rc:measureText` | 12 | 84.47 ms | 7.04 ms | 7.04 ms | 7.60 ms | 7.60 ms |
| `rc:beginFrame` | 24 | 328.5 µs | 13.7 µs | 13.2 µs | 22.9 µs | 25.8 µs |

## `animated-canvas`

The static canvas plus a document-load clock sweeping a highlight every frame

16 operations, 336 B on the wire, 12 loads × 30 frames.

### Load (once per document)

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:decode` | 12 | 1.84 ms | 153.7 µs | 149.1 µs | 254.0 µs | 254.0 µs |
| `rc:link` | 12 | 135.6 µs | 11.3 µs | 10.9 µs | 14.0 µs | 14.0 µs |
| `rc:layoutTree` | 12 | 104.2 µs | 8.68 µs | 5.46 µs | 30.9 µs | 30.9 µs |
| `rc:decodeImages` | 12 | 45.2 µs | 3.77 µs | 3.39 µs | 6.43 µs | 6.43 µs |
| `rc:decodeFonts` | 12 | 27.8 µs | 2.32 µs | 2.12 µs | 3.74 µs | 3.74 µs |

### Frame and input

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:drawRoot` | 303 | 160.67 ms | 530.3 µs | 228.0 µs | 513.8 µs | 8.18 ms |
| `rc:measureText` | 303 | 91.32 ms | 301.4 µs | 29.7 µs | 77.4 µs | 7.37 ms |
| `rc:beginFrame` | 315 | 2.15 ms | 6.81 µs | 5.41 µs | 20.8 µs | 78.4 µs |

## `interactive-button`

The button made clickable — ripple, host action, and a float the canvas reads

35 operations, 328 B on the wire, 12 loads × 30 frames.

### Load (once per document)

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:layoutTree` | 12 | 3.26 ms | 271.8 µs | 193.6 µs | 931.3 µs | 931.3 µs |
| `rc:decode` | 12 | 1.89 ms | 157.1 µs | 147.8 µs | 244.8 µs | 244.8 µs |
| `rc:link` | 12 | 596.5 µs | 49.7 µs | 48.2 µs | 94.0 µs | 94.0 µs |
| `rc:decodeImages` | 12 | 57.3 µs | 4.78 µs | 4.45 µs | 6.64 µs | 6.64 µs |
| `rc:decodeFonts` | 12 | 45.3 µs | 3.78 µs | 3.43 µs | 5.51 µs | 5.51 µs |

### Frame and input

| Section | Count | Total | Mean | p50 | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `rc:actions` | 72 | 4.84 ms | 67.3 µs | 50.6 µs | 77.0 µs | 1.34 ms |
| `rc:drawCanvas` | 281 | 3.13 ms | 11.1 µs | 8.62 µs | 34.1 µs | 54.6 µs |
| `rc:beginFrame` | 96 | 535.5 µs | 5.58 µs | 4.36 µs | 7.94 µs | 70.7 µs |

## What the numbers say

**Text measurement dominates, and it is re-done every frame.** On `static-canvas`, `rc:measureText`
is 7.04 ms of a 7.95 ms paint — 88% of the first frame, for a *single six-character run*. Most of
that is cold-start font resolution and shaping, but the steady state is the more interesting number:
on `animated-canvas` the text is re-measured on all 303 painted frames, at 29.7 µs p50. Nothing in
the document changed; the player simply calls `TextMeasurer.measure` again on every pass, and then
`drawText(textMeasurer, text, …)` measures a *second* time internally with the same inputs. A
`(text, style, layoutDirection) → TextLayoutResult` cache on `RcPaintState` — or hoisting the
measurement out of `drawTextOperation` and passing the layout into `drawText` — is the obvious next
change, and this profile is the baseline to judge it against.

**The load phases are cheap and dominated by decode.** Across all four, `rc:decode` + `rc:link` +
`rc:layoutTree` land in the low hundreds of microseconds for documents of 12–35 operations. `rc:link`
is consistently the cheapest of the three and `rc:layoutTree` grows with component count (7 µs for the
rootless canvas, 272 µs for the interactive button's five components), which is the shape you would
want. Inline image and font decode are noise here only because these documents carry no inline
assets; both spans exist precisely so a document that does carry them shows where the load time went.

**Static documents paint once, not once per frame.** `static-canvas` renders 12 `rc:drawRoot` spans
for 12 loads × 30 frames — Compose has nothing to invalidate after the first frame, so 29 of every 30
`render()` calls do no player work at all. Only `animated-canvas` reaches the ~25 paints per load
that its `TimeAttribute` clock asks for. Any future "the player is slow at 60fps" claim needs to say
which of these two regimes it is talking about.

**Input is a repaint, not a hit test.** On `interactive-button` a tap costs 50.6 µs p50 in
`rc:actions` — the action block itself — and then triggers a repaint of the canvas that reads the
float it wrote (`rc:drawCanvas`, 281 spans at 8.62 µs p50). The hit test never appears, because a
click modifier is dispatched by Compose's own pointer input; `rc:clickAreaHitTest` only fires for the
legacy `ClickArea` path. The occasional 1.34 ms `rc:actions` outlier is worth a look if interaction
latency ever becomes a complaint.

**Layout-tree text is invisible to the player's spans.** `static-button-text` has no draw span at
all, because its label goes to Compose's `BasicText`, which owns measurement, shaping and painting.
The player can time what it hands over and what comes back, but not what happens inside. That is why
the canvas documents carry the drawn text run: `DrawTextRun` is the only text path the player
measures itself.

## One thing this profile found by accident

The first version of the harness handed `RcComposePlayer` a bare `Modifier`. The raw-document path
paints into a `Canvas` sized by the modifier the host supplies, so that canvas measured 0×0 — and
because Compose does not clip by default, every explicitly-positioned draw operation still landed
correctly while canvas-drawn text, whose layout constraints come from the `DrawScope`'s `size`,
silently laid out into nothing. The timing tables looked perfectly healthy throughout.

Two consequences, both kept:

1. The profile renders and writes a PNG for every scenario, and
   [`RcProfileRunnerTest`](../../rc-player/profile/src/test/kotlin/ee/schimke/composeai/rcplayer/profile/RcProfileRunnerTest.kt)
   fails if any scenario renders a single flat colour. A profile of a document that did not draw is
   worse than no profile.
2. The player's raw path arguably ought to fall back to the document header's dimensions when the
   host gives it no size, rather than rendering text-lessly. That is a behaviour change with its own
   visual review, so it is noted here rather than folded into this instrumentation.
