# `:tui-cli` Mosaic limitations

This module is a Mosaic 0.18.0 consumer. The Mosaic surface gets us most of the way
there — composables, layout, key dispatch, terminal-size as Compose state — but a handful
of gaps push features into "best-effort" or "deferred" territory. The user has asked us to
write each one up so they can be addressed in a Mosaic fork.

> **Convention:** each section starts with `## Need:` (what the consumer wants to write),
> `## Today:` (what we work around), and `## Fork shape:` (the smallest patch that would
> satisfy us). If you're tackling one of these in a fork, the Fork shape section is the
> deliverable.

---

## 1. Mouse input

### Need

The TUI's primary inputs are mouse clicks on preview list rows (selection), scroll-wheel
on the preview pane (when image overflows the viewport), and mouse hover over a11y
findings (focuses the relevant region of the overlay). Concretely:

```kotlin
Modifier.onMouseEvent { e ->
  when (e) {
    is MouseEvent.Click -> { … }
    is MouseEvent.Scroll -> { … }
    is MouseEvent.Hover -> { … }
    is MouseEvent.Drag -> { … }
  }
  true // consumed
}
```

### Today

Mosaic 0.18 does not expose mouse events through the composition. The `KeyModifier`
plumbing (`com.jakewharton.mosaic.layout.KeyModifier`) is wired up to the terminal's
keyboard event stream only — there's no sibling `MouseModifier` in the `layout` package,
no `LocalMouseDispatcher`, and the SGR mouse mode (`\e[?1003;1006h`) is never enabled
when `runMosaicMain` starts.

Users have to navigate exclusively by keyboard (arrow keys / `j`/`k` / `Tab`). The
intended-mouse-driven affordances are exposed as keyboard equivalents (`r` for force
render, `/` for filter, `L` for live toggle) — see `tui-cli/Args.kt`'s usage text.

### Fork shape

1. **Enable SGR mouse mode** in `runMosaicMain` / `runMosaicBlocking` setup. Today the
   runtime emits `\e[?1049h` (alternate screen) and the cursor-hide sequence; add
   `\e[?1003h\e[?1006h` (any-event tracking + SGR encoding) alongside. Pair with the
   shutdown sequence (`\e[?1003l\e[?1006l`) in the matching teardown path.

2. **Parse mouse events** in `com.jakewharton.mosaic.terminal.TtyEventReader` (or
   wherever the keyboard event stream is decoded today). SGR-encoded mouse events arrive
   as `\e[<{btn};{x};{y}{M|m}` — `M` = press, `m` = release; `{btn}` is a bitfield where
   bit 5 distinguishes motion-with-button-held from a pure click, and bits 6–7 carry
   scroll-wheel direction.

3. **Expose `Modifier.onMouseEvent`** alongside `Modifier.onKeyEvent`. The interface
   should mirror `KeyModifier`:

   ```kotlin
   interface MouseModifier : Modifier.Element {
     fun onPreMouseEvent(event: MouseEvent): Boolean
     fun onMouseEvent(event: MouseEvent): Boolean
   }

   sealed class MouseEvent(val column: Int, val row: Int, val modifiers: KeyModifiers) {
     class Press(c: Int, r: Int, val button: MouseButton, m: KeyModifiers) : MouseEvent(c, r, m)
     class Release(c: Int, r: Int, val button: MouseButton, m: KeyModifiers) : MouseEvent(c, r, m)
     class Move(c: Int, r: Int, val button: MouseButton?, m: KeyModifiers) : MouseEvent(c, r, m)
     class Scroll(c: Int, r: Int, val direction: ScrollDirection, m: KeyModifiers) : MouseEvent(c, r, m)
   }
   ```

4. **Hit-testing**: the `MosaicNodeLayer` tree already knows each node's bounds in cell
   coordinates (the layout phase computed them). Route `MouseEvent` through the same
   pre-order/post-order pass `KeyLayer` uses, but match by `(column, row)` ∈ node bounds
   instead of focus-chain. The pre-order pass gives ancestors a chance to handle scroll
   gestures before children eat them, mirroring the keyboard semantics.

---

## 2. Truecolor image rendering inside Compose layout

### Need

Render a PNG as a coloured half-block grid (`▀` with `\e[38;2;r;g;b;48;2;r;g;b m`) **inside**
a Mosaic `Text` so the row participates in Mosaic's layout (width measurement, padding,
parent box, etc.). This is what the preview pane wants to draw.

### Today

Embedding raw SGR escapes inside a `Text("…")` desyncs Mosaic's width tracking — the
escape characters are counted as visible glyphs by the layout pass, so a 40-cell image
row is measured as ~200 cells wide and the parent box reports an oversized intrinsic
width.

The current workaround is `AnsiImage.renderAscii` — grayscale luminance steps in a small
glyph ramp (`" .,:;+*?%S#@"`) with no SGR escapes. Plain text, accurate width, ugly
output.

The truecolor renderer (`AnsiImage.render`) is kept around for non-Mosaic callers (an
agent that wants to dump a single preview to stdout outside the TUI).

### Fork shape

Two viable directions. Either is enough — we'd take whichever lands first.

**Option A — `Text` with `displayWidth` override.**

Add an optional `displayWidth: Int` parameter to the `Text` composable. When provided,
Mosaic uses it for layout instead of measuring the string. The renderer still emits the
raw string verbatim. Lets the consumer say "this string is 40 cells wide regardless of
what's inside it" — perfect for image rows where the consumer knows the glyph count.

**Option B — `Modifier.background(rgb)` plus a 2-pixel-high cell mode.**

Already exists in `BackgroundKt.background-fCupJr8`. Two follow-ups would close the gap:

