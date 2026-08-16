// The comparison metric itself, as arithmetic over luminance planes.
//
// Not SSIM and not a pixel diff. Both are wrong for this question: a design reference and a Compose
// render are drawn by two different rasterisers, so every vector edge lands on different sub-pixels
// and a per-pixel comparison reports a mismatch on a pair that is visually identical. What this does
// instead is let an EDGE pixel look for its partner within a small neighbourhood, charging a
// positional cost for how far it had to go — so a one-pixel raster shift is nearly free while a mark
// that is genuinely absent stays expensive.
//
// The search runs in BOTH directions. One way round, an extra mark in the candidate can hide beside
// a matching one in the reference and cost nothing at all.
//
// No DOM here. Everything below is Float32Array in, number out, which is why it can be tested
// exhaustively without a browser — the canvas plumbing that produces the planes lives in
// `frames.ts`.

import {
    EDGE_GRADIENT_THRESHOLD,
    EDGE_POSITION_COST,
    EDGE_SEARCH_RADIUS,
    LUMA_TOLERANCE,
} from "./tuning.js";

/** Any luminance plane — a `Float32Array` in the browser, a plain array in a test. */
export type Plane = Float32Array | number[];

/**
 * Which pixels sit on an edge.
 *
 * The 4-neighbour maximum absolute gradient, clamped at the borders so an edge pixel compares
 * against itself rather than wrapping. Only these pixels get to search for a displaced partner:
 * letting a flat interior pixel roam would let a large uniform region absorb any change inside it.
 */
export function edgeMask(
    plane: Plane,
    width: number,
    height: number,
): Uint8Array {
    const mask = new Uint8Array(width * height);
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const index = y * width + x;
            const value = plane[index];
            const gradient = Math.max(
                Math.abs(value - plane[y * width + Math.max(0, x - 1)]),
                Math.abs(value - plane[y * width + Math.min(width - 1, x + 1)]),
                Math.abs(value - plane[Math.max(0, y - 1) * width + x]),
                Math.abs(
                    value - plane[Math.min(height - 1, y + 1) * width + x],
                ),
            );
            if (gradient >= EDGE_GRADIENT_THRESHOLD) mask[index] = 1;
        }
    }
    return mask;
}

/**
 * One direction of the search: how much of `source` has no home in `target`.
 *
 * Answers the mean per-pixel cost, 0–1. A difference within {@link LUMA_TOLERANCE} is free, so the
 * two rasterisers' disagreement about a shared edge does not accumulate into a finding.
 *
 * `yieldTo` is awaited every eighth row. A full catalog performs dozens of comparisons and a dense
 * edge mask is a real amount of work; without the yield the page stops painting and stops accepting
 * input for the duration.
 */
export async function directedMismatch(
    source: Plane,
    target: Plane,
    sourceEdges: Uint8Array,
    targetEdges: Uint8Array,
    width: number,
    height: number,
    yieldTo: () => Promise<void>,
): Promise<number> {
    let total = 0;
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const index = y * width + x;
            const value = source[index];
            let best = Math.abs(value - target[index]);
            if (sourceEdges[index] && best > LUMA_TOLERANCE) {
                for (
                    let oy = -EDGE_SEARCH_RADIUS;
                    oy <= EDGE_SEARCH_RADIUS;
                    oy++
                ) {
                    const yy = y + oy;
                    if (yy < 0 || yy >= height) continue;
                    for (
                        let ox = -EDGE_SEARCH_RADIUS;
                        ox <= EDGE_SEARCH_RADIUS;
                        ox++
                    ) {
                        const xx = x + ox;
                        if (xx < 0 || xx >= width) continue;
                        const targetIndex = yy * width + xx;
                        if (!targetEdges[targetIndex]) continue;
                        const displaced =
                            Math.abs(value - target[targetIndex]) +
                            Math.sqrt(ox * ox + oy * oy) * EDGE_POSITION_COST;
                        best = Math.min(best, displaced);
                    }
                }
            }
            total +=
                Math.max(0, best - LUMA_TOLERANCE) / (255 - LUMA_TOLERANCE);
        }
        if (y % 8 === 7) await yieldTo();
    }
    return total / (width * height);
}

/** One macrotask, so the browser can paint and accept input between chunks of the scan. */
export function yieldScorer(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

/** The structural match of two luminance planes, 0–100. */
export async function scorePlanes(
    reference: Plane,
    candidate: Plane,
    width: number,
    height: number,
    yieldTo: () => Promise<void> = yieldScorer,
): Promise<number> {
    const referenceEdges = edgeMask(reference, width, height);
    const candidateEdges = edgeMask(candidate, width, height);
    const mismatch =
        ((await directedMismatch(
            reference,
            candidate,
            referenceEdges,
            candidateEdges,
            width,
            height,
            yieldTo,
        )) +
            (await directedMismatch(
                candidate,
                reference,
                candidateEdges,
                referenceEdges,
                width,
                height,
                yieldTo,
            ))) /
        2;
    return Math.max(0, Math.min(100, (1 - mismatch) * 100));
}
