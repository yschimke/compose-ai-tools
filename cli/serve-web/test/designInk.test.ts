// Fitting our drawn pixels onto the design's drawn box, as a table.
//
// A wrong fit here is not a broken page — it is a render that sits in the design's slot at the wrong
// size, which is exactly the finding the lane exists to report. So the failures below all look like
// results.

import assert from "node:assert/strict";
import {
    INK_ALPHA,
    fitInk,
    inkFrom,
    pct,
    sampleSize,
    scanInk,
    type InkBounds,
} from "../src/design/ink.js";

/** RGBA for a `width × height` frame with an opaque rectangle in it. */
function frame(
    width: number,
    height: number,
    mark: { x: number; y: number; w: number; h: number; alpha?: number } | null,
): Uint8ClampedArray {
    const data = new Uint8ClampedArray(width * height * 4);
    if (!mark) return data;
    for (let y = mark.y; y < mark.y + mark.h; y++) {
        for (let x = mark.x; x < mark.x + mark.w; x++) {
            data[(y * width + x) * 4 + 3] = mark.alpha ?? 255;
        }
    }
    return data;
}

describe("sampleSize", () => {
    it("leaves an image already under the cap alone", () => {
        assert.deepEqual(sampleSize(200, 100), {
            width: 200,
            height: 100,
            back: 1,
        });
    });

    it("caps the LONG side, keeping the aspect", () => {
        // The scan's answer is a box, so sampling costs one `drawImage` and bounds the work per
        // node; the worst error is a couple of source pixels on an edge.
        assert.deepEqual(sampleSize(2048, 1024), {
            width: 512,
            height: 256,
            back: 4,
        });
        assert.deepEqual(sampleSize(1024, 2048), {
            width: 256,
            height: 512,
            back: 4,
        });
    });

    it("never samples a sliver away to nothing", () => {
        // A 2048×3 divider scales to 512×0.75; rounded down that is a zero-height canvas, which
        // `getImageData` refuses and which would take the whole lane down for one thin node.
        assert.equal(sampleSize(2048, 3)?.height, 1);
    });

    it("has no answer for an image that has not decoded", () => {
        assert.equal(sampleSize(0, 100), null);
        assert.equal(sampleSize(100, 0), null);
    });
});

describe("scanInk", () => {
    it("finds the tight bounds of what is drawn", () => {
        assert.deepEqual(
            scanInk(frame(10, 10, { x: 2, y: 3, w: 4, h: 5 }), 10, 10),
            {
                left: 2,
                top: 3,
                right: 5,
                bottom: 7,
            },
        );
    });

    it("finds ink that runs to the frame's own edges", () => {
        // The walks start AT the edges; an off-by-one in either would trim a component that fills
        // its canvas, which is the common case rather than the exotic one.
        assert.deepEqual(
            scanInk(frame(4, 4, { x: 0, y: 0, w: 4, h: 4 }), 4, 4),
            {
                left: 0,
                top: 0,
                right: 3,
                bottom: 3,
            },
        );
    });

    it("finds a single drawn pixel", () => {
        assert.deepEqual(
            scanInk(frame(8, 8, { x: 5, y: 6, w: 1, h: 1 }), 8, 8),
            {
                left: 5,
                top: 6,
                right: 5,
                bottom: 6,
            },
        );
    });

    it("takes the alpha cutoff at the threshold, not above it", () => {
        const at = frame(4, 4, { x: 1, y: 1, w: 2, h: 2, alpha: INK_ALPHA });
        const under = frame(4, 4, {
            x: 1,
            y: 1,
            w: 2,
            h: 2,
            alpha: INK_ALPHA - 1,
        });
        assert.ok(scanInk(at, 4, 4), "alpha 16 is ink");
        assert.equal(scanInk(under, 4, 4), null, "alpha 15 is not");
    });

    it("says nothing at all about a frame with no ink in it", () => {
        // Not a zero box: a frame with nothing drawn falls back to plain `contain`, which is the
        // honest placement for a render that has no component in it.
        assert.equal(scanInk(frame(6, 6, null), 6, 6), null);
    });

    it("refuses a frame with no area", () => {
        assert.equal(scanInk(new Uint8ClampedArray(0), 0, 0), null);
    });
});

