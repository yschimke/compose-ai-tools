// The magenta delta map, as arithmetic over two RGBA buffers.
//
// A pixel is "changed" once any channel — alpha included, so a mark appearing over transparency
// counts — differs by more than the tolerance, which is PNG round-tripping and resampling noise. The
// mark grows more opaque with the size of the delta, so a wholesale colour swap reads louder than a
// one-pixel edge shift.
//
// Only meaningful over frames that have already been normalised onto one size and origin — see
// `contentBox.ts`. Diffing raw frames reports the offset between them, on every pixel, forever.

import { DIFF_CHANNEL_TOLERANCE } from "./tuning.js";

/** The delta map's ink. Magenta because nothing either rasteriser draws is this colour. */
export const DIFF_INK = [229, 46, 115] as const;

/** Floor of the mark's alpha, so a barely-changed pixel is still visible. */
export const DIFF_ALPHA_BASE = 96;

export interface DeltaMap {
    /** RGBA for the map: the ink where a pixel moved, fully transparent everywhere else. */
    data: Uint8ClampedArray;
    changed: number;
}

export function deltaMap(
    reference: ArrayLike<number>,
    candidate: ArrayLike<number>,
    into: Uint8ClampedArray,
): DeltaMap {
    let changed = 0;
    for (let i = 0; i < reference.length; i += 4) {
        const delta = Math.max(
            Math.abs(reference[i] - candidate[i]),
            Math.abs(reference[i + 1] - candidate[i + 1]),
            Math.abs(reference[i + 2] - candidate[i + 2]),
            Math.abs(reference[i + 3] - candidate[i + 3]),
        );
        if (delta > DIFF_CHANNEL_TOLERANCE) {
            changed++;
            into[i] = DIFF_INK[0];
            into[i + 1] = DIFF_INK[1];
            into[i + 2] = DIFF_INK[2];
            into[i + 3] = Math.min(255, DIFF_ALPHA_BASE + delta);
        }
    }
    return { data: into, changed };
}
