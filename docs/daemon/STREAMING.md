# Live frame streaming (`composestream/1`)

**Status: pre-1.0, additive on top of the existing `interactive/*` surface.**
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
so the client has an explicit "paint me now" anchor.

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
implementation; `StreamFrameHeaderTest` pins the round-trip. Pre-1.0
binary clients are not supported on the wire today; the JSON envelope is
the only sanctioned transport.

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

## VS Code opt-in

The protocol is gated behind `composePreview.streaming.enabled` (default
`false`). The legacy `<img src=…>` swap path stays the stable default
until the new wire shape has bedded down; flipping the setting on routes
live cards through `stream/start` + the canvas painter. Reads through
`getConfiguration` so workspace + user scopes layer the usual way; the
typed accessor lives in `vscode-extension/src/daemon/streamingSetting.ts`.

## Tests

- `:daemon:core` `StreamFrameHeaderTest` — round-trip + magic / version /
  codec error paths.
- `:daemon:core` `FrameStreamRegistryTest` — dedup, fps gate, visibility
  throttle, keyframe-on-resume, final-on-stop.
- `:daemon:core` `StreamRpcIntegrationTest` — end-to-end RPC over piped
  streams; mirrors `InteractiveRpcIntegrationTest`.
- `vscode-extension` `streamClient.test.ts` — newest-wins queue,
  multi-stream demux, sink isolation, late-bind buffering.
- `vscode-extension` `streamingSetting.test.ts` — pins the setting key,
  default, and `package.json` advertisement so the opt-in can't silently
  drift away from the documented spelling.
