# Live frame streaming (`composestream/1`)

**Status: unstable, additive on top of the existing `interactive/*` surface.**
A daemon that hasn't grown the new methods rejects `stream/start` with
`MethodNotFound (-32601)`; the panel falls back to the existing
`<img src=…>` swap path. No `protocolVersion` bump.

## Why this exists

The legacy live-render path (see [INTERACTIVE.md](INTERACTIVE.md)) ships
each frame as a `renderFinished` notification carrying a *path* to a PNG
on disk that the daemon overwrites on every render. The webview reads
the file, base64s it, posts it through `postMessage`, and swaps the
`<img>` element's `src=`. Three glitches fall out of that pipeline:

1. **Visible-element blanking on swap.** The browser tears down the
   current decoded bitmap before the new one is ready. That's the
   "blink" on every input.
2. **Torn PNG reads.** The daemon overwrites the same on-disk path
   between renders; a busy webview can race the partial write and
   decode tail bytes from frame N+1 stitched onto frame N.
3. **Cold blank on scroll-back.** The viewport tracker calls
   `interactive/stop` when the card scrolls out of view. Re-entering
   view kicks off `interactive/start` from scratch, with no anchor
   bitmap to paint while the new sandbox warms.

The streaming protocol fixes all three by:

1. Pushing frames inline in a `streamFrame` notification — no `<img>`
   swap, the webview paints into a `<canvas>` via `createImageBitmap`
   with a newest-wins queue.
2. Carrying a per-frame sequence number and inline bytes — no on-disk
   path reuse, no torn reads.
3. Replacing the hard "stop on scroll-out" with a soft visibility
   throttle. The held session stays warm; the daemon emits keyframes
   only at 1 fps; scroll-back paints from the cached keyframe
   immediately.

## Layered on `interactive/*`

A `composestream/1` stream is an `interactive/*` session with a
binary-frame consumer attached. `stream/start` allocates the same held
`InteractiveSession` `interactive/start` does; `interactive/input`
notifications routed to a `frameStreamId` allocated by `stream/start`
drive the same composition. Clients can mix the surfaces freely.

## Wire surface

### `stream/start` (request)

```ts
// params
{
  previewId: string;
  codec?: "png" | "webp";   // default: "png"
  maxFps?: number;          // cap on emit cadence; null = renderer-natural
  hidpi?: boolean;          // keep capture density; default: true
  inspectionMode?: boolean; // mirrors interactive/start.inspectionMode
}
// result
{
  frameStreamId: string;    // routing key for stop / visibility / streamFrame
  codec: "png" | "webp";    // codec the daemon will actually emit
  heldSession: boolean;     // false = v1 fallback, frames still flow
  fallbackReason?: string;
}
```

Errors:
- `-32602 (InvalidParams)` when `previewId` is blank or `maxFps <= 0`.
- `-32603 (Internal)` when the host advertises held sessions but failed
  to allocate one.

### `stream/stop` (notification)

```ts
{ frameStreamId: string }
```

Idempotent. The daemon emits one final `streamFrame` carrying
`final: true` so the client can release decoder state, then drains the
held session via `InteractiveSession.close()`.

### `stream/visibility` (notification)

```ts
{
  frameStreamId: string;
  visible: boolean;
  fps?: number;             // override throttled fps; default = 1 when !visible
}
```

Idempotent and silent on unknown stream ids — the client may race a
visibility flip with a `stream/stop`. When `visible` flips back from
`false` to `true`, the *next* emitted frame is flagged `keyframe: true`
so the client has an explicit "paint me now" anchor, and the daemon wakes
the stream's frame loop immediately rather than letting the throttled
wait it is parked in elapse first.

**The throttle is on the render, not just the emit.** The daemon's live
frame loop takes its cadence from `FrameStreamRegistry.emitMinIntervalMs`
for the stream — the same number the emit gate applies — so a hidden
stream *renders* once a second instead of rendering four times a second
into a gate that drops three of them. That distinction is the whole
point of sending the notification: on the Android backend every tick is a
Robolectric capture, so gating emission alone leaves a backgrounded tab
costing very nearly what a watched one does. The `maxFps` cap rides the
same floor, on the same grounds. A session with no frame stream
(`interactive/start`) has no emit gate and keeps its cadence unchanged.

### `streamFrame` (notification, daemon → client)

```ts
{
  frameStreamId: string;
  seq: number;              // monotonic per stream
  ptsMillis: number;        // daemon wall-clock at frame production
  widthPx: number;
  heightPx: number;
  codec?: "png" | "webp";   // omitted = unchanged-heartbeat
  keyframe?: boolean;       // first frame, or first frame after visible:false → true
  final?: boolean;          // set on stream/stop
  payloadBase64?: string;   // omitted with codec
}
```

