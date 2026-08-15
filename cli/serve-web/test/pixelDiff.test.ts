// The in-browser pixel metric, as a table.
//
// `rc-lanes.js` carried this as nine magic constants and a threshold scale, hand-transcribed from
// pixelmatch, with no test of any kind — and it produces the ONLY number on the compare page the
// offline run did not compute. A drift here makes the page disagree with the build while looking
// identical.

import assert from "node:assert/strict";
import {
    DEFAULT_THRESHOLD,
    MAX_DELTA,
    diffPixels,
    limitFor,
    sameSize,
    yiqDelta,
    type Pixels,
} from "../src/rc/pixelDiff.js";

/** A w×h frame of one solid colour. */
const solid = (
    width: number,
    height: number,
    [r, g, b]: [number, number, number],
): Pixels => ({
    width,
    height,
    data: new Uint8ClampedArray(width * height * 4).map((_, i) =>
        i % 4 === 0 ? r : i % 4 === 1 ? g : i % 4 === 2 ? b : 255,
    ),
});

/** A frame whose pixels are given one by one, row-major. */
const frame = (
    width: number,
    height: number,
    px: Array<[number, number, number]>,
): Pixels => ({
    width,
    height,
    data: new Uint8ClampedArray(px.flatMap(([r, g, b]) => [r, g, b, 255])),
});

describe("yiqDelta", () => {
    it("is zero for identical pixels", () => {
        const a = new Uint8ClampedArray([12, 34, 56, 255]);
        assert.equal(yiqDelta(a, a, 0), 0);
    });

    it("is symmetric", () => {
        const a = new Uint8ClampedArray([255, 0, 0, 255]);
        const b = new Uint8ClampedArray([0, 0, 255, 255]);
        assert.equal(yiqDelta(a, b, 0), yiqDelta(b, a, 0));
    });

    it("tops out at pixelmatch's own maximum", () => {
        // MAX_DELTA is the scale `threshold` is expressed against, so a drift in the transform
        // silently changes what every threshold means. The maximum is red against cyan, NOT black
        // against white — white only maximises the luminance term (32,857), and the extra 2,358
        // comes from the chroma pair. Swept over the whole RGB cube's corners, where the extremes
        // of all three terms live.
        const corners: Array<[number, number, number]> = [];
        for (const r of [0, 255])
            for (const g of [0, 255])
                for (const b of [0, 255]) corners.push([r, g, b]);
        let max = 0;
        for (const a of corners)
            for (const b of corners) {
                max = Math.max(
                    max,
                    yiqDelta(
                        new Uint8ClampedArray([...a, 255]),
                        new Uint8ClampedArray([...b, 255]),
                        0,
                    ),
                );
            }
        assert.ok(
            max <= MAX_DELTA,
            `${max} must not exceed the declared scale ${MAX_DELTA}`,
        );
        assert.ok(MAX_DELTA - max < 1, `${max} should reach ≈ ${MAX_DELTA}`);
    });

    it("ignores alpha", () => {
        // Both sides are published opaque PNGs of the same document; an alpha term would only add
        // noise. Asserted so a "fix" that reads the fourth byte has to argue with a test.
        const a = new Uint8ClampedArray([10, 20, 30, 255]);
        const b = new Uint8ClampedArray([10, 20, 30, 0]);
        assert.equal(yiqDelta(a, b, 0), 0);
    });

    it("reads the pixel at the given byte offset", () => {
        const a = new Uint8ClampedArray([0, 0, 0, 255, 255, 255, 255, 255]);
        const b = new Uint8ClampedArray([0, 0, 0, 255, 0, 0, 0, 255]);
        assert.equal(yiqDelta(a, b, 0), 0);
        assert.ok(yiqDelta(a, b, 4) > 0);
    });

    it("weighs luminance above chroma", () => {
        // The point of YIQ over plain RGB distance: a shift the eye reads as brighter matters more
        // than one it reads as a hue change of the same RGB magnitude.
        const grey = new Uint8ClampedArray([128, 128, 128, 255]);
        const lighter = new Uint8ClampedArray([178, 178, 178, 255]);
        const shifted = new Uint8ClampedArray([178, 128, 128, 255]);
        assert.ok(yiqDelta(grey, lighter, 0) > yiqDelta(grey, shifted, 0));
    });
});

