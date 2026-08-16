// What counts as the drawn part of an image, and when cropping to it is safe.
//
// Two shipped regressions live behind these rules and both were silent — a plausible number for the
// wrong region, not an error. Guessing the backdrop from the corner pixel stripped a bled card's own
// surface and boxed only its text, so the denser the card the worse it scored; and cropping a
// near-empty capture stretched one heading across the whole comparison, so an empty state that
// matched its reference read as a total mismatch.

import assert from "node:assert/strict";
import {
    aspectDelta,
    boxFromSamples,
    hasTransparency,
    isScaffoldSheet,
    normalisedBoxes,
} from "../src/scorer/contentBox.js";
import { MIN_BOX_COVERAGE } from "../src/scorer/tuning.js";

/** An RGBA buffer from an ASCII grid, with a palette per test. */
function samples(
    rows: string[],
    palette: Record<string, [number, number, number, number]>,
) {
    const width = rows[0].length;
    const height = rows.length;
    const data = new Uint8ClampedArray(width * height * 4);
    rows.join("")
        .split("")
        .forEach((c, i) => {
            const [r, g, b, a] = palette[c];
            data[i * 4] = r;
            data[i * 4 + 1] = g;
            data[i * 4 + 2] = b;
            data[i * 4 + 3] = a;
        });
    return { data, width, height };
}

const WHITE: [number, number, number, number] = [255, 255, 255, 255];
const DARK: [number, number, number, number] = [28, 27, 31, 255];
const INK: [number, number, number, number] = [17, 34, 51, 255];
const CLEAR: [number, number, number, number] = [0, 0, 0, 0];
const MINT: [number, number, number, number] = [232, 241, 238, 255];

describe("isScaffoldSheet", () => {
    it("recognises both sheets `showBackground` actually paints", () => {
        assert.equal(isScaffoldSheet([255, 255, 255]), true);
        assert.equal(isScaffoldSheet([28, 27, 31]), true);
    });

    it("allows PNG round-tripping slack but not a different colour", () => {
        assert.equal(isScaffoldSheet([251, 252, 250]), true);
        assert.equal(isScaffoldSheet([232, 241, 238]), false, "a mint card");
        assert.equal(isScaffoldSheet([59, 29, 29]), false, "a dark red board");
    });
});

describe("hasTransparency", () => {
    it("treats near-opaque as opaque, because PNG alpha round-trips", () => {
        assert.equal(hasTransparency([0, 0, 0, 255, 0, 0, 0, 250]), false);
        assert.equal(hasTransparency([0, 0, 0, 255, 0, 0, 0, 249]), true);
    });
});

