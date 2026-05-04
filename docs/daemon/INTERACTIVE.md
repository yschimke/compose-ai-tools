# Interactive mode (VS Code panel ↔ daemon)

The daemon exposes `interactive/start` / `interactive/stop` /
`interactive/input` plus `recording/*` RPCs. MCP `record_preview` drives
a held scene, dispatches scripted pointer input into the composition, and
returns an APNG/MP4/WebM plus metadata. Frame deduplication remains part
of the live stream: bytes-identical follow-up renders carry
`renderFinished.unchanged: true`. See [PROTOCOL.md](PROTOCOL.md) for the
wire contract this builds on.

## 3. Panel UI

Two affordances reach interactive mode:

1. **Click the preview image** — single-click on a non-live card enters
   LIVE; subsequent clicks while LIVE forward as pointer events. Plain
   click is single-target (drops every prior live stream); Shift+click
   adds the preview to the live set without disturbing others.
2. **The LIVE button** in the focus-mode toolbar — same plain/Shift
   semantics.

A small focus button (`codicon-screen-full`) sits in each card's title
row.

When LIVE for a card, that card carries:

- `.preview-card.live` class (CSS draws a 2px red border + soft red glow).
- A solid red **LIVE** chip pinned top-right with a blinking dot.
- `<img>` swaps clear `.fade-in`, so the next paint reads as a frame
  update.
- Crosshair cursor.
- Image click handler routes to `recordInteractiveClick(card, event)`
  which posts a `recordInteractiveClick` webview→extension message
  with image-natural pixel coordinates (see § 7).

LIVE auto-stops when:

- The user moves focus to a different editor (extension flushes via
  `interactive/stop`, posts `clearInteractive` to the panel).
- A live card scrolls out of viewport. Re-entering view doesn't
  auto-resume.
- The daemon's `interactive` capability flips to false.

**Drop on classpath dirty.** When the daemon emits `classpathDirty`,
all live streams clear; the panel re-establishes LIVE on user request
after the daemon restarts.

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
  - daemonScheduler.setFocus(moduleId, [previewId])
  - daemonScheduler.renderNow(moduleId, [previewId],
                              tier='fast', reason='interactive-on')
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
`.live` class.

The wire-level RPCs are:

- `interactive/start` (request, client → daemon) — pin a preview as
  interactive target. Returns `frameStreamId`.
- `interactive/stop` (notification, client → daemon) — release the
  warm-sandbox lock. Idempotent.
- `interactive/input` (notification, client → daemon) — dispatch a
  pointer/key event into the held composition.

## 5. Frame deduplication

Daemon-side, consulted on every `renderFinished`:

1. After the host returns a `RenderResult` whose `pngPath` points at a
   real on-disk PNG, the daemon SHA-256s the bytes.
2. If the hash matches the prior hash for the same `previewId`
   (tracked in `lastFrameHashes: Map<String, String>`), the
   `renderFinished` notification carries `unchanged: true` and the
   history archiver is skipped.
3. The first `renderFinished` after `interactive/start` always paints
   (the start handler wipes the cached hash).

Client side (`DaemonScheduler.handleRenderFinished` in
`vscode-extension/src/daemon/daemonScheduler.ts`): `unchanged === true`
short-circuits the disk read + base64 + `postMessage` hop, leaving the
on-screen card untouched.

## 6. Coordinate system

The webview reports image-natural pixel coordinates. It reads
`naturalWidth`/`naturalHeight` and scales `offsetX`/`offsetY` by their
displayed-vs-natural ratio.

## 7. Click capture

The webview attaches a click handler to the focused card's `<img>`
**only when `.live` is set**. On click it computes:

```ts
{
  command: 'recordInteractiveClick',
  previewId,
  // Coordinates in IMAGE-NATURAL pixel space (the same pixel space
  // the daemon's renderer thinks in).
  pixelX: number,
  pixelY: number,
  imageWidth: number,
  imageHeight: number,
}
```

The same payload shape feeds daemon-side pointer input. MCP
`record_preview.events` uses this coordinate contract directly; VS Code
panel traffic forwards it through `interactive/input`.

## 8. Click-input RPC

