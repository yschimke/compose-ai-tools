// What the parity page's visual-difference scan decides, as a table.

import assert from "node:assert/strict";
import {
    GEOMETRY_REPORT_THRESHOLD,
    MATCH_FLOOR,
    checkingOf,
    findingResult,
    geometryOf,
    isFinding,
    progressOf,
    scoreCell,
    sortFindings,
    summaryOf,
    unavailableCell,
    type Finding,
} from "../src/parity/findings.js";
import { GEOMETRY_REPORT_THRESHOLD as SHARED } from "../src/compare/thresholds.js";
import { readout } from "../src/spec/verdict.js";

const finding = (over: Partial<Finding> = {}): Finding => ({
    name: "Button",
    review: "/p/plain.Button",
    score: 72.5,
    geometry: 0,
    unavailable: false,
    ...over,
});

describe("isFinding", () => {
    it("flags a structural mismatch", () => {
        assert.equal(isFinding(MATCH_FLOOR - 0.1, 0), true);
        assert.equal(
            isFinding(MATCH_FLOOR, 0),
            false,
            "the floor itself is a pass",
        );
    });

    it("flags a component that matches structurally but is the wrong shape", () => {
        // The half that is easy to lose: the score is measured on normalised content boxes, so a
        // component can be a 96% match and still be visibly the wrong proportions.
        assert.equal(isFinding(96, GEOMETRY_REPORT_THRESHOLD), true);
        assert.equal(isFinding(96, GEOMETRY_REPORT_THRESHOLD - 0.01), false);
    });
});

describe("geometryOf", () => {
    it("defaults the lanes that report no geometry", () => {
        // A lane comparing two renders of the same source shares its geometry by construction and
        // reports a bare percentage; treating the absent field as a drift would flag all of them.
        assert.equal(geometryOf({ percent: 99 }), 0);
        assert.equal(geometryOf({ percent: 99, geometry: 3.5 }), 3.5);
    });
});

describe("scoreCell", () => {
    it("reads as a clean percentage when the pair matches", () => {
        assert.deepEqual(scoreCell({ percent: 99.94 }), {
            text: "99.9%",
            ok: true,
            title: null,
        });
    });

    it("carries the drift as a tooltip once it is worth reporting", () => {
        const cell = scoreCell({ percent: 97, geometry: 4.25 });
        assert.equal(
            cell.ok,
            false,
            "a shape difference is not a clean result",
        );
        assert.equal(cell.title, "4.3% proportion difference");
    });

    it("says nothing about drift below the threshold", () => {
        assert.equal(scoreCell({ percent: 99, geometry: 1.9 }).title, null);
    });

    it("names a pair it could not score at all", () => {
        assert.deepEqual(unavailableCell(), {
            text: "Unavailable",
            ok: false,
            title: null,
        });
    });
});

describe("findingResult", () => {
    it("is the score alone for a plain structural difference", () => {
        assert.equal(findingResult(finding({ score: 72.46 })), "72.5%");
    });

    it("appends the drift when the shapes differ too", () => {
        assert.equal(
            findingResult(finding({ score: 96.1, geometry: 3.14 })),
            "96.1% · 3.1% proportion drift",
        );
    });

    it("is Unavailable for a pair with no score", () => {
        assert.equal(
            findingResult(finding({ score: null, unavailable: true })),
            "Unavailable",
        );
    });
});

describe("sortFindings", () => {
    it("puts the unscorable pairs above every score", () => {
        // A missing render is a bigger problem than a component that merely disagrees with its
        // reference, and burying it under the low scores is how it goes unnoticed.
        const sorted = sortFindings([
            finding({ name: "High", score: 88 }),
            finding({ name: "Broken", score: null, unavailable: true }),
            finding({ name: "Low", score: 40 }),
        ]);
        assert.deepEqual(
            sorted.map((f) => f.name),
            ["Broken", "Low", "High"],
        );
    });

    it("leaves the caller's array alone", () => {
        const input = [finding({ score: 80 }), finding({ score: 20 })];
        sortFindings(input);
        assert.equal(input[0].score, 80);
    });
});

describe("checkingOf / progressOf", () => {
    it("opens by naming the size of the job", () => {
        // Distinct from the progress line: "Checked 0 of 40" as an opening reads as a result.
        assert.equal(checkingOf(40), "Checking 40 mapped comparison(s)…");
    });

    it("counts up while the queues drain", () => {
        assert.equal(progressOf(3, 40), "Checked 3 of 40 comparisons…");
    });
});

describe("summaryOf", () => {
    it("says so plainly when everything matches", () => {
        assert.equal(
            summaryOf(40, []),
            "All 40 mapped components are at least 90% structural match.",
        );
        assert.equal(
            summaryOf(1, []),
            "All 1 mapped component is at least 90% structural match.",
        );
    });

    it("counts the differences when everything could be scored", () => {
        assert.equal(
            summaryOf(40, [finding(), finding()]),
            "2 mapped components have a structural or proportion difference.",
        );
        assert.equal(
            summaryOf(40, [finding()]),
            "1 mapped component has a structural or proportion difference.",
        );
    });

    it("counts unscorable pairs OUT of the differences", () => {
        // `parity.js` pushed the unavailable pairs onto the same list and then quoted its whole
        // length as "require review", so three missing renders read as "3 are unavailable; 3
        // require review" — the same three components, said twice, as though six were wrong.
        assert.equal(
            summaryOf(
                40,
                [1, 2, 3].map(() =>
                    finding({ score: null, unavailable: true }),
                ),
            ),
            "3 of 40 mapped comparisons could not be scored. The rest are a structural match.",
        );
    });

    it("reports both kinds when there are both", () => {
        assert.equal(
            summaryOf(40, [
                finding({ score: null, unavailable: true }),
                finding({ score: 55 }),
                finding({ score: 61 }),
            ]),
            "1 of 40 mapped comparisons could not be scored. " +
                "2 of the rest have a structural or proportion difference.",
        );
    });
});

describe("the geometry threshold the parity page and the spec lane share", () => {
    it("makes the two pages agree about the SAME pair", () => {
        // Not a value comparison — two `= 2` literals would pass that. This drives the two surfaces
        // that a reader crosses between: the parity page decides a pair is worth opening, and the
        // spec lane it opens into decides whether to say anything about the proportions. Drift
        // either copy and one of these flips, which reads to a visitor as the page being broken
        // rather than as a threshold having moved.
        const drifted = { percent: 100, geometry: SHARED };
        const clean = { percent: 100, geometry: SHARED - 0.01 };

        assert.equal(isFinding(drifted.percent, drifted.geometry), true);
        assert.equal(
            readout(99.9, 0.5, drifted.geometry).includes("proportion"),
            true,
        );

        assert.equal(isFinding(clean.percent, clean.geometry), false);
        assert.equal(
            readout(99.9, 0.5, clean.geometry).includes("proportion"),
            false,
        );
    });
});
