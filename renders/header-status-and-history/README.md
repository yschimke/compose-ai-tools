# Header status, the history menu, and a focus ring with room to land

Three changes to the serve chrome, each captured from the preview-harness at
1024px, light theme. Every "before" is `origin/main` at `60b34b2` with only the
changed assets reverted, so the pair differs by this change and nothing else —
verified by pixel count rather than by eye (`focus-ring` 6,833 differing pixels,
`header` 4,297, `history` 4,196).

## The render-server count moved into the Status link

| file | what it is |
| --- | --- |
| `header-before.png` | the count as a free-floating `● 2` pill in the centre column, and `Status` a separate word on the right — the same question answered twice |
| `header-after.png` | one control: `Status ● 2` |

The header drops from a three-column grid to two. A side effect worth having:
the catalog name stops ellipsising (`compos…` → `compose-m3`), because the lead
is no longer squeezed by a centre column reserving space for the badge.

## The render history is a menu, not a strip

| file | what it is |
| --- | --- |
| `history-before.png` | `main`: no history at all. `viewer-history.js` anchored to `.cp-viewer-bar`, which #3893 stopped emitting, so it returned at its second line |
| `history-after.png` | `History 3 versions unstable ▾` — one control in the toggle row beside Theme and Overrides. Nothing between the title and the render |
| `history-menu-open.png` | the menu open: the dated versions, `current` marked the way the revision menu marks a pin, and what the list covers |

Restoring the old **strip** is what this deliberately does not do. It was a row
of dated chips under the viewer bar — the pattern #3858 had just removed from
the control beside it, replacing the revision chip wall with a dropdown. Render
history is the same kind of list, so it gets the same shape.

Being a menu is also why there is no phone question: closed, it costs one
control in a row that already exists, at every width.

## A focus ring with somewhere to land

| file | what it is |
| --- | --- |
| `focus-ring-before.png` | the sidebar filter focused: the ring is clipped on the top and both sides, leaving only its bottom edge |
| `focus-ring-after.png` | the same field, the whole ring drawn |

`.cp-catalog-menu` scrolls above 960px with no padding, and a scroll container
clips the axis it was *not* given as well as the one it was — `overflow-y: auto`
computes `overflow-x: auto`, not `visible`. The field is the column's first row
and full-width, so three of its four edges went. Reserved as padding, taken back
off the column's own box.

Nothing in the suite focused anything in that column, which is why this survived
every capture: a ring that is never drawn cannot be missing from a baseline.
`serve-landing-sections` · `filter-focus` draws it now.

```
cd vscode-extension
npm run harness:snapshot     # 158 passed
```
