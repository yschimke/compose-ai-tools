# Next-session handoff: `:tui-cli` + Mosaic fork

Branch: `agent/mosaic-tui-preview` (compose-ai-tools).
Mosaic fork: `yschimke/mosaic#1` (`compose-ai-tools` branch).

## Where you're picking up

`PreviewViewPane` now consumes the fork's `Image` composable — the centre pane decodes
the PNG into a `com.jakewharton.mosaic.ui.Bitmap` (via `image/Bitmaps.kt`) and hands it
to `Image(bitmap = ..., cellWidth = ..., cellHeight = ...)`. The composable picks Kitty
Graphics / half-block / ASCII at render time based on `$KITTY_WINDOW_ID`,
`$COLORTERM`, and `$TERM`, so the same `PreviewViewPane` produces high-fidelity output
on a Kitty terminal and graceful degradation elsewhere. The hand-rolled
`AnsiImage.renderAscii` / `AnsiImage.render` are gone — the fork's encoders supersede
both. See `MOSAIC-IMAGE-RFC.md` for the upstream API surface.

## Open from this session

- **The Mosaic fork's `mosaic-tty/build.gradle` needs two local patches on Gradle 9.5**
  to publish cleanly. Both are committed in `../mosaic` as part of session work but
  also need filing upstream — see `MOSAIC-FORK.md` § "Fork-side patches still needed".
- **The kitty e2e harness can't run in this sandbox** because `kitty` / `xdotool` /
  `imagemagick` aren't apt-installed. `Xvfb` is. Run on a dev box with all four binaries
  to capture the new wide/01-initial.png and confirm Kitty Graphics dispatch fires.
- **`PreviewIndex` doesn't refresh when `previews.json` changes mid-session.** Codex
  flagged this in PR review (R3303104398): `remember(modules)` only re-runs when the
  module list changes, so adding/removing an `@Preview` in a live edit session updates
  `previews.json` and triggers a daemon `renderFinished`, but the TUI's list pane
  continues to show the old rows until restart. The fix is to recompute
  `PreviewIndex.loadRows(modules)` on the same FileWatcher tick that re-reads the PNG.

## Filing the four sibling RFCs upstream

The mouse / resize / text-field / redraw RFCs at the repo root are written to be filed
back against `JakeWharton/mosaic`:

| RFC | File against |
| --- | --- |
| `MOSAIC-MOUSE-RFC.md` | new issue |
| `MOSAIC-RESIZE-RFC.md` | new issue or PR adding KDoc |
| `MOSAIC-TEXTFIELD-RFC.md` | new issue |
| `MOSAIC-REDRAW-RFC.md` | new issue + small renderer PR |

Mouse is the highest-value because the decoder already exists (`terminal.MouseEvent` is
in 0.18); composition-side surface is the only gap. Filing that one with an "I'm happy
to PR steps 1–3" note is the most actionable starting point.

## Files to read first

In order:

1. [`MOSAIC-FORK.md`](MOSAIC-FORK.md) — current state of the wiring + the Gradle 9.5
   workarounds added this session.
2. [`LIMITATIONS.md`](LIMITATIONS.md) — what each RFC closes; the image item is now
   marked closed and the workaround section reflects the `Image` composable adoption.
3. [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md) — what the fork's `RawText` / `Image`
   do; the acceptance-test paragraph is updated to reflect that this consumer no longer
   ships `AnsiImage.kt`.
4. [`src/main/kotlin/ee/schimke/composeai/tui/ui/PreviewViewPane.kt`](src/main/kotlin/ee/schimke/composeai/tui/ui/PreviewViewPane.kt)
   — the centre pane, now rendering via `Image`.
5. [`src/main/kotlin/ee/schimke/composeai/tui/image/Bitmaps.kt`](src/main/kotlin/ee/schimke/composeai/tui/image/Bitmaps.kt)
   — the PNG → `Bitmap` decoder consumed by `PreviewViewPane`.
6. [`src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt`](src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt)
   — the verification harness.
