# Interactive mode (VS Code panel ↔ daemon)

> **Status:** v1 — live-streaming surface plus `interactive/start` /
> `interactive/stop` / `interactive/input` RPCs in the daemon, behind the
> `composePreview.experimental.daemon.enabled` flag. Click coordinates are
> forwarded to the daemon as `interactive/input` notifications; today the
> daemon treats each input as a render trigger (LocalInspectionMode is still
> true, so the renderer doesn't yet dispatch the click into the composition —
> that's the v2 follow-up). Frame deduplication ships in v1: bytes-identical
> follow-up renders carry `renderFinished.unchanged: true`, the client
> short-circuits, and history is not re-archived. See
> [PROTOCOL.md](PROTOCOL.md) for the wire contract this builds on.

## 1. What it is

A new **focus-mode toggle** in the live preview panel that turns a single
focused preview into a "live" stream: every render the daemon pushes lands
on the card immediately, with a visible affordance (a pulsing **LIVE**
badge) so the user knows they're looking at the freshest possible bytes
rather than the last save's render.

Today this rides entirely on the existing `renderFinished` notification —
the daemon is already pushing per-render PNG paths over the v1 protocol
(see [PROTOCOL.md § 6](PROTOCOL.md#6-daemon--client-notifications)) and the
panel is already wired to swap them in via `updateImage`. Interactive mode
is the **UI shell** that:

1. Pins the focused preview as the daemon's render priority (`setFocus`).
2. Swaps `<img>` bytes without the `fade-in` animation that normally tags a
   one-off render — successive frames need to read as a stream, not as a
   sequence of independent reloads.
3. Surfaces "daemon not ready for this module" cleanly: the toggle is
   disabled with a tooltip rather than failing silently.
4. Captures mouse-click coordinates on the rendered image and **logs them
   to the extension output channel** for now, so the wire shape is
   exercised and the click-target geometry is debuggable before any
   round-trip RPC ships.

## 2. Why daemon-only

The Gradle path's "render previews" task takes seconds even on a warm
build. Streaming a sequence of frames at that latency would feel like
nothing — the user can't tell the difference between live mode and the
existing save-debounce loop. Sub-second push is the whole point.

A toggle that's silently a no-op when the daemon is off is worse than no
toggle. So the affordance is **only rendered** when the gate reports the
daemon is healthy for the focused preview's owning module
(`DaemonGate.isDaemonReady(moduleId)`). If the daemon dies mid-session the
toggle disables itself; if the user toggles the daemon flag off in
settings, the toggle vanishes on the next focus change.

## 3. UI surface

Lives inside `focus-controls` next to the existing diff / launch-on-device
buttons. Visible only when:

- `layout-mode === 'focus'`
- The currently focused preview's module has a healthy daemon (extension
  pushes a `setInteractiveAvailability` message on daemon up/down).

```
[ ◄ ] 1/3 [ ► ]  [ ⟂HEAD ] [ ⟂main ] [ 🌐 device ]  [ 🔴 LIVE ] [ × ]
                                                       ──new──
```

Toggle states:

| State        | Icon                                 | Tooltip                                    |
|--------------|--------------------------------------|--------------------------------------------|
| Disabled     | `circle-large-outline` (codicon)     | "Daemon not ready — live mode unavailable" |
| Off (ready)  | `circle-large-outline`               | "Enter live mode (stream renders)"         |
| On           | `record` (red)                       | "Live · click to exit"                     |

When ON, the focused card carries:

- `.preview-card.live` class (CSS adds a 1-pixel red border and a
  bottom-right pulsing **LIVE** chip).
- `<img>` swaps clear `.fade-in`, so the next paint reads as a frame
  update, not a card reload.
- Click on the image is handled by `recordInteractiveClick(card, event)`
  which dispatches a `recordInteractiveClick` webview→extension message
  with image-pixel coordinates (see § 6).

## 4. Lifecycle

```
user clicks LIVE
   │
   ▼
webview→ext: { command: 'setInteractive', previewId, enabled: true }
   │
   ▼
extension:
  - resolves moduleId from previewModuleMap
  - daemonScheduler.setFocus(moduleId, [previewId])      // pin priority
  - daemonScheduler.renderNow(moduleId, [previewId],
                              tier='fast', reason='interactive-on')
  - logs '[interactive] live mode on for <previewId>'
   │
   ▼
daemon emits renderFinished(previewId, pngPath)
   │
   ▼
scheduler reads PNG, posts updateImage to webview
   │
   ▼
webview repaints the focused card (no fade-in when .live is active)
```

Exit path is symmetric: `setInteractive { enabled: false }` clears the
`.live` class and sends nothing further to the daemon (focus reverts to
whatever the next save / focus-change publishes).

## 5. Frame deduplication

Daemon-side, consulted on every `renderFinished` regardless of whether
interactive mode is active:

1. After the host returns a `RenderResult` whose `pngPath` points at a
   real on-disk PNG, the daemon SHA-256s the bytes.
2. If the hash matches the prior hash for the same `previewId`
   (tracked in `lastFrameHashes: Map<String, String>`), the
   `renderFinished` notification carries `unchanged: true` and the
   history archiver is skipped — there's nothing new to write.
3. The first `renderFinished` after `interactive/start` always paints
   (the start handler wipes the cached hash). Without this a
   `start` issued after a previous identical render would suppress
   the bootstrap frame and the LIVE chip would have nothing to show
   until the user clicks.

Client side (`DaemonScheduler.handleRenderFinished` in
`vscode-extension/src/daemon/daemonScheduler.ts`): `unchanged === true`
short-circuits the disk read + base64 + `postMessage` hop, leaving the
on-screen card untouched. Skipping work all the way to the webview is
the whole point of the field — without it the panel would re-paint
identical bytes and the user would see a tiny flicker on every
no-op render.

## 6. What changes in the extension

- `vscode-extension/src/types.ts` — new `WebviewToExtension` variants
  `setInteractive`, `recordInteractiveClick`; new `ExtensionToWebview`
  variant `setInteractiveAvailability` (per-module ready/not-ready
  signal).
- `vscode-extension/src/previewPanel.ts` — toggle button, LIVE badge,
  click handler, gate visibility on `setInteractiveAvailability`.
- `vscode-extension/src/extension.ts` — `setInteractive` handler that
  drives `daemonScheduler.setFocus` + `renderNow`. Polls
  `daemonGate.isDaemonReady` on focus changes / daemon channel
  open/close events and pushes availability to the panel.
- `vscode-extension/src/daemon/daemonProtocol.ts` — reserved type
  shapes for the future `interactive/start`, `interactive/stop`,
  `interactive/input` methods (§ 7). **No wire calls today** — we keep
  the types co-located so a future daemon implementation lands in
  lockstep without a schema reshuffle.

## 7. Click capture

The webview attaches a click handler to the focused card's `<img>`
**only when `.live` is set**. On click it computes:

```ts
{
  command: 'recordInteractiveClick',
  previewId,
  // Coordinates in IMAGE-NATURAL pixel space (i.e. the same pixel space
  // the daemon's renderer thinks in). Webview reads naturalWidth/Height
  // and scales the offsetX/Y by their displayed-vs-natural ratio.
  pixelX: number,
  pixelY: number,
  imageWidth: number,
  imageHeight: number,
}
```

Extension logs the message to the output channel. **No daemon call is
issued today.** Once `interactive/input` lands (§ 7) the same payload
shape feeds the RPC.

## 8. Click-input RPC (v1)

The protocol additions land in v1. Adding these methods does **not** bump
`protocolVersion` — they're additive and unknown methods are dropped per
[PROTOCOL.md § 7](PROTOCOL.md#7-versioning). Old clients drop the new
notifications silently; old daemons reject `interactive/start` with
`MethodNotFound (-32601)`, the panel logs and falls back to the legacy
setFocus + renderNow path.

### `interactive/start` (request, client → daemon)

```ts
// params
{ previewId: string }
// result
{ frameStreamId: string }           // opaque; passed back in interactive/input
```

Tells the daemon "this preview is an interactive target — keep a warm
sandbox for it, prefer it on every render-queue drain, do NOT recycle
its sandbox on idle." Returns a unique stream id the client uses for
input correlation.

**Multi-target on the wire.** Each `start` registers a fresh slot —
concurrent streams targeting different (or even the same) preview ids
coexist. Inputs route by `frameStreamId`, so a stop on one stream
leaves the others untouched. The current panel UI is single-target
(only one card carries `.live`), but the daemon does not require that;
a programmatic client (side-by-side comparison view, CI agent driving
multiple previews over one stdio pair) can drive concurrent streams
without any wire change.

### `interactive/stop` (notification, client → daemon)

```ts
{ frameStreamId: string }
```

Releases the warm-sandbox lock. Daemon resumes normal recycling
heuristics. Idempotent — a stop after stop is a no-op.

### `interactive/input` (notification, client → daemon)

```ts
{
  frameStreamId: string;
  kind: 'click' | 'pointerDown' | 'pointerUp' | 'keyDown' | 'keyUp';
  // Image-natural pixel coordinates. Daemon translates to dp using the
  // last render's density. Null for keyboard events.
  pixelX?: number;
  pixelY?: number;
  // For 'keyDown'/'keyUp' only.
  keyCode?: string;
}
```

Notification not request: input fires-and-forgets. The daemon dispatches
the input into the active composition (mechanism TBD — likely
`@TestSemantics` paths in the renderer) and emits a fresh
`renderFinished` for the same `previewId` once the composition settles.
Backpressure is the panel's responsibility: don't send a new click before
the prior frame arrives. Lost inputs are acceptable; the user will
re-click.

### v1 implementation notes

State lives in a `ConcurrentHashMap<String /*streamId*/, InteractiveTarget>` so
multiple concurrent streams coexist. The dispatch flow inside `JsonRpcServer`:

1. `interactive/start` — generate a unique `streamId` (monotonic), put a fresh
   `(previewId, streamId)` entry into the targets map, wipe any cached frame
   hash for that preview so the first interactive frame always paints, return
   the stream id. Blank previewIds reject with `InvalidParams (-32602)`. The
   wipe is per-preview (not per-stream) by design: two streams targeting the
   same preview share dedup state because the bytes are the same bytes
   regardless of which stream's input triggered the render.
2. `interactive/stop` — `remove(streamId)` from the targets map. Idempotent
   on stale or unknown ids — `remove` is a no-op when the key isn't present,
   which is exactly the contract we want.
3. `interactive/input` — look up the target by `streamId`; drop silently when
   absent (the stream was never started, has been stopped, or the client
   typo'd the id). Otherwise enqueue a render against the target preview
   through the same `submitRenderAsync` path `renderNow` uses; the result
   demuxes through the existing render-watcher thread back into
   `renderFinished`.

### Open questions for the v2 protocol

1. **Coalescing.** Should the daemon coalesce a burst of `pointerMove` into
   one render, or render every event? Today's frame budget says coalesce
   to ~60 Hz max with the most recent state. Defer until pointer events
   ship — click-only is fine without coalescing.
2. **Input-only vs full re-render.** A click that mutates Compose state
   only needs to recompose; we can probably skip the
   `setQualifiers`/sandbox setup. Daemon-side optimisation, not visible
   on the wire.
3. **Hit testing.** The renderer needs to know which composable received
   the click. For PNG-only output this is "send pixel coords, let Compose
   figure it out via its existing pointer-input pipeline." Works as long
   as `LocalInspectionMode = false` during interactive renders — see
   [DESIGN § 8](DESIGN.md) on the inspection-mode trade-off. v1 leaves
   `LocalInspectionMode = true`, so today's "click triggers a re-render"
   doesn't yet route the input into the composition; v2 flips the local
   to false during interactive frames and dispatches the pixel coords
   into the active `ImageComposeScene`.

## 9. Out of scope (v1)

- **Continuous-stream rendering** in the absence of edits/clicks. v1
  treats `renderFinished` as the streaming heartbeat — no edits, no new
  frames. Animations remain the carousel's responsibility.
- **Click-into-composition** dispatch. v1 routes the click coords as far
  as the daemon's interactive/input RPC and triggers a fresh render of
  the target preview, but the renderer body still composes with
  `LocalInspectionMode = true` so the input doesn't reach the active
  composition. v2 flips the local and dispatches the pixel coords
  through `ImageComposeScene`'s pointer-input pipeline.
- **Multi-preview simultaneous live mode in the panel UI.** The wire
  protocol supports concurrent `interactive/start` streams (see § 8),
  but the panel only renders a single `.live` card at a time. Lifting
  the UI is purely a webview change — `interactivePreviewId` would
  become a `Set<previewId>` and the LIVE chip / badge would attach per
  card — but the affordance argument for surfacing multi-target to a
  human is weak (the user can only meaningfully focus on one live
  preview at a time). Programmatic clients are the load-bearing case
  for multi-target on the wire today.
- **Mouse-move / drag** events. Click is the smallest first surface; we
  reserve the wire shape but don't capture moves to keep the
  implementation honest.
- **Pixel-accurate pointer mapping** for previews with non-default
  density / scaling. v0 sends pixel coords and lets the future daemon
  resolve density. The webview reports both displayed and natural sizes
  in case a future client wants to do the math itself.
