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
    const size = imageDimensions(image);
    const scale = Math.min(
        1,
        BOX_SAMPLE_SIDE / Math.max(size.width, size.height),
    );
    const width = Math.max(1, Math.round(size.width * scale));
    const height = Math.max(1, Math.round(size.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = "high";
    context.drawImage(image, 0, 0, width, height);
    let data: Uint8ClampedArray;
    try {
        data = context.getImageData(0, 0, width, height).data;
    } catch {
        // A tainted canvas (cross-origin artifact) cannot be sampled. Fall back to the whole image.
        return wholeImage(size);
    }
    return boxFromSamples(data, width, height, size, scale);
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
): HTMLCanvasElement {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    readableContext(canvas).drawImage(
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