Adding these methods does **not** bump `protocolVersion` — they're
additive and unknown methods are dropped per
[PROTOCOL.md § 7](PROTOCOL.md#7-versioning). Old daemons reject
`interactive/start` with `MethodNotFound (-32601)`; the panel falls
back to the legacy setFocus + renderNow path.

### `interactive/start` (request, client → daemon)

```ts
// params
{ previewId: string, inspectionMode?: boolean }
// result
{ frameStreamId: string }           // opaque; passed back in interactive/input
```

Pins a preview as an interactive target and returns a unique stream id
the client uses for input correlation.

**Multi-target invariant.** Each `start` registers a fresh slot —
concurrent streams targeting different (or even the same) preview ids
coexist. Inputs route by `frameStreamId`, so a stop on one stream
leaves the others untouched. Two streams targeting the same preview
share dedup state.

**Live-stream mode.** A held session keeps the composition alive across
`interactive/input` notifications so `remember`'d state survives.
`LocalInspectionMode = false` by default; callers may opt back into
`true` by passing `inspectionMode: true` on `start`.

**Reserved interactive surface.** `interactiveTargets` is a
`ConcurrentHashMap<String /*streamId*/, InteractiveTarget>` keyed by
streamId. The daemon's session implementation is shared per
(previewId, classloaderGeneration) — multiple streams on the same
preview share state via ref-counting, and the session lives until the
last subscribed stream calls `interactive/stop`.

### `interactive/stop` (notification, client → daemon)

```ts
{ frameStreamId: string }
```

Releases the warm-sandbox lock. Idempotent — a stop after stop is a
no-op.

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

Notification not request: input fires-and-forgets. The daemon
dispatches the input into the active composition and emits a fresh
`renderFinished` for the same `previewId` once the composition settles.
Backpressure is the panel's responsibility: don't send a new click
before the prior frame arrives. Lost inputs are acceptable.

## 8a. Display overrides

Per-render display properties — **size**, **density**, **locale**,
**fontScale**, **uiMode** (light/dark), **orientation**, **device**, and
**Material 3 theme tokens** — ride on the existing `renderNow` request
via the optional `overrides` field documented in
[PROTOCOL.md § 5](PROTOCOL.md#renderNow). Overrides are call-scoped, not
session-scoped; a subsequent `renderNow` without `overrides` reverts to
the discovery-time `RenderSpec`.

**Device override.** `device: "id:pixel_5"` (or any other catalog id /
`spec:` grammar that `@Preview(device = …)` accepts) is resolved by the
daemon's built-in `DeviceDimensions` catalog into `widthPx` / `heightPx` /
`density`. Explicit `widthPx` / `heightPx` / `density` overrides on the
same call take precedence. Unknown ids fall back to the daemon's
default (400×800dp at xxhdpi).

**Material 3 theme override.** `material3Theme` lets callers test
components against alternate Material 3 color, typography, and shape
tokens without editing the preview. The renderer applies the override
through the normal composition path as
`MaterialTheme(...) { InvokeComposable(...) }`. Example:

```json
{
  "material3Theme": {
    "colorScheme": { "primary": "#FF336699", "onPrimary": "#FFFFFFFF" },
    "typography": { "bodyLarge": { "fontSizeSp": 18.0, "fontWeight": 700 } },
    "shapes": { "medium": 16.0 }
  }
}
```

**Coalescing.** When an override-bearing `renderNow` arrives for a
previewId that already has an override-bearing render in-flight, the new
one is rejected with `reason = "coalesced: …"`. The panel / MCP client
resubmits on the next `renderFinished` if the latest override values
still differ from what was rendered. Plain (no-overrides) `renderNow`
is unaffected.

**MCP surface.** The `render_preview` tool accepts the same `overrides`
sub-object verbatim — see `mcp/src/main/kotlin/.../DaemonMcpServer.kt`.

**Backend fidelity.** The Android renderer applies all seven fields
via `applyPreviewQualifiers` + `RuntimeEnvironment.setFontScale`. The
desktop renderer applies `widthPx` / `heightPx` / `density`,
`fontScale`, `uiMode`, and `localeTag` (when the runtime exposes a
providable locale list). `orientation` remains a no-op on desktop.

## 9. v2 click dispatch into composition (RenderHost surface)

Click dispatch requires a held composition: the `ImageComposeScene`
must persist across `renderFinished` notifications for the duration of
an `interactive/start` so `remember`'d state survives.

`InteractiveSession` is a daemon-internal abstraction owning the held
scene + composition state for one `frameStreamId`. Lifecycle:

- **Allocated** by `handleInteractiveStart` after the first
  `interactive/start` for a given preview. Pre-renders one bootstrap
  frame.
- **Driven** by `handleInteractiveInput`: dispatches the pointer event
  through `scene.sendPointerEvent`, drives Compose to recompose, then
  encodes a fresh PNG and emits `renderFinished`.
- **Released** by `handleInteractiveStop`: closes the scene, frees the
  Skiko `Surface`, drops the entry from the session map.

The renderer-agnostic `RenderHost` interface in `:daemon:core` exposes:

```kotlin
interface RenderHost {
    /**
     * Acquire a held interactive session for [previewId]. The session
     * must own its own [ImageComposeScene] (or per-host equivalent) so
     * `remember`'d state survives across [InteractiveSession.dispatch]
     * calls. Default no-op throws — hosts that don't support
     * interactive mode reject `interactive/start` via the standard
     * MethodNotFound error path.
     */
    fun acquireInteractiveSession(
        previewId: String,
        classLoader: ClassLoader,
    ): InteractiveSession =
        throw UnsupportedOperationException(
            "interactive mode unsupported by ${this::class.simpleName}"
        )
}
```

`JsonRpcServer.handleInteractiveStart` checks
`host.acquireInteractiveSession` for `UnsupportedOperationException`
and reflects that to the wire as `MethodNotFound (-32601)` so old
panels can fall back gracefully.

## 9.6 Coalescing path

The daemon coalesces input bursts arriving while a render is already
in flight for the same stream. `handleInteractiveInput` checks whether
the session has a render in flight; if so, it appends the event to a
per-session pending queue rather than enqueuing another render. When
the in-flight render finishes, the watcher dispatches all queued
events through `sendPointerEvent` in one batch and renders once. This
caps the render rate at the renderer's natural cadence (typically 60
Hz on Skiko) without dropping events.

`pointerMove` events specifically should also be coalesced
intra-batch — keep only the most recent move at any pixel.

## 9.10 v3 Android pointer

Android click dispatch requires sandbox pinning: see
[INTERACTIVE-ANDROID.md](INTERACTIVE-ANDROID.md) for the full
architecture. `RobolectricHost.acquireInteractiveSession` is supported
iff `sandboxCount >= 2`. With `sandboxCount == 1` the host throws
`UnsupportedOperationException` and `JsonRpcServer` falls back to v1.
