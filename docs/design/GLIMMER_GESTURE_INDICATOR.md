# Glimmer gesture indicator — input event + visual annotation

Follow-up to `docs/design/GLIMMER_PREVIEW.md` § "Data extension:
`:data-glimmer-input-connector`". Promotes `GlimmerGesture` from a label-only
overlay to a **first-class input event** that the renderer dispatches through
Compose's input pipeline alongside touch and keyboard, *and* paints a visual
indicator into the capture so the GIF makes the gesture obvious.

## Why

The current `samples/xr-glimmer/GlimmerInteractiveMenuPreviews.kt` drives its
four-frame focus walk by calling `moveFocus(...)` imperatively from a
`@FocusedPreview(indices = [0, 1, 2, 3])` annotation. That gets focus into
the right slot but skips the input pipeline entirely — the focus moves, but
nothing in the capture or in the composition's state reflects *which gesture
caused it*. The earlier attempt to surface that information drew a
hand-rolled "▲ swipe up" pill into the menu composable; we removed it
because Studio's own previews don't paint that affordance, and inventing one
puts a non-existent UI element on the captured PNG.

The right answer is to do both halves properly:

1. **Treat the swipe as an input event** — synthesise a `KeyEvent` (or
   `MotionEvent` for two-finger gestures, when we add them) at frame start
   so the focus walk goes through Compose's `FocusManager` →
   `LocalInputModeManager` → `Modifier.focusable` chain exactly the way it
   does on-device. The same path touch and keyboard events already take.
2. **Paint a visual indicator** — a small, themeable arrow + label pinned
   to the bottom-centre of the *renderer*'s output, not the preview
   composable. Opt-in per preview, so authors that don't want the chip
   don't get one. Animated across frames so a sequence of swipes reads as
   a sequence in the GIF.

The first half is what makes the preview *behave* like the device. The
second half is what makes the GIF readable to a reviewer who hasn't run it
on AI Glasses.

## Input event model

Add a `GlimmerGesture` enum to a small shared module (likely the existing
`:renderer-android` since the dispatcher lives there). The wire-format type
in `GlimmerInputOverride` (already named in `GLIMMER_PREVIEW.md`) becomes
the same type, not a separate one.

```kotlin
enum class GlimmerGesture(
  internal val keyCode: Int,        // KeyEvent.KEYCODE_DPAD_UP, etc.
  internal val glyph: String,
  internal val label: String,
) {
  SwipeUp   (KeyEvent.KEYCODE_DPAD_DOWN, "▲", "swipe up"),    // forward in lists
  SwipeDown (KeyEvent.KEYCODE_DPAD_UP,   "▼", "swipe down"),  // back in lists
  SwipeLeft (KeyEvent.KEYCODE_DPAD_LEFT, "◀", "swipe left"),
  SwipeRight(KeyEvent.KEYCODE_DPAD_RIGHT,"▶", "swipe right"),
  Tap       (KeyEvent.KEYCODE_DPAD_CENTER, "●", "tap"),
  Back      (KeyEvent.KEYCODE_BACK,        "↺", "back"),
}
```

The swipe-up → DPAD_DOWN mapping needs verification against
`:references/focus-source.md` from the official Glimmer skill — vertical
swipes on AI Glasses traditionally move focus in the *opposite* direction
to the finger (content scrolls under the finger, the focused row moves
toward the next item) but the platform's exact mapping is what counts. Wire
the enum first, swap the keycodes after the reference confirms.

Dispatch happens through `androidx.compose.ui.platform.AndroidComposeView`'s
existing key-event dispatcher. The renderer's host activity already drives
focus walks via `moveFocus(...)`; we add `dispatchKeyEvent(...)` as a peer.
Both routes converge on the same `Modifier.focusable` listeners in the
target composable.

```kotlin
internal fun ComposeViewRoot.dispatchGesture(gesture: GlimmerGesture) {
  val event = KeyEvent(KeyEvent.ACTION_DOWN, gesture.keyCode)
  dispatchKeyEvent(event)
  dispatchKeyEvent(KeyEvent(event).apply { action = KeyEvent.ACTION_UP })
}
```

No special-casing in core (per AGENTS.md → *Important constraints*). The
dispatcher lives next to the existing `FocusManager` plumbing in
`:renderer-android`; the Glimmer-specific keycode mapping is the only
Glimmer-aware part and lives in the enum itself.

## Client API: opt-in per preview

Two annotations, composable in any combination:

```kotlin
@Preview(name = "Glimmer XR Menu · Dark", device = AI_GLASSES_DEVICE_SPEC)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@GlimmerPreviewInput(
  gestures = [INITIAL, SWIPE_UP, SWIPE_UP, SWIPE_UP],
  showIndicator = true,
)
@Composable
fun GlimmerXrMenuDark() = InteractiveMenuOnEnv(GlimmerEnvironment.Dark)
```

