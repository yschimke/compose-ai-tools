// Fitting OUR drawn pixels onto the design's drawn box.
//
// The two halves of the swap are boxes of different kinds, and that is the whole of this module. A
// design node's box is measured off the export, so it is the tight bounds of the shape Figma drew.
// A render is a fixed canvas — the preview's own size — with the component sitting inside it and
// transparent margin around.
//
// Dropping one into the other with `object-fit: contain` fits CANVAS to INK, so the margin is spent
// inside the design's slot and our component comes out smaller: by however much of its canvas it
// does not fill, and by however far the two boxes differ in aspect. On this catalog's Shape page
// that ran from 4% (a circle, which nearly fills its canvas) to 42% (a semicircle, whose canvas is
// square and whose slot is 1.6:1) — read as "everything scales when you flip the lane", which is
// precisely the reading the two lanes exist to make impossible.
//
// So the render's ink box is measured too, and INK is fitted to INK. Uniformly and centred, never
// stretched: the aspect our render actually has is a finding about our code, and a fit that squashed
// it to the design's box would report every component as the right shape.

/** A pixel alpha at or above this counts as drawn. Mirrors `ServeThumbCrop.pngAlphaBounds`. */
export const INK_ALPHA = 16;

/**
 * The scan is capped rather than run at native size. A render can be a full phone screen, and the
 * answer is a box: sampling it at 512 on the long side costs one `drawImage` and bounds the work per
 * node, while the worst error it can introduce is a couple of source pixels on an edge.
 */
export const MAX_INK_SIDE = 512;

export interface InkBounds {
    x: number;
    y: number;
    width: number;
    height: number;
    imageWidth: number;
    imageHeight: number;
}

export interface Rect {
    width: number;
    height: number;
}

/** The sampled canvas size for an image of this natural size, and the factor back to its own pixels. */
export function sampleSize(
    naturalWidth: number,
    naturalHeight: number,
): { width: number; height: number; back: number } | null {
    if (!(naturalWidth > 0 && naturalHeight > 0)) return null;
    const scale = Math.min(
        1,
        MAX_INK_SIDE / Math.max(naturalWidth, naturalHeight),
    );
    return {
        width: Math.max(1, Math.round(naturalWidth * scale)),
        height: Math.max(1, Math.round(naturalHeight * scale)),
        back: 1 / scale,
    };
}

/**
 * The tight bounds of an image's non-transparent pixels, in the SAMPLED canvas's pixels.
 *
 * Walked in from the four edges rather than swept whole: a component usually fills most of its
 * canvas, so each walk stops within a few rows or columns and the common case never touches the
 * interior at all.
 *
 * Null when there is nothing to measure — an image transparent all the way through. That falls back
 * to the plain `contain` this lane had before, which is the honest answer for a frame with no ink in
 * it.
 */
export function scanInk(
    data: Uint8ClampedArray | number[],
    width: number,
    height: number,
): { left: number; top: number; right: number; bottom: number } | null {
    if (width <= 0 || height <= 0) return null;
    const rowHasInk = (y: number): boolean => {
        for (let x = 0, i = y * width * 4 + 3; x < width; x++, i += 4) {
            if (data[i] >= INK_ALPHA) return true;
        }
        return false;
    };
    const columnHasInk = (x: number, top: number, bottom: number): boolean => {
        for (let y = top; y <= bottom; y++) {
            if (data[(y * width + x) * 4 + 3] >= INK_ALPHA) return true;
        }
        return false;
    };
    let top = 0;
    while (top < height && !rowHasInk(top)) top++;
    if (top >= height) return null;
    let bottom = height - 1;
    while (bottom > top && !rowHasInk(bottom)) bottom--;
    let left = 0;
    while (left < width && !columnHasInk(left, top, bottom)) left++;
    let right = width - 1;
    while (right > left && !columnHasInk(right, top, bottom)) right--;
    return { left, top, right, bottom };
}

/** `scanInk`'s answer scaled back into the image's own pixels. */
export function inkFrom(
    data: Uint8ClampedArray | number[],
    sample: { width: number; height: number; back: number },
    naturalWidth: number,
    naturalHeight: number,
): InkBounds | null {
    const box = scanInk(data, sample.width, sample.height);
    if (!box) return null;
    return {
        x: box.left * sample.back,
        y: box.top * sample.back,
        width: (box.right - box.left + 1) * sample.back,
        height: (box.bottom - box.top + 1) * sample.back,
        imageWidth: naturalWidth,
        imageHeight: naturalHeight,
    };
}

/** A percentage of a span, as a CSS length. */
export function pct(value: number, span: number): string {
    return `${(value / span) * 100}%`;
}

export interface Placement {
    left: string;
    top: string;
    width: string;
    height: string;
}

/**
 * Where the render's `<img>` sits inside the node's slot so its INK lands on the design's ink.
 *
 * In PERCENTAGES of the slot, for the same reason the slots themselves are: the node box and the
 * image's ink box scale together with the stage, so the four numbers are constants and a resize only
 * has to re-measure.
 *
 * The image is placed at its FULL canvas size, scaled — then shifted so the ink within it centres on
 * the slot. That is why the left/top can be negative: the transparent margin hangs outside the slot
 * rather than being spent inside it.
 *
 * Null means "no ink to fit" — the caller clears the inline styles and the stylesheet's `inset: 0` +
 * `object-fit: contain` takes over.
 */
export function fitInk(slot: Rect, ink: InkBounds | null): Placement | null {
    if (!ink || ink.width <= 0 || ink.height <= 0) return null;
    if (!(slot.width > 0 && slot.height > 0)) return null;
    // Uniform, never stretched: the aspect our render actually has is a finding about our code.
    const scale = Math.min(slot.width / ink.width, slot.height / ink.height);
    return {
        width: pct(ink.imageWidth * scale, slot.width),
        height: pct(ink.imageHeight * scale, slot.height),
        left: pct(
            (slot.width - ink.width * scale) / 2 - ink.x * scale,
            slot.width,
        ),
        top: pct(
            (slot.height - ink.height * scale) / 2 - ink.y * scale,
            slot.height,
        ),
    };
}
