# `:tui-cli` Mosaic limitations

This module is a Mosaic 0.18.0 consumer. The Mosaic surface gets us most of the way
there — composables, layout, key dispatch, terminal-size as Compose state — but a handful
of gaps push features into "best-effort" or "deferred" territory.

Each gap below has its own focused RFC alongside this file, suitable for filing back
upstream against Mosaic. The RFCs include API sketches, layout-engine integration notes,
and a shipping order.

| # | Gap | RFC | Upstream |
| - | --- | --- | -------- |
| 1 | Mouse input | [`MOSAIC-MOUSE-RFC.md`](MOSAIC-MOUSE-RFC.md) | new |
| 2 | Image / sixel / Kitty graphics rendering | [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md) | [#621](https://github.com/JakeWharton/mosaic/issues/621), [#897](https://github.com/JakeWharton/mosaic/issues/897) |
| 3 | Terminal SIGWINCH as Compose state (verification) | [`MOSAIC-RESIZE-RFC.md`](MOSAIC-RESIZE-RFC.md) | new |
| 4 | Structured input editing (text field) | [`MOSAIC-TEXTFIELD-RFC.md`](MOSAIC-TEXTFIELD-RFC.md) | new |
| 5 | Stale-cell clearing across recomposition | [`MOSAIC-REDRAW-RFC.md`](MOSAIC-REDRAW-RFC.md) | new |

The two non-Mosaic items (raw-escape-passthrough — folded into the image RFC under
Option A "`Text` with `displayWidth` override"; and inline data-product payload — a
`:tui-cli`-side follow-up rather than a Mosaic gap) are not duplicated here.

## What's working today

The TUI compiles, runs, and renders correctly against vanilla Mosaic 0.18 with the
limitations above. The e2e harness at
[`src/test/kotlin/.../e2e/KittyE2ETest.kt`](src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt)
proves the integration end-to-end against a real kitty terminal under Xvfb.

Workarounds per gap, summarised:

- **Mouse**: keyboard-only navigation. `j`/`k`/arrows for list, `Tab` for pane cycling.
  See `compose-preview-tui --help`.
- **Images**: grayscale ASCII art via `AnsiImage.renderAscii` — see
  [`image/AnsiImage.kt`](src/main/kotlin/ee/schimke/composeai/tui/image/AnsiImage.kt).
  Truecolor renderer (`AnsiImage.render`) exists for non-Mosaic stdout consumers.
- **Resize**: probably works today via `LocalTerminalState.current.size` being a State —
  RFC asks upstream to confirm and document.
- **Text field**: hand-rolled append + Backspace inside `onKeyEvent` —
  [`ui/App.kt#handleFilterEdit`](src/main/kotlin/ee/schimke/composeai/tui/ui/App.kt).
  Works for the trivial case, breaks on paste / cursor movement / word deletion.
- **Stale cells**: right-pad every variable-width `Text` to a known column budget. See
  [`PreviewListPane.kt`](src/main/kotlin/ee/schimke/composeai/tui/ui/PreviewListPane.kt)
  for the pattern. Brittle but holds.

## What we found while writing the RFCs

Inspecting the actual `mosaic-runtime-jvm-0.18.0.jar` + `mosaic-terminal-jvm-0.18.0.jar`
classes turned up several "the plumbing already exists, just isn't wired through" cases:

- `terminal.MouseEvent` already decodes SGR mouse events from the byte stream — Mosaic
  just doesn't enable SGR mouse mode at startup and has no `MouseModifier` to deliver
  them. See [`MOSAIC-MOUSE-RFC.md`](MOSAIC-MOUSE-RFC.md).
- `terminal.BracketedPasteEvent` exists — Mosaic decodes bracketed paste but has no
  consumer-facing surface. See [`MOSAIC-TEXTFIELD-RFC.md`](MOSAIC-TEXTFIELD-RFC.md).
- `terminal.KittyGraphicsEvent` exists and `Capabilities.kittyGraphics` is populated by
  the existing startup probe — Mosaic detects Kitty graphics support today, just has no
  API that uses it. See [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md).

Each of those reduces the shipping work to "expose the existing decoder via a Modifier /
composable / `DrawScope` method" rather than "implement protocol decoding from scratch."
That's smaller than it looked at the start.
