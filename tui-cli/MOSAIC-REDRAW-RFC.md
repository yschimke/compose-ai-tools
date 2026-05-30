# Mosaic stale-cell clearing — RFC

Smallest of the Mosaic RFCs by API surface — no new public types — but the one with the
most-visible user-facing artefact. Filed because it shows up in every `:tui-cli`
screenshot where the status bar changed width between frames.

## The bug

When the live-mode error message goes from "no daemon descriptor at /tmp/…long path/…"
(wraps the status bar to two rows) to a short string (fits on one row), the second-row
text from the prior frame stays on screen. Reviewers see two compose-preview-tui status
bars on top of each other, even though the composition only ever emits one
`StatusBar` per frame.

Direct repro from this repo: `tui-cli/build/e2e-screenshots/wide/06-data-pane-focused.png`
— look at rows 1-2 of the screenshot. Same image attached to the original commit:
[`feat(tui-cli): interactive Mosaic-based preview browser`](.).

## Why it happens

Mosaic's flush path computes per-cell diffs against the previous frame and writes only
the cells that changed. For cells **inside** the new frame's footprint the diff is
right — same content, no write; different content, write. For cells that were inside
the **previous** frame's footprint but **outside** the new frame's footprint, the diff
sees "nothing to render here" and emits no write.

Result: the previously-painted glyph in that cell stays in the terminal buffer until
something else writes into it.

The exact shape of the bug:

```
Frame N    : [compose-preview-tui  200×50  [wide]  live!  no daemon at /tmp/…long…]
             [3 previews  /=filter L=live r=render q=quit                          ]
Frame N+1  : [compose-preview-tui  200×50  [wide]  live off  3 previews  /=filter…]
             [↑ cells 0–N here got rewritten with the new short content           ]
             [↑ cells N+1–end here were NOT in frame N+1's StatusBar footprint    ]
             [↑ so they keep showing frame N's wrap-around text                   ]
```

The composition is correct — `StatusBar` is one row tall in frame N+1. The renderer
just doesn't blank the cells that frame N's content occupied but frame N+1's doesn't.

## Proposed fix

In whatever class owns the per-frame flush (most likely `MosaicComposition.flushTo(…)`
or the `Rendering` interface), track the **bounding rectangle of the previous frame's
emitted content** in addition to the per-cell diff state. Before painting frame N+1,
blank any cell that was inside frame N's bounding rectangle but outside frame N+1's:

```kotlin
fun flushTo(out: TerminalOutput) {
  val prevBounds = lastEmittedBounds
  val newBounds = computeBounds(currentFrame)
  if (prevBounds != null) {
    // Cells that were painted last time but won't be this time → blank them first.
    for (cell in prevBounds - newBounds) {
      out.moveCursor(cell.row, cell.col)
      out.write(' ')
    }
  }
  // … existing per-cell diff write …
  lastEmittedBounds = newBounds
}
```

The `prevBounds - newBounds` set difference is cheap — both rectangles are typically
small (a few dozen cells), and `IntSet` / `BitSet` style storage keeps the iteration
free of allocation.

## Alternative: clear-screen on every redraw

The naive fix is `\e[2J` (erase entire display) before each frame, then repaint from
scratch. Mosaic explicitly doesn't do this because it'd cause visible flicker on slow
terminals (foot, kitty over SSH, screen multiplexers). The per-cell diff is the right
optimisation; we just want to extend it to handle the shrink case.

## Alternative: synchronised output

`Capabilities.synchronizedOutput` exists in Mosaic 0.18 and presumably already wraps
frame writes in `\e[?2026h … \e[?2026l` on supporting terminals. That helps with
**inconsistent intermediate frames** (tearing) but doesn't help with this bug — the
final state after `\e[?2026l` still has the un-blanked cells, because the renderer
never told them to be blank.

## Why this surfaces more in our TUI than in other Mosaic consumers

Most Mosaic samples have fixed-width content — the Robot game's status line, the Snake
game's score panel, etc. all stay the same width frame-to-frame. The bug exists for
them too, just nobody ever shrinks the content so it never shows.

`:tui-cli`'s status bar is uniquely vulnerable: it concatenates several variable-width
spans (live error message, filter text) into one `Row`, and the live-error span can be
hundreds of characters long ("no daemon descriptor at /tmp/junit17669321906557413052/
tui-fixture/sample/build/compose-previews/daemon-launch.json") or zero characters
("live off"). The width swings by an order of magnitude between adjacent frames.

A consumer-side workaround is to right-pad every variable-width `Text` to a known
column budget so the row always emits the same width regardless of content. That's what
`PreviewListPane.kt` does with `.padEnd(width).take(width)`. It works but it's brittle
— the budget has to match the actual column count, the column count is reactive, and
forgetting to pad even one `Text` introduces the artefact again. Fixing it in the
renderer is the right place.

## Verification

The simplest test:

1. Compose a `Text("aaaaaaaaaaaaaaaaaaaa")` (20 chars).
2. Recompose with `Text("bbb")` (3 chars).
3. Capture the terminal grid.
4. Assert: cells 3-19 are spaces, not `'a'`s.

This is a Mosaic-side unit test against the renderer's flush path, no actual terminal
needed — the renderer's output buffer is the source of truth.

## Sketched implementation

The fix is small enough to inline here:

```kotlin
// In Mosaic.renderTo() / equivalent
private var lastFrameOccupiedCells: IntSet = emptyIntSet()

internal fun renderFrame(frame: ComposedFrame, out: TerminalCanvas) {
  val newOccupiedCells = computeOccupiedCells(frame)
  // Cells from last frame that won't be touched this frame → blank them.
  (lastFrameOccupiedCells - newOccupiedCells).forEachCell { col, row ->
    out.moveCursor(col, row).write(' ')
  }
  // Existing per-cell diff write.
  frame.cells.forEach { cell -> out.paint(cell) }
  lastFrameOccupiedCells = newOccupiedCells
}
```

The `computeOccupiedCells` step is one pass over the frame's cell grid — the renderer
already iterates the grid to do the per-cell diff, so this is a one-line addition
inside that pass (`if (cell.hasContent) occupied.add(packed(col, row))`).

## Out of scope

- **Z-ordering / overlap.** If frame N painted a popup that overlapped a list, and
  frame N+1 closed the popup, the cells that were popup-only need to be repainted with
  the list content underneath. The per-cell diff already handles this correctly for
  cells that show "different content this frame"; the shrink fix only addresses cells
  that go from "showed something" to "showed nothing."

---

This is the smallest and least controversial of the Mosaic-side RFCs. Could land as a
self-contained PR with a renderer-level unit test, independent of the mouse, image,
or text-field work.
