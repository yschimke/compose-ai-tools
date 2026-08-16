// What the spec lane says about a pair, as a table.

import assert from "node:assert/strict";
import {
    COMPARING,
    GEOMETRY_REPORT_THRESHOLD,
    UNAVAILABLE,
    changedPercentOf,
    chipText,
    matchBand,
    offBaselineReadout,
    readout,
} from "../src/spec/verdict.js";
import {
    rangeValueAt,
    seamX,
    splitAt,
    splitFraction,
} from "../src/spec/wipe.js";

describe("matchBand", () => {
    it("colours the chip by how close the pair is", () => {
        assert.equal(matchBand(100), "match");
        assert.equal(matchBand(99.5), "match");
        assert.equal(matchBand(99.49), "close");
        assert.equal(matchBand(97), "close");
        assert.equal(matchBand(96.99), "off");
        assert.equal(matchBand(0), "off");
    });
});

describe("chipText", () => {
    it("keeps the component's name beside its live score", () => {
        // The chip is an identity element first; a bare percentage there would say what without
        // saying about what.
        assert.equal(chipText("Button", 98.76), "Button 98.8%");
    });
});

describe("readout", () => {
    it("reports structure and literal pixels separately", () => {
        // They answer different questions: a 99% structural match with 8% of pixels differing is a
        // uniform shift; the reverse is a small element in the wrong place. One number loses that.
        assert.equal(
            readout(99.04, 8.123, 0),
            "99.0% match · 8.12% pixels differ",
        );
    });

    it("adds the proportion drift once it is worth reporting", () => {
        assert.equal(
            readout(96, 12, GEOMETRY_REPORT_THRESHOLD),
            "96.0% match · 12.00% pixels differ · 2.0% proportion difference",
        );
    });

    it("refuses a match score when there is no spec for the frame", () => {
        // A reference is imported once, at the catalog's default, and never re-exported per theme.
        // Off that baseline the changed-pixel count is still literally true about the two frames,
        // but a percentage across them grades the override — on a Light Medium Contrast render of
        // a pixel-correct shape the lane read "90.5% match · 89.34% pixels differ".
        const line = offBaselineReadout(89.34);
        assert.equal(
            line,
            "89.34% pixels differ · the imported spec is baseline-only, " +
                "so this is not a match score — clear the overrides to compare",
        );
        assert.ok(!line.includes("match ·"), "no verdict to misread");
    });

    it("stays quiet about drift below the threshold", () => {
        // Under it the two content boxes are the same shape to within rasteriser noise, and saying
        // so would be reporting the rasteriser.
        assert.ok(!readout(96, 12, 1.99).includes("proportion"));
    });

    it("has something to say when there is nothing to measure", () => {
        assert.equal(UNAVAILABLE, "Comparison unavailable");
        assert.equal(COMPARING, "comparing…");
    });
});

describe("changedPercentOf", () => {
    it("is a share of the frame", () => {
        assert.equal(changedPercentOf(50, 10, 10), 50);
        assert.equal(changedPercentOf(0, 10, 10), 0);
    });

    it("answers zero for an empty frame rather than NaN", () => {
        assert.equal(changedPercentOf(0, 0, 0), 0);
    });
});

describe("splitFraction", () => {
    it("reads the range input", () => {
        assert.equal(splitFraction("0"), 0);
        assert.equal(splitFraction("50"), 0.5);
        assert.equal(splitFraction("100"), 1);
    });

    it("centres on anything it cannot read", () => {
        for (const bad of ["", null, undefined, "abc"]) {
            assert.equal(splitFraction(bad), 0.5, String(bad));
        }
    });

    it("clamps a value outside the range", () => {
        assert.equal(splitFraction("-20"), 0);
        assert.equal(splitFraction("420"), 1);
    });
});

describe("splitAt / seamX", () => {
    it("puts the seam where the split is", () => {
        assert.equal(splitAt(200, 0.5), 100);
        assert.equal(seamX(200, 100), 99);
    });

    it("keeps the two-pixel seam inside the frame at both ends", () => {
        // The end the naive arithmetic gets wrong: at 0 the seam would start at -1, and at full
        // width it would start one pixel short of the edge and hang off it.
        assert.equal(seamX(200, splitAt(200, 0)), 0);
        assert.equal(seamX(200, splitAt(200, 1)), 198);
    });
});

describe("rangeValueAt", () => {
    const box = { left: 100, width: 200 };

    it("maps a pointer to the range's own scale", () => {
        assert.equal(rangeValueAt(100, box), "0");
        assert.equal(rangeValueAt(200, box), "50");
        assert.equal(rangeValueAt(300, box), "100");
    });

    it("clamps a drag that leaves the frame", () => {
        // Pointer capture keeps the drag alive past either edge, which is what a slider should do —
        // so the value has to stop at the ends rather than run past them.
        assert.equal(rangeValueAt(-500, box), "0");
        assert.equal(rangeValueAt(5000, box), "100");
    });

    it("declines a frame with no width rather than dividing by it", () => {
        assert.equal(rangeValueAt(50, { left: 0, width: 0 }), null);
    });
});
