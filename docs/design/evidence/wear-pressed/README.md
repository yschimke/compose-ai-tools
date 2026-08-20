# Wear M3 `ButtonPressed` — before / after

Captures of `:samples:design-catalog-wear-m3`'s `ButtonPressed` catalog specimen
across the two changes that made it render a real press, and then made it render
the *same* press every time. Rendered with
`./gradlew :samples:design-catalog-wear-m3:composePreviewRenderAll`.

| File | What it is | Container fill at (30, 68) |
| --- | --- | --- |
| `pressed-before.png` | `ButtonPressed` with a hand-seeded `PressInteraction` | `#E9DDFF` |
| `pressed-after.png` | Driven through `@FocusedPreview(pressed = true)`, patterned ripple, rendered early in its shard | `#C2B5DB` |
| `pressed-hardware-unsettled.png` | The same code and the same commit, rendered behind the full catalog at `shards = 1` | `#D4C8EC` |
| `pressed-software-settled.png` | `settlePressedRipple` forcing the software ripple path | `#C5B8DE` |
| `focused.png` | `ButtonFocused` — real focus traversal, no press | `#D4C8EC` |
| `resting.png` | `FilledButton` — untouched | `#E9DDFF` |

## Round one: the press has to be real

`pressed-before.png` is byte-identical to what `compose-preview/main` published
from 2026-08-14 onwards, and its fill matches `resting.png` exactly: the seeded
press never reached the PNG, because a seeded interaction gets no ripple settle.
`@FocusedPreview(indices = [0], pressed = true)` drives the press through the
path the renderer does settle.

## Round two: the press has to be *reproducible*

`pressed-after.png` and `pressed-hardware-unsettled.png` are the same source, the
same renderer and the same annotation. The only difference is how many preview
rows rendered ahead of `ButtonPressed` in the same JVM:

| Rows ahead of it at `shards = 1` | Container fill |
| --- | --- |
| 3 | `#C2B5DB` |
| 11 | `#D5C8EC` |
| the whole 50-row catalog | `#D4C8EC` |

That last value is pixel-identical to `focused.png` — a `pressed` sticker showing
no press at all — and `composePreview.shards` auto-sizes off a `previews.json`
that a cold CI checkout does not have, so which of these got published was
decided by the shard layout of whichever job happened to render the commit.

The cause is not the settle window. From Android 12 a `RippleDrawable` defaults
to `STYLE_PATTERNED`, whose enter animation runs through
`RippleAnimationSession.enterHardware` → `RenderNodeAnimator` — on the native
RenderThread, which Robolectric does not have. Measured against the full catalog
at `shards = 1`, none of these move the fill off `#D4C8EC`:

| Attempt | Fill |
| --- | --- |
| 5000ms of idled looper time (what `main` does) | `#D4C8EC` |
| 810ms of real `Thread.sleep` | `#D4C8EC` |
| 500ms of Compose `mainClock` | `#D4C8EC` |
| four extra hardware captures, 500ms apart | `#D4C8EC` |

`RippleDrawable.setForceSoftware(true)` moves it onto ordinary `ValueAnimator`s,
which the main looper drives and `ShadowLooper.idleFor` can settle.
`pressed-software-settled.png` is the result, and it is the same `#C5B8DE` at
400ms, 1600ms and 5000ms of settle, rendered on its own and rendered behind the
whole catalog. That stability is the property the specimen never had.

The remaining difference from `pressed-after.png` — three units per channel — is
the software ripple against the patterned one. A real device draws the patterned
ripple; this host draws either the software one or, as
`pressed-hardware-unsettled.png` shows, none at all.

`WearFocusedPressPixelTest` pins all three fills apart, and
`PressedRippleSoftwarePathTest` in `:renderer-android` pins the hidden platform
method the workaround leans on. See
[DESIGN_CATALOGS.md](../../DESIGN_CATALOGS.md) for the pressed/focused capture
contract across the three render hosts.
