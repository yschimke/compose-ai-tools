# RFC: the XR renderer as a streaming, extensible service behind the daemon

**Status: accepted; native-side prototype landed.** Captures the agreed direction and the settled
decisions (see [Decisions](#decisions)) for evolving `renderers/xr-composite` from a one-shot CLI
into a long-lived, extensible render service.

> **Implemented so far:**
> - `xr-composite --serve` — a working long-lived native process speaking the daemon's JSON-RPC +
>   `Content-Length` framing (`initialize`, `render`, `xr/updatePanels`, `streamFrame` base64
>   frames), holding one Filament engine across frames (items #1 and #3 — native JSON-RPC peer + a
>   held, mutable session — in prototype form). See
>   [renderers/xr-composite/README.md → Server mode](../../../renderers/xr-composite/README.md#server-mode---serve).
> - `:renderer-xr-client` — the JVM side: `XrServerClient` (the framed JSON-RPC transport),
>   `XrCompositeBinary` (binary/materials resolution), `XrRenderServer` (resolve → spawn →
>   `initialize` → render/updatePanels → close), and `XrSessionManager` (one server per stream id).
>   A real-binary integration test drives the loop end-to-end in the XR CI job.
> - **Daemon `xr/*` surface** — `JsonRpcServer` now serves `xr/start` (request) / `xr/updatePanels`
>   / `xr/stop` (notifications) behind an injected `XrRenderServerFactory`, advertising
>   `capabilities.xr` and emitting frames as `streamFrame` notifications (the unchanged wire shape).
>   Covered by in-process integration tests with a fake factory.
> - **Desktop daemon wiring** — `:daemon:desktop`'s `DaemonMain` passes `XrRenderServerFactory.Native`
>   only when `XrCompositeBinary.resolve(...)` finds the binary, so the real (host) daemon flips
>   `capabilities.xr` on when provisioned and stays `MethodNotFound` otherwise. XR is host-native, so
>   it's wired on the desktop daemon only.
> - **Frame gating** — XR streams register with `FrameStreamRegistry` and frames route through
>   `consumeForStream` (per-stream fps cap via `xr/start.maxFps`, `stream/visibility` downshift,
>   content dedup → `unchanged` heartbeats, keyframe-on-(re)show), the same gating `stream/start` gets.
> - **Multi-session concurrency** — one shared Filament engine fans across sessions keyed by
>   `sessionId`: the native `--serve` server holds per-session swapchain/scene/view (`xr/stop` tears a
>   session down, engine kept), and `XrSessionManager` drives them all over a single child process,
>   demuxing frames per `sessionId` in `XrServerClient` — replacing one-process-per-session.
>
> **Still to do:** the `xr/structure` / `xr/a11y-overlay` data-product kinds.

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

New *artefact* features are **kinds the native renderer advertises in
`initialize.capabilities.dataProducts` and produces on subscribe** — never a new binary, never a new
CLI flag baked into a one-shot:

- `xr/composite` — the baked still (the increment, promoted to a kind).
- `xr/structure` — the spatial panel tree + poses + semantics as inline JSON (mirrors
  `a11y/hierarchy`).
- `xr/a11y-overlay` — a rendered overlay PNG for XR a11y/TalkBack affordances (mirrors
  `a11y/overlay`), produced when we learn how XR a11y surfaces.

The C++ side mirrors the `DataProductRegistry` seam: a capabilities list + dispatch by kind, with
**no `if (kind == …)`** scattered through the renderer — same rule the JVM side enforces.

**Live frames are NOT a data product.** They are a distinct wire surface: data products are
attachable artefacts negotiated via `data/subscribe` (sticky `(previewId, kind)`, delivered as
`renderFinished` attachments), whereas live frames are negotiated via **`stream/start`** (which
allocates a `frameStreamId` with `stream/visibility`/`stream/stop` semantics on a held session) and
delivered as **`streamFrame`** notifications — see the [Live](#xr-render-inputoutput) section and
`docs/daemon/STREAMING.md`. So XR live rendering reuses the `composestream/1` stream surface
directly; it is advertised as a **stream capability/method**, not as an `xr/frame-stream`
data-product kind. (Conflating the two would leave a subscriber without a `frameStreamId` or the
visibility/stop controls a stream requires.)

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

## Decisions

Settled on RFC review; these shape the implementation but none block the one-shot CLI or the
panels-in-previews increment.

1. **Layering — native speaks the daemon JSON-RPC subset directly.** The renderer is "just another
   node" on the same protocol, not a thin XR protocol behind a Kotlin translator. Less glue, and it
   makes the renderer a first-class producer.
2. **Topology — daemon-supervised child.** The native renderer is a child the daemon owns and
   multiplexes (a new `RenderSession` backend), not a sibling daemon. Matches "the daemon fronts it;
   no parallel services spawned from VS Code."
3. **VS Code 3D view — keep client-side Three.js (for now).** The view stays a Three.js viewer fed
   `scene.json`; server-rendered frames via the daemon are deferred (revisit only if parity or heavy
   scenes demand it).
4. **Frame transport — base64-over-JSON `streamFrame`.** Reuse the proven `composestream/1`
   transport (fine for ≤30fps PNG/WebP); the speced binary data plane / shared memory is a later
   optimization only if throughput demands it.
5. **IDL — hand-mirror the C++ types + shared fixtures for now**, consistent with the existing
   Kotlin↔TS approach. Moving to a single-source IDL with codegen (e.g. protobuf) is tracked as
   follow-up in [#1729](https://github.com/yschimke/compose-ai-tools/issues/1729) — worthwhile once
   the third (C++) mirror actually exists. That follow-up has been evaluated: see
   [WIRE_IDL_CODEGEN.md](../WIRE_IDL_CODEGEN.md). Conclusion: keep the JSON wire and the hand-mirror +
   fixture approach until the C++ mirror is real; when it is, prefer a **JSON-preserving** IDL
   (JSON-Schema codegen, or proto3-with-canonical-JSON), migrated one message family at a time with
   the fixture corpus as the conformance ratchet — not a binary wire swap.
6. **Distribution — auto-provisioned by the CLI (daemon to follow).** The
   `xr-composite-<platform>-<ver>.tar.gz` binaries published on each GitHub Release (see
   `.github/workflows/release.yml`) are fetched automatically by the CLI into a shared, well-known
   cache (`${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite/<version>/<platform>/`) the first time
   it drives an XR render — no manual install step. The Gradle plugin's `composePreviewCompositeXr`
   task only *reads* that cache (after the `composePreview.xrCompositeBinary` property /
   `XR_COMPOSITE_BIN` env overrides); the CLI is the writer. Both sides derive the identical path
   from the release version + host platform, so the fetch and the read meet with no runtime
   handshake. Implemented in `XrCompositeProvision` (`:cli`) +
   `AndroidPreviewSupport.xrCompositeCacheBinaryPath` (`:gradle-plugin`). Daemon-side
   auto-provisioning is a follow-up tied to the daemon actually producing composites (this RFC) —
   today only the CLI→Gradle path bakes composites.
