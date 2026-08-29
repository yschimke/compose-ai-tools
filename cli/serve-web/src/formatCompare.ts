// Entry point for `assets/format-compare.js` — the comparison primitives, as a global.
//
// A global rather than an import because of who calls it. Four serve surfaces reach it from
// elements in the surface component bundles, and TWO consumers live outside the browser and drive
// the built asset by path:
//
//   - `scripts/design-artifacts/design-reference-score.mjs` bakes the reference score at publish
//     time by loading THIS FILE into a headless page. One scorer, so the number on the chip and the
//     number the lane computes live cannot disagree.
//   - `scripts/compare-audit.mjs` intercepts the request for it and swaps in a local build, so a
//     scorer change can be audited against a deployed catalog without deploying anything.
//
// Both of those, plus the Chromium scorer spec and the harness's asset list, name the file. So it
// keeps its name, its path and its published shape; only its source moved. The elements go on
// reading the handle late (see `compare/api.ts`) rather than at upgrade, because nothing orders this
// tag against theirs.

import type { CompareApi } from "./compare/api.js";
import {
    diffCanvases,
    loadImage,
    normaliseImageUrls,
    scoreCanvas,
    scoreImageUrls,
    scoreImages,
    scoreSvgUrls,
} from "./scorer/api.js";
import { SCORE_VERSION } from "./scorer/tuning.js";

// Annotated rather than inferred: this is the published contract, and the annotation is what fails
// the build if a function here stops matching what every consumer is typed against.
const api: CompareApi = {
    loadImage,
    scoreSvgUrls,
    scoreCanvas,
    scoreImageUrls,
    scoreImages,
    normaliseImageUrls,
    diffCanvases,
    SCORE_VERSION,
};

window.ComposePreviewCompare = api;
