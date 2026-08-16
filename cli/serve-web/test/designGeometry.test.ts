// Measured placement — slots, crops, the tip — as a table.

import assert from "node:assert/strict";
import {
    cropFor,
    idMatches,
    idSpellings,
    sheetSize,
    slotIn,
    tipAt,
} from "../src/design/geometry.js";
import {
    laneOf,
    laneState,
    isInert,
    needsRenders,
    outlinesAfterUnlinked,
} from "../src/design/lanes.js";

const box = (left: number, top: number, width: number, height: number) => ({
    left,
    top,
    width,
    height,
});

describe("slotIn", () => {
    it("places a node as percentages of the layer it sits in", () => {
        assert.deepEqual(slotIn(box(0, 0, 400, 200), box(100, 50, 40, 20)), {
            left: "25%",
            top: "25%",
            width: "10%",
            height: "10%",
        });
    });

    it("gives the same ratios at any zoom", () => {
        // The whole reason placement is written in percentages of the CANVAS rather than pixels of
        // the stage: a zoomed canvas is larger and offset, and both sides scale together, so nothing
        // is recomputed when a zoom is applied.
        const plain = slotIn(box(0, 0, 400, 200), box(100, 50, 40, 20));
        const zoomed = slotIn(box(-200, -100, 800, 400), box(0, 0, 80, 40));
        assert.deepEqual(zoomed, plain);
    });

    it("leaves the previous placement alone for a stage with no size yet", () => {
        // A page opened in a background tab, or a font still loading. Placing against a zero box
        // would put every overlay at 0×0 and cache that.
        assert.equal(slotIn(box(0, 0, 0, 200), box(1, 1, 1, 1)), null);
        assert.equal(slotIn(box(0, 0, 400, 0), box(1, 1, 1, 1)), null);
    });

    it("has no slot for a node the export drew with no area", () => {
        assert.equal(slotIn(box(0, 0, 400, 200), box(10, 10, 0, 20)), null);
    });
});

describe("cropFor", () => {
    it("maps a node's screen rect into raster pixels", () => {
        // The raster is 2× the sheet on screen, so every distance doubles.
        assert.deepEqual(
            cropFor(
                { width: 800, height: 400 },
                box(0, 0, 400, 200),
                box(100, 50, 40, 20),
            ),
            { left: 200, top: 100, width: 80, height: 40 },
        );
    });

    it("carries the sheet's own offset on the page", () => {
        assert.deepEqual(
            cropFor(
                { width: 400, height: 200 },
                box(40, 12, 400, 200),
                box(140, 62, 40, 20),
            ),
            { left: 100, top: 50, width: 40, height: 20 },
        );
    });

    it("never crops a zero-width region out of the raster", () => {
        // A hairline divider. `drawImage` with a zero source width draws nothing, so the node would
        // score against a blank and read as totally different.
        const crop = cropFor(
            { width: 100, height: 100 },
            box(0, 0, 1000, 1000),
            box(0, 0, 2, 2),
        );
        assert.equal(crop?.width, 1);
        assert.equal(crop?.height, 1);
    });

    it("has no crop before the sheet has been laid out", () => {
        assert.equal(
            cropFor(
                { width: 10, height: 10 },
                box(0, 0, 0, 0),
                box(0, 0, 5, 5),
            ),
            null,
        );
        assert.equal(
            cropFor(
                { width: 10, height: 10 },
                box(0, 0, 10, 10),
                box(0, 0, 0, 5),
            ),
            null,
        );
    });
});

describe("sheetSize", () => {
    it("leaves a sheet already under the cap at its own size", () => {
        assert.deepEqual(sheetSize({ width: 1000, height: 1000 }), {
            width: 1000,
            height: 1000,
            scale: 1,
        });
    });

    it("caps by TOTAL pixels, keeping the aspect", () => {
        // Not by side: the scorer downsamples to 192px on the longest side anyway, so resolution
        // beyond "the smallest node still lands around 192px" buys nothing and costs 4 bytes each.
        const sized = sheetSize({ width: 4000, height: 4000 })!;
        assert.equal(sized.width, 2000);
        assert.equal(sized.height, 2000);
        assert.ok(sized.width * sized.height <= 4e6);
    });

    it("has no raster for a sheet with no viewBox worth rendering", () => {
        assert.equal(sheetSize({ width: 0, height: 100 }), null);
    });
});

