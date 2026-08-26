// Compositing a raster onto a ground, without a canvas.
//
// This is the arithmetic half of the rebaseline. The score's planes used to come back through
// `drawImage` + `getImageData`, so the browser decided both the downscale filter AND the alpha
// blend; neither is reproducible off-browser, and the second one silently round-trips every pixel
// through premultiplication. `grayFromRaster` does the blend here instead, on straight-alpha bytes,
// so the same inputs give the same luminance plane in a browser, in node, and in `design-parity`.

import "./setup.js";
import assert from "node:assert/strict";
import { grayFromRaster } from "../src/scorer/frames.js";

/** One row of straight-alpha RGBA. */
const row = (...pixels: Array<[number, number, number, number]>) => ({
    width: pixels.length,
    height: 1,
    pixels: new Uint8Array(pixels.flat()),
});

const WHITE = [255, 255, 255] as const;
const BLACK = [0, 0, 0] as const;

describe("grayFromRaster", () => {
    it("leaves an opaque pixel alone, whatever the ground", () => {
        // The ground is only ever visible through alpha. An opaque image composites identically
        // onto every ground — which is also how `groundsWorthScoring` detects opacity, for free —
        // so a blend that leaked ground into an opaque pixel would make the second pass report a
        // difference that is in the grounds rather than in the artwork.
        const ink = row([200, 100, 50, 255]);
        const onWhite = grayFromRaster(ink, WHITE);
        const onBlack = grayFromRaster(ink, BLACK);
        assert.equal(onWhite[0], onBlack[0]);
        assert.equal(
            Math.round(onWhite[0]),
            Math.round(0.299 * 200 + 0.587 * 100 + 0.114 * 50),
        );
    });

    it("shows the ground through a fully transparent pixel", () => {
        const clear = row([255, 0, 255, 0]);
        assert.equal(grayFromRaster(clear, WHITE)[0], 255);
        assert.equal(grayFromRaster(clear, BLACK)[0], 0);
    });

    it("blends a half-transparent pixel toward its ground", () => {
        // `source-over` on straight alpha: `a·colour + (1−a)·ground`. White ink at half alpha is
        // mid-grey on black and stays white on white — which is the whole reason a score is taken
        // on both grounds and the worse result kept. Scored on white alone this pixel is
        // indistinguishable from an absent one.
        const half = row([255, 255, 255, 128]);
        assert.equal(Math.round(grayFromRaster(half, WHITE)[0]), 255);
        assert.equal(Math.round(grayFromRaster(half, BLACK)[0]), 128);
    });

    it("weights the channels the way the luma plane always has", () => {
        const green = row([0, 255, 0, 255]);
        // The plane is a `Float32Array`, so the comparison is to single precision, not to double.
        assert.ok(
            Math.abs(grayFromRaster(green, BLACK)[0] - 0.587 * 255) < 1e-3,
        );
    });
});