Three flavours:

- **Frame.** `codec` and `payloadBase64` set; `keyframe` may be true.
- **Heartbeat.** `codec` and `payloadBase64` both omitted; the daemon
  determined the bytes are identical to the previous frame on this
  stream. `seq` still increments.
- **Final.** `final: true`; `codec` and `payloadBase64` omitted; `seq`
  increments. Sent at most once per stream.

`widthPx` / `heightPx` are the frame's own pixel size, read from the
PNG's `IHDR` (`PngHeader`) — not the requested device size, and not the
sandbox window: a wrap-content preview is cropped to its measured content
before it reaches here, so the size travels with the frame. They are `0`
only when the size genuinely cannot be determined (a stub host's
placeholder bytes, a codec whose header the daemon does not parse). Until
#4281 they were hard-coded to `0` on every frame while looking like a
field a client could size a stage from.

**One read per frame.** The server reads the captured PNG once, in
`JsonRpcServer.emitRenderFinished`, and carries the bytes through the
dedup hash, the size probe, and the base64 payload
(`FrameStreamRegistry.consumeForPreview(pngBytes = …)`). Two streams
watching one preview still share that single copy. Before #4283 the hash,
the size, and the payload each re-read the same file — three reads a
frame, at the interactive loop's cadence, on top of the capture's write.

## Binary header (`StreamFrameHeader`)

JSON is the wire today; a future WebSocket data plane (or a `.cstream1`
fixture file) consumes the same fields as a 20-byte little-endian
binary header followed by the payload bytes:

```
 off  type  field
  0   u8    magic     = 0xCF
  1   u8    version   = 1
  2   u8    codec     // 0=PNG, 1=WEBP, 0xFF=unchanged-heartbeat
  3   u8    flags     // bit0=keyframe, bit1=final
  4   u32   seq
  8   u32   ptsMillisLow      // wall-clock millis & 0xFFFFFFFF
 12   u16   widthPx
 14   u16   heightPx
 16   u32   payloadLen
(20…) payload
```

`StreamFrameHeader` in `:daemon:core` is the canonical pack/parse
implementation; `StreamFrameHeaderTest` pins the round-trip. Binary
clients are not supported on the wire today; the JSON envelope is the
only sanctioned transport.

## Client model — newest-wins queue + canvas paint

The webview painter implements three rules:

1. **Newest-wins queue.** Hold at most one pending frame; if a new one
   arrives before paint, drop the old. `StreamFrameQueue` /
   `StreamClient` in `vscode-extension/src/daemon/streamClient.ts` are
   the canonical implementations.
2. **Decode out-of-band.** Surface the queued bytes through
   `createImageBitmap(blob)` so the visible canvas never tears down its
   current bitmap before the next is ready.
3. **Keyframe anchor cache.** Cache the most recent painted bitmap so
   visibility-back / scroll-into-view repaints from cache immediately.

Minimal browser recipe (~25 LoC):

```ts
const ws = new StreamClient();
ws.bind(streamId, async (frame) => {
  if (frame.codec === undefined) return; // heartbeat — no-op tick
  const blob = base64ToBlob(frame.payloadBase64!, mimeFor(frame.codec));
  const bitmap = await createImageBitmap(blob);
  ctx.transferFromImageBitmap(bitmap);
});
function tick() {
  ws.tick();
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);
// Wire ws.onFrame(...) to the daemon's `streamFrame` notification handler.
```

## `serve` — the same rules, a different wire

The preview server does not put `composestream/1` on the wire. A browser
talks to `/ws/{previewId}` in `ServeStreamProtocol`'s much smaller
envelope — `{type:"frame", seq, codec, widthPx, heightPx, dataBase64}` —
and `ServeLiveSession` translates between that and the daemon's
`stream/start` + `streamFrame`. The *client rules* above are the same on
both, and both lanes now enforce them:
`cli/serve-web/src/live/framePainter.ts` is the serve-side counterpart to
`streamClient.ts`, shared by the viewer's stage and the grid's
`<cp-catalog-live>` cards.

Until issue #4285 the serve lanes enforced none of it: every frame built
an `Image` from a `data:` URL and painted in `onload`, with no ordering
guard at all. Decode time varies with frame content, so a heavier frame N
could still be decoding when a lighter N+1 resolved, and the late N then
painted *over* N+1 and stayed — until the next frame, or forever if the
stream had gone quiescent.

