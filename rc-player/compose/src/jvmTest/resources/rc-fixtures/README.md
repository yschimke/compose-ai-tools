# `rc-fixtures`

Real Remote Compose documents, copied verbatim out of a published catalog bundle so a render test
can exercise the bytes a player actually receives rather than a hand-built approximation.

| File | Source |
| --- | --- |
| `IndeterminateCircularProgress-400x400.rc` | `ir/com.example.designcatalogremotem3.ComponentVariantPreviewsKt.IndeterminateCircularProgressRemote_width_200dp_height_200dp_dpi_320.rc` from `bundle/bundle.png` on `design-artifacts/remote-m3` |

The indeterminate indicator is here because it is the shape that broke: it animates by reading the
player-supplied clock (`RcSystemVariables.CONTINUOUS_SEC`) rather than by carrying an animation, so
a player that does not load the system variables draws no arc at all — and, because the whole
indicator's geometry hangs off that one reference, no track either (#4264). Regenerate it from the
delivery branch if the catalog's indicator changes shape; nothing here is edited by hand.