describe("limitFor", () => {
    it("scales with the square of the threshold", () => {
        assert.equal(limitFor(1), MAX_DELTA);
        assert.equal(limitFor(0.5), MAX_DELTA * 0.25);
        assert.equal(limitFor(0), 0);
    });

    it("uses pixelmatch's default when the manifest names none", () => {
        assert.equal(DEFAULT_THRESHOLD, 0.1);
    });
});

describe("diffPixels", () => {
    it("finds nothing between a frame and itself", () => {
        const result = diffPixels(
            solid(4, 3, [10, 200, 30]),
            solid(4, 3, [10, 200, 30]),
            0.1,
        );
        assert.equal(result.changed, 0);
        assert.equal(result.total, 12);
        assert.equal(result.percent, 0);
    });

    it("counts every differing pixel and reports it as a percentage of the frame", () => {
        const reference = frame(2, 2, [
            [0, 0, 0],
            [0, 0, 0],
            [0, 0, 0],
            [0, 0, 0],
        ]);
        const lane = frame(2, 2, [
            [255, 255, 255],
            [0, 0, 0],
            [0, 0, 0],
            [0, 0, 0],
        ]);
        const result = diffPixels(reference, lane, 0.1);
        assert.equal(result.changed, 1);
        assert.equal(result.percent, 25);
    });

    it("paints the changed pixels red and the rest a washed-out reference", () => {
        const reference = frame(2, 1, [
            [0, 0, 0],
            [0, 0, 0],
        ]);
        const lane = frame(2, 1, [
            [255, 255, 255],
            [0, 0, 0],
        ]);
        const { data } = diffPixels(reference, lane, 0.1);
        assert.deepEqual(Array.from(data.slice(0, 4)), [255, 60, 60, 255]);
        // Black at 10% over white: 255 + (0 - 255) * 0.1 = 229.5, clamped to 230 on write.
        assert.deepEqual(Array.from(data.slice(4, 8)), [230, 230, 230, 255]);
    });

    it("is always opaque, so a diff never shows the page through it", () => {
        const { data } = diffPixels(
            solid(3, 3, [1, 2, 3]),
            solid(3, 3, [200, 0, 0]),
            0.1,
        );
        for (let i = 3; i < data.length; i += 4) assert.equal(data[i], 255);
    });

    it("flags more as the threshold tightens", () => {
        const reference = solid(4, 4, [128, 128, 128]);
        const lane = solid(4, 4, [140, 140, 140]);
        assert.equal(
            diffPixels(reference, lane, 0.5).changed,
            0,
            "loose: within tolerance",
        );
        assert.equal(
            diffPixels(reference, lane, 0.01).changed,
            16,
            "tight: every pixel differs",
        );
    });

    it("agrees with the cutoff it is given, exactly", () => {
        // A pixel AT the limit is not changed — pixelmatch's own `>` rather than `>=`. One pixel of
        // difference on a boundary is not worth a bug report, but a flipped comparison here moves
        // every number on the page slightly, which is.
        const reference = frame(1, 1, [[0, 0, 0]]);
        const lane = frame(1, 1, [[255, 255, 255]]);
        const threshold = Math.sqrt(
            yiqDelta(reference.data, lane.data, 0) / MAX_DELTA,
        );
        assert.equal(diffPixels(reference, lane, threshold).changed, 0);
        assert.equal(diffPixels(reference, lane, threshold * 0.999).changed, 1);
    });
});

describe("sameSize", () => {
    it("refuses frames that cannot be compared", () => {
        assert.equal(
            sameSize(solid(2, 2, [0, 0, 0]), solid(2, 2, [0, 0, 0])),
            true,
        );
        assert.equal(
            sameSize(solid(2, 2, [0, 0, 0]), solid(2, 3, [0, 0, 0])),
            false,
        );
        assert.equal(
            sameSize(solid(2, 2, [0, 0, 0]), solid(3, 2, [0, 0, 0])),
            false,
        );
    });
});
