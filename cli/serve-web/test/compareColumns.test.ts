// Which picture leads the wall's pair, and what the column that is not the render calls itself.
//
// The failure this guards is silent by construction: both columns keep showing real pictures and
// the score keeps being right, so a reversed pair reads as a working page that has quietly swapped
// which artifact is the design and which is the render.

import assert from "node:assert/strict";
import { specLeadsColumns, targetHeadLabel } from "../src/compare/columns.js";

describe("compare wall columns", () => {
    it("puts the design spec first, and only on the lane that has one", () => {
        // The house rule everywhere the two are shown together: spec left, render right.
        assert.equal(specLeadsColumns("reference"), true);
        // `svg` and `rc` pit a render against an export OF that render — the render is the source
        // of truth there, not the thing being measured — so it keeps the left column.
        assert.equal(specLeadsColumns("svg"), false);
        assert.equal(specLeadsColumns("rc"), false);
    });

    it("names the column after the lane it is actually showing", () => {
        assert.equal(targetHeadLabel("reference", "Figma"), "Figma");
        assert.equal(targetHeadLabel("rc", "Figma"), "Remote Compose");
        assert.equal(targetHeadLabel("svg", "Figma"), "SVG");
    });

    it("falls back to the neutral name when the catalog names no design tool", () => {
        // A catalog whose references are plain PNGs/mocks has no tool to be named after, and an
        // empty header over the design column is worse than a generic one.
        assert.equal(targetHeadLabel("reference", ""), "Design reference");
    });
});
