// The compare table's row filter and status line, as a table.

import assert from "node:assert/strict";
import {
    countLabel,
    filterRows,
    statusFor,
    type FilterRow,
} from "../src/rc/rowFilter.js";

const ROWS: FilterRow[] = [
    { hay: "button filled", previewIds: "m3.ButtonFilled,m3.ButtonFilledDark" },
    { hay: "card outlined", previewIds: "m3.CardOutlined" },
    { hay: "switch on", previewIds: "m3.SwitchOn" },
];

describe("filterRows", () => {
    it("shows everything when nothing is asked for", () => {
        const result = filterRows(ROWS, "", "");
        assert.deepEqual(result.keep, [true, true, true]);
        assert.equal(result.visible, 3);
        assert.equal(result.empty, false);
    });

    it("matches the query case-insensitively, on a trimmed value", () => {
        assert.deepEqual(filterRows(ROWS, "  CARD ", "").keep, [
            false,
            true,
            false,
        ]);
    });

    it("narrows to a preview arrived from a viewer link", () => {
        assert.deepEqual(filterRows(ROWS, "", "m3.CardOutlined").keep, [
            false,
            true,
            false,
        ]);
    });

    it("composes the two rather than letting one replace the other", () => {
        // Arriving from a viewer pins the table to that preview; typing then narrows WITHIN it. A
        // query that matches another row must not drag it back into a pinned view.
        assert.deepEqual(filterRows(ROWS, "card", "m3.Button").keep, [
            false,
            false,
            false,
        ]);
        assert.deepEqual(filterRows(ROWS, "button", "m3.Button").keep, [
            true,
            false,
            false,
        ]);
    });

    it("reports empty so the table can say why it is blank", () => {
        const result = filterRows(ROWS, "nothing matches this", "");
        assert.equal(result.visible, 0);
        assert.equal(result.empty, true);
    });
});

describe("countLabel", () => {
    it("agrees with itself about one", () => {
        assert.equal(countLabel(0), "0 comparisons");
        assert.equal(countLabel(1), "1 comparison");
        assert.equal(countLabel(2), "2 comparisons");
    });
});

describe("statusFor", () => {
    it("says nothing until a reference is picked", () => {
        assert.equal(statusFor("none", ""), "");
    });

    it("names the build-time numbers as build-time", () => {
        assert.equal(
            statusFor("baked", "baked"),
            "showing the build-time pixel diffs against the baked PNG",
        );
    });

    it("warns that an in-browser number is not the same measurement", () => {
        // The two are not interchangeable — no anti-aliasing pass here — and a reader comparing a
        // browser number against a build number without knowing that is being misled.
        const status = statusFor("android", "android");
        assert.ok(
            status.includes("diffing in your browser against android"),
            status,
        );
        assert.ok(status.includes("no anti-aliasing pass"), status);
    });
});
