# `rc-fixtures`

Real Remote Compose documents, copied verbatim out of a published catalog bundle so a render test
can exercise the bytes a player actually receives rather than a hand-built approximation.

| File | Source |
| --- | --- |
| `IndeterminateCircularProgress-400x400.rc` | `ir/com.example.designcatalogremotem3.ComponentVariantPreviewsKt.IndeterminateCircularProgressRemote_width_200dp_height_200dp_dpi_320.rc` from `bundle/bundle.png` on `design-artifacts/remote-m3` |
| `AppCardRemote-640x480.rc.b64` | copied from `third_party/rc-embedded-player/src/test/resources/rc-fixtures/`, where it backs the embedded player's SVG export test |

The indeterminate indicator is here because it is the shape that broke: it animates by reading the
player-supplied clock (`RcSystemVariables.CONTINUOUS_SEC`) rather than by carrying an animation, so
a player that does not load the system variables draws no arc at all — and, because the whole
indicator's geometry hangs off that one reference, no track either (#4264). Regenerate it from the
delivery branch if the catalog's indicator changes shape; nothing here is edited by hand.

`AppCardRemote-640x480.rc.b64` is here for two measurements, not for a render: its header declares
`DENSITY_BEHAVIOR_DP` at a generation density of 2.0, and the four corners of its
`RoundedClipRectModifierOperation` are literal `52f` — a 26dp card corner with the density already
folded in at capture. That is the fact `RcRoundedClipDensityTest` pins, and the reason the player
must not scale a clip radius by density a second time (#4712, and #4710 for the embedded player).

The same document's four padding edges are literal `24f` — a 12dp card inset with the density folded
in the same way, by the same `RemoteDp.toPx()` at capture. `RcCapturedPixelsDensityTest` pins that
one, for the same reason and against the same doubling (#4749, and #4727 for the embedded player).

Both copies are the same bytes; the embedded player's is the original.
