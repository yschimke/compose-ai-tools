# parity-element-selection

The focused design comparison (`/{system}/compare/{previewId}`) learning to report *one element*
rather than "somewhere in this picture". Captured by the preview-harness's `serve-reference-compare`
shots (`pages-snapshot.spec.mjs`).

- `before.*.png` — the page as it shipped: three panels, an `Annotations` row for the
  producer-authored redline, the score line, and the report link. Two things are missing and neither
  is visible as an absence. The **Actual** panel carries no layer of its own — the authored redline
  a bundle publishes in `annotations/index.json` overwhelmingly annotates the *reference*, so on most
  catalogs the render side of the comparison has nothing drawn on it at all. And there is no way to
  say which part of the render is wrong, so every report filed from here names the whole preview.
- `after.*.png` — the `element-selected` shot, which is the default page plus two clicks a reporter
  would make.
  - A **Render semantics** row, and its layers drawn over the **Actual** panel only. These are
    projected from the render's own semantics tree, which the viewer has drawn for a long time and
    this page could not: `<cp-inspect-layers>` reached straight for the viewer's ids. It takes a
    host descriptor now. They are on the Actual panel and nowhere else on purpose — the reference is
    an imported raster with no semantics tree behind it, so the same boxes over it would be a
    picture of the wrong thing that looks equally authoritative.
  - A **Report** row: a tag picker fed from the published element index, a drag-a-region button, and
    a status line stating exactly what the prefilled issue will name — here
    `Reporting “follow-button” · 90×36 at 20,132 in render pixels`. That sentence is the visible half;
    the load-bearing half is in the form's hidden body, where the locator block now carries
    `element: "follow-button"` and
    `bounds: {"height":36,"space":"render-pixels","width":90,"x":20,"y":132}` — the two fields
    `compose-parity-locator/v1` reserved and nothing filled.

Both are the `light` and `dark` captures, because the layers and the controls have to hold in both.

## What the pictures cannot show, and where it is asserted instead

Three of this change's rules are invisible in a screenshot, so the harness asserts them in the same
state that takes the shot rather than leaving them to the eye:

- **A duplicated tag cannot be an element selector.** The picker lists `list-row` with its count and
  disables it — `count > 1` is not an identity, and silently resolving one of several is the failure
  the field exists to catch. Visible in the shot only if you open the `<select>`.
- **A dragged region is converted into render pixels before it is recorded.** `v1` accepts no other
  plane, because a display-plane rectangle makes an element that never moved report as *moved*. The
  frame here is shown at half its natural width, so the numbers in the status line are the converted
  ones.
- **Tag selection is gated on the index describing the frame on screen.** The published index is
  measured in CI over the baked render, so an override-bearing or pinned frame is a different render.
  `serve-reference-compare-pinned.*.png` (captured by the same spec) is that case: no picker, the
  reason said out loud, and the drag still offered.

Regenerate with:

```
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
npm --prefix preview-server/preview-harness run harness:pages
```

`before.*.png` is the same shot taken at the merge base — the page has no `element-selected` state
before this change, so the pair compares the default page then against the selected page now.
