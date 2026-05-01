# Interactive mode (VS Code panel ↔ daemon)

> **Status:** v0 — live-streaming surface in the VS Code webview, behind the
> `composePreview.experimental.daemon.enabled` flag. Click-input is a
> documented future extension; no protocol bump required to ship today's
> behaviour. See [PROTOCOL.md](PROTOCOL.md) for the wire contract this builds
> on.

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

## 5. What changes in the extension

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

## 6. Click capture (today)

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

## 7. Future: click-input RPC (reserved)

The protocol additions are documented here so the wire contract is fixed
before any daemon implementation. Adding these methods does **not** bump
`protocolVersion` — they're additive and unknown methods are dropped per
[PROTOCOL.md § 7](PROTOCOL.md#7-versioning).

### `interactive/start` (request, client → daemon)

```ts
// params
{ previewId: string }
// result
{ frameStreamId: string }           // opaque; passed back in interactive/input
```

Tells the daemon "this preview is the user's interactive target — keep a
warm sandbox for it, prefer it on every render-queue drain, do NOT recycle
its sandbox on idle." Returns a stream id the client uses for input
correlation. Multiple `start` calls overwrite the prior target (one
interactive preview at a time — matches the panel's single-focus mode).

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

### Open questions for the future protocol

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
   [DESIGN § 8](DESIGN.md) on the inspection-mode trade-off.

## 8. Out of scope (v0)

- **Continuous-stream rendering** in the absence of edits/clicks. v0
  treats `renderFinished` as the streaming heartbeat — no edits, no new
  frames. Animations remain the carousel's responsibility.
- **Multi-preview simultaneous live mode.** One focused preview, one
  `.live` card. The future `interactive/start` API formalises this with
  the "overwrites prior target" semantics.
- **Mouse-move / drag** events. Click is the smallest first surface; we
  reserve the wire shape but don't capture moves to keep the
  implementation honest.
- **Pixel-accurate pointer mapping** for previews with non-default
  density / scaling. v0 sends pixel coords and lets the future daemon
  resolve density. The webview reports both displayed and natural sizes
  in case a future client wants to do the math itself.
