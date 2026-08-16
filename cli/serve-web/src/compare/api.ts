// `window.ComposePreviewCompare` — the comparison primitives `format-compare.js` publishes.
//
// One declaration, in one place, because two components now reach for it: `<cp-parity-scores>`
// scores published pairs by URL, `<cp-spec-compare>` normalises a pair once and then diffs, paints
// and scores the SAME decoded frames. Declared separately they were two `Window` augmentations of
// the same property, which TypeScript rejects outright — and would have let the two drift on what
// the global is even shaped like.
//
// NOT a port of `format-compare.js`. That file still owns the metric, the normalisation and the
// delta map; this is the typed handle onto it. Porting it is its own change — at 1,700 lines it
// wants breaking into pure modules rather than moving whole.

/** A structural comparison of two frames. */
export interface Measurement {
    /** Structural match, 0–100. */
    percent: number;
    /** Proportion drift between the two content boxes, in percent. Absent on some lanes. */
    geometry?: number;
}

/** Two frames normalised into one shared pixel space, so every surface drawn from them lines up. */
export interface NormalisedPair {
    reference: CanvasImageSource & { width: number; height: number };
    candidate: CanvasImageSource & { width: number; height: number };
    /** The decoded sources, for scoring without re-requesting the URLs. */
    images: [unknown, unknown];
    width: number;
    height: number;
}

export interface CompareApi {
    /** Fetch, decode and score a pair by URL. */
    scoreImageUrls(reference: string, actual: string): Promise<Measurement>;
    /** Fetch and decode a pair into one pixel space, without scoring it. */
    normaliseImageUrls(
        reference: string,
        actual: string,
    ): Promise<NormalisedPair>;
    /** Paint the magenta delta map into `into`; returns how many pixels differ. */
    diffCanvases(
        reference: CanvasImageSource,
        candidate: CanvasImageSource,
        into: HTMLCanvasElement,
    ): number;
    /** Decode one URL into an `<img>`, same-origin, without scoring it. */
    loadImage(url: string): Promise<HTMLImageElement>;
    /**
     * Score a baked PNG against an SVG of the SAME render.
     *
     * Answers a bare percentage rather than a {@link Measurement}: the two share their geometry by
     * construction, so there is no proportion difference for it to report.
     */
    scoreSvgUrls(pngUrl: string, svgUrl: string): Promise<number>;
    /** Score a baked PNG against a canvas something else has already drawn — the RC lane's shape. */
    scoreCanvas(pngUrl: string, canvas: HTMLCanvasElement): Promise<number>;
    /** Score two ALREADY-decoded frames — see `<cp-spec-compare>` on why that matters. */
    scoreImages(
        reference: unknown,
        candidate: unknown,
    ): Promise<Required<Measurement>>;
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
