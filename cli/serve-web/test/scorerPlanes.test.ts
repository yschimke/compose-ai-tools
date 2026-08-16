// The comparison metric, as a table.
//
// This is the number the whole design-parity surface is built on — the badge on a catalog chip, the
// verdict in the spec lane, the ordering of the compare wall — and until now it had no test of any
// kind, because it lived inside an IIFE behind a canvas. Everything here is arithmetic over
// luminance planes; none of it needs a browser.
//
// The assertions are mostly relational on purpose. Exact percentages are a function of the tuning
// constants and would have to be rewritten every time one is adjusted, which trains you to update
// the number rather than ask whether the change was right. What must not change is the ORDERING:
// a raster shift has to stay cheaper than a missing mark, or the metric has stopped answering the
// question it exists for.

import assert from "node:assert/strict";
import {
    directedMismatch,
    edgeMask,
    scorePlanes,
} from "../src/scorer/planes.js";
import {
    EDGE_GRADIENT_THRESHOLD,
    LUMA_TOLERANCE,
} from "../src/scorer/tuning.js";

/** A plane from an ASCII grid: `#` is ink (0), `.` is paper (255). */
function plane(rows: string[]) {
    const width = rows[0].length;
    const height = rows.length;
    const values = rows
        .join("")
        .split("")
        .map((c) => (c === "#" ? 0 : 255));
    return { values, width, height };
}

const noYield = async () => {};

const score = (a: string[], b: string[]) => {
    const left = plane(a);
    const right = plane(b);
    return scorePlanes(
        left.values,
        right.values,
        left.width,
        left.height,
        noYield,
    );
};

describe("edgeMask", () => {
    it("marks the pixels either side of a step, and nothing in a flat field", () => {
        const flat = plane(["....", "....", "....", "...."]);
        assert.equal(
            edgeMask(flat.values, flat.width, flat.height).reduce(
                (a, b) => a + b,
                0,
            ),
            0,
        );

        const line = plane(["..#..", "..#..", "..#..", "..#.."]);
        const mask = edgeMask(line.values, line.width, line.height);
        // The ink column and BOTH paper columns beside it: an edge is a step, and the step is
        // visible from either side of it.
        assert.deepEqual(Array.from(mask.slice(0, 5)), [0, 1, 1, 1, 0]);
    });

    it("clamps at the border rather than wrapping", () => {
        // A column of ink at x=0 must not read the last column as its left neighbour; if it did, a
        // mark against the left edge would answer an edge state that depends on the right edge.
        const edge = plane(["#..", "#..", "#.."]);
        const mask = edgeMask(edge.values, edge.width, edge.height);
        assert.deepEqual(Array.from(mask.slice(0, 3)), [1, 1, 0]);
    });

    it("ignores a step smaller than the gradient threshold", () => {
        const width = 3;
        const subtle = [0, EDGE_GRADIENT_THRESHOLD - 1, 0];
        assert.equal(
            edgeMask(subtle, width, 1).reduce((a, b) => a + b, 0),
            0,
        );
    });
});

