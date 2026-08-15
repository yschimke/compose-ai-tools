# Viewer disclosures

Committed evidence for the preview viewer's four **disclosures** — the component list, the
state/variant axes, the theme chips and the overrides drawer — and the title-row toggle group that
operates them.

The viewer used to spend most of the fold restating things: a component's whole state axis as three
wrapped rows of chips (the published m3-catalog bakes ~30 for `iconbutton-outlined`), eight
ellipsised theme chips, and a 240px component column nailed open on every desktop, all above the
render the page exists for. Each is now foldable, and past a threshold the wide ones arrive folded —
server-side, so there is no expanded flash before the drawer script runs. A closed toggle still
names the value its row carried (`STATE · Default`, `THEME · Day`), which is what makes folding by
default safe.

The component's renders are now a **subtree of the catalog tree**, filtered to the one component
on screen and built from the same `primaryVariants` the landing tree uses — one definition of what
a component's renders are, drawn twice. The markup is in
[`ServeWeb.viewerPage`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt)
(`componentSubtreeHtml`, `appendComponentRow`, `disclosureToggleHtml`, `AXIS_ROWS_INLINE`,
`THEME_CHIPS_INLINE`), the fold behaviour and its
per-visitor memory in [`viewer-drawers.js`](../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/viewer-drawers.js).

Captured at 1400×1000 (and 390×760 for the phone shot) from the committed
`serve-viewer-axes-folded` page fixture, which the preview-harness screenshots and the CI
visual-diff bot compares on every PR — so this surface stays covered without anyone remembering to
shoot it again.

| file | what it is |
| --- | --- |
| `theme-overflow-before.png` | the `serve-viewer-theme-overflow` fixture **before**: eight theme chips on a row of their own, four of them ellipsised, the drawer toggles at either end of the bar |
| `theme-overflow-after.png` | the same page **after**: the chips fold behind `THEME · Night` on the title row, and the row they occupied is back |
| `viewer-folded.png` | the resting state on a wide catalog: ten states and five themes folded behind `STATE · Default` / `THEME · Day`, the render starting where three chip rows used to |
| `viewer-axes-open.png` | both folds opened on the real 22-render shape — the component's **subtree** (the catalog tree filtered to this component) flowing into columns rather than a 550px ladder, the default folded into the component row and marked current |
| `viewer-nav-closed.png` | the component list collapsed **on a desktop**, which it could not be before: the 240px column goes back to the stage |
| `cross-product-labels.png` | a component baking state × props as a matrix, entered on `pressed + RTL` with its subtree open: every row names both coordinates, so the state-reset row and the props-reset row are told apart instead of both reading "Default" |
| `viewer-mobile.png` | a phone, scrolled past the top: the title row is sticky, so all four disclosures stay one tap away over a tall preview |
