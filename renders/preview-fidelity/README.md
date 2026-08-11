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
| `wear-pressed-unchanged.png` | Wear `Button/Filled` `pressed`, deliberately **not** converted — see below. |
| `widget-clamped-before.png` / `widget-clamped-after.png` | `LauncherWidgetClampedPreview` picking up the production condition string after the RemoteViews factory was shared. |
| `placeholder-override-driven-new.png` | New preview: `PlaceholderCardOverrideDriven`, the preview-only wrapper that keeps the live `placeholderActive` override lane exercised. |

## Why the Wear `pressed` sticker was not converted

`@FocusedPreview(indices = [0], pressed = true)` was tried on it and measured.
The resulting capture was pixel-identical to the focus-only one across the whole
button container (both `#D4C8EC`), differing **only** in the label glyphs — the
indirect-pointer Press does not reach Wear M3's `Button` interaction source, so
the capture documented *focus*, not press.

Publishing that under `state = "pressed"` would have been the same class of
defect #3676 exists to remove: a capture whose label promises a state the render
does not establish. The sticker keeps its held `MutableInteractionSource` as a
marked stopgap instead, and stays byte-identical to `main`.

The Android M3 focus ring (`m3-focus-ring-after.png`) is the counter-example
showing the mechanism does work where the component cooperates.
