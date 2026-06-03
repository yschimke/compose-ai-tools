# RFC: the XR renderer as a streaming, extensible service behind the daemon

**Status: draft / RFC.** Captures the agreed direction and the open decisions for evolving
`renderers/xr-composite` from a one-shot CLI into a long-lived, extensible render service. Nothing
here is built yet; the [one-shot tool](../../../renderers/xr-composite/README.md) and the
[panels-in-previews increment](COMPOSITOR.md) land first and are unaffected.

## Motivation

The native `xr-composite` tool today is a one-shot CLI: `scene.json` + panel PNGs → one composite
PNG, exit. We want it to grow, without forking the binary per feature, into something that can:

1. **Stream live** — receive a live stream of panel textures + panel poses, and emit a live stream
   of rendered frames (interactive orbit / drag / play, not just a still).
2. **Be extensible via an API**, not hard-coded to "the XR composite thing" — new capabilities
   (e.g. draw a11y/TalkBack overlays, answer spatial-structure queries) added without duplicating
   or re-shipping a bespoke binary.
3. **Not be 1:1** — one process serving many concurrent requests/sessions.
4. **Sit behind the daemon** — VS Code, the CLI, and MCP talk only to the daemon; the daemon owns
   and multiplexes the renderer process, so we don't spawn parallel services from VS Code.

## Principle: mirror the daemon, don't invent

The daemon (`daemon/core/.../JsonRpcServer.kt`) already is most of this. The design reuses, rather
than reinvents:

| Need | Existing mechanism | Where |
|------|--------------------|-------|
| RPC envelope | JSON-RPC 2.0 + LSP-style `Content-Length` framing over stdio | `JsonRpcServer.kt`, `protocol/Messages.kt` |
| Capability negotiation | `initialize` → `ServerCapabilities { dataProducts, dataExtensions, interactive, recording, backend, supportedOverrides }` | `Messages.kt:125`/`:135` |
| Extensible feature surface | `DataProductRegistry` + `CompositeDataProductRegistry`, kinds advertised in caps; **no `if (kind == …)` in the dispatcher** | `DataProductRegistry.kt`, `CompositeDataProductRegistry.kt` |
| Subscribe + push | `data/subscribe`/`unsubscribe` (sticky `(previewId, kind)`) → attachments on `renderFinished` | `SubscriptionStore.kt`, `RenderFinishedParams` (`Messages.kt:1183`) |
| **Live frame streaming** | **`composestream/1`**: `stream/start`/`stop`/`visibility` + `streamFrame` notifications; newest-wins canvas painter; codec negotiation; a speced 20-byte binary header for a future binary data plane | [`docs/daemon/STREAMING.md`](../../daemon/STREAMING.md), `FrameStreamRegistry` |
| Held interactive sessions | `interactive/start` allocates a held session + `frameStreamId`; `interactive/input` routes to it | [`docs/daemon/INTERACTIVE.md`](../../daemon/INTERACTIVE.md), `Messages.kt:1461` |
| Renderer-behind-an-abstraction | `RenderSession` (open → drive → close), with `Subprocess`/`Embedded` backends; born initialized via the `initialize` handshake | `render-session/api/.../RenderSession.kt` |
| Shared schema | Hand-mirrored Kotlin↔TypeScript, locked by fixture-deserialization tests + a version constant | `api/preview-data-api/.../SpatialScene.kt` ↔ `spatialScene.ts`; `Messages.kt` ↔ `daemonProtocol.ts` |
| The "draw an overlay" precedent | a11y already produces `a11y/overlay` (a PNG) + `a11y/hierarchy` (structure JSON) as data products | `data/a11y/connector/` |

So the live mode, the capability handshake, the subscribe/push streaming, and the overlay/structure
precedent **all already exist**. The XR renderer should speak this protocol, not a new one.

## What is genuinely new

Everything today is one-shot: `DesktopRendererMain` and `xr-composite` both take args → write
files → exit. Interactive/streaming sessions live *inside the daemon JVM*. There is **no precedent
for a long-lived native (C++) process that speaks the protocol**. The new pieces are exactly:

1. A **long-lived native renderer** speaking JSON-RPC + `Content-Length` (the same framing
   `render-session/subprocess` already uses for JVM subprocesses — this makes it JVM→native).
2. The **daemon fronting it**: a new `RenderSession`-style backend (or a supervised child producer)
   so clients keep talking only to the daemon, which multiplexes.
3. **Native-side multi-session concurrency**: one process holding many Filament `Engine`
   scenes/views/swapchains, keyed by session/preview id (the protocol already disambiguates
   concurrent renders by `previewId`).
4. A **third language mirror** of the shared IDL (C++), pinned by the same JSON fixtures.

## Architecture

```
VS Code ─┐
CLI ─────┼─JSON-RPC─▶ daemon (JVM) ──JSON-RPC over stdio──▶ xr-render (native, long-lived)
MCP ─────┘                │  proxies / multiplexes            ├─ session A: Filament Engine+Scene+SwapChain
                          │                                   ├─ session B: …
                          └─ fronts; clients never see        └─ data-product producers (composite, structure, overlay)
                             the native process directly
```

