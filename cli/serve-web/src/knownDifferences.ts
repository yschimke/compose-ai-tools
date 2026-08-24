// Entry point for `assets/known-differences.js` — the acceptance band, and the engine behind it.
//
// Its own bundle, emitted only by the focused comparison, for the reason `codemirror.js` and
// `viewer.js` have theirs: it is by far the heaviest thing on any page that carries it. The engine
// is the contract's whole reference implementation — the document ladder, five gates, a PNG reader
// and a scorer — and folding it into `serve-components.js` would put all of that on the catalog
// grid, the compare wall and the design pages, none of which evaluate an acceptance.
//
// It is deliberately NOT part of `format-compare.js` either, even though both are about comparing
// two frames. That file has four consumers outside this repository that load it by path and depend
// on the `window.ComposePreviewCompare` shape; growing it by an order of magnitude would charge all
// of them for a surface none of them uses.
//
// No global is published. Nothing outside the browser drives this one, so the element is the whole
// interface.

import "./components/Acceptance.js";
