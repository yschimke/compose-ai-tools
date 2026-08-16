# Preview fidelity — before / after

Rendered evidence for the sample-fidelity issue sweep (#3671, #3672, #3674,
#3675, #3676). Every "before" here was rendered from `origin/main` in a
pristine worktree; every "after" from the branch, through the same
`composePreviewRenderAll` pipeline on the same container.

| File | What it shows |
|---|---|
| `permission-granted-before.png` | The `Camera permission — granted` capture as `main` published it: the **denied** branch. Byte-identical to `permission-denied.png`. |
| `permission-granted-after.png` | The same preview with `@PermissionPreview(grants = ["android.permission.CAMERA=granted"])`. The granted branch, reached through the unmodified `ContextCompat.checkSelfPermission(...)` call. |
| `permission-denied.png` | The denied variant, unchanged — kept here so the pair can be compared. |
| `m3-focus-ring-before.png` | `FilledButtonFocused` driven by a hand-emitted `FocusInteraction.Focus`: a hairline outline. |
| `m3-focus-ring-after.png` | The same sticker under `@FocusedPreview(indices = [0])` — real focus traversal raises the full M3 inset focus ring. |
| `wear-focused-before.png` / `wear-focused-after.png` | Wear `Button/Filled` `focused`. Real focus produces a stronger state layer (`#E2D5F8` → `#D4C8EC`) than the forged interaction did. |
| `wear-pressed-unchanged.png` | Historical Wear `Button/Filled` `pressed` evidence from before the focused-key fallback fixed the renderer path. |
| `wear-pressed-after.png` | Wear `Button/Filled` `pressed` through `@FocusedPreview(pressed = true)` after the focused-key fallback. Its container is `#C2B5DB`, distinct from focus-only `#D4C8EC`. |
| `widget-clamped-before.png` / `widget-clamped-after.png` | `LauncherWidgetClampedPreview` picking up the production condition string after the RemoteViews factory was shared. |
| `placeholder-override-driven-new.png` | New preview: `PlaceholderCardOverrideDriven`, the preview-only wrapper that keeps the live `placeholderActive` override lane exercised. |
| `cmp-focused-before.png` / `cmp-focused-after.png` | CMP/desktop `Button/Filled` `keyboard-focus`. Before: the forged `FocusInteraction.Focus` rendered **nothing** — `#6750A4`, the resting container colour. After, under `@FocusedPreview(indices = [0])`: `#7661AD`, the real focus state layer. |
| `cmp-pressed-before.png` / `cmp-pressed-after.png` | CMP/desktop `Button/Filled` `pressed`. Before: also `#6750A4` — indistinguishable from resting. After, under `@FocusedPreview(indices = [0], pressed = true)`: `#8471B5`, the pressed state layer raised by a real pointer down. |
| `cmp-resting-reference.png` | The plain `Button/Filled` sticker, unchanged, at `#6750A4` — the control that makes the two "before" captures legible as *no state at all*. |

## Why the Wear `pressed` sticker originally needed a fallback

`@FocusedPreview(indices = [0], pressed = true)` was tried on it and measured.
The resulting capture was pixel-identical to the focus-only one across the whole
button container (both `#D4C8EC`), differing **only** in the label glyphs — the
indirect-pointer Press does not reach Wear M3's `Button` interaction source, so
the capture documented *focus*, not press.

Wear M3's `Button` uses `combinedClickable`, which does not consume the
indirect-pointer event. The renderer now checks whether the indirect event was
handled and, when it was not, holds a focus-targeted `DPAD_CENTER` key-down.
`combinedClickable` consumes that ordinary Wear input channel and emits the
component's real pressed interaction, so the catalog no longer needs its held
`MutableInteractionSource` stopgap.

The Android M3 focus ring (`m3-focus-ring-after.png`) is the counter-example
showing the mechanism does work where the component cooperates.

## The CMP/desktop sheet, converted afterwards

The desktop half of #3672 was left out of the sweep above because
`renderers/desktop` had no focus or press dispatch at all. It does now — the
renderer drives `@FocusedPreview` through the same connector-side walk under
`runSkikoComposeUiTest`, and `pressed = true` dispatches an ordinary pointer down
onto the focused element (desktop has no indirect-pointer channel, so the press
is hit-tested like a real click).

The `cmp-*` rows above are that conversion, and they are the sharpest evidence in
this directory: on `main` the CMP catalog's `pressed` and `keyboard-focus`
stickers were **pixel-identical to a resting button** (`#6750A4` in all three).
The forged interaction was not merely a weak approximation of the state — it
produced no state at all, and the sheet had been publishing two labelled state
stickers that showed none.
