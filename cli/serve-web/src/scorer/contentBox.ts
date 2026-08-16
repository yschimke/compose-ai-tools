// Where an image actually draws, and whether it is safe to compare over that region.
//
// A design reference and a rendered preview are authored to different edges. The preview carries
// whatever its `@Preview` scaffold added — `showBackground`'s opaque sheet, a `padding()` inset, a
// fixed-height container the content does not fill — while the reference is usually cropped to the
// artboard. Scoring those against each other measures the scaffold, not the component: the whole
// image is translated and rescaled relative to its partner, which reads as total mismatch.
//
// No DOM here. `frames.ts` does the sampling; these are the decisions made about what it sampled.

import {
    BOX_COLOUR_TOLERANCE,
    MIN_BOX_COVERAGE,
    SCAFFOLD_SHEETS,
    SHEET_TOLERANCE,
} from "./tuning.js";

export interface Box {
    x: number;
    y: number;
    width: number;
    height: number;
}

export interface Size {
    width: number;
    height: number;
}

export const wholeImage = (size: Size): Box => ({
    x: 0,
    y: 0,
    width: size.width,
    height: size.height,
});

/** Whether an opaque corner colour is one of the sheets `showBackground` paints. */
export function isScaffoldSheet(rgb: ArrayLike<number>): boolean {
    return SCAFFOLD_SHEETS.some(
        (sheet) =>
            Math.abs(rgb[0] - sheet[0]) <= SHEET_TOLERANCE &&
            Math.abs(rgb[1] - sheet[1]) <= SHEET_TOLERANCE &&
            Math.abs(rgb[2] - sheet[2]) <= SHEET_TOLERANCE,
    );
}

/** Whether any pixel in an RGBA buffer is meaningfully transparent. */
export function hasTransparency(data: ArrayLike<number>): boolean {
    for (let probe = 3; probe < data.length; probe += 4) {
        if (data[probe] < 250) return true;
    }
    return false;
}

/**
 * The drawn rectangle, read off a downscaled RGBA sample and mapped back to source pixels.
 *
 * Detection uses alpha where the image has any: a transparent pixel is unambiguously not artwork.
 *
 * An opaque image is the hard case, because "a uniform border around an interior region" is the
 * *same picture* whether the border is a scaffold sheet with a card inset on it, or a card that
 * bleeds to the artboard edge with text inset on it. Guessing from the corner pixel gets the second
 * one exactly backwards — it strips the component's own surface and boxes only its text, so a
 * tightly-cropped card reference gets stretched against a whole-card render and the pair reads as a
 * total mismatch. The denser the card, the worse the score.
 *
 * So an opaque image's backdrop is not guessed. It is trusted only when the corner is a sheet the
 * preview renderer actually paints, and any other corner colour means the pixels there could be the
 * artwork, so the whole image is the content box. That errs toward comparing too much — which costs
 * a little of the scaffold correction on a preview with a custom `backgroundColor` — rather than
 * toward silently comparing the wrong region.
 */
export function boxFromSamples(
    data: ArrayLike<number>,
    width: number,
    height: number,
    size: Size,
    scale: number,
): Box {
    const transparent = hasTransparency(data);
    const backdrop = [data[0], data[1], data[2]];
    if (!transparent && !isScaffoldSheet(backdrop)) return wholeImage(size);

    let minX = width;
    let minY = height;
    let maxX = -1;
    let maxY = -1;
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const i = (y * width + x) * 4;
            const drawn = transparent
                ? data[i + 3] > 8
                : Math.abs(data[i] - backdrop[0]) +
                      Math.abs(data[i + 1] - backdrop[1]) +
                      Math.abs(data[i + 2] - backdrop[2]) >
                  BOX_COLOUR_TOLERANCE;
            if (drawn) {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
    }
    // A blank capture has no content box; comparing whole-image is the only meaningful answer.
    if (maxX < 0) return wholeImage(size);

    // Widen by one sample cell each way — the downscale can shave a partially-covered edge pixel.
    const inverse = 1 / scale;
    const x0 = Math.max(0, Math.floor((minX - 1) * inverse));
    const y0 = Math.max(0, Math.floor((minY - 1) * inverse));
    const x1 = Math.min(size.width, Math.ceil((maxX + 2) * inverse));
    const y1 = Math.min(size.height, Math.ceil((maxY + 2) * inverse));
    return {
        x: x0,
        y: y0,
        width: Math.max(1, x1 - x0),
        height: Math.max(1, y1 - y0),
    };
}

/**
 * How far apart two content boxes are in shape, 0 (identical proportions) to 100.
 *
 * Reported beside the score rather than folded into it. Normalising both sides to a common box
 * before scoring is what makes the appearance comparison meaningful, but it also makes a genuine
 * proportion difference invisible — and a reference stretched into the render's canvas is a real
 * finding, not noise to be smoothed away. Two honest numbers beat one blended one.
 */
export function aspectDelta(a: Box, b: Box): number {
    const ratioA = a.width / a.height;
    const ratioB = b.width / b.height;
    return (Math.abs(ratioA - ratioB) / Math.max(ratioA, ratioB)) * 100;
}

export interface NormalisedBoxes {
    reference: Box;
    candidate: Box;
    geometry: number;
    cropped: boolean;
}

/**
 * The rectangles to actually compare over, plus the measured boxes for reporting.
 *
 * Cropping to content is the right move while both captures have enough content to locate. It stops
 * being right on a near-empty one: an empty-state preview whose only mark is a heading yields a box
 * of a few percent of the canvas, and stretching that sliver across its partner turns one line of
 * text into the entire comparison. An empty state that genuinely matches its reference then scores
 * like a total mismatch.
 *
 * So the crop is conditional. When EITHER side's box is too small to be a reliable frame, BOTH sides
 * fall back to the whole canvas — cropping one and not the other would be worse than not cropping.
 * The measured boxes are still reported either way; "these two match but are framed very
 * differently" is worth surfacing even when the score was computed whole-canvas.
 */
export function normalisedBoxes(
    referenceSize: Size,
    candidateSize: Size,
    referenceBox: Box,
    candidateBox: Box,
): NormalisedBoxes {
    const coverage = Math.min(
        (referenceBox.width * referenceBox.height) /
            (referenceSize.width * referenceSize.height),
        (candidateBox.width * candidateBox.height) /
            (candidateSize.width * candidateSize.height),
    );
    const full = coverage < MIN_BOX_COVERAGE;
    return {
        reference: full ? wholeImage(referenceSize) : referenceBox,
        candidate: full ? wholeImage(candidateSize) : candidateBox,
        geometry: aspectDelta(referenceBox, candidateBox),
        cropped: !full,
    };
}