- `gestures` — one entry per captured frame. `INITIAL` means "no gesture,
  just capture the starting state". The renderer dispatches each gesture
  *before* the corresponding frame's capture, so frame N shows the state
  after gesture N-1 fires.
- `showIndicator = true` — paints the visual indicator (next section). Set
  to `false` for tests that want the input event but not the chip (e.g.
  pixel-diff tests asserting that the focused item moved). Default is
  `true` since the annotation's main job is making the gesture obvious.

When both `@FocusedPreview(indices = ...)` and `@GlimmerPreviewInput` are
present, `@GlimmerPreviewInput` wins — its `gestures` array length sets the
frame count, and `indices` is treated as a fallback assertion that the
gesture sequence lands focus on the expected items. (Useful as a defensive
check: if the keycode mapping is wrong, the assertion catches it.)

When only `@GlimmerPreviewInput` is present, the renderer infers frame
count from the `gestures` array length. When only `@FocusedPreview` is
present, behaviour is unchanged from today.

## Visual indicator

Rendered by `:data-glimmer-input-connector`'s `AroundComposableExtension`
(already named in `GLIMMER_PREVIEW.md`). Not painted by the preview
composable itself — that's the rule we just enforced by removing the
hand-rolled chip.

Visual spec:

```
                ┌─────────────────────────────────────┐
                │  ┌──────────────┐                   │
                │  │  Next track  │  ◀── focus ring    │
                │  └──────────────┘                   │
                │   Previous track                    │
                │   Add to favourites                 │
                │   Send to phone                     │
                │                                     │
                │         ┌─ ▲  swipe up ─┐           │  ◀── gesture indicator
                │         └──────────────┘            │       (renderer-painted,
                │                                     │        not in composable)
                └─────────────────────────────────────┘
```

- Pill shape, ~40dp tall, ~120dp wide, pinned to bottom-centre with 16dp
  bottom margin.
- Glyph + label from the `GlimmerGesture` enum. Centred in the pill.
- **Themed by `:data-glimmer-input-connector`**, not hardcoded — same way
  the connector themes the focus ring it already plans to paint. Default
  theme matches Glimmer's `TitleChip` (rounded surface, outline border,
  surface tint) so it reads as a HUD overlay rather than a foreign tooltip.
- **Animation across frames** — the pill briefly enlarges + fades to ~80%
  alpha on the frame the gesture fires, then settles back to its resting
  size on the next frame. Reads as "swipe just happened" without needing
  motion blur or arrow trails. ~120ms hold, baked into the per-frame
  capture timing.
- **Hidden on `INITIAL` / `Tap` ambiguity** — for `INITIAL` frames no pill
  draws at all (no gesture happened). For `Tap` the pill draws but the
  focus-ring annotation gains a brief inner glow on the focused element to
  signal the activation.

Drawn into the captured PNG, so consumers of the renderer (VS Code panel,
CLI, `compose-preview show`) get the indicator for free without each
having to composite their own.

## Worked example

The current `samples/xr-glimmer/GlimmerInteractiveMenuPreviews.kt` would
change from this:

```kotlin
@Preview(name = "Dark", device = AI_GLASSES_DEVICE_SPEC)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuDark() = InteractiveMenuOnEnv(GlimmerEnvironment.Dark)
```

to this:

```kotlin
@Preview(name = "Dark", device = AI_GLASSES_DEVICE_SPEC)
@GlimmerPreviewInput(
  gestures = [INITIAL, SWIPE_UP, SWIPE_UP, SWIPE_UP],
  gif = true,
)
@Composable
fun GlimmerXrMenuDark() = InteractiveMenuOnEnv(GlimmerEnvironment.Dark)
```

`@FocusedPreview` becomes optional. The four-frame GIF now shows:

- Frame 1 — focus on "Next track", no pill (INITIAL).
- Frame 2 — focus on "Previous track", pill enlarged showing `▲ swipe up`.
- Frame 3 — focus on "Add to favourites", pill same.
- Frame 4 — focus on "Send to phone", pill same.

Same four-frame walk as today, but now each transition has a visible
cause. A reviewer scanning the GIF can answer "what did the wearer do?"
without reading the source.

## CLI surface

`compose-preview render --glimmer-input gesture=swipeUp` (already in
`GLIMMER_PREVIEW.md`) dispatches a single gesture and captures one frame.
The annotation-driven multi-gesture sequence is a renderer-side feature;
the CLI surface stays one-gesture-per-invocation because the multi-gesture
case is "I'm running discovery on a `@GlimmerPreviewInput`-annotated
function", which goes through the discovery pipeline not the one-shot CLI.

