# Wear M3 `ButtonPressed` — before / after

Captures of `:samples:design-catalog-wear-m3`'s `ButtonPressed` catalog specimen
either side of the change that made it render a real press: the fixture moved
from a hand-seeded `PressInteraction` to
`@FocusedPreview(indices = [0], pressed = true)`, and `RobolectricRenderTest`'s
platform-ripple settle was resized from `FocusController.SETTLE_MS` to its own
`PRESS_SETTLE_MS`. Rendered with
`./gradlew :samples:design-catalog-wear-m3:composePreviewRenderAll`.

| File | What it is | Container fill at (30, 68) |
| --- | --- | --- |
| `pressed-before.png` | `ButtonPressed` with the seeded interaction source | `#E9DDFF` |
| `pressed-after.png` | `ButtonPressed` driven through the focused press path | `#C2B5DB` |
| `focused.png` | `ButtonFocused` — real focus traversal, no press | `#D4C8EC` |
| `resting.png` | `FilledButton` — untouched | `#E9DDFF` |

`pressed-before.png` is byte-identical to what `compose-preview/main` published
from 2026-08-14 onwards, and its fill matches `resting.png` exactly: the seeded
press never reached the PNG, because a seeded interaction gets no ripple settle.

The after value is stable across shard layouts, which is the property that
matters — the same `#C2B5DB` renders at `composePreview { shards = 1 }` behind
the full 59-row catalog as it does at auto sharding. Before the settle was
resized it degraded with the number of captures ahead of it in the sandbox
(`#C2B5DB` → `#D5C8EC` → `#D4C8EC`), so the same commit published different
pixels from different CI jobs.

`WearFocusedPressPixelTest` pins all three fills apart. See
[DESIGN_CATALOGS.md](../../DESIGN_CATALOGS.md) for the pressed/focused capture
contract across the three render hosts.
