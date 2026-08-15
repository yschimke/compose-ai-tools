// The parity feed's lane filter, as a table.

import assert from "node:assert/strict";
import { filterLanes } from "../src/parity/laneFilter.js";

const LANES = ["code", "figma", "comment", "code"];

describe("filterLanes", () => {
    it("shows everything in the resting lane", () => {
        // `all` is what the server marks current, so it has to mean "no filter" — no entry ever
        // carries `all` as its own lane, so matching literally would empty the feed on load.
        const result = filterLanes(LANES, "all");
        assert.deepEqual(result.keep, [true, true, true, true]);
        assert.equal(result.shown, 4);
        assert.equal(result.empty, false);
    });

    it("keeps only the chosen lane", () => {
        assert.deepEqual(filterLanes(LANES, "code").keep, [
            true,
            false,
            false,
            true,
        ]);
        assert.equal(filterLanes(LANES, "figma").shown, 1);
    });

    it("reports empty for a lane with no activity", () => {
        const result = filterLanes(LANES, "nothing-here");
        assert.equal(result.shown, 0);
        assert.equal(result.empty, true);
    });

    it("reports empty for a feed with no entries at all", () => {
        assert.deepEqual(filterLanes([], "all"), {
            keep: [],
            shown: 0,
            empty: true,
        });
    });
});
