// The magenta delta map behind the reference page's middle panel.

import assert from "node:assert/strict";
import { DIFF_ALPHA_BASE, DIFF_INK, deltaMap } from "../src/scorer/deltaMap.js";
import { DIFF_CHANNEL_TOLERANCE } from "../src/scorer/tuning.js";

const rgba = (pixels: Array<[number, number, number, number]>) =>
    Uint8ClampedArray.from(pixels.flat());

const blank = (count: number) => new Uint8ClampedArray(count * 4);

describe("deltaMap", () => {
    it("leaves an unchanged pixel fully transparent", () => {
        const a = rgba([[10, 20, 30, 255]]);
        const into = blank(1);
        const { changed } = deltaMap(a, a, into);
        assert.equal(changed, 0);
        assert.deepEqual(Array.from(into), [0, 0, 0, 0]);
    });

    it("ignores round-tripping noise and marks anything above it", () => {
        const base: [number, number, number, number] = [100, 100, 100, 255];
        const noise = rgba([[100 + DIFF_CHANNEL_TOLERANCE, 100, 100, 255]]);
        assert.equal(deltaMap(rgba([base]), noise, blank(1)).changed, 0);

        const real = rgba([[100 + DIFF_CHANNEL_TOLERANCE + 1, 100, 100, 255]]);
        assert.equal(deltaMap(rgba([base]), real, blank(1)).changed, 1);
    });

    it("counts an ALPHA-only change, so a mark over transparency shows", () => {
        // The case that motivated including alpha: a component drawn where the reference had
        // nothing differs in no colour channel at all, because the RGB under full transparency is
        // whatever the encoder felt like writing.
        const before = rgba([[0, 0, 0, 0]]);
        const after = rgba([[0, 0, 0, 255]]);
        assert.equal(deltaMap(before, after, blank(1)).changed, 1);
    });

    it("grows the mark's alpha with the size of the delta", () => {
        // So a wholesale colour swap reads louder than a one-pixel edge shift, instead of the map
        // showing one flat magenta wash for both.
        const small = blank(1);
        deltaMap(rgba([[100, 0, 0, 255]]), rgba([[120, 0, 0, 255]]), small);
        const large = blank(1);
        deltaMap(rgba([[0, 0, 0, 255]]), rgba([[255, 0, 0, 255]]), large);
        assert.deepEqual(Array.from(small.slice(0, 3)), [
            DIFF_INK[0],
            DIFF_INK[1],
            DIFF_INK[2],
        ]);
        assert.equal(small[3], DIFF_ALPHA_BASE + 20);
        assert.ok(large[3] > small[3]);
    });

    it("clamps the alpha rather than wrapping past 255", () => {
        const into = blank(1);
        deltaMap(rgba([[0, 0, 0, 0]]), rgba([[255, 255, 255, 255]]), into);
        assert.equal(into[3], 255);
    });

    it("takes the LOUDEST channel, not their sum or average", () => {
        // A single channel moving all the way is a real change; averaging four channels would let
        // three unchanged ones drown it under the tolerance.
        const into = blank(1);
        const { changed } = deltaMap(
            rgba([[0, 0, 0, 255]]),
            rgba([[40, 0, 0, 255]]),
            into,
        );
        assert.equal(changed, 1);
        assert.equal(into[3], DIFF_ALPHA_BASE + 40);
    });

    it("counts every changed pixel across a frame", () => {
        const before = rgba([
            [0, 0, 0, 255],
            [0, 0, 0, 255],
            [0, 0, 0, 255],
        ]);
        const after = rgba([
            [0, 0, 0, 255],
            [90, 0, 0, 255],
            [90, 0, 0, 255],
        ]);
        const into = blank(3);
        assert.equal(deltaMap(before, after, into).changed, 2);
        assert.equal(into[3], 0, "the matching pixel stays clear");
    });
});
