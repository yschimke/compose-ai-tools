// The geometry behind the zoom, tested where it is decided.
//
// These are the cases that actually broke it. The first `frameRect` cut fitted
// both axes, which is correct for a square node and useless for the surface this
// feature exists for: on m3-catalog's Styles page (6263x1851, so a 3.4:1 stage)
// every section is a full-height card, and fitting one resolves to 1.0x — the
// reader double-clicks Typography and the picture does not move. `pickLevel`'s
// magnification floor is the same failure one level down.

import assert from "node:assert/strict";
import {
    MAX_SCALE,
    clamp,
    fitFactor,
    frameRect,
    pickLevel,
    rescale,
    rest,
    revealDelta,
    zoomAbout,
    zoomed,
    type Box,
} from "../src/zoom/viewport.js";

function box(left: number, top: number, width: number, height: number): Box {
    return { left, top, width, height };
}

/** A stage the shape of m3-catalog's Styles sheet: 1200 x 355 is 3.38:1. */
const STYLES_STAGE = box(0, 100, 1200, 355);
/** One of its sections: a quarter of the width, the sheet's full height. */
const STYLES_SECTION = box(0, 100, 303, 355);

/** A landing-shaped stage, 1200 x 800, for the ordinary cases. */
const STAGE = box(0, 0, 1200, 800);

describe("clamp", () => {
    it("pins the view at 1:1 — there is nothing to pan", () => {
        assert.deepEqual(clamp({ scale: 1, x: -400, y: -200 }, STAGE), rest());
    });

    it("never lets the sheet be dragged off its stage", () => {
        const held = clamp({ scale: 2, x: 500, y: -5000 }, STAGE);
        assert.equal(held.x, 0, "panned past the left edge");
        assert.equal(held.y, -800, "panned past the bottom edge");
    });

    it("caps the scale", () => {
        assert.equal(clamp({ scale: 500, x: 0, y: 0 }, STAGE).scale, MAX_SCALE);
    });
});

describe("zoomAbout", () => {
    it("keeps what is under the pointer under the pointer", () => {
        const next = zoomAbout(rest(), STAGE, 300, 200, 2);
        // The content point at (300, 200) was at 300/1 = 300 before; after doubling
        // it must still land on the same stage pixel.
        assert.equal(next.x + 300 * next.scale, 300);
        assert.equal(next.y + 200 * next.scale, 200);
    });

    it("cannot zoom out past 1:1", () => {
        assert.deepEqual(zoomAbout(rest(), STAGE, 600, 400, 0.2), rest());
    });
});

describe("rescale", () => {
    it("keeps the reader looking at the same part of the sheet", () => {
        // A 2x view centred on the middle of the sheet, in a stage that then halves in
        // width. Clamping alone would leave x where it was and slide the centre of the
        // view from 50% to 75% across the drawing.
        const wide = box(0, 0, 1200, 800);
        const narrow = box(0, 0, 600, 800);
        const centred = { scale: 2, x: -600, y: -400 };
        const after = rescale(centred, wide, narrow);
        const before = -centred.x / (wide.width * centred.scale);
        assert.equal(-after.x / (narrow.width * after.scale), before);
    });

    it("still holds the sheet inside the new box", () => {
        const after = rescale(
            { scale: 2, x: -2000, y: 0 },
            box(0, 0, 1200, 800),
            box(0, 0, 600, 800),
        );
        assert.ok(
            after.x >= -600,
            "cannot be panned past the right edge of the smaller stage",
        );
    });

    it("leaves a view alone when a box has no size yet", () => {
        const view = { scale: 2, x: -10, y: -20 };
        assert.deepEqual(rescale(view, box(0, 0, 0, 0), STAGE), view);
        assert.deepEqual(rescale(view, STAGE, box(0, 0, 0, 0)), view);
    });
});

describe("fitFactor", () => {
    it("magnifies a full-height section on a wide sheet", () => {
        // The bug this pins: fitting both axes gives min(3.96, 1.0) = 1.0, so the
        // gesture appeared to do nothing on the one page that needs it most.
        assert.ok(
            fitFactor(STYLES_SECTION, STYLES_STAGE) > 2.5,
            "a quarter-width, full-height card must actually get bigger",
        );
    });

    it("caps the crop at three times what would fit", () => {
        // A very tall column: filling the width would leave a sliver on screen.
        const column = box(0, 100, 100, 4000);
        const factor = fitFactor(column, STYLES_STAGE);
        assert.ok(factor <= (STYLES_STAGE.height / column.height) * 3);
    });

    it("crops nothing when the shape is close to the stage's", () => {
        // A square node in a 1.5:1 stage fits at 800/180; filling the width would
        // cut a third of it off instead.
        const node = box(100, 100, 180, 180);
        assert.equal(fitFactor(node, STAGE), (800 / 180) * 0.94);
    });

    it("answers 1 for a rect with no area", () => {
        assert.equal(fitFactor(box(0, 0, 0, 0), STAGE), 1);
    });
});

