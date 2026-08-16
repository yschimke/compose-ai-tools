// What a node's diff badge says, as a table.
//
// Both mistakes pinned here have been made in this code before, and neither looks like a bug on
// screen: one prints a confident green number for a total mismatch, the other prints the same wrong
// number on every node.

import assert from "node:assert/strict";
import { badgeFor, bandFor } from "../src/design/score.js";

describe("badgeFor", () => {
    it("reports DRIFT, inverting the scorer's match", () => {
        // `scoreImages` answers with a match percentage — identical images score 100. Getting the
        // inversion backwards prints "100.0%" in red for a perfect match and green for a total
        // mismatch: a readout that lies rather than one that is merely wrong.
        assert.equal(badgeFor({ percent: 100, geometry: 0 }).text, "0.0%");
        assert.equal(badgeFor({ percent: 0, geometry: 0 }).text, "100.0%");
        assert.equal(badgeFor({ percent: 91.4, geometry: 0 }).text, "8.6%");
    });

    it("keeps the headline as drift alone, never the worse of the two", () => {
        // The first attempt took `max(drift, geometry)` as the number, and every badge on this
        // fixture read 52.4% — the aspect difference wearing the label of a pixel difference. Two
        // measures conflated into one lie.
        const badge = badgeFor({ percent: 99, geometry: 52.4 });
        assert.equal(badge.text, "1.0% ⇲", "the number is the drift");
        assert.equal(badge.value, 52.4, "the BAND still sees the geometry");
        assert.equal(badge.band, "far");
    });

    it("marks a render that is the wrong SHAPE", () => {
        // Proportion difference is held out of the match number by the scorer, which normalises both
        // boxes onto one size first. Unmarked, a component rendered at the wrong aspect reads as a
        // near-perfect match.
        assert.ok(
            badgeFor({ percent: 100, geometry: 2.01 }).text.endsWith("⇲"),
        );
        assert.ok(!badgeFor({ percent: 100, geometry: 2 }).text.endsWith("⇲"));
    });

    it("names the geometry in the tooltip once it is more than dust", () => {
        assert.equal(
            badgeFor({ percent: 95, geometry: 3.24 }).title,
            "5.0% different · 3.2% proportion difference",
        );
        assert.equal(
            badgeFor({ percent: 95, geometry: 0.05 }).title,
            "5.0% different",
            "0.05 rounds to 0.1% and would read as a finding",
        );
    });

    it("survives a lane that reports no geometry at all", () => {
        const badge = badgeFor({ percent: 98 });
        assert.equal(badge.text, "2.0%");
        assert.equal(badge.title, "2.0% different");
        assert.equal(badge.band, "drifting");
    });

    it("never reports a negative difference", () => {
        // Floating-point dust just over 100, which would otherwise print "-0.0%".
        assert.equal(
            badgeFor({ percent: 100.0001, geometry: -0.2 }).text,
            "0.0%",
        );
    });
});

describe("bandFor", () => {
    it("triages into three, on the boundaries it claims", () => {
        // A decision, not a measurement — so the edges are worth pinning.
        assert.equal(bandFor(0), "close");
        assert.equal(bandFor(1.99), "close");
        assert.equal(bandFor(2), "drifting");
        assert.equal(bandFor(9.99), "drifting");
        assert.equal(bandFor(10), "far");
        assert.equal(bandFor(100), "far");
    });
});