## Live preview surface

The three primitives — `GlimmerGesture` enum, `dispatchGesture(...)`, and
the indicator overlay — compose into an interactive preview unchanged.
The same `AndroidComposeView` that the renderer drives during capture
backs an interactive preview, so dispatching a synthesised `KeyEvent`
through it has the same effect: focus moves, `Modifier.focusable`
listeners react, and the indicator pill (since it's an
`AroundComposableExtension` on the composable's layer, not a capture-time
post-process) animates against the live composition. No reimplementation
needed.

What's missing for live use is an **input-trigger surface**. The capture
case has `@GlimmerPreviewInput(gestures = [...])` as a scripted,
deterministic sequence. The live case needs the human to be the source.
Two surfaces, both useful, both ship together:

### Toolbar above the canvas

Six buttons pinned above the preview canvas — one per `GlimmerGesture`
variant — each labelled with the same glyph + label the indicator pill
uses. Click → `host.dispatchGesture(SwipeUp)` → the `AndroidComposeView`
receives a synthesised `KeyEvent`, focus moves, the connector's indicator
pill animates.

```
┌─────────────────────────────────────────────────────────┐
│  [▲ swipe up] [▼ swipe down] [◀ swipe left] [▶ swipe   │  ◀── toolbar
│  right]  [● tap]  [↺ back]                             │      (host chrome)
├─────────────────────────────────────────────────────────┤
│                                                         │
│   ┌──────────────┐                                      │
│   │  Next track  │  ◀── focus ring                       │
│   └──────────────┘                                      │
│    Previous track                                       │   ◀── live preview
│    Add to favourites                                    │       canvas
│    Send to phone                                        │
│                                                         │
│           ┌── ▲  swipe up ──┐                            │  ◀── indicator pill
│           └────────────────┘                            │      (in-canvas, same as
│                                                         │       in captured GIFs)
└─────────────────────────────────────────────────────────┘
```

Lives in the preview host's chrome, not the captured frame — so the
toolbar exists in VS Code panel / Android Studio interactive preview /
CMP hot-reload but is **never** baked into the GIF. The pill *inside* the
canvas is the same overlay either way; the toolbar is just one of several
ways to trigger a dispatch.

Host integration is per-platform but the wiring is small in each case:

- **VS Code panel** — toolbar buttons in `vscode-extension/src/webview/
  previewCard.ts` alongside the existing display-filter chips. Click
  posts an RPC message to the daemon which calls `dispatchGesture` on the
  host view. Already the pattern the panel uses for other interactive
  controls.
- **Android Studio interactive preview** — IDE-integration point is the
  Studio plugin; a "Gestures" toolbar group sits next to Studio's
  existing "Interactive" / "Run preview" controls. Calls
  `dispatchGesture` directly on the in-process renderer.
- **CMP Desktop hot-reload** — toolbar is a `Row` of buttons in the
  preview window's `WindowDecoration`. Same `dispatchGesture` call,
  same overlay reaction.

### Keyboard map

Arrow keys → swipe in that direction. Enter → tap. Esc → back. Faster
than the toolbar for power users and works in every host without extra
chrome.

| Key      | Gesture     |
| -------- | ----------- |
| `↑`      | SwipeUp     |
| `↓`      | SwipeDown   |
| `←`      | SwipeLeft   |
| `→`      | SwipeRight  |
| `Enter`  | Tap         |
| `Esc`    | Back        |

**Important — the keys are intercepted at the host, not in the
composition.** Without that distinction, `↑` and `↓` would *both* fire
the synthesised `DPAD_DOWN` / `DPAD_UP` AND continue propagating as the
original `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` events, double-firing
focus moves. The host's key listener consumes the key, calls
`dispatchGesture`, and returns true to stop further propagation.

Hold-to-repeat is on (`KeyEvent.ACTION_DOWN` with `getRepeatCount() > 0`
re-fires the gesture) so holding `↑` walks focus through a long list at
the platform's key-repeat rate, the same way it would on a desktop. Tap
and Back don't repeat — first event only.

### Relationship to the annotation

The annotation and the live surface are complementary, not exclusive:

- The annotation drives **capture** — deterministic, reproducible,
  test-asserted. Reviewers see the GIF in the PR.
- The toolbar + keyboard map drive **iteration** — the author plays with
  the preview, finds a focus walk that reads well, then writes the
  matching `gestures = [...]` array into the annotation to lock it in
  for capture.

A preview function can be annotated with `@GlimmerPreviewInput(gestures
= [...])` and *also* be opened in interactive mode — discovery uses the
annotation for capture, the host's toolbar lets the author override it
live. The annotation's `gestures` array is the canonical sequence; the
toolbar is a scratch surface.

