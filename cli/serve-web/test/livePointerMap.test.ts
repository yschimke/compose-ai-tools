// Where a press on a live card lands on the composition, as a table.
//
// None of this is visible in a screenshot. A wrong mapping does not error and draws nothing wrong —
// it presses a different widget, and the card looks identical either way. Every case below is a
// press that would reach the wrong place.

import assert from "node:assert/strict";
import { containedRect, drifted, framePixel } from "../src/live/pointerMap.js";

const rect = (left: number, top: number, width: number, height: number) => ({
    left,
    top,
    width,
    height,
});

describe("containedRect", () => {
    it("fills the slot exactly when the aspects agree", () => {
        assert.deepEqual(
            containedRect(rect(0, 0, 200, 100), { x: 400, y: 200 }),
            {
                left: 0,
                top: 0,
                width: 200,
                height: 100,
            },
        );
    });

    it("pillarboxes a frame taller than its slot, centred", () => {
        // A 1:2 frame in a 2:1 slot: the frame is 50 wide and the 150 left over is split evenly.
        assert.deepEqual(
            containedRect(rect(0, 0, 200, 100), { x: 100, y: 200 }),
            {
                left: 75,
                top: 0,
                width: 50,
                height: 100,
            },
        );
    });

    it("letterboxes a frame wider than its slot, centred", () => {
        assert.deepEqual(
            containedRect(rect(0, 0, 100, 200), { x: 200, y: 100 }),
            {
                left: 0,
                top: 75,
                width: 100,
                height: 50,
            },
        );
    });

    it("carries the slot's own offset on the page", () => {
        const painted = containedRect(rect(40, 12, 200, 100), {
            x: 100,
            y: 200,
        });
        assert.deepEqual(painted, {
            left: 115,
            top: 12,
            width: 50,
            height: 100,
        });
    });

    it("has no answer for a frame or slot with no area", () => {
        // A card scrolled out of layout, or a canvas before its first frame.
        assert.equal(containedRect(rect(0, 0, 0, 100), { x: 10, y: 10 }), null);
        assert.equal(containedRect(rect(0, 0, 100, 0), { x: 10, y: 10 }), null);
        assert.equal(
            containedRect(rect(0, 0, 100, 100), { x: 0, y: 10 }),
            null,
        );
        assert.equal(
            containedRect(rect(0, 0, 100, 100), { x: 10, y: 0 }),
            null,
        );
    });
});

describe("framePixel", () => {
    it("maps into the frame's OWN pixels, not the element's", () => {
        // The daemon's wire units are the frame's natural pixels. A 400px frame shown at 200px means
        // every coordinate doubles on the way out.
        const point = framePixel(
            rect(0, 0, 200, 100),
            { x: 400, y: 200 },
            { x: 50, y: 25 },
        );
        assert.deepEqual(point, { x: 100, y: 50 });
    });

    it("subtracts the letterbox margin rather than scaling against the whole element", () => {
        // The case the contained rect exists for. The frame is pillarboxed 75px in on each side, so
        // a press at the frame's left edge is at client x=75 — scaled against the bounding rect it
        // would report x=37, a third of the way into a component it is nowhere near.
        const frame = { x: 100, y: 200 };
        const box = rect(0, 0, 200, 100);
        assert.deepEqual(framePixel(box, frame, { x: 75, y: 0 }), {
            x: 0,
            y: 0,
        });
        assert.deepEqual(framePixel(box, frame, { x: 100, y: 50 }), {
            x: 50,
            y: 100,
        });
    });

    it("treats a press in the margin as a press on nothing", () => {
        // Not a clamp. Clamping would invent a press on whatever widget sits at the frame's border,
        // reliably, and only for people whose window shape differs from the author's.
        const frame = { x: 100, y: 200 };
        const box = rect(0, 0, 200, 100);
        assert.equal(framePixel(box, frame, { x: 10, y: 50 }), null);
        assert.equal(framePixel(box, frame, { x: 190, y: 50 }), null);
    });

    it("keeps the frame's own far edge pressable", () => {
        // `Math.round` puts the last half-pixel at exactly `width`; rejecting it would make the
        // rightmost column and bottom row unreachable.
        const frame = { x: 100, y: 100 };
        assert.deepEqual(
            framePixel(rect(0, 0, 100, 100), frame, { x: 100, y: 100 }),
            { x: 100, y: 100 },
        );
        assert.equal(
            framePixel(rect(0, 0, 100, 100), frame, { x: 101, y: 100 }),
            null,
        );
    });

    it("has no answer before the canvas has a frame in it", () => {
        assert.equal(
            framePixel(rect(0, 0, 200, 100), { x: 0, y: 0 }, { x: 5, y: 5 }),
            null,
        );
    });
});

describe("drifted", () => {
    const from = { x: 100, y: 100 };

    it("tolerates a hand that is not quite still", () => {
        assert.equal(drifted(from, { x: 110, y: 110 }, 10), false);
    });

    it("gives up the gesture once the pointer is really moving", () => {
        assert.equal(drifted(from, { x: 111, y: 100 }, 10), true);
        assert.equal(drifted(from, { x: 100, y: 111 }, 10), true);
        assert.equal(drifted(from, { x: 89, y: 100 }, 10), true);
    });

    it("tolerates a SQUARE, not a circle", () => {
        // 10 on both axes is a diagonal distance of 14.1 — outside a radius-10 circle, inside the
        // square. Deliberate: the gesture this has to survive is a vertical flick-scroll on touch,
        // and a square is more forgiving along the axes the competing gestures actually use.
        assert.equal(drifted(from, { x: 110, y: 110 }, 10), false);
    });
});