describe("boxFromSamples", () => {
    const size = { width: 8, height: 4 };

    it("boxes the drawn pixels of a transparent capture", () => {
        const grid = samples(["........", "..####..", "..####..", "........"], {
            ".": CLEAR,
            "#": INK,
        });
        // Widened by one sample cell each way — the downscale can shave a partially covered edge.
        assert.deepEqual(
            boxFromSamples(grid.data, grid.width, grid.height, size, 1),
            { x: 1, y: 0, width: 6, height: 4 },
        );
    });

    it("strips a scaffold sheet an opaque capture is sitting on", () => {
        const grid = samples(["........", "...##...", "...##...", "........"], {
            ".": WHITE,
            "#": INK,
        });
        const box = boxFromSamples(grid.data, grid.width, grid.height, size, 1);
        assert.ok(box.width < size.width, `stripped to ${box.width}`);
    });

    it("recognises the DARK sheet too, not only white", () => {
        const grid = samples(["........", "...##...", "...##...", "........"], {
            ".": DARK,
            "#": WHITE,
        });
        const box = boxFromSamples(grid.data, grid.width, grid.height, size, 1);
        assert.ok(box.width < size.width, `stripped to ${box.width}`);
    });

    it("does NOT strip an opaque capture whose artwork reaches the corner", () => {
        // The shipped bug. A card bled to the artboard edge with text inset on it is the same
        // picture as a sheet with a card inset on it — so the corner colour decides, and a colour
        // the renderer never paints means those pixels could be the artwork. Guessing here boxed
        // only the text and stretched it against a whole-card render.
        const grid = samples(["########", "##....##", "##....##", "########"], {
            "#": MINT,
            ".": INK,
        });
        assert.deepEqual(
            boxFromSamples(grid.data, grid.width, grid.height, size, 1),
            { x: 0, y: 0, width: 8, height: 4 },
        );
    });

    it("hands back the whole image for a blank capture", () => {
        const grid = samples(["........", "........", "........", "........"], {
            ".": WHITE,
        });
        assert.deepEqual(
            boxFromSamples(grid.data, grid.width, grid.height, size, 1),
            { x: 0, y: 0, width: 8, height: 4 },
        );
    });

    it("maps the box back to SOURCE pixels through the sampling scale", () => {
        // Detection runs on a downscale; a box in sample coordinates would crop the wrong region of
        // the full-resolution image entirely.
        const grid = samples(["........", "..####..", "..####..", "........"], {
            ".": CLEAR,
            "#": INK,
        });
        const box = boxFromSamples(
            grid.data,
            grid.width,
            grid.height,
            { width: 32, height: 16 },
            0.25,
        );
        assert.deepEqual(box, { x: 4, y: 0, width: 24, height: 16 });
    });
});

describe("aspectDelta", () => {
    const box = (width: number, height: number) => ({
        x: 0,
        y: 0,
        width,
        height,
    });

    it("says nothing about two boxes of the same proportions at different sizes", () => {
        assert.equal(aspectDelta(box(100, 50), box(400, 200)), 0);
    });

    it("measures the difference as a share of the LARGER ratio, so it is symmetric", () => {
        assert.equal(
            aspectDelta(box(100, 50), box(100, 100)),
            aspectDelta(box(100, 100), box(100, 50)),
        );
        assert.equal(aspectDelta(box(100, 50), box(100, 100)), 50);
    });
});

describe("normalisedBoxes", () => {
    const size = { width: 200, height: 200 };
    const full = { x: 0, y: 0, width: 200, height: 200 };
    const generous = { x: 20, y: 20, width: 160, height: 160 };
    // Under MIN_BOX_COVERAGE of its canvas: an empty-state capture whose only mark is a heading.
    const sliver = { x: 0, y: 0, width: 40, height: 20 };

    it("crops both sides when both have enough content to locate", () => {
        const boxes = normalisedBoxes(size, size, generous, generous);
        assert.equal(boxes.cropped, true);
        assert.deepEqual(boxes.reference, generous);
        assert.deepEqual(boxes.candidate, generous);
    });

    it("falls back to whole-canvas on BOTH sides when EITHER is too small", () => {
        // Cropping one and not the other would be worse than not cropping: a sliver stretched
        // across its partner turns one line of text into the entire comparison, and an empty state
        // that genuinely matches its reference scores like a total mismatch.
        const boxes = normalisedBoxes(size, size, generous, sliver);
        assert.equal(boxes.cropped, false);
        assert.deepEqual(boxes.reference, full);
        assert.deepEqual(boxes.candidate, full);
        assert.ok(
            (sliver.width * sliver.height) / (size.width * size.height) <
                MIN_BOX_COVERAGE,
        );
    });

    it("reports the MEASURED geometry either way", () => {
        // "These two match but are framed very differently" is worth surfacing even when the score
        // was computed whole-canvas — otherwise the fallback silently hides the finding.
        const boxes = normalisedBoxes(size, size, generous, sliver);
        assert.equal(boxes.cropped, false);
        assert.ok(boxes.geometry > 0, `${boxes.geometry}`);
        assert.equal(boxes.geometry, aspectDelta(generous, sliver));
    });
});