describe("scorePlanes", () => {
    const MARK = [
        ".........",
        ".........",
        "...###...",
        "...###...",
        "...###...",
        ".........",
        ".........",
    ];
    const SHIFTED = [
        ".........",
        ".........",
        "....###..",
        "....###..",
        "....###..",
        ".........",
        ".........",
    ];
    const ABSENT = [
        ".........",
        ".........",
        ".........",
        ".........",
        ".........",
        ".........",
        ".........",
    ];
    const EXTRA = [
        ".........",
        ".........",
        "...###...",
        "...###...",
        "...###...",
        ".......#.",
        ".........",
    ];

    it("answers exactly 100 for two identical planes", async () => {
        assert.equal(await score(MARK, MARK), 100);
    });

    it("charges a one-pixel raster shift far less than a missing mark", async () => {
        // The reason this is not a pixel diff. Figma's browser SVG rasteriser and Skia cover the
        // same vector edge with different sub-pixels, so a pair that is visually identical is
        // displaced by a pixel everywhere. Charging that like an absent mark would report a finding
        // on every component in the catalog, forever.
        const shifted = await score(MARK, SHIFTED);
        const absent = await score(MARK, ABSENT);
        assert.ok(shifted > absent, `${shifted} should beat ${absent}`);
        assert.ok(shifted > 95, `a one-pixel shift scored ${shifted}`);
        assert.ok(absent < 90, `a missing mark scored ${absent}`);
    });

    it("averages BOTH directions, so the answer cannot depend on which side is the reference", async () => {
        // The two directions genuinely disagree, and the reason is worth knowing: only an EDGE
        // pixel of the SOURCE may search. A mark added into what was a flat region has no edge in
        // the reference to go looking for it, so reference→candidate pays the full price; from the
        // other side that same mark IS an edge and finds a partner two pixels away for almost
        // nothing. One direction alone would therefore score the same pair differently depending on
        // which frame the caller happened to pass first — and the parity page, the compare wall and
        // the spec lane do not all pass them in the same order.
        const left = plane(MARK);
        const right = plane(EXTRA);
        const leftEdges = edgeMask(left.values, left.width, left.height);
        const rightEdges = edgeMask(right.values, right.width, right.height);
        const forwards = await directedMismatch(
            left.values,
            right.values,
            leftEdges,
            rightEdges,
            left.width,
            left.height,
            noYield,
        );
        const backwards = await directedMismatch(
            right.values,
            left.values,
            rightEdges,
            leftEdges,
            right.width,
            right.height,
            noYield,
        );
        assert.ok(
            forwards > backwards * 2,
            `the added mark costs far more from the reference's side (${forwards} vs ${backwards})`,
        );
        assert.ok(
            (await score(MARK, EXTRA)) < 100,
            "an added mark is a difference",
        );
    });

    it("is symmetric — swapping the two sides answers the same number", async () => {
        assert.equal(await score(MARK, SHIFTED), await score(SHIFTED, MARK));
    });

    it("lets a difference within the luma tolerance through for free", async () => {
        // The two rasterisers disagree slightly about a shared edge on every pixel. Accumulating
        // that would turn "these match" into a percentage that slowly drifts with image size.
        const width = 4;
        const height = 4;
        const flat = new Array(width * height).fill(200);
        const nudged = flat.map((v) => v + LUMA_TOLERANCE);
        assert.equal(
            await scorePlanes(flat, nudged, width, height, noYield),
            100,
        );
        const beyond = flat.map((v) => v + LUMA_TOLERANCE + 40);
        assert.ok(
            (await scorePlanes(flat, beyond, width, height, noYield)) < 100,
        );
    });

    it("charges a flat-region change in full — only EDGE pixels may search", async () => {
        // A large uniform region could otherwise absorb any change inside it by pointing every
        // pixel at some neighbour of the same value.
        const width = 11;
        const height = 11;
        const flat = new Array(width * height).fill(255);
        const blot = flat.slice();
        for (let y = 3; y < 8; y++)
            for (let x = 3; x < 8; x++) blot[y * width + x] = 0;
        const scored = await scorePlanes(flat, blot, width, height, noYield);
        assert.ok(scored < 85, `a blot on a blank field scored ${scored}`);
    });

    it("yields between chunks of rows, so the page keeps painting", async () => {
        // Not a detail: a full catalog performs dozens of comparisons, and without the yield the
        // tab stops accepting input for the duration of each one.
        let yields = 0;
        const size = 24;
        const flat = new Array(size * size).fill(255);
        await scorePlanes(flat, flat, size, size, async () => {
            yields += 1;
        });
        // Every eighth row, in each of the two directions.
        assert.equal(yields, Math.floor(size / 8) * 2);
    });

    it("never answers outside 0–100", async () => {
        const width = 6;
        const height = 6;
        const black = new Array(width * height).fill(0);
        const white = new Array(width * height).fill(255);
        const worst = await scorePlanes(black, white, width, height, noYield);
        assert.ok(worst >= 0 && worst <= 100, `${worst}`);
    });
});
