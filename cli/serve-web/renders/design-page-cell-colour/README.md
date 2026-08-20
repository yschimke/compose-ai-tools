# Design page: a node we reached vs a node we built

Before/after for the fifth legend colour on `/{system}/pages/{id}` — the mark that separates a
component with a `@Preview` of its own from a kit variant reached by seeding a knob on a
neighbouring one (`PageNode.cell`).

| | |
| --- | --- |
| `before.png` | six identical blue outlines, a four-entry legend. Every mapped node reads the same. |
| `after.png` | two blue, four purple, a five-entry legend. Two components were written; four are override cells. |

Both are captured from a **real running server** rather than a mock, because the colour is decided
in two places that only meet at runtime: the `data-cp-cell` attribute `ServeWeb` emits, and the
`serve.css` rule that reads it.

## Reproducing

`fixture/` is a minimal preview bundle — a specimen sheet with eight `data-node-id` nodes, six
mapped (four of them to `_VARIANT_` captures) and two unmapped. It exists so this capture needs no
catalog, no Figma token and no render pass.

```sh
./gradlew :cli:installDist
cli/build/install/*/bin/compose-preview serve \
  --bundles cli/serve-web/renders/design-page-cell-colour --public --port 8791

npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs http://127.0.0.1:8791/fixture/pages/shape after.png
```

`shoot.mjs` selects the **Design spec** lane and turns the outline layer on before shooting: that is
the state the legend exists for, and the reading the coverage question is asked in. In the "Our
renders" lane the catalog's own pixels cover the sheet and the outlines sit behind them.

To capture a `before.png` for a change to this surface, stash it, re-run `:cli:installDist`, and
shoot again on a second port — the fixture is unchanged between the two, so the only difference in
the image is the change under review.