## Relationship to existing primitives

| Primitive                              | Today                                  | After this design                                  |
| -------------------------------------- | -------------------------------------- | -------------------------------------------------- |
| `@FocusedPreview(indices = [...])`     | Imperative focus walk via `moveFocus`  | Still supported; gesture annotation supersedes when both are present |
| `XrGesture` enum (just deleted)        | Hand-rolled in sample, no dispatch     | Resurrected as `GlimmerGesture`, lives in `:renderer-android`, drives both dispatch and indicator |
| `:data-glimmer-input-connector`        | Named in design, not implemented       | Gains the indicator-painting `AroundComposableExtension` described above |
| Focus ring overlay                     | Named in design, not implemented       | Unchanged — still planned, this design adds gesture pill alongside it |
| `LocalInputModeManager = Keyboard`     | Set by `@FocusedPreview` for focusables to honour focus moves | Same — KeyEvent dispatch needs Keyboard mode for `Modifier.focusable` to react |

## Open questions

1. **Keycode mapping** — verify `SwipeUp → DPAD_DOWN` against the official
   Glimmer skill's `:references/focus-source.md`. If the mapping is wrong
   the `indices` fallback assertion (above) catches it during PR review;
   fix is one enum-entry edit.
2. **Multi-finger gestures** — Glimmer's input model is documented as 1-D,
   but if a future revision adds two-finger swipes (zoom, dismiss) the
   enum gains entries and the dispatcher routes through `MotionEvent`
   instead of `KeyEvent`. Out of scope for this iteration.
3. **Indicator on the additive-RGB encoding** — when
   `:data-glimmer-environment-connector` lands and captures move to
   encoding B (transparent background, no env composite), should the
   indicator paint into the alpha-RGBA capture or only into the
   env-composited output? Default: paint into both — the indicator is a
   debug affordance for reviewers, not part of the additive light the UI
   actually emits, and a downstream consumer that doesn't want it can
   strip it with the same `--composite-onto` post-process the env
   compositor already uses.
4. **GIF frame timing for the enlarge-fade animation** — the renderer
   currently captures one steady frame per index. Sub-frame animation
   (enlarge over 60ms, hold 60ms) means either capturing two PNGs per
   gesture or post-process tweening. Tweening is cheaper. Lock in a
   format after the indicator's visual treatment is reviewed on real
   renders.
5. **Live-mode animation timing** — in capture mode the pill's
   enlarge-fade is baked into the frame timing the renderer controls. In
   live mode it has to run against the host's wall clock instead. The
   overlay should drive the animation through Compose's standard
   `Animatable` / `animateFloatAsState` (the connector already wraps the
   composable's layer) so the live host inherits the timing from the
   composition without separate plumbing — verify on first implementation
   that this matches the capture-time appearance.
6. **Toolbar focus-stealing** — clicking a toolbar button shouldn't move
   keyboard focus out of the canvas (the user expects the canvas to
   remain focused so the synthesised key event reaches the right
   composable). Each host has its own focus-management story; the
   simplest fix is `Modifier.focusProperties { canFocus = false }` on the
   toolbar buttons (Compose hosts) or the `preventDefault` /
   `tabindex="-1"` equivalent for the VS Code webview.

## Phasing

Stages can ship independently:

1. **`GlimmerGesture` enum + `dispatchGesture(...)` in `:renderer-android`**
   — minimum to flip the current sample from `moveFocus(indices = [...])`
   to gesture-driven walks. No visual change; the test still asserts four
   distinct GIFs.
2. **`@GlimmerPreviewInput` annotation** — discovery wires through. At
   this point the sample's annotation block flips to the new shape but
   the GIF still looks the same.
3. **Indicator-painting `AroundComposableExtension` in
   `:data-glimmer-input-connector`** — pill appears in the GIF. This is
   the user-visible deliverable for capture; (1) and (2) can land in
   either order before this.
4. **Live-mode trigger surfaces** — toolbar + keyboard map in each host
   (VS Code panel, Android Studio interactive preview, CMP hot-reload).
   Reuses (1)'s dispatcher and (3)'s overlay; the host integration is
   the only new code per platform. Ships independently per host — VS
   Code panel first (already the most-customised surface), Studio +
   Desktop after.
5. **Focus-ring overlay in the same connector** — the
   `GLIMMER_PREVIEW.md`-named focus-ring affordance lands next to the
   gesture pill, completing the connector's planned surface.

(1) and (2) are renderer-only and Glimmer-agnostic (the enum is the only
Glimmer-aware part). (3) and (5) are the Glimmer connector. (4) is host
chrome — per-platform but small. A non-Glimmer project could ship its own
connector with a different indicator look and reuse all the
renderer-side + host-side wiring unchanged.
