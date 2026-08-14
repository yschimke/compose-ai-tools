# parity-comparison-annotations

The focused design comparison (`/{system}/compare/{previewId}`) with every annotation layer switched
on, captured by the preview-harness's `serve-reference-compare` **`annotated`** shot
(`pages-snapshot.spec.mjs`, which ticks the toggles because the layers default to off).

- `before.*.png` — two toggles, `Layout` and `Typography`. The ACTUAL panel's legend carries one row.
  A `theme` annotation was already accepted by `ServeAnnotationStore` and already had a box and a
  legend row built for it by `format-compare.js` — but the page offered no toggle for that kind and
  `serve.css` gated every `.cp-annotation` on `data-annotate-layout` / `data-annotate-typography`
  only, so the layer was unconditionally invisible. Nothing was broken on screen; the layer simply
  could not be reached.
- `after.*.png` — a third `Theme` toggle, its own hue (teal, against the layout redline's pink and
  the type spec's blue so all three read apart when several are on), the box drawn over the ACTUAL
  panel, and the resolved-container spec as a second legend row: `fill #FF6750A4 · radius 20.0dp ·
  border 1.0dp #FF79747E`.

Both are the `light` and `dark` captures, since the new hue has to hold in both.

The pair is committed rather than left to the CI visual-diff bot because the fixture gained an
annotation at the same time as the page gained the control: the bot's diff would show the new box
and the new legend row, but not that the box was previously *undrawable*, which is the thing the
change fixes. Regenerate with:

```
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
cd vscode-extension && npm run harness:snapshot
```
