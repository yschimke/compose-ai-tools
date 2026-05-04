# Interactive mode (VS Code panel ↔ daemon)

> **Status:** implemented for daemon and MCP scripted recording paths. The
> daemon exposes `interactive/start` / `interactive/stop` /
> `interactive/input` plus `recording/*` RPCs; MCP `record_preview` drives a
> held scene, dispatches scripted pointer input into the composition, and
> returns an APNG/MP4/WebM plus metadata. Android/Robolectric and desktop
> backends can keep composition state alive across recorded clicks. The VS Code
> panel still uses its live-preview UI path separately; use
> [MCP.md](MCP.md#scripted-interaction-recordings) for agent-facing scripted
> interactions. Frame deduplication remains part of the live stream:
> bytes-identical follow-up renders carry `renderFinished.unchanged: true`, the
> client short-circuits, and history is not re-archived. See
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
4. Captures mouse-click coordinates on the rendered image in image-natural
   pixel space. The MCP `record_preview` path uses the same coordinate
   contract to drive daemon-side pointer input; the VS Code panel path can
   route through `interactive/input` when it needs click-into-composition
   behavior.

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

Two equivalent affordances reach interactive mode:

1. **Click the preview image** in any layout (focus, grid, flow, column).
   Single-click on a non-live card enters LIVE for that preview;
   subsequent clicks while LIVE forward as pointer events to the
   daemon. Plain click is single-target (drops every prior live
   stream); Shift+click adds the preview to the live set without
   disturbing others (multi-stream).
2. **The LIVE button** in the focus-mode toolbar — same plain/Shift
   semantics. Redundant for focus-mode users but useful for keyboard
   navigation and as a visible exit.

A small **focus button** sits in each card's title row
(`codicon-screen-full`) so users can jump into focus mode without
relying on a hidden double-click affordance — single-click on the
image is reserved for entering LIVE.

When LIVE for a card, that card carries:

- `.preview-card.live` class (CSS draws a 2px red border + soft red
  glow so the live state reads from across the panel, not just on
  close inspection).
- A solid red **LIVE** chip pinned top-right with a blinking dot.
- `<img>` swaps clear `.fade-in`, so the next paint reads as a frame
  update, not a card reload.
- Crosshair cursor signalling "click here to dispatch into the live
  preview".
- Image click handler routes to `recordInteractiveClick(card, event)`
  which posts a `recordInteractiveClick` webview→extension message
  with image-natural pixel coordinates (see § 7).

LIVE is **sticky for edits** — saves trigger fresh `renderFinished`
notifications that the live card consumes. LIVE **auto-stops** when:

- The user moves focus to a different editor (extension flushes via
  `interactive/stop`, posts `clearInteractive` to the panel — both
  sides reach the off state in lockstep without race).
- A live card scrolls out of viewport (panel-side, hooked into the
  existing IntersectionObserver). Re-entering view doesn't auto-
  resume; the user re-clicks if they want it back.
- The daemon's `interactive` capability flips to false (status-bar
  hint surfaces in this case — see #431).

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

The same payload shape feeds daemon-side pointer input. MCP
`record_preview.events` uses this coordinate contract directly; VS Code panel
traffic can forward it through `interactive/input` for live click dispatch.

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

### Follow-up questions for richer input

1. **Coalescing.** Should the daemon coalesce a burst of `pointerMove` into
   one render, or render every event? Today's frame budget says coalesce
   to ~60 Hz max with the most recent state. Defer until pointer events
   ship — click-only is fine without coalescing.
2. **Input-only vs full re-render.** A click that mutates Compose state
   only needs to recompose; we can probably skip the
   `setQualifiers`/sandbox setup. Daemon-side optimisation, not visible
   on the wire.
3. **Hit testing.** The renderer receives image-natural pixel coordinates
   and lets Compose route them through its pointer-input pipeline. Keep this
   contract for new clients instead of inventing a separate component-level
   hit-test API.

## 8a. Display overrides

Per-render display properties — **size**, **density**, **locale**,
**fontScale**, **uiMode** (light/dark), **orientation**, **device**, and
**Material 3 theme tokens** — ride on the existing `renderNow` request via the optional `overrides` field
documented in [PROTOCOL.md § 5](PROTOCOL.md#renderNow). They are not
interactive-only: any caller (panel, MCP, future RPC) can attach overrides
to a single `renderNow` to get a one-off render with a different qualifier
set. A subsequent `renderNow` without `overrides` reverts to the
discovery-time `RenderSpec` — overrides are call-scoped, not
session-scoped.

**Device override.** `device: "id:pixel_5"` (or any other catalog id /
`spec:` grammar that `@Preview(device = …)` accepts) is resolved by the
daemon's built-in `DeviceDimensions` catalog into `widthPx` / `heightPx` /
`density`. Explicit `widthPx` / `heightPx` / `density` overrides on the
same call take precedence — so a caller can say `device: "id:pixel_5",
widthPx: 600` to force a wider window on the Pixel 5's density, or
`device: "id:wearos_small_round"` to flip a phone preview to a Wear round
device frame (the Android backend's `isRoundDevice` round-detection picks
up the override). Unknown ids fall back to the daemon's default
(400×800dp at xxhdpi).

**Material 3 theme override.** `material3Theme` lets callers test components
against alternate Material 3 color, typography, and shape tokens without
editing the preview. The renderer applies the override through the normal
composition path as `MaterialTheme(...) { InvokeComposable(...) }`, so regular
Material components and `MaterialTheme.colorScheme` / `typography` / `shapes`
reads see the supplied values. Example:

```json
{
  "material3Theme": {
    "colorScheme": { "primary": "#FF336699", "onPrimary": "#FFFFFFFF" },
    "typography": { "bodyLarge": { "fontSizeSp": 18.0, "fontWeight": 700 } },
    "shapes": { "medium": 16.0 }
  }
}
```

**Why not interactive-only.** Size/density/locale/fontScale/uiMode/orientation
are all the same Robolectric qualifier knob (`setQualifiers` +
`setFontScale`) under the hood. Splitting "size lives on `interactive/*`" vs
"the rest live on `renderNow`" duplicates the plumbing for no payoff, and the
MCP use case is overwhelmingly static ("render this at width=400, locale=fr,
dark") so MCP needs them on the static path regardless. Interactive mode is
just "auto-fire `renderNow` with the current overrides whenever the user
moves a slider."

**Coalescing.** When the user drags a width slider you don't want every
intermediate value to queue a Robolectric render. The daemon coalesces:
when an override-bearing `renderNow` arrives for a previewId that already
has an override-bearing render in-flight, the new one is rejected with
`reason = "coalesced: …"`. The panel / MCP client resubmits on the next
`renderFinished` if the latest override values still differ from what was
rendered. Plain (no-overrides) `renderNow` is unaffected — the save-debounce
loop continues to coalesce upstream.

**MCP surface.** The `render_preview` tool accepts the same `overrides`
sub-object verbatim — see `mcp/src/main/kotlin/.../DaemonMcpServer.kt` (tool
definition + `decodePreviewOverrides`). Agents asking "show me this preview
in dark mode at width 400" route through the static path; no live-mode
toggle required.

**Backend fidelity.** The Android renderer applies all seven fields via
`applyPreviewQualifiers` + `RuntimeEnvironment.setFontScale`. The desktop
renderer applies `widthPx` / `heightPx` / `density` (via
`ImageComposeScene`'s constructor), `fontScale` (via `Density(density,
fontScale)` re-provided as `LocalDensity`), `uiMode` (via
`LocalSystemTheme provides SystemTheme.Light/Dark`, which is what Compose
Desktop's `isSystemInDarkTheme()` reads), and `localeTag` when the Compose UI
runtime exposes a providable locale list. `orientation` remains a no-op on
desktop; older Compose Desktop runtimes also leave `localeTag` unsupported
rather than mutating JVM-wide `Locale.setDefault(...)`:

- **`orientation`** — `ImageComposeScene` has no display rotation
  concept; size override (`widthPx` / `heightPx`) is the natural lever.

## 9. v2 — click dispatch into composition

> **Status:** implemented for held-session backends and MCP scripted
> recordings. This section is retained as the design rationale for why click
> dispatch requires a held composition.

### 9.1 Why v1 isn't enough

v1's `interactive/input` triggers a fresh render of the target preview,
but the renderer still composes with `LocalInspectionMode = true` and
allocates a fresh `ImageComposeScene` per render. The composition
restarts from scratch on every frame, so any state derived from
`remember { mutableStateOf(...) }` resets between clicks — which means
even if we dispatched the pointer event correctly, the click wouldn't
*do* anything observable. The user sees the bytes refresh and that's
it.

For v2 we need three pieces, each of which is small on its own but
together force a real refactor:

1. **Held composition.** The `ImageComposeScene` must persist across
   `renderFinished` notifications for the duration of an
   `interactive/start` so `remember`'d state survives. Today's render
   path closes the scene in a `try/finally` after every render.
2. **Pointer dispatch.** The pixel coords carried in
   `interactive/input` need to reach the held scene via
   `ImageComposeScene.sendPointerEvent`.
3. **Inspection-mode flip.** `LocalInspectionMode = false` by default
   for the duration of the interactive session so previews that branch
   on `isInspectionMode` show their non-inspection ("real") behaviour
   and pointer-input modifiers actually fire. Callers may opt a held
   session back into `true` with `interactive/start.inspectionMode`.

### 9.2 InteractiveSession — held scene per stream

A new daemon-internal abstraction owns the held scene + composition
state for one `frameStreamId`. Lifecycle:

- **Allocated** by `handleInteractiveStart` after the first
  `interactive/start` for a given preview. Pre-renders one bootstrap
  frame so the panel has something to paint immediately.
- **Driven** by `handleInteractiveInput`: dispatches the pointer event
  through `scene.sendPointerEvent`, drives Compose to recompose, then
  encodes a fresh PNG and emits `renderFinished`.
- **Released** by `handleInteractiveStop` (or by daemon shutdown):
  closes the scene, frees the Skiko `Surface`, drops the entry from the
  session map.

Sketch:

```kotlin
class InteractiveSession(
    val streamId: String,
    val previewId: String,
    private val scene: ImageComposeScene,
    private val classLoader: ClassLoader,
) : AutoCloseable {

    fun dispatch(input: InteractiveInputParams) {
        val type = when (input.kind) {
            CLICK -> /* down + up */
            POINTER_DOWN -> PointerEventType.Press
            ...
        }
        val position = Offset(
            (input.pixelX ?: 0).toFloat(),
            (input.pixelY ?: 0).toFloat(),
        )
        scene.sendPointerEvent(type, position)
    }

    fun render(): RenderResult {
        // Two render() calls so launched effects + recompositions settle.
        scene.render()
        val image = scene.render()
        val pngData = image.encodeToData(EncodedImageFormat.PNG)
        ...
    }

    override fun close() { scene.close() }
}
```

`JsonRpcServer` keeps a `ConcurrentHashMap<String /*streamId*/, InteractiveSession>` parallel to the
`interactiveTargets` map. Eviction on `interactive/stop` calls
`session.close()` before removing the map entry.

### 9.3 Scene allocation — same RenderEngine, different lifetime

The desktop `RenderEngine` already knows how to set up a scene + invoke
the composable + close. For v2, factor `RenderEngine.render` into:

- `setUp(spec, classLoader, sandboxStats)` → `ImageComposeScene` (still
  holding the user's composable, no first render yet).
- `renderOnce(scene)` → `RenderResult` (encode current pixels to PNG;
  does NOT close the scene).
- `tearDown(scene)` (closes the scene + restores context classloader).

`RenderEngine.render` becomes the existing one-shot wrapper around
those three. The new `InteractiveSession` holds the scene long after
`setUp` and calls `renderOnce` per input.

### 9.4 Pointer dispatch

`ImageComposeScene` (compose-multiplatform 1.10+) exposes
`sendPointerEvent` from its `BaseComposeScene` parent:

```kotlin
fun sendPointerEvent(
    eventType: PointerEventType,
    position: Offset,
    scrollDelta: Offset = Offset.Zero,
    timeMillis: Long = System.currentTimeMillis(),
    ...
)
```

For a `CLICK` we send `Press` then `Release` at the same position back-
to-back. `pointerDown` / `pointerUp` map directly. `keyDown` / `keyUp`
go through `sendKeyEvent` (different API; same general shape).

The pixel coords on the wire are **image-natural pixels** (the
coordinate system the renderer thinks in — see § 6/§ 7). Compose's
`Offset` is in "scene px" which equals natural pixels at density 1.0;
when the spec carries a non-default `density` we divide before
dispatch:

```kotlin
val sceneOffset = Offset(
    pixelX / scene.density.density,
    pixelY / scene.density.density,
)
```

### 9.5 LocalInspectionMode flip

Compose's `LocalInspectionMode` controls a few opt-in branches —
`pointerInput` modifiers no-op when true, several text-input + IME
behaviours short-circuit, and most importantly, app code that checks
`if (LocalInspectionMode.current)` to bypass network/IO + use stub
data won't run that bypass. For v1 the trade-off was "pin
inspection-mode-aware previews to a deterministic stub", which is why
we set it to true everywhere.

For v2 the default call inverts: an interactive session is the user
*wanting* real behaviour. `interactive/start` therefore provides
`LocalInspectionMode = false` unless the caller passes
`inspectionMode: true` for previews that need their preview/stub-data
branch while still using a held session. Non-interactive renders (the
existing save → discoveryUpdated → renderFinished flow) keep
`LocalInspectionMode = true`.

The split is per-render, not per-engine: the same `RenderEngine`
serves both interactive and non-interactive renders, and the flag is a
parameter into `setUp`.

### 9.6 Coalescing pointer bursts

Click-only input, as v1 ships, doesn't burst — one click, one render.
But v2's pointer-down/move/up sequence (when we add it) can emit
hundreds of events per second from a drag.

Approach: the daemon coalesces input bursts arriving while a render is
already in flight for the same stream. `handleInteractiveInput` checks
whether the session has a render in flight; if so, it appends the
event to a per-session pending queue rather than enqueuing another
render. When the in-flight render finishes, the watcher dispatches all
queued events through `sendPointerEvent` in one batch and renders
once. This caps the render rate at the renderer's natural cadence
(typically 60 Hz on Skiko) without dropping events.

`pointerMove` events specifically should *also* be coalesced
intra-batch — keep only the most recent move at any pixel. v1 click-
only doesn't need this; revisit when move events ship.

### 9.7 Concurrent streams targeting the same preview

`interactiveTargets` is a `ConcurrentHashMap<String, InteractiveTarget>`
keyed by streamId, so two streams on the same `previewId` are legal at
the wire level (§ 8). For v2 we have two reasonable architectures:

- **One session per stream.** Each `interactive/start` allocates its
  own held scene + composition. Inputs route by streamId; each stream
  has its own state. Memory cost: 2× the scene per concurrent stream
  on the same preview.
- **One session per (previewId, classloaderGeneration).** Multiple
  streams on the same preview share a session; inputs from any stream
  drive the same composition. Memory: O(unique previews); state is
  shared, which is *probably* the more useful semantics for the side-
  by-side / CI use cases that motivated multi-target.

We pick the second: shared session per previewId, ref-counted by the
number of subscribed streamIds. The session lives until the last
subscribed stream calls `interactive/stop`. This matches what a human
would expect from "two streams looking at the same preview" — they're
looking at the same thing, not at parallel copies of it.

### 9.8 RenderHost surface — minimal interface change

The renderer-agnostic `RenderHost` interface in `:daemon:core` already
has `submit(RenderRequest, timeoutMs)`. v2 adds **one** new method:

```kotlin
interface RenderHost {
    ...

    /**
     * Acquire a held interactive session for [previewId]. The session
     * must own its own [ImageComposeScene] (or per-host equivalent) so
     * `remember`'d state survives across [InteractiveSession.dispatch]
     * calls. Default no-op throws — hosts that don't support
     * interactive mode (FakeHost, the v1 stub) reject `interactive/start`
     * via the standard MethodNotFound error path.
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

`InteractiveSession` itself moves into `:daemon:core` as an interface;
`:daemon:desktop` ships the concrete implementation. `:daemon:android`
gets a `throw UnsupportedOperationException` stub (Robolectric pointer
dispatch is its own follow-up — § 9.10).

`JsonRpcServer.handleInteractiveStart` checks
`host.acquireInteractiveSession` for `UnsupportedOperationException`
and reflects that to the wire as `MethodNotFound (-32601)` so old
panels can fall back gracefully.

### 9.9 Tests

The challenge: actually verifying clicks reach composition requires a
real Compose render. v1's `BytesAwareFakeHost` returns canned PNGs and
can't observe state mutation.

Two-layer test approach:

1. **`:daemon:core` integration test (FakeHost-driven).** Verifies the
   protocol-level plumbing — `interactive/start` allocates, `input`
   dispatches, `stop` releases — without actually exercising
   composition. The fake host records `dispatch(input)` calls into a
   visible counter; the test asserts each `interactive/input` produces
   a counter bump. Fast, deterministic, doesn't need Compose.
2. **`:daemon:desktop` integration test (real RenderEngine).** A
   stateful preview composable that paints red on first render, paints
   green after one click. The test pre-renders, asserts red bytes,
   sends `interactive/input` with pixel coords inside the click
   region, asserts green bytes on the next `renderFinished`. This is
   the honest "v2 actually works" assertion. Single-digit-second
   runtime.

Both tests live next to existing peers (`InteractiveRpcIntegrationTest`,
`RenderEngineTest`) and follow the same harness patterns.

### 9.10 Android (Robolectric) — explicitly out of scope for v2

`androidx.compose.ui.test.junit4`'s pointer-input dispatch (`onNode`,
`performClick`) requires a `ComposeTestRule` + `ActivityScenario`,
which are inseparably tied to JUnit's `@Test`-method lifetime. The
existing `RobolectricRenderTest` works around this with the dummy-
`@Test` runner trick (DESIGN § 9), and that scaffold doesn't survive
across multiple driver invocations from one daemon session — once the
test method exits, the rule is closed and the activity is destroyed.

Making Robolectric click-aware needs either (a) a custom long-lived
sandbox without the JUnit lifecycle (significant Robolectric internals
reverse-engineering — see CLASSLOADER-FORENSICS.md for the kind of
work required) or (b) rebuilding the rule + activity per input
(rebuilds composition state, defeats the point). Both are large.

v2 lands desktop only. Android `interactive/input` against a daemon
backed by `RobolectricHost` rejects with `MethodNotFound`, the panel
falls back. The status bar surfaces "interactive mode unsupported by
Android backend" so the user knows. Lifting this is a v3 problem with
its own design pass.

### 9.11 Rollout — three PRs

Roughly:

1. **PR 1 — RenderHost surface + InteractiveSession interface** (`:daemon:core`). Adds the new method + interface, keeps every existing host on the default no-op throw. JsonRpcServer's `handleInteractiveStart` translates the throw to `MethodNotFound`. The wire behaviour for callers is identical to v1: the daemon accepts `interactive/start` and dispatches inputs but the renders still come from `submitRenderAsync` (no scene-warming yet). Adds the FakeHost-driven test from § 9.9.1 to validate the plumbing.

2. **PR 2 — Desktop RenderEngine refactor + concrete InteractiveSession** (`:daemon:desktop`). Splits `RenderEngine.render` into `setUp`/`renderOnce`/`tearDown`. `DesktopHost.acquireInteractiveSession` returns a `DesktopInteractiveSession` that holds the scene. JsonRpcServer's `handleInteractiveInput` switches to `session.dispatch + session.render` for streams that have a session, falls back to the v1 path otherwise. Adds the stateful-composable integration test from § 9.9.2.

3. **PR 3 — Coalescing + extension UI polish** (`:daemon:core` + `vscode-extension`). Wires the in-flight coalescing queue from § 9.6. Adds a tiny status-bar hint when interactive mode is rejected because the host doesn't support it (Android case). Probably also flips the panel's `interactivePreviewId` to a `Set<previewId>` so the "watch two previews live" use case is reachable from the UI when the user holds Shift on the LIVE toggle (optional; requires a small UX call).

Total: ~600 lines of Kotlin + ~100 lines of TypeScript across the three PRs. Each PR is independently mergeable — PR 1 alone would let an external programmatic client build its own session-aware host without waiting for desktop wiring.

## 10. Out of scope (v1)

- **Continuous-stream rendering** in the absence of edits/clicks. v1
  treats `renderFinished` as the streaming heartbeat — no edits, no new
  frames. Animations remain the carousel's responsibility.
- **Click-into-composition** dispatch. v1 routes the click coords as far
  as the daemon's interactive/input RPC and triggers a fresh render of
  the target preview, but the renderer body still composes with
  `LocalInspectionMode = true` so the input doesn't reach the active
  composition. v2 flips the local and dispatches the pixel coords
  through `ImageComposeScene`'s pointer-input pipeline.
- **Multi-preview simultaneous live mode in the panel UI.** Surfaced
  behind the Shift modifier on the LIVE toggle: plain click is
  single-target (preserves the v1 mental model — one card live at a
  time, follow-focus on navigation), Shift+click adds or removes the
  focused preview from the live set without disturbing existing
  streams. The webview keeps `interactivePreviewIds: Set<string>` and
  decorates each card in the set; the wire protocol's multi-target
  capability (§ 8) is what makes this trivial. Programmatic clients
  exercising concurrent streams remain the primary load-bearing case;
  the human-affordance argument is "nice when you need it, not in the
  way when you don't".
- **Mouse-move / drag** events. Click is the smallest first surface; we
  reserve the wire shape but don't capture moves to keep the
  implementation honest.
- **Pixel-accurate pointer mapping** for previews with non-default
  density / scaling. v0 sends pixel coords and lets the future daemon
  resolve density. The webview reports both displayed and natural sizes
  in case a future client wants to do the math itself.
