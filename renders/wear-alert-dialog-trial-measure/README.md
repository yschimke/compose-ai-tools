# The dialog's trial measure is not a second title

[yschimke/wear-m3-catalog#77](https://github.com/yschimke/wear-m3-catalog/issues/77) — "what is the
second typography element here?" — asked of the Wear catalog's
[`alertdialog__ideal__default__192dp`](https://preview.coo.ee/wear-m3-catalog/p/alertdialog__ideal__default__192dp)
preview. Ticking **Typography** drew two boxes over a dialog with one line of text: the title, and a
second box of the same size stacked in the frame's top-left corner.

The second box is real data, honestly drawn. Wear's `AlertDialogContent` decides between a scrolling
and a fixed layout by **subcomposing a whole trial copy of the dialog** and measuring its
unconstrained height (`DynamicScrollableOrFixedLayout`). That copy is measured and never placed, but
it stays in the semantics tree — and an unplaced node has no position, so its `boundsInRoot` reads as
`0,0,248,74`. Nothing on the wire said "this was never placed", so the annotation walk read those
bounds as the frame's top-left corner rather than as nowhere. `layout/inspector` has carried a
`placed` flag since its first version and its walk already skips unplaced nodes for exactly this
reason; `compose/semantics` — the tree that owns typography — did not.

| file | what it is |
| --- | --- |
| `typography-overlay-before.png` | before: two typography boxes — ① the drawn title, ② the trial measure's copy of it, in the corner |
| `typography-overlay-after.png` | after: one box, the title the dialog actually draws |

## How these were made

The frame is the published render of that preview
(`preview.coo.ee/wear-m3-catalog/render/alertdialog__ideal__default__192dp.png`, rendered by
compose-ai-tools 1.28.0). The boxes are the `typography` annotations `ServeDesignAnnotations`
returns for it, drawn at their reported bounds:

- **before** — the preview's own `compose-semantics.json` out of its published bundle
  (`/wear-m3-catalog/bundle/…`), a v14 payload with no `placed` field, so every node reads as
  placed. Two boxes: `(68,101,248×74)` and `(0,0,248×74)`. This is byte-for-byte what the deployed
  server's `…​.annotations` endpoint serves today.
- **after** — the same payload with the trial subtree marked `placed = false`, which is what the
  producer now writes. One box: `(68,101,248×74)`.

That the renderer really does mark that subtree unplaced is pinned by
`WearAlertDialogTrialMeasureTest`, which composes the same `AlertDialogContent` against
wear-compose 1.7.0-beta01 and finds two copies of the title in the tree with exactly one of them
placed.

```
./gradlew :cli:test :data-layoutinspector-core:test          # green
./gradlew :renderer-android:testDebugUnitTest \
  --tests "*ComposeSemanticsCoreFieldsTest*" \
  --tests "*WearAlertDialogTrialMeasureTest*"                # green
node scripts/validate-report-schemas.mjs                     # 12 payloads across 13 schemas
```
