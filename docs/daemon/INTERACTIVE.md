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

1. **Click the preview image** — in focus mode, single-click on a
   non-live card enters LIVE; subsequent clicks while LIVE forward as
   pointer events. Plain click is single-target (drops every prior live
   stream); Shift+click adds the preview to the live set without
   disturbing others. In a multi-card layout (grid/flow/column) a plain
   click enters focus first, while **Shift+click goes live in place**
   (multi-stream) without leaving the grid — so several grid cards can
   stream at once.
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
displayed-vs-natural ratio. Those values are physical scene pixels: Desktop
passes them directly to `ImageComposeScene.sendPointerEvent`. Preview density
already shaped the scene's dp-to-pixel layout and must not scale input again.

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
  // Image-natural physical pixels, dispatched without density conversion.
  // Null for keyboard events.
  pixelX?: number;
  pixelY?: number;
  // For 'keyDown'/'keyUp' only.
  keyCode?: string;
  // The literal character a printable 'keyDown' produced (the browser's
  // KeyboardEvent.key). `keyCode` names the physical key; this names what it
  // typed, and a TextField inserts from the code point — so caret movement and
  // deletion work from `keyCode` alone while typing needs this (#3491).
  text?: string;
  // DOM PointerEvent.pointerType — 'mouse' | 'touch' | 'pen'. Absent means
  // touch. Load-bearing for text selection: only a mouse press-drag starts one.
  pointerType?: string;
  // Semantic target (#1784) — set instead of pixelX/pixelY to act by stable
  // handle. The daemon resolves it against the held session's *live* semantics
  // tree and dispatches at the matched node's centre. Explicit pixels win when
  // both are present (the escape hatch for canvas / custom-drawn surfaces).
  target?: { ref?: string; testTag?: string; role?: string; text?: string };
}
```

`recording/input` carries the same field set (plus `recordingId` in place of
`frameStreamId`), and both lanes share one dispatch implementation per backend —
so a live recording types and mouse-selects exactly like an ordinary interactive
session (#3545).

Notification not request: input fires-and-forgets. The daemon
dispatches the input into the active composition and emits a fresh
`renderFinished` for the same `previewId` once the composition settles.
Backpressure is the panel's responsibility: don't send a new click
before the prior frame arrives. Lost inputs are acceptable.

**Semantic targeting & resolution diagnostics (#1784).** A `target`
resolves cross-backend (Robolectric + Skiko) against the held session's
live tree, so an agent re-reads `compose/semantics`, picks a node's
`ref`, and never does pixel math — the loop self-heals across
recomposition. Because `interactive/input` is fire-and-forget, a target
that resolves to no node (or more than one) is logged daemon-side and the
input is dropped; the matching frame still renders. The **structured**
diagnostic lives on the `record_preview` / `recording/*` path, which is a
request: a missed target there yields `unsupported` script evidence whose
`targetUnresolvedReason` carries `{ code: 'noMatch' | 'ambiguous' |
'noSemanticsRoot', matchCount, candidates: [{ ref, testTag, role, text,
label, boundsInRoot }] }`. The agent disambiguates by picking a candidate
`ref` — mirroring Playwright codegen's "improve the locator when multiple
match" — without re-rendering.

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

## 9.7 A press is settled with a render before the next event

Every `pointerDown` (and the press half of a `click`) is followed by one
`scene.render()` **inside the dispatch**, before the call returns —
`ScenePointerDispatch.press` owns this, so the live lane, the click
fast-path and scripted recording playback all get it.

Compose's gesture detectors are coroutines suspended in
`awaitPointerEventScope`; a press only *becomes* the anchor of a gesture
once that coroutine has run. Dispatching the next event into the same
scene touch hands Compose a pointer whose down it has not processed yet.

That is not a theoretical window — it is the normal shape of a browser
drag. The viewer defers the press until the first `pointermove` (so a tap
stays a click), then sends `pointerDown` and `pointerMove` back to back
in the same tick. Without the settling render a text field's
mouse-selection observer never receives `onStart(pressPosition)`, so the
drag extends from wherever the caret already was instead of from the
press — and when the drag ends past the end of the text, that range is
empty and no selection is painted at all (issue #3697). Tap detection has
the same exposure, which is why the click path already rendered between
its press and release by hand.

The settling frame goes through `RenderEngine.renderSettlingFrame`, not
`scene.render` directly: it composes whatever the press invalidated, so
it carries the same `localeTag` JVM-default-`Locale` scope the capture
frames run under (`rememberResourceEnvironment` caches what it resolves,
and a `stringResource(...)` first resolved at the host default is not
re-resolved by the capture that follows), and it closes the snapshot it
allocates instead of leaving native Skia memory to a cleaner.

## 9.7.1 Press feedback: a real press, sampled by a burst

A live click has to show what the component does when you press it — the
ripple, the state layer, the pressed shape. Two things used to stop it,
and both are fixed here (wear-m3-catalog#32).

**The Android lane invoked the semantics action.** `RobolectricHost`'s
`click` arm looked for the smallest clickable node containing the point
and invoked its `SemanticsActions.OnClick` lambda. That runs the handler
and nothing else: no `PressInteraction` reaches the component's
interaction source, so the component never rippled, and the only thing
that moved on screen was whatever state the handler wrote. It now
injects a real press and release (`down` → `move` → `up`) like the
desktop lane already did.

That path needs `waitForIdle()` between the halves and after the up, not
just a `mainClock` advance. Under the held session's paused clock nothing
else resumes the node's `awaitPointerEventScope` coroutine, so without
the idle `Modifier.clickable` sees the up before it has committed to the
down and drops the tap — which is what made the semantics shortcut look
like the reliable option in the first place.
The semantics action is still the right dispatch for the paths that ask
for it by name: `uia.click` and `a11y.action.click`, the screen-reader
lane.

A click also no longer advances the held `mainClock` 100ms past its own
release before the next capture; it advances one frame. That settle is
still right for the kinds that leave a pointer **down** — their feedback
persists, and the touch overlay's active-pointer ring needs those frames
to appear — but for a click the gesture is already complete when the
dispatch returns, so the 100ms was spent on two thirds of the ripple's
fade-out.

**The frame loop sampled too slowly to catch it.** The per-stream loop
runs at `INTERACTIVE_FRAME_INTERVAL_MS` (250ms) so a resting preview is
cheap, and the render an input triggers paints t≈0 — before press
feedback has drawn anything. A Material ripple fades in over ~75ms and
back out over ~150ms, so the whole animation used to land between two
frames. Every input now runs that stream's loop at
`INTERACTIVE_BURST_INTERVAL_MS` (16ms) for `INTERACTIVE_BURST_MS`
(600ms) and then falls back to the idle cadence. The loop parks on a
per-stream wake channel rather than sleeping flat, so an input arriving
mid-park starts its burst immediately.

The burst is bounded by what is already there rather than by a budget of
its own: `interactiveRenderInFlight` drops a burst tick whose predecessor
is still rendering, so a slow host simply renders as fast as it can, and
`FrameStreamRegistry`'s dedup turns the ticks that come back
pixel-identical into `unchanged` heartbeats — a burst over a component
that does not animate costs renders, not wire.

## 9.7.2 The idle cadence backs off while nothing moves

The other end of the same loop. A resting preview cannot simply stop
being rendered: nothing tells the daemon that an animation has started,
so the only way to notice is to render and compare. But polling it at
`INTERACTIVE_FRAME_INTERVAL_MS` for as long as its socket stays open is
most of what an unattended live viewer costs on a public server — and
because every render refreshes the held session's `lastUsedAtMs`, it also
meant the idle lease could never reclaim the sandbox from a visitor who
had wandered off. The session outlived the attention.

So the idle cadence is not flat. Past `INTERACTIVE_QUIESCENT_AFTER` (3)
consecutive byte-identical frames it doubles per frame, capped at
`INTERACTIVE_IDLE_MAX_INTERVAL_MS` (2s): 250 → 500 → 1000 → 2000. An
idle viewer settles to roughly one render every two seconds instead of
four a second.

The quiescence signal costs nothing. The daemon already SHA-256s every
frame to set `renderFinished.unchanged`; `interactiveIdleRun` counts the
run length off that same comparison, keyed by previewId alongside
`lastFrameHashes` so two streams watching one preview share the
observation.

Three frames of grace before backing off at all, because one unchanged
frame is ordinary *inside* an animation — a tween's flat leading edge, a
`delay()` that has not elapsed — and reacting to a single one would
stutter the cadence for the rest of the motion.

**Nothing here delays a response.** `startInteractiveBurst` clears the
run and wakes the parked loop, so a click arriving two seconds deep into
a backoff is answered at the burst cadence, not after the wait. The only
thing a longer gap can delay is an animation that starts with nobody
touching the preview.

## 9.8 The `localeTag` scope is a process-wide reader/writer gate

Applying `localeTag` means moving the **process-global** JVM default
`Locale`: CMP `stringResource(...)` resolves through
`androidx.compose.ui.text.intl.Locale.current`, which on desktop reads
that default. One-shot renders are safe by construction — `DesktopHost`
funnels them through its single `compose-ai-daemon-host` thread — but
**held sessions are not**: each interactive session composes on its own
`compose-ai-daemon-interactive-scene-<previewId>` executor, and recording
sessions on their own playback / live-tick threads.

Every locale-scoped region therefore goes through
`RenderEngine.withPreviewLocale` — `setUp`, `renderOnce`,
`driveStaticScrollToEnd`, `renderSettlingFrame` — which gates them on one
static `ReentrantReadWriteLock` (issue #3721). The polarity is inverted
from the usual intuition, and that inversion is the point:

- a render with **no** locale override is a **reader**. It doesn't touch
  the global, it only depends on it staying put, so any number run
  concurrently and the cost is one uncontended acquire per frame.
- a render **with** an override is a **writer**, and excludes everyone.

Three constraints hold this together:

- The lock is **static**, because the thing it guards is. A daemon (or a
  test) can hold several `RenderEngine`s against one JVM.
- It is **fair**, because unlocalized readers never stop arriving on a
  busy multi-seat serve and would otherwise starve a localized writer.
- It is taken **on the composing thread only, never around a cross-thread
  wait** — held sessions reach the engine through `submit(...).get()`, so
  a lock held on the calling side of that wait deadlocks against the
  executor thread that needs it. `withPreviewLocale` also rejects a
  read→write upgrade outright, since `ReentrantReadWriteLock` would hang
  rather than fail on one.

Adding a fifth region that moves the locale without going through
`withPreviewLocale` reopens the bug; guarding only some of them is worse
than guarding none, because it looks handled.

**The gate cannot be narrowed by plumbing the locale through the
composition**, which is the obvious-looking alternative and was the open
question on #3721. CMP *resource* resolution does have a per-composition
lever (`LocalComposeEnvironment`, public since 1.11.1), but it is not the
only consumer of the global. Material resolves **its own** strings on a
separate path — `androidx.compose.material3.internal.getString` reads
`Locale.current` (the JVM default) from inside a composable that never
consults its own `Composer` — against the 75 locale bundles
`material3-desktop` ships in `androidx/compose/material3/l10n/`. So every
built-in Material string a preview draws follows the process global, with
no composition-scoped lever at any version. `material3`'s date-picker
descriptions additionally use `String.format` with no `Locale`, reading
`Locale.getDefault(Category.FORMAT)` — a *different* global that no
composition local can reach at all.

`MaterialBuiltInStringsLocaleTest` pins all three facts: that Material's
own strings follow the JVM default (the positive control), that providing
`LocalLocaleList` does **not** reach them while that provide demonstrably
applies (the canary — it turns red the day upstream wires the local into
text resolution, and says so in its failure message), and that a
`localeTag` render localizes them end to end. The full sink inventory,
across 6,373 classes of the pinned desktop artifacts, is in
[yschimke/m3-catalog#54](https://github.com/yschimke/m3-catalog/issues/54).

## 9.10 v3 Android pointer

Android click dispatch requires sandbox pinning: see
[INTERACTIVE-ANDROID.md](INTERACTIVE-ANDROID.md) for the full
architecture. `RobolectricHost.acquireInteractiveSession` is supported
iff `sandboxCount >= 2`. With `sandboxCount == 1` the host throws
`UnsupportedOperationException` and `JsonRpcServer` falls back to v1.