describe("inkFrom", () => {
    it("scales the sampled box back into the image's own pixels", () => {
        // Sampled at half size, so every edge doubles — and the box stays inclusive of its last
        // sampled row and column, which is where the `+ 1` lives.
        const ink = inkFrom(
            frame(10, 10, { x: 2, y: 2, w: 4, h: 4 }),
            { width: 10, height: 10, back: 2 },
            20,
            20,
        );
        assert.deepEqual(ink, {
            x: 4,
            y: 4,
            width: 8,
            height: 8,
            imageWidth: 20,
            imageHeight: 20,
        });
    });

    it("carries the image's full canvas size alongside the ink", () => {
        // `fitInk` places the whole canvas and shifts it; without these it could only place the ink,
        // and the transparent margin would be cropped rather than hung outside the slot.
        const ink = inkFrom(
            frame(4, 4, { x: 1, y: 1, w: 1, h: 1 }),
            { width: 4, height: 4, back: 1 },
            4,
            4,
        );
        assert.equal(ink?.imageWidth, 4);
        assert.equal(ink?.imageHeight, 4);
    });
});

describe("fitInk", () => {
    const slot = { width: 200, height: 100 };

    it("lands ink exactly on the slot when the two agree", () => {
        // A render whose ink fills its canvas, into a slot of the same aspect: full size, no offset.
        const ink: InkBounds = {
            x: 0,
            y: 0,
            width: 400,
            height: 200,
            imageWidth: 400,
            imageHeight: 200,
        };
        assert.deepEqual(fitInk(slot, ink), {
            width: "100%",
            height: "100%",
            left: "0%",
            top: "0%",
        });
    });

    it("hangs the transparent margin OUTSIDE the slot", () => {
        // The case the module exists for. Ink is the middle half of a square canvas; `contain` would
        // fit the CANVAS to the slot and draw the component at half the size it should be. Here the
        // canvas is scaled so the INK matches, which puts it at 200% of the slot with a negative
        // offset — the margin hanging outside rather than eating the slot.
        const ink: InkBounds = {
            x: 50,
            y: 50,
            width: 100,
            height: 100,
            imageWidth: 200,
            imageHeight: 200,
        };
        const placed = fitInk({ width: 100, height: 100 }, ink);
        assert.deepEqual(placed, {
            width: "200%",
            height: "200%",
            left: "-50%",
            top: "-50%",
        });
    });

    it("fits uniformly and centres, never stretching to the slot", () => {
        // The aspect our render actually has is a finding about our code. A fit that squashed it to
        // the design's box would report every component as the right shape.
        const ink: InkBounds = {
            x: 0,
            y: 0,
            width: 100,
            height: 100,
            imageWidth: 100,
            imageHeight: 100,
        };
        const placed = fitInk(slot, ink)!;
        // Scale is the SMALLER of 2 and 1, so the square ink is 100px in a 200×100 slot...
        assert.equal(placed.width, "50%");
        assert.equal(placed.height, "100%");
        // ...and centred in the leftover width rather than stretched across it.
        assert.equal(placed.left, "25%");
        assert.equal(placed.top, "0%");
    });

    it("hands back to plain `contain` when there is no ink to fit", () => {
        assert.equal(fitInk(slot, null), null);
        assert.equal(
            fitInk(slot, {
                x: 0,
                y: 0,
                width: 0,
                height: 10,
                imageWidth: 10,
                imageHeight: 10,
            }),
            null,
        );
    });

    it("hands back rather than dividing by a slot with no area", () => {
        // A node scrolled out of layout. Without the guard every percentage is Infinity, and the
        // render is placed somewhere no scroll can reach.
        const ink: InkBounds = {
            x: 0,
            y: 0,
            width: 10,
            height: 10,
            imageWidth: 10,
            imageHeight: 10,
        };
        assert.equal(fitInk({ width: 0, height: 100 }, ink), null);
        assert.equal(fitInk({ width: 100, height: 0 }, ink), null);
    });
});

describe("pct", () => {
    it("writes a ratio as a CSS percentage", () => {
        assert.equal(pct(50, 200), "25%");
        assert.equal(pct(-10, 100), "-10%");
    });
});