- The native renderer is a **producer node behind the daemon**, surfaced as a `RenderSession`
  backend (e.g. a new `RenderSessionBackend.NativeXr`) or a child the daemon supervises and proxies.
- Clients (VS Code/CLI/MCP) keep their current relationship with the daemon; XR scenes become just
  another preview kind whose renders/streams/data-products the daemon serves.

### XR render input/output

- **Input:** a `SpatialScene` (the existing `api/preview-data-api` type: panels with `id`,
  `poseInRoot`, `sizeDp`, `texture`) plus per-panel textures. `:renderer-xr` (Robolectric) produces
  the scene + panel PNGs; the daemon hands them to the native renderer.
- **Texture/data transport:** start with **paths** (matches today's `pngPath` /
  `DataProductAttachment.path`); inline base64 for small payloads; a binary data plane later.
- **Live:** panel texture/pose updates streamed *in* (a new method, e.g. `xr/updatePanels`, keyed by
  session id — shaped like `interactive/input`); rendered frames streamed *out* via **`streamFrame`,
  reusing `composestream/1` wholesale** (the canvas painter, newest-wins queue, codec negotiation,
  visibility throttle, and binary header already exist client-side).

### Extensibility: new capabilities = new data-product kinds

New features are **kinds the native renderer advertises in `initialize.capabilities.dataProducts`
and produces on subscribe** — never a new binary, never a new CLI flag baked into a one-shot:

- `xr/composite` — the baked still (the increment, promoted to a kind).
- `xr/frame-stream` — live frames (via `composestream/1`).
- `xr/structure` — the spatial panel tree + poses + semantics as inline JSON (mirrors
  `a11y/hierarchy`).
- `xr/a11y-overlay` — a rendered overlay PNG for XR a11y/TalkBack affordances (mirrors
  `a11y/overlay`), produced when we learn how XR a11y surfaces.

The C++ side mirrors the `DataProductRegistry` seam: a capabilities list + dispatch by kind, with
**no `if (kind == …)`** scattered through the renderer — same rule the JVM side enforces.

### Concurrency (not 1:1)

One native process, many sessions. Filament holds multiple `Engine`/`Scene`/`View`/`SwapChain`
instances; sessions are keyed and have a held lifecycle mirroring `interactive/*` (warm → throttled
→ closed). The daemon already keys concurrent work by `previewId`.

### The "renderer also serves the VS Code XR views" question

This **coexists** with "daemon fronts the renderer": VS Code → daemon → renderer, with the daemon
proxying the frame stream / data products. So the renderer can serve the VS Code 3D view *through*
the daemon — VS Code still talks only to the daemon. Whether the VS Code 3D view stays a
**client-side Three.js** viewer fed `scene.json`, or switches to **server-rendered frames** from the
native renderer, is an open decision (both fit this architecture).

## Shared IDL

Extend the existing hand-mirrored contract: XR message types added to `protocol/Messages.kt` +
`daemonProtocol.ts`; `SpatialScene` already lives in `api/preview-data-api` + `spatialScene.ts`.
Keep the fixture-deserialization lock + `SPATIAL_SCENE_VERSION`-style discipline. The native (C++)
side becomes a **third mirror** with its own parse/serialize, pinned by the same shared JSON
fixtures (`docs/daemon/protocol-fixtures/` + the spatial fixture). That third mirror is the main new
maintenance cost — see open questions.

## Migration (incremental)

1. **Now:** panels-in-previews via the one-shot CLI — no service, no protocol. Capability-gated hook.
2. **Long-lived mode:** native renderer gains `initialize` + a render method taking a `SpatialScene`
   → composite path; daemon spawns/supervises it; `xr/composite` becomes a data-product kind.
3. **Live:** `stream/start` + `xr/updatePanels` in → `streamFrame` out (reuse `composestream/1`).
4. **New kinds:** `xr/structure`, `xr/a11y-overlay` as XR a11y/TalkBack support is understood.

Each step is independently shippable; the one-shot CLI remains the floor.

## Open questions (need a decision)

1. **Layering.** (A) Native process speaks the full daemon JSON-RPC subset directly (max reuse,
   "just another node"); vs (B) native speaks a thin XR protocol and a Kotlin connector in the
   daemon translates to the data-product/stream surface (smaller native surface, more JVM glue).
2. **Topology.** Native renderer as a **child the daemon supervises** (a `RenderSession` backend),
   vs a **sibling daemon** the JVM daemon proxies. Affects `render-session` design.
3. **VS Code 3D view.** Keep client-side Three.js fed `scene.json`, or move to server-rendered
   frames from the native renderer (via the daemon)?
4. **Frame transport.** Reuse base64-over-JSON `streamFrame` now (fine for ≤30fps PNG/WebP, already
   proven), or invest in the speced binary data plane / shared memory for higher throughput?
5. **Third IDL mirror.** Accept hand-mirrored C++ types + shared fixtures, or introduce codegen for
   the wire types (none exists today; everything is hand-mirrored)?
6. **Distribution.** A long-lived native renderer must ship to wherever the daemon runs
   (dev machines, CI, the VS Code host) — tighter than the one-shot tool. Bundle + bootstrap story
   (à la `install.sh --android-sdk`).
