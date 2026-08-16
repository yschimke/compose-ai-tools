# Embedded player drops dynamic-colour fills — issue #3936

The embedded Compose player rendered a badge whose background is a **dynamic** colour id as fully
transparent, while the literal-coloured badge beside it drew normally. The homeassistant-remotecompose
button tiles show both in one frame: `ButtonIconChip` draws
`.background(accent.copy(alpha = accent.alpha * 0.2f.rf))` unconditionally, but the two tiles reach
it by different routes.

| tile | accent | encoding | before |
| --- | --- | --- | --- |
| "Temp" (sensor) | constant `0xFF2196F3` | literal-channel `SolidBackgroundModifier` | drew `#D3EAFD` |
| "Kitchen" (light, on) | `tween(inactive, active, progress)` — no constant value | `DynamicSolidBackgroundModifier`, colour id `59` | drew nothing |

Colour id `59` is `rgb(a=[55], r=[56], g=[57], b=[58])` over `ColorAttribute` ops that decompose the
tween's output, with `[55] = [54] * 0.2`. `ColorAttribute` publishes its channel from
`paint(PaintContext)`, not from `apply(RemoteContext)`, and the graph evaluated computed ops by
calling `apply` — so every channel resolved to `0`, the recombined colour was `0x00000000`, and the
disc was painted transparent.

| file | how it was produced |
| --- | --- |
| `before.png` | `RcEmbeddedRenderHarness` over `horizontal-stack (light)` and `grid (dark)`, on `main` |
| `after.png` | the same harness and documents, with `GraphContext.evaluate` |

The badge in `after.png` is `#FFF2D8`, which is `0xFFFFBE3E` (the light's active accent) at
alpha `0.2` composited over the white card — the value the document's own arithmetic gives, arrived
at without consulting another player. The `remote-player-view` render agrees to the byte, and the
blue badge is unchanged in both images, so it doubles as the control.

Across the 164-document homeassistant catalog the change moves 25 documents and leaves 137
byte-identical; every document it moves is a button, tile, or a stack of them — the surfaces whose
accent takes the tween-derived path.

`GraphContextPaintOperationTest` pins the mechanism without the images: it fails on `main` and
passes with the fix.

**Not evidence of a player bug: the baked catalog PNG for these ids.** It shows three tiles where
every player renders four, and no amber badge at all. It does not correspond to the captured
document, so it is not usable as ground truth for this comparison — see the PR discussion.
