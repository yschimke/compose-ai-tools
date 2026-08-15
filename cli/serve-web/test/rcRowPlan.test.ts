// What one compare row does once a reference is picked, as a table.

import assert from "node:assert/strict";
import type { RcModel, RcRow } from "../src/rc/model.js";
import { laneIdsOf, referenceFrom, shortLabelOf } from "../src/rc/model.js";
import {
    band,
    percentText,
    planRow,
    sizeMismatchText,
    type RowStep,
} from "../src/rc/rowPlan.js";

const LANES = ["baked", "android", "cmp"];

const row = (over: Partial<RcRow> = {}): RcRow => ({
    label: "Button",
    referenceBlank: false,
    lanes: {
        baked: { rendered: true, render: "baked/0.png" },
        android: {
            rendered: true,
            render: "android/0.png",
            diff: "android-diff/0.png",
            mismatchPct: 1.25,
            mismatchPx: 480,
        },
        cmp: {
            rendered: true,
            render: "cmp/0.png",
            diff: "cmp-diff/0.png",
            mismatchPct: 12.5,
            mismatchPx: 9000,
        },
    },
    ...over,
});

const kinds = (steps: RowStep[]) => steps.map((s) => `${s.kind}:${s.laneId}`);

describe("band", () => {
    it("colours a number by how far off it is", () => {
        assert.equal(band(0), "good");
        assert.equal(band(1.99), "good");
        assert.equal(band(2), "ok");
        assert.equal(band(9.99), "ok");
        assert.equal(band(10), "bad");
    });

    it("never colours an absent number", () => {
        // "no reference", "no render" and a size mismatch are reasons, not scores — a green chip
        // saying "no render" would read as a pass.
        assert.equal(band(null), "na");
        assert.equal(band(undefined), "na");
    });
});

describe("percentText / sizeMismatchText", () => {
    it("quotes a mismatch to two places", () => {
        assert.equal(percentText(1.2345), "1.23%");
        assert.equal(percentText(0), "0.00%");
    });

    it("says what the two frames were, rather than that something failed", () => {
        assert.equal(
            sizeMismatchText(
                { width: 200, height: 100 },
                { width: 200, height: 120 },
            ),
            "200×100 ≠ 200×120",
        );
    });
});

describe("planRow", () => {
    it("replays the build's own numbers against the baked PNG", () => {
        // The offline run already diffed every player against the baked capture with pixelmatch:
        // exact, and free. Measuring them again in the browser would be slower AND less accurate.
        const steps = planRow(row(), LANES, "baked");
        assert.deepEqual(kinds(steps), ["chip:android", "chip:cmp"]);
        assert.deepEqual(steps[0], {
            kind: "chip",
            laneId: "android",
            text: "1.25%",
            pct: 1.25,
            px: 480,
            diff: "android-diff/0.png",
        });
    });

    it("measures in the browser when the reference is a player", () => {
        // Nothing precomputed compares two players, so this is the one number the build cannot
        // answer — and the only reason the metric exists on the client at all.
        const steps = planRow(row(), LANES, "android");
        assert.deepEqual(kinds(steps), ["measure:baked", "measure:cmp"]);
        assert.deepEqual(steps[0], {
            kind: "measure",
            laneId: "baked",
            referenceSrc: "android/0.png",
            laneSrc: "baked/0.png",
        });
    });

    it("measures against the baked lane too when the build left no diff", () => {
        const steps = planRow(
            row({
                lanes: {
                    ...row().lanes,
                    cmp: { rendered: true, render: "cmp/0.png" },
                },
            }),
            LANES,
            "baked",
        );
        assert.deepEqual(kinds(steps), ["chip:android", "measure:cmp"]);
    });

    it("stops at the reference when the reference itself produced nothing", () => {
        // Nothing can be compared against a missing image, so the row says so once rather than
        // repeating "no reference" per lane.
        const steps = planRow(
            row({ lanes: { ...row().lanes, baked: { rendered: false } } }),
            LANES,
            "baked",
        );
        assert.deepEqual(steps, [
            {
                kind: "chip",
                laneId: "baked",
                text: "no reference",
                pct: null,
                px: null,
            },
        ]);
        assert.deepEqual(planRow(row(), LANES, "missing-lane"), [
            {
                kind: "chip",
                laneId: "missing-lane",
                text: "no reference",
                pct: null,
                px: null,
            },
        ]);
    });

    it("scopes a blank baked capture to the baked lane, not the whole row", () => {
        // The short circuit that is easy to get wrong: a blank baked capture is no reference, but
        // two PLAYER renders still compare fine — killing the whole row would throw away the only
        // comparison left on exactly the documents where the baked lane failed.
        const blank = row({ referenceBlank: true });
        assert.deepEqual(kinds(planRow(blank, LANES, "baked")), [
            "chip:android",
            "chip:cmp",
        ]);
        assert.deepEqual(kinds(planRow(blank, LANES, "android")), [
            "measure:baked",
            "measure:cmp",
        ]);
    });

    it("carries a player's own reason for having no render", () => {
        const steps = planRow(
            row({
                lanes: {
                    ...row().lanes,
                    cmp: { rendered: false, note: "unsupported op: shader" },
                },
            }),
            LANES,
            "baked",
        );
        assert.deepEqual(steps[1], {
            kind: "chip",
            laneId: "cmp",
            text: "unsupported op: shader",
            pct: null,
            px: null,
        });
    });

    it("falls back to a bare reason when the player gave none", () => {
        const steps = planRow(
            row({
                lanes: { ...row().lanes, cmp: { rendered: true, render: "" } },
            }),
            LANES,
            "baked",
        );
        assert.equal(steps[1].kind === "chip" && steps[1].text, "no render");
    });

    it("says nothing at all about a lane that was not in the run", () => {
        // Distinct from a lane that ran and failed: an absent cell means this player was not part
        // of the run, and inventing a chip for it would report a gap that is not one.
        const steps = planRow(row(), [...LANES, "wasm"], "baked");
        assert.deepEqual(kinds(steps), ["chip:android", "chip:cmp"]);
    });
});

describe("referenceFrom", () => {
    const ids = ["baked", "android"];

    it("takes a reference the model actually has", () => {
        assert.equal(referenceFrom("?ref=android", ids), "android");
    });

    it("refuses anything else", () => {
        // `?ref=` is visitor-controlled and selects images and a `[data-lane="…"]` query, so an
        // unrecognised value has to fall back rather than address something.
        for (const bad of [
            "",
            "?ref=",
            "?ref=nope",
            "?ref=none",
            '?ref="]',
            "?other=1",
        ]) {
            assert.equal(referenceFrom(bad, ids), "none", bad);
        }
    });
});

describe("shortLabelOf / laneIdsOf", () => {
    const model: RcModel = {
        lanes: [
            { id: "baked", label: "Baked PNG", short: "baked" },
            { id: "android", label: "Android player", short: "android" },
        ],
        rows: [],
    };

    it("reads a lane's column heading", () => {
        assert.equal(shortLabelOf(model, "android"), "android");
        assert.deepEqual(laneIdsOf(model), ["baked", "android"]);
    });

    it("falls back to the id so an unknown lane still names itself", () => {
        assert.equal(shortLabelOf(model, "ghost"), "ghost");
    });
});
