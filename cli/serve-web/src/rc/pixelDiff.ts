// pixelmatch's YIQ metric, reimplemented for the browser, as arithmetic over plain arrays.
//
// This is the half of `rc-lanes.js` that most needed pinning: nine magic constants and a threshold
// scale, hand-transcribed from pixelmatch, with no test of any kind. It decides the mismatch
// percentage a reader sees next to two players, and it is the ONLY number on the page the offline
// run did not compute — so if it drifts from pixelmatch, the page quietly disagrees with the build
// while looking exactly the same.
//
// Deliberately NOT pixelmatch itself: the anti-aliasing pass is dropped, which reads a touch higher
// on text-heavy previews. That is a stated trade (the status line says so), not an accident, and
// `diffPixels` is where it is visible.

/** pixelmatch's maximum YIQ difference — the scale its `threshold` option is expressed against. */
export const MAX_DELTA = 35215;

/** The default when the published manifest names no threshold; pixelmatch's own default. */
export const DEFAULT_THRESHOLD = 0.1;

/** Any RGBA buffer with its dimensions — `ImageData`, or a plain object in a test. */
export interface Pixels {
    data: Uint8ClampedArray | number[];
    width: number;
    height: number;
}

export interface DiffResult {
    /** RGBA for the diff image: flagged pixels in red, everything else a washed-out reference. */
    data: Uint8ClampedArray;
    changed: number;
    total: number;
    /** Mismatch as a percentage of the frame. */
    percent: number;
}

// The Y'IQ transform pixelmatch uses. Named rather than inlined so the three rows are checkable
// against the source they came from.
const Y = [0.29889531, 0.58662247, 0.11448223];
const I = [0.59597799, -0.2741761, -0.32180189];
const Q = [0.21147017, -0.52261711, 0.31114694];
const WEIGHT = { y: 0.5053, i: 0.299, q: 0.1957 };

const dot = (
    px: Uint8ClampedArray | number[],
    i: number,
    m: number[],
): number => px[i] * m[0] + px[i + 1] * m[1] + px[i + 2] * m[2];

/**
 * Squared perceptual distance between the two pixels at byte offset `i`.
 *
 * Alpha is ignored on purpose: both sides are published opaque PNGs of the same document, so a
 * transparency term would only ever add noise.
 */
export function yiqDelta(
    a: Uint8ClampedArray | number[],
    b: Uint8ClampedArray | number[],
    i: number,
): number {
    const dy = dot(a, i, Y) - dot(b, i, Y);
    const di = dot(a, i, I) - dot(b, i, I);
    const dq = dot(a, i, Q) - dot(b, i, Q);
    return WEIGHT.y * dy * dy + WEIGHT.i * di * di + WEIGHT.q * dq * dq;
}

/** The cutoff a pixel has to clear to count as changed, for a given pixelmatch threshold. */
export function limitFor(threshold: number): number {
    return threshold * threshold * MAX_DELTA;
}

/**
 * Diff two frames of identical size.
 *
 * The backdrop is pixelmatch's: the reference in grey at 10% opacity, so the flagged pixels read
 * against something recognisable rather than against white.
 */
export function diffPixels(
    reference: Pixels,
    lane: Pixels,
    threshold: number,
): DiffResult {
    const { width, height } = reference;
    const out = new Uint8ClampedArray(width * height * 4);
    const limit = limitFor(threshold);
    const ref = reference.data;
    let changed = 0;
    for (let i = 0; i < width * height * 4; i += 4) {
        if (yiqDelta(ref, lane.data, i) > limit) {
            out[i] = 255;
            out[i + 1] = 60;
            out[i + 2] = 60;
            changed++;
        } else {
            const grey = 255 + (dot(ref, i, Y) - 255) * 0.1;
            out[i] = grey;
            out[i + 1] = grey;
            out[i + 2] = grey;
        }
        out[i + 3] = 255;
    }
    const total = width * height;
    return { data: out, changed, total, percent: (100 * changed) / total };
}

/** Whether two frames can be compared at all. */
export function sameSize(a: Pixels, b: Pixels): boolean {
    return a.width === b.width && a.height === b.height;
}
