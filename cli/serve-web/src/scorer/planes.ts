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
// What the answer is a fraction OF is the other half of the metric, and getting it wrong is what
// issue #4290 reported: the cost used to be averaged over every pixel of the canvas, so a pair of
// watch screens that agreed on nothing but their black background still scored 93%. Empty backdrop
// is not evidence of agreement — two frames that share it share nothing anybody drew. So the
// average is taken over the pixels that carry CONTENT: where either frame has detail, plus wherever
// the two actually disagree. A component that lost half its marks now reads as having lost half its
// marks, whatever the size of the canvas it was rendered onto.
//
// No DOM here. Everything below is Float32Array in, number out, which is why it can be tested
// exhaustively without a browser — the canvas plumbing that produces the planes lives in
// `frames.ts`.

import {
    CONTENT_DILATION,
    EDGE_GRADIENT_THRESHOLD,
    EDGE_POSITION_COST,
    EDGE_SEARCH_RADIUS,
    FULL_DIFFERENCE_DELTA,
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
 * Where the two frames have something to say — the union of their detail, widened by
 * {@link CONTENT_DILATION}.
 *
 * This is the denominator's backbone. A flat region that both frames agree on carries no detail and
 * belongs to neither: it is the sheet the component was rendered onto, not evidence that the
 * component matches. What it must NOT do is exclude a region the two frames disagree about — a fill
 * that changed tone across a whole card has no edge of its own — so {@link scorePlanes} adds every
 * disagreeing pixel to this before dividing.
 */
export function contentMask(
    referenceEdges: Uint8Array,
    candidateEdges: Uint8Array,
    width: number,
    height: number,
): Uint8Array {
    const mask = new Uint8Array(width * height);
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const index = y * width + x;
            if (!referenceEdges[index] && !candidateEdges[index]) continue;
            for (let oy = -CONTENT_DILATION; oy <= CONTENT_DILATION; oy++) {
                const ny = y + oy;
                if (ny < 0 || ny >= height) continue;
                for (let ox = -CONTENT_DILATION; ox <= CONTENT_DILATION; ox++) {
                    const nx = x + ox;
                    if (nx < 0 || nx >= width) continue;
                    mask[ny * width + nx] = 1;
                }
            }
        }
    }
    return mask;
}

/**
 * What one pixel's luminance gap costs, 0–1.
 *
 * Free within {@link LUMA_TOLERANCE} — the two rasterisers disagree slightly about every shared
 * edge, and accumulating that would turn "these match" into a percentage that drifts with image
 * size. Full price from {@link FULL_DIFFERENCE_DELTA} up, so a mark that is simply not there costs
 * a whole pixel rather than the fraction of 255 its own tone happens to occupy.
 */
export function pixelCost(delta: number): number {
    const span = FULL_DIFFERENCE_DELTA - LUMA_TOLERANCE;
    return Math.min(1, Math.max(0, delta - LUMA_TOLERANCE) / span);
}

/**
 * One direction of the search: what each pixel of `source` costs against `target`, 0–1 per pixel.
 *
 * `yieldTo` is awaited every eighth row. A full catalog performs dozens of comparisons and a dense
 * edge mask is a real amount of work; without the yield the page stops painting and stops accepting
 * input for the duration.
 */
export async function directedCosts(
    source: Plane,
    target: Plane,
    sourceEdges: Uint8Array,
    targetEdges: Uint8Array,
    width: number,
    height: number,
    yieldTo: () => Promise<void>,
): Promise<Float32Array> {
    const costs = new Float32Array(width * height);
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
            costs[index] = pixelCost(best);
        }
        if (y % 8 === 7) await yieldTo();
    }
    return costs;
}

/** The mean cost of one direction, over the whole plane — the shape the tests reason in. */
export function meanCost(costs: Float32Array): number {
    let total = 0;
    for (let i = 0; i < costs.length; i++) total += costs[i];
    return total / costs.length;
}

/** One macrotask, so the browser can paint and accept input between chunks of the scan. */
export function yieldScorer(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * The structural match of two luminance planes, 0–100.
 *
 * The share of the two frames' CONTENT that agrees: their cost, summed both ways round, over the
 * pixels either frame drew on or disagrees about. Two blank planes have no content and no
 * disagreement, so they are a match by definition rather than a division by zero.
 */
export async function scorePlanes(
    reference: Plane,
    candidate: Plane,
    width: number,
    height: number,
    yieldTo: () => Promise<void> = yieldScorer,
): Promise<number> {
    const referenceEdges = edgeMask(reference, width, height);
    const candidateEdges = edgeMask(candidate, width, height);
    const forwards = await directedCosts(
        reference,
        candidate,
        referenceEdges,
        candidateEdges,
        width,
        height,
        yieldTo,
    );
    const backwards = await directedCosts(
        candidate,
        reference,
        candidateEdges,
        referenceEdges,
        width,
        height,
        yieldTo,
    );
    const content = contentMask(referenceEdges, candidateEdges, width, height);
    let cost = 0;
    let measured = 0;
    for (let index = 0; index < forwards.length; index++) {
        const pixel = (forwards[index] + backwards[index]) / 2;
        cost += pixel;
        if (content[index] || pixel > 0) measured++;
    }
    if (measured === 0) return 100;
    return Math.max(0, Math.min(100, (1 - cost / measured) * 100));
}
