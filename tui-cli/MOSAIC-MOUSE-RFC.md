# Mosaic mouse-input support — RFC

Companion to [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md). The TL;DR is that Mosaic 0.18
**already decodes mouse events** from the terminal byte stream — there's a fully-fledged
`com.jakewharton.mosaic.terminal.MouseEvent` with `x`, `y`, `type` (Press/Release/Drag/
Motion), `button` (Left/Middle/Right/None/WheelUp/WheelDown/Button8…11), and `shift`/
`alt`/`ctrl` modifiers. What's missing is **the composition-side surface that delivers
them to composables** and the **startup byte sequence that asks the terminal to send them
in the first place.**

That makes this a smaller RFC than the image one. The decode work is already done — we
just need to wire it through.

## Why it's hard today

Compare keyboard input, which works:

```
Terminal bytes → terminal/KeyboardEvent (decoded)
              → composition's MosaicNodeLayer
              → KeyLayer.sendKeyEvent
              → KeyModifier.onPreKeyEvent / onKeyEvent
              → Modifier.onKeyEvent { … } in user code
```

Mouse input gets stuck at step 1.5:

```
Terminal bytes → terminal/MouseEvent (decoded) ✓
              → ??? no MouseLayer in MosaicNodeLayer
              → ??? no MouseModifier interface
              → user code can't observe mouse events
```

Two concrete gaps:

1. **Startup never enables SGR mouse tracking.** A quick `strings` over `mosaic-terminal-jvm-
   0.18.0.jar` finds no `?1003` / `?1006` / `?1000` substrings, so the terminal-side
   decoder is wired up but starved of input — most terminals don't emit any of the
   sequences the decoder recognises until the application asks for them via DECSET. Our
   own `:tui-cli` confirms this empirically: with current Mosaic, mouse clicks in the
   kitty window have zero effect on the composition.

2. **No `MouseModifier` mirror of `KeyModifier`.** `com.jakewharton.mosaic.layout.KeyModifier`
   is the consumer-facing seam for keyboard input; there's no sibling for mouse. The
   `MouseEvent` type that exists is in `com.jakewharton.mosaic.terminal`, two packages
   away from anything a composable can observe.

## Proposed API

### 1. `MouseEvent` in the composition package

Mirror the existing `terminal.MouseEvent` into `com.jakewharton.mosaic.layout`. The
composition-side event differs from the terminal-side one only in adding a `position`
that's expressed as `IntOffset` in cell coordinates (the decoded `x`/`y` from the terminal
are 1-based; cell offsets should be 0-based to match `IntOffset(col, row)` semantics
already used by `Modifier.offset`):

```kotlin
package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.ui.unit.IntOffset

data class MouseEvent(
  val position: IntOffset,
  val type: Type,
  val button: Button,
  val shift: Boolean = false,
  val ctrl: Boolean = false,
  val alt: Boolean = false,
) {
  enum class Type { Press, Release, Drag, Motion }
  enum class Button {
    Left, Middle, Right, None,
    WheelUp, WheelDown,
    Button8, Button9, Button10, Button11,
  }
}
```

The shape matches `terminal.MouseEvent` 1:1 so the translation from one to the other is a
field copy plus the 1→0 coordinate shift.

### 2. `MouseModifier` mirroring `KeyModifier`

```kotlin
package com.jakewharton.mosaic.layout

interface MouseModifier : Modifier.Element {
  fun onPreMouseEvent(event: MouseEvent): Boolean = false
  fun onMouseEvent(event: MouseEvent): Boolean = false
}

fun Modifier.onMouseEvent(handler: (MouseEvent) -> Boolean): Modifier
fun Modifier.onPreviewMouseEvent(handler: (MouseEvent) -> Boolean): Modifier
```

Dispatch ordering mirrors keyboard input: pre-order pass (ancestors first) for
`onPreMouseEvent` so a parent scroll container can claim wheel events before children
swallow them; post-order pass (children first) for `onMouseEvent` so leaf nodes get the
first crack at clicks.

### 3. `MouseLayer` in `MosaicNodeLayer`

Add a sibling to `KeyLayer`:

```kotlin
internal final class MouseLayer(
  private val element: MouseModifier,
  override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
  fun sendMouseEvent(event: MouseEvent): Boolean {
    // Hit test: only dispatch if event.position is inside this node's bounds.
    if (event.position !in this.bounds) return next.sendMouseEvent(event)
    if (element.onPreMouseEvent(event)) return true
    if (next.sendMouseEvent(event)) return true
    return element.onMouseEvent(event)
  }
}
```

The bounds check is the new ingredient. `KeyLayer` doesn't need it because keyboard input
is focus-routed — the event goes to whichever node has focus regardless of where the user
clicked. Mouse events are inherently spatial: a click at `(col=5, row=12)` should only
fire the `onMouseEvent` of nodes whose layout bounds contain that cell.

Each `MosaicNode` already knows its post-layout `(x, y, width, height)` — that's how
`Modifier.offset` and `placeRelative` work. Expose the rectangle to layers and the hit
test is one `Rect.contains(IntOffset)` per node.

### 4. Startup mode-enable

At the same point in `runMosaicMain` / `runMosaicBlocking` that emits the
"enter alternate screen" + "hide cursor" sequences, also emit:

```
\e[?1003h   any-event mouse tracking (press, release, drag, motion)
\e[?1006h   SGR encoding (handles >223-column terminals, decoder already expects it)
```

And teardown:

```
\e[?1006l
\e[?1003l
```

