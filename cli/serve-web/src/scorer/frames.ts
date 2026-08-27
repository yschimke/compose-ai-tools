// Getting pixels out of the browser and into the plain arrays everything else here works on.
//
// Everything that needs a `document`, an `Image` or a canvas lives in this file and nowhere else, so
// the decisions next door — `planes.ts`, `contentBox.ts`, `deltaMap.ts` — stay testable without one.
// What is left here is thin on purpose: decode, downscale, sample, hand off.

import {
    boxFromSamples,
    normalisedBoxes as decideBoxes,
    wholeImage,
    type Box,
    type NormalisedBoxes,
    type Size,
} from "./contentBox.js";
import { BOX_SAMPLE_SIDE } from "./tuning.js";
import {
    cropTo,
    resampleArea,
} from "../../../../scripts/design-artifacts/known-difference-resample.mjs";

/** Anything decoded that a canvas can draw and that reports its own size. */
export type Frame = CanvasImageSource & {
    width?: number;
    height?: number;
    naturalWidth?: number;
    naturalHeight?: number;
};

export function loadImage(src: string): Promise<HTMLImageElement> {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.decoding = "async";
        img.onload = () => resolve(img);
        img.onerror = () => reject(new Error("image load failed"));
        img.src = src;
    });
}

/**
 * SVG text decoded into an image.
 *
 * Through a blob URL rather than a data URI: an SVG of any real size overflows what a data URI can
 * carry comfortably, and the object URL is revoked either way — on the next macrotask when the
 * decode succeeded (the image still needs it during `onload`), immediately when it failed.
 */
export function svgImage(text: string): Promise<HTMLImageElement> {
    const url = URL.createObjectURL(
        new Blob([text], { type: "image/svg+xml" }),
    );
    return loadImage(url).then(
        (img) => {
            setTimeout(() => URL.revokeObjectURL(url), 0);
            return img;
        },
        (error) => {
            URL.revokeObjectURL(url);
            throw error;
        },
    );
}

export function imageDimensions(image: Frame): Size {
    return {
        width: image.naturalWidth || image.width || 0,
        height: image.naturalHeight || image.height || 0,
    };
}

/** A 2D context that will be read back — see `boxCanvas` on why the flag has to be set here. */
function readableContext(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
    return canvas.getContext("2d", {
        willReadFrequently: true,
    }) as CanvasRenderingContext2D;
}

/** A decoded raster in the shape the portable kernel works on. */
export interface Raster {
    width: number;
    height: number;
    pixels: Uint8Array;
}

/**
 * A frame's own pixels, at its own size — the entry point to the portable path.
 *
 * The draw is one-to-one, so no filter runs and nothing here is host-dependent: what comes back is
 * the decoded image, and every downscale after it is {@link resampleArea}'s arithmetic rather than
 * `drawImage`'s implementation-defined smoothing. That is the whole of the rebaseline
 * ([D3](../../../../docs/design/parity-batches/00-decisions.md)) on this side — the score's geometry
 * is unchanged, its kernel is not.
 *
 * `null` for a frame whose pixels cannot be read at all: a cross-origin artifact taints the canvas
 * and `getImageData` throws. Every caller here already had that case, because the plane the score is
 * computed from is read back the same way.
 */
export function rasterOf(image: Frame): Raster | null {
    const { width, height } = imageDimensions(image);
    if (width <= 0 || height <= 0) return null;
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    context.drawImage(image, 0, 0);
    try {
        const data = context.getImageData(0, 0, width, height).data;
        return {
            width,
            height,
            pixels: new Uint8Array(
                data.buffer,
                data.byteOffset,
                data.byteLength,
            ),
        };
    } catch {
        return null;
    }
}

/**
 * One raster composited onto `ground`, as a luminance plane.
 *
 * {@link grayFromDraw}'s answer without the canvas: the same `source-over` arithmetic on
 * non-premultiplied bytes, so a pixel with alpha `a` lands at `a·colour + (1−a)·ground`. Having it
 * here rather than painting the raster back onto a context is what keeps the portable path portable
 * — a `putImageData` round-trip would reintroduce the browser's own premultiplication rounding on
 * every pixel, which is a difference between engines for no gain.
 */
export function grayFromRaster(
    raster: Raster,
    ground: readonly [number, number, number],
): Float32Array {
    const { width, height, pixels } = raster;
    const gray = new Float32Array(width * height);
    for (let i = 0; i < gray.length; i++) {
        const alpha = pixels[i * 4 + 3] / 255;
        const rest = 1 - alpha;
        const r = pixels[i * 4] * alpha + ground[0] * rest;
        const g = pixels[i * 4 + 1] * alpha + ground[1] * rest;
        const b = pixels[i * 4 + 2] * alpha + ground[2] * rest;
        gray[i] = 0.299 * r + 0.587 * g + 0.114 * b;
    }
    return gray;
}

/**
 * {@link grayFromRaster} for a raster whose colour is already **premultiplied** — the score plane.
 *
 * `source-over` on premultiplied colour is `a·c + (1−a)·ground` with the `a·c` already done, so this
 * adds the ground's share instead of weighting the colour a second time. Handing a premultiplied
 * raster to {@link grayFromRaster} would multiply by alpha twice and drag every partly transparent
 * pixel toward the ground.
 *
 * It exists because averaging straight colour and compositing afterwards do not commute: the same
 * half-covered white edge on black scored 128 encoded as one pixel at alpha 128 and 64 encoded as an
 * opaque pixel beside a transparent one, so two visually identical exports at different resolutions
 * read as a mismatch. `resampleAreaPremultiplied` fixes the ordering upstream and this reads its
 * output — together they are `mean(a·c) + g·(1 − mean(a))`, which is also what `drawImage` produced
 * before the portable kernel replaced it, since a canvas downscales premultiplied.
 */
