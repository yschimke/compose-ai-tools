// The six entry points every comparison surface calls, composed from the pieces next door.
//
// This is the whole of `window.ComposePreviewCompare`. Four surfaces reach it as a global — the
// parity page, the compare wall, the spec lane and the reference page — and two consumers outside
// the browser drive it by loading the built asset: the publish-time score driver
// (`scripts/design-artifacts/design-reference-score.mjs`) and the compare audit. That is why the
// shape below is a contract rather than an implementation detail; `src/formatCompare.ts` is what
// publishes it.

import { deltaMap } from "./deltaMap.js";
import {
    blankMap,
    boxCanvas,
    grayFromDraw,
    imageDimensions,
    loadImage,
    normalisedBoxes,
    pixelsOf,
    svgImage,
    type Frame,
} from "./frames.js";
import { scorePlanes } from "./planes.js";
import { translateOf } from "./svgTranslate.js";
import { MAX_SIDE } from "./tuning.js";

export interface Measurement {
    /** Structural match, 0–100. */
    percent: number;
    /** Proportion drift between the two content boxes, in percent. */
    geometry: number;
}

export interface NormalisedPair {
    reference: HTMLCanvasElement;
    candidate: HTMLCanvasElement;
    /** The decoded originals, so a caller can score without re-requesting the two frames. */
    images: [HTMLImageElement, HTMLImageElement];
    width: number;
    height: number;
    geometry: number;
    /** Source rectangles redrawn into the shared canvas; annotation bounds use this space. */
    boxes: {
        reference: { x: number; y: number; width: number; height: number };
        candidate: { x: number; y: number; width: number; height: number };
    };
}

/** The downscale a score is computed at, from whichever box drives the shared size. */
function comparisonSize(box: { width: number; height: number }) {
    const scale = Math.min(1, MAX_SIDE / Math.max(box.width, box.height));
    return {
        scale,
        width: Math.max(1, Math.round(box.width * scale)),
        height: Math.max(1, Math.round(box.height * scale)),
    };
}

/**
 * Score a baked PNG against an SVG of the SAME render.
 *
 * A bare percentage rather than a {@link Measurement}: the two share their geometry by construction,
 * so there is no proportion difference to report. The SVG is drawn with its root translate
 * subtracted — see `svgTranslate.ts` — because otherwise it lands wherever it sat on the design
 * board and the score describes the offset.
 */
export async function scoreSvgUrls(
    pngUrl: string,
    svgUrl: string,
): Promise<number> {
    const [png, text] = await Promise.all([
        loadImage(pngUrl),
        fetch(svgUrl).then((response) => {
            if (!response.ok) throw new Error(`SVG ${response.status}`);
            return response.text();
        }),
    ]);
    const svg = await svgImage(text);
    const render = imageDimensions(png);
    const { scale, width, height } = comparisonSize(render);
    const reference = grayFromDraw(
        (context) =>
            context.drawImage(
                png,
                0,
                0,
                render.width * scale,
                render.height * scale,
            ),
        width,
        height,
    );
    const translate = translateOf(text);
    const svgSize = imageDimensions(svg);
    const candidate = grayFromDraw(
        (context) =>
            context.drawImage(
                svg,
                -translate.x * scale,
                -translate.y * scale,
                svgSize.width * scale,
                svgSize.height * scale,
            ),
        width,
        height,
    );
    return scorePlanes(reference, candidate, width, height);
}

/** Score a baked PNG against a canvas something else has already drawn — the RC lane's shape. */
export async function scoreCanvas(
    pngUrl: string,
    sourceCanvas: CanvasImageSource,
): Promise<number> {
    const png = await loadImage(pngUrl);
    const render = imageDimensions(png);
    const { scale, width, height } = comparisonSize(render);
    const draw =
        (source: CanvasImageSource) => (context: CanvasRenderingContext2D) =>
            context.drawImage(
                source,
                0,
                0,
                render.width * scale,
                render.height * scale,
            );
    const reference = grayFromDraw(draw(png), width, height);
    const candidate = grayFromDraw(draw(sourceCanvas), width, height);
    return scorePlanes(reference, candidate, width, height);
}

