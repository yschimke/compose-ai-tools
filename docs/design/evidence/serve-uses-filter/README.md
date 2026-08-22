# `uses:` — an operator, not a control

Three states of the landing filter box, captured from the committed page fixture
`vscode-extension/preview-harness/fixtures/pages/serve-landing.html` by the two
`serve-landing-uses-*` entries in `pages-snapshot.spec.mjs`, so they show exactly what the server
emits.

| File | Fixture state | What it shows |
| --- | --- | --- |
| `resting.png` | `serve-landing` | The page before anything is typed — **unchanged** by this feature. The operator adds no furniture until it is used. |
| `uses-filter.png` | `serve-landing-uses-filter` | `uses:Button` narrowing six previews to the two whose declarations call one. |
| `uses-unavailable.png` | `serve-landing-uses-unavailable` | A catalog that cannot be indexed, saying so. |

**The middle capture is the argument for the whole feature.** The two cards left standing are
*Card* and *Profile screen* — neither name contains "button", so the grid's own label-and-id filter
could not have found either, and the four it hid include `ButtonPreview`, which that filter would
have matched. Being able to ask what a preview is *made of*, rather than what it is *called*, is the
question a name search structurally cannot answer.

**The third is the argument for the `available` flag.** Take the readout away and it is
pixel-for-pixel a catalog where nothing matched. The endpoint answers `available: false` rather than
an empty list precisely so this state can say "call index unavailable" instead of reporting a zero
nobody computed.

`uses:` is Dev-mode only, so there is no Catalog-mode capture here: in that presentation the cards
carry no `data-uses-id`, the page script contains no branch of the operator, and `/api/uses` answers
404. The Catalog-mode fixture is byte-identical to before the change, which
`ServeWebFixtureTest` holds to.

The endpoint is stubbed in the capture. What matching returns is `PreviewUsageIndexTest`'s subject;
these shots are the page's half.
