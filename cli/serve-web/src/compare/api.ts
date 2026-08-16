// `window.ComposePreviewCompare` — the comparison primitives `format-compare.js` publishes.
//
// One declaration, in one place, because several components reach for it: `<cp-parity-scores>` and
// `<cp-compare-wall>` score published pairs by URL, `<cp-spec-compare>` normalises a pair once and
// then diffs, paints and scores the SAME decoded frames, and `<cp-reference-compare>` does all three
// over one pair. Declared separately they were two `Window` augmentations of the same property,
// which TypeScript rejects outright — and would have let the two drift on what the global is even
// shaped like.
//
// The shape is now taken FROM the implementation rather than restated beside it. `scorer/api.ts` is
// what `format-compare.js` is built from, so a rename or a changed signature there is a type error
// at every call site instead of a global that silently answers `undefined` at runtime. Keeping this
// as a separate module is still worth it: what a *consumer* needs is the accessor and the type, and
// importing the accessor must not pull the scorer's several hundred lines into the components
// bundle — the two are different script tags on purpose.

import type {
    diffCanvases,
    loadImage,
    normaliseImageUrls,
    scoreCanvas,
    scoreImageUrls,
    scoreImages,
    scoreSvgUrls,
} from "../scorer/api.js";

export type { Measurement, NormalisedPair } from "../scorer/api.js";

export interface CompareApi {
    /** Decode one URL into an `<img>`, same-origin, without scoring it. */
    loadImage: typeof loadImage;
    /** Score a baked PNG against an SVG of the SAME render. */
    scoreSvgUrls: typeof scoreSvgUrls;
    /** Score a baked PNG against a canvas something else has already drawn. */
    scoreCanvas: typeof scoreCanvas;
    /** Fetch, decode and score a pair by URL. */
    scoreImageUrls: typeof scoreImageUrls;
    /** Score two ALREADY-decoded frames — see `<cp-spec-compare>` on why that matters. */
    scoreImages: typeof scoreImages;
    /** Fetch and decode a pair into one pixel space, without scoring it. */
    normaliseImageUrls: typeof normaliseImageUrls;
    /** Paint the magenta delta map into `into`; returns how many pixels differ. */
    diffCanvases: typeof diffCanvases;
}

declare global {
    interface Window {
        ComposePreviewCompare?: CompareApi;
    }
}

/** The page's comparison handle, or `null` on a surface that never loaded `format-compare.js`. */
export function compareApi(): CompareApi | null {
    return window.ComposePreviewCompare ?? null;
}
