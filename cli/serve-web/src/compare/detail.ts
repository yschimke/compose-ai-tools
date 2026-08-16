// The one measurement behind the Reference / Diff / Actual detail page.
//
// Composed here from three primitives rather than called as a fourth, because the page wants the
// diff and the score of the SAME normalised frames. Diffing raw canvases only worked while both
// sides happened to be exported at identical dimensions, and when they were not the page had nothing
// to show but "dimensions differ" — least useful exactly when a visitor most wants to see where the
// two drift apart. Normalising once and then diffing and scoring that pair means the delta map and
// the percentage are describing the same pixels.

import type { CompareApi } from "./api.js";

export interface ComparisonResult {
    /** Structural match, 0–100. */
    score: number;
    /** Pixels the delta map painted. */
    changed: number;
    /** Pixels in the normalised frame — the denominator for {@link changed}. */
    pixels: number;
    /** Proportion drift between the two content boxes, in percent. */
    geometry: number;
}

export async function compareImageUrls(
    api: CompareApi,
    referenceUrl: string,
    actualUrl: string,
    canvas: HTMLCanvasElement,
): Promise<ComparisonResult> {
    const frames = await api.normaliseImageUrls(referenceUrl, actualUrl);
    const changed = api.diffCanvases(
        frames.reference,
        frames.candidate,
        canvas,
    );
    const score = await api.scoreImages(frames.images[0], frames.images[1]);
    return {
        score: score.percent,
        geometry: score.geometry,
        changed,
        pixels: frames.width * frames.height,
    };
}
