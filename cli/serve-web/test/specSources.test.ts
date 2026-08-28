// Which source the spec lane compares against, as a table.

import assert from "node:assert/strict";

import {
    activeSource,
    changesSource,
    offersChoice,
    sourceNote,
    type SpecSource,
} from "../src/spec/sources.js";

const kit: SpecSource = {
    id: "kit",
    label: "Figma",
    src: "/m3/reference/a.png",
};
const parallel: SpecSource = {
    id: "parallel",
    label: "wear-m3-catalog",
    src: "/wear-m3-catalog/render/b.png",
    provenance:
        "wear-m3-catalog's own render, under that catalog's theme and knobs.",
};

describe("spec lane sources", () => {
    it("takes the pressed source", () => {
        assert.equal(activeSource([kit, parallel], "parallel")?.id, "parallel");
    });

    it("falls back to the first when nothing is pressed", () => {
        // Markup alone describes the initial state; a picker that arrives with nothing pressed still
        // compares against something rather than going blank.
        assert.equal(activeSource([kit, parallel], null)?.id, "kit");
    });

    it("falls back to the first when the pressed id is not one of the sources", () => {
        assert.equal(activeSource([kit, parallel], "gone")?.id, "kit");
    });

    it("has no active source when the lane has none", () => {
        assert.equal(activeSource([], "kit"), null);
    });

    it("offers no choice for the single-source lane every unpaired catalog has", () => {
        assert.equal(offersChoice([kit]), false);
        assert.equal(offersChoice([]), false);
        assert.equal(offersChoice([kit, parallel]), true);
    });

    it("treats re-picking the showing source as a no-op", () => {
        // Re-entering costs a raster request and a fresh normalisation pass.
        assert.equal(changesSource([kit, parallel], "kit", "kit"), false);
    });

    it("switches between two real sources", () => {
        assert.equal(changesSource([kit, parallel], "kit", "parallel"), true);
        assert.equal(changesSource([kit, parallel], "parallel", "kit"), true);
    });

    it("refuses a source the lane does not offer", () => {
        assert.equal(changesSource([kit, parallel], "kit", "invented"), false);
    });

    it("says nothing when the pressed default is implicit", () => {
        assert.equal(changesSource([kit, parallel], null, "kit"), false);
        assert.equal(changesSource([kit, parallel], null, "parallel"), true);
    });

    it("states the caveat for another catalog's render", () => {
        assert.match(sourceNote(parallel), /own render/);
    });

    it("gives the imported spec no caveat to state", () => {
        // A specification needs none: comparing this publish's render against this publish's spec is
        // the comparison the lane exists for.
        assert.equal(sourceNote(kit), "");
        assert.equal(sourceNote(null), "");
    });
});