1. Accept 24-bit RGB (today the colour palette appears to be 8-bit / 16-bit only — the
   `background-fCupJr8` int parameter likely encodes the palette index).
2. Add a "split cell" composable that draws a different background colour in the top vs
   bottom half of one terminal cell, equivalent to the `▀` glyph trick but driven by the
   renderer rather than the consumer.

We prefer **Option A** — it's smaller and unblocks sixel / Kitty / iTerm2 escapes too
(any consumer-supplied raw byte stream with a known display width).

---

## 3. Raw escape-sequence passthrough (sixel / Kitty graphics / OSC 1337)

### Need

A `RawText("\e_Gf=32,…\e\\")` composable (or `RawEscape("…")` modifier) that emits its
contents into the rendered cell grid without participating in layout measurement.
Specifically a "this draws into the cell I'm in but the runtime should treat it as
zero-width / known-width". Pairs with #2.

### Today

Same width-tracking problem. We fall back to the ASCII renderer.

### Fork shape

If Option A from #2 lands, this is redundant — `Text(text = sixelBytes, displayWidth = N)`
covers it. If only Option B from #2 lands, add a separate `Raw` composable that takes a
string + an `IntSize` and emits to the cell grid as-is.

---

## 4. Terminal SIGWINCH as Compose state

### Need

When the user resizes their terminal window, the `LocalTerminalState.current.size` value
should recompose dependents. The TUI's narrow-vs-wide layout decision needs this — we
want a desktop user who drags their terminal narrower than 120 cols to see the layout
flip to tabs without restarting.

### Today

`LocalTerminalState` exists in Mosaic 0.18 and exposes `Terminal.Size`. We don't yet
know empirically whether the size updates on SIGWINCH or is captured once at composition
start. If it's the latter, we'd add a Mosaic-side fix here.

### Fork shape

Install a SIGWINCH handler in the JVM at `runMosaicMain` startup (the Mosaic-native code
should already have one — it just needs to push the new `Terminal.Size` through the
`MutableState` backing `LocalTerminalState`). If the existing implementation already
does this, we'll delete this section after verification.

---

## 5. Structured input editing (text field)

### Need

A `BasicTextField`-equivalent composable for the filter editor — cursor positioning,
selection, paste handling, etc.

### Today

`App.kt`'s filter editor hand-rolls character append + Backspace inside `onKeyEvent`.
It's enough for the typical "type three characters to narrow the list" interaction but
falls over on:

- Paste — most terminals encode multi-character paste as either bracketed paste
  (`\e[200~ … \e[201~`) or a burst of individual key events. Mosaic's KeyEvent stream
  doesn't distinguish — we'd append each character individually, which works for plain
  pastes but loses the "treat this as one transaction" semantic.
- Cursor movement inside the field. Today the caret is always at end-of-string.
- Word-aware editing (Ctrl+W to delete previous word).

### Fork shape

Either ship a `BasicTextField` in `com.jakewharton.mosaic.ui` (`value: String`,
`onValueChange: (String) -> Unit`, `cursorPosition: Int`, plus the standard keyboard
shortcuts), or expose enough of the bracketed-paste / event-grouping primitives that
consumers can build one. We'd lean towards the former — text editing is a wheel worth
not reinventing.

---

## 6. Stale-row clearing across recomposition

### Need

When a composition shrinks in either dimension across recompositions — e.g. a `Row`'s second
child becomes a no-op or a status bar reduces from a wrapped two-line layout to a single
line — the cells previously occupied by the discarded content should be cleared. Reviewers
of the e2e captures see this as a "doubled status bar" in `wide/06-data-pane-focused.png`:
the long live-mode error text from the wide-mode session (which wrapped to a second row) is
still visible underneath the post-filter status bar, because the second row never got
overwritten.

### Today

Mosaic 0.18 emits absolute-position SGR sequences during repaint but doesn't blank the
delta between the prior frame's footprint and the new one. Anywhere the composition shrinks
between frames, the residue stays on-screen until something else writes into those cells.
The TUI's `Column { StatusBar(); body }` layout exposes this every time the live-mode error
message comes and goes (long string → wraps → next frame fits on one line → previous line
still drawn).

### Fork shape

In `MosaicNodeLayer` / the renderer's flush path, track per-frame max bounds and emit
spaces over the dropped delta before painting the new frame. The frame buffer Mosaic
already keeps for diffing has enough information; the patch is on the write-out side, not
the layout side.

A consumer-side workaround is to right-pad every variable-width `Text` to a known column
budget, which is what the TUI's [StatusBar.kt](src/main/kotlin/ee/schimke/composeai/tui/ui/StatusBar.kt)
should probably move toward, but it's brittle (you need the budget to match the actual
column count, and the column count itself is reactive). The fork-side fix is cleaner.

---

## 7. Structured data inline-payload pull (less a Mosaic issue, more a render-session note)

### Need

The data pane today reads `accessibility.json` off disk; when live mode is on and the
daemon has just published an in-memory `a11y/atf` payload via the `dataProduct`
notification, we re-read the file because the daemon also writes the sidecar. We'd
prefer to consume the inline payload from the notification directly — saves the disk
round-trip and works for data products that don't have a canonical on-disk shape yet.

### Today

The `LiveSession.onNotification` listener just bumps a tick — the params payload is
discarded. The `DataPane` reads the sidecar. This is by design for the MVP because the
disk path keeps live and dead modes symmetric (the same code reads `accessibility.json`
whether or not the daemon is running), but it's worth revisiting once we add a second
data product.

### Fork shape

n/a — this is `:tui-cli` code, not Mosaic. Filed here so the work item stays visible
alongside the other live-mode gaps.
