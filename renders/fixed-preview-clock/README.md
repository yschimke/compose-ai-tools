# Fixed preview clock — issue #3239

`:samples:wear`'s `WearAppSystemClockPreview` (`@WearPreviewLargeRound`), which previews the
production `WearApp()` composition — a bare `TimeText()` with Wear's default `TimeSource`, no
`FixedPreviewTimeSource`.

| file | how it was produced | clock |
| --- | --- | --- |
| `before-run1.png` | `./gradlew :samples:wear:composePreviewRender --rerun-tasks`, before the fix | `4:52` |
| `before-run2.png` | the same command two minutes later, **no source change** | `4:54` |
| `after.png` | the same command with the fix | `10:10`, byte-identical across runs |

The two `before` files are the issue's reproduction: an unpinned render redraws the wall clock, so
the PNG changes on every run and the visual-diff bot reports it as a real diff on every PR. `after`
is what `PreviewClock` pins.

Regenerate with `./gradlew :samples:wear:composePreviewRender --rerun-tasks` and read
`samples/wear/build/compose-previews/renders/WearAppSystemClockPreview_Devices_Large_Round.png`;
`-Dcomposeai.render.fixedTime=off` is *not* the "before" state, because the instrumentation half of
the fix is unconditional — reproducing `before` means checking out a commit that predates it.
