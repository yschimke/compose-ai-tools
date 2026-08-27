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
    grayFromPremultipliedRaster,
    imageDimensions,
    loadImage,
    normalisedBoxes,
    normalisedBoxesOf,
    pixelsOf,
    rasterOf,
    svgImage,
    type Frame,
    type Raster,
} from "./frames.js";
import { scorePlanes } from "./planes.js";
import { translateOf } from "./svgTranslate.js";
import {
    COMPARISON_GROUNDS,
    COMPARISON_GROUND_RGB,
    MAX_SIDE,
} from "./tuning.js";
import { cropToPremultiplied } from "../../../../scripts/design-artifacts/known-difference-resample.mjs";

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

/** Paints one side of a comparison onto whichever ground it is handed. */
type Draw = (context: CanvasRenderingContext2D) => void;

/**
 * The structural match of two drawings, scored once per {@link COMPARISON_GROUNDS} and reported as
 * the **worst** result.
 *
 * The canvas-bound scorers — the SVG lane and the Remote Compose lane, whose sources are not
 * rasters this side can read — go through this rather than compositing once, because a single
 * opaque ground
 * silently deletes ink that matches it and `scorePlanes` scores the resulting pair of blanks as
 * `100`. Taking the minimum is what makes that unrecoverable-looking case recoverable: content
 * annihilated on white survives on black and vice versa, so the ground that still *has* the evidence
 * is the one that decides the number.
 *
 * The pessimism this introduces on an honest pair is small and symmetric — the two grounds disagree
 * only by resampling noise on content that is visible on both — and it is the right direction to err
 * in for a metric whose job is to find differences.
 */
async function scoreOnEveryGround(
    drawReference: Draw,
    drawCandidate: Draw,
    width: number,
    height: number,
): Promise<number> {
    // EVERY plane is rasterised before the first await, not one ground at a time.
    //
    // `scorePlanes` yields to the event loop every eighth row, and a source is not always a still:
    // `scoreCanvas`'s candidate is a live canvas owned by the Remote Compose player, which schedules
    // its own animation frames. Scoring ground-by-ground would let it repaint between passes, so the
    // two grounds would measure two different frames and the minimum of those is neither — a
    // single-shot score that changes when nothing changed.
    const planes = COMPARISON_GROUNDS.map((ground) => ({
        reference: grayFromDraw(drawReference, width, height, ground),
        candidate: grayFromDraw(drawCandidate, width, height, ground),
    }));

    let worst = 100;
    for (const { reference, candidate } of groundsWorthScoring(planes)) {
        worst = Math.min(
            worst,
            await scorePlanes(reference, candidate, width, height),
        );
    }
    return worst;
}

/** One comparison, composited onto one ground. */
export interface GroundPlanes {
    reference: Float32Array;
    candidate: Float32Array;
}

/**
 * Which of the rasterised grounds actually deserve a score: all of them, or only the first.
 *
 * A second ground only means something when there is alpha for it to show through. An opaque image
 * composites identically onto every ground, so its planes come back equal — which is also how this
 * detects opacity, for free, without rasterising or decoding anything extra.
 *
 * The case it guards is a MIXED pair: an opaque reference against a render with a transparent
 * surround. Nothing about the reference moves between grounds while all of the render's surround
 * does, so the black pass would report a difference that is in the grounds rather than in the
 * artwork, and `scoreOnEveryGround`'s minimum would take it as the answer. That is not hypothetical
 * — a design-page reference is a crop of a rasterised sheet, opaque background and all.
 *
 * When BOTH sides are opaque the extra grounds are merely redundant and the minimum is a no-op, so
 * dropping them costs nothing. When both carry alpha, scoring all of them is the whole point.
 */
export function groundsWorthScoring(
    planes: ReadonlyArray<GroundPlanes>,
): ReadonlyArray<GroundPlanes> {
    const varies = (side: keyof GroundPlanes) =>
        planes.some((plane) => !samePlane(plane[side], planes[0][side]));
    return varies("reference") && varies("candidate") ? planes : [planes[0]];
}

/**
 * Whether two luminance planes are the same picture.
 *
 * The tolerance is for a nearly-opaque pixel: alpha 254 lets a sliver of ground through and moves a
 * luminance by well under one unit, which is not the alpha this is looking for. Anything that
 * genuinely shows its ground moves by far more.
 */
