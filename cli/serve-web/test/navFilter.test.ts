// The component-nav filter's three exceptions, as a table.

import assert from "node:assert/strict";
import { filterNav, type NavRow } from "../src/viewer/navFilter.js";

const ROWS: NavRow[] = [
    { haystack: "Button · Filled", current: true },
    { haystack: "Button · Outlined", current: false },
    { haystack: "Switch", current: false },
];

describe("filterNav", () => {
    it("keeps everything for an empty query", () => {
        const { keep, empty } = filterNav(ROWS, "", true);
        assert.deepEqual(keep, [true, true, true]);
        assert.equal(empty, false);
    });

    it("treats whitespace as no query at all", () => {
        assert.deepEqual(filterNav(ROWS, "   ", true).keep, [true, true, true]);
    });

    it("matches on a substring, case-insensitively", () => {
        assert.deepEqual(filterNav(ROWS, "outlined", false).keep, [
            true,
            true,
            false,
        ]);
        assert.deepEqual(filterNav(ROWS, "SWITCH", false).keep, [
            true,
            false,
            true,
        ]);
    });

    it("never filters away the preview being viewed", () => {
        // Losing it would make the filter feel like a navigation — the row you are reading
        // vanishes out from under you.
        const { keep } = filterNav(ROWS, "zzz", false);
        assert.equal(keep[0], true, "the aria-current row stays");
        assert.deepEqual(keep.slice(1), [false, false]);
    });

    it("counts the pinned current-component block as something showing", () => {
        // Otherwise a query that matches only the pinned block claims nothing matched, while the
        // reader is looking straight at a match.
        const nothing: NavRow[] = [{ haystack: "Switch", current: false }];
        assert.equal(filterNav(nothing, "zzz", true).empty, false);
        assert.equal(filterNav(nothing, "zzz", false).empty, true);
    });

    it("reports the count including the pinned block", () => {
        assert.equal(filterNav(ROWS, "", true).shown, 4);
        assert.equal(filterNav(ROWS, "", false).shown, 3);
    });
});