describe("tipAt", () => {
    const stage = box(0, 0, 500, 400);
    const tip = { width: 120, height: 60 };

    it("sits just off the cursor, never under it", () => {
        assert.deepEqual(tipAt(stage, tip, { x: 100, y: 100 }), {
            left: 114,
            top: 114,
        });
    });

    it("flips to the other side rather than leaving the stage", () => {
        // A tip that ran off the right-hand shapes, or off the bottom row, would be describing
        // something the reader cannot read.
        assert.deepEqual(tipAt(stage, tip, { x: 480, y: 390 }), {
            left: 346,
            top: 316,
        });
    });

    it("carries the stage's own offset on the page", () => {
        assert.deepEqual(
            tipAt(box(50, 30, 500, 400), tip, { x: 150, y: 130 }),
            {
                left: 114,
                top: 114,
            },
        );
    });

    it("stays on the stage even when flipping would take it off the other edge", () => {
        // A tip wider than the space either side of the cursor. Clamped rather than allowed to go
        // negative, which would put it under the page chrome.
        assert.deepEqual(tipAt(box(0, 0, 130, 70), tip, { x: 125, y: 65 }), {
            left: 0,
            top: 0,
        });
    });
});

describe("idSpellings / idMatches", () => {
    it("tries both of Figma's spellings", () => {
        // Figma writes ids with a colon; the same id appears hyphenated in its own URLs, and a
        // hand-written manifest may use either.
        assert.deepEqual(idSpellings("58548:7249"), [
            "58548:7249",
            "58548-7249",
        ]);
        assert.deepEqual(idSpellings("58548-7249"), [
            "58548-7249",
            "58548:7249",
        ]);
    });

    it("does not invent a second spelling for an id with neither", () => {
        assert.deepEqual(idSpellings("I1234"), ["I1234"]);
        assert.deepEqual(idSpellings(""), []);
    });

    it("matches an attribute value under either spelling", () => {
        assert.equal(idMatches("58548-7249", "58548:7249"), true);
        assert.equal(idMatches("58548:7249", "58548:7249"), true);
        assert.equal(idMatches("58548:7250", "58548:7249"), false);
        assert.equal(idMatches(null, "58548:7249"), false);
    });
});

describe("lanes", () => {
    it("shows OUR renders in every lane but the design's own", () => {
        assert.equal(laneState("code")["cp-page-swap-on"], true);
        assert.equal(laneState("diff")["cp-page-swap-on"], true);
        assert.equal(laneState("design")["cp-page-swap-on"], false);
        assert.equal(laneState("diff")["cp-page-diff-on"], true);
        assert.equal(laneState("code")["cp-page-diff-on"], false);
    });

    it("adopts the renders for any lane that draws them", () => {
        // Including diff, which scores what is actually on the sheet.
        assert.equal(needsRenders("code"), true);
        assert.equal(needsRenders("diff"), true);
        assert.equal(needsRenders("design"), false);
    });

    it("falls back to the code lane for anything it does not recognise", () => {
        assert.equal(laneOf("diff"), "diff");
        assert.equal(laneOf("nonsense"), "code");
        assert.equal(laneOf(null), "code");
    });

    it("turns the marks on for a filter that would otherwise be invisible", () => {
        // A coverage filter with nothing to draw on is a no-op the reader cannot see.
        assert.equal(outlinesAfterUnlinked(true, false), true);
    });

    it("leaves the marks on when the filter is switched off again", () => {
        // It was an explicit state to arrive at, and silently repainting the sheet plain would read
        // as the filter having broken something.
        assert.equal(outlinesAfterUnlinked(false, true), true);
        assert.equal(outlinesAfterUnlinked(false, false), false);
    });

    it("mutes on the GAP, not on 'unlinked'", () => {
        // The filter shows components with no code behind them; the sheet's private furniture and
        // variant-set containers are neither, so they are what gets taken out of the tab order.
        assert.equal(isInert(true, false), true);
        assert.equal(isInert(true, true), false);
        assert.equal(isInert(false, false), false);
    });
});
