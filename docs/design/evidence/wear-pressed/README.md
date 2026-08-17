# Wear M3 `ButtonPressed` — before / after

Captures of `:samples:design-catalog-wear-m3`'s `ButtonPressed` catalog specimen
either side of the switch from a hand-seeded `PressInteraction` to
`@FocusedPreview(indices = [0], pressed = true)`. Rendered with
`./gradlew :samples:design-catalog-wear-m3:composePreviewRenderAll`.

| File | What it is | Container fill at (30, 68) |
| --- | --- | --- |
| `pressed-before.png` | `ButtonPressed` with the seeded interaction source | `#E9DDFF` |
| `pressed-after.png` | `ButtonPressed` driven through the focused press path | `#C2B5DB` |
| `focused.png` | `ButtonFocused` — real focus traversal, no press | `#D4C8EC` |
| `resting.png` | `FilledButton` — untouched | `#E9DDFF` |

`pressed-before.png` is byte-identical to what `compose-preview/main` published
from 2026-08-14 onwards, and its container fill matches `resting.png` exactly:
the seeded press never reached the PNG, because Wear M3's only press affordance
is `material-ripple` — a platform `RippleDrawable` that `RobolectricRenderTest`
settles only on a `focus.pressed` capture.

`WearFocusedPressPixelTest` pins the three fills apart, so a specimen that stops
pressing fails the build rather than publishing a resting image as pressed. See
[DESIGN_CATALOGS.md](../../DESIGN_CATALOGS.md) for the full pressed/focused
capture contract across the three render hosts.