Gate the enable behind a `Mosaic` config flag (`mouseTracking: Boolean = true`?) so
applications that don't want mouse tracking (e.g. ones that pipe through tmux without
mouse passthrough configured) can opt out cleanly. Today there's no way for the consumer
to even ask — adding mouse tracking should default-on, since the decode pipeline already
handles the inverse case (no events arriving) gracefully.

## Cell-coordinate semantics

The terminal-side decoder reports `(x, y)` in 1-based cell coordinates because that's
what xterm's wire protocol uses. The composition side should normalise to 0-based
`IntOffset` to match every other Mosaic API:

```kotlin
private fun terminal.MouseEvent.toLayout(): layout.MouseEvent =
  layout.MouseEvent(
    position = IntOffset(x - 1, y - 1),
    type = when (type) { Press -> Press; Release -> Release; Drag -> Drag; Motion -> Motion },
    button = button.toLayoutButton(),
    shift = shift, ctrl = ctrl, alt = alt,
  )
```

This is a 4-line bridge function in whatever class today routes `terminal.KeyboardEvent`
into `KeyLayer.sendKeyEvent`.

## Examples

### List-row click

`:tui-cli`'s preview list pane would benefit from this immediately:

```kotlin
@Composable
fun PreviewListPane(index: PreviewIndex, …) {
  Column(modifier = Modifier.onMouseEvent { e ->
    if (e.type == MouseEvent.Type.Press && e.button == MouseEvent.Button.Left) {
      // Hit row at e.position.y - listHeaderHeight; route to index.selectByIndex(row).
      // The bounds check has already happened in MouseLayer; we know the click landed
      // inside this Column.
      true
    } else false
  }) { … }
}
```

### Scroll wheel on preview pane

```kotlin
Box(modifier = Modifier.onMouseEvent { e ->
  when (e.button) {
    MouseEvent.Button.WheelUp -> { scrollState.scroll(-1); true }
    MouseEvent.Button.WheelDown -> { scrollState.scroll(+1); true }
    else -> false
  }
})
```

### Tab strip click in narrow mode

`TabStrip` in `Layout.kt` could become clickable directly, replacing the keyboard-only
Tab cycle for users who'd rather click.

## Out of scope (initial PR)

- **Pointer-shape protocol.** Mosaic already has `KittyPointerQuerySupportEvent` in
  `terminal/`, and `Capabilities.kittyPointerShape` reports the boolean. A
  `Modifier.pointerShape(PointerShape.Hand)` API would let composables ask the terminal to
  show a hand cursor over clickable regions — but it's a follow-up, not a blocker for
  basic click handling.
- **Drag-and-drop semantics on top of `Drag` events.** The decoder reports each motion
  during a drag; composing them into a higher-level "drag started / drag ended with
  delta" abstraction is a consumer concern initially.
- **Focus follows mouse.** Some consumers may want a click to also shift keyboard focus
  to the clicked subtree. Out of scope — focus management is its own concern.

## Backwards compatibility

`MouseModifier`, `MouseLayer`, and `Modifier.onMouseEvent` are all new symbols. No
existing consumer is affected. The startup mode-enable does emit additional bytes to the
terminal that legacy hosts might display as garbage if they don't understand DECSET — but
all terminals from the last ~25 years either implement or silently ignore the sequence.

The `mouseTracking` config flag defaults to `true`; consumers who want the legacy
no-mouse behaviour pass `false`. This matches the existing per-feature toggles in the
Mosaic startup config — verify naming during PR review.

## Sketched implementation order

1. Add `layout.MouseEvent`, `layout.MouseModifier`, `Modifier.onMouseEvent` /
   `onPreviewMouseEvent`. No runtime yet — just the surface.
2. Add `MouseLayer` to `MosaicNodeLayer`'s known layer types, including the bounds-based
   hit test. Wire the existing event pipeline (whatever routes `terminal.KeyboardEvent`
   into the composition today) to also forward `terminal.MouseEvent` after translating
   coordinates.
3. Emit the SGR mouse-mode enable/disable bytes at composition start/end.
4. Add the `mouseTracking` opt-out flag.

Steps 1+2 are enough to make the wiring work end-to-end, but no events flow because the
mode isn't enabled. Step 3 is what makes it visible to consumers. Step 4 lets people who
explicitly don't want it turn it off.

## Open questions for upstream

- **Should `onMouseEvent` see Motion events by default?** Motion (no-button-pressed cursor
  movement) is the loudest mouse event by far — typically 50–200 events/second. We
  probably want consumers to opt into Motion explicitly (`Modifier.onMouseEvent(includeMotion
  = false)` default), with `Press` / `Release` / `Drag` always delivered. Wheel events go
  through the existing Button enum so they're not affected.
- **How does this interact with tmux / screen / VS Code's terminal pane?** All three pass
  mouse events through when their own mouse-passthrough is enabled (`set -g mouse on` in
  tmux). Users who hit the "my mouse doesn't work" case usually have it disabled at the
  outer layer; document this in the `mouseTracking` flag's KDoc rather than trying to
  detect it.
- **Bounds for clipped / scrolled content?** A child inside a `Column` whose intrinsic
  height exceeds the parent gets clipped; should a mouse event at a position that's
  geometrically inside the child's bounds but visually clipped fire `onMouseEvent`? We'd
  say no — only dispatch to nodes that contributed to the rendered cell — but the layer
  needs the post-clip bounds, which is more work than just reading the layout-pass
  rectangle. Could land as a follow-up; v1 uses the unclipped layout bounds and accepts
  the corner case.

---

Acceptance test from this consumer's side: `Modifier.onMouseEvent` on the preview list
delivers a left-click `Press` at the right row, and `WheelUp`/`WheelDown` events fire
on the preview-pane `Box`. When that works, `LIMITATIONS.md` item 1 (mouse input)
collapses to "use the upstream API."