The visibility signal crosses the same translation. A browser sends
`{type:"visibility", visible, fps?}` on its `/ws/{previewId}` socket —
the viewer on `document.visibilitychange`, a `<cp-catalog-live>` card on
an `IntersectionObserver` as it scrolls out of the grid — and
`ServeLiveSession` turns it into `stream/visibility` on the held stream.
Two things only the serve side has to decide:

- **One shared daemon stream serves every watcher of the same preview +
  overrides + codec + fps** (`ServeBroadcastHub`), so the hub throttles
  on the *aggregate*: visible while **any** watcher is, and when none
  are, at the slowest rate they asked for. Throttling on the first
  hidden watcher would starve the tab still watching the same preview
  beside it.
- **A socket outlives its daemon streams**, so `ServeLiveSession`
  remembers the last visibility the client reported and re-states it on
  every stream a `setOverrides` or `switch` opens — daemon streams start
  visible, so a hidden socket would otherwise silently return to full
  rate.

The snapshot fallback lane (`ServeStreamSession`) accepts the message and
does nothing with it: it renders only when asked, so there is nothing to
throttle, and answering each tab switch with an error would paint the
viewer's error banner over a working lane.

**`seq` on the serve wire is the socket's, not the daemon's.** One socket
outlives several daemon streams: every `setOverrides` restarts the held
session and every `switch` opens a replacement, each numbering from zero.
`ServeLiveSession` therefore counts its own frames for the life of the
connection. Relaying the daemon's numbers was harmless while the client
painted everything it received and became load-bearing the moment the
client started dropping stale frames — a viewer forty frames in would
have rejected an entire restarted stream and frozen the lane.

## Codec negotiation

`stream/start.codec` is a request; `stream/start.result.codec` is the
daemon's actual choice. Daemons that lack the requested encoder
downgrade to PNG silently and report the chosen codec. Clients pick a
decoder off the result, never the request.

PNG is the only codec every daemon supports today (every renderer
already produces PNG bytes). WebP is opt-in and requires a daemon-side
encoder; the wire shape and `FrameStreamRegistry` are encoder-agnostic
so plugging in a Skiko / libwebp encoder is a one-class change.

## Coexistence with the legacy path

A `stream/start` does **not** suppress the legacy `renderFinished`
notification — both flow on every render. Clients that subscribe to
both must dedup themselves (the natural "use streamFrame for live cards,
ignore renderFinished there" split is the expected pattern). New
clients that only care about the buttery path can ignore
`renderFinished` entirely on streamed previews.

## VS Code

The protocol is the only live-mode path in the VS Code extension — the
legacy `<img src=…>` swap was retired once the `composestream/1` painter
proved out. Every live entry point routes through `stream/start` + the
canvas painter; there is no opt-in setting and no fallback.

## Tests

- `:daemon:core` `StreamFrameHeaderTest` — round-trip + magic / version /
  codec error paths.
- `:daemon:core` `FrameStreamRegistryTest` — dedup, fps gate, visibility
  throttle, keyframe-on-resume, final-on-stop.
- `:daemon:core` `StreamRpcIntegrationTest` — end-to-end RPC over piped
  streams; mirrors `InteractiveRpcIntegrationTest`.
- `:daemon:core` `InteractiveVisibilityCadenceTest` — the frame loop's
  cadence floored by the stream's emit gate (visibility + `maxFps`).
- `cli` `ServeBroadcastHubTest` — aggregate visibility across the
  watchers sharing one upstream stream.
- `cli/serve-web` `catalogLiveElement.test.ts` — the card's scroll-out /
  backgrounded-tab throttle and its teardown.
- `vscode-extension` `streamClient.test.ts` — newest-wins queue,
  multi-stream demux, sink isolation, late-bind buffering.
- `vscode-extension` `liveCommand.test.ts` — pins the LIVE-button →
  wire-command rule (every entry point posts the same shape).
- `:daemon:core` `PngHeaderTest` — the frame-size probe, including the
  cases that must report "unknown" rather than a fabricated size.
- `cli` `ServeLiveSessionTest` — the socket's own monotonic `seq`,
  including across a stream restart, and the frame/heartbeat counters
  behind `/status.json`'s `liveFrames`.
- `cli` `LiveFramePerfStatsTest` — achieved fps from the inter-frame
  intervals, per-catalog scoping, socket open/close accounting.
- `cli/serve-web` `liveFramePainter.test.ts` — newest-wins queue, stale
  drop after dispatch, the post-decode watermark, heartbeat handling.