function samePlane(a: Float32Array, b: Float32Array): boolean {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
        if (Math.abs(a[i] - b[i]) > 1) return false;
    }
    return true;
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
    const translate = translateOf(text);
    const svgSize = imageDimensions(svg);
    return scoreOnEveryGround(
        (context) =>
            context.drawImage(
                png,
                0,
                0,
                render.width * scale,
                render.height * scale,
            ),
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
    return scoreOnEveryGround(draw(png), draw(sourceCanvas), width, height);
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
 * is one resample and nothing about the geometry depends on what a caller happened to draw.
 *
 * **The kernel is the portable area average, not `drawImage`** — the D3 rebaseline. Both sides are
 * rasterised at their own size, cropped to their content box and resampled by `cropTo`, and the
 * grounds are composited in arithmetic rather than by a `fillRect` underneath a draw. That is what
 * makes this number reproducible outside a browser, and therefore the same number the acceptance
 * band's `raw` reports: measured over the committed `renders/lane-parity` pairs the two now agree
 * to 0.007pp, where the browser filter used to put them ~0.3pp apart. `SCORE_VERSION` says which
 * path a published figure came from; see `tuning.ts`.
 */
export async function scoreImages(
    referenceImage: Frame,
    candidateImage: Frame,
): Promise<Measurement> {
    // Rasterised ONCE per side and reused for both questions. A full-resolution raster is the
    // expensive object on this path — it is what the portable kernel measures from, exactly as the
    // offline engine measures from `decodePng`'s — so asking the frame for its content box and then
    // for its score plane must not decode it twice.
    const reference = rasterOf(referenceImage);
    const candidate = rasterOf(candidateImage);
    if (!reference || !candidate) {
        // Unreadable pixels — a cross-origin frame. The old path could not measure one either:
        // every plane it scored came back through `getImageData`.
        throw new Error("frame pixels are unreadable");
    }
    const boxes = normalisedBoxesOf(reference, candidate);
    const { width, height } = comparisonSize(boxes.candidate);
    // ONE resample, source → score plane, at the candidate box's dimensions (I10), through the
    // portable area average rather than `drawImage`. The geometry is exactly what it was and the
    // kernel is not. Premultiplied, because averaging straight colour and compositing afterwards do
    // not commute — see `resampleAreaPremultiplied`. `boxCanvas` still crops through the straight
    // `cropTo`: the panel it paints and the delta map that walks it need displayable bytes, and
    // premultiplied colour handed to `putImageData` renders dark.
    const scaled: [Raster, Raster] = [
        cropToPremultiplied(reference, boxes.reference, width, height),
        cropToPremultiplied(candidate, boxes.candidate, width, height),
    ];
    const grounds = COMPARISON_GROUND_RGB.map((ground) => ({
        reference: grayFromPremultipliedRaster(scaled[0], ground),
        candidate: grayFromPremultipliedRaster(scaled[1], ground),
    }));
    let percent = 100;
    for (const plane of groundsWorthScoring(grounds)) {
        percent = Math.min(
            percent,
            await scorePlanes(plane.reference, plane.candidate, width, height),
        );
    }
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
    maxSide?: number,
): Promise<NormalisedPair> {
    const images = (await Promise.all([
        loadImage(referenceUrl),
        loadImage(candidateUrl),
    ])) as [HTMLImageElement, HTMLImageElement];
    const rasters = [rasterOf(images[0]), rasterOf(images[1])] as const;
    const boxes =
        rasters[0] && rasters[1]
            ? normalisedBoxesOf(rasters[0], rasters[1])
            : normalisedBoxes(images[0], images[1]);
    // `maxSide` bounds the pixel space the pair is normalised INTO, for a caller that will never
    // draw the result larger than that — the compare wall, whose map lives in a 200px column. It
    // cannot move the percentage: `scoreImages` measures the decoded ORIGINALS at its own downscale,
    // not these canvases. What it does change is the peak: uncapped, one row transiently holds three
    // full-resolution RGBA buffers (two normalised sides plus the delta map), a frame past the
    // browser's canvas limit fails outright, and the wall pays all of it once per row.
    const bound = maxSide
        ? Math.min(
              1,
              maxSide /
                  Math.max(boxes.candidate.width, boxes.candidate.height, 1),
          )
        : 1;
    const width = Math.max(1, Math.round(boxes.candidate.width * bound));
    const height = Math.max(1, Math.round(boxes.candidate.height * bound));
    return {
        width,
        height,
        geometry: boxes.geometry,
        boxes: {
            reference: boxes.reference,
            candidate: boxes.candidate,
        },
        reference: boxCanvas(
            images[0],
            boxes.reference,
            width,
            height,
            rasters[0],
        ),
        candidate: boxCanvas(
            images[1],
            boxes.candidate,
            width,
            height,
            rasters[1],
        ),
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
