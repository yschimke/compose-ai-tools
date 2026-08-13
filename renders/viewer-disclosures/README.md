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

The markup is in [`ServeWeb.viewerPage`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt)
(`disclosureToggleHtml`, `AXIS_CHIPS_INLINE`, `THEME_CHIPS_INLINE`), the fold behaviour and its
per-visitor memory in [`viewer-drawers.js`](../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/viewer-drawers.js).

Captured at 1400×1000 (and 390×760 for the phone shot) from the committed
`serve-viewer-axes-folded` page fixture, which the preview-harness screenshots and the CI
visual-diff bot compares on every PR — so this surface stays covered without anyone remembering to
shoot it again.

| file | what it is |
| --- | --- |
| `viewer-folded.png` | the resting state on a wide catalog: ten states and five themes folded behind `STATE · Default` / `THEME · Day`, the render starting where three chip rows used to |
| `viewer-axes-open.png` | both folds opened — the state chips and the theme bar are one click away, and the toggles go tonal to say so |
| `viewer-nav-closed.png` | the component list collapsed **on a desktop**, which it could not be before: the 240px column goes back to the stage |
| `viewer-mobile.png` | a phone, scrolled past the top: the title row is sticky, so all four disclosures stay one tap away over a tall preview |