describe("frameRect", () => {
    it("centres a section that fits", () => {
        const node = box(100, 100, 180, 180);
        const next = frameRect(rest(), STAGE, node);
        const width = node.width * next.scale;
        const left = next.x + node.left * next.scale;
        assert.ok(
            Math.abs(left - (STAGE.width - width) / 2) < 1,
            "a fitted node is centred horizontally",
        );
    });

    it("anchors the top of a section too tall to fit", () => {
        const next = frameRect(rest(), STYLES_STAGE, STYLES_SECTION);
        const top =
            next.y + (STYLES_SECTION.top - STYLES_STAGE.top) * next.scale;
        assert.ok(
            top <= 0.04 * STYLES_STAGE.height,
            "opens at the top, not the middle",
        );
        assert.ok(top > -1, "and not above it");
    });

    it("composes: framing from inside an already-zoomed view", () => {
        const outer = frameRect(rest(), STAGE, box(0, 0, 600, 400));
        const inner = frameRect(outer, STAGE, box(100, 100, 100, 100));
        assert.ok(inner.scale > outer.scale, "a second frame goes deeper");
    });

    it("leaves the view alone for an empty rect", () => {
        const view = { scale: 2, x: -10, y: -20 };
        assert.deepEqual(frameRect(view, STAGE, box(0, 0, 0, 0)), view);
    });
});

describe("revealDelta", () => {
    it("says nothing for a node already on screen", () => {
        assert.equal(revealDelta(box(100, 100, 50, 50), STAGE), null);
    });

    it("pans the minimum needed to bring a node back", () => {
        const delta = revealDelta(box(-100, 100, 50, 50), STAGE);
        assert.equal(delta?.x, 112, "just inside the left edge, plus the pad");
        assert.equal(delta?.y, 0, "and not a pixel vertically");
    });

    it("pulls a node back from beyond the bottom edge", () => {
        const delta = revealDelta(box(100, 900, 50, 50), STAGE);
        assert.ok((delta?.y ?? 0) < 0);
    });
});

describe("pickLevel", () => {
    const outer = box(0, 0, 1200, 800);
    // A sheet, a card on it, a slot in the card, a glyph in the slot.
    const chain = [
        { node: "sheet", box: box(0, 0, 1180, 780) },
        { node: "card", box: box(0, 0, 560, 690) },
        { node: "slot", box: box(20, 20, 460, 200) },
        { node: "hairline", box: box(30, 30, 2, 40) },
    ];

    it("enters the outermost level that is not the sheet itself", () => {
        assert.equal(pickLevel(chain, -1, STAGE, outer)?.node, "card");
    });

    it("goes one level deeper from the level already framed", () => {
        assert.equal(pickLevel(chain, 1, STAGE, outer)?.node, "slot");
    });

    it("skips a wrapper the same size as its parent", () => {
        const wrapped = [
            { node: "card", box: box(0, 0, 560, 690) },
            { node: "clip-group", box: box(0, 0, 559, 689) },
            { node: "slot", box: box(20, 20, 460, 200) },
        ];
        assert.equal(pickLevel(wrapped, 0, STAGE, outer)?.node, "slot");
    });

    it("skips a hairline it could only fill the stage with", () => {
        assert.equal(pickLevel(chain, 2, STAGE, outer), null);
    });

    it("measures the hairline cutoff in the sheet's pixels, not the screen's", () => {
        // A 1 px stroke seen at 12x measures 12 on screen and would sail through a
        // fixed screen cutoff — filling the stage with the exact glyph detail the
        // guard exists to reject.
        const zoomedIn = [
            { node: "column", box: box(0, 0, 400, 600) },
            { node: "stroke", box: box(10, 10, 12, 480) },
        ];
        assert.equal(pickLevel(zoomedIn, 0, STAGE, outer, 12), null);
        // …and at 1:1 a 12 px node is a real thing to enter.
        assert.equal(pickLevel(zoomedIn, 0, STAGE, outer, 1)?.node, "stroke");
    });

    it("skips a level it cannot magnify", () => {
        // A band as wide as the sheet: framing it would not move the picture, so the
        // drill must keep going rather than pretend.
        const bands = [
            { node: "band", box: box(0, 0, 1180, 260) },
            { node: "cell", box: box(20, 20, 200, 200) },
        ];
        assert.equal(pickLevel(bands, -1, STAGE, outer)?.node, "cell");
    });

    it("answers null when there is nothing under the pointer", () => {
        assert.equal(pickLevel([], -1, STAGE, outer), null);
    });
});

describe("zoomed", () => {
    it("ignores floating-point dust just above 1", () => {
        assert.equal(zoomed({ scale: 1.0005, x: 0, y: 0 }), false);
        assert.equal(zoomed({ scale: 1.2, x: 0, y: 0 }), true);
    });
});