export function grayFromPremultipliedRaster(
    raster: Raster,
    ground: readonly [number, number, number],
): Float32Array {
    const { width, height, pixels } = raster;
    const gray = new Float32Array(width * height);
    for (let i = 0; i < gray.length; i++) {
        const rest = 1 - pixels[i * 4 + 3] / 255;
        const r = pixels[i * 4] + ground[0] * rest;
        const g = pixels[i * 4 + 1] + ground[1] * rest;
        const b = pixels[i * 4 + 2] + ground[2] * rest;
        gray[i] = 0.299 * r + 0.587 * g + 0.114 * b;
    }
    return gray;
}

/**
 * Whatever `draw` paints, on `ground`, as a luminance plane.
 *
 * The ground is a parameter rather than a constant because a score is taken on more than one of them
 * — see {@link COMPARISON_GROUNDS}. Both sides of a given comparison must be handed the same one, or
 * the pair differs by the ground everywhere.
 */
export function grayFromDraw(
    draw: (context: CanvasRenderingContext2D) => void,
    width: number,
    height: number,
    ground: string,
): Float32Array {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = "high";
    context.fillStyle = ground;
    context.fillRect(0, 0, width, height);
    draw(context);
    const rgba = context.getImageData(0, 0, width, height).data;
    const gray = new Float32Array(width * height);
    for (let i = 0; i < gray.length; i++) {
        gray[i] =
            0.299 * rgba[i * 4] +
            0.587 * rgba[i * 4 + 1] +
            0.114 * rgba[i * 4 + 2];
    }
    return gray;
}

/**
 * The rectangle an image actually draws in, in source pixels.
 *
 * Sampling is done on a downscale: a crop rectangle needs to be roughly right, not exact, and a
 * full-resolution scan of a 1078×2399 device shot per row is real time on the client.
 */
export function contentBox(image: Frame): Box {
    const raster = rasterOf(image);
    // A tainted canvas (cross-origin artifact) cannot be sampled. Fall back to the whole image.
    if (!raster) return wholeImage(imageDimensions(image));
    return contentBoxOf(raster);
}

/**
 * {@link contentBox} over a raster the caller already holds.
 *
 * Split out because a full-resolution raster is the expensive thing on this path and a comparison
 * needs several answers from the same one: its content box, and then its score plane. Measuring
 * from the frame each time decoded it again per question.
 */
export function contentBoxOf(raster: Raster): Box {
    const size = { width: raster.width, height: raster.height };
    const scale = Math.min(
        1,
        BOX_SAMPLE_SIDE / Math.max(size.width, size.height),
    );
    const width = Math.max(1, Math.round(size.width * scale));
    const height = Math.max(1, Math.round(size.height * scale));
    // At `scale === 1` the resample is the identity, so a preview-sized capture is sampled at full
    // resolution and the kernel cannot matter at all.
    const sampled = scale === 1 ? raster : resampleArea(raster, width, height);
    return boxFromSamples(sampled.pixels, width, height, size, scale);
}

/** Both sides measured, and the decision about whether cropping to those measurements is safe. */
export function normalisedBoxes(
    referenceImage: Frame,
    candidateImage: Frame,
): NormalisedBoxes {
    return decideBoxes(
        imageDimensions(referenceImage),
        imageDimensions(candidateImage),
        contentBox(referenceImage),
        contentBox(candidateImage),
    );
}

/** {@link normalisedBoxes} over two rasters the caller already holds. */
export function normalisedBoxesOf(
    reference: Raster,
    candidate: Raster,
): NormalisedBoxes {
    return decideBoxes(
        { width: reference.width, height: reference.height },
        { width: candidate.width, height: candidate.height },
        contentBoxOf(reference),
        contentBoxOf(candidate),
    );
}

/**
 * One image's content box redrawn into a fresh canvas of the shared comparison size.
 *
 * `willReadFrequently` because the very next thing anyone does with these is `getImageData` (the
 * diff walks both of them pixel by pixel), and the flag has to be set on the FIRST `getContext` — a
 * later call with different attributes silently returns the existing context.
 */
export function boxCanvas(
    image: Frame,
    box: Box,
    width: number,
    height: number,
    raster: Raster | null = rasterOf(image),
): HTMLCanvasElement {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    if (!raster) {
        // Unreadable pixels: the browser can still *draw* what it will not let anyone read back, so
        // the visible panel stays right even though nothing downstream can measure it.
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
        );
        return canvas;
    }
    // The same crop-and-resample the score plane is built by, so the magenta map marks the pixels
    // the number was actually computed over rather than a second, differently-filtered rendering of
    // the same pair.
    const scaled = cropTo(raster, box, width, height);
    const painted = context.createImageData(width, height);
    painted.data.set(scaled.pixels);
    context.putImageData(painted, 0, 0);
    return canvas;
}

/** The RGBA of an already-painted canvas, for the delta map. */
export function pixelsOf(
    canvas: HTMLCanvasElement,
    width: number,
    height: number,
): Uint8ClampedArray {
    return readableContext(canvas).getImageData(0, 0, width, height).data;
}

/** A fresh, fully transparent RGBA buffer sized to `target`, plus the context that will show it. */
export function blankMap(
    target: HTMLCanvasElement,
    width: number,
    height: number,
): { context: CanvasRenderingContext2D; image: ImageData } {
    target.width = width;
    target.height = height;
    const context = readableContext(target);
    return { context, image: context.createImageData(width, height) };
}