/**
 * Score a design reference against a rendered preview.
 *
 * Both sides are cropped to their content box and drawn into one common target box, so the score
 * answers "does this component look like its design?" rather than "were these two files exported at
 * the same size?". Dimensions no longer have to agree — requiring that was what pushed producers
 * into resampling reference art to fit the render's canvas in the first place.
 */
export async function scoreImageUrls(
    referenceUrl: string,
    candidateUrl: string,
): Promise<Measurement> {
    const [reference, candidate] = await Promise.all([
        loadImage(referenceUrl),
        loadImage(candidateUrl),
    ]);
    return scoreImages(reference, candidate);
}

/**
 * {@link scoreImageUrls} over frames that are already decoded.
 *
 * Split out so a caller holding the images — the viewer's spec lane, which has just normalised them
 * onto its canvases — can score the very frames it drew instead of re-requesting the URLs. That
 * matters beyond the wasted work: an override-bearing `/render` is `no-store`, so a second request
 * is a second render, and the score could end up describing a different frame than the diff beside
 * it. The downscale still starts from the ORIGINAL images, not from the normalised canvases, so this
 * is one resample exactly as before and the numbers are unchanged.
 */
export async function scoreImages(
    referenceImage: Frame,
    candidateImage: Frame,
): Promise<Measurement> {
    const boxes = normalisedBoxes(referenceImage, candidateImage);
    const { width, height } = comparisonSize(boxes.candidate);
    const plane = (image: Frame, box: typeof boxes.candidate) =>
        grayFromDraw(
            (context) =>
                context.drawImage(
                    image,
                    box.x,
                    box.y,
                    box.width,
                    box.height,
                    0,
                    0,
                    width,
                    height,
                ),
            width,
            height,
        );
    const percent = await scorePlanes(
        plane(referenceImage, boxes.reference),
        plane(candidateImage, boxes.candidate),
        width,
        height,
    );
    return { percent, geometry: boxes.geometry };
}

/**
 * Both frames redrawn at ONE shared size: each side's content box scaled onto the candidate's box.
 *
 * This is the step every pixel-for-pixel surface needs before it can say anything true — the diff
 * map, the triptych's three panels, the wipe's two halves. A design reference exported at a
 * different scale, or with different padding, than the render is the normal case, not the exception;
 * comparing the raw frames would put the two components' pixels at different addresses and every
 * downstream surface would be reporting the offset rather than the divergence.
 */
export async function normaliseImageUrls(
    referenceUrl: string,
    candidateUrl: string,
): Promise<NormalisedPair> {
    const images = (await Promise.all([
        loadImage(referenceUrl),
        loadImage(candidateUrl),
    ])) as [HTMLImageElement, HTMLImageElement];
    const boxes = normalisedBoxes(images[0], images[1]);
    const width = boxes.candidate.width;
    const height = boxes.candidate.height;
    return {
        width,
        height,
        geometry: boxes.geometry,
        boxes: {
            reference: boxes.reference,
            candidate: boxes.candidate,
        },
        reference: boxCanvas(images[0], boxes.reference, width, height),
        candidate: boxCanvas(images[1], boxes.candidate, width, height),
        images,
    };
}

/**
 * Paint the magenta delta map of two already-normalised, same-sized canvases into `target`, and
 * report how many pixels actually moved.
 */
export function diffCanvases(
    reference: HTMLCanvasElement,
    candidate: HTMLCanvasElement,
    target: HTMLCanvasElement,
): number {
    const width = reference.width;
    const height = reference.height;
    const referenceData = pixelsOf(reference, width, height);
    const candidateData = pixelsOf(candidate, width, height);
    const { context, image } = blankMap(target, width, height);
    const { changed } = deltaMap(referenceData, candidateData, image.data);
    context.clearRect(0, 0, width, height);
    context.putImageData(image, 0, 0);
    return changed;
}

export { loadImage };
